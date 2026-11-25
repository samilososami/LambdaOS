#!/usr/bin/env bash
# Build an unsigned development target-files input; release signing is separate.
set -eo pipefail
if [[ ${EUID} -eq 0 ]] && id kali >/dev/null 2>&1; then
    exec runuser -u kali -- bash "$0" "$@"
fi
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source_dir=${1:-/srv/lambdaos/src/lineage-22.2}
export USE_CCACHE=1 CCACHE_EXEC=/usr/bin/ccache CCACHE_DIR=/var/cache/lambdaos/ccache CCACHE_COMPRESS=1
export REPO_CONFIG_DIR="${source_dir}/out/repo-config" XDG_CONFIG_HOME="${source_dir}/out/repo-config"
mkdir -p "${XDG_CONFIG_HOME}/git"
touch "${XDG_CONFIG_HOME}/git/config"
bash "${script_dir}/stage_source_tree.sh" "${source_dir}"
cd "${source_dir}"
source build/envsetup.sh
lunch lineage_lambda_beyond1lte-bp1a-userdebug
printf 'LambdaOS Alpha 2 compilation started: %s\n' "$(date -Is)"
m LineageSetupWizard Lambda Settings TeleService Seedvault SystemUI \
    LambdaAccessibilityFramework LambdaAccessibilitySystemUI \
    lambda-kmod -j"${LAMBDA_BUILD_JOBS:-10}"
python3 "${script_dir}/verify_lockscreen_apk.py" --source "${source_dir}"
if [[ ${LAMBDA_MODULES_ONLY:-0} == 1 ]]; then
    printf 'Lambda native modules ready: %s\n' "$(date -Is)"
    exit 0
fi
m target-files-package otatools -j"${LAMBDA_BUILD_JOBS:-10}"
printf 'LambdaOS Alpha 2 target-files ready: %s\n' "$(date -Is)"
