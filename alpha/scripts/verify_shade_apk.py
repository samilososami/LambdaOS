#!/usr/bin/env python3
"""Read-only UI14 APK resource/font checks. Does not install or access a device."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def verify(apk, aapt):
    alpha = Path(__file__).resolve().parents[1]
    resources = subprocess.check_output([aapt, 'dump', 'resources', str(apk)], text=True)
    palette = ET.parse(alpha/'platform/SystemUI/res/values/lambda_shade.xml')
    for color in palette.getroot():
        # Exclude the preceding "spec resource" declarations: verify actual values.
        values = re.findall(r'^\s*resource 0x[0-9a-f]+ [^\n]*:color/' + color.get('name') +
                            r': t=0x[0-9a-f]+ d=(0x[0-9a-f]+)', resources, re.M)
        if not values or set(values) != {'0xff'+color.text[1:].lower()}:
            raise RuntimeError('Missing/mismatched compiled palette: '+color.get('name'))
    with zipfile.ZipFile(apk) as z:
        if z.testzip() is not None:
            raise RuntimeError('Corrupt APK')
        font = z.read('res/font/lambda_clock_display.ttf')
        count = struct.unpack_from('>H', font, 4)[0]
        entries = [struct.unpack_from('>4sIII', font, 12+i*16) for i in range(count)]
        offset = next(o for tag, _, o, _ in entries if tag == b'OS/2')
        weight = struct.unpack_from('>H', font, offset+4)[0]
        if weight != 600:
            raise RuntimeError('Compiled clock is not SemiBold')
        if font != (alpha/'platform/SystemUI/customization/res/font/lambda_clock_display.ttf').read_bytes():
            raise RuntimeError('Compiled clock font differs from the reviewed asset')
    for size in ('small', 'large'):
        tree = subprocess.check_output([aapt, 'dump', 'xmltree', str(apk),
                                       f'res/layout/clock_default_{size}.xml'], text=True)
        if not re.search(r'lockScreenWeight\(0x[0-9a-f]+\)=\(type 0x10\)0x258', tree):
            raise RuntimeError('Missing namespaced weight 600 for '+size+' clock')
    return {'status': 'compiled-ui14-verified-not-physical-acceptance',
            'apk_sha256': hashlib.sha256(Path(apk).read_bytes()).hexdigest(),
            'clock_weight': weight, 'neutral_colors_verified': len(palette.getroot()),
            'namespaced_clock_weights': '600: small and large'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--aapt', default='/tools/codex/android-sdk/build-tools/35.0.0/aapt')
    args = parser.parse_args()
    print(json.dumps(verify(args.apk, args.aapt), indent=2))
