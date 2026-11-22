#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
build_root="${1:-}"

if [[ -z "${build_root}" ]]; then
  printf 'Usage: %s BUILD_ROOT\n' "$0" >&2
  exit 2
fi

dist_dir="${build_root}/dist"
stage_dir="${build_root}/staging"
manifest="${dist_dir}/build-manifest.txt"

for required in \
  "${dist_dir}/Image" \
  "${dist_dir}/lambdaos_beyond1lte.config" \
  "${dist_dir}/SHA256SUMS" \
  "${dist_dir}/System.map" \
  "${manifest}"; do
  if [[ ! -f "${required}" ]]; then
    printf 'Missing build artifact: %s\n' "${required}" >&2
    exit 1
  fi
done

embedded_config="$(mktemp)"
depmod_log="$(mktemp)"
trap 'rm -f "${embedded_config}" "${depmod_log}"' EXIT

if ! "${root_dir}/scripts/extract-ikconfig" "${dist_dir}/Image" \
  >"${embedded_config}"; then
  printf 'Unable to extract the embedded configuration from: %s\n' \
    "${dist_dir}/Image" >&2
  exit 1
fi

if ! cmp -s "${dist_dir}/lambdaos_beyond1lte.config" "${embedded_config}"; then
  printf 'Embedded Image configuration differs from the final build configuration.\n' >&2
  diff -u "${dist_dir}/lambdaos_beyond1lte.config" "${embedded_config}" >&2 || true
  exit 1
fi

kernel_release="$(sed -n 's/^kernel_release=//p' "${manifest}")"
module_root="${stage_dir}/lib/modules/${kernel_release}"

if [[ -z "${kernel_release}" || ! -d "${module_root}" ]]; then
  printf 'Invalid kernel release or module tree: %s\n' "${kernel_release}" >&2
  exit 1
fi

(
  cd "${dist_dir}"
  sha256sum -c SHA256SUMS
)

module_count="$(find "${module_root}" -type f -name '*.ko' | wc -l)"
if [[ "${module_count}" -ne 95 ]]; then
  printf 'Unexpected module count: %s (expected 95)\n' "${module_count}" >&2
  exit 1
fi

mt76_modules=(
  "kernel/drivers/net/wireless/mediatek/mt76/mt76.ko"
  "kernel/drivers/net/wireless/mediatek/mt76/mt76-usb.ko"
  "kernel/drivers/net/wireless/mediatek/mt76/mt76x0/mt76x0.ko"
  "kernel/drivers/net/wireless/mediatek/mt76/mt76x2-common.ko"
  "kernel/drivers/net/wireless/mediatek/mt76/mt76x2u.ko"
)

for relative_module in "${mt76_modules[@]}"; do
  module="${module_root}/${relative_module}"
  if [[ ! -f "${module}" ]]; then
    printf 'Missing mt76 module: %s\n' "${module}" >&2
    exit 1
  fi
  if ! modinfo "${module}" | grep -Fq "vermagic:       ${kernel_release}"; then
    printf 'Wrong vermagic for: %s\n' "${module}" >&2
    exit 1
  fi
done

depmod -e -F "${dist_dir}/System.map" -b "${stage_dir}" "${kernel_release}" \
  >"${depmod_log}" 2>&1 || {
    cat "${depmod_log}" >&2
    exit 1
  }

if grep -Eq 'needs unknown symbol|ERROR' "${depmod_log}"; then
  cat "${depmod_log}" >&2
  exit 1
fi

printf 'Validated kernel release: %s\n' "${kernel_release}"
printf 'Embedded Image configuration matches the final build configuration.\n'
printf 'Validated module count: %s\n' "${module_count}"
printf 'No unresolved module symbols detected.\n'
printf 'Source tree: %s\n' "${root_dir}"
