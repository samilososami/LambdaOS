#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
toolchain_root="${LAMBDAOS_TOOLCHAIN_ROOT:-/tools/codex/toolchains}"
clang_root="${LAMBDAOS_CLANG_ROOT:-${toolchain_root}/clang-10.0}"
gcc64_root="${LAMBDAOS_GCC64_ROOT:-${toolchain_root}/aarch64-linaro-5.5}"
gcc32_root="${LAMBDAOS_GCC32_ROOT:-${toolchain_root}/armhf-linaro-5.5}"
jobs="${LAMBDAOS_JOBS:-$(nproc)}"
build_root="${1:-${root_dir}/../builds/lambdaos-beyond1lte-$(date -u +%Y%m%dT%H%M%SZ)}"
out_dir="${build_root}/out"
stage_dir="${build_root}/staging"
log_dir="${build_root}/logs"
dist_dir="${build_root}/dist"
reference_firmware_zip="${LAMBDAOS_REFERENCE_FIRMWARE_ZIP:-${root_dir}/../reference/nethunter-22.2/kernel-nethunter-20250630_123952-beyond1lte-los-22.2-fifteen.zip}"
supplemental_firmware_dir="${LAMBDAOS_SUPPLEMENTAL_FIRMWARE_DIR:-${root_dir}/../firmware/linux-firmware}"

for required in \
  "${clang_root}/bin/clang" \
  "${clang_root}/bin/ld.lld" \
  "${gcc64_root}/bin/aarch64-linux-gnu-gcc" \
  "${gcc32_root}/bin/arm-linux-gnueabihf-gcc"; do
  if [[ ! -x "${required}" ]]; then
    printf 'Missing required compiler executable: %s\n' "${required}" >&2
    exit 1
  fi
done

mkdir -p "${out_dir}" "${stage_dir}" "${log_dir}" "${dist_dir}"

export ARCH=arm64
export SUBARCH=arm64
export PATH="${clang_root}/bin:${PATH}"
export CROSS_COMPILE="${gcc64_root}/bin/aarch64-linux-gnu-"
export CROSS_COMPILE_ARM32="${gcc32_root}/bin/arm-linux-gnueabihf-"
export CLANG_TRIPLE=aarch64-linux-gnu-

make_args=(
  -C "${root_dir}"
  O="${out_dir}"
  ARCH="${ARCH}"
  SUBARCH="${SUBARCH}"
  LLVM=1
  CROSS_COMPILE="${CROSS_COMPILE}"
  CROSS_COMPILE_ARM32="${CROSS_COMPILE_ARM32}"
  CLANG_TRIPLE="${CLANG_TRIPLE}"
  CONFIG_NO_ERROR_ON_MISMATCH=y
)

printf 'Source: %s\nBuild root: %s\nJobs: %s\n' \
  "${root_dir}" "${build_root}" "${jobs}"

"${root_dir}/scripts/lambdaos/apply_vendor_fixes.sh" \
  2>&1 | tee "${log_dir}/vendor-fixes.log"

"${root_dir}/scripts/lambdaos/prepare_config.sh" "${build_root}/config-check" \
  2>&1 | tee "${log_dir}/prepare-config.log"

make "${make_args[@]}" lambdaos_beyond1lte_defconfig \
  2>&1 | tee "${log_dir}/defconfig.log"

make "${make_args[@]}" -j"${jobs}" Image modules dtbs \
  2>&1 | tee "${log_dir}/build.log"

kernel_release="$(make "${make_args[@]}" -s kernelrelease)"

make "${make_args[@]}" \
  INSTALL_MOD_PATH="${stage_dir}" \
  modules_install \
  2>&1 | tee "${log_dir}/modules-install.log"

depmod -b "${stage_dir}" "${kernel_release}" \
  2>&1 | tee "${log_dir}/depmod.log"

install -m 0644 "${out_dir}/arch/arm64/boot/Image" "${dist_dir}/Image"
install -m 0644 "${out_dir}/.config" "${dist_dir}/lambdaos_beyond1lte.config"
install -m 0644 "${out_dir}/Module.symvers" "${dist_dir}/Module.symvers"
install -m 0644 "${out_dir}/System.map" "${dist_dir}/System.map"

