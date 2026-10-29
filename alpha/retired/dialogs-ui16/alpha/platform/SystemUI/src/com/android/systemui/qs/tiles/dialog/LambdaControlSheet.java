/* SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.qs.tiles.dialog;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;

/** Shared matte sheet. Native focus, insets, dismissal and accessibility are retained. */
public final class LambdaControlSheet {
    public static final int WHITE=0xfff0f0f0, MUTED=0xffbdbdbd, SURFACE=0xff202020;
    private LambdaControlSheet() {}
    public static int dp(Context c, float n) { return Math.round(n*c.getResources().getDisplayMetrics().density); }
    public static int width(SystemUIDialog d) {
        return Math.min(d.getContext().getResources().getDisplayMetrics().widthPixels, dp(d.getContext(), 520));
    }
    public static int height(SystemUIDialog d) {
        // Window excludes system bars; bound content rather than clipping large fonts/landscape.
        return (int)(d.getContext().getResources().getDisplayMetrics().heightPixels*.73f);
    }
    public static void theme(SystemUIDialog d) { d.getContext().getTheme().applyStyle(R.style.LambdaControlsTheme,true); }
    public static void window(SystemUIDialog d) {
        Window w=d.getWindow();
        w.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setDimAmount(.48f);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setNavigationBarColor(SURFACE);
        w.getDecorView().setSystemUiVisibility(w.getDecorView().getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
    public static GradientDrawable round(Context c,int color,int radius) {
        GradientDrawable g=new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(c,radius)); return g;
    }
    public static Drawable press(Context c,int color,int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x28ffffff),round(c,color,radius),null);
    }
    public static TextView text(Context c,CharSequence text,int size,boolean bold) {
        TextView t=new TextView(c); t.setText(text);t.setTextSize(size);t.setTextColor(WHITE);
        t.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
        t.setFontFeatureSettings("kern"); t.setIncludeFontPadding(false);return t;
    }
    public static Button button(Context c,CharSequence label,boolean primary) {
        Button b=new Button(c); b.setText(label);b.setAllCaps(false);b.setTextSize(16);
        b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setTextColor(primary?SURFACE:WHITE);b.setBackground(press(c,primary?WHITE:0xff303030,18));
        b.setMinimumHeight(dp(c,52));b.setMinHeight(dp(c,52));b.setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8));
        b.setMaxLines(2); return b;
    }
    public static ImageView icon(Context c,String kind,int size) {
        ImageView i=new ImageView(c);i.setImageDrawable(new Glyph(kind,WHITE));
        i.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(c,size),dp(c,size)));return i;
    }
    public static void styleTree(View v) {
        if(v instanceof TextView){TextView t=(TextView)v;t.setTextColor(WHITE);}
        if(v instanceof ImageView)((ImageView)v).setImageTintList(ColorStateList.valueOf(WHITE));
        if(v instanceof Switch){ Switch s=(Switch)v;
            s.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{WHITE,0xff555555}));
            s.setThumbTintList(ColorStateList.valueOf(SURFACE));
        }
        if(v instanceof ProgressBar){ ProgressBar p=(ProgressBar)v;
            p.setIndeterminateTintList(ColorStateList.valueOf(MUTED));p.setProgressTintList(ColorStateList.valueOf(WHITE));
        }
        if(v instanceof Button && !(v instanceof android.widget.CompoundButton)){Button b=(Button)v;boolean primary=v.getId()==R.id.done_button;
            b.setAllCaps(false);b.setTextColor(primary?SURFACE:WHITE);
            b.setBackground(press(v.getContext(),primary?WHITE:0xff303030,16));
            b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setMinHeight(dp(v.getContext(),48));
        }
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)styleTree(((ViewGroup)v).getChildAt(i));
    }
    public static LinearLayout shell(SystemUIDialog d) {
        Context c=d.getContext(); window(d);
        LinearLayout root=new LinearLayout(c);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new Matte(c));root.setPadding(0,dp(c,10),0,dp(c,12));
        LinearLayout top=new LinearLayout(c);top.setGravity(Gravity.CENTER_VERTICAL);
        Space left=new Space(c);top.addView(left,new LinearLayout.LayoutParams(dp(c,48),dp(c,40)));
        FrameLayout handleBox=new FrameLayout(c);
        View handle=new View(c);handle.setBackground(round(c,0xff666666,4));
        FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(dp(c,34),dp(c,3),Gravity.CENTER);
        handleBox.addView(handle,hp);top.addView(handleBox,new LinearLayout.LayoutParams(0,dp(c,40),1));
        ImageButton close=new ImageButton(c);close.setImageDrawable(new Glyph("close",WHITE));
        close.setPadding(dp(c,13),dp(c,13),dp(c,13),dp(c,13));close.setBackground(press(c,Color.TRANSPARENT,24));
        close.setContentDescription(c.getString(R.string.lambda_controls_close));close.setOnClickListener(v->d.dismiss());
        top.addView(close,new LinearLayout.LayoutParams(dp(c,48),dp(c,48)));
        top.setPadding(dp(c,10),0,dp(c,10),0);root.addView(top);
        return root;
    }
    /** Existing Wi-Fi/BT delegates retain all native controllers and security dialogs. */
    public static void legacy(SystemUIDialog d,View content) {
        Context c=d.getContext();LinearLayout shell=shell(d);
        if(content.getParent() instanceof ViewGroup)((ViewGroup)content.getParent()).removeView(content);
        content.setBackground(null);content.setPadding(0,0,0,0);
        // One scrolling body, with actions outside it. A match-parent inner scroll
        // otherwise consumes the footer and clips native Done/Share on the S10.
        View inner=content.findViewById(R.id.scroll_view);
        if(inner!=null){inner.getLayoutParams().height=ViewGroup.LayoutParams.WRAP_CONTENT;inner.setMinimumHeight(0);
            if(inner instanceof androidx.core.widget.NestedScrollView){
                ((androidx.core.widget.NestedScrollView)inner).setFillViewport(false);
                ((androidx.core.widget.NestedScrollView)inner).setNestedScrollingEnabled(false);
            }}
        View innerContent=content.findViewById(R.id.scroll_layout);
        if(innerContent!=null){innerContent.getLayoutParams().height=ViewGroup.LayoutParams.WRAP_CONTENT;innerContent.setMinimumHeight(0);}
        LinearLayout footer=new LinearLayout(c);footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(c,24),dp(c,10),dp(c,24),dp(c,8));
        LinearLayout actions=new LinearLayout(c);
        for(int id:new int[]{R.id.apm_button,R.id.audio_sharing_button,R.id.share_wifi_button,R.id.done_button}){
            Button action=content.findViewById(id);if(action==null)continue;
            ((ViewGroup)action.getParent()).removeView(action);
            action.setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8));action.setTextSize(16);action.setMaxLines(2);
            action.setMinimumWidth(0);action.setMinWidth(0);
            if(id==R.id.share_wifi_button)action.setText(R.string.lambda_controls_share);
            if(id==R.id.apm_button||id==R.id.audio_sharing_button){
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(c,52));lp.bottomMargin=dp(c,8);footer.addView(action,lp);
            }else{LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(c,52),1);
                if(actions.getChildCount()>0)lp.leftMargin=dp(c,8);actions.addView(action,lp);}
        }
        View emptyButtons=content.findViewById(R.id.button_layout);if(emptyButtons!=null)emptyButtons.setVisibility(View.GONE);
        footer.addView(actions,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(c);scroll.setFillViewport(false);scroll.setClipToPadding(false);
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        shell.addView(footer,new LinearLayout.LayoutParams(-1,-2));
        d.setContentView(shell);styleTree(content);styleTree(footer);
        View progressBackground=content.findViewById(R.id.bluetooth_tile_dialog_progress_background);
        if(progressBackground!=null)progressBackground.setBackgroundColor(0xff454545);
        TextView title=content.findViewById(R.id.internet_dialog_title);
        String glyph="wifi";
        if(title==null){title=content.findViewById(R.id.bluetooth_tile_dialog_title);glyph="bluetooth";}
        if(title!=null){title.setTextSize(27);title.setTypeface(Typeface.create("sans-serif-medium",0));
            Glyph g=new Glyph(glyph,WHITE);g.setBounds(0,0,dp(c,32),dp(c,32));
            title.setCompoundDrawablesRelative(g,null,null,null);title.setCompoundDrawablePadding(dp(c,14));}
    }
    public static final class Matte extends Drawable {
        private final Paint p=new Paint(3);private final float density;
        public Matte(Context c){density=c.getResources().getDisplayMetrics().density;}
        @Override public void draw(Canvas c){Rect b=getBounds();float r=28*density;
            Path clip=new Path();clip.addRoundRect(new RectF(b),new float[]{r,r,r,r,0,0,0,0},Path.Direction.CW);
            c.save();c.clipPath(clip);p.setShader(null);p.setColor(SURFACE);c.drawRect(b,p);
            p.setShader(new RadialGradient(b.left,b.top,320*density,new int[]{0xff303030,0xff202020},null,Shader.TileMode.CLAMP));c.drawRect(b,p);p.setShader(null);
            for(float y=8*density;y<240*density;y+=8*density)for(float x=8*density;x<240*density;x+=8*density){
                float f=Math.max(0,1-(float)Math.hypot(x,y)/(240*density));p.setColor(Color.argb((int)(22*f),240,240,240));c.drawCircle(x,y,.55f*density,p);}
            c.restore();}
        @Override public void setAlpha(int a){} @Override public void setColorFilter(ColorFilter f){} @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
    /** Small geometric glyphs, not emoji; stable in every font/locale. */
    public static final class Glyph extends Drawable {
        private final String kind;private final Paint p=new Paint(3);
        public Glyph(String k,int color){kind=k;p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.5f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);}
        private void line(Canvas c,float... xy){Path a=new Path();a.moveTo(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)a.lineTo(xy[i],xy[i+1]);c.drawPath(a,p);}
        @Override public void draw(Canvas c){Rect b=getBounds();c.save();c.translate(b.left,b.top);c.scale(b.width()/24f,b.height()/24f);
            switch(kind){
            case "close":line(c,6,6,18,18);line(c,18,6,6,18);break;
            case "flash":p.setStrokeWidth(1.35f);line(c,7,7,17,7,17,11,14,15,14,22,10,22,10,15,7,11,7,7);line(c,7,10,17,10);line(c,12,17,12,19);line(c,12,3.5f,12,1);line(c,8,4,7,2);line(c,16,4,17,2);break;
            case "power":c.drawArc(4,4,20,20,-45,270,false,p);line(c,12,2,12,11);break;
            case "wifi":c.drawArc(1,4,23,24,218,104,false,p);c.drawArc(5,9,19,23,220,100,false,p);c.drawArc(8,14,16,22,225,90,false,p);c.drawCircle(12,21,.7f,p);break;
            case "bluetooth":line(c,6,6,17,16,12,21,12,3,17,8,6,18);break;
            case "hotspot":c.drawCircle(12,12,1.5f,p);c.drawArc(5,5,19,19,-50,100,false,p);c.drawArc(5,5,19,19,130,100,false,p);c.drawArc(1,1,23,23,-50,100,false,p);c.drawArc(1,1,23,23,130,100,false,p);break;
            case "phones":c.save();c.rotate(-13,8,13);c.drawRoundRect(3,2,13,22,1.5f,1.5f,p);c.drawRoundRect(5,4,11,6,1,1,p);c.restore();c.drawRoundRect(12,2,22,22,1.5f,1.5f,p);line(c,7,10,5,14,8,14,6,18);break;
            case "eye":c.drawOval(2,7,22,17,p);c.drawCircle(12,12,3,p);break;
            case "sound":line(c,3,9,7,9,12,5,12,19,7,15,3,15,3,9);c.drawArc(9,5,23,19,-55,110,false,p);c.drawArc(13,8,21,16,-55,110,false,p);break;
            case "vibrate":c.drawRoundRect(7,3,17,21,1,1,p);line(c,3,6,1,9,3,12,1,15,3,18);line(c,21,6,23,9,21,12,23,15,21,18);break;
            case "silent":line(c,4,3,21,20);line(c,7,8,7,15,4,18,20,18,17,15,17,9);c.drawArc(7,3,17,15,190,160,false,p);c.drawArc(10,18,14,22,0,180,false,p);break;
            case "music":line(c,9,18,9,5,20,3,20,16);c.drawOval(3,16,9,21,p);c.drawOval(14,14,20,19,p);break;
            case "bell":line(c,7,8,7,15,4,18,20,18,17,15,17,8);c.drawArc(7,3,17,13,180,180,false,p);c.drawArc(10,18,14,22,0,180,false,p);break;
            case "alarm":c.drawCircle(12,13,8,p);line(c,12,8,12,13,16,15);line(c,3,5,6,2);line(c,18,2,21,5);break;
            case "moon":Path m=new Path();m.moveTo(16,3);m.cubicTo(-1,1,1,25,20,19);m.cubicTo(8,18,10,7,16,3);c.drawPath(m,p);break;
            case "plus":line(c,12,4,12,20);line(c,4,12,20,12);break;
            case "scan":c.drawArc(4,4,20,20,30,290,false,p);line(c,20,3,20,9,14,9);break;
            default:line(c,9,5,16,12,9,19);
            }c.restore();}
        @Override public void setTint(int color){p.setColor(color);invalidateSelf();}
        @Override public void setTintList(ColorStateList colors){if(colors!=null)setTint(colors.getColorForState(getState(),colors.getDefaultColor()));}
        @Override public void setAlpha(int a){p.setAlpha(a);invalidateSelf();} @Override public void setColorFilter(ColorFilter f){p.setColorFilter(f);invalidateSelf();} @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
