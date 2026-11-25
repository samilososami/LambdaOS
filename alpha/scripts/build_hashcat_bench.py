#!/usr/bin/env python3
"""Pinned Android CPU/GPU test tooling. No device mutations."""
import hashlib
import json
import os
from pathlib import Path
import pwd
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "out/cracking-speed"
NDK = Path("/tools/codex/android-sdk/ndk/27.1.12297006")
BIN = NDK / "toolchains/llvm/prebuilt/linux-x86_64/bin"
URL = "https://github.com/hashcat/hashcat/archive/refs/tags/v8.0.0.tar.gz"
SHA = "6281721ef2bc57aed1ffa1a186aa39d01cee09899741cb58e027733a373c2ae3"

def main():
    if os.geteuid() == 0:
        u = pwd.getpwnam("kali")
        os.initgroups(u.pw_name, u.pw_gid); os.setgid(u.pw_gid); os.setuid(u.pw_uid)
    vendor = OUT / "vendor"; vendor.mkdir(parents=True, exist_ok=True)
    archive = vendor / "hashcat-8.0.0.tar.gz"
    if not archive.exists():
        with urllib.request.urlopen(URL, timeout=60) as r: archive.write_bytes(r.read())
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA: raise RuntimeError("Source hash mismatch")
    source = vendor / "hashcat-8.0.0"
    if not source.exists():
        with tarfile.open(archive) as t: t.extractall(vendor, filter="data")
    cc = BIN / "aarch64-linux-android29-clang"
    with (OUT / "hashcat-build.log").open("a") as log:
        subprocess.run(["make", "-j6", "UNAME=Android", "IS_AARCH64=1", f"CC={cc}",
            f"CXX={BIN}/aarch64-linux-android29-clang++", f"AR={BIN}/llvm-ar",
            "ENABLE_BRAIN=0", "ENABLE_CUBIN=0", "LFLAGS_NATIVE=-ldl -lm",
            "INSTALL_FOLDER=/data/local/tmp/lambda-crack-bench/gpu", "SHARED_FOLDER=/data/local/tmp/lambda-crack-bench/gpu",
            "hashcat", "modules/module_00000.so", "modules/module_22000.so", "feeds/feed_wordlist.so"],
            cwd=source, stdout=log, stderr=subprocess.STDOUT, check=True)
    for name in ["benchmark-guard", "opencl-probe"]:
        subprocess.run([str(cc), "-O2", "-Wall", "-Wextra", "-fPIE", "-pie",
            str(ROOT / f"apps/Lambda/native/{name}.c"), "-ldl", "-o", str(OUT / name)], check=True)
    shutil.copy2(NDK / "toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so", OUT)
    manifest = {"source": URL, "sha256": SHA, "version": "8.0.0", "ndk": NDK.name,
        "android_api": 29, "mode": 22000, "device_memory_limit_mib": 768,
        "binary_sha256": hashlib.sha256((source / "hashcat").read_bytes()).hexdigest()}
    (OUT / "hashcat-build-manifest.json").write_text(json.dumps(manifest, indent=2)+"\n")
    notices = OUT / "notices"; notices.mkdir(exist_ok=True)
    for filename, original in {
        "Aircrack-ng-LICENSE": vendor / "aircrack-ng-1.7/LICENSE",
        "OpenSSL-LICENSE.txt": vendor / "openssl-3.5.9/LICENSE.txt",
        "Hashcat-LICENSE.txt": source / "docs/license.txt",
        "NDK-LLVM-NOTICE": NDK / "toolchains/llvm/prebuilt/linux-x86_64/NOTICE",
        "ATTRIBUTION.md": ROOT / "docs/cracking-benchmark-third-party.md",
    }.items():
        if original.is_file(): shutil.copy2(original, notices / filename)
    shutil.copytree(source / "docs/license_libs", notices / "hashcat-dependencies", dirs_exist_ok=True)
    print(json.dumps(manifest, indent=2))

if __name__ == "__main__": main()
