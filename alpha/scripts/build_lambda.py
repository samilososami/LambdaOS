#!/usr/bin/env python3
"""Build the standalone Lambda development APK with the shared Android SDK."""
import hashlib
import json
import os
from pathlib import Path
import pwd
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get("LAMBDA_ANDROID_SDK", "/tools/codex/android-sdk"))
JDK = Path(os.environ.get("LAMBDA_JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64"))
TOOLS = SDK / "build-tools/35.0.0"
PLATFORM = SDK / "platforms/android-35/android.jar"
APP = ROOT / "apps/Lambda"


def run(*args, **kwargs):
    env = dict(os.environ, JAVA_HOME=str(JDK))
    subprocess.run([str(arg) for arg in args], check=True, env=env, **kwargs)


def main():
    if os.geteuid() == 0:
        account = pwd.getpwnam("kali")
        os.initgroups(account.pw_name, account.pw_gid)
        os.setgid(account.pw_gid)
        os.setuid(account.pw_uid)
    for required in [TOOLS / "aapt2", TOOLS / "d8", TOOLS / "apksigner", TOOLS / "zipalign", PLATFORM, JDK / "bin/javac"]:
        if not required.is_file():
            raise SystemExit(f"Missing build dependency: {required}")

    out = ROOT / "out"
    out.mkdir(exist_ok=True)
    private = ROOT / "private"
    private.mkdir(mode=0o700, exist_ok=True)
    key = private / "lambda-development.p12"
    if not key.exists():
        run(JDK / "bin/keytool", "-genkeypair", "-keystore", key,
            "-storetype", "PKCS12", "-storepass", "android", "-keypass", "android",
            "-alias", "lambda-dev", "-keyalg", "RSA", "-keysize", "3072",
            "-validity", "3650", "-dname", "CN=LambdaOS Development, O=Lambda Project")
        key.chmod(0o600)

    target = out / "lambda-0.1.0-alpha.1-dev.apk"
    with tempfile.TemporaryDirectory(prefix="lambda-build-", dir=out) as scratch:
        work = Path(scratch)
        classes = work / "classes"
        dex = work / "dex"
        generated = work / "generated"
        for folder in [classes, dex, generated]:
            folder.mkdir()
        run(TOOLS / "aapt2", "compile", "--dir", APP / "res", "-o", work / "resources.zip")
        run(TOOLS / "aapt2", "link", "-o", work / "base.apk", "-I", PLATFORM,
            "--manifest", APP / "AndroidManifest.xml", "--java", generated,
            "--min-sdk-version", "29", "--target-sdk-version", "35", work / "resources.zip")
        sources = sorted(APP.glob("src/**/*.java")) + sorted(generated.glob("**/*.java"))
        run(JDK / "bin/javac", "--release", "8", "-encoding", "UTF-8", "-classpath", PLATFORM,
            "-d", classes, *sources)
        run(JDK / "bin/jar", "--create", "--file", work / "classes.jar", "-C", classes, ".")
        run(TOOLS / "d8", "--release", "--min-api", "29", "--lib", PLATFORM,
            "--output", dex, work / "classes.jar")
        with zipfile.ZipFile(work / "base.apk", "a") as apk:
            for file in sorted(dex.glob("*.dex")):
                info = zipfile.ZipInfo(file.name, date_time=(2026, 10, 24, 0, 0, 0))
                info.compress_type = zipfile.ZIP_STORED
                info.external_attr = 0o644 << 16
                apk.writestr(info, file.read_bytes())
        run(TOOLS / "zipalign", "-p", "-f", "4", work / "base.apk", work / "aligned.apk")
        run(TOOLS / "apksigner", "sign", "--ks", key, "--ks-key-alias", "lambda-dev",
            "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", target, work / "aligned.apk")
    run(TOOLS / "apksigner", "verify", "--verbose", target)
    run(TOOLS / "zipalign", "-c", "4", target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    report = {"artifact": target.name, "sha256": digest, "bytes": target.stat().st_size,
              "type": "standalone-development-apk", "rom": False, "device_tested": False,
              "version": "0.1.0-alpha.1", "sdk": 35, "build_tools": "35.0.0"}
    (out / "lambda-build.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
