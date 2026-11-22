# Development

This repository is the reviewed LambdaOS-specific source and design snapshot.
It does not vendor the full Android tree, proprietary Samsung blobs, the private
build machine, signing identities, radio captures, model weights or ROM output.

## Source layout

- `alpha/apps/Lambda`: Operator/Core application source and native helpers.
- `alpha/apps/SetupWizard`: modified LineageOS setup assistant.
- `alpha/platform`, `alpha/patches`, `alpha/vendor`: selected platform and product
  integration.
- `alpha/tests`: synthetic contract/UI fixtures that do not transmit RF.
- `kernel`: configs, patch and scripts layered on a pinned external kernel base.
- `assets`: screenshots, branding, wallpapers and design history.

Historical platform patches are retained, but the current staging helpers choose
the active subset. Do not apply every patch indiscriminately. The six nested
repositories in the private workspace are not subdirectories to publish or
rewrite from this checkout.

## Build the standalone Operator APK

Prerequisites: Python 3, JDK 21, Android SDK Platform 35 and Build Tools 35.0.0.

```sh
export LAMBDA_ANDROID_SDK=/path/to/android-sdk
export LAMBDA_JAVA_HOME=/path/to/jdk-21
python3 alpha/scripts/build_lambda.py
```

The script works when invoked as a normal user or through root on the maintainer's
Kali environment; root invocation drops to `kali` before creating output. It
generates an ignored development identity and APK under `alpha/private/` and
`alpha/out/`. It does not package the privileged worker or native runtime.

## Operator dependencies and tests

The WPS integration adapter is published, while its independent upstream is
pinned in [`alpha/vendor/DEPENDENCIES.md`](../alpha/vendor/DEPENDENCIES.md).
With the exact OneShot-Extended checkout available at
`alpha/vendor/oneshot-extended`, run the non-RF adapter tests:

```sh
python3 alpha/scripts/test_wps_adapter.py
```

Synthetic cracking fixtures can be regenerated with:

```sh
python3 alpha/scripts/prepare_cracking_fixtures.py
python3 alpha/scripts/prepare_dedicated_dictionary.py
```

These scripts download pinned public inputs and verify hashes. Generated
fixtures and dictionaries remain ignored. They do not perform radio capture.

The radio/cracking build scripts preserve the current research implementation,
but some depend on pinned NDK/tool inputs and retained upstream sources that are
not vendored. See each script and [Operator status](OPERATOR.md) before use.

## Kernel reconstruction

Follow [`kernel/README.md`](../kernel/README.md). The public snapshot is a patch
against the exact upstream revision plus complete Lambda configs and scripts.
Build success does not establish device compatibility; use the
[capability matrix](../kernel/CAPABILITY-MATRIX.md) for the tested boundary.

## Full ROM builds

The private development pipeline has produced and installed LambdaOS alpha
builds, but the portable public pipeline is incomplete. A full build additionally
requires the matching LineageOS/device trees, extracted proprietary files,
reviewed release signing and device-specific packaging. Host ADB preauthorization
is development-only and must not be part of a public release.

No file from GitHub's automatic **Source code** archives is a flashable ROM.
Future installable artifacts will be published only through Releases with hashes
and known issues.

## Publication checks

```sh
node scripts/catalog-assets.mjs
node scripts/check-public-tree.mjs
gitleaks git . --redact=100
git diff --check
```

The project checker validates ignored/private paths, known secret patterns,
file sizes, local documentation links and source/asset hashes. Gitleaks covers
the complete Git history. Review staged changes manually before publishing.
