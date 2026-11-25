#!/usr/bin/env python3
"""Read-only service checks plus rejected requests; never starts a WPS exchange."""
import json
import os
import shlex
import subprocess
import uuid
SERIAL=os.environ.get('ANDROID_SERIAL','')
if not SERIAL: raise SystemExit('set ANDROID_SERIAL to the target device')
def shell(cmd):return subprocess.check_output(['adb','-s',SERIAL,'shell',cmd],text=True).strip()
def rpc(method,arg=None):
    cmd='content call --uri content://org.lambdaproject.lambda.core --method '+method
    if arg is not None:cmd+=' --arg '+shlex.quote(json.dumps(arg))
    out=shell(cmd);return json.loads(out.split('json=',1)[1].rsplit('}]',1)[0])
def main():
    before=rpc('status');assert before['active']==0
    assert before['version']=='0.2.0-operator.4' and 'wps.oneshot' in before['tools']
    status=rpc('radio_status');assert status['available'] and status['wps']['provisioned']
    base={'snapshot_id':'missing-wps-fixture','bssid':'02:00:00:00:00:01','mode':'pixie','authorized':True}
    cases=[('consent',dict(base,authorized=False),'target_authorization_required'),
           ('PIN checksum',dict(base,mode='pin',pin='12345678'),'invalid_wps_pin_checksum'),
           ('source receipt',base,'job_not_found'),
           ('command injection',dict(base,command='id'),'unknown_field: command')]
    for label,args,error in cases:
        r=rpc('submit',{'tool':'wps.oneshot','args':args,'request_id':str(uuid.uuid4())})
        assert r.get('ok') is False and r.get('detail')==error,(label,r)
        print('PASS IPC rejection: '+label)
    assert rpc('status')['active']==0
    print(json.dumps({'version':before['version'],'worker_available':True,'wps_provisioned':True,
                      'active_jobs':0,'selinux':shell('getenforce'),'wifi_on':shell('settings get global wifi_on'),
                      'active_radio_verified':False,'wps_exchanges_started':0}))
if __name__=='__main__':main()
