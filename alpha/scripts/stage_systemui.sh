#!/usr/bin/env bash
# Scoped native Keyguard integration. No authentication or firmware changes.
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
alpha_dir=$(cd -- "${script_dir}/.." && pwd)
source_dir=${1:?Expected Lineage source directory}
target=${source_dir}/frameworks/base
revision=e1d69875104b9004b7a8bcbd2af590af8f7fd41a
if [[ $(git -C "${target}" rev-parse HEAD) != "${revision}" ]]; then
    printf 'Unexpected frameworks/base revision; review Keyguard before staging.\n' >&2
    exit 1
fi
patch_file=${alpha_dir}/patches/systemui-lambda-keyguard.patch
# Keep the native LineageOS credential bouncer while retaining Lambda's
# fingerprint, clocks, navigation treatment and system palette.  These paths
# are intentionally excluded from the historical all-in-one UI12 patch.
stock_bouncer_paths=(
    packages/SystemUI/res-keyguard/drawable/num_pad_key_background.xml
    packages/SystemUI/res-keyguard/layout/bouncer_message_view.xml
    packages/SystemUI/res-keyguard/layout/keyguard_num_pad_key.xml
    packages/SystemUI/res-keyguard/layout/keyguard_pattern_motion_layout.xml
    packages/SystemUI/res-keyguard/layout/keyguard_pattern_view.xml
    packages/SystemUI/res-keyguard/layout/keyguard_pin_motion_layout.xml
    packages/SystemUI/res-keyguard/layout/keyguard_pin_view.xml
    packages/SystemUI/res-keyguard/values/arrays.xml
    packages/SystemUI/res-keyguard/values/dimens.xml
    packages/SystemUI/res-keyguard/values/styles.xml
    packages/SystemUI/src/com/android/keyguard/KeyguardPINView.java
    packages/SystemUI/src/com/android/keyguard/KeyguardSecurityContainer.java
    packages/SystemUI/src/com/android/keyguard/NumPadAnimator.java
    packages/SystemUI/src/com/android/keyguard/NumPadButton.java
    packages/SystemUI/src/com/android/keyguard/NumPadKey.java
    packages/SystemUI/src/com/android/keyguard/PinShapeHintingView.java
    packages/SystemUI/src/com/android/keyguard/PinShapeNonHintingView.java
    packages/SystemUI/src/com/android/systemui/bouncer/ui/binder/BouncerMessageViewBinder.kt
)
exclude_args=()
include_args=()
for path in "${stock_bouncer_paths[@]}"; do
    exclude_args+=(--exclude="${path}")
    include_args+=(--include="${path}")
done

# Migrate a source tree that already contains the older full patch by
# reverting only the presentation files above. Authentication is never edited.
if git -C "${target}" apply --reverse --check "${include_args[@]}" \
        "${patch_file}" >/dev/null 2>&1; then
    git -C "${target}" apply --reverse "${include_args[@]}" "${patch_file}"
    printf 'Restored native LineageOS PIN/pattern presentation\n'
fi

if git -C "${target}" apply --reverse --check "${exclude_args[@]}" "${patch_file}" >/dev/null 2>&1; then
    printf 'Lambda SystemUI patch with native bouncer already applied\n'
elif git -C "${target}" apply --check "${exclude_args[@]}" "${patch_file}"; then
    git -C "${target}" apply "${exclude_args[@]}" "${patch_file}"
else
    printf 'Keyguard patch conflicts; no existing source edits overwritten.\n' >&2
    exit 1
fi
# Retire only the five byte-identified UI16 files explicitly rejected by the user.
# Unknown edits cause a stop; all retired originals remain in the alpha archive.
retired=${alpha_dir}/retired/dialogs-ui16/alpha/platform/SystemUI
for relative in res/values/lambda_controls.xml res/values/lambda_controls_strings.xml \
    res/values-es/lambda_controls_strings.xml \
    src/com/android/systemui/qs/tiles/dialog/LambdaControlSheet.java \
    src/com/android/systemui/qs/tiles/dialog/LambdaQuickControls.java; do
    obsolete=${target}/packages/SystemUI/${relative}
    if [[ -f ${obsolete} ]]; then
        cmp -- "${retired}/${relative}" "${obsolete}" || {
            printf 'Retired UI16 file has unknown edits: %s\n' "${obsolete}" >&2
            exit 1
        }
        rm -- "${obsolete}"
    fi
done
rsync -a --omit-dir-times "${alpha_dir}/platform/SystemUI/" "${target}/packages/SystemUI/"
