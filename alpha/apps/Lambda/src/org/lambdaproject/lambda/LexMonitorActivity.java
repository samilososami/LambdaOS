package org.lambdaproject.lambda;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** LEX mode 2: the Kali XFCE desktop on a USB-C/DisplayPort monitor as a
 *  Presentation (noVNC in a WebView), while the phone is a trackpad and
 *  keyboard over a separate input-only RFB session on the same Xvnc.
 *
 *  The shared desktop is started only when none is running; if this activity
 *  started it, it stops it on exit. A desktop started elsewhere keeps running.
 *
 *  UNTESTED ON HARDWARE: no external display or Miracast sink was available.
 *  Display detection, Presentation on a secondary display and WebView on a
 *  secondary display are unverified on the S10; failures are reported, never
 *  dressed up as success. */
public final class LexMonitorActivity extends Activity {
    private static final int BG=0xff101112,SURFACE=0xff1D1F20,SURFACE_ALT=0xff171819,
        FG=0xffEEEEEC,SECONDARY=0xffA4A7A9,ACCENT=0xff4F9BA9;

    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final int[] pointer=new int[2];
    private final DisplayManager.DisplayListener displayListener=new DisplayManager.DisplayListener(){
        @Override public void onDisplayAdded(int id){refreshDisplays();}
        @Override public void onDisplayRemoved(int id){refreshDisplays();}
        @Override public void onDisplayChanged(int id){refreshDisplays();}
    };

