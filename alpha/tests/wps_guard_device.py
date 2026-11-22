#!/usr/bin/env python3
"""S10 guard tests. Adapter receives invalid JSON or no request; never starts RF."""
import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path
BASE=Path('/data/local/tmp/lambda-wps')
ENV=dict(os.environ,PATH=str(BASE/'usr/bin')+':/system/bin',LD_LIBRARY_PATH=str(BASE/'usr/lib'),PYTHONHOME=str(BASE/'usr'),HOME=str(BASE/'home'),TMPDIR=str(BASE/'tmp'))
def dead(pid):
    try:return Path(f'/proc/{pid}/stat').read_text().split(') ',1)[1].startswith('Z')
    except FileNotFoundError:return True
def gone(pid):
    for _ in range(60):
        if dead(pid):return
        time.sleep(.05)
    raise AssertionError('owned process remains: '+str(pid))
def children_of(pid):
    children=[]
    for path in Path('/proc').iterdir():
        if not path.name.isdigit():continue
        try:
            fields=(path/'stat').read_text().split(') ',1)[1].split()
            if int(fields[1])==pid:children.append(int(path.name))
        except (OSError,ValueError,IndexError):pass
    return children
def main():
    rejected=subprocess.run([str(BASE/'wps-guard'),'--anything'],capture_output=True,env=ENV,timeout=5)
    assert rejected.returncode==64
    print('PASS unknown guard option rejected')
    invalid=subprocess.run([str(BASE/'wps-guard')],input='{}\n',text=True,capture_output=True,env=ENV,timeout=5)
    assert invalid.returncode==1 and json.loads(invalid.stdout)=={'error':'invalid_fields'}
    print('PASS invalid request; no radio')
    p=subprocess.Popen([str(BASE/'wps-guard')],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=ENV)
    time.sleep(.3)
    children=children_of(p.pid)
    assert children,'adapter child not started'
    p.terminate();p.wait(timeout=4)
    assert p.returncode==143
    for child in children:gone(int(child))
    p.stdin.close();p.stdout.close();p.stderr.close()
    print('PASS cancellation; adapter gone')
    p=subprocess.Popen([str(BASE/'wps-guard'),'--bruteforce'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=ENV)
    time.sleep(.3);children=children_of(p.pid);assert children
    p.terminate();p.wait(timeout=4);assert p.returncode==143
    for child in children:gone(child)
    p.stdin.close();p.stdout.close();p.stderr.close()
    print('PASS brute guard cancellation; no request/no radio')
    read,write=os.pipe();info_read,info_write=os.pipe()
    parent=os.fork()
    if parent==0:
        os.close(info_read)
        guard=subprocess.Popen([str(BASE/'wps-guard')],stdin=read,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,env=ENV)
        time.sleep(.3)
        children=children_of(guard.pid)
        os.write(info_write,json.dumps([guard.pid,*map(int,children)]).encode())
        os._exit(0)
    os.close(info_write)
    pids=json.loads(os.read(info_read,4096));os.waitpid(parent,0)
    assert len(pids)>1
    for pid in pids:gone(pid)
    for fd in (read,write,info_read):os.close(fd)
    print('PASS parent death; guard and adapter gone')
if __name__=='__main__':
    if '--timeout' in sys.argv:
        begin=time.monotonic()
        p=subprocess.Popen([str(BASE/'wps-guard')],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=ENV)
        p.wait(timeout=136)
        elapsed=time.monotonic()-begin
        assert p.returncode==124 and 129<=elapsed<=135,(p.returncode,elapsed)
        p.stdin.close();p.stdout.close();p.stderr.close()
        print('PASS hard timeout; %.2f seconds; no request/no radio'%elapsed)
    else:main()
