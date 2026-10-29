# Source snapshot — 29 October 2026

The initial publication contains the Lambda and SetupWizard applications,
platform resources, patches, product configuration, device manifest and selected
build/staging/verification helpers from the LambdaOS development workspace.

The local system is based on LambdaOS `0.1.0-alpha.2`. The latest fully packaged
local ROM is the UI17 build; a later native PIN/pattern SystemUI update was tested
separately. The public source snapshot includes the current staging selection,
and should not be mistaken for a verified new full ROM release.

## Deliberate publication boundary

Included: implementation sources, resource assets, retained license notices,
reviewed screenshots, visual concepts and source-integrity manifests.

Kept locally: private signing keys, ADB host keys, device backups, extracted
proprietary firmware/blobs, private test logs and credentials, build caches,
ROM/APK binaries and AI model weights. Full ROM distribution is handled through
the release process rather than a blanket upload of the development directory.

`docs/source-inventory.json` records hashes of the published implementation
files. `assets/manifest.json` records the reusable image library. These indexes
identify the snapshot; they do not establish a successful Android build.