    private DisplayManager displays;
    private int displayId=Display.INVALID_DISPLAY;
    private String displayName="";
    private int displayWidth,displayHeight;
    private Presentation presentation;
    private WebView web;
    private LexRfbInput rfb;
    private int vncWidth=2280,vncHeight=1080;
    private int vncPort=5901,webPort=6080;
    private String vncPassword="";
    private volatile boolean ownDesktop,destroyed;
    private boolean desktopReady,busy,resumed,ctrlArmed;
    private long lastAttempt;
    private String padState;
    private FrameLayout root,console,setup;
    private TextView status,setupTitle,setupBody;
    private Button keyboardButton,ctrlButton;
    private EditText keyField;

    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private String tr(String es,String en){return Locale.getDefault().getLanguage().equals("en")?en:es;}

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        displays=(DisplayManager)getSystemService(DISPLAY_SERVICE);
        root=buildUi();
        setContentView(root);
        immersive();
    }

    private FrameLayout buildUi(){
        FrameLayout frame=new FrameLayout(this);
        frame.setBackgroundColor(BG);
        console=buildConsole();
        setup=buildSetup();
        frame.addView(console,new FrameLayout.LayoutParams(-1,-1));
        frame.addView(setup,new FrameLayout.LayoutParams(-1,-1));
        console.setVisibility(View.GONE);
        setup.setVisibility(View.VISIBLE);
        if(Build.VERSION.SDK_INT>=30){
            frame.setOnApplyWindowInsetsListener((view,insets)->{
                Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                view.setPadding(bars.left,bars.top,bars.right,bars.bottom);
                return insets;
            });
        }
        return frame;
    }

    private FrameLayout buildConsole(){
        FrameLayout layer=new FrameLayout(this);
        LinearLayout column=new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16),dp(10),dp(16),dp(14));
        status=new TextView(this);
        status.setTextColor(SECONDARY);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP,12);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(status,new LinearLayout.LayoutParams(-1,dp(22)));

        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        column.addView(row,new LinearLayout.LayoutParams(-1,0,1));

        FrameLayout padArea=new FrameLayout(this);
        padArea.addView(new Pad(),new FrameLayout.LayoutParams(-1,-1));
        ScrollStrip strip=new ScrollStrip();
        FrameLayout.LayoutParams stripParams=new FrameLayout.LayoutParams(dp(56),-1,Gravity.RIGHT);
        stripParams.rightMargin=dp(8);
        stripParams.topMargin=dp(8);
        stripParams.bottomMargin=dp(8);
        padArea.addView(strip,stripParams);
        row.addView(padArea,new LinearLayout.LayoutParams(0,-1,1));

        LinearLayout controls=new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams controlsParams=new LinearLayout.LayoutParams(dp(164),-1);
        controlsParams.leftMargin=dp(12);
        row.addView(controls,controlsParams);
        addControl(controls,tr("Clic izq.","Left click"),()->click(1));
        addControl(controls,tr("Clic der.","Right click"),()->click(4));
        keyboardButton=addControl(controls,tr("Teclado","Keyboard"),this::toggleKeyboard);
        ctrlButton=addControl(controls,tr("Ctrl","Ctrl"),this::toggleCtrl);
        addControl(controls,tr("Terminar","End"),this::finish);
        layer.addView(column,new FrameLayout.LayoutParams(-1,-1));

        // Stay attached and focusable, but invisible: the system IME needs a
        // target view, and PadConnection forwards instead of storing text.
        keyField=new KeyField(this);
        keyField.setAlpha(0f);
        layer.addView(keyField,new FrameLayout.LayoutParams(dp(1),dp(1),Gravity.BOTTOM|Gravity.START));
        return layer;
    }

    private FrameLayout buildSetup(){
        FrameLayout layer=new FrameLayout(this);
        LinearLayout column=new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_VERTICAL);
        column.setPadding(dp(28),dp(20),dp(28),dp(20));
        TextView tag=label("LEX · MODO 2",11,true,SECONDARY);
        tag.setLetterSpacing(.12f);
        column.addView(tag);
        space(column,10);
        setupTitle=label("",30,true,FG);
        column.addView(setupTitle);
        space(column,10);
        setupBody=label("",15,false,SECONDARY);
        setupBody.setLineSpacing(dp(3),1);
        column.addView(setupBody);
        space(column,18);
        Button retry=actionButton(tr("Reintentar","Retry"),()->{
            lastAttempt=0;
            refreshDisplays();
        });
        column.addView(retry,new LinearLayout.LayoutParams(-1,dp(52)));
        space(column,8);
        Button back=actionButton(tr("Volver","Back"),this::finish);
        column.addView(back,new LinearLayout.LayoutParams(-1,dp(52)));
        space(column,14);
        TextView note=label(tr("Modo en preparación: la ruta USB-C/DisplayPort no está validada en hardware en esta build.",
            "Work in progress: the USB-C/DisplayPort path is not hardware-validated in this build."),12,false,SECONDARY);
        note.setLineSpacing(dp(2),1);
        column.addView(note);
        layer.addView(column,new FrameLayout.LayoutParams(-1,-1));
        return layer;
    }

    private Button addControl(LinearLayout parent,String text,Runnable action){
        Button button=actionButton(text,action);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,0,1);
        params.bottomMargin=dp(6);
        parent.addView(button,params);
        return button;
    }

    private Button actionButton(String text,Runnable action){
        Button button=new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP,14);
        button.setTextColor(FG);
        button.setBackground(rounded(SURFACE,12));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setOnClickListener(view->action.run());
        return button;
    }

    private TextView label(String text,float size,boolean bold,int color){
        TextView view=new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP,size);
        view.setTextColor(color);
        view.setTypeface(android.graphics.Typeface.create(bold?"sans-serif-medium":"sans-serif",android.graphics.Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    private void space(LinearLayout parent,int height){
        parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));
    }

    private GradientDrawable rounded(int color,int radius){
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private Display findExternalDisplay(){
        if(displays==null)return null;
        for(Display display:displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)){
            if(display==null||display.getDisplayId()==Display.DEFAULT_DISPLAY)continue;
            if(display.getState()!=Display.STATE_ON)continue;
            if((display.getFlags()&Display.FLAG_PRESENTATION)==0)continue;
            return display;
        }
        return null;
    }

    private void refreshDisplays(){
        if(destroyed)return;
        Display display=findExternalDisplay();
        if(display==null){
            dropDisplay(tr("Conecta un monitor USB-C","Connect a USB-C display"),
                tr("No se ha detectado ninguna pantalla externa encendida. Conecta el monitor por USB-C/DisplayPort y pulsa Reintentar.",
                    "No powered external display detected. Connect the monitor over USB-C/DisplayPort and tap Retry."));
            return;
        }
        int id=display.getDisplayId();
        if(id!=displayId){
            displayId=id;
            pointer[0]=vncWidth/2;
            pointer[1]=vncHeight/2;
            closeRfb();
            desktopReady=false;
        }
        DisplayMetrics metrics=new DisplayMetrics();
        display.getMetrics(metrics);
        displayName=display.getName()==null?"":display.getName();
        displayWidth=metrics.widthPixels;
        displayHeight=metrics.heightPixels;
        showConsole();
        updateStatus();
        if(!desktopReady&&!busy&&SystemClock.elapsedRealtime()-lastAttempt>5000)ensureDesktop();
        if(desktopReady){
            presentIfPossible();
            ensureRfb();
        }
    }

    private void dropDisplay(String title,String body){
        if(displayId!=Display.INVALID_DISPLAY||presentation!=null||rfb!=null){
            displayId=Display.INVALID_DISPLAY;
            displayName="";
            dismissPresentation();
            closeRfb();
            desktopReady=false;
        }
        updateStatus();
        showSetup(title,body);
    }

    private void showConsole(){
        console.setVisibility(View.VISIBLE);
        setup.setVisibility(View.GONE);
    }

    private void showSetup(String title,String body){
        console.setVisibility(View.GONE);
        setup.setVisibility(View.VISIBLE);
        setupTitle.setText(title);
        setupBody.setText(body);
        hideKeyboard();
    }

    private void updateStatus(){
        if(status==null)return;
        String device=displayName.isEmpty()?tr("sin pantalla","no display")
            :displayName+" · "+displayWidth+"×"+displayHeight;
        String desktop=desktopReady?vncWidth+"×"+vncHeight:tr("escritorio no listo","desktop not ready");
        status.setText(device+"  ·  "+desktop+"  ·  "
            +(padState==null?tr("pad sin conexión","pad offline"):padState));
    }

    private void ensureDesktop(){
        if(destroyed||busy||desktopReady)return;
        busy=true;
        lastAttempt=SystemClock.elapsedRealtime();
        padState=tr("escritorio iniciando…","starting desktop…");
        updateStatus();
        io.execute(()->{
            JSONObject desktop=null;
            String error=null;
            try{
                JSONObject response=rpc("desktop_status",new JSONObject());
                if(response.optBoolean("ok")&&response.optBoolean("running")){
                    desktop=response.optJSONObject("desktop");
                }else{
                    // Mark ownership before the start job exists, so leaving
                    // mid-start still enqueues the stop.
                    ownDesktop=true;
                    desktop=startJob();
                }
                if(desktop==null)throw new IllegalStateException("desktop_start_timeout");
                if(!desktop.optBoolean("running")&&desktop.optInt("running",0)!=1)
                    throw new IllegalStateException("desktop_start_failed");
            }catch(Exception e){
                error=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            }
            final JSONObject value=desktop;
            final String detail=error;
            main.post(()->onDesktop(value,detail));
        });
    }

    private JSONObject rpc(String method,JSONObject args)throws Exception{
        Bundle bundle=getContentResolver().call(CoreProvider.URI,method,args.toString(),null);
        return new JSONObject(bundle.getString("json"));
    }

    private JSONObject startJob()throws Exception{
        JSONObject request=new JSONObject().put("tool","system.desktop")
            .put("args",new JSONObject().put("action","start"))
            .put("request_id",UUID.randomUUID().toString());
        JSONObject response=rpc("submit",request);
        if(!response.optBoolean("ok"))
            throw new IllegalStateException(response.optString("detail",response.optString("error","start_failed")));
        String id=response.getJSONObject("job").getString("id");
        long until=SystemClock.elapsedRealtime()+90000;
        while(SystemClock.elapsedRealtime()<until){
            if(destroyed)throw new IllegalStateException("activity_destroyed");
            JSONObject job=rpc("get",new JSONObject().put("id",id)).optJSONObject("job");
            String state=job==null?"":job.optString("state");
            if(state.equals("completed"))return job.optJSONObject("result");
            if(state.equals("failed")||state.equals("cancelled")||state.equals("interrupted"))
                throw new IllegalStateException(job.optString("error",state));
            Thread.sleep(400);
        }
        throw new IllegalStateException("desktop_start_timeout");
    }

    private void onDesktop(JSONObject desktop,String error){
        busy=false;
        if(destroyed)return;
        if(desktop==null){
            padState=tr("escritorio: ","desktop: ")+(error==null?"error":error);
            updateStatus();
            showSetup(tr("No se pudo iniciar el escritorio","Could not start the desktop"),
                (error==null?tr("Error desconocido","Unknown error"):error)+"\n\n"
                    +tr("Pulsa Reintentar. Si persiste, revisa la pestaña LEX en Operator.",
                        "Tap Retry. If it persists, check the LEX tab in Operator."));
            return;
        }
        desktopReady=true;
        vncPort=desktop.optInt("vnc_port",5901);
        webPort=desktop.optInt("web_port",6080);
        vncPassword=desktop.optString("password","");
        parseGeometry(desktop.optString("geometry","2280x1080"));
        pointer[0]=vncWidth/2;
        pointer[1]=vncHeight/2;
        Display current=displays==null?null:displays.getDisplay(displayId);
        if(current==null||current.getState()!=Display.STATE_ON){
            // The monitor was unplugged while the start job ran. The desktop
            // is now up; keep that state so reconnecting attaches to it.
            padState=tr("escritorio listo","desktop ready");
            updateStatus();
            showSetup(tr("Conecta un monitor USB-C","Connect a USB-C display"),
                tr("El escritorio está en marcha, pero no hay ninguna pantalla externa conectada. Conecta el monitor y pulsa Reintentar.",
                    "The desktop is running, but no external display is connected. Connect the monitor and tap Retry."));
            return;
        }
        showConsole();
        updateStatus();
        presentIfPossible();
        ensureRfb();
    }

    private void parseGeometry(String geometry){
        try{
            String[] parts=geometry.toLowerCase(Locale.US).split("x");
            int width=Integer.parseInt(parts[0].trim());
            int height=Integer.parseInt(parts[1].trim());
            if(width>=320&&width<=8192&&height>=240&&height<=8192){
                vncWidth=width;
                vncHeight=height;
            }
        }catch(Exception ignored){}
    }

    private void presentIfPossible(){
        if(destroyed||!resumed||presentation!=null||!desktopReady||displayId==Display.INVALID_DISPLAY)return;
        Display display=displays==null?null:displays.getDisplay(displayId);
        if(display==null||display.getState()!=Display.STATE_ON)return;
        Presentation dialog=null;
        WebView view=null;
        try{
            dialog=new Presentation(this,display);
            if(dialog.getWindow()!=null)
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(BG));
            view=new WebView(dialog.getContext());
            view.setBackgroundColor(BG);
            WebSettings settings=view.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
            settings.setBuiltInZoomControls(false);
            settings.setDisplayZoomControls(false);
            settings.setMediaPlaybackRequiresUserGesture(false);
            view.setWebViewClient(new WebViewClient());
            String encoded;
            try{encoded=URLEncoder.encode(vncPassword,"UTF-8");}
            catch(Exception e){encoded="";}
            view.loadUrl("http://127.0.0.1:"+webPort
                +"/vnc.html?autoconnect=1&resize=scale&reconnect=true&view_only=0&password="+encoded);
            dialog.setContentView(view);
            dialog.show();
            presentation=dialog;
            web=view;
        }catch(RuntimeException e){
            if(view!=null)try{view.destroy();}catch(Exception ignored){}
            if(dialog!=null)try{dialog.dismiss();}catch(Exception ignored){}
            padState=tr("pantalla externa: ","external display: ")+describe(e);
            updateStatus();
        }
    }

    private void dismissPresentation(){
        Presentation dialog=presentation;
        WebView view=web;
        presentation=null;
        web=null;
        if(dialog!=null)try{dialog.dismiss();}catch(Exception ignored){}
        if(view!=null)try{view.destroy();}catch(Exception ignored){}
    }

    private void ensureRfb(){
        if(destroyed||!desktopReady||rfb!=null)return;
        final LexRfbInput client=new LexRfbInput();
        rfb=client;
        padState=tr("pad conectando…","pad connecting…");
        updateStatus();
        client.connect("127.0.0.1",vncPort,vncPassword,new LexRfbInput.Listener(){
            @Override public void onReady(int width,int height){
                main.post(()->{
                    if(destroyed||rfb!=client)return;
                    padState=tr("pad listo","pad ready")+" ("+width+"×"+height+")";
                    updateStatus();
                });
            }
            @Override public void onError(String detail){
                main.post(()->{
                    if(destroyed||rfb!=client)return;
                    padState=tr("pad: ","pad: ")+detail;
                    updateStatus();
                });
            }
        });
    }

    private void closeRfb(){
        LexRfbInput client=rfb;
        rfb=null;
        if(client!=null)client.close();
    }

    private void click(int button){
        LexRfbInput client=rfb;
        if(client==null)return;
        client.pointer(pointer[0],pointer[1],button);
        client.pointer(pointer[0],pointer[1],0);
    }

    /** Absolute mapping to the Xvnc geometry: moving the finger moves the
     *  pointer, a tap clicks, and a drag past 10 dp presses and drags. The
     *  buttons below click without moving the pointer. */
    private void pointerTo(View pad,float x,float y,int mask){
        LexRfbInput client=rfb;
        int width=pad.getWidth(),height=pad.getHeight();
        if(client==null||width<=0||height<=0)return;
        int px=clamp(Math.round(x/width*vncWidth),0,vncWidth-1);
        int py=clamp(Math.round(y/height*vncHeight),0,vncHeight-1);
        pointer[0]=px;
        pointer[1]=py;
        client.pointer(px,py,mask);
    }

    private static int clamp(int value,int min,int max){
        return value<min?min:value>max?max:value;
    }

    private void toggleCtrl(){
        ctrlArmed=!ctrlArmed;
        if(ctrlButton!=null)
            ctrlButton.setBackground(rounded(ctrlArmed?ACCENT:SURFACE,12));
    }

    private void toggleKeyboard(){
        InputMethodManager imm=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if(imm==null||keyField==null)return;
        if(keyField.hasFocus()){
            imm.hideSoftInputFromWindow(keyField.getWindowToken(),0);
            keyField.clearFocus();
            if(keyboardButton!=null)keyboardButton.setText(tr("Teclado","Keyboard"));
        }else{
            keyField.requestFocus();
            imm.showSoftInput(keyField,InputMethodManager.SHOW_IMPLICIT);
            if(keyboardButton!=null)keyboardButton.setText(tr("Ocultar teclado","Hide keyboard"));
        }
    }

    private void hideKeyboard(){
        if(keyField==null)return;
        InputMethodManager imm=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if(imm!=null)imm.hideSoftInputFromWindow(keyField.getWindowToken(),0);
        keyField.clearFocus();
    }

    private void sendText(String text){
        if(text==null)return;
        for(int i=0;i<text.length();i++)typeChar(text.charAt(i));
    }

    private void typeChar(char value){
        LexRfbInput client=rfb;
        if(client==null)return;
        if(value=='\n'||value=='\r'){tapKey(client,0xff0d);return;}
        if(value=='\t'){tapKey(client,0xff09);return;}
        if(value<0x20||value>0x7e)return; // printable ASCII only
        if(ctrlArmed){
            client.key(0xffe3,true);
            tapKey(client,Character.toLowerCase(value));
            client.key(0xffe3,false);
            ctrlArmed=false;
            if(ctrlButton!=null)ctrlButton.setBackground(rounded(SURFACE,12));
        }else tapKey(client,value);
    }

    private static void tapKey(LexRfbInput client,int keysym){
        client.key(keysym,true);
        client.key(keysym,false);
    }

    private void sendAndroidKey(KeyEvent event){
        LexRfbInput client=rfb;
        if(client==null)return;
        int keysym=keysymFor(event.getKeyCode());
        if(keysym!=0)tapKey(client,keysym);
    }

    private static int keysymFor(int keyCode){
        switch(keyCode){
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:return 0xff0d;
            case KeyEvent.KEYCODE_DEL:return 0xff08;
            case KeyEvent.KEYCODE_TAB:return 0xff09;
            case KeyEvent.KEYCODE_ESCAPE:return 0xff1b;
            case KeyEvent.KEYCODE_DPAD_LEFT:return 0xff51;
            case KeyEvent.KEYCODE_DPAD_UP:return 0xff52;
            case KeyEvent.KEYCODE_DPAD_RIGHT:return 0xff53;
            case KeyEvent.KEYCODE_DPAD_DOWN:return 0xff54;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:return 0xffe3;
            default:return 0;
        }
    }

    private static String describe(Throwable error){
        Throwable cause=error;
        while(cause.getCause()!=null&&cause.getCause()!=cause)cause=cause.getCause();
        String message=cause.getMessage();
        return message==null||message.trim().isEmpty()?cause.getClass().getSimpleName():message.trim();
    }

    private final class Pad extends View{
        private final Paint hint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private float downX,downY;
        private boolean dragging;
        Pad(){
            super(LexMonitorActivity.this);
            setBackground(rounded(SURFACE,16));
            setContentDescription(tr("Área táctil del escritorio","Desktop touch area"));
            hint.setColor(0x33EEEEEC);
            hint.setTextSize(dp(10));
            hint.setTextAlign(Paint.Align.CENTER);
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            canvas.drawText(tr("TOCA PARA HACER CLIC · ARRASTRA PARA ARRASTRAR",
                "TAP TO CLICK · DRAG TO DRAG"),getWidth()/2f,dp(20),hint);
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    downX=event.getX();
                    downY=event.getY();
                    dragging=false;
                    pointerTo(this,downX,downY,0);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if(!dragging){
                        float dx=event.getX()-downX,dy=event.getY()-downY;
                        int slop=dp(10);
                        if(dx*dx+dy*dy>slop*slop){
                            dragging=true;
                            pointerTo(this,downX,downY,1);
                        }
                    }
                    pointerTo(this,event.getX(),event.getY(),dragging?1:0);
                    return true;
                case MotionEvent.ACTION_UP:
                    if(dragging){
                        pointerTo(this,event.getX(),event.getY(),0);
                    }else{
                        pointerTo(this,event.getX(),event.getY(),1);
                        pointerTo(this,event.getX(),event.getY(),0);
                    }
                    performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pointerTo(this,event.getX(),event.getY(),0);
                    performClick();
                    return true;
                default:return super.onTouchEvent(event);
            }
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }

    private final class ScrollStrip extends View{
        private final Paint arrows=new Paint(Paint.ANTI_ALIAS_FLAG);
        private float lastY;
        ScrollStrip(){
            super(LexMonitorActivity.this);
            setBackground(rounded(SURFACE_ALT,12));
            setContentDescription(tr("Tira de desplazamiento","Scroll strip"));
            arrows.setColor(SECONDARY);
            arrows.setStyle(Paint.Style.STROKE);
            arrows.setStrokeWidth(dp(2));
            arrows.setStrokeCap(Paint.Cap.ROUND);
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            float cx=getWidth()/2f;
            float top=getHeight()*0.42f,bottom=getHeight()*0.58f;
            Path path=new Path();
            path.moveTo(cx-dp(7),top+dp(5));
            path.lineTo(cx,top-dp(5));
            path.lineTo(cx+dp(7),top+dp(5));
            path.moveTo(cx-dp(7),bottom-dp(5));
            path.lineTo(cx,bottom+dp(5));
            path.lineTo(cx+dp(7),bottom-dp(5));
            canvas.drawPath(path,arrows);
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    lastY=event.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float delta=event.getY()-lastY;
                    if(Math.abs(delta)>=dp(14)){
                        LexRfbInput client=rfb;
                        if(client!=null)client.scroll(delta>0?1:-1);
                        lastY=event.getY();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    performClick();
                    return true;
                default:return super.onTouchEvent(event);
            }
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }

    /** A transparent, 1dp text field whose InputConnection forwards text and
     *  control keys to RFB instead of storing them. */
    private final class KeyField extends EditText{
        KeyField(Context context){
            super(context);
            setInputType(android.text.InputType.TYPE_CLASS_TEXT
                |android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                |android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI|EditorInfo.IME_FLAG_NO_FULLSCREEN);
            setTextColor(Color.TRANSPARENT);
            setCursorVisible(false);
            setBackgroundColor(Color.TRANSPARENT);
            setFocusableInTouchMode(true);
        }
        @Override public InputConnection onCreateInputConnection(EditorInfo info){
            InputConnection base=super.onCreateInputConnection(info);
            return base==null?null:new PadConnection(base);
        }
    }

    private final class PadConnection extends InputConnectionWrapper{
        PadConnection(InputConnection target){super(target,false);}
        @Override public boolean commitText(CharSequence text,int newCursorPosition){
            sendText(text==null?"":text.toString());
            return true;
        }
        @Override public boolean setComposingText(CharSequence text,int newCursorPosition){
            return true;
        }
        @Override public boolean finishComposingText(){
            return true;
        }
        @Override public boolean deleteSurroundingText(int beforeLength,int afterLength){
            LexRfbInput client=rfb;
            if(client!=null)for(int i=0;i<beforeLength;i++)tapKey(client,0xff08);
            return true;
        }
        @Override public boolean deleteSurroundingTextInCodePoints(int beforeLength,int afterLength){
            return deleteSurroundingText(beforeLength,afterLength);
        }
        @Override public boolean sendKeyEvent(KeyEvent event){
            if(event.getAction()==KeyEvent.ACTION_DOWN)sendAndroidKey(event);
            return true;
        }
    }

    private void immersive(){
        if(Build.VERSION.SDK_INT>=30){
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller=getWindow().getInsetsController();
            if(controller!=null){
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    @Override public void onWindowFocusChanged(boolean focus){
        super.onWindowFocusChanged(focus);
        if(focus)immersive();
    }

    @Override protected void onResume(){
        super.onResume();
        resumed=true;
        if(displays!=null)displays.registerDisplayListener(displayListener,main);
        refreshDisplays();
    }

    @Override protected void onPause(){
        resumed=false;
        if(displays!=null)try{displays.unregisterDisplayListener(displayListener);}
        catch(Exception ignored){}
        dismissPresentation();
        hideKeyboard();
        if(keyboardButton!=null)keyboardButton.setText(tr("Teclado","Keyboard"));
        super.onPause();
    }

    @Override protected void onDestroy(){
        destroyed=true;
        main.removeCallbacksAndMessages(null);
        if(displays!=null)try{displays.unregisterDisplayListener(displayListener);}
        catch(Exception ignored){}
        dismissPresentation();
        closeRfb();
        if(ownDesktop){
            // Best effort: the stop is a normal typed Core job. If the process
            // dies first, the desktop simply remains available, never half-off.
            final Context app=getApplicationContext();
            new Thread(()->{
                try{
                    JSONObject request=new JSONObject().put("tool","system.desktop")
                        .put("args",new JSONObject().put("action","stop"))
                        .put("request_id",UUID.randomUUID().toString());
                    app.getContentResolver().call(CoreProvider.URI,"submit",request.toString(),null);
                }catch(Exception ignored){}
            },"lex-desktop-stop").start();
        }
        io.shutdownNow();
        super.onDestroy();
    }
}
