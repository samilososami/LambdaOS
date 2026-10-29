#!/usr/bin/env python3
"""Compare APK code/resources while allowing signature metadata to differ."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def payload(path):
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise RuntimeError(f'Invalid APK ZIP: {path}')
        return {name: hashlib.sha256(archive.read(name)).hexdigest()
                for name in archive.namelist()
                if not name.startswith('META-INF/') and not name.endswith('/')}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('installed_candidate', type=Path)
    parser.add_argument('release_apk', type=Path)
    args = parser.parse_args()
    before, after = payload(args.installed_candidate), payload(args.release_apk)
    difference = sorted(name for name in before.keys() | after.keys()
                        if before.get(name) != after.get(name))
    report = {'candidate': str(args.installed_candidate), 'release': str(args.release_apk),
              'entries': len(before), 'payload_equal': not difference, 'differences': difference}
    print(json.dumps(report, indent=2))
    raise SystemExit(bool(difference))
