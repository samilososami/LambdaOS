# Development

This repository is the public project-specific source and asset snapshot.
It does not vendor the full Android tree, proprietary Samsung blobs or the
maintainer's build-machine configuration.

## Source layout

`alpha/apps/Lambda` is the standalone application foundation. It exposes device
information and category navigation; the category labels do not imply that
the corresponding pentesting operations are already implemented.

`alpha/apps/SetupWizard` is a modified LineageOS setup wizard, built in the
Android platform tree. `alpha/platform`, `alpha/patches` and `alpha/vendor`
contain the related platform integration. `alpha/manifests/beyond1lte.xml`
identifies the device repositories used alongside the LineageOS 22.2 manifest.

The patches retain their development history. In particular, the native
PIN/pattern presentation is selected by `alpha/scripts/stage_systemui.sh`, which
excludes retired custom-bouncer changes from the historical patch. Do not apply
every patch indiscriminately. The retained `alpha/retired` files let the staging
helper recognize known earlier files without overwriting unknown edits.

## Build the Lambda application

Prerequisites: Python 3, JDK 21, Android SDK Platform 35 and Build Tools 35.0.0.
Point the environment variables to your installed SDK and JDK:

```sh
export LAMBDA_ANDROID_SDK=/path/to/android-sdk
export LAMBDA_JAVA_HOME=/path/to/jdk-21
python3 alpha/scripts/build_lambda.py
```

The script generates a standalone development APK in `alpha/out/`. It creates
a local development signing key under `alpha/private/`; both directories are
ignored by Git. This is a development build, not the release signing identity.
On the maintainer's Kali machine the command works as either `kali` or root;
root invocation drops to `kali` to preserve consistent file ownership.

This standalone APK is not a flashable ROM. The setup wizard and SystemUI
sources require the matching platform tree and system integration.

## Full system builds

The maintainer has a working local build pipeline; a portable public end-to-end
build recipe is still being prepared. Samsung proprietary files, exact source
revision locks, signing inputs and boot-animation packaging must be supplied
and validated separately. Do not treat this snapshot as a one-command ROM build.

The product fragment preserves the development reference to a local ADB key
file. That file is not published. Public-release preparation must remove host
preauthorization and document normal user-approved ADB authorization.

No kernel source is vendored here. The device manifest currently references
the LineageOS Exynos 9820 kernel. A new pentesting-oriented kernel is planned
and must pass device testing before it is advertised as supported.

## Assets and checks

Browse [`assets/README.md`](../assets/README.md) for the visual archive. The
gallery and inventory can be regenerated with:

```sh
node scripts/catalog-assets.mjs
node scripts/check-public-tree.mjs
```

Use Node.js 20 or later. Both helpers also accept root invocation on the
maintainer's Kali environment and delegate to the normal user. The checker
validates local documentation links, file-size limits and excluded private
material. It is not a substitute for reviewing changes before publication.
