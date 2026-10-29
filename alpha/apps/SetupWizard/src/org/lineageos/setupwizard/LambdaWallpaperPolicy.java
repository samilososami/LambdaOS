/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.setupwizard;

/** One-time provisioning policy, deliberately independent of theme observers. */
final class LambdaWallpaperPolicy {
    static boolean shouldApply(boolean restored, boolean applied, boolean provisioned) {
        return !restored && !applied && !provisioned;
    }

    static boolean isLight(String choice, boolean resolvedLight) {
        return "light".equals(choice) || ("auto".equals(choice) && resolvedLight);
    }

    private LambdaWallpaperPolicy() {}
}
