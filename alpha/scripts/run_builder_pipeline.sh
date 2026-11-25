#!/usr/bin/env bash
set -Euo pipefail

if [[ ${EUID} -eq 0 ]] && id kali >/dev/null 2>&1; then
    exec runuser -u kali -- "$0" "$@"
fi

umask 002
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source_dir=${1:-/srv/lambdaos/src/lineage-22.2}
downloads=/srv/lambdaos/downloads
log="${downloads}/lambda-alpha-build.log"
build_lock="${downloads}/lambda-alpha-build.lock"
sync_lock="${downloads}/lineage-22.2-sync.lock"
sync_success="${downloads}/lineage-22.2-sync.ok"

exec > >(tee -a "${log}") 2>&1

on_error() {
    local rc=$?
    local line=${BASH_LINENO[0]:-${LINENO}}
    printf '[%s] LambdaOS alpha pipeline failed at line %s (exit %s)\n' \
        "$(date -Is)" "${line}" "${rc}"
    exit "${rc}"
}
trap on_error ERR

exec 9>"${build_lock}"
if ! flock -n 9; then
    printf '[%s] Another LambdaOS alpha pipeline is already running\n' \
        "$(date -Is)"
    exit 0
fi

printf '[%s] LambdaOS alpha pipeline waiting for the base source sync\n' \
    "$(date -Is)"
exec 8>"${sync_lock}"
flock 8
flock -u 8
exec 8>&-

if [[ ! -s "${sync_success}" ]]; then
    printf '[%s] Base sync ended without a success marker; refusing to build an incomplete tree\n' \
        "$(date -Is)"
    exit 1
fi

printf '[%s] Base sync lock released; preparing the S10 source tree\n' \
    "$(date -Is)"
"${script_dir}/prepare_source_tree.sh" "${source_dir}"

printf '[%s] Source tree prepared; starting LambdaOS Alpha 0.1 build\n' \
    "$(date -Is)"
"${script_dir}/build_rom.sh" "${source_dir}"

printf '[%s] LambdaOS Alpha 0.1 build completed successfully\n' \
    "$(date -Is)"
