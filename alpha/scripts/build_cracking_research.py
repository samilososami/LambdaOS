#!/usr/bin/env python3
"""Rebuild isolated CPU/GPU experiments; never overwrite the production engines."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import pwd
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'out/cracking-speed'
VENDOR = OUT / 'vendor'
NATIVE = ROOT / 'apps/Lambda/native'
BIN = Path('/tools/codex/android-sdk/ndk/27.1.12297006/toolchains/llvm/prebuilt/linux-x86_64/bin')
CC = str(BIN / 'aarch64-linux-android29-clang')
CRYPTO = OUT / 'openssl-install'


def replace_once(path, original, replacement):
    text = path.read_text()
    if replacement in text:
        return
    if text.count(original) != 1:
        raise RuntimeError('Unexpected source revision: ' + str(path))
    path.write_text(text.replace(original, replacement, 1))


def run(args, cwd=ROOT):
    subprocess.run([str(x) for x in args], cwd=cwd, check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--legacy-hashcat', action='store_true')
    args = parser.parse_args()
    if os.geteuid() == 0:
        user = pwd.getpwnam('kali')
        os.initgroups(user.pw_name, user.pw_gid)
        os.setgid(user.pw_gid)
        os.setuid(user.pw_uid)
    assert (CRYPTO / 'lib/libcrypto.a').is_file(), 'Run build_cracking_bench.py first'
    assert hashlib.sha256((VENDOR / 'aircrack-ng-1.7.tar.gz').read_bytes()).hexdigest() == '05a704e3c8f7792a17315080a21214a4448fd2452c1b0dd5226a3a55f90b58c3'
    variant = VENDOR / 'aircrack-ng-1.7-sha'
    if not variant.exists():
        shutil.copytree(VENDOR / 'aircrack-ng-1.7', variant)
    source = variant / 'lib/ce-wpa/crypto_engine.c'
    replace_once(source, '#include "aircrack-ng/ce-wpa/crypto_engine.h"',
                 '#include "aircrack-ng/ce-wpa/crypto_engine.h"\n#include "lambda_pmk_sha1.h"')
    block = '''// PMK calculation
    if ((getauxval(AT_HWCAP) & HWCAP_SHA1) && engine->essid_length <= 32)
    {
        for (int j = 0; j < nparallel; j++)
        {
            lambda_pmk(key[j].v, strnlen((const char *)key[j].v, 63),
                       (const uint8_t *)engine->essid, engine->essid_length,
                       (uint8_t *)&pmk[j]);
        }
        return;
    }'''
    replace_once(source, '// PMK calculation', block)
    shutil.copy2(NATIVE / 'pmk-sha1.h', variant / 'lib/ce-wpa/lambda_pmk_sha1.h')
    run(['make', '-j4', 'aircrack-ng'], variant)
    products = {'aircrack-ng-sha': variant / 'aircrack-ng'}
    for name in ['pmk-cpu-probe', 'pmk-gpu-probe']:
        target = OUT / name
        run([CC, '-O3', '-Wall', '-Wextra', '-Wno-deprecated-declarations', '-fPIE', '-pie',
             '-I' + str(CRYPTO / 'include'),
             '-I' + str(VENDOR / 'hashcat-8.0.0/deps/OpenCL-Headers'),
             NATIVE / (name + '.c'), CRYPTO / 'lib/libcrypto.a', '-ldl', '-o', target])
        products[name] = target
    run([CC, '-O2', '-Wall', '-Wextra', '-fPIE', '-pie', NATIVE / 'benchmark-guard.c', '-o', OUT / 'benchmark-guard'])
    products['benchmark-guard'] = OUT / 'benchmark-guard'
    if args.legacy_hashcat:
        archive = VENDOR / 'hashcat-6.2.6.tar.gz'
        assert hashlib.sha256(archive.read_bytes()).hexdigest() == 'e02efd2390c31e9dff6bd32f24382651c73559a7f7908e901e7cbbe760d6fcfd'
        legacy = VENDOR / 'hashcat-e5b302363654cf4717dbe5bbe417504fc5d0ca72'
        if not legacy.exists():
            with tarfile.open(archive) as tar:
                tar.extractall(VENDOR, filter='data')
        replace_once(legacy / 'src/affinity.c',
                     '  const int rc = pthread_setaffinity_np (thread, sizeof (cpu_set_t), &cpuset);',
                     '  #if defined (__ANDROID__)\n  const int rc = sched_setaffinity (pthread_gettid_np (thread), sizeof (cpu_set_t), &cpuset);\n  #else\n  const int rc = pthread_setaffinity_np (thread, sizeof (cpu_set_t), &cpuset);\n  #endif')
        replace_once(legacy / 'src/backend.c',
                     '        device_param->device_global_mem = device_global_mem;',
                     '        // Bound this isolated mobile research build, including allocation probes.\n        device_param->device_global_mem = MIN (device_global_mem, 512ULL * 1024 * 1024);')
        run(['make', '-j4', 'CC=' + CC, 'CXX=' + str(BIN / 'aarch64-linux-android29-clang++'),
             'AR=' + str(BIN / 'llvm-ar'), 'ENABLE_UNRAR=0', 'ENABLE_BRAIN=0', 'LFLAGS_NATIVE=-ldl -lm',
             'INSTALL_FOLDER=/data/local/tmp/lambda-crack-bench/gpu626',
             'SHARED_FOLDER=/data/local/tmp/lambda-crack-bench/gpu626',
             'hashcat', 'modules/module_00000.so', 'modules/module_16800.so', 'modules/module_22000.so'], legacy)
        products['hashcat626'] = legacy / 'hashcat'
    report = {'experimental': True, 'production_engine_changed': False,
              'sha256': {k: hashlib.sha256(v.read_bytes()).hexdigest() for k, v in products.items()},
              'source_sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                for p in [NATIVE / 'pmk-sha1.h', NATIVE / 'pmk-gpu-probe.cl', source]}}
    (OUT / 'research-build-manifest.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
