#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -eq 0 ]] && id kali >/dev/null 2>&1; then
    exec runuser -u kali -- "$0" "$@"
fi

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
alpha_dir=$(cd -- "${script_dir}/.." && pwd)
project_dir=$(cd -- "${alpha_dir}/.." && pwd)
source_dir=${1:-/srv/lambdaos/src/lineage-22.2}
expected_boot_sha=2b6acfdf1f9972b57f4ae31b0f41acfc02624e59bec1fbb3351b9e956b1b5155
bootanimation=${LAMBDA_BOOTANIMATION:-${project_dir}/design/round-05/boot-animation/android-test-v11-continuous-575/bootanimation.zip}
adb_keys=${LAMBDA_ADB_KEYS:-${alpha_dir}/private/adb/authorized_keys}
kernel_release=4.14.356-lambdaos+
kernel_dist=${LAMBDA_KERNEL_DIST:-${project_dir}/kernel/builds/lambdaos-beyond1lte-fcm-v4/dist}
kernel_image=${kernel_dist}/Image
recovery_kernel_image=${LAMBDA_RECOVERY_KERNEL_IMAGE:-${project_dir}/kernel/builds/lambdaos-beyond1lte-capabilities-v3-verified/dist/recovery/Image}
kernel_modules=${kernel_dist}/modules-${kernel_release}.tar.xz
kernel_reference_firmware=${kernel_dist}/lambdaos-beyond1lte-reference-firmware.tar.xz
kernel_mt76_firmware=${kernel_dist}/firmware-linux-firmware-mt76.tar.xz
expected_kernel_sha=9570b634be90b319e7eff960b927de764d098c33be3b33097a74d00b699d1217
expected_recovery_kernel_sha=59c8f88b2782cece0cce4c8aff8f33380c20874833c7d9c870d5d2db4aaa450f
expected_modules_sha=cb6d4663478a923cf12dbf9f1346ba5e63477529fe3e549b86ea1598dcb4e055
expected_reference_firmware_sha=731057d11daf3b77ef06b8f77e35906321ebf299c3f86147f9ec936baa3a722d
expected_mt76_firmware_sha=c4d212e317911a2ab1e2988144de34e8602272d0ae602d52cb515e5b822562f8
expected_module_count=95
expected_staged_firmware_count=74
device_revision=548f086b874423d9353f34afc6389c4177e84a56
device_patch=${alpha_dir}/patches/beyond1lte-lambda-kernel.patch
manifest_patches=(
    "${alpha_dir}/patches/lineage-build-manifest-repo-cache.patch"
    "${alpha_dir}/patches/lineage-build-manifest-xdg-cache.patch"
)
manifest_target=${source_dir}/vendor/lineage/build/tasks/build-manifest_xml.mk
manifest_final_marker='XDG_CONFIG_HOME=$(OUT_DIR)/repo-config REPO_CONFIG_DIR=$(OUT_DIR)/repo-config'

verify_sha256() {
    local path=$1
    local expected=$2
    local label=$3
    local actual

    if [[ ! -s "${path}" ]]; then
        printf 'Missing %s: %s\n' "${label}" "${path}" >&2
        exit 1
    fi
    actual=$(sha256sum "${path}" | awk '{print $1}')
    if [[ "${actual}" != "${expected}" ]]; then
        printf '%s hash mismatch: expected %s, got %s\n' \
            "${label}" "${expected}" "${actual}" >&2
        exit 1
    fi
}

if [[ ! -f "${bootanimation}" ]] && [[ -f "${alpha_dir}/bootanimation.zip" ]]; then
    bootanimation="${alpha_dir}/bootanimation.zip"
fi

if [[ ! -f "${source_dir}/build/envsetup.sh" ]]; then
    printf 'LineageOS source tree is incomplete: %s\n' "${source_dir}" >&2
    exit 1
fi

if [[ ! -s "${adb_keys}" ]]; then
    printf 'Missing LambdaOS ADB authorization file: %s\n' "${adb_keys}" >&2
    exit 1
fi

for manifest_patch in "${manifest_patches[@]}"; do
    if [[ ! -s "${manifest_patch}" ]]; then
        printf 'Missing Lineage build-manifest patch: %s\n' "${manifest_patch}" >&2
        exit 1
    fi