find "${dist_dir}" -maxdepth 1 -type f \( -name '*.dtb' -o -name '*.dtbo' \) \
  -delete

find "${out_dir}/arch/arm64/boot/dts" -type f \( \
  -name 'exynos9820.dtb' -o \
  -name 'exynos9820-beyond1lte_*.dtbo' \
  \) -exec install -m 0644 -t "${dist_dir}" {} +

find "${stage_dir}/lib/modules/${kernel_release}" -type f -name '*.ko' -printf '%P\n' | \
  sort > "${dist_dir}/modules.list"

tar \
  --exclude='./build' \
  --exclude='./source' \
  -C "${stage_dir}/lib/modules/${kernel_release}" \
  -cJf "${dist_dir}/modules-${kernel_release}.tar.xz" \
  .

if [[ -f "${reference_firmware_zip}" ]]; then
  "${root_dir}/scripts/lambdaos/package_reference_firmware.sh" \
    "${reference_firmware_zip}" \
    "${dist_dir}" \
    2>&1 | tee "${log_dir}/reference-firmware.log"
else
  printf 'Reference firmware archive not found; bundle skipped: %s\n' \
    "${reference_firmware_zip}" | tee "${log_dir}/reference-firmware.log"
fi

if [[ -d "${supplemental_firmware_dir}/mediatek" ]]; then
  tar \
    -C "${supplemental_firmware_dir}" \
    -cJf "${dist_dir}/firmware-linux-firmware-mt76.tar.xz" \
    mediatek LICENCE.mediatek SOURCE.md
else
  printf 'Supplemental MediaTek firmware directory not found; bundle skipped: %s\n' \
    "${supplemental_firmware_dir}" | tee "${log_dir}/supplemental-firmware.log"
fi

{
  printf 'kernel_release=%s\n' "${kernel_release}"
  printf 'source_commit=%s\n' "$(git -C "${root_dir}" rev-parse HEAD)"
  printf 'source_branch=%s\n' "$(git -C "${root_dir}" branch --show-current)"
  printf 'clang=%s\n' "$(clang --version | head -n 1)"
  printf 'gcc64=%s\n' "$("${CROSS_COMPILE}gcc" --version | head -n 1)"
  printf 'gcc32=%s\n' "$("${CROSS_COMPILE_ARM32}gcc" --version | head -n 1)"
  printf 'jobs=%s\n' "${jobs}"
  printf 'config_sha256=%s\n' "$(sha256sum "${out_dir}/.config" | cut -d' ' -f1)"
  printf 'rtl8188eus_commit=%s\n' "$(git -C "${root_dir}/drivers/net/wireless/realtek/rtl8188eus" rev-parse HEAD)"
  printf 'rtl8812au_commit=%s\n' "$(git -C "${root_dir}/drivers/net/wireless/realtek/rtl8812au" rev-parse HEAD)"
  printf 'rtl88x2bu_commit=%s\n' "$(git -C "${root_dir}/drivers/net/wireless/realtek/rtl88x2bu" rev-parse HEAD)"
  printf 'mt76_commit=%s\n' "$(git -C "${root_dir}/drivers/net/wireless/mediatek/mt76" rev-parse HEAD)"
  if [[ -f "${supplemental_firmware_dir}/SOURCE.md" ]]; then
    printf 'supplemental_firmware_source=%s\n' \
      "$(awk -F'`' '/^Pinned commit:/ { print $2; exit }' "${supplemental_firmware_dir}/SOURCE.md")"
  fi
} > "${dist_dir}/build-manifest.txt"

checksum_file="${log_dir}/SHA256SUMS.tmp"
rm -f "${checksum_file}"
(
  cd "${dist_dir}"
  find . -maxdepth 1 -type f ! -name SHA256SUMS -print0 | sort -z | \
    xargs -0 sha256sum > "${checksum_file}"
)
mv "${checksum_file}" "${dist_dir}/SHA256SUMS"

"${root_dir}/scripts/lambdaos/validate_build.sh" "${build_root}"

printf 'Kernel build completed successfully.\n'
printf 'Release: %s\n' "${kernel_release}"
printf 'Artifacts: %s\n' "${dist_dir}"
printf 'Staged modules: %s\n' "${stage_dir}/lib/modules/${kernel_release}"
