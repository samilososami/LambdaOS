package org.lambdaproject.lambda;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Authorized AP workflow: clone an owned/authorized network or build a
 *  custom one. Live client list, console log, kick/ban controls and receipts;
 *  leaving never owns or kills work. */
final class ApUi {
    private final MainActivity a;private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private boolean showing,resumed=true,dead,busy;
    private int generation;private long selectionExpires;
    private String screen="choice",jobId,jobTool,adapter="",session,error="",apSsid="",pendingAck="",snapshotId="";
    private String manualSecurity="wpa2";
    private JSONArray networks=new JSONArray();private JSONObject receipt,result,target;
    private LinearLayout devices,secRow,chRow,portalRow,stationList,consoleList,bannedList,credentialsList;private String deviceFingerprint="";
    private android.widget.EditText ssidField,passField,chField;
    private TextView status,detail,time,clients;
    private byte[] export;
    ApUi(MainActivity a){this.a=a;jobId=a.getPreferences(0).getString("ap_job",null);if(jobId!=null)screen="resume";}
    void save(Bundle state){state.putString("ap_screen",screen);state.putString("ap_job_id",jobId);state.putString("ap_error",error);}
    void restore(Bundle state){jobId=state.getString("ap_job_id",jobId);error=state.getString("ap_error","");String saved=state.getString("ap_screen","choice");screen=jobId!=null?"resume":Arrays.asList("choice","external","manual","error").contains(saved)?saved:"choice";}
    private String t(String es,String en){return a.tr(es,en);}
    private JSONObject o(){return new JSONObject();}private JSONObject p(String k,Object v){try{return o().put(k,v);}catch(Exception e){throw new IllegalStateException(e);}}
    private interface Reply{void done(JSONObject r)throws Exception;}
    private void rpc(String method,JSONObject args,Reply reply){int gen=generation;io.execute(()->{JSONObject r;try{Bundle b=a.getContentResolver().call(CoreProvider.URI,method,args.toString(),null);r=new JSONObject(b.getString("json"));}catch(Exception e){r=CoreEngine.error(e);}JSONObject response=r;main.post(()->{if(method.equals("submit"))busy=false;if(dead||gen!=generation)return;try{reply.done(response);}catch(Exception e){a.notice(e.getMessage());}});});}
    void beforeRender(){showing=false;generation++;main.removeCallbacksAndMessages(null);devices=null;stationList=consoleList=bannedList=null;status=detail=time=clients=null;deviceFingerprint="";}
    void pause(){resumed=false;main.removeCallbacksAndMessages(null);}
    void resume(){resumed=true;main.removeCallbacksAndMessages(null);if(showing)schedule();}
    void destroy(){dead=true;main.removeCallbacksAndMessages(null);io.shutdownNow();}
    private void schedule(){if(!showing||!resumed)return;if(screen.equals("external"))main.postDelayed(this::pollAdapters,1000);if(screen.equals("working")||screen.equals("ap")||screen.equals("resume"))main.postDelayed(this::pollJob,650);}
    private void go(String s){screen=s;a.navigate("ap");}
    boolean back(){if(!a.isAp())return false;
        if(screen.equals("choice")||screen.equals("working")||screen.equals("ap")){a.handshake.showExplore();a.navigate("category");return true;}
        if(screen.equals("networks")){release();screen="choice";a.render();return true;}
        if(screen.equals("result")||screen.equals("error")){clearJob();screen="choice";a.render();return true;}
        if(screen.equals("external")||screen.equals("manual")){screen="choice";a.render();return true;}
        return false;}
    private TextView chip(String text,boolean selected,Runnable action){TextView v=a.text(text,14,true,selected?a.background:a.foreground);v.setGravity(Gravity.CENTER);v.setSelected(selected);a.card(v,selected?a.foreground:a.surface,26);a.touch(v,text,action);return v;}
    private void primary(String text,Runnable click){TextView v=chip(text,true,click);v.setSelected(false);v.setTextSize(16);v.setMinHeight(a.dp(56));a.content.addView(v,new LinearLayout.LayoutParams(-1,a.dp(56)));a.space(a.content,12);}
    private void secondary(String text,Runnable click){TextView v=a.text(text,14,true,a.secondary);v.setGravity(Gravity.CENTER);a.content.addView(v,new LinearLayout.LayoutParams(-1,a.dp(48)));a.touch(v,text,click);}
    private TextView center(String s,int size,boolean bold,int color){TextView v=a.text(s,size,bold,color);v.setGravity(Gravity.CENTER);v.setLineSpacing(a.dp(3),1);a.content.addView(v,new LinearLayout.LayoutParams(-1,-2));return v;}
    private void label(String s){TextView v=a.text(s,12,true,a.secondary);a.content.addView(v);a.space(a.content,6);}
    private String clock(long ms){return new java.text.SimpleDateFormat("HH:mm:ss",Locale.US).format(new java.util.Date(ms));}
    private String secLabel(String s){return s.equals("open")?"OPEN":s.equals("wpa2")?"WPA2":s.equals("wpa3")?"WPA3":s.equals("mixed")?t("MIXTO","MIXED"):s;}
    private boolean portalOn(){return a.getPreferences(0).getBoolean("ap_portal",false);}
    private boolean ntlmOn(){return a.getPreferences(0).getBoolean("ap_ntlm",true);}
    private String portalTemplate(){return a.getPreferences(0).getString("ap_portal_template","google");}
    private void paintPortalTemplates(){if(portalRow==null)return;portalRow.removeAllViews();String[] vals={"google","router","simple"};String[] names={t("Google","Google"),t("Router","Router"),t("Simple","Simple")};for(int i=0;i<3;i++){final String v=vals[i];boolean sel=portalTemplate().equals(v);TextView c=a.text(names[i],13,true,sel?a.background:a.foreground);c.setGravity(Gravity.CENTER);a.card(c,sel?a.foreground:a.surface,20);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,a.dp(40),1);if(i>0)lp.leftMargin=a.dp(6);portalRow.addView(c,lp);a.touch(c,names[i],()->{a.getPreferences(0).edit().putString("ap_portal_template",v).apply();paintPortalTemplates();});}}
    private void row(String name,String sub,int icon,Runnable click){LinearLayout row=new LinearLayout(a);row.setPadding(a.dp(16),a.dp(18),a.dp(16),a.dp(18));row.setGravity(Gravity.CENTER_VERTICAL);a.card(row,a.surface,16);row.addView(new RadioIllustration(a,icon,a.foreground,false),new LinearLayout.LayoutParams(a.dp(44),a.dp(44)));LinearLayout words=a.column();words.setPadding(a.dp(14),0,a.dp(8),0);words.addView(a.text(name,17,true,a.foreground));if(!sub.isEmpty()){a.space(words,6);words.addView(a.text(sub,12,false,a.secondary));}row.addView(words,new LinearLayout.LayoutParams(0,-2,1));Glyph next=new Glyph(a,10,a.secondary);next.setRotation(180);row.addView(next,new LinearLayout.LayoutParams(a.dp(18),a.dp(24)));a.content.addView(row);a.space(a.content,10);a.touch(row,name,click);}
    /** Entry point from the Wi-Fi category's AP tab. Attaches to a radio.ap
     *  job that is already running, whoever submitted it (app, notification
     *  deep link or the ADB CLI), instead of offering only a new one. */
    /** Attach the notification deep link to its exact job, bypassing stale state. */
    void openLive(String id){jobId=id;jobTool="radio.ap";apSsid="";error="";screen="ap";showing=true;}
    void landing(){showing=true;
        if(screen.equals("ap")||screen.equals("resume")||screen.equals("working")){render();return;}
        rpc("list",o(),r->{JSONArray jobs=r.optJSONArray("jobs");if(jobs==null)jobs=new JSONArray();
            for(int i=0;i<jobs.length();i++){JSONObject j=jobs.optJSONObject(i);
                if(j!=null&&j.optString("tool").equals("radio.ap")&&Arrays.asList("queued","running").contains(j.optString("state"))){
                    jobId=j.optString("id");jobTool="radio.ap";apSsid="";
                    a.getPreferences(0).edit().putString("ap_job",jobId).apply();
                    if(showing&&Arrays.asList("choice","external","manual","networks").contains(screen)){screen="ap";a.render();}
                    return;}}});
        if(screen.equals("choice"))choice();
    }
    void render(){showing=true;switch(screen){case "choice":choice();break;case "external":external();break;case "manual":manual();break;case "networks":networks();break;case "working":case "resume":working();break;case "ap":running();break;case "result":result();break;default:failed();}schedule();}
    private void choice(){
        a.heading(t("Punto de acceso","Access point"),t("Clona una red propia o autorizada, o crea la tuya. DHCP/DNS locales, sniffing y upstream opcional.","Clone an owned or authorized network, or build your own. Local DHCP/DNS, sniffing and optional upstream."));
        a.content.addView(new RadioIllustration(a,RadioIllustration.SCAN,a.foreground,false),new LinearLayout.LayoutParams(-1,a.dp(100)));a.space(a.content,16);
        LinearLayout pair=new LinearLayout(a);String[] labels={t("Interna","Internal"),t("Externa","External")};
        for(int i=0;i<2;i++){final boolean internal=i==0;LinearLayout card=a.column();card.setGravity(Gravity.CENTER);card.setPadding(a.dp(8),a.dp(16),a.dp(8),a.dp(18));a.card(card,a.surface,18);card.addView(new RadioIllustration(a,internal?RadioIllustration.PHONE:RadioIllustration.ANTENNA,a.foreground,false),new LinearLayout.LayoutParams(-1,a.dp(90)));TextView name=a.text(labels[i],18,true,a.foreground);name.setGravity(Gravity.CENTER);card.addView(name);a.space(card,8);TextView sub=a.text(internal?"Samsung S10":"USB · OTG",12,false,a.secondary);sub.setGravity(Gravity.CENTER);card.addView(sub);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i==0)lp.rightMargin=a.dp(12);pair.addView(card,lp);a.touch(card,labels[i],()->{if(internal)confirmInternal();else go("external");});}
        a.content.addView(pair);a.space(a.content,14);
        primary(t("Crear AP personalizado","Create custom AP"),()->go("manual"));
        android.widget.Switch up=new android.widget.Switch(a);
        up.setText(t("Compartir Internet (upstream)","Share Internet (upstream)"));
        up.setTextColor(a.foreground);up.setTextSize(16);
        up.setChecked(a.getPreferences(0).getBoolean("ap_upstream",true));
        up.setPadding(0,a.dp(8),0,a.dp(8));
        up.setOnCheckedChangeListener((v,checked)->a.getPreferences(0).edit().putBoolean("ap_upstream",checked).apply());
        a.content.addView(up,new LinearLayout.LayoutParams(-1,-2));a.space(a.content,2);
        android.widget.Switch portalSwitch=new android.widget.Switch(a);
        portalSwitch.setText(t("Portal cautivo","Captive portal"));
        portalSwitch.setTextColor(a.foreground);portalSwitch.setTextSize(16);
        portalSwitch.setChecked(portalOn());
        portalSwitch.setPadding(0,a.dp(8),0,a.dp(8));
        portalSwitch.setOnCheckedChangeListener((v,checked)->{a.getPreferences(0).edit().putBoolean("ap_portal",checked).apply();paintPortalTemplates();});
        a.content.addView(portalSwitch,new LinearLayout.LayoutParams(-1,-2));a.space(a.content,4);
        label(t("Plantilla del portal","Portal template"));portalRow=new LinearLayout(a);paintPortalTemplates();a.content.addView(portalRow,new LinearLayout.LayoutParams(-1,a.dp(40)));a.space(a.content,8);
        android.widget.Switch ntlmSwitch=new android.widget.Switch(a);
        ntlmSwitch.setText(t("Capturar hashes NTLM (WPAD)","Capture NTLM hashes (WPAD)"));
        ntlmSwitch.setTextColor(a.foreground);ntlmSwitch.setTextSize(16);
        ntlmSwitch.setChecked(ntlmOn());
        ntlmSwitch.setPadding(0,a.dp(8),0,a.dp(8));
        ntlmSwitch.setOnCheckedChangeListener((v,checked)->a.getPreferences(0).edit().putBoolean("ap_ntlm",checked).apply());
        a.content.addView(ntlmSwitch,new LinearLayout.LayoutParams(-1,-2));a.space(a.content,6);
        center(t("La radio interna mantiene un enlace cliente para el upstream; el AP va en una interfaz virtual.","The internal radio keeps a client link for upstream; the AP runs on a virtual interface."),12,false,a.secondary);
    }
    private void confirmInternal(){new AlertDialog.Builder(a).setTitle(t("Usar la antena interna","Use internal antenna")).setMessage(t("El escaneo usa el escáner nativo de Android: rápido y sin cortar tu Wi-Fi. Después se levanta el AP clonado. Se restaura al detener.","The survey uses Android's native scanner: fast, your Wi-Fi stays connected. The clone AP starts afterwards. Restored when stopped."))
        .setNegativeButton(t("Cancelar","Cancel"),null).setPositiveButton(t("Continuar","Continue"),(d,w)->startScan("internal")).show();}
    private android.widget.EditText newField(String hint){
        android.widget.EditText e=new android.widget.EditText(a);
        e.setTextColor(a.foreground);e.setTextSize(16);e.setHintTextColor(a.secondary);e.setHint(hint);
        e.setPadding(a.dp(14),a.dp(14),a.dp(14),a.dp(14));a.card(e,a.surface,10);
        a.content.addView(e,new LinearLayout.LayoutParams(-1,-2));a.space(a.content,10);return e;
    }
    private void paintSecurity(){
        secRow.removeAllViews();
        String[] vals={"wpa2","wpa3","mixed"};String[] names={"WPA2","WPA3",t("MIXTO","MIXED")};
        for(int i=0;i<3;i++){final String value=vals[i];boolean sel=manualSecurity.equals(value);
            TextView c=a.text(names[i],13,true,sel?a.background:a.foreground);c.setGravity(Gravity.CENTER);a.card(c,sel?a.foreground:a.surface,20);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,a.dp(42),1);if(i>0)lp.leftMargin=a.dp(6);secRow.addView(c,lp);
            a.touch(c,names[i],()->{manualSecurity=value;paintSecurity();});}
    }
    private void manual(){
        a.heading(t("AP personalizado","Custom AP"),t("Sin escanear. Contraseña vacía = red abierta; canal vacío = aleatorio.","No survey. Empty password = open network; empty channel = random."));
        label("SSID");ssidField=newField(t("Nombre de la red","Network name"));
        label(t("Contraseña","Password"));passField=newField(t("Vacía = red abierta","Empty = open network"));
        label(t("Seguridad (solo con contraseña)","Security (password only)"));secRow=new LinearLayout(a);paintSecurity();a.content.addView(secRow,new LinearLayout.LayoutParams(-1,a.dp(42)));a.space(a.content,12);
        label(t("Canal","Channel"));chField=newField(t("Vacío = aleatorio (1-13)","Empty = random (1-13)"));
        a.space(a.content,6);
        primary(t("Lanzar AP","Launch AP"),this::launchManual);
        secondary(t("Volver","Back"),()->{screen="choice";a.render();});
    }
    private void launchManual(){
        String ssid=ssidField.getText().toString().trim();
        String pass=passField.getText().toString();
        String channel=chField.getText().toString().trim();
        if(ssid.isEmpty()||ssid.getBytes().length>32){a.notice(t("El SSID debe tener entre 1 y 32 caracteres","SSID must be 1-32 characters"));return;}
        Integer ch=null;
        if(!channel.isEmpty()){
            if(!channel.matches("[0-9]{1,2}")){a.notice(t("El canal debe ser un número del 1 al 13","Channel must be 1-13"));return;}
            ch=Integer.valueOf(channel);
            if(ch<1||ch>13){a.notice(t("El canal debe ser del 1 al 13","Channel must be 1-13"));return;}
        }
        String security=pass.isEmpty()?"open":manualSecurity;
        if(!pass.isEmpty()&&pass.length()<8){a.notice(t("La contraseña necesita al menos 8 caracteres","Password needs at least 8 characters"));return;}
        final Integer chosen=ch;
        boolean share=a.getPreferences(0).getBoolean("ap_upstream",true);
        String summary=secLabel(security)+(security.equals("open")?t(" sin contraseña"," without password"):t(" con la contraseña indicada"," with the given password"))
            +t(" · canal "," · channel ")+(ch==null?t("aleatorio","random"):Integer.toString(ch))            +t(" · Internet: "," · Internet: ")+(share?t("sí","yes"):t("no","no"))+t(".\nCorre hasta que lo detengas.",".\nRuns until you stop it.");
        new AlertDialog.Builder(a).setTitle(ssid).setMessage(summary+t("\n\n¿Lanzar el AP?","\n\nLaunch the AP?"))
            .setNegativeButton(t("Cancelar","Cancel"),null)
            .setPositiveButton(t("Lanzar","Launch"),(d,w)->{
                apSsid=ssid;
                try{JSONObject args=p("mode","manual").put("ssid",ssid).put("security",security)
                    .put("authorized",true).put("sniff",true).put("portal",portalOn()).put("portal_template",portalTemplate()).put("upstream",share).put("ntlm",ntlmOn());
                    if(chosen!=null)args.put("channel",chosen);
                    if(!pass.isEmpty())args.put("passphrase",pass);
                    start("radio.ap",args,"ap");}catch(Exception e){fail(e.getMessage());}
            }).show();
    }
    private void external(){a.heading(t("Antena externa","External antenna"),t("Conecta tu adaptador USB.","Connect your USB adapter."));a.content.addView(new RadioIllustration(a,RadioIllustration.PLUG,a.foreground,true),new LinearLayout.LayoutParams(-1,a.dp(200)));a.space(a.content,16);status=center(t("Esperando adaptador…","Waiting for adapter…"),21,true,a.foreground);a.space(a.content,10);detail=center(t("Se detectará automáticamente.","It will be detected automatically."),14,false,a.secondary);a.space(a.content,24);devices=a.column();a.content.addView(devices);}
    private void pollAdapters(){if(!showing||!resumed||!screen.equals("external"))return;rpc("radio_status",o(),r->{JSONArray items=r.optJSONArray("adapters");boolean ready=r.optBoolean("available");if(items==null)items=new JSONArray();String key=ready+items.toString();if(!key.equals(deviceFingerprint)){deviceFingerprint=key;devices.removeAllViews();int found=0;for(int i=0;i<items.length();i++){JSONObject d=items.getJSONObject(i);if(!d.optString("kind").equals("external"))continue;found++;TextView v=a.text(d.optString("name")+"\n"+d.optString("chipset")+" · "+d.optString("vid_pid")+(d.optBoolean("supported")?"":t("\nControlador no compatible","\nUnsupported driver")),15,true,a.foreground);v.setLineSpacing(a.dp(6),1);v.setPadding(a.dp(18),a.dp(18),a.dp(18),a.dp(18));a.card(v,a.surface,16);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=a.dp(10);devices.addView(v,lp);if(ready&&d.optBoolean("supported"))a.touch(v,d.optString("name"),()->startScan(d.optString("id")));else v.setAlpha(.55f);}
                if(!ready){status.setText(t("Servicio de captura no disponible","Capture service unavailable"));detail.setText(t("La versión de pruebas necesita el servicio root de Lambda activo. No se ha activado ninguna antena.","This test version requires Lambda's root service. No adapter was activated."));}
                else{status.setText(found>0?t("Elige tu antena","Choose your adapter"):t("Esperando adaptador…","Waiting for adapter…"));detail.setText(found>0?t("Controlador e interfaz detectados.","Driver and interface detected."):t("Se detectará automáticamente.","It will be detected automatically."));}
            }if(showing&&screen.equals("external"))main.postDelayed(this::pollAdapters,1300);});}
    private void startScan(String id){adapter=id;if(id.equals("internal"))start("radio.wifi_on",o(),"working");else start("radio.scan",p("adapter",id),"working");}
    private void start(String tool,JSONObject args,String next){if(busy)return;busy=true;try{rpc("submit",p("tool",tool).put("args",args).put("request_id",UUID.randomUUID().toString()),r->{busy=false;if(!r.optBoolean("ok")){fail(r.optString("detail"));return;}jobId=r.getJSONObject("job").getString("id");jobTool=tool;a.getPreferences(0).edit().putString("ap_job",jobId).apply();go(next);});}catch(Exception e){busy=false;fail(e.getMessage());}}
    private void working(){boolean resume=screen.equals("resume");a.heading(t("Punto de acceso","Access point"),resume?t("Recuperando la sesión","Restoring session"):t("Escaneando la red objetivo","Surveying the target network"));a.content.addView(new RadioIllustration(a,RadioIllustration.SCAN,a.foreground,true),new LinearLayout.LayoutParams(-1,a.dp(190)));a.space(a.content,16);status=center(resume?t("Consultando el estado…","Checking status…"):t("Escaneando con el escáner nativo…","Scanning with the native scanner…"),21,true,a.foreground);a.space(a.content,10);detail=center("",14,false,a.secondary);a.space(a.content,22);primary(t("Detener","Stop"),this::stop);secondary(t("Puedes salir; la tarea continúa en Sesiones.","You can leave; the task continues in Sessions."),()->a.navigate("sessions"));}
    private void networks(){a.heading(t("Elige la red a clonar","Choose the network to clone"),t("Solo una red propia o con autorización explícita.","Only an owned network or one you are explicitly authorized to test."));
        if(selectionExpires>0&&selectionExpires<Long.MAX_VALUE&&SystemClock.elapsedRealtime()>=selectionExpires){center(t("El escaneo ha caducado","Survey expired"),21,true,a.foreground);a.space(a.content,12);center(t("La antena se libera automáticamente tras dos minutos. Escanea de nuevo.","The adapter is released after two minutes. Scan again."),14,false,a.secondary);a.space(a.content,24);primary(t("Buscar de nuevo","Scan again"),()->startScan(adapter));return;}
        center(selectionExpires==Long.MAX_VALUE?t("Escáner nativo · 2,4 GHz · Resultado del escaneo de Android","Native scanner · 2.4 GHz · Android scan results"):t("Modo monitor · 2,4 GHz · Selección disponible 2 min","Monitor mode · 2.4 GHz · Select within 2 min"),12,false,a.secondary);a.space(a.content,16);
        for(int i=0;i<networks.length();i++){JSONObject n=networks.optJSONObject(i);if(n==null)continue;String name=n.optString("ssid");if(name.isEmpty())name=t("SSID oculto","Hidden SSID");int signal=n.optInt("rssi_dbm",-127);String sub=n.optString("security")+" · ch "+n.optInt("channel")+" · "+(signal<0&&signal>-127?signal+" dBm":t("Señal n/d","Signal n/a"))+"\n"+n.optString("bssid");final String label=name;
            row(name,sub,RadioIllustration.SCAN,()->{new AlertDialog.Builder(a).setTitle(label).setMessage(t("Se creará una red abierta con el mismo SSID y canal, con DHCP y DNS locales. El tráfico podrá capturarse mientras el AP esté activo.\nInternet: ","An open network with the same SSID and channel will be created, with local DHCP and DNS. Traffic can be captured while the AP is active.\nInternet: ")+(a.getPreferences(0).getBoolean("ap_upstream",true)?t("sí","yes"):t("no","no"))+t(".\n\n¿Tienes autorización para suplantar esta red?\n\n",".\n\nAre you authorized to impersonate this network?\n\n")+n.optString("bssid")+t("\nCorre hasta que lo detengas.","\nRuns until you stop it."))
                .setNegativeButton(t("Cancelar","Cancel"),null).setPositiveButton(t("Autorizar AP","Authorize AP"),(d,w)->{target=n;apSsid=n.optString("ssid");try{start("radio.ap",p("snapshot_id",snapshotId).put("bssid",n.optString("bssid")).put("authorized",true).put("sniff",true).put("portal",portalOn()).put("portal_template",portalTemplate()).put("upstream",a.getPreferences(0).getBoolean("ap_upstream",true)).put("ntlm",ntlmOn()),"ap");}catch(Exception e){fail(e.getMessage());}}).show();});
        }
        if(networks.length()==0)center(t("No se observaron redes.","No networks observed."),17,false,a.secondary);
        a.space(a.content,16);primary(t("Buscar de nuevo","Scan again"),()->startScan(adapter));if(selectionExpires>0&&selectionExpires<Long.MAX_VALUE)secondary(t("Terminar y restaurar Wi-Fi","Finish and restore Wi-Fi"),()->{release();clearJob();screen="choice";a.render();});}
    private void running(){
        a.heading(t("AP activo","AP running"),apSsid.isEmpty()?t("Punto de acceso","Access point"):apSsid);
        a.content.addView(new RadioIllustration(a,RadioIllustration.SCAN,a.foreground,true),new LinearLayout.LayoutParams(-1,a.dp(110)));a.space(a.content,10);
        status=center(t("Preparando AP…","Preparing AP…"),19,true,a.foreground);a.space(a.content,6);
        detail=center("",12,false,a.secondary);a.space(a.content,12);
        LinearLayout metrics=new LinearLayout(a);time=a.text("00:00",24,true,a.foreground);clients=a.text("0",24,true,a.foreground);
        for(TextView v:new TextView[]{time,clients}){v.setGravity(Gravity.CENTER);metrics.addView(v,new LinearLayout.LayoutParams(0,a.dp(40),1));}
        a.content.addView(metrics);a.space(a.content,4);center(t("tiempo · clientes","time · clients"),10,false,a.secondary);a.space(a.content,14);
        label(t("Clientes conectados (mantén pulsado para opciones)","Connected clients (long-press for options)"));stationList=a.column();a.content.addView(stationList);a.space(a.content,8);
        label(t("Registro","Console log"));consoleList=a.column();a.content.addView(consoleList);a.space(a.content,8);
        label(t("Baneados","Banned"));bannedList=a.column();a.content.addView(bannedList);a.space(a.content,8);
        label(t("Credenciales y hashes","Credentials and hashes"));credentialsList=a.column();a.content.addView(credentialsList);a.space(a.content,12);
        primary(t("Detener y restaurar","Stop and restore"),this::stop);
    }
    private void stationRow(LinearLayout parent,JSONObject s){
        String mac=s.optString("mac");String name=s.optString("name","");String ip=s.optString("ip","");
        String title=name.isEmpty()?mac:name;
        TextView v=a.text(title+"\n"+(ip.isEmpty()?t("sin IP aún","no IP yet"):ip)+" · "+clock(s.optLong("since")),14,true,a.foreground);
        v.setLineSpacing(a.dp(4),1);v.setPadding(a.dp(14),a.dp(12),a.dp(14),a.dp(12));a.card(v,a.surface,12);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=a.dp(8);parent.addView(v,lp);
        v.setOnLongClickListener(view->{clientActions(title,mac);return true;});
    }
    private void clientActions(String name,String mac){
        new AlertDialog.Builder(a).setTitle(name)
            .setItems(new CharSequence[]{t("Expulsar de la red","Kick from the network"),t("Expulsar y banear","Kick and ban")},(d,which)->sendControl(which==0?"kick":"ban",mac))
            .setNegativeButton(t("Cancelar","Cancel"),null).show();
    }
    private void sendControl(String action,String mac){
        if(jobId==null)return;
        if(!pendingAck.isEmpty()){a.notice(t("Espera a que termine la acción anterior","Wait for the previous action"));return;}
        try{String request=UUID.randomUUID().toString();pendingAck=request;
            rpc("ap_control",p("id",jobId).put("request_id",request).put("action",action).put("mac",mac).put("authorized",true),r->{
                if(!r.optBoolean("ok")){pendingAck="";a.notice(friendly(r.optString("detail")));}
            });}catch(Exception e){pendingAck="";a.notice(e.getMessage());}
    }
    private void pollJob(){if(!showing||!resumed||jobId==null)return;rpc("get",p("id",jobId),r->{if(!r.optBoolean("ok")){fail(r.optString("detail"));return;}JSONObject j=r.getJSONObject("job");receipt=j;String state=j.optString("state");
        if(state.equals("queued")||state.equals("running")){JSONObject progress=j.optJSONObject("progress");if(progress!=null){String phase=progress.optString("phase");
            if(phase.equals("ap")&&!screen.equals("ap")){go("ap");return;}
            if(phase.equals("ap")){
                if(time!=null)time.setText(String.format(Locale.US,"%02d:%02d",progress.optLong("seconds")/60,progress.optLong("seconds")%60));
                if(clients!=null)clients.setText(Integer.toString(progress.optInt("clients")));
                if(status!=null)status.setText(progress.optInt("clients")>0?t("Cliente conectado","Client connected"):t("Esperando clientes…","Waiting for clients…"));
                if(detail!=null)detail.setText("ch "+progress.optInt("channel")+" · "+secLabel(progress.optString("security"))+" · "+(progress.optBoolean("upstream_active")?"upstream "+progress.optString("upstream_iface"):t("sin upstream","no upstream"))+" · "+progress.optInt("packets")+" pkt · SoC "+(progress.optInt("temp")/1000)+"°C");
                if(stationList!=null){stationList.removeAllViews();JSONArray st=progress.optJSONArray("stations");if(st==null)st=new JSONArray();
                    for(int i=0;i<st.length();i++)stationRow(stationList,st.getJSONObject(i));
                    if(st.length()==0){TextView e=a.text(t("Nadie conectado aún","Nobody connected yet"),13,false,a.secondary);e.setPadding(a.dp(4),a.dp(6),a.dp(4),a.dp(6));stationList.addView(e);}}
                if(consoleList!=null){consoleList.removeAllViews();JSONArray ev=progress.optJSONArray("events");if(ev==null)ev=new JSONArray();
                    for(int i=Math.max(0,ev.length()-6);i<ev.length();i++){JSONObject e=ev.getJSONObject(i);
                        TextView v=a.text(clock(e.optLong("t"))+"  "+e.optString("text"),12,false,a.secondary);
                        v.setPadding(a.dp(4),a.dp(3),a.dp(4),a.dp(3));consoleList.addView(v);}
                    if(ev.length()==0)consoleList.addView(a.text(t("Sin eventos aún","No events yet"),12,false,a.secondary));}
                if(bannedList!=null){bannedList.removeAllViews();JSONArray ban=progress.optJSONArray("banned");if(ban==null)ban=new JSONArray();
                    for(int i=0;i<ban.length();i++){final String mac=ban.getString(i);
                        TextView v=a.text(mac+"   "+t("desbanear","unban"),13,true,a.foreground);a.card(v,a.surface,8);v.setPadding(a.dp(12),a.dp(10),a.dp(12),a.dp(10));
                        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=a.dp(6);bannedList.addView(v,lp);
                        a.touch(v,mac,()->sendControl("unban",mac));}
                    if(ban.length()==0)bannedList.addView(a.text(t("Ninguno","None"),12,false,a.secondary));}
                if(credentialsList!=null){credentialsList.removeAllViews();JSONArray cr=progress.optJSONArray("credentials");if(cr==null)cr=new JSONArray();
                    for(int i=Math.max(0,cr.length()-6);i<cr.length();i++){JSONObject c=cr.getJSONObject(i);
                        TextView v=a.text(clock(c.optLong("t"))+"  "+c.optString("ip")+"\n"+c.optString("data"),12,true,a.foreground);v.setLineSpacing(a.dp(3),1);v.setPadding(a.dp(10),a.dp(8),a.dp(10),a.dp(8));a.card(v,a.surface,8);
                        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=a.dp(6);credentialsList.addView(v,lp);}
                    if(cr.length()==0)credentialsList.addView(a.text(t("Sin credenciales aún","No credentials yet"),12,false,a.secondary));}
                String ack=progress.optString("ap_control_ack","");
                if(!ack.isEmpty()&&ack.equals(pendingAck)){pendingAck="";a.notice(t("Hecho","Done"));}
            }else if(status!=null){status.setText(phase.equals("scanning")?t("Buscando redes…","Finding networks…"):phase.equals("activating")?t("Preparando antena…","Preparing adapter…"):t("Preparando AP…","Preparing AP…"));if(detail!=null)detail.setText(phase.equals("scanning")?t("Canal ","Channel ")+progress.optInt("channel")+" · 2,4 GHz":t("Comprobando el estado real del adaptador.","Checking actual adapter state."));}
        }if(showing)main.postDelayed(this::pollJob,850);return;}
        result=j.optJSONObject("result");if(result==null){fail(j.optString("error",state));return;}
        String tool=j.optString("tool");
        if(tool.equals("radio.wifi_on")){start("wifi.scan",o(),"working");return;}
        if(tool.equals("radio.scan")||tool.equals("wifi.scan")||tool.equals("wifi.cached")){
            JSONArray all=result.optJSONArray("networks");if(all==null)all=new JSONArray();
            networks=new JSONArray();
            for(int i=0;i<all.length();i++){JSONObject n=all.optJSONObject(i);if(n==null)continue;
                // The AP is 2.4 GHz only; hide 5 GHz entries from the picker.
                int ch=n.optInt("channel",0);if(tool.equals("radio.scan")||(ch>=1&&ch<=13))networks.put(n);}
            if(tool.equals("radio.scan")){session=result.optString("session");selectionExpires=result.optLong("expires_elapsed_ms");}
            else{snapshotId=j.optString("id");selectionExpires=Long.MAX_VALUE;}
            adapter=tool.equals("radio.scan")?j.optJSONObject("args").optString("adapter","internal"):"internal";
            go("networks");}else go("result");
    });}
    private void stop(){if(jobId!=null)rpc("cancel",p("id",jobId),r->{if(status!=null)status.setText(t("Restaurando Wi-Fi…","Restoring Wi-Fi…"));});}
    private void release(){rpc("submit",p("tool","radio.release"),r->{});}
    private void clearJob(){jobId=null;jobTool=null;a.getPreferences(0).edit().remove("ap_job").apply();}
    private void fail(String s){error=s;clearJob();go("error");}
    private void failed(){boolean stopped=error.equals("cancelled");a.heading(stopped?t("Tarea detenida","Task stopped"):t("No se pudo continuar","Unable to continue"),stopped?t("Puedes iniciar otra cuando quieras.","You can start another when ready."):t("Un fallo nunca se muestra como AP activo.","A failure is never shown as an active AP."));a.space(a.content,20);a.fact(t("Detalle","Details"),friendly(error));primary(t("Volver a elegir antena","Choose adapter again"),()->{screen="choice";a.render();});}
    private String friendly(String e){
        if(e.contains("scan_session_expired"))return t("La selección ha caducado y se ha restaurado el Wi-Fi. Vuelve a escanear.","Selection expired and Wi-Fi was restored. Scan again.");
        if(e.contains("network_not_in_scan"))return t("Esta red no pertenece al escaneo actual. Busca de nuevo.","This network is not in the current survey. Scan again.");
        if(e.contains("ap_start_failed"))return t("Hostapd no pudo levantar el AP. Comprueba que el controlador soporta modo AP.","Hostapd could not start the AP. Check that the driver supports AP mode.");
        if(e.contains("ap_dhcp_failed"))return t("El servidor DHCP/DNS no arrancó. No hay AP utilizable.","The DHCP/DNS server did not start. No usable AP.");
        if(e.contains("ap_process_exited"))return t("Hostapd terminó inesperadamente y el AP se ha detenido.","Hostapd exited unexpectedly and the AP stopped.");
        if(e.contains("invalid_ssid"))return t("El SSID debe tener entre 1 y 32 caracteres.","The SSID must be 1-32 characters.");
        if(e.contains("target_not_2ghz"))return t("La red objetivo no está en 2,4 GHz.","The target network is not on 2.4 GHz.");
        if(e.contains("invalid_snapshot")||e.contains("network_not_in_snapshot"))return t("La red no está en el escaneo actual. Escanea de nuevo.","The network is not in the current scan. Scan again.");
        if(e.contains("invalid_security"))return t("Tipo de seguridad no soportado.","Unsupported security type.");
        if(e.contains("passphrase"))return t("La contraseña debe tener entre 8 y 63 caracteres imprimibles.","The password must have 8-63 printable characters.");
        if(e.contains("open_network_has_no_passphrase"))return t("Una red abierta no lleva contraseña. Déjala vacía.","An open network has no password. Leave it empty.");
        if(e.contains("wifi_task_in_progress"))return t("Hay otra tarea de Wi-Fi en curso. Deténla antes de iniciar otra.","Another Wi-Fi task is running. Stop it first.");
        if(e.contains("portal_start_failed"))return t("El portal cautivo no pudo abrir el puerto 80.","The captive portal could not bind port 80.");
        if(e.contains("invalid_portal_template"))return t("Plantilla de portal no válida.","Invalid portal template.");
        if(e.contains("ap_not_active"))return t("El AP no está activo ahora mismo.","The AP is not active right now.");
        if(e.contains("ap_not_ready"))return t("Espera a que el AP termine de arrancar.","Wait for the AP to finish starting.");
        if(e.contains("ap_control_busy"))return t("Hay una acción en curso; espera un momento.","An action is in flight; wait a moment.");
        if(e.contains("adapter_disconnected")||e.contains("adapter_missing"))return t("La antena ya no está conectada o no tiene controlador compatible.","The adapter is disconnected or has no compatible driver.");
        if(e.contains("radio_worker_offline"))return t("El servicio root no está activo. En desarrollo se inicia por ADB y no sobrevive a un reinicio.","Root service is offline. In development it starts via ADB and does not survive a reboot.");
        if(e.contains("restore_failed"))return t("No se ha confirmado la restauración de la antena. Recupera el servicio antes de continuar.","Adapter restoration was not confirmed. Recover the service before continuing.");
        if(e.contains("cancelled"))return t("Tarea detenida. Se ha solicitado restaurar el Wi-Fi.","Task stopped. Wi-Fi restoration was requested.");
        return e;
    }
    private void result(){boolean ok=result!=null&&!result.optString("outcome").startsWith("restore_failed");a.heading(t("AP finalizado","AP finished"),ok?t("El AP se ha detenido y el Wi-Fi se ha restaurado.","The AP stopped and Wi-Fi was restored."):t("Revisar restauración","Check restoration"));a.content.addView(new RadioIllustration(a,RadioIllustration.CHECK,a.foreground,false),new LinearLayout.LayoutParams(-1,a.dp(150)));a.space(a.content,18);
        if(result!=null){a.fact(t("SSID","SSID"),result.optString("ssid"));a.fact(t("Modo","Mode"),result.optString("mode").equals("manual")?t("Personalizado","Custom"):t("Clonado","Cloned"));a.fact("BSSID",result.optString("bssid"));a.fact(t("Seguridad","Security"),secLabel(result.optString("security","open")));a.fact(t("Canal","Channel"),Integer.toString(result.optInt("channel")));a.fact(t("Antena","Adapter"),result.optString("adapter"));a.fact(t("Upstream","Upstream"),result.optBoolean("upstream_active")?result.optString("upstream_iface"):result.optBoolean("upstream")?t("Solicitado, sin ruta","Requested, no route"):t("Desactivado","Off"));
        a.fact("IPv6",result.optBoolean("ipv6")?"fd50::1/64 · SLAAC":t("No disponible","Unavailable"));a.fact(t("Clientes","Clients"),Integer.toString(result.optInt("clients")));if(result.optBoolean("portal")||result.optBoolean("ntlm")){JSONArray cr=result.optJSONArray("credentials");a.fact(t("Credenciales y hashes","Credentials and hashes"),Integer.toString(cr==null?0:cr.length()));}
        JSONArray ev=result.optJSONArray("events");if(ev!=null&&ev.length()>0){JSONObject last=ev.optJSONObject(ev.length()-1);a.space(a.content,4);a.fact(t("Último evento","Last event"),last==null?"-":last.optString("text"));}
        if(result.optBoolean("sniff"))a.fact(t("Tráfico","Traffic"),result.optInt("packets")+" "+t("paquetes","packs")+" · "+result.optLong("pcap_bytes")+" B");a.fact(t("Salida","Outcome"),result.optString("outcome"));
            String data=result.optString("pcap_base64");if(!data.isEmpty())primary(t("Guardar captura .pcap","Save .pcap capture"),()->{export=android.util.Base64.decode(data,android.util.Base64.DEFAULT);a.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/vnd.tcpdump.pcap").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"lambda-ap-"+System.currentTimeMillis()+".pcap"),85);});}
        secondary(t("Nuevo AP","New AP"),()->{clearJob();screen="choice";a.render();});}
    boolean activityResult(int request,int resultCode,Intent data){if(request!=85)return false;if(resultCode==android.app.Activity.RESULT_OK&&data!=null&&export!=null)try(OutputStream out=a.getContentResolver().openOutputStream(data.getData())){out.write(export);a.notice(t("Captura guardada.","Capture saved."));}catch(Exception e){a.notice(e.getMessage());}return true;}
}