done

if [[ ! -s "${device_patch}" ]]; then
    printf 'Missing beyond1lte kernel integration patch: %s\n' "${device_patch}" >&2
    exit 1
fi

verify_sha256 "${kernel_image}" "${expected_kernel_sha}" 'LambdaOS kernel Image'
verify_sha256 "${recovery_kernel_image}" \
    "${expected_recovery_kernel_sha}" 'LambdaOS recovery kernel Image'
verify_sha256 "${kernel_modules}" "${expected_modules_sha}" 'LambdaOS kernel modules'
verify_sha256 "${kernel_reference_firmware}" \
    "${expected_reference_firmware_sha}" 'LambdaOS reference firmware'
verify_sha256 "${kernel_mt76_firmware}" \
    "${expected_mt76_firmware_sha}" 'LambdaOS MT76 firmware'

archive_module_count=$(tar -tJf "${kernel_modules}" | sed -n '/\.ko$/p' | wc -l)
if [[ "${archive_module_count}" -ne "${expected_module_count}" ]]; then
    printf 'Kernel module archive contains %s modules; expected %s.\n' \
        "${archive_module_count}" "${expected_module_count}" >&2
    exit 1
fi

for archive in \
    "${kernel_modules}" \
    "${kernel_reference_firmware}" \
    "${kernel_mt76_firmware}"; do
    if tar -tJf "${archive}" | grep -Eq '(^|/)\.\.(/|$)|^/'; then
        printf 'Unsafe path found in archive: %s\n' "${archive}" >&2
        exit 1
    fi
done

if grep -Evq '^[A-Za-z0-9+/]+={0,2}([[:space:]]+[^[:space:]]+)?$' "${adb_keys}"; then
    printf 'Invalid ADB public-key line in %s\n' "${adb_keys}" >&2
    exit 1
fi

if [[ ! -f "${source_dir}/device/samsung/beyond1lte/lineage_beyond1lte.mk" ]]; then
    printf 'Missing beyond1lte device tree. Run prepare_source_tree.sh first.\n' >&2
    exit 1
fi

device_target=${source_dir}/device/samsung/beyond1lte
if [[ $(git -C "${device_target}" rev-parse HEAD) != "${device_revision}" ]]; then
    printf 'Unexpected beyond1lte device-tree revision; review before staging.\n' >&2
    exit 1
fi
if git -C "${device_target}" apply --recount --reverse --check \
    "${device_patch}" >/dev/null 2>&1; then
    printf 'Lambda beyond1lte kernel integration already applied\n'
elif git -C "${device_target}" apply --recount --check "${device_patch}"; then
    git -C "${device_target}" apply --recount "${device_patch}"
else
    printf 'Beyond1lte kernel patch conflicts; no local edits overwritten.\n' >&2
    exit 1
fi

for vendor_makefile in \
    vendor/samsung/beyond1lte/beyond1lte-vendor.mk \
    vendor/samsung/exynos9820-common/exynos9820-common-vendor.mk; do
    if [[ ! -f "${source_dir}/${vendor_makefile}" ]]; then
        printf 'Missing proprietary file %s. Run prepare_source_tree.sh first.\n' \
            "${vendor_makefile}" >&2
        exit 1
    fi
done

actual_boot_sha=$(sha256sum "${bootanimation}" | awk '{print $1}')
if [[ "${actual_boot_sha}" != "${expected_boot_sha}" ]]; then
    printf 'Bootanimation hash mismatch: %s\n' "${actual_boot_sha}" >&2
    exit 1
fi

install -d "${source_dir}/packages/apps/Lambda"
rsync -a --omit-dir-times --delete "${alpha_dir}/apps/Lambda/" "${source_dir}/packages/apps/Lambda/"

# This is the reviewed, attributed upstream fork, not a second competing HOME app.
# Preserve Git metadata and stop on unexpected upstream revisions.
setup_target=${source_dir}/packages/apps/SetupWizard
if [[ $(git -C "${setup_target}" rev-parse HEAD) != a064c564fb6c7e7f82d32fb06bf34c2ca2e24baa ]]; then
    printf 'Unexpected SetupWizard revision; review the integration before staging.\n' >&2
    exit 1
