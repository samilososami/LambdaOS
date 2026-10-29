#!/usr/bin/env bash
# Declare only the touch interfaces supported by the S10's existing driver.
# No binary, kernel, SELinux, init-service or firmware changes.
set -euo pipefail
if [[ ${EUID} -eq 0 ]] && id kali >/dev/null 2>&1; then
    exec runuser -u kali -- bash "$0" "$@"
fi
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
alpha_dir=$(cd -- "${script_dir}/.." && pwd)
source_dir=${1:?Expected Lineage source directory}
target=${source_dir}/device/samsung/exynos9820-common
revision=f6d51b43b12adb837cb935a51d881e53c0642f74
if [[ $(git -C "${target}" rev-parse HEAD) != "${revision}" ]]; then
    printf 'Unexpected exynos9820-common revision; review touch VINTF before staging.\n' >&2
    exit 1
fi
patch_file=${alpha_dir}/patches/exynos9820-lambda-touch-vintf.patch
if git -C "${target}" apply --reverse --check "${patch_file}" >/dev/null 2>&1; then
    printf 'Lambda touch VINTF declaration already applied\n'
elif git -C "${target}" apply --check "${patch_file}"; then
    git -C "${target}" apply "${patch_file}"
else
    printf 'Touch VINTF patch conflicts; no existing source edits overwritten.\n' >&2
    exit 1
fi
