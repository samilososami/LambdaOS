/* SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Lambda Project. Native controls for the approved setup design.
 */
package org.lineageos.setupwizard;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.animation.ValueAnimator;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.*;
import android.widget.*;

final class LambdaUi {
    static final int BG=0xff080808,FG=0xfff4f4f4,MUTED=0xffb4b4b4,SURFACE=0xff1c1c1c,LINE=0xff343434;
    final Context context;
    final Typeface regular,bold;
    private final java.util.ArrayList<Runnable> themeBindings=new java.util.ArrayList<>();
    LambdaUi(Context context){this.context=context;regular=context.getResources().getFont(R.font.lambda_regular);bold=context.getResources().getFont(R.font.lambda_bold);}
    boolean isLight(){String value=context.getSharedPreferences("LambdaSetup",Context.MODE_PRIVATE).getString("theme","dark");return value.equals("light")||(value.equals("auto")&&(context.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_NO);}
    int color(int token){if(!isLight())return token;switch(token){case BG:return 0xfff7f7f7;case FG:return 0xff151515;case MUTED:return 0xff626262;case SURFACE:return 0xffeeeeee;case LINE:return 0xffcecece;case 0xff292929:return 0xffe2e2e2;case 0xff303030:return 0xffd8d8d8;case 0x661c1c1c:return 0x66eeeeee;default:return token;}}
    void bindTheme(Runnable update){themeBindings.add(update);update.run();}
    void clearThemeBindings(){themeBindings.clear();}
    void refreshTheme(){for(Runnable update:new java.util.ArrayList<>(themeBindings))update.run();}
    void textColor(TextView view,int token){bindTheme(()->view.setTextColor(color(token)));}
    private ColorMatrixColorFilter ink(int color){return new ColorMatrixColorFilter(new float[]{0,0,0,0,Color.red(color),0,0,0,0,Color.green(color),0,0,0,0,Color.blue(color),.333f,.333f,.334f,0,0});}
    int dp(float v){return Math.round(v*context.getResources().getDisplayMetrics().density);}
    LinearLayout column(){LinearLayout v=new LinearLayout(context);v.setOrientation(LinearLayout.VERTICAL);return v;}
    LinearLayout line(){LinearLayout v=new LinearLayout(context);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    TextView text(String s,float size,int color){TextView v=new TextView(context);v.setText(s);v.setTextSize(size);textColor(v,color);v.setTypeface(regular);v.setIncludeFontPadding(false);v.setLineSpacing(dp(3),1);return v;}
    GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    Button button(String label,boolean primary,Runnable action){
        Button b=new Button(context);b.setText(label);b.setAllCaps(false);b.setTypeface(primary?bold:regular);b.setTextSize(18);textColor(b,primary?BG:FG);
        b.setMinHeight(dp(50));b.setMinimumHeight(dp(50));b.setPadding(dp(12),dp(12),dp(12),dp(12));
        bindTheme(()->b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33777777),shape(primary?color(FG):Color.TRANSPARENT,14),shape(Color.WHITE,14))));
        b.setOnClickListener(v->{haptic(v);action.run();});return b;
    }
    Drawable selectionBackground(){
        StateListDrawable state=new StateListDrawable();
        GradientDrawable selected=shape(color(0xff292929),14);selected.setStroke(dp(1),color(MUTED));
        state.addState(new int[]{android.R.attr.state_selected},selected);
        state.addState(new int[]{android.R.attr.state_pressed},shape(color(0xff303030),14));
        GradientDrawable normal=shape(color(0x661c1c1c),14);normal.setStroke(dp(1),color(LINE));
        state.addState(new int[]{},normal);state.setExitFadeDuration(60);return state;
    }
    ImageButton iconButton(String name,String label,Runnable action){
        ImageButton b=new ImageButton(context);b.setImageDrawable(icon(name,24).getDrawable());
        bindTheme(()->b.setImageTintList(ColorStateList.valueOf(color(FG))));
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);b.setPadding(dp(12),dp(12),dp(12),dp(12));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33777777),null,shape(FG,24)));
        b.setContentDescription(label);b.setMinimumWidth(dp(48));b.setMinimumHeight(dp(48));
        b.setOnClickListener(v->{haptic(v);action.run();});return b;
    }
    Button link(String label,String icon,Runnable action){
        Button b=button(label,false,action);b.setTextSize(13);textColor(b,MUTED);b.setPadding(0,dp(10),0,dp(10));
        Drawable d=icon(icon,16).getDrawable();if(d!=null){final Drawable glyph=d.mutate();bindTheme(()->glyph.setTint(color(MUTED)));glyph.setBounds(0,0,dp(16),dp(16));b.setCompoundDrawablesRelative(glyph,null,null,null);b.setCompoundDrawablePadding(dp(6));}return b;
    }
    void enter(View view){
        if(!ValueAnimator.areAnimatorsEnabled())return;
        view.setAlpha(0);view.setTranslationY(dp(6));
        view.animate().alpha(1).translationY(0).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(()->{view.setAlpha(1);view.setTranslationY(0);}).start();
    }
    static void haptic(View v){if(v.getContext().getSharedPreferences("LambdaSetup",Context.MODE_PRIVATE).getBoolean("haptics",true))v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);}
    void gap(LinearLayout p,int h){p.addView(new View(context),new LinearLayout.LayoutParams(1,dp(h)));}
    void divider(LinearLayout p){View line=new View(context);bindTheme(()->line.setBackgroundColor(color(LINE)));p.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));}
    ImageView icon(String name,int size){ImageView i=new ImageView(context);int id=context.getResources().getIdentifier("lambda_"+name.replace('-','_'),"drawable",context.getPackageName());if(id!=0)i.setImageResource(id);bindTheme(()->i.setImageTintList(ColorStateList.valueOf(color(FG))));i.setScaleType(ImageView.ScaleType.FIT_CENTER);i.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);i.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));return i;}
    LinearLayout row(LinearLayout parent,String title,String summary,String icon,Runnable action){
        LinearLayout row=line();row.setPadding(0,dp(18),0,dp(18));row.setMinimumHeight(dp(70));
        if(icon!=null){row.addView(icon(icon,26));View gap=new View(context);row.addView(gap,new LinearLayout.LayoutParams(dp(18),1));}
        LinearLayout labels=column();labels.addView(text(title,18,FG));if(summary!=null&&!summary.isEmpty()){gap(labels,6);labels.addView(text(summary,14,MUTED));}
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        if(action!=null){ImageView arrow=icon("chevron-right",22);row.addView(arrow,new LinearLayout.LayoutParams(dp(22),dp(22)));row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22777777),null,shape(FG,4)));row.setFocusable(true);row.setOnClickListener(v->{haptic(v);action.run();});}
        parent.addView(row);divider(parent);return row;
    }
    /** Shared geometry for connected and available networks; only the fill changes. */
    void networkRow(LinearLayout parent,String title,String summary,boolean locked,boolean connected,String endIcon,Runnable action){
        LinearLayout row=line();row.setPadding(dp(24),dp(18),dp(24),dp(18));row.setMinimumHeight(dp(70));
        bindTheme(()->row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22777777),shape(connected?color(SURFACE):Color.TRANSPARENT,0),shape(FG,0))));
        row.addView(new WifiIcon(locked),new LinearLayout.LayoutParams(dp(26),dp(26)));
        View gap=new View(context);row.addView(gap,new LinearLayout.LayoutParams(dp(18),1));
        LinearLayout labels=column();labels.addView(text(title,18,FG));gap(labels,6);labels.addView(text(summary,14,MUTED));row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        row.addView(icon(endIcon,22),new LinearLayout.LayoutParams(dp(22),dp(22)));
        if(action!=null){row.setFocusable(true);row.setOnClickListener(v->{haptic(v);action.run();});}
        parent.addView(row,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout separator=column();separator.setPadding(dp(24),0,dp(24),0);divider(separator);parent.addView(separator);
    }
    private final class WifiIcon extends View {
        final Paint p=new Paint(3);final boolean locked;
        WifiIcon(boolean locked){super(context);this.locked=locked;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c){
            float s=Math.min(getWidth(),getHeight())/32f;c.save();c.scale(s,s);
            p.setColor(color(FG));p.setStrokeWidth(2.5f);p.setStrokeCap(Paint.Cap.ROUND);p.setStyle(Paint.Style.STROKE);
            for(int r:new int[]{7,14,21})c.drawArc(16-r,27-r,16+r,27+r,225,90,false,p);
            p.setStyle(Paint.Style.FILL);c.drawCircle(16,27,1.8f,p);
            if(locked){
                // A dark/white separator keeps the lock legible without a colored badge.
                p.setColor(color(BG));c.drawRoundRect(21,18,32,32,2,2,p);
                p.setColor(color(FG));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.7f);c.drawRoundRect(24,20,29,27,2.5f,2.5f,p);
                p.setStyle(Paint.Style.FILL);c.drawRoundRect(22,25,31,32,1.4f,1.4f,p);
            }
            c.restore();
        }
    }
    LinearLayout actionCard(LinearLayout parent,String title,String summary,String icon,Runnable action){
        LinearLayout card=line();card.setPadding(0,dp(26),0,dp(26));card.setMinimumHeight(dp(104));
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22777777),null,shape(FG,18)));
        if(icon!=null){ImageView glyph=icon(icon,30);glyph.setPadding(dp(12),dp(12),dp(12),dp(12));bindTheme(()->glyph.setBackground(shape(color(SURFACE),18)));LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(54),dp(54));ip.setMarginEnd(dp(20));card.addView(glyph,ip);}
        LinearLayout labels=column();TextView h=text(title,20,FG);labels.addView(h);
        if(summary!=null){gap(labels,8);labels.addView(text(summary,14,MUTED));}
        card.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams arrow=new LinearLayout.LayoutParams(dp(22),dp(22));arrow.setMarginStart(dp(12));card.addView(icon("chevron-right",22),arrow);
        card.setFocusable(true);card.setOnClickListener(v->{haptic(v);action.run();});parent.addView(card,new LinearLayout.LayoutParams(-1,-2));divider(parent);return card;
    }
    /** Two explicit choices, updated in place, with accessible selected state. */
    void binary(LinearLayout parent,String yes,String no,boolean initial,java.util.function.Consumer<Boolean> changed){
        LinearLayout rail=line();rail.setPadding(dp(5),dp(5),dp(5),dp(5));
        bindTheme(()->rail.setBackground(shape(color(SURFACE),22)));parent.addView(rail,new LinearLayout.LayoutParams(-1,-2));
        Button[] buttons=new Button[2];String[] labels={yes,no};
        Runnable update=()->{for(Button b:buttons)if(b!=null){b.setTextColor(color(b.isSelected()?BG:MUTED));b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33777777),shape(b.isSelected()?color(FG):Color.TRANSPARENT,18),shape(FG,18)));}};
        for(int i=0;i<2;i++){final int choice=i;Button b=button(labels[i],false,()->{});buttons[i]=b;b.setTextSize(16);b.setTypeface(regular);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(52));b.setMaxLines(2);b.setSelected(initial==(i==0));checkable(b);rail.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->{if(b.isSelected())return;haptic(v);buttons[0].setSelected(choice==0);buttons[1].setSelected(choice==1);update.run();changed.accept(choice==0);});}
        bindTheme(update);gap(parent,24);
    }
    void checkable(View view){view.setAccessibilityDelegate(new View.AccessibilityDelegate(){
        @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setCheckable(true);info.setChecked(host.isSelected());info.setClassName("android.widget.RadioButton");}
    });}
    ImageView mascot(boolean finished){ImageView image=new ImageView(context);image.setImageResource(finished?R.drawable.lamb_finished:R.drawable.lamb_welcome);
        // Approved white-on-black asset: luminance becomes alpha over the shared background.
        bindTheme(()->image.setColorFilter(ink(color(FG))));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription("Lamb");return image;}
    LinearLayout welcomeHero(){
        LinearLayout hero=column();hero.setGravity(Gravity.CENTER);
        ImageView lamb=mascot(false);lamb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(lamb,new LinearLayout.LayoutParams(dp(230),dp(180)));gap(hero,18);
        TextView heading=text("Welcome to",27,MUTED);heading.setGravity(Gravity.CENTER);hero.addView(heading);gap(hero,4);
        hero.addView(new Wordmark(),new LinearLayout.LayoutParams(dp(224),dp(64)));
        return hero;
    }
    EditText field(LinearLayout parent,String hint,int inputType){EditText e=new EditText(context);e.setSingleLine(true);e.setHint(hint);textColor(e,FG);bindTheme(()->{e.setHintTextColor(color(MUTED));e.setBackground(shape(color(SURFACE),10));});e.setTextSize(18);e.setInputType(inputType);e.setTypeface(regular);e.setMinimumHeight(dp(52));e.setPadding(dp(12),dp(12),dp(12),dp(12));e.setSaveEnabled(false);parent.addView(e,new LinearLayout.LayoutParams(-1,-2));gap(parent,16);return e;}
    static final class Battery extends View {
        int level=100;boolean charging;
        private final Paint p=new Paint(3);
        private final LambdaUi palette;
        Battery(Context c){super(c);palette=new LambdaUi(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);}
        void update(int level,boolean charging){this.level=Math.max(0,Math.min(100,level));this.charging=charging;setContentDescription("Battery "+this.level+"%"+(charging?", charging":""));invalidate();}
        @Override protected void onDraw(Canvas c){super.onDraw(c);c.save();c.scale(getWidth()/27f,getHeight()/15f);p.setColor(palette.color(FG));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.2f);c.drawRoundRect(1,1,23,14,2,2,p);p.setStyle(Paint.Style.FILL);if(level>0)c.drawRoundRect(3,3,3+18*level/100f,12,.5f,.5f,p);c.drawRoundRect(24,5,26,10,.8f,.8f,p);c.restore();}
    }
    final class Wordmark extends View {
        final Paint p=new Paint(3);
        final Bitmap brand=BitmapFactory.decodeResource(context.getResources(),R.drawable.lambda_brand_source);
        final Rect source=new Rect(163,721,782,816);
        Wordmark(){super(context);setContentDescription("LambdaOS");bindTheme(()->{p.setColorFilter(ink(color(FG)));invalidate();});}
        @Override protected void onDraw(Canvas c){float scale=Math.min(getWidth()/619f,getHeight()/95f);float w=619*scale,h=95*scale;c.drawBitmap(brand,source,new RectF((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2),p);}
    }
    View preview(String kind,boolean selected){View v=new PhonePreview(kind);v.setSelected(selected);return v;}
    View themePreview(String kind){return new ThemePreview(kind);}
    View selectionDot(){return new SelectionDot();}
    private final class SelectionDot extends View {
        final Paint p=new Paint(3);
        SelectionDot(){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);bindTheme(this::invalidate);}
        @Override protected void onDraw(Canvas c){float x=getWidth()/2f,y=getHeight()/2f,r=dp(7);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1));p.setColor(color(isSelected()?FG:MUTED));c.drawCircle(x,y,r,p);if(isSelected()){p.setStyle(Paint.Style.FILL);c.drawCircle(x,y,dp(4),p);}}
        @Override public void setSelected(boolean selected){super.setSelected(selected);invalidate();}
    }
    private final class ThemePreview extends View {
        final Paint p=new Paint(3),mark=new Paint(3);final String kind;
        final Bitmap brand=BitmapFactory.decodeResource(context.getResources(),R.drawable.lambda_brand_source);
        // The exact approved lambda cropped from the wordmark, without its L or cursor.
        final Rect source=new Rect(232,721,323,816);
        ThemePreview(String kind){super(context);this.kind=kind;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);bindTheme(this::invalidate);}
        private void half(Canvas c,boolean light){
            int bg=light?0xfff7f7f7:0xff151515,fg=light?0xff151515:0xfff4f4f4;
            p.setStyle(Paint.Style.FILL);p.setColor(bg);c.drawRoundRect(2,2,114,244,13,13,p);
            mark.setColorFilter(ink(fg));c.drawBitmap(brand,source,new RectF(40,85,76,123),mark);
            p.setColor(light?0xff777777:0xff656565);for(int i=0;i<3;i++)c.drawRoundRect(25,151+i*14,91,154+i*14,1,1,p);
            p.setColor(light?0xff777777:0xffbbbbbb);c.drawRoundRect(43,231,73,233,1,1,p);c.drawCircle(101,14,2.4f,p);
        }
        @Override protected void onDraw(Canvas c){super.onDraw(c);float s=Math.min(getWidth()/116f,getHeight()/246f);c.save();c.translate((getWidth()-116*s)/2,(getHeight()-246*s)/2);c.scale(s,s);half(c,kind.equals("light"));if(kind.equals("auto")){c.save();c.clipRect(58,0,116,246);half(c,true);c.restore();}p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(isSelected()?2:1);p.setColor(color(isSelected()?FG:MUTED));c.drawRoundRect(2,2,114,244,13,13,p);c.restore();}
        @Override public void setSelected(boolean selected){super.setSelected(selected);invalidate();}
    }
    private final class PhonePreview extends View {
        final Paint p=new Paint(3);final String kind;final LambdaAppGlyph[] glyphs=new LambdaAppGlyph[10];
        PhonePreview(String kind){super(context);this.kind=kind;for(int i=0;i<glyphs.length;i++){glyphs[i]=new LambdaAppGlyph(context,i,i==0?0xff4f9ba9:i==1?0xff578fce:FG);glyphs[i].layout(0,0,100,100);}setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        private void label(Canvas c,String text,float x,float y,float size,int color,boolean strong){p.setStyle(Paint.Style.FILL);p.setTextSize(size);p.setTypeface(strong?bold:regular);p.setColor(color(color));c.drawText(text,x,y,p);}
        private void glyph(Canvas c,int i,float x,float y,float size){glyphs[i].setGlyphColor(i==0?0xff4f9ba9:i==1?0xff578fce:color(FG));c.save();c.translate(x,y);c.scale(size/100,size/100);glyphs[i].draw(c);c.restore();}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);float s=Math.min(getWidth()/116f,getHeight()/245f);c.save();c.translate((getWidth()-116*s)/2,(getHeight()-245*s)/2);c.scale(s,s);
            p.setStyle(Paint.Style.FILL);p.setColor(isLight()?0xfff4f3ef:0xff181b1d);c.drawRoundRect(1,1,115,244,10,10,p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(isSelected()?1.5f:1);p.setColor(color(isSelected()?FG:LINE));c.drawRoundRect(1,1,115,244,10,10,p);
            boolean navigation=kind.equals("gestures")||kind.equals("buttons");
            int contentLayer=c.saveLayerAlpha(3,3,113,224,navigation?64:255);
            label(c,"Lambda",9,26,11,FG,true);glyph(c,6,96,15,12);
            String[] names={"Wi-Fi","Bluetooth","USB","CAN",java.util.Locale.getDefault().getLanguage().equals("es")?"Red":"Network","Radio"};
            boolean icons=!(navigation?context.getSharedPreferences("LambdaSetup",Context.MODE_PRIVATE).getString("layout","icons"):kind).equals("text");
            for(int i=0;i<6;i++){
                if(icons){float x=8+(i%2)*52,y=40+(i/2)*52;p.setStyle(Paint.Style.FILL);p.setColor(isLight()?0xffe6e6e2:0xff23272a);c.drawRoundRect(x,y,x+48,y+47,5,5,p);glyph(c,i,x+13,y+4,23);p.setTextSize(7);p.setTypeface(bold);label(c,names[i],x+(48-p.measureText(names[i]))/2,y+38,7,FG,true);}
                else {float y=48+i*26;label(c,names[i],9,y+9,14,FG,true);if(i<5){p.setColor(color(LINE));p.setStrokeWidth(.5f);c.drawLine(9,y+19,107,y+19,p);}}
            }
            for(int i=0;i<3;i++)glyph(c,7+i,15+i*34,207,15);
            c.restoreToCount(contentLayer);
            p.setStyle(Paint.Style.FILL);p.setColor(color(FG));
            if(kind.equals("buttons")){Path t=new Path();t.moveTo(28,230);t.lineTo(22,234);t.lineTo(28,238);t.close();c.drawPath(t,p);c.drawCircle(58,234,3.5f,p);c.drawRoundRect(87,230,95,238,1,1,p);}
            else c.drawRoundRect(44,234,72,236,1,1,p);c.restore();
        }
        @Override public void setSelected(boolean selected){super.setSelected(selected);invalidate();}
    }
    /** Dot and arcs, not a false percentage. Cancelled when detached. */
    final class WifiProgress extends View {
        final Paint p=new Paint(3);ValueAnimator animator;int level;
        WifiProgress(){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(!ValueAnimator.areAnimatorsEnabled()){level=3;return;}animator=ValueAnimator.ofInt(0,4);animator.setDuration(1000);animator.setRepeatCount(ValueAnimator.INFINITE);animator.setInterpolator(new android.view.animation.LinearInterpolator());animator.addUpdateListener(a->{level=Math.min(3,(Integer)a.getAnimatedValue());invalidate();});animator.start();}
        @Override protected void onDetachedFromWindow(){if(animator!=null)animator.cancel();super.onDetachedFromWindow();}
        @Override protected void onDraw(Canvas c){float s=Math.min(getWidth(),getHeight())/100f;c.save();c.translate((getWidth()-100*s)/2,(getHeight()-100*s)/2);c.scale(s,s);p.setColor(color(FG));p.setStyle(Paint.Style.FILL);c.drawCircle(50,72,3.5f,p);p.setStrokeWidth(4.5f);p.setStrokeCap(Paint.Cap.ROUND);p.setStyle(Paint.Style.STROKE);for(int i=1;i<=3;i++){p.setColor(color(i<=level?FG:LINE));float r=2+i*15;c.drawArc(50-r,72-r,50+r,72+r,225,90,false,p);}c.restore();}
    }
    /** Indeterminate work, then a real completion check. No fabricated percentage. */
    final class SetupProgress extends View {
        final Paint p=new Paint(3);ValueAnimator motion;float angle,check;boolean done;
        SetupProgress(){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(!ValueAnimator.areAnimatorsEnabled())return;motion=ValueAnimator.ofFloat(0,360);motion.setDuration(1400);motion.setRepeatCount(ValueAnimator.INFINITE);motion.setInterpolator(new android.view.animation.LinearInterpolator());motion.addUpdateListener(a->{angle=(float)a.getAnimatedValue();invalidate();});motion.start();}
        void complete(){done=true;if(motion!=null)motion.cancel();if(!ValueAnimator.areAnimatorsEnabled()){check=1;invalidate();return;}motion=ValueAnimator.ofFloat(0,1);motion.setDuration(320);motion.addUpdateListener(a->{check=(float)a.getAnimatedValue();invalidate();});motion.start();}
        @Override protected void onDetachedFromWindow(){if(motion!=null)motion.cancel();super.onDetachedFromWindow();}
        @Override protected void onDraw(Canvas c){float s=Math.min(getWidth(),getHeight())/100f;c.save();c.translate((getWidth()-100*s)/2,(getHeight()-100*s)/2);c.scale(s,s);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.5f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setColor(color(LINE));c.drawCircle(50,50,40,p);p.setColor(color(FG));if(!done)c.drawArc(10,10,90,90,angle-90,100,false,p);else{p.setAlpha(Math.round(255*check));c.drawCircle(50,50,40,p);Path path=new Path();path.moveTo(33,50);path.lineTo(45,62);path.lineTo(68,38);PathMeasure measure=new PathMeasure(path,false);Path part=new Path();measure.getSegment(0,measure.getLength()*check,part,true);c.drawPath(part,p);}c.restore();}
    }
    void finishEntrance(View view,long delay){if(!ValueAnimator.areAnimatorsEnabled())return;view.setAlpha(0);view.setTranslationY(dp(8));view.animate().alpha(1).translationY(0).setStartDelay(delay).setDuration(360).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();}
}