fi
# Keep directory mtimes fresh when Java/resources are added. Restoring older
# directory times can make Soong's cached globs omit a newly copied source file.
rsync -a --omit-dir-times --delete --exclude='.git' "${alpha_dir}/apps/SetupWizard/" "${setup_target}/"

settings_target=${source_dir}/packages/apps/Settings
settings_patch=${alpha_dir}/patches/settings-lambda-setup.patch
if git -C "${settings_target}" apply --recount --unidiff-zero --reverse --check "${settings_patch}" >/dev/null 2>&1; then
    printf 'Lambda Settings integration already applied\n'
elif git -C "${settings_target}" apply --recount --unidiff-zero --check "${settings_patch}"; then
    git -C "${settings_target}" apply --recount --unidiff-zero "${settings_patch}"
else
    printf 'Settings patch does not apply cleanly; refusing to overwrite local edits.\n' >&2
    exit 1
fi
rsync -a --omit-dir-times "${alpha_dir}/platform/Settings/" "${settings_target}/"

# Native first-run screens remain the real platform implementations. Apply only
# reviewed, scoped hooks and fail closed on upstream drift/local conflicts. The
# explicit None option still delegates authentication/policy/removal to Settings.
for native_spec in \
    'Settings:packages/apps/Settings:e1aa3ebed37df620b2135de0c6680f0912bd73ed:settings-lambda-native.patch' \
    'Settings:packages/apps/Settings:e1aa3ebed37df620b2135de0c6680f0912bd73ed:settings-lambda-no-lock.patch' \
    'Settings:packages/apps/Settings:e1aa3ebed37df620b2135de0c6680f0912bd73ed:settings-lambda-fingerprint-copy.patch' \
    'Settings:packages/apps/Settings:e1aa3ebed37df620b2135de0c6680f0912bd73ed:settings-lambda-fingerprint-decline.patch' \
    'Settings:packages/apps/Settings:e1aa3ebed37df620b2135de0c6680f0912bd73ed:settings-lambda-fingerprint-focus.patch' \
    'Settings:packages/apps/Settings:e1aa3ebed37df620b2135de0c6680f0912bd73ed:settings-lambda-accessibility-dialog.patch' \
    'Settings:packages/apps/Settings:e1aa3ebed37df620b2135de0c6680f0912bd73ed:settings-lambda-wifi-picker.patch' \
    'Settings:packages/apps/Settings:e1aa3ebed37df620b2135de0c6680f0912bd73ed:settings-lambda-build-number.patch' \
    'Telephony:packages/services/Telephony:4fe0d7081464f0d3eddf97f65ef11f63cf090dbe:telephony-lambda-native.patch' \
    'Seedvault:packages/apps/Seedvault:62e13bbabfb97eea45595bc992586de8e11a03ce:seedvault-lambda-native.patch'; do
    IFS=: read -r native_name native_path native_revision native_patch_name <<< "${native_spec}"
    native_target=${source_dir}/${native_path}
    native_patch=${alpha_dir}/patches/${native_patch_name}
    if [[ $(git -C "${native_target}" rev-parse HEAD) != "${native_revision}" ]]; then
        printf 'Unexpected %s revision; review before staging.\n' "${native_name}" >&2
        exit 1
    fi
    if git -C "${native_target}" apply --recount --reverse --check "${native_patch}" >/dev/null 2>&1; then
        printf 'Lambda native %s integration already applied\n' "${native_name}"
    elif git -C "${native_target}" apply --recount --check "${native_patch}"; then
        git -C "${native_target}" apply --recount "${native_patch}"
    else
        printf 'Native %s patch conflicts; no local edits overwritten.\n' "${native_name}" >&2
        exit 1
    fi
    rsync -a --omit-dir-times "${alpha_dir}/platform/${native_name}/" "${native_target}/"
done

bash "${script_dir}/stage_systemui.sh" "${source_dir}"
bash "${script_dir}/stage_touch_hal.sh" "${source_dir}"

