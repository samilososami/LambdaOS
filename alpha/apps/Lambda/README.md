# Lambda Operator

Lambda Operator is the native control centre for LambdaOS. The application owns
the user interface, durable job state and a foreground Core process. Privileged
radio and cracking helpers remain outside the ordinary APK sandbox and are
reached through bounded, typed requests.

Current source version: `0.2.0-operator.4` (`versionCode 24`).

## Included flows

- device, Wi-Fi, Bluetooth and USB inventory;
- internal or supported external Wi-Fi source selection;
- target-bound capture sessions and durable receipts;
- separately confirmed, bounded active assistance;
- WPS Pixie Dust, PIN, push-button and bounded PIN-batch UI/state handling;
- quick and dedicated offline WPA/WPA2 candidate verification;
- CPU and experimental CPU + Mali hybrid PMK engines;
- connection/save/autojoin only after independent candidate verification.

The application does not expose arbitrary shell execution. A visible SSID is
not treated as authorization. Backend results distinguish observed facts,
tool-reported actions and unverified effects.

## Standalone build

From the repository root:

```sh
export LAMBDA_ANDROID_SDK=/path/to/android-sdk
export LAMBDA_JAVA_HOME=/path/to/jdk-21
python3 alpha/scripts/build_lambda.py
```

The generated APK and development key are written to ignored directories. The
standalone build verifies Android resources and Java code; it does not package
the separately provisioned privileged worker, native radio tools, WPS runtime,
wordlists or ROM policy.

## External runtime dependencies

The WPS adapter derives from OneShot-Extended under GPL-2.0-or-later and expects
the pinned upstream checkout at runtime. Aircrack-ng/wireless-tools and the
larger wordlist retain their own licenses. See
[`THIRD_PARTY_RADIO.md`](THIRD_PARTY_RADIO.md),
[`alpha/vendor/DEPENDENCIES.md`](../../vendor/DEPENDENCIES.md) and
[`NOTICE.md`](../../../NOTICE.md).

## Status boundary

This source has been exercised as a development APK with a separately
provisioned restricted worker on the reference S10. APK installation alone does
not reproduce that deployment, and full ROM persistence remains unfinished.
