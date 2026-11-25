package org.lambdaproject.lambda;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** LEX mode 1: local Kali XFCE desktop in landscape through a native RFB
 *  client (LexRfbClient + LexDesktopView) over loopback. Back leaves the
 *  viewer; the desktop keeps running. The connection is dropped on pause and
 *  re-established on resume, so every resume starts with a full refresh. */
public final class LexActivity extends Activity {
    private static final int BG=0xff101112,FG=0xffe9e9e9,SECONDARY=0xff9aa0a6;

    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Runnable pollTask=this::checkDesktop;
    private final Runnable reconnectTask=()->{reconnectPending=false;connectViewer();};

    private FrameLayout root;
    private LexDesktopView desktop;
    private TextView status;
    private Button close,keyboard;
    private JSONObject desktopInfo;
    private volatile LexRfbClient client;
    private volatile int generation;
    private int retries;
    private boolean destroyed,closing,paused,starting,reconnectPending,keyboardVisible;

    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private String tr(String es,String en){return Locale.getDefault().getLanguage().equals("en")?en:es;}

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        root=new FrameLayout(this);root.setBackgroundColor(BG);
        desktop=new LexDesktopView(this);
        desktop.setHost(new LexDesktopView.Host(){
            @Override public void onPointer(int x,int y,int mask){
                LexRfbClient c=client;if(c!=null)c.pointer(x,y,mask);
            }
            @Override public void onScroll(int steps){
                LexRfbClient c=client;if(c!=null)c.scroll(steps);
            }
            @Override public void onKey(int keysym,boolean down){
                LexRfbClient c=client;if(c!=null)c.key(keysym,down);
            }
        });
        desktop.setKeyboardStateListener(visible->main.post(()->setKeyboardVisible(visible)));
        root.addView(desktop,new FrameLayout.LayoutParams(-1,-1));
        status=new TextView(this);
        status.setText(tr("Iniciando escritorio…","Starting desktop…"));
        status.setTextColor(SECONDARY);status.setTextSize(TypedValue.COMPLEX_UNIT_SP,16);
        status.setGravity(Gravity.CENTER);status.setPadding(dp(32),dp(16),dp(32),dp(16));
        root.addView(status,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));
        LinearLayout buttons=new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        keyboard=pillButton(tr("Teclado","Keyboard"));
        keyboard.setVisibility(View.GONE);
        keyboard.setOnClickListener(v->{
            if(keyboardVisible)desktop.hideKeyboard();else desktop.showKeyboard();
        });
        close=pillButton(tr("Cerrar","Close"));
        close.setVisibility(View.GONE);
        close.setOnClickListener(v->finish());
        LinearLayout.LayoutParams keyboardParams=new LinearLayout.LayoutParams(-2,dp(40));
        LinearLayout.LayoutParams closeParams=new LinearLayout.LayoutParams(-2,dp(40));
        buttons.addView(keyboard,keyboardParams);
        buttons.addView(close,closeParams);
        close.setPadding(dp(16),0,dp(16),0);
        FrameLayout.LayoutParams barParams=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        barParams.bottomMargin=dp(16);
        root.addView(buttons,barParams);
        setContentView(root);
        immersive();
        startDesktop();
    }

    private Button pillButton(String label){
        Button button=new Button(this);
        button.setText(label);button.setAllCaps(false);
        button.setTextColor(BG);button.setBackgroundColor(FG);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP,13);
        button.setAlpha(0.92f);
        button.setMinWidth(0);button.setMinimumWidth(0);
        button.setPadding(dp(16),0,dp(16),0);
        return button;
    }

    private void setKeyboardVisible(boolean visible){
        keyboardVisible=visible;
        keyboard.setText(visible?tr("Ocultar teclado","Hide keyboard"):tr("Teclado","Keyboard"));
    }

    private void startDesktop(){
        if(starting||destroyed||closing)return;
        starting=true;
        io.execute(()->{
            JSONObject result=null;String error=null;
            try{
                // Attach to an already running desktop; only start when none exists.
                Bundle s=getContentResolver().call(CoreProvider.URI,"desktop_status","{}",null);
                JSONObject statusObject=new JSONObject(s.getString("json"));
                if(statusObject.optBoolean("ok")&&statusObject.optBoolean("running")){
                    result=statusObject.optJSONObject("desktop");
                }else{
                    result=startJob();
                }
                if(result==null)throw new IllegalStateException("desktop_start_timeout");
                if(!result.optBoolean("running")&&result.optInt("running",0)!=1)
                    throw new IllegalStateException("desktop_start_failed");
            }catch(Exception e){error=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
            JSONObject value=result;String detail=error;
            main.post(()->{
                starting=false;
                if(destroyed||closing)return;
                if(value==null){fail(detail);return;}
                desktopInfo=value;
                retries=0;
                status.setText(tr("Conectando al escritorio…","Connecting to desktop…"));
                status.setTextColor(SECONDARY);
                status.setVisibility(View.VISIBLE);
                connectViewer();
                pollDesktop();
            });
        });
    }

    private JSONObject startJob()throws Exception{
        JSONObject request=new JSONObject().put("tool","system.desktop")
            .put("args",new JSONObject().put("action","start"))
            .put("request_id",UUID.randomUUID().toString());
        Bundle b=getContentResolver().call(CoreProvider.URI,"submit",request.toString(),null);
        JSONObject response=new JSONObject(b.getString("json"));
        if(!response.optBoolean("ok"))throw new IllegalStateException(response.optString("detail",response.optString("error","start_failed")));
        String id=response.getJSONObject("job").getString("id");
        long until=SystemClock.elapsedRealtime()+90000;
        while(SystemClock.elapsedRealtime()<until){
            Bundle g=getContentResolver().call(CoreProvider.URI,"get",new JSONObject().put("id",id).toString(),null);
            JSONObject job=new JSONObject(g.getString("json")).optJSONObject("job");
            String jobState=job==null?"":job.optString("state");
            if(jobState.equals("completed"))return job.optJSONObject("result");
            if(jobState.equals("failed")||jobState.equals("cancelled")||jobState.equals("interrupted"))
                throw new IllegalStateException(job.optString("error",jobState));
            Thread.sleep(400);
        }
        // Do not leave a queued start that would bring the desktop up later.
        try{getContentResolver().call(CoreProvider.URI,"cancel",new JSONObject().put("id",id).toString(),null);}catch(Exception ignored){}
        throw new IllegalStateException("desktop_start_timeout");
    }

    /** One native RFB connection per attempt. Reconnect uses 1,2,4,8 then 10 s
     *  while the activity is resumed and the desktop is still running. */
    private void connectViewer(){
        if(destroyed||closing||paused||desktopInfo==null)return;
        main.removeCallbacks(reconnectTask);
        reconnectPending=false;
        closeClient();
        final int attempt=generation;
        final int port=desktopInfo.optInt("vnc_port",5901);
        final String password=desktopInfo.optString("password","");
        final LexRfbClient connection=new LexRfbClient();
        client=connection;
        connection.connect("127.0.0.1",port,password,new LexRfbClient.Listener(){
            @Override public void onReady(int width,int height,int[] pixels){
                if(attempt!=generation)return;
                desktop.setFramebuffer(pixels,width,height);
                main.post(()->{
                    if(destroyed||closing||attempt!=generation)return;
                    retries=0;
                    status.setVisibility(View.GONE);
                    desktop.setVisibility(View.VISIBLE);
                    desktop.requestFocus();
                    keyboard.setVisibility(View.VISIBLE);
                    close.setVisibility(View.VISIBLE);
                    pollDesktop();
                });
            }
            @Override public void onFramebufferUpdate(int[] pixels,int[] damage,int count){
                if(attempt!=generation)return;
                desktop.applyDamage(pixels,damage,count);
            }
            @Override public void onError(String detail){
                main.post(()->onViewerError(attempt,detail));
            }
        });
    }

    private void onViewerError(int attempt,String detail){
        if(destroyed||closing||attempt!=generation)return;
        LexRfbClient c=client;
        client=null;
        if(c!=null)c.close();
        status.setText(tr("Conexión perdida","Connection lost")+"\n"+detail+"\n"
            +tr("Reconectando…","Reconnecting…"));
        status.setTextColor(FG);
        status.setVisibility(View.VISIBLE);
        keyboard.setVisibility(View.GONE);
        scheduleReconnect();
    }

    private void scheduleReconnect(){
        if(destroyed||closing||paused||reconnectPending)return;
        long delay=Math.min(10000L,1000L<<Math.min(retries,4));
        retries++;
        reconnectPending=true;
        main.postDelayed(reconnectTask,delay);
    }

    private void closeClient(){
        generation++;
        LexRfbClient c=client;
        client=null;
        if(c!=null)c.close();
    }

    /** A stop from the notification or another client also closes this viewer
     *  and returns to Operator on the LEX tab. */
    private void pollDesktop(){
        if(destroyed||closing||paused)return;
        main.removeCallbacks(pollTask);
        main.postDelayed(pollTask,1500);
    }

    private void checkDesktop(){
        if(destroyed||closing||paused)return;
        io.execute(()->{
            boolean running=true;
            JSONObject info=null;
            try{
                Bundle b=getContentResolver().call(CoreProvider.URI,"desktop_status","{}",null);
                JSONObject s=new JSONObject(b.getString("json"));
                running=s.optBoolean("ok")&&s.optBoolean("running");
                if(running)info=s.optJSONObject("desktop");
            }catch(Exception ignored){}
            final boolean alive=running;
            final JSONObject current=info;
            main.post(()->{
                if(destroyed||closing||paused)return;
                if(!alive){returnToOperator();return;}
                if(current!=null)desktopInfo=current;
                pollDesktop();
            });
        });
    }

    private void returnToOperator(){
        if(destroyed||closing||isFinishing())return;
        closing=true;
        closeClient();
        if(!paused){
            android.content.Intent intent=new android.content.Intent(this,MainActivity.class)
                .putExtra("open","lex_tab")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP|android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        }
        finish();
    }

    private void fail(String detail){
        status.setText(tr("No se pudo iniciar el escritorio","Could not start the desktop")
            +"\n"+(detail==null?"":detail));
        status.setTextColor(FG);
        close.setVisibility(View.VISIBLE);
    }

    private void immersive(){
        if(android.os.Build.VERSION.SDK_INT>=30){
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller=getWindow().getInsetsController();
            if(controller!=null){
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    @Override public void onWindowFocusChanged(boolean focus){
        super.onWindowFocusChanged(focus);if(focus)immersive();
    }

    /** Leave the viewer without stopping the desktop. */
    @Override public void onBackPressed(){closing=true;closeClient();finish();}

    @Override protected void onPause(){
        paused=true;
        setKeyboardVisible(false);
        main.removeCallbacks(reconnectTask);
        main.removeCallbacks(pollTask);
        reconnectPending=false;
        closeClient();
        super.onPause();
    }

    @Override protected void onResume(){
        super.onResume();
        if(!paused)return;
        paused=false;
        if(destroyed||closing)return;
        if(desktopInfo!=null){
            // A fresh connection requests a full (non-incremental) update.
            status.setText(tr("Conectando al escritorio…","Connecting to desktop…"));
            status.setTextColor(SECONDARY);
            status.setVisibility(desktop.hasFrame()?View.GONE:View.VISIBLE);
            connectViewer();
            pollDesktop();
        }else if(!starting){
            startDesktop();
        }
    }

    @Override protected void onDestroy(){
        destroyed=true;
        main.removeCallbacksAndMessages(null);
        closeClient();
        io.shutdownNow();
        super.onDestroy();
    }
}
