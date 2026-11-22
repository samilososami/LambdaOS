#!/usr/bin/env python3
"""No RF: synthetic supplicant socket/events exercise the actual OneShot parser."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import time
from unittest.mock import patch

ALPHA=Path(__file__).resolve().parents[1]
ADAPTER=ALPHA/'apps/Lambda/wps/adapter.py'
spec=importlib.util.spec_from_file_location('adapter',ADAPTER)
adapter=importlib.util.module_from_spec(spec);spec.loader.exec_module(adapter)
REQUEST=dict(interface='wlan0',bssid='02:00:00:00:00:01',mode='pin',pin='12345670')
FAKE='''#!/usr/bin/env python3
import os,sys,socket
conf=next(a[2:] for a in sys.argv if a.startswith('-c'))
folder=next(l.split('=',1)[1].strip() for l in open(conf) if l.startswith('ctrl_interface='))
s=socket.socket(socket.AF_UNIX,socket.SOCK_DGRAM);s.bind(folder+'/wlan0')
case=os.environ['WPS_FIXTURE']
while True:
 data,addr=s.recvfrom(4096)
 if data.startswith(b'WPS_CANCEL'):continue
 s.sendto(b'FAIL' if case=='reject' else b'OK',addr)
 if case=='reject':continue
 if case=='m2d':print('WPS: Received M2D',flush=True);continue
 if case=='early-nack':print('WPS: Received WSC_NACK',flush=True);continue
 if case=='locked':print('wlan0: Associated with 02:00:00:00:00:01\\nWPS: Received WSC_NACK\\nwlan0: WPS-FAIL msg=8 config_error=15',flush=True);continue
 if case=='unbound-lock':print('wlan0: WPS-FAIL msg=8 config_error=15',flush=True);continue
 if case=='pin-fail':print('wlan0: Associated with 02:00:00:00:00:01\\nWPS: Received M3\\nWPS: Building Message M4\\nWPS: Received WSC_NACK\\nwlan0: WPS-FAIL msg=8 config_error=18',flush=True);continue
 if case=='telemetry':print('WPS: Received M1\\nWPS: Building Message M2\\nWPS: Received M3\\nWPS: Received M3',flush=True)
 if case=='pbc':
  assert data==b'WPS_PBC 02:00:00:00:00:01'
  print('wlan0: WPS-TIMEOUT',flush=True);continue
 if case=='nack':print('WPS: Building Message M3\\nWPS: Received WSC_NACK',flush=True);continue
 mac='02:00:00:00:00:02' if case=='mismatch' else '02:00:00:00:00:01'
 print('wlan0: Associated with '+mac,flush=True)
 print('WPS: Network Key - hexdump(len=8): 74 65 73 74 31 32 33 34',flush=True)
 if case=='no-success':print('wlan0: WPS-FAIL',flush=True)
 else:print('wlan0: WPS-SUCCESS',flush=True)
'''

class Validation(unittest.TestCase):
    def test_valid(self):self.assertEqual(adapter.validate(dict(REQUEST)),REQUEST)
    def test_bad_requests(self):
        for patch in ({'bssid':'ff:ff:ff:ff:ff:ff'},{'bssid':'00:00:00:00:00:00'},
                      {'bssid':'02:00:00:00:00:01;id'},{'interface':'wlan1'},
                      {'pin':'12345678'},{'pin':'1234'},{'mode':'bruteforce'},
                      {'mode':'pbc'},{'extra':'anything'},{'pin':12345670}):
            with self.subTest(patch=patch),self.assertRaises((ValueError,TypeError)):
                adapter.validate(dict(REQUEST,**patch))
    def test_no_pin_modes(self):
        for mode in ('pixie','pbc'):adapter.validate(dict(REQUEST,mode=mode,pin=''))
    def test_brute_options(self):
        req=dict(REQUEST,mode='bruteforce',pin='',max_attempts=10,start_prefix='0000')
        adapter.validate(req)
        for changes in ({'max_attempts':0},{'max_attempts':10000},{'max_attempts':True},{'max_attempts':10.0},{'start_prefix':'00'},{'start_prefix':1234},{'start_prefix':'１２３４'}):
            with self.subTest(changes=changes),self.assertRaises(ValueError):adapter.validate(dict(req,**changes))

class Protocol(unittest.TestCase):
    def fixture(self,case,request=None):
        with tempfile.TemporaryDirectory(prefix='lambda-wps-test-') as tmp:
            root=Path(tmp);(root/'bin').mkdir();(root/'temp').mkdir()
            shutil.copyfile(ADAPTER,root/'adapter.py')
            (root/'oneshot').symlink_to(ALPHA/'vendor/oneshot-extended',target_is_directory=True)
            fake=root/'bin/wpa_supplicant';fake.write_text(FAKE);fake.chmod(0o755)
            env=dict(os.environ,PATH=str(root/'bin')+os.pathsep+os.environ['PATH'],TMPDIR=str(root/'temp'),HOME=tmp,WPS_FIXTURE=case,PYTHONDONTWRITEBYTECODE='1')
            # Test invokes the adapter with a mocked effective UID; no privileges needed.
            code="import os,runpy;os.getuid=lambda:0;runpy.run_path('adapter.py',run_name='__main__')"
            r=subprocess.run([sys.executable,'-c',code],cwd=root,env=env,input=json.dumps(request or REQUEST)+'\n',capture_output=True,text=True,timeout=8)
            events=[json.loads(line) for line in r.stdout.splitlines()]
            self.assertFalse(r.stderr,r.stderr)
            self.assertFalse(list((root/'temp').iterdir()),'private socket/config cleanup')
            return r.returncode,events
    def test_confirmed(self):
        code,events=self.fixture('success');self.assertEqual(code,0)
        self.assertTrue(events[-1]['result']['protocol_confirmed'])
        self.assertEqual(events[-1]['result']['password'],'test1234')
        self.assertTrue(all('test1234' not in json.dumps(e) for e in events[:-1]))
    def test_wrong_target(self):self.assertEqual(self.fixture('mismatch')[1][-1],{'error':'target_mismatch'})
    def test_locked(self):self.assertEqual(self.fixture('locked')[1][-1]['result']['reason'],'wps_locked')
    def test_m2d_not_lock(self):
        result=self.fixture('m2d')[1][-1]['result'];self.assertEqual(result['reason'],'negotiation_deferred');self.assertEqual(result['telemetry']['lock_status'],'unknown')
    def test_early_nack_not_lock(self):self.assertEqual(self.fixture('early-nack')[1][-1]['result']['reason'],'negotiation_rejected')
    def test_lock_requires_target_association(self):self.assertNotEqual(self.fixture('unbound-lock')[1][-1]['result']['reason'],'wps_locked')
    def test_pin_failure(self):self.assertEqual(self.fixture('pin-fail')[1][-1]['result']['reason'],'pin_rejected')
    def test_telemetry(self):
        result=self.fixture('telemetry')[1][-1]['result'];p=result['telemetry']
        self.assertEqual(p['message_mask'],7);self.assertEqual(p['frames'],4);self.assertEqual(p['retransmissions'],1)
    def test_pbc_wait(self):
        events=self.fixture('pbc',dict(REQUEST,mode='pbc',pin=''))[1]
        self.assertIn('wps_wait_button',[e.get('phase') for e in events]);self.assertEqual(events[-1]['result']['reason'],'wps_timeout')
    def test_rejected(self):self.assertEqual(self.fixture('reject')[1][-1],{'error':'wps_command_rejected'})
    def test_missing_confirmation(self):self.assertFalse(self.fixture('no-success')[1][-1]['result']['protocol_confirmed'])
    def test_nack(self):self.assertFalse(self.fixture('nack')[1][-1]['result']['protocol_confirmed'])

class Brute(unittest.TestCase):
    def run_search(self,results,budget=10):
        req=dict(REQUEST,mode='bruteforce',pin='',max_attempts=budget,start_prefix='0000')
        progress=adapter.Telemetry(req);pins=[];waits=[]
        def exchange(pin,seconds):
            self.assertEqual(seconds,30);pins.append(pin);progress.data['attempts']+=1
            self.assertEqual(sum(int(c)*(3 if i%2==0 else 1) for i,c in enumerate(pin))%10,0)
            return results[min(len(pins)-1,len(results)-1)]
        def checksum(n):
            digits=f'{n:07d}';return (-sum(int(c)*(3 if i%2==0 else 1) for i,c in enumerate(digits)))%10
        with patch.object(adapter,'emit'):
            result=adapter.brute_search(req,exchange,progress,time.monotonic()+600,checksum,waits.append)
        return result,pins,progress.data,waits
    def test_budget(self):
        r,p,_,waits=self.run_search([(False,'pin_rejected',False)])
        self.assertEqual(r[2],'attempt_limit');self.assertEqual(len(p),10);self.assertTrue(all(w==3 for w in waits));self.assertEqual(p[:2],['00000000','00010009'])
    def test_timeout_retries_same_pin(self):
        r,p,t,_=self.run_search([(False,'wps_timeout',False)])
        self.assertEqual(len(p),3);self.assertEqual(len(set(p)),1);self.assertEqual(t['retries'],2)
    def test_stops_on_lock(self):self.assertEqual(len(self.run_search([(False,'wps_locked',False)])[1]),1)
    def test_stops_on_ambiguous(self):self.assertEqual(len(self.run_search([(False,'negotiation_rejected',False)])[1]),1)
    def test_second_half(self):
        r,p,_,_=self.run_search([(False,'pin_rejected',True),(True,'',True)])
        self.assertTrue(r[0]);self.assertEqual(p,['00000000','00000017'])
    def test_deadline(self):
        req=dict(REQUEST,mode='bruteforce',pin='',max_attempts=10,start_prefix='0000')
        def forbidden(*args):raise AssertionError('expired deadline called exchange')
        self.assertEqual(adapter.brute_search(req,forbidden,adapter.Telemetry(req),time.monotonic()-1,forbidden)[2],'session_limit')

if __name__=='__main__':unittest.main(verbosity=2)
