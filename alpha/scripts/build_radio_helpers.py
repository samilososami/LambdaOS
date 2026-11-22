#!/usr/bin/env python3
"""Reproducible Android wireless-tools build; no system-partition modifications.

The retained Nexmon aireplay/airodump executables have a hard-coded helper search list.
Relocate one exact, unused directory literal to a root-private directory. This
does NOT bypass an iwpriv check: real Android iwpriv/iwconfig binaries are built
below. Original executable remains unchanged, hashes recorded in the manifest.
"""
import hashlib
import json
import os
from pathlib import Path
import pwd
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REV = "68720406f54ff16cad28fd3c51f2392aefad9fb7"
URL = f"https://raw.githubusercontent.com/seemoo-lab/nexmon/{REV}/utilities/wireless_tools/"

def main():
    if os.geteuid() == 0:
        user = pwd.getpwnam("kali")
        os.initgroups(user.pw_name, user.pw_gid)
        os.setgid(user.pw_gid)
        os.setuid(user.pw_uid)
    out = ROOT / "out/operator-handshake/native"
    src = out / "wireless-tools-src"
    src.mkdir(parents=True, exist_ok=True)
    names = ["iwpriv.c", "iwconfig.c", "iwlib.c", "iwlib.h", "wireless.h", "COPYING"]
    for name in names:
        if not (src / name).exists():
            with urllib.request.urlopen(URL + name, timeout=30) as response:
                (src / name).write_bytes(response.read())
    cc = Path("/tools/codex/android-sdk/ndk/27.1.12297006/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang")
    for tool in ("iwpriv", "iwconfig"):
        subprocess.run([str(cc), "-O2", "-fPIE", "-pie", "-include", "strings.h", "-Wno-deprecated-non-prototype", "-I", str(src),
                        str(src / f"{tool}.c"), str(src / "iwlib.c"), "-lm", "-o", str(out / tool)], check=True)
    subprocess.run([str(cc), "-O2", "-Wall", "-Wextra", "-Werror", "-fPIE", "-pie",
                    str(ROOT / "apps/Lambda/native/radio-guard.c"), "-o", str(out / "radio-guard")], check=True)
    original = ROOT.parent / "kernel/reference/nexmon-s10/extracted/system/bin/aireplay-ng"
    binary = original.read_bytes()
    assert hashlib.sha256(binary).hexdigest() == "c937ec1445ba81e3997478c479f5feb3d4eab2f3c90673edcb073eb3363c1b41", "Unexpected Nexmon executable"
    before, after = b"/usr/local/sbin\0", b"/data/lambda-op\0"
    after = after.ljust(len(before), b"\0")
    assert len(before) == len(after) and binary.count(before) == 1
    (out / "aireplay-ng").write_bytes(binary.replace(before, after))
    airodump_input = ROOT.parent / "kernel/reference/nexmon-s10/extracted/system/bin/airodump-ng"
    airodump = airodump_input.read_bytes()
    assert hashlib.sha256(airodump).hexdigest() == "a8cccc7fca0fbd80f21024557922710398a08054255996a38eb3ea685b4fcdc0", "Unexpected airodump executable"
    assert airodump.count(before) == 1
    (out / "airodump-ng").write_bytes(airodump.replace(before, after))
    manifest = {"upstream_revision": REV, "source_url": URL, "ndk": "27.1.12297006", "api": 29,
                "patch": {"from": "/usr/local/sbin", "to": "/data/lambda-op", "purpose": "private helper lookup only"},
                "original_aireplay_sha256": hashlib.sha256(binary).hexdigest(),
                "original_airodump_sha256": hashlib.sha256(airodump).hexdigest(),
                "airodump_version": "1.2 rc4 (retained Nexmon Android arm64 port)",
                "source_sha256": {n: hashlib.sha256((src / n).read_bytes()).hexdigest() for n in names},
                "outputs": {n: hashlib.sha256((out / n).read_bytes()).hexdigest() for n in ("iwpriv", "iwconfig", "aireplay-ng", "airodump-ng", "radio-guard")}}
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))

if __name__ == "__main__":
    main()
