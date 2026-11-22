#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workspace_dir="$(cd "${root_dir}/.." && pwd)"
reference_zip="${1:-${workspace_dir}/reference/nethunter-22.2/kernel-nethunter-20250630_123952-beyond1lte-los-22.2-fifteen.zip}"
output_dir="${2:-${workspace_dir}/builds/lambdaos-beyond1lte-capabilities-v1/dist}"

if [[ ! -f "${reference_zip}" ]]; then
  printf 'Reference firmware archive not found: %s\n' "${reference_zip}" >&2
  exit 1
fi

mkdir -p "${output_dir}"
temp_dir="$(mktemp -d)"
trap 'rm -rf "${temp_dir}"' EXIT

unzip -q "${reference_zip}" 'system/etc/firmware/*' -d "${temp_dir}"

firmware_root="${temp_dir}/system/etc/firmware"
if [[ ! -d "${firmware_root}" ]]; then
  printf 'The reference archive contains no system/etc/firmware tree.\n' >&2
  exit 1
fi

archive_name="lambdaos-beyond1lte-reference-firmware.tar.xz"
manifest_name="reference-firmware-manifest.txt"

tar -C "${temp_dir}/system/etc" -cJf "${output_dir}/${archive_name}" firmware

{
  printf 'role=reference-firmware-for-driver-validation\n'
  printf 'source_archive=%s\n' "$(basename "${reference_zip}")"
  printf 'source_archive_sha256=%s\n' \
    "$(sha256sum "${reference_zip}" | cut -d' ' -f1)"
  printf 'file_count=%s\n' "$(find "${firmware_root}" -type f | wc -l)"
  printf 'total_bytes=%s\n' \
    "$(find "${firmware_root}" -type f -printf '%s\n' | awk '{sum += $1} END {print sum + 0}')"
  printf '\n'
  (
    cd "${firmware_root}"
    find . -type f -print0 | sort -z | xargs -0 sha256sum
  )
} > "${output_dir}/${manifest_name}"

printf 'Firmware reference bundle: %s\n' "${output_dir}/${archive_name}"
printf 'Firmware manifest: %s\n' "${output_dir}/${manifest_name}"
printf 'This bundle is staged for review; it is not proof of hardware support.\n'
