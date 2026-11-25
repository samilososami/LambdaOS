#!/usr/bin/env bash
# Android's envsetup/lunch shell functions intentionally probe unset variables.
# Keep fail-fast and pipe error handling, but do not enable nounset here.
set -eo pipefail

if [[ ${EUID} -eq 0 ]] && id kali >/dev/null 2>&1; then
    exec runuser -u kali -- "$0" "$@"
fi

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source_dir=${1:-/srv/lambdaos/src/lineage-22.2}
jobs=${LAMBDA_BUILD_JOBS:-10}

export USE_CCACHE=1
export CCACHE_EXEC=/usr/bin/ccache
export CCACHE_DIR=/var/cache/lambdaos/ccache
export CCACHE_COMPRESS=1

# Keep direct `repo` invocations away from the regular home directory while
# Android is building.  The late Ninja manifest rule needs the same setting on
# its command line; stage_source_tree.sh applies that reproducible Lineage patch.
export REPO_CONFIG_DIR="${source_dir}/out/repo-config"
export XDG_CONFIG_HOME="${REPO_CONFIG_DIR}"
mkdir -p "${XDG_CONFIG_HOME}/git"
touch "${XDG_CONFIG_HOME}/git/config"

"${script_dir}/stage_source_tree.sh" "${source_dir}"

# An interrupted incremental build can leave KERNEL_OBJ/.config newer than the
# copied Image. Ninja intentionally rejects that timestamp ordering instead of
# risking a stale kernel. Remove only the derived Image so Kbuild regenerates it
# while preserving every other completed object.
kernel_obj=${source_dir}/out/target/product/beyond1lte/obj/KERNEL_OBJ
kernel_image=${kernel_obj}/arch/arm64/boot/Image
if [[ -f "${kernel_obj}/.config" && -f "${kernel_image}" && \
      "${kernel_obj}/.config" -nt "${kernel_image}" ]]; then
    printf 'Removing stale incremental kernel Image before resume\n'
    rm -f -- "${kernel_image}"
fi

cd "${source_dir}"
# shellcheck disable=SC1091
source build/envsetup.sh
lunch lineage_lambda_beyond1lte-bp1a-userdebug
mka bacon -j"${jobs}"

artifact=$(find "${source_dir}/out/target/product/beyond1lte" -maxdepth 1 \
    -type f -name 'lineage-*.zip' -printf '%T@ %p\n' | sort -nr | head -n 1 | cut -d' ' -f2-)
if [[ -z "${artifact}" ]]; then
    printf 'Build finished but no OTA ZIP was found.\n' >&2
    exit 1
fi

sha256sum "${artifact}" | tee "${artifact}.sha256"
printf 'LambdaOS alpha candidate: %s\n' "${artifact}"