boot_patch=${alpha_dir}/patches/lineage-bootanimation-input.patch
if git -C "${source_dir}/vendor/lineage" apply --recount --unidiff-zero --reverse --check "${boot_patch}" >/dev/null 2>&1; then
    printf 'Lambda bootanimation input dependency already applied\n'
else
    git -C "${source_dir}/vendor/lineage" apply --recount --unidiff-zero --check "${boot_patch}"
    git -C "${source_dir}/vendor/lineage" apply --recount --unidiff-zero "${boot_patch}"
fi

install -d "${source_dir}/vendor/lambda"
rsync -a --delete \
    --exclude '/prebuilt/' \
    "${alpha_dir}/vendor/lambda/" "${source_dir}/vendor/lambda/"
install -d "${source_dir}/vendor/lambda/prebuilt"
install -m 0644 "${bootanimation}" "${source_dir}/vendor/lambda/prebuilt/bootanimation.zip"
kernel_prebuilt=${source_dir}/vendor/lambda/prebuilt/kernel
kernel_modules_dir=${kernel_prebuilt}/modules
kernel_firmware_dir=${kernel_prebuilt}/firmware
install -d "${kernel_modules_dir}" "${kernel_firmware_dir}"
find "${kernel_modules_dir}" -mindepth 1 -delete
find "${kernel_firmware_dir}" -mindepth 1 -delete
install -m 0644 "${kernel_image}" "${kernel_prebuilt}/Image"
install -m 0644 "${recovery_kernel_image}" "${kernel_prebuilt}/recovery-Image"
tar -xJf "${kernel_modules}" -C "${kernel_modules_dir}"
llvm_strip=${source_dir}/prebuilts/clang/host/linux-x86/llvm-binutils-stable/llvm-strip
if [[ ! -x "${llvm_strip}" ]]; then
    printf 'Missing pinned Android LLVM strip tool: %s\n' "${llvm_strip}" >&2
    exit 1
fi
find "${kernel_modules_dir}" -type f -name '*.ko' -print0 | \
    xargs -0 -r "${llvm_strip}" --strip-debug
# No module is loaded automatically. lambda-kmod/modprobe resolves the archived
# depmod graph on demand from /vendor/lib/modules/$(uname -r).
: > "${kernel_modules_dir}/modules.load"

modules_makefile=${source_dir}/vendor/lambda/kernel/modules.generated.mk
modules_makefile_tmp=${modules_makefile}.tmp
module_names=()
: > "${modules_makefile_tmp}"
printf '%s\n' \
    '# Auto-generated by LambdaOS stage_source_tree.sh; do not edit in AOSP.' \
    '# Sources are hash-verified and stripped before these modules are declared.' \
    >> "${modules_makefile_tmp}"
while IFS= read -r module_rel; do
    module_key=$(printf '%s' "${module_rel}" | sha256sum | cut -c1-12)
    module_name=lambda_kernel_file_${module_key}
    module_dir=$(dirname -- "${module_rel}")
    module_stem=$(basename -- "${module_rel}")
    module_names+=("${module_name}")
    printf '\ninclude $(CLEAR_VARS)\n' >> "${modules_makefile_tmp}"
    printf 'LOCAL_MODULE := %s\n' "${module_name}" >> "${modules_makefile_tmp}"
    printf 'LOCAL_MODULE_CLASS := ETC\n' >> "${modules_makefile_tmp}"
    printf 'LOCAL_MODULE_TAGS := optional\n' >> "${modules_makefile_tmp}"
    printf 'LOCAL_MODULE_OWNER := lambda\n' >> "${modules_makefile_tmp}"
    printf 'LOCAL_PREBUILT_MODULE_FILE := $(LOCAL_PATH)/prebuilt/kernel/modules/%s\n' \
        "${module_rel}" >> "${modules_makefile_tmp}"
    if [[ "${module_dir}" == '.' ]]; then
        printf 'LOCAL_MODULE_PATH := $(TARGET_OUT_VENDOR)/lib/modules/%s\n' \
            "${kernel_release}" >> "${modules_makefile_tmp}"
    else
        printf 'LOCAL_MODULE_PATH := $(TARGET_OUT_VENDOR)/lib/modules/%s/%s\n' \
            "${kernel_release}" "${module_dir}" >> "${modules_makefile_tmp}"
    fi
    printf 'LOCAL_MODULE_STEM := %s\n' "${module_stem}" >> "${modules_makefile_tmp}"
    printf 'include $(BUILD_PREBUILT)\n' >> "${modules_makefile_tmp}"
