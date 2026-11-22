#!/usr/bin/env python3
"""Pinned ARM64 CPU benchmark build. Shared artifacts work from kali and root."""
import hashlib
import json
import os
from pathlib import Path
import pwd
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "out/cracking-speed"
NDK = Path("/tools/codex/android-sdk/ndk/27.1.12297006")
BIN = NDK / "toolchains/llvm/prebuilt/linux-x86_64/bin"
SOURCES = {
    "openssl-3.5.9": ("https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz", "603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a"),
    "aircrack-ng-1.7": ("https://download.aircrack-ng.org/aircrack-ng-1.7.tar.gz", "05a704e3c8f7792a17315080a21214a4448fd2452c1b0dd5226a3a55f90b58c3"),
}

def main():
    if os.geteuid() == 0:
        user = pwd.getpwnam("kali")
        os.initgroups(user.pw_name, user.pw_gid)
        os.setgid(user.pw_gid)
        os.setuid(user.pw_uid)
    vendor = OUT / "vendor"
    vendor.mkdir(parents=True, exist_ok=True)
    for name, (url, digest) in SOURCES.items():
        archive = vendor / (name + ".tar.gz")
        if not archive.exists():
            with urllib.request.urlopen(url, timeout=60) as response:
                archive.write_bytes(response.read())
        if hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
            raise RuntimeError("Source checksum mismatch: " + name)
        if not (vendor / name).exists():
            with tarfile.open(archive) as tar:
                tar.extractall(vendor, filter="data")
    env = dict(os.environ, ANDROID_NDK_ROOT=str(NDK), PATH=str(BIN)+":"+os.environ["PATH"])
    crypto = vendor / "openssl-3.5.9"
    prefix = OUT / "openssl-install"
    with (OUT / "build.log").open("a") as log:
        def run(args, cwd, overrides=None):
            print("BUILD:", " ".join(map(str, args)), flush=True)
            log.write("\n" + " ".join(map(str, args)) + "\n"); log.flush()
            subprocess.run(list(map(str, args)), cwd=cwd, env=dict(env, **(overrides or {})), stdout=log, stderr=subprocess.STDOUT, check=True)
        if not (prefix / "lib/libcrypto.a").exists():
            run(["./Configure", "android-arm64", "-D__ANDROID_API__=29", "no-shared", "no-tests", "no-apps", "no-docs", "--libdir=lib", "--prefix="+str(prefix)], crypto)
            run(["make", "-j6", "build_libs"], crypto)
            run(["make", "install_dev"], crypto)
        source = vendor / "aircrack-ng-1.7"
        if not (source / "configure").exists():
            run(["autoreconf", "-fi"], source)
        cross = dict(CC=str(BIN / "aarch64-linux-android29-clang"), CXX=str(BIN / "aarch64-linux-android29-clang++"),
                     AR=str(BIN / "llvm-ar"), RANLIB=str(BIN / "llvm-ranlib"), STRIP=str(BIN / "llvm-strip"),
                     PKG_CONFIG_LIBDIR=str(prefix / "lib/pkgconfig"), CFLAGS="-O3 -fPIE -Wno-deprecated-declarations", CXXFLAGS="-O3 -fPIE",
                     CPPFLAGS="-I"+str(prefix / "include"), LDFLAGS="-L"+str(prefix / "lib")+" -pie")
        if not (source / "Makefile").exists():
            run(["./configure", "--host=aarch64-linux-android", "--disable-shared", "--enable-static", "--with-static-simd=arm-neon",
                 "--disable-libnl", "--without-libpcre", "--without-libpcre2", "--without-sqlite3", "--without-hwloc"], source, cross)
        run(["make", "-j6", "aircrack-ng"], source, cross)
        target = OUT / "aircrack-ng"
        target.write_bytes((source / "aircrack-ng").read_bytes())
        target.chmod(0o755)
        run([BIN / "llvm-strip", target], OUT)
    report = {"sources": SOURCES, "ndk": NDK.name, "api": 29, "simd": "arm-neon", "binary_sha256": hashlib.sha256(target.read_bytes()).hexdigest()}
    (OUT / "build-manifest.json").write_text(json.dumps(report, indent=2)+"\n")
    print(json.dumps(report, indent=2))

if __name__ == "__main__":
    main()
