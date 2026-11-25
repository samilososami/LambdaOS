#!/usr/bin/env bash
set -Eeuo pipefail

# Historical Alpha 1 helper only. Alpha 2 must sign the complete target-files,
# including APK/APEX identities and recovery trust, before producing the OTA.
if [[ ${LAMBDA_ALLOW_LEGACY_TESTKEY_OTA:-0} != 1 ]]; then
    printf 'Legacy test-key OTA helper disabled. Use sign_release.py for Alpha 2.\n' >&2
    exit 1
fi

# Build a first-install OTA that cannot restore LineageOS addon.d payloads
# (for example GApps or Magisk) from the previously installed system. Normal
# LambdaOS-to-LambdaOS update packages keep the standard addon.d behaviour.

if [[ ${EUID} -eq 0 ]] && id kali >/dev/null 2>&1; then
    exec runuser -u kali -- "$0" "$@"
fi

source_dir=${1:-/srv/lambdaos/src/lineage-22.2}
input_zip=${2:-}

if [[ -z ${input_zip} ]]; then
    input_zip=$(find "${source_dir}/out/target/product/beyond1lte" -maxdepth 1 \
        -type f -name 'lineage-*.zip' ! -name '*-clean-install.zip' \
        -printf '%T@ %p\n' | sort -nr | head -n 1 | cut -d' ' -f2-)
fi

if [[ -z ${input_zip} || ! -f ${input_zip} ]]; then
    printf 'A source OTA ZIP is required.\n' >&2
    exit 1
fi

case ${input_zip} in
    *.zip) output_zip=${input_zip%.zip}-clean-install.zip ;;
    *) printf 'Source OTA must end in .zip: %s\n' "${input_zip}" >&2; exit 1 ;;
esac

signapk_jar=${source_dir}/out/host/linux-x86/framework/signapk.jar
signapk_lib=${source_dir}/out/host/linux-x86/lib64
testkey_cert=${source_dir}/build/make/target/product/security/testkey.x509.pem
testkey_pk8=${source_dir}/build/make/target/product/security/testkey.pk8
updater_path=META-INF/com/google/android/updater-script

for required in "${signapk_jar}" "${testkey_cert}" "${testkey_pk8}"; do
    [[ -f ${required} ]] || { printf 'Missing required file: %s\n' "${required}" >&2; exit 1; }
done

work_dir=$(mktemp -d "${source_dir}/out/.lambda-clean-ota.XXXXXX")
trap 'rm -rf -- "${work_dir}"' EXIT
unsigned_zip=${work_dir}/unsigned.zip
patch_root=${work_dir}/patch
mkdir -p "${patch_root}/$(dirname "${updater_path}")"

unzip -p "${input_zip}" "${updater_path}" \
    > "${patch_root}/${updater_path}"

backup_count=$(grep -c 'backuptool\.sh".*, "backup"' \
    "${patch_root}/${updater_path}" || true)
restore_count=$(grep -c 'backuptool\.sh".*, "restore"' \
    "${patch_root}/${updater_path}" || true)
if [[ ${backup_count} -ne 1 || ${restore_count} -ne 1 ]]; then
    printf 'Expected exactly one addon.d backup and restore call; found %s/%s.\n' \
        "${backup_count}" "${restore_count}" >&2
    exit 1
fi

sed -i \
    -e '/backuptool\.sh".*, "backup"/d' \
    -e '/backuptool\.sh".*, "restore"/d' \
    "${patch_root}/${updater_path}"

cp --reflink=auto -- "${input_zip}" "${unsigned_zip}"
zip -q -d "${unsigned_zip}" "${updater_path}"
(
    cd "${patch_root}"
    zip -q -0 "${unsigned_zip}" "${updater_path}"
)

rm -f -- "${output_zip}" "${output_zip}.sha256"
java -Xmx4096m -Djava.library.path="${signapk_lib}" \
    -jar "${signapk_jar}" -w \
    "${testkey_cert}" "${testkey_pk8}" \
    "${unsigned_zip}" "${output_zip}"

unzip -tq "${output_zip}"
if unzip -p "${output_zip}" "${updater_path}" | grep -q 'backuptool\.sh'; then
    printf 'Clean-install OTA still contains addon.d migration calls.\n' >&2
    exit 1
fi

sha256sum "${output_zip}" | tee "${output_zip}.sha256"
printf 'LambdaOS clean-install OTA: %s\n' "${output_zip}"

