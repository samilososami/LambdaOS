#!/usr/bin/env python3
"""Generate reproducible offline WPA2/PMKID fixtures. Never captures radio traffic."""
import hashlib
import hmac
import json
import os
from pathlib import Path
import pwd
import struct
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "out/cracking-speed/fixtures"
URL = "https://raw.githubusercontent.com/berzerk0/Probable-Wordlists/master/Real-Passwords/WPA-Length/Top4800-WPA-probable-v2.txt"
SOURCE_SHA256 = "5dc33214cad9a11eb926de00f5ded20b1a5fc12ba3332319e054713265a35c51"
SSID = b"LambdaBenchmark"
PASSWORD = b"LambdaBench2026!"  # synthetic, deliberately absent from the quick list
AP = bytes.fromhex("021122334455")
STA = bytes.fromhex("02aabbccddee")

def main():
    if os.geteuid() == 0:
        user = pwd.getpwnam("kali")
        os.initgroups(user.pw_name, user.pw_gid); os.setgid(user.pw_gid); os.setuid(user.pw_uid)
    OUT.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(URL, timeout=30) as r:
        original = r.read()
    if hashlib.sha256(original).hexdigest() != SOURCE_SHA256:
        raise RuntimeError("Wordlist changed upstream; review before accepting a new version")
    words = original.splitlines()
    assert len(words) == len(set(words)) == 4800
    assert all(8 <= len(w) <= 15 and all(32 <= c <= 126 for c in w) for w in words)
    assert PASSWORD not in words
    (OUT / "quick-4800.txt").write_bytes(b"\n".join(words)+b"\n")
    (OUT / "selftest.txt").write_bytes(PASSWORD+b"\n")
    pmk = hashlib.pbkdf2_hmac("sha1", PASSWORD, SSID, 4096, 32)
    pmkid = hmac.digest(pmk, b"PMK Name"+AP+STA, "sha1")[:16]
    fields = [pmkid.hex(), AP.hex(), STA.hex(), SSID.hex()]
    (OUT / "pmkid.txt").write_text("*".join(fields)+"\n")
    (OUT / "pmkid.22000").write_text("WPA*01*"+"*".join(fields)+"***\n")
    anonce, snonce = bytes(range(32)), bytes(range(32,64))
    context = min(AP,STA)+max(AP,STA)+min(anonce,snonce)+max(anonce,snonce)
    kck = hmac.digest(pmk, b"Pairwise key expansion\0"+context+b"\0", "sha1")[:16]
    def eapol(info, replay, nonce, sign):
        body = b"\x02"+struct.pack(">HHQ",info,16,replay)+nonce+bytes(16+8+8+16)+b"\0\0"
        packet = b"\x02\x03"+struct.pack(">H",len(body))+body
        if sign:
            mic = hmac.digest(kck, packet, "sha1")[:16]
            packet = packet[:81]+mic+packet[97:]
        return packet
    rsn = bytes.fromhex("0100000fac040100000fac040100000fac020000")
    beacon = struct.pack("<HH",0x0080,0)+b"\xff"*6+AP+AP+bytes(2)+bytes(8)+struct.pack("<HH",100,0x11)
    beacon += bytes([0,len(SSID)])+SSID+b"\x01\x01\x82\x03\x01\x06"+bytes([48,len(rsn)])+rsn
    frames = [beacon]
    m2 = eapol(0x010a,1,snonce,True)
    for i, (down, payload) in enumerate([(True,eapol(0x008a,1,anonce,False)),(False,m2),(True,eapol(0x03ca,2,anonce,True)),(False,eapol(0x030a,2,bytes(32),True))]):
        header=struct.pack("<HH",0x0208 if down else 0x0108,0)+(STA+AP+AP if down else AP+STA+AP)+struct.pack("<H",(i+1)<<4)
        frames.append(header+bytes.fromhex("aaaa03000000888e")+payload)
    pcap=struct.pack("<IHHIIII",0xa1b2c3d4,2,4,0,0,65535,105)
    for i,frame in enumerate(frames):
        pcap+=struct.pack("<IIII",1700000000,i*1000,len(frame),len(frame))+frame
    (OUT / "wpa.cap").write_bytes(pcap)
    zero_m2=m2[:81]+bytes(16)+m2[97:]
    (OUT / "wpa.22000").write_text("WPA*02*"+"*".join([m2[81:97].hex(),AP.hex(),STA.hex(),SSID.hex(),anonce.hex(),zero_m2.hex(),"00"])+"\n")
    report={"synthetic":True,"ssid":SSID.decode(),"candidates":4800,"min_length":8,"max_length":15,
            "wordlist_source":URL,"source_sha256":hashlib.sha256(original).hexdigest(),"wordlist_license":"CC-BY-SA-4.0",
            "sha256":{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(OUT.iterdir()) if p.is_file() and p.name!="manifest.json"}}
    (OUT / "manifest.json").write_text(json.dumps(report,indent=2)+"\n")
    print(json.dumps(report,indent=2))

if __name__ == "__main__": main()
