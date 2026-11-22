package org.lambdaproject.lambda;

import android.app.AlertDialog;
import android.os.*;
import android.text.InputType;
import android.text.InputFilter;
import android.view.Gravity;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Native WPS view. Only Core owns jobs; observations, not typed SSIDs, select targets. */
final class WpsUi {
    final MainActivity a;
    final Handler main=new Handler(Looper.getMainLooper());
    final ExecutorService io=Executors.newSingleThreadExecutor();
    boolean visible,resumed=true,dead,pending;
    int generation;
    String screen="intro",id,scanId,error="",method="pixie",prefixValue="0000",renderedMode="";
    JSONObject target,receipt;
    JSONArray networks=new JSONArray();
    TextView phase,elapsed,readiness,detail,stats,history;
    WpsSteps steps;
    EditText pin,prefix;
    int budget=10;
    final Runnable poll=this::refresh;
    WpsUi(MainActivity a){this.a=a;}
    String t(String es,String en){return a.tr(es,en);}
    JSONObject p(String k,Object v){try{return new JSONObject().put(k,v);}catch(Exception e){throw new IllegalStateException(e);}}
    interface Reply{void done(JSONObject r)throws Exception;}
    void call(String name,JSONObject arg,Reply reply){
        int epoch=generation;
        io.execute(()->{
            JSONObject r;
            try{Bundle b=a.getContentResolver().call(CoreProvider.URI,name,arg.toString(),null);r=new JSONObject(b.getString("json"));}
            catch(Exception e){r=CoreEngine.error(e);}
            JSONObject response=r;
            main.post(()->{
                // Preserve the receipt even when navigation races a submit response.
                if(name.equals("submit")){
                    pending=false;
                    JSONObject j=response.optJSONObject("job");
                    if(j!=null)a.getPreferences(0).edit().putString("wps_last_job",j.optString("id")).apply();
                }
                if(dead||epoch!=generation)return;
                try{reply.done(response);}catch(Exception e){fail(e.getMessage());}
            });
        });
    }
    void beforeRender(){visible=false;generation++;main.removeCallbacks(poll);pin=null;if(prefix!=null)prefixValue=prefix.getText().toString();prefix=null;}
    void pause(){resumed=false;main.removeCallbacks(poll);}
    void resume(){resumed=true;if(visible&&screen.equals("working"))refresh();}
    void destroy(){dead=true;main.removeCallbacksAndMessages(null);io.shutdownNow();}
    void go(String next){screen=next;a.navigate("wps");}
    boolean back(){if(!a.isWps())return false;if(screen.equals("method"))go("networks");else a.navigate("category");return true;}
    void landing(){
        a.heading("OneShot",t("Auditoría WPS","WPS assessment"));
        illustration(false,130);
        a.space(a.content,18);
        a.content.addView(a.text(t("Pixie Dust, PIN, botón WPS o fuerza bruta por lotes. Sigue cada fase del intercambio.","Pixie Dust, PIN, WPS button or bounded brute-force batches. Follow every exchange stage."),16,false,a.secondary));
        a.space(a.content,24);
        button(t("Abrir OneShot","Open OneShot"),true,this::open);
    }
    void open(){
        id=a.getPreferences(0).getString("wps_last_job",null);
        if(id==null){go("intro");return;}
        call("get",p("id",id),r->{JSONObject j=r.optJSONObject("job");if(j!=null&&active(j)){receipt=j;go("working");}else{id=null;receipt=null;go("intro");}});
    }
    void openJob(String job){id=job;receipt=null;go("working");}
    boolean active(JSONObject j){return Arrays.asList("queued","running").contains(j.optString("state"));}
    TextView button(String label,boolean primary,Runnable action){
        TextView b=a.text(label,16,true,primary?a.background:a.foreground);
        b.setGravity(Gravity.CENTER);b.setMinHeight(a.dp(52));b.setPadding(a.dp(16),a.dp(16),a.dp(16),a.dp(16));
        a.card(b,primary?a.foreground:a.surface,18);a.touch(b,label,action);
        a.content.addView(b,new LinearLayout.LayoutParams(-1,-2));a.space(a.content,10);return b;
    }
    void illustration(boolean animate,int h){a.content.addView(new RadioIllustration(a,RadioIllustration.SCAN,a.foreground,animate),new LinearLayout.LayoutParams(-1,a.dp(h)));}
    TextView center(String label,int size){TextView v=a.text(label,size,true,a.foreground);v.setGravity(Gravity.CENTER);a.content.addView(v,new LinearLayout.LayoutParams(-1,-2));return v;}
    void render(){
        visible=true;
        if(screen.equals("intro"))intro();
        else if(screen.equals("networks"))networks();
        else if(screen.equals("method"))methods();
        else if(screen.equals("working"))working();
        else if(screen.equals("result"))result();
        else {a.heading(t("No se ha completado","Not completed"),friendly(error));button(t("Volver a OneShot","Back to OneShot"),true,()->go("intro"));}
    }
    void intro(){
        a.heading("OneShot",t("WPS · antena interna del S10","WPS · S10 internal antenna"));illustration(false,160);a.space(a.content,20);
        readiness=a.text(t("Comprobando motor…","Checking engine…"),14,true,a.secondary);a.content.addView(readiness);a.space(a.content,18);
        TextView scan=button(t("Buscar redes WPS","Find WPS networks"),true,()->submit("wifi.scan",new JSONObject()));scan.setEnabled(false);scan.setAlpha(.45f);
        a.content.addView(a.text(t("El escaneo no inicia una prueba WPS. Antes de empezar elegirás la red y confirmarás la operación. Durante la prueba se pausará la conexión Wi-Fi del teléfono.","Scanning does not start a WPS test. You will select a network and confirm the operation first. The phone's Wi-Fi connection pauses during a test."),14,false,a.secondary));
        a.space(a.content,22);a.fact(t("Motor","Engine"),"OneShot-Extended · GPL-2.0-or-later");
        call("radio_status",new JSONObject(),r->{JSONObject w=r.optJSONObject("wps");boolean ready=r.optBoolean("available")&&w!=null&&w.optBoolean("provisioned");readiness.setText(ready?t("Motor instalado · prueba de radio pendiente","Engine installed · radio verification pending"):t("Servicio WPS no disponible","WPS service unavailable"));scan.setEnabled(ready);scan.setAlpha(ready?1f:.45f);});
    }
    void networks(){
        a.heading(t("Redes con WPS","WPS networks"),t("Elige una red que tengas autorización para comprobar.","Choose a network you are authorized to assess."));
        int count=0;
        for(int i=0;i<networks.length();i++){
            JSONObject n=networks.optJSONObject(i);if(n==null||!n.optString("capabilities").contains("[WPS]"))continue;count++;
            String ssid=n.optString("ssid");if(ssid.isEmpty())ssid=t("Red oculta","Hidden network");
            LinearLayout row=a.column();row.setPadding(a.dp(18),a.dp(18),a.dp(18),a.dp(18));a.card(row,a.surface,18);
            row.addView(a.text(ssid,18,true,a.foreground));a.space(row,7);
            row.addView(a.text(n.optString("bssid")+" · "+n.optInt("rssi_dbm")+" dBm",12,false,a.secondary));a.space(row,5);
            row.addView(a.text("WPS · "+n.optString("security")+" · "+t("canal ","channel ")+n.optInt("channel"),12,false,a.secondary));
            a.content.addView(row);a.space(a.content,10);a.touch(row,ssid,()->{target=n;method="pixie";go("method");});
        }
        if(count==0){illustration(false,140);a.fact(t("Sin redes WPS observadas","No WPS networks observed"),t("No se han visto anuncios WPS. No equivale a demostrar que todos los routers lo tengan desactivado.","No WPS advertisements were observed. This does not prove WPS is disabled on every router."));}
        a.space(a.content,16);button(t("Escanear de nuevo","Scan again"),false,()->submit("wifi.scan",new JSONObject()));
        a.content.addView(a.text(t("Solo se admiten observaciones de los últimos 2 minutos. Si la selección caduca, vuelve a escanear.","Only observations from the last 2 minutes are accepted. Scan again if your selection expires."),12,false,a.secondary));
    }
    void methods(){
        if(target==null){go("intro");return;}
        a.heading("OneShot",target.optString("ssid"));a.fact("BSSID",target.optString("bssid"));a.space(a.content,12);
        String[] modes={"pixie","pin","pbc","bruteforce"};String[] titles={"Pixie Dust",t("PIN conocido","Known PIN"),t("Botón WPS","WPS button"),t("Fuerza bruta · PIN","Brute force · PIN")};
        String[] subtitles={t("Recoge los datos WPS y comprueba si permiten calcular el PIN.","Collect WPS data and check whether the PIN can be calculated."),t("Valida un PIN de 8 dígitos que ya conoces.","Validate an 8-digit PIN you already know."),t("Activa el botón físico del router cuando empiece la espera.","Press the router's physical button when the wait begins."),t("Prueba PINs en lotes limitados. Se detiene ante bloqueo o rechazo ambiguo.","Test PINs in bounded batches. Stops on lockout or ambiguous rejection.")};
        for(int i=0;i<modes.length;i++){final String value=modes[i];boolean selected=method.equals(value);LinearLayout row=a.column();row.setPadding(a.dp(18),a.dp(17),a.dp(18),a.dp(17));a.card(row,selected?a.foreground:a.surface,18);row.setSelected(selected);row.addView(a.text(titles[i],17,true,selected?a.background:a.foreground));a.space(row,7);row.addView(a.text(subtitles[i],13,false,selected?a.background:a.secondary));a.content.addView(row);a.space(a.content,10);a.touch(row,titles[i],()->{method=value;a.render();});}
        if(method.equals("pin")){pin=new EditText(a);pin.setSingleLine(true);pin.setHint("PIN WPS · 8");pin.setTextColor(a.foreground);pin.setHintTextColor(a.secondary);pin.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);pin.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});pin.setMinHeight(a.dp(56));pin.setSaveEnabled(false);a.content.addView(pin);a.space(a.content,12);}
        if(method.equals("bruteforce")){
            a.space(a.content,10);a.content.addView(a.text(t("LÍMITE DE INTENTOS","ATTEMPT LIMIT"),12,true,a.secondary));a.space(a.content,10);
            LinearLayout choices=new LinearLayout(a);choices.setGravity(Gravity.CENTER);
            for(int size:new int[]{10,50,100}){TextView chip=a.text(String.valueOf(size),16,true,budget==size?a.background:a.foreground);chip.setGravity(Gravity.CENTER);a.card(chip,budget==size?a.foreground:a.surface,12);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,a.dp(48),1);lp.setMargins(a.dp(3),0,a.dp(3),0);choices.addView(chip,lp);a.touch(chip,String.valueOf(size),()->{budget=size;a.render();});}a.content.addView(choices);a.space(a.content,14);
            prefix=new EditText(a);prefix.setSingleLine();prefix.setText(prefixValue);prefix.setHint(t("Prefijo inicial · 4 dígitos","Starting prefix · 4 digits"));prefix.setTextColor(a.foreground);prefix.setInputType(InputType.TYPE_CLASS_NUMBER);prefix.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});prefix.setSaveEnabled(false);a.content.addView(prefix);a.space(a.content,8);
            a.content.addView(a.text(t("Prefijo inicial de 4 dígitos. Máximo 10 minutos, 3 s entre intentos y 2 reintentos por timeout. No es una búsqueda exhaustiva.","Starting 4-digit prefix. Up to 10 minutes, 3 s between attempts and 2 timeout retries. Not an exhaustive search."),13,false,a.secondary));
        }
        a.space(a.content,10);button(t("Continuar","Continue"),true,this::confirm);
    }
    void confirm(){
        try{
            JSONObject args=p("snapshot_id",scanId).put("bssid",target.getString("bssid")).put("mode",method).put("authorized",true);
            if(method.equals("pin"))args.put("pin",pin.getText().toString());
            if(method.equals("bruteforce"))args.put("max_attempts",budget).put("start_prefix",prefix.getText().toString());WpsJob.validate(args);
            new AlertDialog.Builder(a).setTitle(t("Confirmar prueba WPS","Confirm WPS test"))
                .setMessage(target.optString("ssid")+"\n"+target.optString("bssid")+t("\n\nConfirma que tienes autorización sobre esta red. Se pausará el Wi-Fi del teléfono. Los intentos PIN pueden bloquear WPS en el router. Puedes detener la sesión.","\n\nConfirm you are authorized to test this network. The phone's Wi-Fi connection will pause. PIN attempts may lock WPS on the router. You can stop the session.")+(method.equals("bruteforce")?t("\n\nLímite: ","\n\nLimit: ")+budget+t(" intentos / 10 minutos. Sin reanudar tras un bloqueo."," attempts / 10 minutes. No resuming after lockout."):t("\n\nLímite: 2 minutos.","\n\nLimit: 2 minutes.")))
                .setNegativeButton(t("Cancelar","Cancel"),null).setPositiveButton(t("Iniciar prueba","Start test"),(d,w)->submit("wps.oneshot",args)).show();
        }catch(Exception e){a.notice(friendly(e.getMessage()));}
    }
    void submit(String tool,JSONObject args){if(pending)return;pending=true;try{call("submit",p("tool",tool).put("args",args).put("request_id",UUID.randomUUID().toString()),r->{if(!r.optBoolean("ok")){fail(r.optString("detail"));return;}receipt=r.getJSONObject("job");id=receipt.getString("id");go("working");});}catch(Exception e){pending=false;fail(e.getMessage());}}
    void working(){
        JSONObject args=receipt==null?null:receipt.optJSONObject("args");String m=args==null?method:args.optString("mode",method);
        renderedMode=m;
        a.heading("OneShot",modeName(m)+" · WPS");illustration(true,94);a.space(a.content,12);
        phase=center(t("Consultando estado…","Checking status…"),22);a.space(a.content,9);
        detail=a.text("",14,false,a.secondary);detail.setGravity(Gravity.CENTER);a.content.addView(detail);a.space(a.content,18);
        LinearLayout panel=a.column();panel.setPadding(a.dp(16),a.dp(15),a.dp(16),a.dp(15));a.card(panel,a.surface,18);
        elapsed=a.text("—",15,true,a.foreground);panel.addView(elapsed);a.space(panel,12);
        steps=new WpsSteps(a);panel.addView(steps,new LinearLayout.LayoutParams(-1,a.dp(48)));a.space(panel,12);
        stats=a.text("",13,false,a.secondary);stats.setLineSpacing(a.dp(5),1);panel.addView(stats);a.content.addView(panel);a.space(a.content,16);
        button(t("Detener sesión","Stop session"),true,()->{if(id!=null)call("cancel",p("id",id),r->{if(phase!=null)phase.setText(t("Deteniendo y restaurando…","Stopping and restoring…"));});});
        a.space(a.content,10);a.content.addView(a.text(t("ACTIVIDAD RECIENTE","RECENT ACTIVITY"),12,true,a.secondary));a.space(a.content,12);
        history=a.text(t("Esperando observaciones del motor…","Waiting for engine observations…"),13,false,a.secondary);history.setLineSpacing(a.dp(6),1);a.content.addView(history);a.space(a.content,18);
        button(t("Ver sesiones","View sessions"),false,()->a.navigate("sessions"));refresh();
    }
    String modeName(String m){return m.equals("pbc")?t("Botón físico","Push button"):m.equals("pin")?t("PIN conocido","Known PIN"):m.equals("bruteforce")?t("Fuerza bruta PIN","PIN brute force"):"Pixie Dust";}
    void updateProgress(JSONObject prog,boolean scan,boolean stopping){
        if(prog==null)prog=new JSONObject();String stage=prog.optString("phase");
        phase.setText(stopping?t("Deteniendo y restaurando…","Stopping and restoring…"):scan?t("Buscando redes WPS…","Looking for WPS networks…"):phaseName(stage));
        detail.setText(scan?t("Buscando anuncios WPS; no se están probando PINs.","Looking for WPS advertisements; no PINs are being tested."):stage.equals("wps_wait_button")?t("Pulsa WPS en el router. La espera no confirma que WPS esté bloqueado.","Press WPS on the router. Waiting does not mean WPS is locked."):stage.equals("wps_exchange")?"M"+prog.optInt("message")+" · "+(prog.optString("direction").equals("rx")?t("recibido del router","received from router"):t("preparado para enviar","prepared for sending")):t("Cada cambio corresponde a una observación del motor.","Each change corresponds to an engine observation."));
        elapsed.setText((prog.has("seconds")?prog.optLong("seconds")+" s":"—")+"  ·  "+(prog.optString("lock_status").equals("reported_locked")?t("Bloqueo indicado","Lockout reported"):t("Bloqueo no confirmado","Lockout unconfirmed")));
        steps.set(prog.optInt("message_mask"),prog.optInt("message"));
        stats.setText(t("Intentos ","Attempts ")+prog.optInt("attempts")+" / "+prog.optInt("max_attempts",1)+"    ·    "+t("Reintentos ","Retries ")+prog.optInt("retries")+"\n"+t("PINs distintos ","Distinct PINs ")+prog.optInt("candidates")+"    ·    "+t("Mensajes repetidos ","Repeated messages ")+prog.optInt("retransmissions"));
        history.setText(historyText(prog));
    }
    String historyText(JSONObject prog){
        JSONArray rows=prog.optJSONArray("events");if(rows==null||rows.length()==0)return t("Esperando observaciones del motor…","Waiting for engine observations…");
        StringBuilder text=new StringBuilder();for(int i=rows.length()-1;i>=Math.max(0,rows.length()-6);i--){JSONObject row=rows.optJSONObject(i);if(row==null)continue;
            if(text.length()>0)text.append('\n');text.append(String.format(Locale.ROOT,"%02d:%02d",row.optInt("seconds")/60,row.optInt("seconds")%60)).append("   ");
            if(row.optString("code").equals("wps_exchange"))text.append("M").append(row.optInt("message")).append(" · ").append(row.optString("direction").equals("rx")?t("recibido","received"):t("preparado","prepared"));
            else text.append(phaseName(row.optString("code")));
            if(row.has("config_error"))text.append(" · ").append(t("código ","code ")).append(row.optInt("config_error"));
        }return text.toString();
    }
    void refresh(){main.removeCallbacks(poll);if(!visible||!resumed||id==null||!screen.equals("working"))return;
        call("get",p("id",id),r->{if(!r.optBoolean("ok")){fail(r.optString("detail"));return;}receipt=r.getJSONObject("job");
            if(active(receipt)){JSONObject args=receipt.optJSONObject("args");if(args!=null&&args.has("mode")&&!renderedMode.equals(args.optString("mode"))){go("working");return;}updateProgress(receipt.optJSONObject("progress"),receipt.optString("tool").equals("wifi.scan"),receipt.optBoolean("cancel_requested"));main.postDelayed(poll,650);return;}
            JSONObject result=receipt.optJSONObject("result");
            if(receipt.optString("tool").equals("wifi.scan")&&result!=null&&result.has("networks")){scanId=id;networks=result.getJSONArray("networks");go("networks");}
            else go("result");
        });
    }
    String phaseName(String s){switch(s){
        case "wps_wait_button":return t("Esperando WPS button…","Waiting for WPS button…");
        case "wps_preparing":return t("Preparando antena…","Preparing adapter…");
        case "wps_wait_ap":return t("Esperando al router…","Waiting for router…");
        case "wps_authenticating":return t("Autenticando…","Authenticating…");
        case "wps_associated":return t("Enlace establecido","Link established");
        case "wps_m2d":return t("M2D · negociación aplazada","M2D · negotiation deferred");
        case "wps_nack":return t("NACK · intercambio rechazado","NACK · exchange rejected");
        case "wps_failure":return t("Respuesta de error WPS","WPS error response");
        case "wps_retry_wait":return t("Pausa antes de reintentar…","Pausing before retry…");
        case "wps_next_pin":return t("Pausa · siguiente PIN…","Pause · next PIN…");
        case "wps_second_half":return t("Primera mitad validada","First half validated");
        case "pixie_calculation":return t("Calculando con Pixie Dust…","Calculating with Pixie Dust…");
        case "wps_verifying":return t("Verificando PIN calculado…","Verifying calculated PIN…");
        case "wps_restoring":return t("Restaurando Wi-Fi…","Restoring Wi-Fi…");
        case "wps_complete":return t("Intercambio completado","Exchange completed");
        case "wps_exchange":return t("Intercambiando mensajes WPS","Exchanging WPS messages");
        case "":return t("Consultando estado…","Checking status…");
        default:return friendly(s);
    }}
    void result(){JSONObject r=receipt==null?null:receipt.optJSONObject("result");boolean success=r!=null&&r.optBoolean("protocol_confirmed");a.heading(success?t("Credenciales recibidas","Credentials received"):t("Sin credenciales verificadas","No verified credentials"),"OneShot · WPS");illustration(false,130);a.space(a.content,16);
        if(success){a.fact("SSID",r.optString("ssid"));a.fact(t("Contraseña","Password"),r.optString("password"));if(!r.optString("pin").isEmpty())a.fact("PIN WPS",r.optString("pin"));}
        else a.fact(t("Resultado","Result"),friendly(r!=null?r.optString("reason","partial"):receipt==null?"wps_process_failed":receipt.optString("error",receipt.optString("state"))));
        if(r!=null)a.fact(t("Wi-Fi","Wi-Fi"),r.optBoolean("restored")?t("Estado anterior restaurado","Previous state restored"):t("Restauración no confirmada","Restoration not confirmed"));
        JSONObject telemetry=r==null?null:r.optJSONObject("telemetry");if(telemetry==null&&receipt!=null)telemetry=receipt.optJSONObject("progress");
        if(telemetry!=null){a.fact(t("Intercambios / reintentos","Exchanges / retries"),telemetry.optInt("attempts")+" / "+telemetry.optInt("retries"));a.fact(t("Actividad reciente","Recent activity"),historyText(telemetry));}
        a.space(a.content,20);button(t("Nueva sesión","New session"),true,()->{id=null;go("intro");});
    }
    void fail(String message){error=message==null?"wps_error":message;go("error");}
    String friendly(String e){if(e==null)e="";
        if(e.equals("negotiation_deferred"))return t("El router aplazó el intercambio (M2D). No confirma un bloqueo WPS.","The router deferred the exchange (M2D). This does not confirm WPS lockout.");
        if(e.equals("negotiation_rejected"))return t("El intercambio fue rechazado; no hay evidencia suficiente de bloqueo ni de PIN incorrecto.","The exchange was rejected; insufficient evidence of lockout or an incorrect PIN.");
        if(e.equals("pin_rejected"))return t("PIN rechazado durante la negociación.","PIN rejected during negotiation.");
        if(e.equals("pixie_no_pin"))return t("Pixie Dust no calculó un PIN con los datos recibidos.","Pixie Dust did not calculate a PIN from the received data.");
        if(e.equals("insufficient_pixie_data"))return t("Faltan datos WPS para ejecutar Pixie Dust. No equivale a WPS bloqueado.","Insufficient WPS data for Pixie Dust. This does not mean WPS is locked.");
        if(e.equals("attempt_limit"))return t("Lote completado sin credenciales. No se ha probado todo el espacio de PINs.","Batch completed without credentials. The entire PIN space was not tested.");
        if(e.equals("session_limit")||e.equals("wps_timeout"))return t("Tiempo de espera agotado. No confirma bloqueo ni PIN incorrecto.","Timed out. This does not confirm lockout or an incorrect PIN.");
        if(e.equals("pbc_overlap"))return t("Se detectaron varias sesiones de botón WPS. Se ha detenido la prueba.","Multiple WPS push-button sessions detected. Test stopped.");
        if(e.equals("range_exhausted"))return t("Se alcanzó el final del rango seleccionado.","End of the selected range reached.");
        if(e.equals("invalid_start_prefix"))return t("El prefijo inicial debe tener exactamente 4 dígitos.","The starting prefix must contain exactly 4 digits.");
        if(e.startsWith("invalid_wps_pin"))return t("Introduce un PIN WPS válido de 8 dígitos, incluido su dígito de control.","Enter a valid 8-digit WPS PIN, including its checksum digit.");
        if(e.contains("expired")||e.contains("stale"))return t("El escaneo ha caducado. Busca las redes de nuevo.","The scan expired. Scan for networks again.");if(e.contains("permission"))return t("Revisa los permisos de ubicación y redes cercanas en Ajustes.","Check location and nearby-device permissions in Settings.");if(e.contains("locked"))return t("El router indicó bloqueo WPS explícito. No se reintentará automáticamente.","The router explicitly reported WPS lockout. No automatic retry.");if(e.contains("cancel"))return t("Sesión detenida.","Session stopped.");if(e.equals("partial"))return t("El intercambio no produjo credenciales verificadas. No demuestra que la red sea segura.","The exchange did not yield verified credentials. This does not prove the network is secure.");return t("Detalle: ","Details: ")+e;}
}
