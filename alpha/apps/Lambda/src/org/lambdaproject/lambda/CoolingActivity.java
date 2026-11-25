package org.lambdaproject.lambda;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Fullscreen UCM/SUCM cooling screen and developer FDT heater. The only
 *  in-app exit is the stop button or the job reaching its target. */
public final class CoolingActivity extends Activity {
    private static final int BG=0xff101112, FG=0xffe9e9e9, SECONDARY=0xff9aa0a6, SURFACE=0xff1d1f20, TRACK=0xff2a2d2f;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private TextView temperature,targetView,batteryView,eta,status,actionsView;
    private ProgressBar bar;
    private Button stop,monitorBtn,smartBtn;
    private String mode="smart",jobId;
    private int target=42;
    private double startC=-1;
    private boolean finishing,stopRequested,destroyed,started,ended,kiosk;

    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String value,float size,boolean bold,int color){
        TextView view=new TextView(this);view.setText(value);view.setTextSize(TypedValue.COMPLEX_UNIT_SP,size);
        view.setTextColor(color);view.setGravity(Gravity.CENTER);view.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));
        view.setLineSpacing(dp(4),1f);return view;
    }
    private GradientDrawable card(int color,int radius){
        GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(radius));return shape;
    }

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        mode=getIntent().getStringExtra("mode");if(mode==null)mode="smart";
        target=getIntent().getIntExtra("target_c",mode.equals("heater")?65:38);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        if(mode.equals("heater")){
            WindowManager.LayoutParams lp=getWindow().getAttributes();lp.screenBrightness=1f;getWindow().setAttributes(lp);
        }
        setContentView(build());
        immersive();
        enterKiosk();
        start();
    }

    private View build(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(BG);root.setPadding(dp(28),dp(42),dp(28),dp(32));
        boolean heater=mode.equals("heater"),monitor=mode.equals("monitor");
        ImageView flake=new ImageView(this);flake.setImageResource(heater?R.drawable.ucm_fire:monitor?R.drawable.ucm_thermometer:R.drawable.ucm_snowflake);
        root.addView(flake,new LinearLayout.LayoutParams(dp(108),dp(108)));
        TextView title=text(monitor?"Temperature Monitor":heater?"Heating device up":"Cooling down the device",26,true,FG);
        title.setMaxLines(2);root.addView(title,new LinearLayout.LayoutParams(-1,-2));
        root.addView(text(monitor?"Monitor pasivo · SoC y batería":heater?"Force Device Temperature":mode.equals("ultra")?"Ultra Cooling Mode":"Smart Ultra Cooling Mode",13,false,SECONDARY),new LinearLayout.LayoutParams(-1,-2));
        LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(-1,0);gap.weight=1;root.addView(new View(this),gap);
        temperature=text("—",84,true,FG);root.addView(temperature,new LinearLayout.LayoutParams(-1,-2));
        targetView=text(monitor?"SoC · inicio —":heater?"SoC · calentando hasta "+target+" °C":"SoC · objetivo "+target+" °C",14,false,SECONDARY);
        root.addView(targetView,new LinearLayout.LayoutParams(-1,-2));
        batteryView=text("Batería (cuerpo) —",13,false,SECONDARY);
        root.addView(batteryView,new LinearLayout.LayoutParams(-1,-2));
        eta=text(monitor?"00:00":heater?"Calentando…":"Calculando descenso…",18,true,FG);
        LinearLayout.LayoutParams etaParams=new LinearLayout.LayoutParams(-1,-2);etaParams.topMargin=dp(18);root.addView(eta,etaParams);
        bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(FG));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(TRACK));
        LinearLayout.LayoutParams barParams=new LinearLayout.LayoutParams(-1,dp(6));barParams.topMargin=dp(20);root.addView(bar,barParams);
        if(monitor)bar.setVisibility(View.GONE);
        status=text(monitor?"Leyendo temperatura…":heater?"Hilos de calor activos":"Aplicando límites térmicos",13,false,SECONDARY);
        LinearLayout.LayoutParams statusParams=new LinearLayout.LayoutParams(-1,-2);statusParams.topMargin=dp(18);root.addView(status,statusParams);
        actionsView=text("",12,false,SECONDARY);root.addView(actionsView,new LinearLayout.LayoutParams(-1,-2));
        if(heater){
            LinearLayout handoff=new LinearLayout(this);
            monitorBtn=handoffButton("Monitor cooling",()->handoff("monitor"));
            smartBtn=handoffButton("Smart cooling",()->handoff("smart"));
            handoff.addView(monitorBtn,new LinearLayout.LayoutParams(0,dp(48),1));
            LinearLayout.LayoutParams smartParams=new LinearLayout.LayoutParams(0,dp(48),1);smartParams.leftMargin=dp(8);
            handoff.addView(smartBtn,smartParams);
            LinearLayout.LayoutParams handoffParams=new LinearLayout.LayoutParams(-1,-2);
            handoffParams.topMargin=dp(14);handoffParams.bottomMargin=dp(10);
            root.addView(handoff,handoffParams);
        }
        LinearLayout.LayoutParams gap2=new LinearLayout.LayoutParams(-1,0);gap2.weight=1;root.addView(new View(this),gap2);
        stop=new Button(this);stop.setText(monitor?"Cerrar":"Parar");stop.setTextSize(TypedValue.COMPLEX_UNIT_SP,17);
        stop.setTextColor(BG);stop.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        stop.setBackground(card(FG,14));stop.setMinHeight(dp(56));stop.setAllCaps(false);
        stop.setOnClickListener(v->stop());
        root.addView(stop,new LinearLayout.LayoutParams(-1,dp(56)));
        root.addView(text(monitor?"Monitor pasivo · no modifica el dispositivo":heater?"Herramienta de desarrollo · no dejar sin supervisión":"La pantalla permanece bloqueada hasta alcanzar el objetivo o pulsar Parar",11,false,SECONDARY),new LinearLayout.LayoutParams(-1,-2));
        return root;
    }

    private Button handoffButton(String label,Runnable action){
        Button button=new Button(this);button.setText(label);button.setTextSize(TypedValue.COMPLEX_UNIT_SP,14);
        button.setTextColor(FG);button.setAllCaps(false);button.setBackground(card(SURFACE,12));
        button.setMinHeight(dp(48));button.setOnClickListener(v->action.run());return button;
    }

    private void handoff(String next){
        if(finishing)return;
        exitKiosk();
        finishing=true;
        if(jobId!=null){
            String id=jobId;
            io.execute(()->{try{getContentResolver().call(CoreProvider.URI,"cancel",new JSONObject().put("id",id).toString(),null);}catch(Exception ignored){}});
        }
        android.content.Intent intent=new android.content.Intent(this,CoolingActivity.class);
        if(next.equals("monitor"))intent.putExtra("mode","monitor");
        else{intent.putExtra("mode","smart");intent.putExtra("target_c",38);}
        startActivity(intent);finish();
    }

    private void immersive(){
        Window window=getWindow();
        if(Build.VERSION.SDK_INT>=30){
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller=window.getInsetsController();
            if(controller!=null){
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }else{
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN
                |View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                |View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override public void onWindowFocusChanged(boolean focus){
        super.onWindowFocusChanged(focus);if(focus)immersive();
    }

    @Override public void onBackPressed(){ /* the only exit is the stop button */ }

    private void start(){
        started=true;
        if(mode.equals("monitor")){startMonitor();return;}
        io.execute(()->{
            JSONObject r;
            try{
                JSONObject args=new JSONObject().put("target_c",target);
                String tool;
                if(mode.equals("heater")){tool="system.heater";args.put("hold_seconds",getIntent().getIntExtra("hold_seconds",30));}
                else{tool="system.cooling";args.put("mode",mode);}
                JSONObject request=new JSONObject().put("tool",tool).put("args",args).put("request_id",UUID.randomUUID().toString());
                Bundle b=getContentResolver().call(CoreProvider.URI,"submit",request.toString(),null);
                r=new JSONObject(b.getString("json"));
            }catch(Exception e){r=CoreEngine.error(e);}
            JSONObject response=r;
            main.post(()->{
                if(destroyed)return;
                if(!response.optBoolean("ok")){fail(response.optString("detail",response.optString("error","No se pudo iniciar")));return;}
                try{jobId=response.getJSONObject("job").getString("id");}catch(Exception e){fail("bad job");return;}
                poll();
            });
        });
    }

    private void poll(){
        if(destroyed||jobId==null||finishing||ended)return;
        io.execute(()->{
            JSONObject r;
            try{
                Bundle b=getContentResolver().call(CoreProvider.URI,"get",new JSONObject().put("id",jobId).toString(),null);
                r=new JSONObject(b.getString("json"));
            }catch(Exception e){r=CoreEngine.error(e);}
            JSONObject response=r;
            main.post(()->{if(destroyed)return;draw(response);if(!finishing&&!ended)main.postDelayed(this::poll,700);});
        });
    }

    private void draw(JSONObject response){
        if(!response.optBoolean("ok")){fail(response.optString("detail","job_not_found"));return;}
        JSONObject job=response.optJSONObject("job");if(job==null)return;
        String state=job.optString("state");
        JSONObject data=job.optJSONObject("progress");if(data==null)data=job.optJSONObject("result");
        if(data==null){
            if(state.equals("failed"))fail(job.optString("error","failed"));
            return;
        }
        double temp=data.optDouble("temp_c",data.optDouble("end_c",-1));
        if(mode.equals("heater")&&data.has("end_c"))temp=data.optDouble("end_c",temp);
        if(temp>0)temperature.setText(String.format(Locale.ROOT,"%.1f°",temp));
        if(data.has("start_c"))startC=data.optDouble("start_c",startC);
        target=data.optInt("target_c",target);
        targetView.setText((mode.equals("heater")?"SoC · calentando hasta ":"SoC · objetivo ")+target+" °C");
        double batteryTemp=data.optDouble("battery_c",-1);
        if(batteryTemp>0)batteryView.setText("Batería (cuerpo) "+fmt(batteryTemp)+" °C");
        int etaSeconds=data.optInt("eta_seconds",-1);
        String phase=data.optString("phase","");
        if(mode.equals("heater")){
            if(phase.equals("holding")){
                eta.setText("Manteniendo "+target+" °C · "+data.optInt("hold_remaining",0)+" s");
                status.setText("Calentando el cuerpo del dispositivo");
            }else{
                eta.setText("Calentando…");
                status.setText("Calentando con "+data.optInt("threads",Runtime.getRuntime().availableProcessors())+" hilos");
            }
        }else{
            if(etaSeconds==0)eta.setText("Objetivo alcanzado");
            else if(etaSeconds>0)eta.setText("≈ "+clock(etaSeconds)+" restantes (predicción)");
            else eta.setText("Calculando descenso…");
            status.setText("CPU/GPU limitados · pantalla atenuada");
        }
        JSONArray actions=data.optJSONArray("actions");
        StringBuilder actionText=new StringBuilder();
        if(actions!=null)for(int i=0;i<actions.length();i++){if(i>0)actionText.append(" · ");actionText.append(actions.optString(i));}
        actionsView.setText(actionText.toString());
        if(startC>0&&temp>0){
            double span=Math.abs(startC-target);
            double done=mode.equals("heater")?(phase.equals("holding")?span:temp-startC):(startC-temp);
            int progress=span<=0?1000:(int)Math.max(0,Math.min(1000,done/span*1000));
            bar.setProgress(progress);
        }
        if(!state.equals("running")&&!state.equals("queued")){
            if(mode.equals("heater")&&state.equals("completed")){
                ended=true;
                temperature.setText(String.format(Locale.ROOT,"%.1f°",data.optDouble("end_c",temp)));
                eta.setText("Objetivo alcanzado");
                status.setText("Monitor cooling o Smart cooling, o Cerrar");
                stop.setText("Cerrar");stop.setEnabled(true);
                return;
            }
            finishing=true;stop.setEnabled(false);
            temperature.setText(String.format(Locale.ROOT,"%.1f°",data.optDouble("end_c",temp)));
            eta.setText(job.optString("state").equals("completed")?"Completado":"Detenido");
            status.setText(mode.equals("heater")?"Herramienta detenida":"Límites restaurados");
            main.postDelayed(this::finishNow,1200);
        }
    }

    private void stop(){
        if(finishing)return;
        if(ended){finishing=true;finishNow();return;}
        if(mode.equals("monitor")){finishing=true;finishNow();return;}
        if(stopRequested||jobId==null)return;
        stopRequested=true;stop.setEnabled(false);stop.setText("Deteniendo…");eta.setText("Restaurando ajustes…");
        io.execute(()->{
            try{getContentResolver().call(CoreProvider.URI,"cancel",new JSONObject().put("id",jobId).toString(),null);}catch(Exception ignored){}
        });
    }

    private long monitorStart;
    private double monitorStartC=-1,monitorMin=Double.MAX_VALUE,monitorMax=-Double.MAX_VALUE;
    private double monitorBatteryStart=-1,monitorBatteryMin=Double.MAX_VALUE,monitorBatteryMax=-Double.MAX_VALUE;
    private final java.util.ArrayList<double[]> monitorHistory=new java.util.ArrayList<>();
    private final java.util.ArrayList<Double> monitorRaw=new java.util.ArrayList<>();

    private void startMonitor(){
        monitorStart=System.currentTimeMillis();monitorStartC=-1;
        monitorMin=Double.MAX_VALUE;monitorMax=-Double.MAX_VALUE;
        monitorBatteryStart=-1;monitorBatteryMin=Double.MAX_VALUE;monitorBatteryMax=-Double.MAX_VALUE;
        monitorHistory.clear();monitorRaw.clear();
        monitorTick();
    }

    private void monitorTick(){
        if(destroyed||finishing)return;
        double raw=CoolingJob.socTempC();
        double battery=CoolingJob.batteryTempC();
        long elapsed=(System.currentTimeMillis()-monitorStart)/1000;
        if(raw>0){
            monitorRaw.add(raw);
            while(monitorRaw.size()>5)monitorRaw.remove(0);
            double temp=0;
            for(double sample:monitorRaw)temp+=sample;
            temp/=monitorRaw.size();
            if(monitorStartC<0){monitorStartC=temp;targetView.setText("SoC · inicio "+fmt(monitorStartC)+" °C");}
            if(temp<monitorMin)monitorMin=temp;
            if(temp>monitorMax)monitorMax=temp;
            temperature.setText(fmt(temp)+"°");
            monitorHistory.add(new double[]{elapsed,temp});
            while(monitorHistory.size()>120)monitorHistory.remove(0);
        }
        eta.setText(clock((int)elapsed));
        double rate=monitorSlope();
        if(!monitorRateReady())status.setText("Calculando velocidad…");
        else if(Math.abs(rate)<0.005)status.setText("SoC estable ("+String.format(Locale.ROOT,"%+.1f",rate*60)+" °C/min)");
        else status.setText("SoC "+(rate<0?"bajando ":"subiendo ")+String.format(Locale.ROOT,"%.1f",Math.abs(rate*60))+" °C/min");
        if(monitorMin!=Double.MAX_VALUE)actionsView.setText("SoC mín "+fmt(monitorMin)+" · máx "+fmt(monitorMax));
        if(battery>0){
            if(monitorBatteryStart<0)monitorBatteryStart=battery;
            if(battery<monitorBatteryMin)monitorBatteryMin=battery;
            if(battery>monitorBatteryMax)monitorBatteryMax=battery;
            batteryView.setText("Batería (cuerpo) "+fmt(battery)+" °C · mín "+fmt(monitorBatteryMin)+" · máx "+fmt(monitorBatteryMax));
        }
        main.postDelayed(this::monitorTick,500);
    }

    // A rate needs at least 8 s of samples; the first seconds after launch
    // otherwise report meaningless hundreds of °C/min.
    private boolean monitorRateReady(){
        if(monitorHistory.size()<6)return false;
        int n=Math.min(60,monitorHistory.size());
        return monitorHistory.get(monitorHistory.size()-1)[0]-monitorHistory.get(monitorHistory.size()-n)[0]>=8;
    }

    private double monitorSlope(){
        if(!monitorRateReady())return 0;
        int n=Math.min(60,monitorHistory.size());
        double sx=0,sy=0,sxx=0,sxy=0;
        for(int i=monitorHistory.size()-n;i<monitorHistory.size();i++){
            double x=monitorHistory.get(i)[0],y=monitorHistory.get(i)[1];
            sx+=x;sy+=y;sxx+=x*x;sxy+=x*y;
        }
        double den=n*sxx-sx*sx;
        return den==0?0:(n*sxy-sx*sy)/den;
    }

    private String fmt(double value){return String.format(Locale.ROOT,"%.1f",value);}

    private void fail(String detail){
        if(destroyed)return;
        finishing=true;stopRequested=true;
        eta.setText("No se pudo iniciar");status.setText(detail);actionsView.setText("");
        stop.setEnabled(true);stop.setText("Cerrar");
        stop.setOnClickListener(v->finishNow());
        bar.setProgress(0);
    }

    private String clock(int seconds){
        int minutes=seconds/60,rest=seconds%60;
        return minutes>0?minutes+" min "+rest+" s":rest+" s";
    }

    /** Silent lock-task kiosk needs device owner (or an allowlisted package):
     *  without it the system Home gesture can leave this screen. */
    private void enterKiosk(){
        try{
            android.app.admin.DevicePolicyManager dpm=(android.app.admin.DevicePolicyManager)getSystemService(DEVICE_POLICY_SERVICE);
            if(dpm==null)return;
            android.content.ComponentName admin=new android.content.ComponentName(this,LambdaDeviceAdmin.class);
            if(dpm.isDeviceOwnerApp(getPackageName()))dpm.setLockTaskPackages(admin,new String[]{getPackageName()});
            if(dpm.isLockTaskPermitted(getPackageName())){startLockTask();kiosk=true;}
        }catch(Exception ignored){}
    }

    private void exitKiosk(){
        if(!kiosk)return;kiosk=false;
        try{stopLockTask();}catch(Exception ignored){}
    }

    /** The locked task refuses finish() until lock task is left. */
    private void finishNow(){exitKiosk();finish();}

    @Override protected void onDestroy(){
        exitKiosk();
        destroyed=true;main.removeCallbacksAndMessages(null);io.shutdownNow();super.onDestroy();
    }
}
