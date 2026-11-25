#!/usr/bin/env bash
set -Euo pipefail

if [[ ${EUID} -eq 0 ]] && id kali >/dev/null 2>&1; then
    exec runuser -u kali -- "$0" "$@"
fi

umask 002
source_dir=${1:-/srv/lambdaos/src/lineage-22.2}
downloads=${LAMBDA_DOWNLOADS_DIR:-/srv/lambdaos/downloads}
log="${downloads}/lineage-22.2-sync.log"
lock="${downloads}/lineage-22.2-sync.lock"
success="${downloads}/lineage-22.2-sync.ok"
jobs=${LAMBDA_SYNC_JOBS:-4}
retry_delays=(0 60 180 300 600)

install -d "${source_dir}" "${downloads}"
exec 9>"${lock}"
if ! flock -n 9; then
    printf '[%s] Another LambdaOS source sync is already running\n' \
        "$(date -Is)" | tee -a "${log}"
    exit 0
fi

rm -f "${success}"
cd "${source_dir}"
printf '[%s] LambdaOS source preparation started\n' "$(date -Is)" | tee -a "${log}"
if [[ ! -d .repo ]]; then
    repo init -u https://github.com/LineageOS/android.git \
        -b lineage-22.2 --git-lfs 2>&1 | tee -a "${log}"
else
    printf '[%s] Existing repo checkout detected; reusing it\n' \
        "$(date -Is)" | tee -a "${log}"
fi

for index in "${!retry_delays[@]}"; do
    delay=${retry_delays[${index}]}
    attempt=$((index + 1))
    if (( delay > 0 )); then
        printf '[%s] Transient sync failure; retry %d/5 in %d seconds\n' \
            "$(date -Is)" "${attempt}" "${delay}" | tee -a "${log}"
        sleep "${delay}"
    fi

    printf '[%s] Starting repo sync attempt %d/5 with %d network jobs\n' \
        "$(date -Is)" "${attempt}" "${jobs}" | tee -a "${log}"
    set +e
    repo sync -c -j"${jobs}" --retry-fetches=5 --force-sync \
        --no-clone-bundle --no-tags --optimized-fetch --prune \
        2>&1 | tee -a "${log}"
    rc=${PIPESTATUS[0]}
    set -e
    if [[ ${rc} -eq 0 ]]; then
        {
            printf 'completed=%s\n' "$(date -Is)"
            printf 'manifest_sha256='
            sha256sum .repo/manifests/default.xml | awk '{print $1}'
        } >"${success}"
        printf '[%s] LineageOS source sync completed successfully\n' \
            "$(date -Is)" | tee -a "${log}"
        exit 0
    fi

    printf '[%s] Repo sync attempt %d/5 failed with exit code %d\n' \
        "$(date -Is)" "${attempt}" "${rc}" | tee -a "${log}"
done

printf '[%s] LineageOS source sync exhausted all retries\n' \
    "$(date -Is)" | tee -a "${log}"
exit 1
