#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -eq 0 ]] && id kali >/dev/null 2>&1; then
    exec runuser -u kali -- "$0" "$@"
fi

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
alpha_dir=$(cd -- "${script_dir}/.." && pwd)
source_dir=${1:-/srv/lambdaos/src/lineage-22.2}
jobs=${LAMBDA_SYNC_JOBS:-4}
manifest_dir="${source_dir}/.repo/local_manifests"

if [[ ! -d "${source_dir}/.repo" ]]; then
    printf 'repo has not been initialized in %s\n' "${source_dir}" >&2
    exit 1
fi

install -d "${manifest_dir}"
install -m 0644 "${alpha_dir}/manifests/beyond1lte.xml" \
    "${manifest_dir}/lambda-beyond1lte.xml"

cd "${source_dir}"
repo sync -c -j"${jobs}" --retry-fetches=8 --force-sync \
    --no-clone-bundle --no-tags --optimized-fetch --prune

repo manifest -r -o /srv/lambdaos/downloads/lineage-22.2-lock.xml
"${script_dir}/install_proprietary.sh" "${source_dir}"
"${script_dir}/stage_source_tree.sh" "${source_dir}"

printf 'Source tree prepared and revision lock written to %s\n' \
    /srv/lambdaos/downloads/lineage-22.2-lock.xml
