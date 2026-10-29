/* SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Lambda Project.
 * Generated into each native package by prepare_native_assets.mjs.
 */
package com.android.phone;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.LinearLayout;
import java.util.WeakHashMap;

/** Visual adapter only. Never reads credentials, intercepts input, changes
 * click handlers, clears secure flags, or substitutes native security flows. */
public final class LambdaNativeUi {
    private LambdaNativeUi() {}
    public static boolean isLight(Activity activity) {
        return (activity.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_NO;
    }

    public static boolean isEnabled(Activity activity) {
        return "LambdaOS".equals(SystemProperties.get("ro.lambda.name"))
            && (isEmergencyDialer(activity) || Settings.Global.getInt(activity.getContentResolver(),
                    Settings.Global.DEVICE_PROVISIONED, 0)==0
                || Settings.Secure.getInt(activity.getContentResolver(),
                    Settings.Secure.USER_SETUP_COMPLETE, 0)==0);
    }

    private static boolean isEmergencyDialer(Activity activity) {
        return activity.getClass().getName().equals("com.android.phone.EmergencyDialer");
    }

    public static void applyTheme(Activity activity) {
        if (!isEnabled(activity)) return;
        // Activity-local: never change the user's global rotation preference.
        if(activity.getRequestedOrientation()!=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        int style=activity.getResources().getIdentifier(isLight(activity)?"LambdaNativeLightOverlay":"LambdaNativeOverlay", "style", activity.getPackageName());
        if (style!=0) activity.getTheme().applyStyle(style, true);
        int dialogs=activity.getResources().getIdentifier(isLight(activity)?"LambdaNativeSettingsLightOverlay":"LambdaNativeSettingsOverlay", "style", activity.getPackageName());
        if (dialogs!=0) activity.getTheme().applyStyle(dialogs, true);
    }

    public static void install(Activity activity) {
        if (!isEnabled(activity)) return;
        applyTheme(activity);
        Window window=activity.getWindow();
        boolean light=isLight(activity);
        window.setBackgroundDrawable(new LambdaSetupBackground(light));
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(light?0xfff7f7f7:0xff080808);
        window.setStatusBarContrastEnforced(false);
        window.setNavigationBarContrastEnforced(false);
        WindowInsetsController bars=window.getInsetsController();
        if (bars!=null) {
            bars.setSystemBarsAppearance(light?WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS:0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            bars.hide(WindowInsets.Type.statusBars());
        }
        // Native layouts retain their own IME/cutout handling. The window draws
        // the same gradient behind their safe area, rather than padding twice.
        View root=activity.findViewById(android.R.id.content);
        if (root==null) return;
        int tag=activity.getResources().getIdentifier("lambda_native_style", "id", activity.getPackageName());
        if (tag==0 || root.getTag(tag)!=null) return;
        Styler styler=new Styler(activity);
        root.setTag(tag, styler);
        // The introduction's inset ScrollView must share the window's origin;
        // restarting the gradient at its safe-area edge produces a visible seam.
        if(activity.getClass().getSimpleName().contains("FingerprintEnrollIntroduction"))root.setBackgroundColor(Color.TRANSPARENT);
        else root.setBackground(new LambdaSetupBackground(light));
        root.getViewTreeObserver().addOnGlobalLayoutListener(()->styler.visit(root));
        root.post(()->styler.visit(root));
    }

    private static final class Styler {
        private final Activity activity;
        private final Typeface regular,bold;
        private final int WHITE,MUTED,BLACK;
        private final boolean light;
        private final WeakHashMap<View,Boolean> styled=new WeakHashMap<>();
        Styler(Activity activity) {
            this.activity=activity;
            light=isLight(activity);WHITE=light?0xff151515:0xfff4f4f4;
            MUTED=light?0xff626262:0xffb4b4b4;BLACK=light?0xfff7f7f7:0xff080808;
            int font=activity.getResources().getIdentifier("lambda_regular", "font", activity.getPackageName());
            regular=font==0?Typeface.create("sans-serif",0):activity.getResources().getFont(font);
            int weight=activity.getResources().getIdentifier("lambda_bold", "font", activity.getPackageName());
            bold=weight==0?Typeface.create(regular,Typeface.BOLD):activity.getResources().getFont(weight);
        }
        private int dp(float value) {return Math.round(value*activity.getResources().getDisplayMetrics().density);}
        private String name(View view) {
            if(view.getId()==View.NO_ID)return "";
            try{return activity.getResources().getResourceEntryName(view.getId());}
            catch(android.content.res.Resources.NotFoundException e){return "";}
        }
        private ColorStateList colors(int active,int disabled) {
            return new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}},new int[]{disabled,active});
        }
        private GradientDrawable rounded(int color) {
            GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(14));return d;
        }
        private StateListDrawable buttonBackground(boolean primary) {
            StateListDrawable d=new StateListDrawable();
            d.addState(new int[]{-android.R.attr.state_enabled},rounded(light?0xffdddddd:primary?0xff414141:0xff181818));
            d.addState(new int[]{android.R.attr.state_pressed},rounded(light?primary?0xff353535:0xffdddddd:primary?0xffcecece:0xff353535));
            d.addState(new int[]{android.R.attr.state_focused},rounded(light?primary?0xff353535:0xffdddddd:primary?0xffcecece:0xff353535));
            d.addState(new int[]{},rounded(primary?WHITE:light?0xffeeeeee:0xff202020));
            d.setEnterFadeDuration(0);d.setExitFadeDuration(60);return d;
        }
        void visit(View view) {
            if(!styled.containsKey(view)) {
                styled.put(view,true);
                style(view,name(view));
            }
            if(view instanceof ViewGroup) {
                ViewGroup group=(ViewGroup)view;
                for(int i=0;i<group.getChildCount();i++)visit(group.getChildAt(i));
                if(group instanceof LinearLayout && group.getClass().getSimpleName().equals("ButtonBarLayout"))balanceFooter((LinearLayout)group);
            }
        }
        /** SetupCompat assigns generated IDs, so identify real footer buttons by
         * class/role, never their translated captions. Preserve every listener. */
        private boolean footerPrimary(View view) {
            try{return (Boolean)view.getClass().getMethod("isPrimaryButtonStyle").invoke(view);}
            catch(ReflectiveOperationException ignored){return true;}
        }
        private void balanceFooter(LinearLayout bar) {
            if(activity.getClass().getSimpleName().contains("FingerprintEnrollEnrolling")){
                // No skip action on the live sensor screen. Keep the native
                // layout space so sensor coordinates do not shift; Back and the
                // previous page's cancel action remain entirely native.
                bar.setVisibility(View.INVISIBLE);
                return;
            }
            java.util.ArrayList<Button> buttons=new java.util.ArrayList<>();
            for(int i=0;i<bar.getChildCount();i++){View child=bar.getChildAt(i);if(child instanceof Button && child.getVisibility()!=View.GONE)buttons.add((Button)child);}
            if(buttons.isEmpty())return;
            if(bar.getPaddingLeft()!=dp(24)||bar.getPaddingRight()!=dp(24))bar.setPadding(dp(24),dp(12),dp(24),dp(12));
            for(int i=0;i<bar.getChildCount();i++){
                View child=bar.getChildAt(i);LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)child.getLayoutParams();
                int width=child instanceof Button||buttons.size()==1?0:dp(12);float weight=child instanceof Button?1f:0f;
                if(lp.width!=width||lp.weight!=weight||lp.leftMargin!=0||lp.rightMargin!=0){lp.width=width;lp.weight=weight;lp.leftMargin=lp.rightMargin=0;lp.setMarginStart(0);lp.setMarginEnd(0);child.setLayoutParams(lp);}
            }
        }
        private void style(View view,String id) {
            if(activity.getClass().getSimpleName().contains("FingerprintEnrollIntroduction")&&view instanceof android.widget.ScrollView){
                view.setBackgroundColor(Color.TRANSPARENT);
                // Do not paint a gray focus plate over the whole consent page.
                // The individual actions retain their own keyboard focus states.
                view.setDefaultFocusHighlightEnabled(false);
            }
            if(view instanceof ViewGroup && view.getId()!=android.R.id.content) {
                if(view.getBackground() instanceof ColorDrawable && !view.isClickable())view.setBackgroundColor(Color.TRANSPARENT);
                if(id.equals("setup_wizard_layout") || id.startsWith("sud_layout_") || id.equals("content_parent")
                        || id.equals("main_content") || id.equals("fragment") || id.equals("app_bar")) {
                    if(!view.isClickable())view.setBackgroundColor(Color.TRANSPARENT);
                }
                if(view.getClass().getSimpleName().startsWith("Glif")) {
                    try {
                        view.getClass().getMethod("setBackgroundBaseColor",ColorStateList.class).invoke(view,ColorStateList.valueOf(Color.TRANSPARENT));
                        view.getClass().getMethod("setBackgroundPatterned",boolean.class).invoke(view,false);
                    } catch(ReflectiveOperationException ignored) { /* Optional visual API only. */ }
                }
                if(id.equals("sud_layout_header")&&activity.getClass().getSimpleName().contains("FingerprintEnroll")){
                    // Title/subtitle already supply their own 24dp gutters.
                    // Remove the extra asymmetric container inset, do not double it.
                    view.setPadding(0,view.getPaddingTop(),0,view.getPaddingBottom());
                }
            }
            if(view instanceof TextView) {
                TextView text=(TextView)view;
                text.setTypeface(regular);
                if(!id.matches(".*(error|warning|security_message).*"))text.setTextColor(colors(WHITE,0xff747474));
                boolean largeHeading=!(view instanceof EditText) && !(view instanceof Button)
                        && !id.startsWith("dialpad_")
                        && text.getTextSize()/activity.getResources().getDisplayMetrics().scaledDensity>26;
                if(id.equals("sud_layout_header") || id.equals("header_title") || id.equals("lambda_native_title") || largeHeading) {
                    text.setTextSize(TypedValue.COMPLEX_UNIT_SP,30);
                } else if(id.contains("description") || id.equals("summary") || id.contains("subtitle")) {
                    text.setTextColor(colors(MUTED,0xff666666));
                    text.setTextSize(TypedValue.COMPLEX_UNIT_SP,16);
                }
                if(view instanceof EditText) {
                    // Typeface only: preserve inputType, transformation, validators,
                    // autofill exclusions, secure-window flags and input listeners.
                    text.setHintTextColor(0xff818181);
                } else if(view instanceof Button && !(view instanceof CompoundButton)) {
                    boolean primary=view.getClass().getSimpleName().equals("FooterActionButton")?footerPrimary(view):!id.contains("secondary")&&!id.contains("cancel")&&!id.contains("skip");
                    text.setTypeface(Typeface.create(regular,500,false));text.setAllCaps(false);
                    text.setTextSize(TypedValue.COMPLEX_UNIT_SP,16);
                    text.setTextColor(colors(primary?BLACK:WHITE,0xff909090));
                    view.setBackgroundTintList(null);view.setBackground(buttonBackground(primary));
                    view.setMinimumHeight(dp(48));view.setPadding(dp(20),dp(10),dp(20),dp(10));
                    view.setStateListAnimator(null);
                    if(view.getClass().getSimpleName().equals("FooterActionButton")){
                        Button b=(Button)view;b.setMinWidth(0);b.setMinimumWidth(0);b.setMaxWidth(Integer.MAX_VALUE);b.setSingleLine(true);b.setEllipsize(null);b.setGravity(android.view.Gravity.CENTER);b.setPadding(dp(12),dp(12),dp(12),dp(12));
                    }
                }
            }
            if(view instanceof ImageView && (id.equals("sud_layout_icon") || id.equals("icon") || view instanceof ImageButton)) {
                ImageView icon=(ImageView)view;
                Drawable drawable=icon.getDrawable();
                if(id.equals("icon") && drawable instanceof LayerDrawable) {
                    // Accessibility icons have a background disc plus a glyph.
                    // A tint on the whole ImageView turns both into a solid dot.
                    LayerDrawable layers=(LayerDrawable)drawable.mutate();
                    icon.setImageTintList(null);
                    for(int i=0;i<layers.getNumberOfLayers();i++) {
                        layers.getDrawable(i).setTint(i==layers.getNumberOfLayers()-1?WHITE:light?0xffdedede:0xff282828);
                    }
                } else icon.setImageTintList(colors(WHITE,0xff747474));
            }
            // SettingsLib's decorative illustration plate uses a dynamic accent
            // even inside the first-run theme. Recolor only that plate, not the
            // animated instruction, semantic warnings or accessibility glyphs.
            if(activity.getPackageName().equals("com.android.settings")
                    && activity.getClass().getSimpleName().contains("FingerprintEnrollFinish")
                    && id.equals("fingerprint_in_app_indicator") && view instanceof ImageView) {
                int success=activity.getResources().getIdentifier(light?
                        "lambda_fingerprint_enroll_finish_light":"lambda_fingerprint_enroll_finish",
                        "drawable",activity.getPackageName());
                if(success!=0){
                    // The check badge needs an opaque knockout, not a tint on
                    // the whole image (which would fill that knockout white).
                    ImageView icon=(ImageView)view;icon.setImageTintList(null);
                    icon.setImageResource(success);
                }
            }
            if(activity.getPackageName().equals("com.android.settings")
                    && id.equals("background_view") && view instanceof ImageView
                    && view.getParent() instanceof View
                    && name((View)view.getParent()).equals("illustration_frame")) {
                ((ImageView)view).setImageTintList(ColorStateList.valueOf(light?0xffeeeeee:0xff1c1c1c));
            }
            if(activity.getPackageName().equals("com.android.settings")
                    && (id.equals("illustration_frame") && view instanceof ViewGroup
                    || id.equals("illustration_lottie") && view instanceof ImageView
                    || view.getId()==android.R.id.content && activity.getClass().getSimpleName().contains("FingerprintEnrollEnrolling")
                    || activity.getClass().getSimpleName().contains("FingerprintEnrollIntroduction") && view instanceof ImageView)) {
                // Lottie embeds colored strokes as well as the plate. A visual
                // compositing filter preserves motion and luminance instead of
                // flattening the instructional image into a single tinted blob.
                // UDFPS draws above its small translated parent. Composite at
                // the full-screen root, never clip to that parent's bounds.
                ColorMatrix grayscale=new ColorMatrix();grayscale.setSaturation(0);
                Paint layer=new Paint();layer.setColorFilter(new ColorMatrixColorFilter(grayscale));
                view.setLayerType(View.LAYER_TYPE_HARDWARE,layer);
            }
            if(view instanceof ImageButton && id.equals("deleteButton")) {
                view.setBackground(buttonBackground(false));
                view.setMinimumWidth(dp(48));view.setMinimumHeight(dp(48));
            }
            if(id.equals("dialpad_key_number") && view instanceof TextView)((TextView)view).setTextSize(32);
            if(id.equals("dialpad_key_letters") && view instanceof TextView)((TextView)view).setTextColor(MUTED);
            if(id.matches("one|two|three|four|five|six|seven|eight|nine|zero|star|pound")) {
                if(isEmergencyDialer(activity) && Settings.Secure.getInt(activity.getContentResolver(),
                        Settings.Secure.USER_SETUP_COMPLETE,0)!=0) {
                    StateListDrawable keys=new StateListDrawable();
                    keys.addState(new int[]{android.R.attr.state_pressed},dialDisc(light?0xffd5d5d5:0xff404040));
                    keys.addState(new int[]{android.R.attr.state_focused},dialDisc(light?0xffd5d5d5:0xff404040));
                    keys.addState(new int[]{},dialDisc(light?0xffededed:0xff202020));
                    keys.setExitFadeDuration(100);view.setBackgroundTintList(null);view.setBackground(keys);
                } else view.setBackground(buttonBackground(false));
                if(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams p=(ViewGroup.MarginLayoutParams)view.getLayoutParams();
                    p.setMargins(dp(4),dp(4),dp(4),dp(4));view.setLayoutParams(p);
                }
            }
            if(id.equals("floating_action_button") && view instanceof ImageView) {
                view.setBackgroundTintList(null);view.setBackground(buttonBackground(true));
                if(isEmergencyDialer(activity) && Settings.Secure.getInt(activity.getContentResolver(),
                        Settings.Secure.USER_SETUP_COMPLETE,0)!=0)view.setBackground(dialDisc(WHITE));
                ((ImageView)view).setImageTintList(ColorStateList.valueOf(BLACK));
            }
        }
        private Drawable dialDisc(int fill) {
            return new Drawable() {
                private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
                {paint.setColor(fill);}
                @Override public void draw(android.graphics.Canvas canvas) {
                    android.graphics.Rect b=getBounds();
                    canvas.drawCircle(b.exactCenterX(),b.exactCenterY(),Math.min(b.width(),b.height())*.46f,paint);
                }
                @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
                @Override public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
                @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
            };
        }
    }
}
