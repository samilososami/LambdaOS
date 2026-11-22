#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
out_dir="${1:-${root_dir}/out/lambdaos-config}"
base_config="${root_dir}/arch/arm64/configs/nethunter_beyond1lte_defconfig"
feature_config="${root_dir}/arch/arm64/configs/lambdaos_beyond1lte_pentest.config"
output_defconfig="${root_dir}/arch/arm64/configs/lambdaos_beyond1lte_defconfig"

mkdir -p "${out_dir}"

ARCH=arm64 "${root_dir}/scripts/kconfig/merge_config.sh" \
  -m -O "${out_dir}" \
  "${base_config}" \
  "${feature_config}"

make -C "${root_dir}" \
  O="${out_dir}" \
  ARCH=arm64 \
  CROSS_COMPILE="${CROSS_COMPILE:-aarch64-linux-gnu-}" \
  olddefconfig

config_mismatches=0
while IFS= read -r expected; do
  if [[ "${expected}" =~ ^#\ (CONFIG_[A-Za-z0-9_]+)\ is\ not\ set$ ]]; then
    key="${BASH_REMATCH[1]}"
  else
    key="${expected%%=*}"
  fi
  actual="$(grep -E "^(${key}=|# ${key} is not set$)" "${out_dir}/.config" || true)"
  if [[ "${actual}" != "${expected}" ]]; then
    printf 'Configuration mismatch: expected %s, resolved %s\n' \
      "${expected}" "${actual:-<unset>}" >&2
    config_mismatches=$((config_mismatches + 1))
  fi
done < <(grep -E '^(CONFIG_[A-Za-z0-9_]+=|# CONFIG_[A-Za-z0-9_]+ is not set$)' "${feature_config}")

if (( config_mismatches > 0 )); then
  printf 'Refusing to emit a defconfig with %d unresolved option(s).\n' \
    "${config_mismatches}" >&2
  exit 1
fi

make -C "${root_dir}" \
  O="${out_dir}" \
  ARCH=arm64 \
  CROSS_COMPILE="${CROSS_COMPILE:-aarch64-linux-gnu-}" \
  savedefconfig

cp "${out_dir}/defconfig" "${output_defconfig}"

echo "Generated ${output_defconfig}"
echo "Resolved configuration: ${out_dir}/.config"
