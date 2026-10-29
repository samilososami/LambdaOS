#!/usr/bin/env python3
"""Validate the built S10 touch declaration; never modifies a device."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

EXPECTED = {'IGloveMode', 'IHighTouchPollingRate', 'ITouchscreenGesture'}


def validate(path):
    root = ET.parse(path).getroot()
    blocks = [hal for hal in root.findall('hal')
              if hal.findtext('name') == 'vendor.lineage.touch']
    if len(blocks) != 1:
        raise ValueError('Expected exactly one vendor.lineage.touch HAL declaration')
    hal = blocks[0]
    # assemble_vintf omits the default AIDL v1 version in the installed XML.
    if hal.get('format') != 'aidl' or hal.findtext('version', '1') != '1':
        raise ValueError('Expected the existing AIDL version 1 contract')
    pairs = [(i.findtext('name'), i.findtext('instance')) for i in hal.findall('interface')]
    pairs += [tuple(n.text.split('/', 1)) for n in hal.findall('fqname')]
    expected = {(name, 'default') for name in EXPECTED}
    if len(pairs) != len(expected) or set(pairs) != expected:
        raise ValueError(f'Touch interfaces differ from supported S10 contract: {pairs!r}')
    return {'manifest': str(path), 'interfaces': sorted(EXPECTED),
            'status': 'declaration-verified-not-runtime-registration'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    print(json.dumps(validate(parser.parse_args().manifest), indent=2))
