#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -eq 0 ]] && id kali >/dev/null 2>&1; then
    exec runuser -u kali -- "$0" "$@"
fi

source_dir=${1:-/srv/lambdaos/src/lineage-22.2}
quarantine_root=${2:-/srv/lambdaos/downloads/manifest-cache-quarantine-$(date +%Y%m%d-%H%M%S)}

if pgrep -f "${source_dir}/.repo/repo/main.py" >/dev/null; then
    printf 'Refusing to repair manifest cache while repo sync is running.\n' >&2
    exit 1
fi

paths=(
    device/samsung/beyond1lte
    device/samsung/exynos9820-common
    device/samsung_slsi/sepolicy
    hardware/samsung
    hardware/samsung_slsi-linaro/config
    hardware/samsung_slsi-linaro/exynos
    hardware/samsung_slsi-linaro/exynos5
    hardware/samsung_slsi-linaro/graphics
    hardware/samsung_slsi-linaro/interfaces
    hardware/samsung_slsi-linaro/openmax
    kernel/samsung/exynos9820
)

repos=(
    android_device_samsung_beyond1lte
    android_device_samsung_exynos9820-common
    android_device_samsung_slsi_sepolicy
    android_hardware_samsung
    android_hardware_samsung_slsi-linaro_config
    android_hardware_samsung_slsi-linaro_exynos
    android_hardware_samsung_slsi-linaro_exynos5
    android_hardware_samsung_slsi-linaro_graphics
    android_hardware_samsung_slsi-linaro_interfaces
    android_hardware_samsung_slsi-linaro_openmax
    android_kernel_samsung_exynos9820
)

move_if_present() {
    local source=$1
    local relative=$2
    local destination="${quarantine_root}/${relative}"
    if [[ -e "${source}" || -L "${source}" ]]; then
        install -d "$(dirname -- "${destination}")"
        mv -- "${source}" "${destination}"
        printf 'Quarantined %s\n' "${source}"
    fi
}

for index in "${!paths[@]}"; do
    path=${paths[${index}]}
    repo=${repos[${index}]}
    move_if_present "${source_dir}/${path}" "worktrees/${path}"
    move_if_present "${source_dir}/.repo/projects/${path}.git" \
        "projects/${path}.git"
    move_if_present "${source_dir}/.repo/project-objects/${repo}.git" \
        "project-objects/legacy/${repo}.git"
    move_if_present "${source_dir}/.repo/project-objects/LineageOS/${repo}.git" \
        "project-objects/LineageOS/${repo}.git"
done

printf 'Manifest cache repair complete. Quarantine: %s\n' "${quarantine_root}"
