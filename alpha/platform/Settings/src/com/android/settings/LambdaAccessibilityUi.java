/* SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Lambda Project.
 */
package com.android.settings;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.appcompat.app.AlertDialog;

/** Presentation of the first-run shortcut tutorial only. The native action,
 * accessibility shortcut, focus handling and dismiss behavior are unchanged. */
public final class LambdaAccessibilityUi {
    private LambdaAccessibilityUi() {}

    public static void prepareTutorial(Context context, AlertDialog dialog) {
        Activity activity = activity(context);
        if (activity == null || !LambdaNativeUi.isEnabled(activity)) return;
        // The upstream first-run tutorial has no on-show callback. Do not use
        // this helper on other dialogs that may already have a native callback.
        dialog.setOnShowListener(ignored -> {
            Button button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (button == null || !(button.getParent() instanceof LinearLayout)) return;
            LinearLayout panel = (LinearLayout) button.getParent();
            float density = activity.getResources().getDisplayMetrics().density;
            // Keep the action away from either edge-anchored floating control,
            // including its large 72dp footprint, without moving or hiding it.
            int gutter = Math.round(80 * density);
            panel.setPadding(gutter, panel.getPaddingTop(), gutter, panel.getPaddingBottom());
            panel.setGravity(Gravity.CENTER_HORIZONTAL);
            int spacer = activity.getResources().getIdentifier("spacer", "id", activity.getPackageName());
            View gap = spacer == 0 ? null : panel.findViewById(spacer);
            if (gap != null) gap.setVisibility(View.GONE);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) button.getLayoutParams();
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.weight = 0;
            params.gravity = Gravity.CENTER_HORIZONTAL;
            params.setMargins(0, Math.round(8*density), 0, Math.round(8*density));
            button.setLayoutParams(params);
            button.setMinHeight(Math.round(48*density));
            button.setMinimumHeight(Math.round(48*density));
            button.setPadding(Math.round(16*density), Math.round(12*density),
                    Math.round(16*density), Math.round(12*density));
            button.setTypeface(activity.getResources().getFont(R.font.lambda_bold));
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            boolean light=LambdaNativeUi.isLight(activity);
            button.setTextColor(light?Color.rgb(247,247,247):Color.rgb(8,8,8));
            button.setAllCaps(false);
            button.setSingleLine(false);
            button.setEllipsize(null);
            StateListDrawable background = new StateListDrawable();
            background.addState(new int[]{android.R.attr.state_pressed}, rounded(light?0xff353535:0xffcecece, density));
            background.addState(new int[]{android.R.attr.state_focused}, rounded(light?0xff353535:0xffcecece, density));
            background.addState(new int[]{}, rounded(light?0xff151515:0xfff4f4f4, density));
            background.setEnterFadeDuration(0);
            background.setExitFadeDuration(60);
            button.setBackgroundTintList(null);
            button.setBackground(background);
            button.setStateListAnimator(null);
        });
    }

    private static GradientDrawable rounded(int color, float density) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(14*density);
        return result;
    }

    private static Activity activity(Context context) {
        for (int i=0; i<12 && context instanceof ContextWrapper; i++) {
            if (context instanceof Activity) return (Activity) context;
            Context base = ((ContextWrapper)context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        return null;
    }
}
