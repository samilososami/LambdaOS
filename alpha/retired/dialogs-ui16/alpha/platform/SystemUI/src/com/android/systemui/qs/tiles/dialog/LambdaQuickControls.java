/* SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.qs.tiles.dialog;

import static com.android.systemui.qs.tiles.dialog.LambdaControlSheet.*;
import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.hardware.camera2.*;
import android.media.AudioManager;
import android.net.wifi.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import com.android.settingslib.wifi.WifiEnterpriseRestrictionUtils;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.*;
import java.util.*;
import java.util.function.IntConsumer;
import vendor.lineage.powershare.IPowerShare;

/** Native controls only. No exported component, shell commands or synthetic device state. */
public final class LambdaQuickControls {
    private static Panel current;
    private LambdaQuickControls() {}
    public static void showUnlocked(Context c,ActivityStarter starter,Runnable action){
        // Preserve the shade when already unlocked, never expose credentials over keyguard.
        new Handler(Looper.getMainLooper()).post(()->{
            if(c.getSystemService(KeyguardManager.class).isKeyguardLocked())
                starter.postQSRunnableDismissingKeyguard(action);
            else action.run();
        });
    }
    private static final class Panel {
        final SystemUIDialog dialog;final Context c;final LinearLayout body,footer;
        final Handler handler=new Handler(Looper.getMainLooper());final ArrayList<Runnable> cleanup=new ArrayList<>();
        boolean stopped;
        Panel(Context context,int title,int subtitle) {
            if(current!=null)current.dialog.dismiss();current=this;
            dialog=new SystemUIDialog(context,R.style.LambdaControlsTheme);c=dialog.getContext();
            LinearLayout root=shell(dialog);
            ScrollView scroll=new ScrollView(c);scroll.setFillViewport(true);
            body=new LinearLayout(c);body.setOrientation(1);body.setPadding(dp(c,24),dp(c,12),dp(c,24),dp(c,12));
            scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            footer=new LinearLayout(c);footer.setOrientation(1);footer.setPadding(dp(c,24),dp(c,8),dp(c,24),dp(c,8));root.addView(footer);
            TextView heading=text(c,c.getText(title),28,true);body.addView(heading);heading.setAccessibilityHeading(true);
            if(subtitle!=0){TextView sub=text(c,c.getText(subtitle),15,false);sub.setTextColor(MUTED);add(sub,8);}
            // AlertDialog.setView adds an inset parent around a custom view. Install
            // our content after onCreate, like the native Wi-Fi/BT delegates.
            dialog.create();dialog.setContentView(root);
            BroadcastReceiver config=new BroadcastReceiver(){@Override public void onReceive(Context x,Intent i){dialog.dismiss();}};
            c.registerReceiver(config,new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED),Context.RECEIVER_EXPORTED);
            cleanup.add(()->c.unregisterReceiver(config));
            dialog.setOnDismissListener(d->{stopped=true;handler.removeCallbacksAndMessages(null);for(Runnable r:cleanup)try{r.run();}catch(RuntimeException ignored){} if(current==this)current=null;});
        }
        void add(View v,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(c,top);body.addView(v,p);}
        void gap(int h){Space s=new Space(c);body.addView(s,new LinearLayout.LayoutParams(1,dp(c,h)));}
        void divider(){View v=new View(c);v.setBackgroundColor(0xff393939);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(c,1));p.topMargin=dp(c,16);p.bottomMargin=dp(c,8);body.addView(v,p);}
        Button action(int label,Runnable run){Button b=button(c,c.getText(label),true);footer.addView(b,new LinearLayout.LayoutParams(-1,dp(c,54)));b.setOnClickListener(v->run.run());return b;}
        void done(){Button b=button(c,c.getText(R.string.lambda_controls_done),false);b.setTextColor(MUTED);b.setTypeface(Typeface.create("sans-serif",0));b.setBackground(press(c,Color.TRANSPARENT,16));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(c,48));p.topMargin=dp(c,6);footer.addView(b,p);b.setOnClickListener(v->dialog.dismiss());}
        TextView hero(String kind,int size,int label){ImageView i=icon(c,kind,size);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(c,size),dp(c,size));ip.gravity=Gravity.CENTER;ip.topMargin=dp(c,26);ip.bottomMargin=dp(c,18);body.addView(i,ip);TextView t=text(c,c.getText(label),21,true);t.setGravity(Gravity.CENTER);add(t,0);return t;}
        TextView row(int label,String value,Runnable click){LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(c,12),0,dp(c,12));row.setMinimumHeight(dp(c,56));
            TextView l=text(c,c.getText(label),15,false);row.addView(l,new LinearLayout.LayoutParams(0,-2,1));TextView r=text(c,value,15,false);r.setGravity(Gravity.END);r.setTextColor(MUTED);row.addView(r,new LinearLayout.LayoutParams(dp(c,144),-2));
            if(click!=null){row.setBackground(press(c,Color.TRANSPARENT,10));row.setFocusable(true);row.setOnClickListener(v->click.run());r.setCompoundDrawablePadding(dp(c,8));Glyph g=new Glyph("chevron",MUTED);g.setBounds(0,0,dp(c,16),dp(c,16));r.setCompoundDrawablesRelative(null,null,g,null);}
            body.addView(row);return r;}
        void error(){Toast.makeText(c,R.string.lambda_controls_error,Toast.LENGTH_LONG).show();}
        void poll(Runnable r){Runnable loop=new Runnable(){public void run(){if(stopped)return;try{r.run();}catch(RuntimeException e){error();return;}handler.postDelayed(this,1200);}};handler.post(loop);}
        void show(){dialog.show();window(dialog);dialog.getWindow().setLayout(width(dialog),height(dialog));}
    }
    public static void showFlashlight(Context c,FlashlightController controller) {
        Panel p=new Panel(c,R.string.lambda_controls_flash,0);
        CameraManager camera=c.getSystemService(CameraManager.class);String found=null;int levels=1;
        try{for(String id:camera.getCameraIdList()){CameraCharacteristics ch=camera.getCameraCharacteristics(id);
            if(Boolean.TRUE.equals(ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))&&Objects.equals(ch.get(CameraCharacteristics.LENS_FACING),CameraCharacteristics.LENS_FACING_BACK)){
                found=id;Integer max=ch.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);levels=max==null?1:Math.max(1,max);break;}}}catch(CameraAccessException|RuntimeException e){p.error();}
        final String id=found;final int max=levels;final int[] selected={1};final boolean[] enabled={controller.isEnabled()};
        if(id!=null)try{selected[0]=Math.max(1,Math.min(max,camera.getTorchStrengthLevel(id)));}catch(CameraAccessException|RuntimeException ignored){}
        TextView status=p.hero("flash",112,enabled[0]?R.string.lambda_controls_on:R.string.lambda_controls_off);
        p.gap(28);TextView label=text(p.c,p.c.getText(R.string.lambda_controls_strength),12,false);label.setTextColor(MUTED);p.add(label,0);
        Segments slider=new Segments(p.c,max,selected[0]);p.add(slider,12);
        LinearLayout limits=new LinearLayout(p.c);TextView low=text(p.c,p.c.getText(R.string.lambda_controls_soft),13,false),high=text(p.c,p.c.getText(R.string.lambda_controls_high),13,false);low.setTextColor(MUTED);high.setTextColor(MUTED);high.setGravity(Gravity.END);limits.addView(low,new LinearLayout.LayoutParams(0,-2,1));limits.addView(high,new LinearLayout.LayoutParams(0,-2,1));p.add(limits,4);
        Button toggle=p.action(enabled[0]?R.string.lambda_controls_disable:R.string.lambda_controls_enable,()->{
            try{if(id==null)throw new IllegalStateException();if(enabled[0])controller.setFlashlight(false);else camera.turnOnTorchWithStrengthLevel(id,selected[0]);}catch(CameraAccessException|RuntimeException e){p.error();}});
        setPowerLabel(p.c,toggle,enabled[0]);
        slider.changed=n->{selected[0]=n;if(enabled[0])try{camera.turnOnTorchWithStrengthLevel(id,n);}catch(CameraAccessException|RuntimeException e){p.error();}};
        CameraManager.TorchCallback cb=new CameraManager.TorchCallback(){
            @Override public void onTorchModeChanged(String cameraId,boolean on){if(!Objects.equals(id,cameraId)||p.stopped)return;enabled[0]=on;status.setText(on?R.string.lambda_controls_on:R.string.lambda_controls_off);setPowerLabel(p.c,toggle,on);toggle.setEnabled(true);slider.setEnabled(max>1);}
            @Override public void onTorchModeUnavailable(String cameraId){if(!Objects.equals(id,cameraId)||p.stopped)return;status.setText(R.string.lambda_controls_unavailable);toggle.setEnabled(false);slider.setEnabled(false);}
            @Override public void onTorchStrengthLevelChanged(String cameraId,int level){if(Objects.equals(id,cameraId)&&!p.stopped){selected[0]=level;slider.setValue(level);}}
        };
        if(id!=null){camera.registerTorchCallback(cb,p.handler);p.cleanup.add(()->camera.unregisterTorchCallback(cb));}else{toggle.setEnabled(false);slider.setEnabled(false);status.setText(R.string.lambda_controls_unavailable);}
        p.done();p.show();
    }
    private static void setPowerLabel(Context c,Button button,boolean on){
        android.text.SpannableString label=new android.text.SpannableString("\ufffc  "+c.getString(on?R.string.lambda_controls_disable:R.string.lambda_controls_enable));
        Glyph power=new Glyph("power",SURFACE);power.setBounds(0,0,dp(c,22),dp(c,22));
        label.setSpan(new android.text.style.ImageSpan(power,android.text.style.ImageSpan.ALIGN_CENTER),0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setText(label);
    }
    /** One draggable, five-segment control; keyboard/TalkBack expose real range actions. */
    static final class Segments extends View {
        final int max;int value;IntConsumer changed=n->{};final Paint paint=new Paint(3);
        Segments(Context c,int max,int value){super(c);this.max=max;this.value=value;setFocusable(true);setClickable(true);setContentDescription(c.getText(R.string.lambda_controls_strength));setMinimumHeight(dp(c,74));setEnabled(max>1);}
        void setValue(int n){value=Math.max(1,Math.min(max,n));setStateDescription(value+" / "+max);invalidate();}
        void choose(int n){n=Math.max(1,Math.min(max,n));if(n==value)return;setValue(n);performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);changed.accept(n);}
        @Override protected void onMeasure(int w,int h){setMeasuredDimension(MeasureSpec.getSize(w),dp(getContext(),74));}
        @Override protected void onDraw(Canvas c){float gap=dp(getContext(),6),width=(getWidth()-gap*(max-1))/max;for(int i=0;i<max;i++){float x=i*(width+gap);paint.setColor(i<value?WHITE:0xff454545);c.drawRoundRect(x,dp(getContext(),9),x+width,dp(getContext(),23),dp(getContext(),8),dp(getContext(),8),paint);paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(Typeface.create(i+1==value?"sans-serif-medium":"sans-serif",0));paint.setTextSize(dp(getContext(),14));paint.setColor(i+1==value?WHITE:MUTED);c.drawText(""+(i+1),x+width/2,dp(getContext(),53),paint);}}
        @Override public boolean onTouchEvent(android.view.MotionEvent e){if(!isEnabled())return false;switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:case MotionEvent.ACTION_MOVE:getParent().requestDisallowInterceptTouchEvent(true);choose((int)(Math.max(0,Math.min(getWidth()-1,e.getX()))/getWidth()*max)+1);return true;case MotionEvent.ACTION_UP:performClick();getParent().requestDisallowInterceptTouchEvent(false);return true;case MotionEvent.ACTION_CANCEL:getParent().requestDisallowInterceptTouchEvent(false);return true;}return false;}
        @Override public boolean performClick(){super.performClick();return true;}
        @Override public boolean onKeyDown(int k,KeyEvent e){if(isEnabled()&&(k==KeyEvent.KEYCODE_DPAD_LEFT||k==KeyEvent.KEYCODE_DPAD_RIGHT)){choose(value+(k==KeyEvent.KEYCODE_DPAD_RIGHT?1:-1));return true;}return super.onKeyDown(k,e);}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo i){super.onInitializeAccessibilityNodeInfo(i);i.setClassName(SeekBar.class.getName());i.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,1,max,value));if(isEnabled()){i.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);i.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);i.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);}}
        @Override public boolean performAccessibilityAction(int a,Bundle b){if(isEnabled()){if(a==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD){choose(value+1);return true;}if(a==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD){choose(value-1);return true;}if(a==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()&&b!=null){choose(Math.round(b.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)));return true;}}return super.performAccessibilityAction(a,b);}
    }
    public static void showSound(Context c,ActivityStarter starter){
        Panel p=new Panel(c,R.string.lambda_controls_sound,R.string.lambda_controls_rhythm);AudioManager audio=c.getSystemService(AudioManager.class);
        LinearLayout modes=new LinearLayout(p.c);Button[] modeButtons=new Button[3];int[] modesOrder={AudioManager.RINGER_MODE_NORMAL,AudioManager.RINGER_MODE_VIBRATE,AudioManager.RINGER_MODE_SILENT};int[] labels={R.string.lambda_controls_sound,R.string.lambda_controls_vibrate,R.string.lambda_controls_silent};String[] glyphs={"sound","vibrate","silent"};
        for(int i=0;i<3;i++){final int mode=modesOrder[i];Button b=button(p.c,p.c.getText(labels[i]),false);modeButtons[i]=b;b.setTextSize(12);Glyph g=new Glyph(glyphs[i],WHITE);g.setBounds(0,0,dp(p.c,28),dp(p.c,28));b.setCompoundDrawables(null,g,null,null);b.setCompoundDrawablePadding(dp(p.c,9));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,dp(p.c,88),1);if(i>0)bp.leftMargin=dp(p.c,4);modes.addView(b,bp);b.setOnClickListener(v->{try{audio.setRingerModeInternal(mode);}catch(RuntimeException e){p.error();}});}
        p.add(modes,24);
        int[] streams={AudioManager.STREAM_MUSIC,AudioManager.STREAM_RING,AudioManager.STREAM_ALARM};int[] names={R.string.lambda_controls_media,R.string.lambda_controls_ring,R.string.lambda_controls_alarm};String[] icons={"music","bell","alarm"};SeekBar[] bars=new SeekBar[3];boolean[] dragging=new boolean[3];
        for(int i=0;i<3;i++){final int index=i,stream=streams[i];LinearLayout row=new LinearLayout(p.c);row.setGravity(Gravity.CENTER_VERTICAL);row.addView(icon(p.c,icons[i],24));LinearLayout col=new LinearLayout(p.c);col.setOrientation(1);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1);cp.leftMargin=dp(p.c,16);row.addView(col,cp);TextView label=text(p.c,p.c.getText(names[i]),15,false);col.addView(label);SeekBar bar=new SeekBar(p.c);bars[i]=bar;bar.setMin(audio.getStreamMinVolume(stream));bar.setMax(audio.getStreamMaxVolume(stream));bar.setProgress(audio.getStreamVolume(stream));bar.setContentDescription(p.c.getText(names[i]));bar.setProgressTintList(ColorStateList.valueOf(WHITE));bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xff484848));bar.setThumbTintList(ColorStateList.valueOf(WHITE));col.addView(bar,new LinearLayout.LayoutParams(-1,dp(p.c,48)));bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){dragging[index]=true;}public void onStopTrackingTouch(SeekBar b){dragging[index]=false;}public void onProgressChanged(SeekBar b,int v,boolean user){if(user)try{audio.setStreamVolume(stream,v,0);}catch(RuntimeException e){p.error();}}});p.add(row,24);}
        p.divider();TextView dnd=p.row(R.string.lambda_controls_dnd,"",()->{p.dialog.dismiss();starter.postStartActivityDismissingKeyguard(new Intent(Settings.ACTION_ZEN_MODE_SETTINGS),0);});
        p.poll(()->{int mode=audio.getRingerModeInternal();for(int i=0;i<3;i++){boolean selected=mode==modesOrder[i];modeButtons[i].setSelected(selected);modeButtons[i].setTextColor(selected?SURFACE:WHITE);modeButtons[i].setBackground(press(p.c,selected?WHITE:0xff303030,16));for(android.graphics.drawable.Drawable g:modeButtons[i].getCompoundDrawables())if(g!=null)g.setTint(selected?SURFACE:WHITE);if(!dragging[i])bars[i].setProgress(audio.getStreamVolume(streams[i]));}dnd.setText(Settings.Global.getInt(c.getContentResolver(),Settings.Global.ZEN_MODE,0)==0?R.string.lambda_controls_inactive:R.string.lambda_controls_active);});
        p.action(R.string.lambda_controls_done,()->p.dialog.dismiss());p.show();
    }
    public static void showHotspot(Context c,HotspotController controller,DataSaverController saver,ActivityStarter starter){
        Panel p=new Panel(c,R.string.lambda_controls_hotspot,R.string.lambda_controls_hotspot_sub);WifiManager wifi=c.getSystemService(WifiManager.class);
        TextView clients=p.hero("hotspot",72,R.string.lambda_controls_inactive);
        Runnable settings=()->{p.dialog.dismiss();starter.postStartActivityDismissingKeyguard(new Intent(Settings.ACTION_WIFI_TETHER_SETTING),0);};
        p.divider();TextView name=p.row(R.string.lambda_controls_name,"",settings);boolean[] reveal={false};final TextView[] password={null};password[0]=p.row(R.string.lambda_controls_password,"",()->{reveal[0]=!reveal[0];SoftApConfiguration cfg=wifi.getSoftApConfiguration();if(cfg!=null)password[0].setText(reveal[0]?Objects.toString(cfg.getPassphrase(),"—"):"••••••••");});
        TextView band=p.row(R.string.lambda_controls_band,"",settings);TextView timeout=p.row(R.string.lambda_controls_autoff,"",settings);
        Button toggle=p.action(R.string.lambda_controls_enable,()->{if(controller.isHotspotTransient())return;boolean on=controller.isHotspotEnabled();if(!on&&(saver.isDataSaverEnabled()||!WifiEnterpriseRestrictionUtils.isWifiTetheringAllowed(c))){p.error();return;}controller.setHotspotEnabled(!on);});
        p.row(R.string.lambda_controls_share,"",()->{SoftApConfiguration cfg=wifi.getSoftApConfiguration();if(cfg==null){p.error();return;}
            int security=cfg.getSecurityType();String auth=security==SoftApConfiguration.SECURITY_TYPE_WPA3_SAE?"SAE":security==SoftApConfiguration.SECURITY_TYPE_OPEN?"nopass":"WPA";
            Intent qr=new Intent("android.settings.WIFI_DPP_CONFIGURATOR_QR_CODE_GENERATOR").setPackage("com.android.settings")
                .putExtra("isHotspot",true).putExtra("ssid",cfg.getSsid()).putExtra("security",auth)
                .putExtra("preSharedKey",cfg.getPassphrase()).putExtra("hiddenSsid",cfg.isHiddenSsid()).putExtra("networkId",-1);
            p.dialog.dismiss();starter.postStartActivityDismissingKeyguard(qr,0);});
        p.poll(()->{boolean on=controller.isHotspotEnabled(),busy=controller.isHotspotTransient();clients.setText(busy?p.c.getString(R.string.lambda_controls_starting):on?p.c.getString(R.string.lambda_controls_clients,controller.getNumConnectedDevices()):p.c.getString(R.string.lambda_controls_inactive));toggle.setText(on?R.string.lambda_controls_disable:R.string.lambda_controls_enable);toggle.setEnabled(!busy&&(on||(!saver.isDataSaverEnabled()&&WifiEnterpriseRestrictionUtils.isWifiTetheringAllowed(c))));SoftApConfiguration cfg=wifi.getSoftApConfiguration();if(cfg!=null){name.setText(cfg.getSsid());password[0].setText(cfg.getPassphrase()==null?"—":reveal[0]?cfg.getPassphrase():"••••••••");int b=cfg.getBand();band.setText(b==SoftApConfiguration.BAND_2GHZ?"2,4 GHz":b==SoftApConfiguration.BAND_5GHZ?"5 GHz":"2,4 / 5 GHz");timeout.setText(cfg.isAutoShutdownEnabled()?R.string.lambda_controls_no_clients:R.string.lambda_controls_never);}});
        p.done();p.show();
    }
    public static void showPowerShare(Context c,IPowerShare service,BatteryController battery,Runnable refresh){
        Panel p=new Panel(c,R.string.lambda_controls_powershare,R.string.lambda_controls_energy);
        TextView status=p.hero("phones",128,R.string.lambda_controls_reverse);TextView help=text(p.c,p.c.getText(R.string.lambda_controls_place),15,false);help.setGravity(Gravity.CENTER);help.setTextColor(MUTED);p.add(help,8);p.divider();
        BatteryManager bm=c.getSystemService(BatteryManager.class);PowerManager power=c.getSystemService(PowerManager.class);
        TextView level=p.row(R.string.lambda_controls_battery,"",null);final TextView[] min={null};
        min[0]=p.row(R.string.lambda_controls_minbattery,"",()->{
            LinearLayout options=new LinearLayout(p.c);options.setOrientation(1);options.setPadding(dp(p.c,8),dp(p.c,8),dp(p.c,8),dp(p.c,8));
            // An explicit measured height lets PopupWindow flip above the anchor;
            // WRAP_CONTENT clipped the last option against the S10 navigation bar.
            ScrollView optionScroll=new ScrollView(p.c);optionScroll.addView(options);
            PopupWindow popup=new PopupWindow(optionScroll,dp(p.c,150),Math.min(dp(p.c,208),p.c.getResources().getDisplayMetrics().heightPixels/2),true);
            popup.setBackgroundDrawable(round(p.c,0xff303030,16));popup.setElevation(dp(p.c,8));popup.setOutsideTouchable(true);
            for(int n:new int[]{20,30,40,50}){Button choice=button(p.c,n+" %",false);options.addView(choice,new LinearLayout.LayoutParams(-1,dp(p.c,48)));choice.setOnClickListener(v->{try{service.setMinBattery(n);min[0].setText(service.getMinBattery()+" %");refresh.run();}catch(Exception e){p.error();}popup.dismiss();});}
            p.cleanup.add(popup::dismiss);popup.showAsDropDown(min[0],0,0,Gravity.END);
        });
        TextView thermal=text(p.c,p.c.getText(R.string.lambda_controls_thermal),13,false);thermal.setTextColor(MUTED);p.add(thermal,16);
        Button toggle=p.action(R.string.lambda_controls_enable,()->{try{boolean on=service.isEnabled();if(!on&&(battery.isPowerSave()||bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)<service.getMinBattery()||power.getCurrentThermalStatus()>=PowerManager.THERMAL_STATUS_MODERATE)){p.error();return;}service.setEnabled(!on);refresh.run();}catch(Exception e){p.error();}});
        p.poll(()->{try{int percent=bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),minimum=service.getMinBattery();boolean on=service.isEnabled();boolean blocked=battery.isPowerSave()||percent<minimum||power.getCurrentThermalStatus()>=PowerManager.THERMAL_STATUS_MODERATE;level.setText(percent+" %");min[0].setText(minimum+" %");status.setText(on?R.string.lambda_controls_ready:blocked?R.string.lambda_controls_powerblocked:R.string.lambda_controls_reverse);toggle.setText(on?R.string.lambda_controls_disable:R.string.lambda_controls_enable);toggle.setEnabled(on||!blocked);}catch(Exception e){status.setText(R.string.lambda_controls_unavailable);toggle.setEnabled(false);}});
        p.done();p.show();
    }
}
