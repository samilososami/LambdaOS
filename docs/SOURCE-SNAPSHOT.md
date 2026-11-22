# Source snapshot — 22 November 2026

This publication updates the LambdaOS project-specific source to the current
development checkpoint.

## Included

- Lambda Operator/Core `0.2.0-operator.4` Java, resources, WPS adapter and native
  CPU/Mali, radio and guard sources.
- Synthetic WPS contract/UI tests and selected reproducibility scripts.
- Lambda setup assistant, platform resources, Android patches and product
  configuration already present in the public snapshot.
- Lambda Kernel configs, scripts, capability matrix and a patch against the
  pinned upstream kernel revision.
- Architecture, security-boundary, benchmark and current-status documentation.
- Existing screenshots, wallpapers, branding and design archive.

## Deliberately kept private or external

- signing and ADB keys, credentials, backups and personal/device identifiers;
- raw network captures, recovered credentials, real SSIDs and private test logs;
- Samsung proprietary firmware/blobs and full Android/build trees;
- APK/ROM/kernel build output, caches, downloaded dependencies and wordlists;
- AI model weights, checkpoints and private benchmark artifacts;
- six independent/nested Git repositories from the private workspace.

External source dependencies are pinned by URL and revision instead of being
silently copied into this Git history. `docs/source-inventory.json` records the
path, size and SHA-256 of every published implementation file. It is an integrity
index, not a claim that the full ROM was rebuilt from this checkout.

## Status boundary

The maintained ROM line is `0.1.0-alpha.2`. The latest known private FCM-v4 line
booted with kernel `4.14.356-lambdaos+`, but no flashable ZIP is published here.
Operator's standalone host build and synthetic tests are reproducible from this
snapshot with their documented dependencies; physical radio claims remain
limited to the explicit capability matrix.
