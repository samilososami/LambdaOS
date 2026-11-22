# Lambda Kernel source snapshot

This directory publishes the LambdaOS changes layered on the NetHunter Exynos
9820 kernel rather than duplicating the complete upstream repository.

## Base

- Upstream: <https://github.com/V0lk3n/nethunter_kernel_samsung_exynos9820>
- Base revision: `b238f0ee9c9f68350cc6d7a60964d640e29ec334`
- Lambda development branch: `lambdaos/kernel-capabilities-v1`
- Runtime line observed on the reference device: `4.14.356-lambdaos+`

## Contents

- `configs/`: complete reference defconfig and the auditable capability fragment.
- `patches/`: tracked source changes against the pinned base revision.
- `scripts/`: configuration, build, packaging and validation helpers plus the
  small vendor-driver compatibility patches they apply.
- `CAPABILITY-MATRIX.md`: configured, built and physically verified boundaries.
- `SOURCE-MANIFEST.json`: upstream and file-integrity metadata.

The patch adds the pinned mt76 submodule entry, fixes selected compatibility
issues, corrects a Realtek Makefile symbol, makes IKCONFIG follow the actual
build config and guards an optional Samsung USB callback. Driver-specific
changes inside independent Realtek submodules are represented by the reviewed
patches in `scripts/vendor-fixes/`.

## Reconstructing the source

```sh
git clone https://github.com/V0lk3n/nethunter_kernel_samsung_exynos9820.git
cd nethunter_kernel_samsung_exynos9820
git checkout b238f0ee9c9f68350cc6d7a60964d640e29ec334
git apply /path/to/LambdaOS/kernel/patches/lambdaos-kernel-capabilities-v1.patch
git submodule update --init --recursive
cp /path/to/LambdaOS/kernel/configs/lambdaos_beyond1lte_defconfig arch/arm64/configs/
cp /path/to/LambdaOS/kernel/configs/lambdaos_beyond1lte_pentest.config arch/arm64/configs/
cp -a /path/to/LambdaOS/kernel/scripts scripts/lambdaos
```

Run `scripts/lambdaos/prepare_config.sh`, then review the resulting `.config`
before building. The helper scripts support invocation by `kali` or root and
drop privileges before creating ordinary-user build output.

## Verification boundary

The current private development build packaged 95 modules and booted on the
SM-G973F. Internal BCM4375B1 2.4 GHz monitor reception and ALFA AWUS036ACH
2.4 GHz monitor reception were observed. External injection, 5 GHz, sustained
capture and every configured adapter family are not claimed as verified.

`vhci_hcd` must remain disabled in deployed configurations until the prepared
callback guard has been rebuilt, flashed and retested: the previous runtime path
caused a kernel panic. No firmware, proprietary blob, device log or build output
is included here.
