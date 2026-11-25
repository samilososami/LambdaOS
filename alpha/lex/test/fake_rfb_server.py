#!/usr/bin/env python3
"""Minimal RFB 3.8 + VncAuth server for the LexRfbClient host test.

Sends one 64x48 Hextile framebuffer update, records every client message, and
writes a JSON report when the client disconnects. The VncAuth response is
checked against a precomputed DES value, so no crypto library is needed; the
test password never appears in the report.
"""
import json
import os
import socket
import struct
import sys

CHALLENGE = bytes(range(16))
# DES-ECB(CHALLENGE) for password "vnc-test", key bytes bit-reversed. Computed
# independently with OpenSSL.
EXPECTED_RESPONSE = bytes.fromhex("6462c8f87dc31b5642d39beecb016a32")


def u16(value):
    return struct.pack(">H", value)


def u32(value):
    return struct.pack(">I", value)


def pixel(rgb):
    r, g, b = rgb
    return bytes((b, g, r, 0))


def hextile_update():
    """64x48 Hextile rectangle: solid, raw, coloured and uncoloured tiles."""
    tiles = []
    tiles.append(bytes([2]) + pixel((255, 0, 0)))                       # solid red
    tiles.append(bytes([1]) + pixel((0, 255, 0)) * 256)                 # raw green
    tiles.append(bytes([2 | 8 | 16]) + pixel((40, 40, 40)) + bytes([1])
                 + pixel((255, 255, 0)) + bytes([0x00, 0xFF]))          # coloured
    tiles.append(bytes([2 | 4 | 8]) + pixel((0, 0, 255)) + pixel((255, 255, 255))
                 + bytes([1, 0x00, 0xFF]))                              # uncoloured
    tiles.append(bytes([2]) + pixel((255, 0, 255)))                     # solid magenta
    tiles.append(bytes([0]))                                            # reuse bg
    tiles.append(bytes([2]) + pixel((0, 255, 255)))                     # solid cyan
    tiles.append(bytes([2]) + pixel((255, 128, 0)))                     # solid orange
    tiles.append(bytes([2]) + pixel((80, 80, 80)))                      # solid grey
    tiles.append(bytes([2 | 8 | 16]) + pixel((0, 0, 0)) + bytes([1])
                 + pixel((0, 255, 255))
                 + bytes([(2 << 4) | 2, ((4 - 1) << 4) | (3 - 1)]))      # coloured subrect
    tiles.append(bytes([2 | 4 | 8]) + pixel((255, 128, 0)) + pixel((0, 255, 0))
                 + bytes([2, 0x00, 0x77, ((8 << 4) | 8), 0x77]))        # uncoloured subrects
    tiles.append(bytes([2]) + pixel((40, 40, 40)))                      # solid grey
    payload = b"".join(tiles)
    return (bytes([0, 0]) + u16(1) + u16(0) + u16(0) + u16(64) + u16(48)
            + struct.pack(">i", 5) + payload)


def read_exact(conn, count):
    data = b""
    while len(data) < count:
        part = conn.recv(count - len(data))
        if not part:
            raise EOFError("client closed early")
        data += part
    return data


def run(conn, report):
    version = read_exact(conn, 12)
    if version != b"RFB 003.008\n":
        raise ValueError("unexpected client version %r" % version)
    conn.sendall(b"RFB 003.008\n")
    conn.sendall(bytes([1, 2]))                       # one type: VncAuth
    if read_exact(conn, 1) != b"\x02":
        raise ValueError("client did not choose VncAuth")
    conn.sendall(CHALLENGE)
    if read_exact(conn, 16) != EXPECTED_RESPONSE:
        raise ValueError("wrong VncAuth response")
    report["auth_ok"] = True
    conn.sendall(u32(0))                              # SecurityResult OK
    if read_exact(conn, 1) != b"\x01":
        raise ValueError("ClientInit was not shared")
    name = b"lex-rfb-test"
    pixel_format = (bytes([32, 24, 0, 1]) + u16(255) + u16(255) + u16(255)
                    + bytes([16, 8, 0]) + bytes(3))
    conn.sendall(u16(64) + u16(48) + pixel_format + u32(len(name)) + name)
    sent_update = False
    while True:
        try:
            message_type = read_exact(conn, 1)[0]
        except EOFError:
            break
        if message_type == 0:                         # SetPixelFormat
            report["pixel_format"] = read_exact(conn, 19)[3:19].hex()
        elif message_type == 2:                       # SetEncodings
            head = read_exact(conn, 3)
            count = struct.unpack(">H", head[1:3])[0]
            raw = read_exact(conn, 4 * count)
            report["encodings"] = [struct.unpack(">i", raw[i:i + 4])[0]
                                   for i in range(0, len(raw), 4)]
        elif message_type == 3:                       # FramebufferUpdateRequest
            data = read_exact(conn, 9)
            incremental, x, y, width, height = struct.unpack(">BHHHH", data)
            report["fb_requests"].append([incremental, x, y, width, height])
            if not sent_update:
                if incremental != 0 or (x, y, width, height) != (0, 0, 64, 48):
                    raise ValueError("unexpected first update request")
                conn.sendall(hextile_update())
                sent_update = True
        elif message_type == 5:                       # PointerEvent
            report["pointer_events"].append((b"\x05" + read_exact(conn, 5)).hex())
        elif message_type == 4:                       # KeyEvent
            report["key_events"].append((b"\x04" + read_exact(conn, 7)).hex())
        else:
            raise ValueError("unexpected client message type %d" % message_type)


def main():
    report_path, port_path = sys.argv[1], sys.argv[2]
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", 0))
    server.listen(1)
    server.settimeout(10)
    with open(port_path, "w", encoding="ascii") as handle:
        handle.write(str(server.getsockname()[1]))
    report = {"auth_ok": False, "pixel_format": "", "encodings": [],
              "fb_requests": [], "pointer_events": [], "key_events": []}
    error = None
    try:
        conn, _ = server.accept()
        conn.settimeout(10)
        try:
            run(conn, report)
        finally:
            conn.close()
    except Exception as exc:                          # noqa: BLE001 - report it
        error = exc
    finally:
        server.close()
        temporary = report_path + ".tmp"
        with open(temporary, "w", encoding="utf-8") as handle:
            json.dump(report, handle, separators=(",", ":"))
        os.replace(temporary, report_path)
    if error is not None:
        raise SystemExit("fake RFB server: %s" % error)


if __name__ == "__main__":
    main()
