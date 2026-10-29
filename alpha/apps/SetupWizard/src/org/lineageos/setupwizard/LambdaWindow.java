/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.setupwizard;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/** Shared with the visual harness: one owner of insets, full-size background. */
final class LambdaWindow {
    static void configure(Activity activity) {
        Window window = activity.getWindow();
        window.setDecorFitsSystemWindows(false);
        WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        window.setAttributes(attrs);
        boolean light = new LambdaUi(activity).isLight();
        window.setBackgroundDrawable(new LambdaBackground(light));
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setStatusBarContrastEnforced(false);
        window.setNavigationBarContrastEnforced(false);
        window.getDecorView();
        WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars());
            controller.setSystemBarsAppearance(light ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
    }

    static void insetContent(View content, LambdaUi ui) {
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets safe = insets.getInsets(WindowInsets.Type.displayCutout());
            Insets nav = insets.getInsets(WindowInsets.Type.navigationBars());
            Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(ui.dp(24) + Math.max(safe.left, nav.left),
                    Math.max(ui.dp(12), safe.top),
                    ui.dp(24) + Math.max(safe.right, nav.right),
                    ui.dp(12) + Math.max(Math.max(safe.bottom, nav.bottom), keyboard.bottom));
            return WindowInsets.CONSUMED;
        });
        content.requestApplyInsets();
    }
}
