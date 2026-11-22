#!/usr/bin/env python3
"""Pin, normalize and attribute the bundled larger WPA dictionary."""
import hashlib
import json
import os
from pathlib import Path
import pwd
import urllib.request
ROOT=Path(__file__).resolve().parents[1]
REV='2df55facf06c7742f2038a8f6607ea9071596128'
URL=f'https://raw.githubusercontent.com/berzerk0/Probable-Wordlists/{REV}/Real-Passwords/WPA-Length/Top204Thousand-WPA-probable-v2.txt'
def main():
    if os.geteuid()==0:
        u=pwd.getpwnam('kali');os.initgroups(u.pw_name,u.pw_gid);os.setgid(u.pw_gid);os.setuid(u.pw_uid)
    src=ROOT/'out/cracking-speed/vendor/probable204k-source.txt'
    if not src.exists():
        with urllib.request.urlopen(URL,timeout=30) as response:src.write_bytes(response.read(4*1024*1024))
    data=src.read_bytes();assert hashlib.sha256(data).hexdigest()=='e416890a4742481971ba6d85039e0072111c92cb46c8cb7f3f8f22b865f48bf4','Upstream dictionary hash mismatch'
    words=[];seen=set()
    for line in data.split(b'\n'):
        line=line.removesuffix(b'\r')
        if 8<=len(line)<=15 and all(32<=b<=126 for b in line) and line not in seen:words.append(line);seen.add(line)
    output=b'\n'.join(words)+b'\n';root=ROOT/'out/cracking-speed/fixtures';(root/'dedicated-8-15.txt').write_bytes(output)
    manifest={'id':'probable-long','name':'Probable WPA · '+format(len(words),','),'candidates':len(words),'bytes':len(output),'sha256':hashlib.sha256(output).hexdigest(),
              'source_url':URL,'source_sha256':hashlib.sha256(data).hexdigest(),'license':'CC-BY-SA-4.0','author':'berzerk0 / Probable-Wordlists',
              'changes':'Filtered to printable ASCII, 8-15 bytes; CRLF normalized; deduplicated in original order.'}
    (root/'dedicated-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');print(json.dumps(manifest,indent=2))
if __name__=='__main__':main()
