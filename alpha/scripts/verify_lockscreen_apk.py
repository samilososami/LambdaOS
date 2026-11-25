#!/usr/bin/env python3
"""Check built artifacts, not source alone. Never installs or touches a device."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

EXPECTED = {
    'LineageSetupWizard.apk': {
        'resources': ('drawable/lambda_wallpaper_light', 'drawable/lambda_wallpaper_dark'),
        'dex': (b'lambda_initial_wallpaper', b'arista-light-v1', b'arista-dark-v1'),
    },
    'SystemUI.apk': {
        'resources': ('style/Theme.Lambda.Keyguard', 'font/lambda_keyguard_regular',
                      'font/lambda_clock_regular', 'layout/lambda_clock_stacked',
                      'drawable/lambda_clock_analog_thumbnail',
                      'drawable/lambda_clock_stacked_thumbnail',
                      'font/lambda_clock_display', 'drawable/lambda_bouncer_background',
                      'drawable/lambda_fingerprint_recognition',
                      'color/lambda_shade_background', 'color/lambda_shade_active',
                      'color/lambda_shade_inactive',
                      'fraction/lambda_large_clock_vertical_bias'),
        'forbidden_resources': ('drawable/lambda_keyguard_enter',
                                'drawable/lambda_pin_empty_avd',
                                'drawable/lambda_pin_filled_avd'),
        'dex': (b'LAMBDA_STACKED', b'LAMBDA_ANALOG'),
    },
    'TeleService.apk': {
        'resources': ('style/LambdaNativeOverlay', 'style/LambdaNativeLightOverlay'),
        'dex': (b'com.android.phone.EmergencyDialer',),
    },
}


def verify_apk(apk, name, aapt2):
    expected = EXPECTED[name]
    with zipfile.ZipFile(apk) as z:
        if z.testzip() is not None:
            raise RuntimeError('APK ZIP CRC failure: ' + name)
        dex = b''.join(z.read(n) for n in z.namelist()
                       if re.fullmatch(r'classes\d*\.dex', n))
        for marker in expected['dex']:
            if marker not in dex:
                raise RuntimeError('Required runtime marker missing: ' + name + ' ' + repr(marker))
        if name == 'SystemUI.apk' and any(marker in dex for marker in
                (b'LambdaQuickControls', b'LambdaControlSheet')):
            raise RuntimeError('Retired UI16 dialogs remain in SystemUI')
    resources = subprocess.check_output([str(aapt2), 'dump', 'resources', str(apk)], text=True)
    for resource in expected['resources']:
        if not re.search(r'\b' + re.escape(resource) + r'(?=\s|$)', resources):
            raise RuntimeError('Required packaged resource missing: ' + name + ' ' + resource)
    for resource in expected.get('forbidden_resources', ()):
        if re.search(r'\b' + re.escape(resource) + r'(?=\s|$)', resources):
            raise RuntimeError('Retired custom bouncer resource remains: ' + name + ' ' + resource)
    with open(apk, 'rb') as f:
        digest = hashlib.file_digest(f, 'sha256').hexdigest()
    return {'apk': name, 'sha256': digest, 'bytes': Path(apk).stat().st_size,
            'resources': list(expected['resources']), 'runtime_markers': 'present',
            'physical_ui_validation': 'pending'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', default='/srv/lambdaos/src/lineage-22.2')
    args = parser.parse_args()
    source = Path(args.source)
    product = source / 'out/target/product/beyond1lte'
    paths = {
        'LineageSetupWizard.apk': 'system_ext/priv-app/LineageSetupWizard/LineageSetupWizard.apk',
        'SystemUI.apk': 'system_ext/priv-app/SystemUI/SystemUI.apk',
        'TeleService.apk': 'system/priv-app/TeleService/TeleService.apk',
    }
    report = [verify_apk(product / relative, name, source / 'out/host/linux-x86/bin/aapt2')
              for name, relative in paths.items()]
    print(json.dumps({'status': 'built-artifacts-verified-not-installed', 'artifacts': report}, indent=2))


if __name__ == '__main__':
    main()