done < <(find "${kernel_modules_dir}" -type f -printf '%P\n' | LC_ALL=C sort)
printf '\ninclude $(CLEAR_VARS)\n' >> "${modules_makefile_tmp}"
printf 'LOCAL_MODULE := lambda-kernel-payload\n' >> "${modules_makefile_tmp}"
printf 'LOCAL_MODULE_TAGS := optional\n' >> "${modules_makefile_tmp}"
printf 'LOCAL_REQUIRED_MODULES :=' >> "${modules_makefile_tmp}"
printf ' %s' "${module_names[@]}" >> "${modules_makefile_tmp}"
printf '\ninclude $(BUILD_PHONY_PACKAGE)\n' >> "${modules_makefile_tmp}"
mv -f -- "${modules_makefile_tmp}" "${modules_makefile}"

tar -xJf "${kernel_reference_firmware}" --strip-components=1 \
    -C "${kernel_firmware_dir}" firmware
tar -xJf "${kernel_mt76_firmware}" -C "${kernel_firmware_dir}" mediatek
# The Android base already installs rt2870.bin through
# external/linux-firmware/rt2800usb. Keep that single authoritative copy and
# avoid two build rules targeting vendor/firmware/rt2870.bin.
rm -f -- "${kernel_firmware_dir}/rt2870.bin"

staged_module_count=$(find "${kernel_modules_dir}" -type f -name '*.ko' | wc -l)
if [[ "${staged_module_count}" -ne "${expected_module_count}" ]]; then
    printf 'Staged %s kernel modules; expected %s.\n' \
        "${staged_module_count}" "${expected_module_count}" >&2
    exit 1
fi
staged_firmware_count=$(find "${kernel_firmware_dir}" -type f | wc -l)
if [[ "${staged_firmware_count}" -ne "${expected_staged_firmware_count}" ]]; then
    printf 'Staged %s firmware files; expected %s.\n' \
        "${staged_firmware_count}" "${expected_staged_firmware_count}" >&2
    exit 1
fi
install -m 0644 "${alpha_dir}/config/alpha.json" "${source_dir}/vendor/lambda/config/alpha.json"
install -d "${source_dir}/vendor/lambda/security"
install -m 0644 "${adb_keys}" "${source_dir}/vendor/lambda/security/adb_keys"

if grep -Fq "${manifest_final_marker}" "${manifest_target}"; then
    printf 'Final Lineage build-manifest cache patch already applied\n'
else
    for manifest_patch in "${manifest_patches[@]}"; do
        if git -C "${source_dir}/vendor/lineage" apply --reverse --check \
            "${manifest_patch}" >/dev/null 2>&1; then
            printf 'Lineage patch already applied: %s\n' \
                "$(basename "${manifest_patch}")"
        elif git -C "${source_dir}/vendor/lineage" apply --check \
            "${manifest_patch}" >/dev/null 2>&1; then
            git -C "${source_dir}/vendor/lineage" apply "${manifest_patch}"
            printf 'Applied Lineage patch: %s\n' "$(basename "${manifest_patch}")"
        else
            printf 'Lineage patch does not apply cleanly: %s\n' \
                "${manifest_patch}" >&2
            exit 1
        fi
    done
fi

printf 'Staged LambdaOS alpha into %s\n' "${source_dir}"
printf 'Bootanimation SHA-256: %s\n' "${actual_boot_sha}"
printf 'Kernel release: %s (%s modules)\n' \
    "${kernel_release}" "${staged_module_count}"
printf 'Supplemental firmware files: %s\n' "${staged_firmware_count}"
printf 'Kernel Image SHA-256: %s\n' "${expected_kernel_sha}"
printf 'Recovery kernel Image SHA-256: %s\n' \
    "${expected_recovery_kernel_sha}"
printf 'ADB authorization SHA-256: %s\n' "$(sha256sum "${adb_keys}" | awk '{print $1}')"
