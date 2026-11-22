#!/usr/bin/env python3
"""Reproducible separate hybrid engines; keep original and SHA CPU controls."""
import hashlib
import json
import os
from pathlib import Path
import pwd
import shutil
import subprocess
from build_cracking_research import ROOT, OUT, VENDOR, NATIVE, CC, CRYPTO, replace_once

def main():
    if os.geteuid()==0:
        u=pwd.getpwnam('kali');os.initgroups(u.pw_name,u.pw_gid);os.setgid(u.pw_gid);os.setuid(u.pw_uid)
    variant=VENDOR/'aircrack-ng-1.7-hybrid'
    if not variant.exists():shutil.copytree(VENDOR/'aircrack-ng-1.7-sha',variant)
    source=variant/'lib/ce-wpa/crypto_engine.c'
    replace_once(source,'#include "lambda_pmk_sha1.h"','#include "lambda_pmk_sha1.h"\n#include "lambda_pmk_cache.h"')
    replace_once(source,'            lambda_pmk(key[j].v, strnlen((const char *)key[j].v, 63),',
                 '            if (lambda_cache_pmk(key[j].v, strnlen((const char *)key[j].v, 63), (const uint8_t *)engine->essid, engine->essid_length, (uint8_t *)&pmk[j])) continue;\n            lambda_pmk(key[j].v, strnlen((const char *)key[j].v, 63),')
    shutil.copy2(NATIVE/'pmk-cache.h',variant/'lib/ce-wpa/lambda_pmk_cache.h')
    subprocess.run(['make','-j4','aircrack-ng'],cwd=variant,check=True)
    subprocess.run([CC,'-O3','-Wall','-Wextra','-Wno-deprecated-declarations','-fPIE','-pie',
                    '-I'+str(CRYPTO/'include'),'-I'+str(VENDOR/'hashcat-8.0.0/deps/OpenCL-Headers'),
                    str(NATIVE/'pmk-hybrid-worker.c'),str(CRYPTO/'lib/libcrypto.a'),'-ldl','-o',str(OUT/'pmk-hybrid-worker')],check=True)
    subprocess.run([CC,'-O2','-Wall','-Wextra','-fPIE','-pie',str(NATIVE/'benchmark-guard.c'),'-o',str(OUT/'benchmark-guard')],check=True)
    files=[variant/'aircrack-ng',OUT/'pmk-hybrid-worker',OUT/'benchmark-guard',NATIVE/'pmk-hybrid.cl',NATIVE/'pmk-cache.h',source]
    report={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
    (OUT/'hybrid-build-manifest.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
if __name__=='__main__':main()
