#!/usr/bin/env python3
"""Build OneShot guard and a separate non-radio Android contract-test DEX."""
import os
import pwd
from pathlib import Path
import subprocess
import tempfile
import importlib.util
import zipfile
ROOT=Path(__file__).resolve().parents[1]
def run(*args):subprocess.run([str(x) for x in args],check=True)
def main():
    if os.geteuid()==0:
        account=pwd.getpwnam('kali');os.initgroups('kali',account.pw_gid);os.setgid(account.pw_gid);os.setuid(account.pw_uid)
    out=ROOT/'out/operator-wps';out.mkdir(exist_ok=True)
    sdk=Path('/tools/codex/android-sdk');tools=sdk/'build-tools/35.0.0';platform=sdk/'platforms/android-35/android.jar'
    clang=sdk/'ndk/28.1.13356709/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang'
    run(clang,'-O2','-Wall','-Wextra','-Wno-unused-parameter',ROOT/'apps/Lambda/native/wps-guard.c','-o',out/'wps-guard')
    with tempfile.TemporaryDirectory(prefix='wps-tests-',dir=out) as temp:
        tmp=Path(temp);generated=tmp/'generated';classes=tmp/'classes';generated.mkdir();classes.mkdir()
        run(tools/'aapt2','compile','--dir',ROOT/'apps/Lambda/res','-o',tmp/'res.zip')
        run(tools/'aapt2','link','-o',tmp/'test.apk','-I',platform,'--manifest',ROOT/'apps/Lambda/AndroidManifest.xml','--java',generated,tmp/'res.zip')
        run('/usr/lib/jvm/java-21-openjdk-amd64/bin/javac','--release','8','-classpath',platform,'-d',classes,*sorted((ROOT/'apps/Lambda/src').rglob('*.java')),*generated.rglob('*.java'),ROOT/'tests/WpsContractTest.java')
        run('/usr/lib/jvm/java-21-openjdk-amd64/bin/jar','--create','--file',tmp/'classes.jar','-C',classes,'.')
        run(tools/'d8','--min-api','29','--lib',platform,'--output',out/'wps-contract-tests.jar',tmp/'classes.jar')
        # Separate instrumentation DEX; no test paths in the production APK.
        previews=tmp/'previews';previews.mkdir();dex=tmp/'preview-dex';dex.mkdir()
        run('/usr/lib/jvm/java-21-openjdk-amd64/bin/javac','--release','8','-classpath',str(platform)+os.pathsep+str(classes),'-d',previews,ROOT/'tests/WpsUiPreview.java')
        run('/usr/lib/jvm/java-21-openjdk-amd64/bin/jar','--create','--file',tmp/'preview.jar','-C',previews,'.')
        run(tools/'d8','--min-api','29','--lib',platform,'--classpath',tmp/'classes.jar','--output',dex,tmp/'preview.jar')
        run(tools/'aapt2','link','-o',tmp/'preview.apk','-I',platform,'--manifest',ROOT/'tests/wps-ui-manifest.xml')
        with zipfile.ZipFile(tmp/'preview.apk','a') as apk:apk.write(dex/'classes.dex','classes.dex')
        run(tools/'zipalign','-f','4',tmp/'preview.apk',out/'wps-ui-unsigned.apk')
        run('node',ROOT/'scripts/sign_operator.mjs',out/'wps-ui-unsigned.apk',out/'wps-ui-tests.apk')
if __name__=='__main__':main()
