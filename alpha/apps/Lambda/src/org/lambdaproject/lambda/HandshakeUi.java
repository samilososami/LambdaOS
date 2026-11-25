package org.lambdaproject.lambda;

import android.app.AlertDialog;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.*;

/** The native workflow observes Core receipts. Leaving it never owns/kills work. */
final class HandshakeUi {
    private final MainActivity a;private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private boolean showing,resumed=true,dead,busy;
    private int generation;private long selectionExpires;
    private String tab="explore",screen="choice",jobId,jobTool,session,adapter="",error="",operation="capture";
    private JSONArray networks=new JSONArray();private JSONObject receipt,result,target;
    private LinearLayout devices;private String deviceFingerprint="";private TextView status,detail,time,count;
    private TextView deauthButton;private boolean deauthSubmitting;
    private byte[] export;
    HandshakeUi(MainActivity a){this.a=a;jobId=a.getPreferences(0).getString("radio_job",null);jobTool=a.getPreferences(0).getString("radio_tool",null);operation=a.getPreferences(0).getString("radio_operation","capture");if(jobId!=null)screen="resume";}
    private String operationTitle(){return operation.equals("deauth")?"Deauth":"Handshake capture";}
    void save(Bundle state){state.putString("radio_screen",screen);state.putString("radio_operation",operation);state.putString("radio_job_id",jobId);state.putString("radio_job_tool",jobTool);state.putString("radio_error",error);state.putString("radio_tab",tab);}
    void restore(Bundle state){operation=state.getString("radio_operation",operation);tab=state.getString("radio_tab",tab);jobId=state.getString("radio_job_id",jobId);jobTool=state.getString("radio_job_tool",jobTool);String saved=state.getString("radio_screen","choice");error=state.getString("radio_error","");screen=Arrays.asList("choice","external","error").contains(saved)?saved:jobId!=null?"resume":"choice";}
    void openJob(String id,String tool){jobId=id;jobTool=tool;operation=tool.equals("radio.deauth")?"deauth":"capture";go("resume");}
    private String t(String es,String en){return a.tr(es,en);}
    private JSONObject o(){return new JSONObject();}private JSONObject p(String k,Object v){try{return o().put(k,v);}catch(Exception e){throw new IllegalStateException(e);}}
    private interface Reply{void done(JSONObject r)throws Exception;}
    private void rpc(String method,JSONObject args,Reply reply){int gen=generation;io.execute(()->{JSONObject r;try{Bundle b=a.getContentResolver().call(CoreProvider.URI,method,args.toString(),null);r=new JSONObject(b.getString("json"));}catch(Exception e){r=CoreEngine.error(e);}JSONObject response=r;main.post(()->{if(method.equals("submit"))busy=false;if(method.equals("capture_deauth"))deauthSubmitting=false;if(dead||gen!=generation)return;try{reply.done(response);}catch(Exception e){a.notice(e.getMessage());}});});}
    void beforeRender(){showing=false;generation++;main.removeCallbacksAndMessages(null);devices=null;status=detail=time=count=deauthButton=null;deviceFingerprint="";}
    void pause(){resumed=false;main.removeCallbacksAndMessages(null);}
    void resume(){resumed=true;main.removeCallbacksAndMessages(null);if(showing&&screen.equals("networks")&&SystemClock.elapsedRealtime()>=selectionExpires)a.render();else if(showing)schedule();}
    void destroy(){dead=true;main.removeCallbacksAndMessages(null);io.shutdownNow();}
    private void schedule(){if(!showing||!resumed)return;if(screen.equals("external"))main.postDelayed(this::pollAdapters,1000);if(screen.equals("working")||screen.equals("capture")||screen.equals("resume"))main.postDelayed(this::pollJob,650);if(screen.equals("networks")&&selectionExpires>SystemClock.elapsedRealtime())main.postDelayed(()->{if(showing&&screen.equals("networks"))a.render();},selectionExpires-SystemClock.elapsedRealtime()+100);}
    private void go(String s){screen=s;a.navigate("handshake");}
    boolean back(){if(!a.isHandshake())return false;if(screen.equals("choice")){a.navigate("category");return true;}
        if(screen.equals("working")||screen.equals("capture")){a.navigate("category");return true;}
        if(screen.equals("networks"))release();go("choice");return true;}
    private TextView chip(String text,boolean selected,Runnable action){TextView v=a.text(text,14,true,selected?a.background:a.foreground);v.setGravity(Gravity.CENTER);v.setSelected(selected);a.card(v,selected?a.foreground:a.surface,26);a.touch(v,text,action);return v;}
    void showExplore(){tab="explore";}
    void openAp(){tab="ap";}
    boolean category(){
        a.heading("Wi-Fi",t("Redes y auditorías Wi-Fi","Networks and Wi-Fi audits"));
        LinearLayout tabs=new LinearLayout(a);String[] names={t("Explorar","Explore"),"AP","Atacks","WPS"};String[] values={"explore","ap","attacks","wps"};
        for(int i=0;i<4;i++){final String value=values[i];LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,a.dp(48),1);if(i>0)lp.leftMargin=a.dp(6);tabs.addView(chip(names[i],tab.equals(value),()->{tab=value;a.render();}),lp);}
        a.content.addView(tabs);a.space(a.content,24);
        if(tab.equals("explore"))return false;
        if(tab.equals("wps")){a.wps.landing();return true;}
        if(tab.equals("ap")){a.ap.landing();return true;}
        row("Handshake capture",t("Captura pasiva · WPA / WPA2","Passive capture · WPA / WPA2"),RadioIllustration.HANDSHAKE,()->open("capture"));
        row("Deauth",t("Desconexión controlada · una ráfaga","Controlled disconnection · one burst"),RadioIllustration.SCAN,()->open("deauth"));
        return true;
    }
    private void open(String mode){
        if(jobId==null){newOperation(mode);return;}
        rpc("get",p("id",jobId),r->{
            if(!r.optBoolean("ok")&&!r.optString("detail").equals("job_not_found")){a.notice(friendly(r.optString("detail")));return;}
            JSONObject j=r.optJSONObject("job");
            if(j!=null&&Arrays.asList("queued","running").contains(j.optString("state"))){
                jobTool=j.optString("tool",jobTool);
                operation="radio.scan".equals(jobTool)?a.getPreferences(0).getString("radio_operation","capture"):"radio.deauth".equals(jobTool)?"deauth":"capture";
                go("resume");return;
            }
            // Menu entry starts fresh. Only Sessions/openJob reopens old receipts.
            // A completed survey may still own a monitor lease; captures restore themselves.
            if(j!=null&&j.optString("tool").equals("radio.scan"))release();
            newOperation(mode);
        });
    }
    private void newOperation(String mode){
        clearJob();operation=mode;session=null;target=receipt=result=null;
        adapter="";error="";selectionExpires=0;networks=new JSONArray();deauthSubmitting=false;
        a.getPreferences(0).edit().putString("radio_operation",mode).apply();
        go("choice");
    }
    void render(){showing=true;
        switch(screen){case "choice":choose();break;case "external":external();break;case "networks":networks();break;case "capture":case "working":case "resume":working();break;case "result":result();break;default:failed();}
        schedule();
    }
    private void title(String title,String subtitle){a.heading(title,subtitle);}
    private void primary(String text,Runnable click){TextView v=chip(text,true,click);v.setSelected(false);v.setTextSize(16);v.setMinHeight(a.dp(56));a.content.addView(v,new LinearLayout.LayoutParams(-1,a.dp(56)));a.space(a.content,12);}
    private void secondary(String text,Runnable click){TextView v=a.text(text,14,true,a.secondary);v.setGravity(Gravity.CENTER);a.content.addView(v,new LinearLayout.LayoutParams(-1,a.dp(48)));a.touch(v,text,click);}
    private void illustration(int type,int height,boolean animate){a.content.addView(new RadioIllustration(a,type,a.foreground,animate),new LinearLayout.LayoutParams(-1,a.dp(height)));}
    private TextView center(String s,int size,boolean bold,int color){TextView v=a.text(s,size,bold,color);v.setGravity(Gravity.CENTER);v.setLineSpacing(a.dp(3),1);a.content.addView(v,new LinearLayout.LayoutParams(-1,-2));return v;}
    private void row(String name,String sub,int icon,Runnable click){LinearLayout row=new LinearLayout(a);row.setPadding(a.dp(16),a.dp(18),a.dp(16),a.dp(18));row.setGravity(Gravity.CENTER_VERTICAL);a.card(row,a.surface,16);row.addView(new RadioIllustration(a,icon,a.foreground,false),new LinearLayout.LayoutParams(a.dp(44),a.dp(44)));LinearLayout words=a.column();words.setPadding(a.dp(14),0,a.dp(8),0);words.addView(a.text(name,17,true,a.foreground));if(!sub.isEmpty()){a.space(words,6);words.addView(a.text(sub,12,false,a.secondary));}row.addView(words,new LinearLayout.LayoutParams(0,-2,1));Glyph next=new Glyph(a,10,a.secondary);next.setRotation(180);row.addView(next,new LinearLayout.LayoutParams(a.dp(18),a.dp(24)));a.content.addView(row);a.space(a.content,10);a.touch(row,name,click);}
    private void choose(){
        title(operationTitle(),t("Elige una antena","Choose an antenna"));illustration(operation.equals("deauth")?RadioIllustration.SCAN:RadioIllustration.HANDSHAKE,125,false);a.space(a.content,24);
        LinearLayout pair=new LinearLayout(a);String[] labels={t("Interna","Internal"),t("Externa","External")};
        for(int i=0;i<2;i++){final boolean internal=i==0;LinearLayout card=a.column();card.setGravity(Gravity.CENTER);card.setPadding(a.dp(8),a.dp(18),a.dp(8),a.dp(20));a.card(card,a.surface,18);card.addView(new RadioIllustration(a,internal?RadioIllustration.PHONE:RadioIllustration.ANTENNA,a.foreground,false),new LinearLayout.LayoutParams(-1,a.dp(130)));TextView name=a.text(labels[i],18,true,a.foreground);name.setGravity(Gravity.CENTER);card.addView(name);a.space(card,8);TextView sub=a.text(internal?"Samsung S10":"USB · OTG",12,false,a.secondary);sub.setGravity(Gravity.CENTER);card.addView(sub);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i==0)lp.rightMargin=a.dp(12);pair.addView(card,lp);a.touch(card,labels[i],()->{if(internal)confirmInternal();else go("external");});}
        a.content.addView(pair);a.space(a.content,24);center(t("Escaneo pasivo en 2,4 GHz","Passive survey on 2.4 GHz"),13,false,a.secondary);
    }
    private void confirmInternal(){new AlertDialog.Builder(a).setTitle(t("Usar la antena interna","Use internal antenna")).setMessage(t("Se pausará la conexión Wi-Fi del S10 mientras se usa el modo monitor. Se restaurará al terminar o detener. Este primer escaneo es pasivo.","The S10 Wi-Fi connection pauses during monitor mode and is restored when finished or stopped. This initial survey is passive."))
        .setNegativeButton(t("Cancelar","Cancel"),null).setPositiveButton(t("Activar","Activate"),(d,w)->startScan("internal")).show();}
    private void external(){title(t("Antena externa","External antenna"),t("Conecta tu adaptador USB.","Connect your USB adapter."));illustration(RadioIllustration.PLUG,240,true);a.space(a.content,16);status=center(t("Esperando adaptador…","Waiting for adapter…"),21,true,a.foreground);a.space(a.content,10);detail=center(t("Se detectará automáticamente.","It will be detected automatically."),14,false,a.secondary);a.space(a.content,24);devices=a.column();a.content.addView(devices);}
    private void pollAdapters(){if(!showing||!resumed||!screen.equals("external"))return;rpc("radio_status",o(),r->{JSONArray items=r.optJSONArray("adapters");boolean ready=r.optBoolean("available");if(items==null)items=new JSONArray();String key=ready+items.toString();if(!key.equals(deviceFingerprint)){deviceFingerprint=key;devices.removeAllViews();int found=0;for(int i=0;i<items.length();i++){JSONObject d=items.getJSONObject(i);if(!d.optString("kind").equals("external"))continue;found++;TextView v=a.text(d.optString("name")+"\n"+d.optString("chipset")+" · "+d.optString("vid_pid")+(d.optBoolean("supported")?"":t("\nControlador no compatible","\nUnsupported driver")),15,true,a.foreground);v.setLineSpacing(a.dp(6),1);v.setPadding(a.dp(18),a.dp(18),a.dp(18),a.dp(18));a.card(v,a.surface,16);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=a.dp(10);devices.addView(v,lp);if(ready&&d.optBoolean("supported"))a.touch(v,d.optString("name"),()->startScan(d.optString("id")));else v.setAlpha(.55f);}
                if(!ready){status.setText(t("Servicio de captura no disponible","Capture service unavailable"));detail.setText(t("La versión de pruebas necesita el servicio root de Lambda activo. No se ha activado ninguna antena.","This test version requires Lambda's root service. No adapter was activated."));}
                else{status.setText(found>0?t("Elige tu antena","Choose your adapter"):t("Esperando adaptador…","Waiting for adapter…"));detail.setText(found>0?t("Controlador e interfaz detectados.","Driver and interface detected."):t("Se detectará automáticamente.","It will be detected automatically."));}
            }if(showing&&screen.equals("external"))main.postDelayed(this::pollAdapters,1300);});}
    private void startScan(String id){adapter=id;start("radio.scan",p("adapter",id),"working");}
    private void start(String tool,JSONObject args,String next){if(busy)return;busy=true;try{rpc("submit",p("tool",tool).put("args",args).put("request_id",UUID.randomUUID().toString()),r->{busy=false;if(!r.optBoolean("ok")){fail(r.optString("detail"));return;}jobId=r.getJSONObject("job").getString("id");jobTool=tool;a.getPreferences(0).edit().putString("radio_job",jobId).putString("radio_tool",jobTool).putString("radio_operation",operation).apply();go(next);});}catch(Exception e){busy=false;fail(e.getMessage());}}
    private void stop(){if(jobId!=null)rpc("cancel",p("id",jobId),r->{if(deauthButton!=null)deauthButton.setEnabled(false);if(status!=null)status.setText(t("Restaurando antena…","Restoring adapter…"));});}
    private void confirmCaptureDeauth(){
        if(jobId==null||target==null||deauthSubmitting)return;
        final String captureId=jobId;
        new AlertDialog.Builder(a).setTitle(t("Deauth durante la captura","Deauth during capture"))
            .setMessage(target.optString("ssid")+"\n"+target.optString("bssid")+t("\n\nPuede desconectar dispositivos de esta red. Confirma que tienes autorización.\n\nUna ráfaga por captura, máximo 10 s. La captura continúa sin interrupción.","\n\nThis can disconnect devices on this network. Confirm you are authorized.\n\nOne burst per capture, up to 10 s. Capture continues uninterrupted."))
            .setNegativeButton(t("Cancelar","Cancel"),null).setPositiveButton(t("Enviar ráfaga","Send burst"),(d,w)->{
                if(deauthSubmitting||!captureId.equals(jobId))return;
                deauthSubmitting=true;if(deauthButton!=null){deauthButton.setEnabled(false);deauthButton.setText(t("Solicitando…","Requesting…"));}
                try{rpc("capture_deauth",p("id",captureId).put("request_id",UUID.randomUUID().toString()).put("authorized",true),r->{
                    deauthSubmitting=false;
                    if(r.optBoolean("ok")){if(receipt!=null)receipt.put("deauth_request",r.getJSONObject("request"));}
                    else a.notice(t("No se ha iniciado otra ráfaga: ","No additional burst started: ")+friendly(r.optString("detail")));
                });}catch(Exception e){deauthSubmitting=false;a.notice(e.getMessage());}
            }).show();
    }
    private void working(){boolean captureScreen=screen.equals("capture"),resumeScreen=screen.equals("resume");
        title(operationTitle(),captureScreen&&target!=null?target.optString("ssid"):resumeScreen?t("Recuperando la sesión","Restoring session view"):t("Preparando la antena","Preparing adapter"));
        String initial=resumeScreen?t("Consultando el estado…","Checking status…"):captureScreen?operation.equals("deauth")?t("Preparando prueba…","Preparing test…"):t("Iniciando captura…","Starting capture…"):t("Activando modo monitor…","Activating monitor mode…");
        illustration(captureScreen?RadioIllustration.CAPTURE:RadioIllustration.SCAN,205,true);a.space(a.content,18);status=center(initial,21,true,a.foreground);a.space(a.content,12);detail=center(t("Comprobando el estado real del adaptador.","Checking actual adapter state."),14,false,a.secondary);a.space(a.content,28);
        LinearLayout metrics=new LinearLayout(a);time=a.text("00:00",28,true,a.foreground);count=a.text("—",28,true,a.foreground);time.setTypeface(Typeface.create("sans-serif-medium",0));for(TextView v:new TextView[]{time,count}){v.setGravity(Gravity.CENTER);metrics.addView(v,new LinearLayout.LayoutParams(0,a.dp(48),1));}a.content.addView(metrics);a.space(a.content,24);
        if(captureScreen&&operation.equals("capture")){
            LinearLayout actions=new LinearLayout(a);deauthButton=chip("Deauth",false,this::confirmCaptureDeauth);deauthButton.setTextSize(16);deauthButton.setEnabled(false);
            TextView stop=chip(t("Detener","Stop"),true,this::stop);stop.setTextSize(16);stop.setSelected(false);
            LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,a.dp(56),1);left.rightMargin=a.dp(12);actions.addView(deauthButton,left);actions.addView(stop,new LinearLayout.LayoutParams(0,a.dp(56),1));a.content.addView(actions);a.space(a.content,12);
        }else primary(t("Detener","Stop"),this::stop);
        secondary(t("Puedes salir; la tarea continúa en Sesiones.","You can leave; the task continues in Sessions."),()->a.navigate("sessions"));}
    private void pollJob(){if(!showing||!resumed||jobId==null)return;rpc("get",p("id",jobId),r->{if(!r.optBoolean("ok")){fail(r.optString("detail"));return;}JSONObject j=r.getJSONObject("job");receipt=j;String state=j.optString("state");
        if(state.equals("queued")||state.equals("running")){JSONObject progress=j.optJSONObject("progress");if(progress!=null&&status!=null){String phase=progress.optString("phase");boolean capturing=phase.equals("capturing")||phase.equals("deauth");if(capturing&&!screen.equals("capture")){target=progress.optJSONObject("target");go("capture");return;}
            status.setText(capturing?t("Esperando conexión","Waiting for connection"):phase.equals("scanning")?t("Buscando redes…","Finding networks…"):phase.equals("stabilizing")?t("Comprobando conexión USB…","Checking USB connection…"):t("Activando modo monitor…","Activating monitor mode…"));
            detail.setText(capturing?t("Captura pasiva. Conecta un dispositivo a esta red para observar el intercambio.","Passive capture. Connect a device to this network to observe the exchange."):phase.equals("scanning")?t("Canal ","Channel ")+progress.optInt("channel")+" · 2.4 GHz":t("Sin inyección ni deauth.","No injection or deauth."));
            if(phase.equals("deauth")){status.setText(t("Prueba de desconexión","Disconnection test"));detail.setText(t("Una sola ráfaga sobre la red confirmada. Sin reintentos automáticos.","One burst on the confirmed network. No automatic retries."));}
            if(deauthButton!=null){
                JSONObject sent=progress.optJSONObject("deauth");boolean requested=j.has("deauth_request");String outcome=sent==null?"":sent.optString("outcome");
                boolean canSend=!requested&&!deauthSubmitting&&!j.optBoolean("cancel_requested")&&phase.equals("capturing")&&progress.optLong("seconds")<165;
                deauthButton.setEnabled(canSend);deauthButton.setAlpha(canSend?1f:.55f);
                deauthButton.setText(outcome.equals("sending")?t("Enviando…","Sending…"):requested?t("Deauth solicitado","Deauth requested"):"Deauth");
                if(requested){detail.setText(t("La captura sigue activa. ","Capture remains active. ")+(sent==null?t("Ráfaga solicitada; esperando al servicio.","Burst requested; waiting for service."):deauthSummary(sent)));
                    if(outcome.equals("sending"))status.setText(t("Enviando deauth","Sending deauth"));
                    if(sent!=null&&!outcome.equals("sending"))deauthButton.setText(sent.optBoolean("transmission_reported")?t("Ráfaga enviada","Burst sent"):t("Sin reintentos","No retries"));}
            }
            long seconds=capturing?progress.optLong("seconds"):(System.currentTimeMillis()-j.optLong("started_ms",System.currentTimeMillis()))/1000;time.setText(String.format(Locale.US,"%02d:%02d",seconds/60,seconds%60));count.setText(phase.equals("deauth")?t("Máx. 1","Max. 1"):capturing?progress.optInt("eapol_frames")+" EAPOL":progress.optInt("networks")+t(" redes"," networks"));
        if(phase.startsWith("crack")){
            status.setText(j.optBoolean("cancel_requested")?t("Deteniendo comprobación…","Stopping check…"):t("Comprobación rápida","Quick dictionary check"));
            detail.setText(t("Wi-Fi restaurado · Aircrack optimizado · 4.800 claves. La captura se conserva.","Wi-Fi restored · Optimized Aircrack · 4,800 keys. Capture is preserved."));
            time.setText(String.format(Locale.getDefault(),"%.1f s",progress.optDouble("seconds")));count.setText(progress.optLong("tested")+" / 4800");
            if(deauthButton!=null){deauthButton.setEnabled(false);deauthButton.setAlpha(.35f);}
        }else if(j.optBoolean("cancel_requested")){status.setText(t("Deteniendo y restaurando…","Stopping and restoring…"));detail.setText(t("Esperando la confirmación del servicio.","Waiting for service confirmation."));}
        }if(showing)main.postDelayed(this::pollJob,850);return;}
        result=j.optJSONObject("result");if(result==null){fail(j.optString("error",state));return;}
        if(j.optString("tool").equals("radio.scan")){session=result.optString("session");selectionExpires=result.optLong("expires_elapsed_ms");adapter=j.optJSONObject("args").optString("adapter","internal");networks=result.optJSONArray("networks");if(networks==null)networks=new JSONArray();go("networks");}else go("result");
    });}
    private void networks(){title(t("Elige una red","Choose a network"),t("Selecciona solo una red propia o autorizada.","Select only an owned or authorized network."));
        if(selectionExpires==0||SystemClock.elapsedRealtime()>=selectionExpires){center(t("El escaneo ha caducado","Survey expired"),21,true,a.foreground);a.space(a.content,12);center(t("La antena se libera automáticamente tras dos minutos. Escanea de nuevo para seleccionar una red actual.","The adapter is released automatically after two minutes. Scan again to choose a currently visible network."),14,false,a.secondary);a.space(a.content,24);primary(t("Buscar de nuevo","Scan again"),()->startScan(adapter));return;}
        center(t("Modo monitor · 2,4 GHz · Selección disponible 2 min","Monitor mode · 2.4 GHz · Select within 2 min"),12,false,a.secondary);a.space(a.content,20);
        for(int i=0;i<networks.length();i++){JSONObject n=networks.optJSONObject(i);if(n==null)continue;String name=n.optString("ssid");if(name.isEmpty())name=t("SSID oculto","Hidden SSID");int signal=n.optInt("rssi_dbm",-127);String sub=n.optString("security")+" · ch "+n.optInt("channel")+" · "+(signal<0&&signal>-127?signal+" dBm":t("Señal n/d","Signal n/a"))+"\n"+n.optString("bssid");final String label=name;
            row(name,sub,RadioIllustration.SCAN,()->{boolean active=operation.equals("deauth");if(!active&&!n.optBoolean("capture_supported")){a.notice(t("Esta captura requiere WPA/WPA2. No es un flujo WPA3/SAE.","This capture requires WPA/WPA2, not WPA3/SAE."));return;}
                String warning=active?t("Esta prueba puede desconectar dispositivos. Confirma que tienes autorización para esta red.\n\n","This test can disconnect devices. Confirm authorization for this network.\n\n"):t("¿Tienes autorización para capturar el intercambio de esta red?\n\n","Are you authorized to capture this network's exchange?\n\n");
                new AlertDialog.Builder(a).setTitle(label).setMessage(warning+n.optString("bssid")+(active?t("\nUna ráfaga. Máximo 10 s. Sin reintentos.","\nOne burst. Up to 10 s. No retries."):t("\nMáximo 3 minutos. Captura pasiva; deauth requiere otra confirmación. Al capturar se probarán localmente 4.800 claves.","\nUp to 3 minutes. Passive capture; deauth requires separate confirmation. After capture, 4,800 candidates will be checked offline.")))
                .setNegativeButton(t("Cancelar","Cancel"),null).setPositiveButton(active?t("Autorizar prueba","Authorize test"):t("Capturar y comprobar","Capture and check"),(d,w)->{target=n;deauthSubmitting=false;try{start(active?"radio.deauth":"radio.capture",p("session",session).put("bssid",n.optString("bssid")).put("authorized",true),"capture");}catch(Exception e){fail(e.getMessage());}}).show();});
        }
        if(networks.length()==0)center(t("No se observaron redes en esta pasada.","No networks observed in this pass."),17,false,a.secondary);
        a.space(a.content,16);primary(t("Buscar de nuevo","Scan again"),()->startScan(adapter));secondary(t("Terminar y restaurar Wi-Fi","Finish and restore Wi-Fi"),()->{release();clearJob();go("choice");});
    }
    private void release(){rpc("submit",p("tool","radio.release"),r->{});}
    private void clearJob(){jobId=null;jobTool=null;a.getPreferences(0).edit().remove("radio_job").remove("radio_tool").apply();}
    private void fail(String s){error=s;clearJob();go("error");}
    private void failed(){boolean stopped=error.equals("cancelled");title(stopped?t("Tarea detenida","Task stopped"):t("No se pudo continuar","Unable to continue"),stopped?t("Puedes iniciar otra cuando quieras.","You can start another when ready."):t("No se muestra una captura como válida si ha fallado.","A failed capture is never shown as valid."));illustration(RadioIllustration.PHONE,150,false);a.space(a.content,24);a.fact(t("Detalle","Details"),friendly(error));primary(t("Volver a elegir antena","Choose adapter again"),()->go("choice"));}
    private String friendly(String e){
        if(e.contains("capture_not_active")||e.contains("capture_ending"))return t("La captura ha terminado o está a punto de terminar.","Capture has ended or is about to end.");
        if(e.contains("capture_not_ready"))return t("Espera a que comience la captura.","Wait for capture to begin.");
        if(e.contains("capture_deauth_already_requested"))return t("Ya se ha solicitado la única ráfaga de esta captura.","This capture's single burst has already been requested.");
        if(e.contains("capture_tool_missing")||e.contains("airodump_failed"))return t("Airodump-ng no está disponible o se ha detenido. No se muestra un handshake válido; revisa el servicio de captura.","Airodump-ng is unavailable or stopped. No valid handshake is reported; check the capture service.");
        if(e.contains("radio_worker_offline"))return t("El servicio root de captura no está activo. En esta versión de desarrollo se inicia mediante ADB y no sobrevive a un reinicio.","Root capture service is offline. This development version starts through ADB and does not survive a reboot.");
        if(e.contains("restore_failed")||e.contains("lost_restore_pending"))return t("Se ha interrumpido el servicio y no se ha confirmado la restauración de la antena. No hay ninguna captura válida. Es necesario recuperar el servicio de Lambda antes de continuar.","The service was interrupted and adapter restoration has not been confirmed. No valid capture is available. Recover the Lambda service before continuing.");
        if(e.contains("worker_restarted"))return t("El servicio se ha reiniciado. La tarea anterior no se repetirá automáticamente; inicia un nuevo escaneo.","The service restarted. The previous task will not be replayed automatically; start a new survey.");
        if(e.contains("cancelled"))return t("Tarea detenida. Se ha solicitado restaurar la antena.","Task stopped. Adapter restoration was requested.");
        if(e.contains("scan_session_expired"))return t("La selección ha caducado y se ha restaurado el Wi-Fi. Vuelve a escanear.","Selection expired and Wi-Fi was restored. Scan again.");
        if(e.contains("adapter_missing")||e.contains("adapter_disconnected"))return t("La antena ya no está conectada o no dispone de un controlador compatible. Vuelve a conectarla y selecciónala de nuevo.","The adapter is disconnected or has no compatible driver. Reconnect and select it again.");
        if(e.contains("network_not_in_scan"))return t("Esta red no pertenece al escaneo actual. Busca de nuevo antes de continuar.","This network is not in the current survey. Scan again before continuing.");
        if(e.contains("wifi_task_in_progress"))return t("Hay otra tarea de Wi-Fi en curso. Deténla antes de iniciar otra.","Another Wi-Fi task is running. Stop it first.");return e;
    }
    private void result(){if(receipt!=null&&receipt.optString("tool").equals("radio.deauth")){deauthResult();return;}boolean nativeCapture=result!=null&&result.has("handshake_detected");boolean pair=result!=null&&result.optBoolean(nativeCapture?"handshake_detected":"pair_observed");String outcome=result==null?"":result.optString("outcome");boolean stopped=outcome.equals("cancelled");
        title("Handshake capture",pair?t("Intercambio EAPOL detectado","EAPOL exchange observed"):stopped?t("Captura detenida","Capture stopped"):t("Captura finalizada","Capture ended"));illustration(pair?RadioIllustration.CHECK:RadioIllustration.CAPTURE,175,false);a.space(a.content,20);
        String explanation=pair?t("Mismo cliente y contador. No se ha comprobado ninguna contraseña ni validado el MIC criptográficamente.","Same client and replay counter. No password or cryptographic MIC verification was performed."):stopped?t("La captura se detuvo antes de observar un intercambio completo. Las tramas recogidas se pueden conservar.","Capture stopped before a complete exchange was observed. Collected frames can be kept."):outcome.equals("size_limit")?t("Se alcanzó el límite de tamaño. Las tramas recogidas se pueden conservar o puedes iniciar otra captura.","The size limit was reached. Keep the collected frames or start another capture."):t("Puede no haber conexiones nuevas durante la captura. No significa que la antena no funcione.","There may have been no new connections during capture. This does not mean the adapter failed.");
        if(pair&&nativeCapture)explanation=t("Detectado por airodump-ng, con tramas EAPOL guardadas. No se ha comprobado ninguna contraseña ni validado el MIC criptográficamente.","Detected by airodump-ng, with saved EAPOL frames. No password or cryptographic MIC verification was performed.");
        JSONObject quick=result==null?null:result.optJSONObject("quick_crack");
        if(quick!=null)explanation=t("Captura guardada y Wi-Fi restaurado. Resultado de la comprobación por diccionario:","Capture saved and Wi-Fi restored. Dictionary check result:");
        center(pair?(nativeCapture?t("Handshake detectado","Handshake detected"):t("M1 + M2 coincidentes","Matching M1 + M2")):t("Sin handshake completo","No complete handshake"),23,true,a.foreground);a.space(a.content,12);center(explanation,14,false,a.secondary);a.space(a.content,24);
        if(result!=null){a.fact("EAPOL",result.optInt("eapol_frames")+t(" tramas observadas"," frames observed"));a.fact(t("Antena","Adapter"),result.optBoolean("restored")?t("Restaurada","Restored"):t("Revisar restauración","Check restoration"));String data=result.optString("pcap_base64");if(!data.isEmpty())primary(t("Guardar captura .pcap","Save .pcap capture"),()->{export=android.util.Base64.decode(data,android.util.Base64.DEFAULT);a.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/vnd.tcpdump.pcap").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"lambda-handshake-"+System.currentTimeMillis()+".pcap"),84);});}
        if(result!=null&&result.has("deauth"))a.fact("Deauth",deauthSummary(result.optJSONObject("deauth")));
        if(quick!=null){String quickOutcome=quick.optString("outcome");boolean found=quickOutcome.equals("found");
            a.fact(t("Comprobación rápida","Quick check"),found?t("Contraseña verificada","Password verified"):quickOutcome.equals("exhausted")?t("No encontrada entre las 4.800 claves.","Not found among the 4,800 candidates."):t("No completada: ","Not completed: ")+quick.optString("stop_reason",quickOutcome));
            if(found)center(quick.optString("password"),24,true,a.foreground);
            a.fact(t("Tiempo total","Total time"),String.format(Locale.getDefault(),"%.2f s",quick.optLong("wall_ms")/1000.0));
            if(found&&quick.optBoolean("cryptographic_validation"))a.verifiedWifi.attach(a.content,jobId);
        }
        if(pair&&(quick==null||!quick.optString("outcome").equals("found")))primary(t("Crackear contraseña","Crack password"),()->a.cracking.openCapture(jobId));
        secondary(t("Nueva captura","New capture"),()->{clearJob();go("choice");});
    }
    private String deauthSummary(JSONObject r){
        if(r==null)return "";
        if(r.optBoolean("transmission_reported"))return t("La herramienta informa de una ráfaga enviada. El efecto sobre los clientes no está verificado.","The tool reports sending a burst. Client impact is not verified.");
        if(r.optString("outcome").equals("sending"))return t("Enviando una ráfaga. Sin reintentos automáticos.","Sending one burst. No automatic retries.");
        return t("No se ha confirmado el envío de la ráfaga. No se repetirá automáticamente.","Burst transmission has not been confirmed. It will not be retried automatically.");
    }
    private void deauthResult(){title("Deauth",t("Prueba finalizada","Test finished"));illustration(RadioIllustration.SCAN,180,false);a.space(a.content,24);boolean sent=result!=null&&result.optBoolean("transmission_reported");a.fact(t("Envío","Transmission"),sent?t("La herramienta informa del envío de una ráfaga.","The tool reports sending one burst."):t("No se ha confirmado el envío.","Transmission was not confirmed."));a.fact(t("Efecto sobre la red","Network effect"),t("No verificado automáticamente. El envío no demuestra que se haya desconectado un cliente; PMF puede protegerlo.","Not automatically verified. Sending does not prove a client disconnected; PMF may protect it."));if(result!=null){a.fact(t("Antena","Adapter"),result.optBoolean("restored")?t("Restaurada","Restored"):t("Revisar restauración","Check restoration"));secondary(t("Ver salida de la herramienta","View tool output"),()->new AlertDialog.Builder(a).setTitle("aireplay-ng").setMessage(result.optString("tool_output")).setPositiveButton(t("Cerrar","Close"),null).show());}primary(t("Volver","Back"),()->{clearJob();go("choice");});}
    boolean activityResult(int request,int resultCode,Intent data){if(request!=84)return false;if(resultCode==android.app.Activity.RESULT_OK&&data!=null&&export!=null)try(OutputStream out=a.getContentResolver().openOutputStream(data.getData())){out.write(export);a.notice(t("Captura guardada.","Capture saved."));}catch(Exception e){a.notice(e.getMessage());}return true;}
}
