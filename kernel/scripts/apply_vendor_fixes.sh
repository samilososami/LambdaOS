#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fix_dir="${root_dir}/scripts/lambdaos/vendor-fixes"

apply_fix() {
  local relative_tree="$1"
  local patch_name="$2"
  local target="${root_dir}/${relative_tree}"
  local patch_file="${fix_dir}/${patch_name}"

  if [[ ! -d "${target}/.git" && ! -f "${target}/.git" ]]; then
    printf 'Required submodule is not initialized: %s\n' "${relative_tree}" >&2
    printf 'Run: git submodule update --init --recursive\n' >&2
    exit 1
  fi

  if git -C "${target}" apply --check "${patch_file}" 2>/dev/null; then
    git -C "${target}" apply "${patch_file}"
    printf 'Applied %s to %s\n' "${patch_name}" "${relative_tree}"
  elif git -C "${target}" apply --reverse --check "${patch_file}" 2>/dev/null; then
    printf 'Already applied: %s\n' "${patch_name}"
  else
    printf 'Cannot apply or recognize vendor fix %s in %s\n' \
      "${patch_name}" "${relative_tree}" >&2
    exit 1
  fi
}

apply_fix \
  drivers/net/wireless/realtek/rtl8812au \
  rtl8812au-clang10.diff
apply_fix \
  drivers/net/wireless/realtek/rtl88x2bu \
  rtl88x2bu-clang10.diff
