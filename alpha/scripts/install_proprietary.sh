#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -eq 0 ]] && id kali >/dev/null 2>&1; then
    exec runuser -u kali -- "$0" "$@"
fi

source_dir=${1:-/srv/lambdaos/src/lineage-22.2}
archive=${2:-/srv/lambdaos/downloads/lambda-beyond1lte-proprietary-20250627.tar.zst}
expected_archive_sha=355c3fb65e618b5bd88355595cc6599adfceaacc2d31e99cad5cd2fbe9cae9c3
expected_tree_sha=ce58ff6aafa45609b7e52ab0c6dd417ab820984697b5027f1f78e7b00c756877
expected_file_count=223
expected_byte_count=162024715

if [[ ! -f "${source_dir}/build/envsetup.sh" ]]; then
    printf 'LineageOS source tree is incomplete: %s\n' "${source_dir}" >&2
    exit 1
fi

if [[ ! -f "${archive}" ]]; then
    printf 'Missing proprietary archive: %s\n' "${archive}" >&2
    exit 1
fi

actual_archive_sha=$(sha256sum "${archive}" | awk '{print $1}')
if [[ "${actual_archive_sha}" != "${expected_archive_sha}" ]]; then
    printf 'Proprietary archive hash mismatch: %s\n' "${actual_archive_sha}" >&2
    exit 1
fi

tmp_root=$(mktemp -d /srv/lambdaos/.lambda-proprietary.XXXXXX)
trap 'rm -rf -- "${tmp_root}"' EXIT
tar --zstd --no-same-owner -xf "${archive}" -C "${tmp_root}"

metadata="${tmp_root}/proprietary-source.json"
vendor_root="${tmp_root}/vendor/samsung"
if [[ ! -f "${metadata}" ]] || [[ ! -d "${vendor_root}" ]]; then
    printf 'Invalid proprietary archive layout.\n' >&2
    exit 1
fi
jq -e . "${metadata}" >/dev/null

actual_file_count=$(find "${vendor_root}" -type f | wc -l)
actual_byte_count=$(find "${vendor_root}" -type f -printf '%s\n' |
    awk '{sum += $1} END {print sum + 0}')
actual_tree_sha=$(
    cd "${vendor_root}"
    find . -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum |
        awk '{print $1}'
)

if [[ "${actual_file_count}" != "${expected_file_count}" ]] ||
   [[ "${actual_byte_count}" != "${expected_byte_count}" ]] ||
   [[ "${actual_tree_sha}" != "${expected_tree_sha}" ]]; then
    printf 'Proprietary tree verification failed.\n' >&2
    printf 'files=%s bytes=%s tree=%s\n' \
        "${actual_file_count}" "${actual_byte_count}" "${actual_tree_sha}" >&2
    exit 1
fi

install -d "${source_dir}/vendor/samsung"
for module in beyond1lte exynos9820-common; do
    if [[ ! -d "${vendor_root}/${module}" ]]; then
        printf 'Missing proprietary module: %s\n' "${module}" >&2
        exit 1
    fi
    install -d "${source_dir}/vendor/samsung/${module}"
    rsync -a --delete \
        "${vendor_root}/${module}/" \
        "${source_dir}/vendor/samsung/${module}/"
done

printf 'Installed verified proprietary tree into %s/vendor/samsung\n' \
    "${source_dir}"
printf 'Archive SHA-256: %s\n' "${actual_archive_sha}"
printf 'Tree SHA-256: %s\n' "${actual_tree_sha}"
