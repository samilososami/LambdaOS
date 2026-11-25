#!/usr/bin/env python3
"""Explicit S10 development install; no partition flashes, boot hooks or WPS exchange.

Prerequisites: prepare_wps_runtime.py, build_wps.py, build_lambda.py, signed APK.
Checks idle Core and preserves the first pre-WPS APK before stopping its worker.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'out/operator-wps'
SERIAL=os.environ.get('ANDROID_SERIAL','')
if not SERIAL: raise SystemExit('set ANDROID_SERIAL to the target device')
REMOTE='/data/local/tmp/lambda-wps'
COMMIT='3f19bb0c8b60cc7c584ea5a6e758a15e79920f99'
def adb(*args, capture=False):
    return subprocess.run(['adb','-s',SERIAL,*map(str,args)],check=True,text=True,stdout=subprocess.PIPE if capture else None).stdout
def shell(cmd):return adb('shell',cmd,capture=True).strip()
def rpc(name):
    output=shell('content call --uri content://org.lambdaproject.lambda.core --method '+name)
    return json.loads(output.split('json=',1)[1].rsplit('}]',1)[0])
def main():
    apk=OUT/'Lambda-operator-wps.apk'
    for f in [apk,OUT/'wps-guard',ROOT/'vendor/pixiewps/pixiewps',OUT/'runtime/bin/python3']:
        if not f.is_file():raise RuntimeError('Missing build output: '+str(f))
    actual=subprocess.check_output(['git','-C',str(ROOT/'vendor/oneshot-extended'),'rev-parse','HEAD'],text=True).strip()
    if actual!=COMMIT:raise RuntimeError('OneShot source revision changed')
    if shell('getprop ro.product.device')!='beyond1lte':raise RuntimeError('Unexpected device')
    if rpc('status')['active']!=0:raise RuntimeError('Core is busy; leave current work untouched')
    adb('root');adb('wait-for-device')
    if 'uid=0(' not in shell('id') or shell('getenforce')!='Enforcing':raise RuntimeError('Need root ADB with enforcing SELinux')
    checkpoint=OUT/'checkpoint';checkpoint.mkdir(exist_ok=True)
    installed=shell('pm path org.lambdaproject.lambda').removeprefix('package:')
    if not (checkpoint/'installed-before.apk').exists():adb('pull',installed,checkpoint/'installed-before.apk')
    if not (checkpoint/'worker-before.apk').exists():adb('pull','/data/local/tmp/lambda-operator-radio/worker.apk',checkpoint/'worker-before.apk')
    if rpc('status')['active']!=0:raise RuntimeError('Core became busy')
    shell('test ! -e /data/local/tmp/lambda-wps/restore.json && test ! -e /data/local/tmp/lambda-operator-radio/restore.json')
    print(shell(r'''if [ -f /data/local/tmp/lambda-operator-radio/worker.pid ]; then
pid=$(cat /data/local/tmp/lambda-operator-radio/worker.pid)
case "$pid" in *[!0-9]*|"") exit 1;; esac
if [ -r /proc/$pid/cmdline ] && tr '\000' ' ' < /proc/$pid/cmdline | grep -q org.lambdaproject.lambda.RadioWarden; then
kill -TERM "$pid"
for n in 1 2 3 4 5 6 7 8 9 10; do [ ! -d /proc/$pid ] && break; sleep 1; done
[ ! -d /proc/$pid ] || exit 1
fi
fi'''))
    shell('test ! -L '+REMOTE+'; mkdir -p '+REMOTE+'/usr '+REMOTE+'/tmp '+REMOTE+'/home; chmod 700 '+REMOTE+' '+REMOTE+'/tmp '+REMOTE+'/home')
    adb('push',str(OUT/'runtime')+'/.',REMOTE+'/usr/')
    adb('push',ROOT/'vendor/oneshot-extended',REMOTE+'/oneshot-stage')
    # Copy only audited upstream source, not the .git database or arbitrary reports.
    shell('mkdir -p '+REMOTE+'/oneshot; cp -R '+REMOTE+'/oneshot-stage/src '+REMOTE+'/oneshot/; cp '+REMOTE+'/oneshot-stage/LICENSE '+REMOTE+'/oneshot/LICENSE')
    for local,remote in [(ROOT/'apps/Lambda/wps/adapter.py','adapter.py'),(OUT/'wps-guard','wps-guard'),(ROOT/'vendor/pixiewps/pixiewps','usr/bin/pixiewps')]:adb('push',local,REMOTE+'/'+remote)
    shell('chmod 700 '+REMOTE+'/wps-guard '+REMOTE+'/usr/bin/pixiewps')
    env='export PATH='+REMOTE+'/usr/bin:/system/bin LD_LIBRARY_PATH='+REMOTE+'/usr/lib PYTHONHOME='+REMOTE+'/usr HOME='+REMOTE+'/home TMPDIR='+REMOTE+'/tmp PYTHONDONTWRITEBYTECODE=1; '
    check=json.loads(shell(env+'python3 '+REMOTE+'/adapter.py --check'))
    if not check.get('ready') or check.get('radio_started') is not False:raise RuntimeError('Preflight failed')
    manifest=dict(check,upstream_commit=COMMIT,adapter_sha256=hashlib.sha256((ROOT/'apps/Lambda/wps/adapter.py').read_bytes()).hexdigest(),guard_sha256=hashlib.sha256((OUT/'wps-guard').read_bytes()).hexdigest())
    with tempfile.TemporaryDirectory(prefix='lambda-wps-manifest-') as temp:
        path=Path(temp)/'runtime-ready.json';path.write_text(json.dumps(manifest,indent=2)+'\n');adb('push',path,REMOTE+'/runtime-ready.json')
    adb('install','--no-incremental','-r',apk)
    shell('chmod 644 /data/local/tmp/lambda-operator-radio/worker.apk')
    adb('push',apk,'/data/local/tmp/lambda-operator-radio/worker.apk')
    shell('chmod 444 /data/local/tmp/lambda-operator-radio/worker.apk; CLASSPATH=/data/local/tmp/lambda-operator-radio/worker.apk nohup setsid app_process /system/bin org.lambdaproject.lambda.RadioWarden > /data/local/tmp/lambda-operator-radio/worker.log 2>&1 < /dev/null &')
    print(json.dumps({'installed':str(apk),'radio_test_started':False,'adb_root':'temporarily enabled for QA; unroot afterwards'}))
if __name__=='__main__':main()
