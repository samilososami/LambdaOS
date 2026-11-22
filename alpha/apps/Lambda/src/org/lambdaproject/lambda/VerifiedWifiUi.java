package org.lambdaproject.lambda;

import android.os.*;
import android.view.Gravity;
import android.widget.*;
import org.json.*;
import java.util.UUID;
import java.util.concurrent.*;

/** Shared result action. Credentials never pass through the button or its RPC. */
final class VerifiedWifiUi {
    final MainActivity a;
    final Handler main=new Handler(Looper.getMainLooper());
    final ExecutorService io=Executors.newSingleThreadExecutor();
    TextView button,detail;String source,job;int epoch;boolean dead,paused;
    final Runnable poll=this::refresh;
    VerifiedWifiUi(MainActivity a){this.a=a;}
    String t(String es,String en){return a.tr(es,en);}
    void detach(){epoch++;main.removeCallbacks(poll);button=null;detail=null;source=null;job=null;}
    void pause(){paused=true;main.removeCallbacks(poll);}
    void resume(){paused=false;if(button!=null&&job!=null)refresh();}
    void destroy(){dead=true;detach();io.shutdownNow();}
    void attach(LinearLayout container,String receipt){
        detach();source=receipt;job=a.getPreferences(0).getString("connect_pending_"+source,null);
        a.space(container,16);
        button=a.text(t("Conectar","Connect"),17,true,a.background);
        button.setGravity(Gravity.CENTER);button.setMinHeight(a.dp(52));
        button.setPadding(a.dp(16),a.dp(14),a.dp(16),a.dp(14));a.card(button,a.foreground,16);
        a.touch(button,t("Conectar","Connect"),this::connect);
        container.addView(button,new LinearLayout.LayoutParams(-1,-2));a.space(container,8);
        detail=a.text(t("Conectar y guardar esta red Wi-Fi.","Connect and save this Wi-Fi network."),13,false,a.secondary);
        detail.setGravity(Gravity.CENTER);container.addView(detail);a.space(container,12);
        if(job!=null){busy();refresh();}
    }
    interface Reply{void done(JSONObject reply)throws Exception;}
    void call(String method,JSONObject args,Reply callback){
        int generation=epoch;
        io.execute(()->{
            JSONObject response;
            try{Bundle b=a.getContentResolver().call(CoreProvider.URI,method,args.toString(),null);response=new JSONObject(b.getString("json"));}
            catch(Exception e){response=CoreEngine.error(e);}
            JSONObject result=response;main.post(()->{
                if(dead||generation!=epoch||button==null)return;
                try{callback.done(result);}catch(Exception e){failed();}
            });
        });
    }
    void busy(){button.setEnabled(false);button.setText(t("Conectando…","Connecting…"));detail.setText(t("Verificando la conexión con esta red…","Verifying the connection to this network…"));}
    void connect(){
        if(!button.isEnabled())return;busy();
        // Record the request before submitting, so rotation/process recreation
        // cannot accidentally submit the same action twice.
        job=UUID.randomUUID().toString();a.getPreferences(0).edit().putString("connect_pending_"+source,job).apply();
        try{call("submit",new JSONObject().put("tool","wifi.connect_verified").put("request_id",job)
                .put("args",new JSONObject().put("result_id",source)),r->{if(!r.optBoolean("ok")){failed();return;}refresh();});}
        catch(Exception e){failed();}
    }
    void refresh(){
        main.removeCallbacks(poll);if(paused||button==null||job==null)return;
        try{call("get",new JSONObject().put("id",job),r->{
            if(!r.optBoolean("ok")){failed();return;}
            JSONObject record=r.getJSONObject("job");String state=record.optString("state");
            if(state.equals("running")||state.equals("queued")){busy();if(!paused)main.postDelayed(poll,700);return;}
            JSONObject result=record.optJSONObject("result");
            if(state.equals("completed")&&result!=null&&result.optBoolean("connected")){
                clear();button.setText(t("Conectado","Connected"));button.setContentDescription(button.getText());button.setEnabled(false);
                detail.setText(result.optString("ssid")+t(" · Conexión verificada"," · Connection verified"));
            }else failed();
        });}catch(Exception e){failed();}
    }
    void clear(){a.getPreferences(0).edit().remove("connect_pending_"+source).apply();job=null;}
    void failed(){clear();button.setText(t("Conectar","Connect"));button.setEnabled(true);detail.setText(t("No se ha podido confirmar la conexión. Comprueba que la red esté disponible y vuelve a intentarlo.","Could not confirm the connection. Check that the network is available and try again."));}
}
