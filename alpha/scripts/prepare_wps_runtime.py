#!/usr/bin/env python3
"""Stage isolated Android Python; never execute package maintainer scripts.

First run freezes HTTPS Termux package URLs and SHA256 in downloads/runtime-lock.json.
Subsequent builds use that lock, not the latest package versions. This is not an
APT signature verification claim: trust is HTTPS origin plus the recorded hash.
Works as kali or root; root drops to kali before writing build outputs.
"""
import gzip
import hashlib
import json
import os
from pathlib import Path
import pwd
import shutil
import subprocess
import urllib.request

BASE = Path(__file__).resolve().parents[1]
OUT = BASE / 'out/operator-wps'
ORIGIN = 'https://packages.termux.dev/apt/termux-main/'

def main():
    if os.geteuid() == 0:
        user = pwd.getpwnam('kali')
        os.initgroups(user.pw_name, user.pw_gid)
        os.setgid(user.pw_gid)
        os.setuid(user.pw_uid)
    downloads = OUT / 'downloads'
    downloads.mkdir(parents=True, exist_ok=True)
    lock = downloads / 'runtime-lock.json'
    if not lock.exists():
        index = gzip.decompress(urllib.request.urlopen(ORIGIN + 'dists/stable/main/binary-aarch64/Packages.gz', timeout=30).read()).decode()
        packages = {}
        for para in index.split('\n\n'):
            fields = dict(line.split(': ', 1) for line in para.splitlines() if ': ' in line and not line.startswith(' '))
            if 'Package' in fields:
                packages.setdefault(fields['Package'], fields)
        names = ['python', 'gdbm', 'libandroid-posix-semaphore', 'libandroid-support', 'libbz2', 'libcrypt', 'libexpat', 'libffi', 'liblzma', 'libsqlite', 'ncurses', 'ncurses-ui-libs', 'openssl', 'readline', 'zlib', 'zstd']
        rows = [{k: packages[n][k] for k in ('Package', 'Version', 'Filename', 'SHA256')} for n in names]
        lock.write_text(json.dumps(rows, indent=2) + '\n')
    rows = json.loads(lock.read_text())
    for name, origin, suite in [('libnl', ORIGIN, 'stable/main'), ('wpa-supplicant', 'https://packages.termux.dev/apt/termux-root/', 'root/stable')]:
        if any(r['Package'] == name for r in rows):
            continue
        index = gzip.decompress(urllib.request.urlopen(origin + 'dists/' + suite + '/binary-aarch64/Packages.gz', timeout=30).read()).decode()
        for para in index.split('\n\n'):
            if para.startswith('Package: ' + name + '\n'):
                fields = dict(line.split(': ', 1) for line in para.splitlines() if ': ' in line and not line.startswith(' '))
                rows.append({**{k: fields[k] for k in ('Package', 'Version', 'Filename', 'SHA256')}, 'origin': origin})
                break
        else:
            raise RuntimeError('Missing runtime package: ' + name)
    lock.write_text(json.dumps(rows, indent=2) + '\n')
    extracted = OUT / 'packages'
    extracted.mkdir(exist_ok=True)
    for row in rows:
        deb = downloads / Path(row['Filename']).name
        if not deb.exists():
            urllib.request.urlretrieve(row.get('origin', ORIGIN) + row['Filename'], deb)
        if hashlib.sha256(deb.read_bytes()).hexdigest() != row['SHA256']:
            raise RuntimeError('Package hash mismatch: ' + row['Package'])
        subprocess.run(['dpkg-deb', '-x', str(deb), str(extracted)], check=True)
    source = extracted / 'data/data/com.termux/files/usr'
    runtime = OUT / 'runtime'
    runtime.mkdir(exist_ok=True)
    for directory in ('bin', 'lib', 'share'):
        (runtime / directory).mkdir(exist_ok=True)
        subprocess.run(['cp', '-a', str(source / directory) + '/.', str(runtime / directory)], check=True)
    print(json.dumps({'runtime': str(runtime), 'packages': len(rows), 'package_scripts_executed': False}))

if __name__ == '__main__':
    main()
