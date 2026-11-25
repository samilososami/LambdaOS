package org.lambdaproject.lambda;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Native view of the durable Core job; leaving this page never kills the test. */
final class BenchmarkUi {
    private final MainActivity a;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private String page="",id,engine="aircrack";
    private int threads=4,generation;
    private boolean resumed=true,destroyed,pending,active,loading;
    private TextView rate,phase,meta,start,status;
    private ProgressBar bar;
    private LinearLayout resultBox;
    private final ArrayList<View> options=new ArrayList<>();
    private JSONObject last;
    private final Runnable poll=()->refresh();
    BenchmarkUi(MainActivity activity){a=activity;id=a.getPreferences(0).getString("benchmark_job",null);}
    private String t(String es,String en){return a.tr(es,en);}
    private interface Callback{void accept(JSONObject value)throws Exception;}
    private void call(String method,JSONObject request,Callback callback){
        int epoch=generation;
        io.execute(()->{
            JSONObject r;
            try{Bundle b=a.getContentResolver().call(CoreProvider.URI,method,request.toString(),null);r=new JSONObject(b.getString("json"));}
            catch(Exception e){r=CoreEngine.error(e);}
            JSONObject value=r;
            main.post(()->{if(method.equals("cancel"))pending=false;if(destroyed||epoch!=generation)return;try{callback.accept(value);}catch(Exception e){a.notice(e.getMessage());}});
        });
    }
    private JSONObject obj(String key,Object value){try{return new JSONObject().put(key,value);}catch(Exception e){throw new IllegalStateException(e);}}
    void beforeRender(){generation++;page="";options.clear();main.removeCallbacks(poll);}
    void pause(){resumed=false;main.removeCallbacks(poll);}
    void resume(){resumed=true;if(page.equals("benchmark"))refresh();}
    void destroy(){destroyed=true;main.removeCallbacksAndMessages(null);io.shutdownNow();}
    boolean back(){
        if(page.equals("developer")){a.navigate("settings");return true;}
        if(page.equals("temperature")){a.navigate("developer");return true;}
        if(page.equals("cracking_tests")){a.navigate("developer");return true;}
        if(page.equals("benchmark")){a.navigate("cracking_tests");return true;}return false;
    }
    void render(String route){
        page=route;
        if(route.equals("developer")){
            a.heading("Developer mode",t("Pruebas locales de Lambda Operator.","Local Lambda Operator tests."));
            tile("Cracking speed test",t("Velocidad real · CPU y GPU","Real throughput · CPU and GPU"),()->a.navigate("cracking_tests"));
            tile("Temperature",t("FDT y monitor térmico","FDT and thermal monitor"),()->a.navigate("temperature"));
            a.space(a.content,18);a.content.addView(a.text(t("Datos sintéticos. Sin capturas ni deauth.","Synthetic data. No captures or deauth."),14,false,a.secondary));return;
        }
        if(route.equals("temperature")){
            a.heading("Temperature",t("Pruebas térmicas del dispositivo.","Device thermal tests."));
            tile("Force Device temperature",t("Calienta hasta el objetivo y mantiene 30 s","Heats to the target and holds for 30 s"),()->fdt());
            tile("Temperature Monitor",t("Monitor pasivo de temperatura en tiempo real","Passive live temperature monitor"),()->a.startActivity(new Intent(a,CoolingActivity.class).putExtra("mode","monitor")));
            a.space(a.content,18);
            a.content.addView(a.text(t("El monitor no modifica el dispositivo; sirve para comparar el enfriamiento natural con UCM.","The monitor does not modify the device; use it to compare natural cooling with UCM."),14,false,a.secondary));return;
        }
        if(route.equals("cracking_tests")){
            a.heading("Cracking speed test",t("Elige el tipo de prueba.","Choose a test suite."));
            tile("WPA / PMKID","PBKDF2-SHA1 · 4.800 "+t("candidatos","candidates"),()->a.navigate("benchmark"));return;
        }
        a.heading("WPA / PMKID",t("Compara motores con los mismos datos.","Compare engines on identical inputs."));
        LinearLayout engines=new LinearLayout(a);
        choice(engines,"CPU",engine.equals("aircrack"),()->{engine="aircrack";a.render();});
        choice(engines,t("GPU · experimental","GPU · experimental"),engine.equals("hashcat"),()->{engine="hashcat";a.render();});
        a.content.addView(engines);a.space(a.content,12);
        a.content.addView(a.text(engine.equals("aircrack")?"Aircrack-ng 1.7 · ARM64":"Hashcat 8.0 · Mali-G76 · OpenCL",13,false,a.secondary));a.space(a.content,16);
        if(engine.equals("hashcat")){a.content.addView(a.text(t("Experimental: esta versión no supera el self-test en el S10. No usar para descartar contraseñas.","Experimental: this build fails the S10 self-test. Do not use it to rule out passwords."),13,false,a.secondary));a.space(a.content,12);}
        if(engine.equals("aircrack")){
            LinearLayout count=new LinearLayout(a);for(int n:new int[]{1,4,8})choice(count,n+" "+t(n==1?"hilo":"hilos",n==1?"thread":"threads"),threads==n,()->{threads=n;a.render();});
            a.content.addView(count);a.space(a.content,18);
        }
        LinearLayout panel=a.column();panel.setPadding(a.dp(22),a.dp(22),a.dp(22),a.dp(22));a.card(panel,a.surface,22);
        phase=a.text(t("Listo para medir","Ready to measure"),14,true,a.secondary);panel.addView(phase);a.space(panel,22);
        rate=a.text("—",52,true,a.foreground);panel.addView(rate);
        panel.addView(a.text(t("contraseñas / segundo","passwords / second"),13,false,a.secondary));a.space(panel,24);
        bar=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(5);bar.setProgressTintList(android.content.res.ColorStateList.valueOf(a.foreground));
        bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(a.foreground));bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(a.divider));
        panel.addView(bar,new LinearLayout.LayoutParams(-1,a.dp(4)));a.space(panel,16);
        meta=a.text(t("Temperatura y memoria durante la prueba","Temperature and memory during the test"),13,false,a.secondary);meta.setLineSpacing(a.dp(5),1);panel.addView(meta);
        a.content.addView(panel);a.space(a.content,16);
        start=a.text(t("Iniciar prueba","Start test"),17,true,a.background);start.setGravity(Gravity.CENTER);start.setPadding(a.dp(12),a.dp(18),a.dp(12),a.dp(18));a.card(start,a.foreground,14);
        a.touch(start,t("Iniciar o detener prueba","Start or stop test"),()->{if(active)cancel();else submit();});a.content.addView(start,new LinearLayout.LayoutParams(-1,-2));a.space(a.content,12);
        status=a.text(t("Comprobando motor…","Checking engine…"),13,false,a.secondary);status.setLineSpacing(a.dp(4),1);a.content.addView(status);a.space(a.content,20);
        resultBox=a.column();a.content.addView(resultBox);
        a.content.addView(a.text(t("4.800 candidatos de 8–15 caracteres. Incluye una prueba de respuesta conocida antes de medir. La velocidad del motor y el tiempo total del lote son métricas distintas.","4,800 candidates, 8–15 characters. A known-answer test runs before measurement. Engine throughput and total batch time are different metrics."),13,false,a.secondary));
        a.space(a.content,12);a.content.addView(a.text(t("Reserva la cola de Lambda. No detiene Android ni ADB y conserva las protecciones térmicas.","Reserves Lambda's queue. Android, ADB and thermal protections remain active."),12,false,a.secondary));
        loading=id!=null;controls();
        if(last!=null)try{draw(last);}catch(Exception e){a.notice(e.getMessage());}
        call("radio_status",new JSONObject(),r->{JSONObject cap=r.optJSONObject("benchmark");boolean ready=r.optBoolean("available")&&cap!=null&&cap.optBoolean("provisioned");
            status.setText(ready?t("Motor disponible · solo datos sintéticos","Engine available · synthetic data only"):t("El servicio de pruebas no está disponible. Requiere aprovisionamiento por ADB tras reiniciar.","Test worker unavailable. ADB provisioning is required after a reboot."));});
        refresh();
    }
    private void fdt(){
        new AlertDialog.Builder(a).setTitle("Force Device temperature")
            .setMessage(t("Esto calienta el dispositivo de forma controlada hasta el objetivo elegido. Herramienta de desarrollo: no la dejes sin supervisión.",
                "This heats the device in a controlled way up to the chosen target. Developer tool: do not leave it unattended."))
            .setNegativeButton(t("Cancelar","Cancel"),null)
            .setPositiveButton(t("Elegir objetivo","Choose target"),(d,w)->fdtTarget())
            .show();
    }
    private void fdtTarget(){
        new AlertDialog.Builder(a).setTitle(t("Objetivo de temperatura","Temperature target"))
            .setItems(new CharSequence[]{"50 °C","55 °C","60 °C","65 °C","70 °C"},(d,w)->fdtHold(50+w*5))
            .setNegativeButton(t("Cancelar","Cancel"),null).show();
    }
    private void fdtHold(int target){
        new AlertDialog.Builder(a).setTitle(t("Duración del hold","Hold duration"))
            .setMessage(t("Para calentar el cuerpo, no solo el SoC, usa 120 o 300 s.","To warm the body, not just the SoC, use 120 or 300 s."))
            .setItems(new CharSequence[]{"30 s","60 s","120 s","300 s"},(d,w)->{
                int hold=new int[]{30,60,120,300}[w];
                a.startActivity(new Intent(a,CoolingActivity.class).putExtra("mode","heater").putExtra("target_c",target).putExtra("hold_seconds",hold));
            }).setNegativeButton(t("Cancelar","Cancel"),null).show();
    }
    private void tile(String title,String subtitle,Runnable action){
        LinearLayout tile=a.column();tile.setPadding(a.dp(22),a.dp(24),a.dp(22),a.dp(24));a.card(tile,a.surface,20);
        tile.addView(a.text(title,22,true,a.foreground));a.space(tile,10);tile.addView(a.text(subtitle,14,false,a.secondary));a.touch(tile,title,action);a.content.addView(tile,new LinearLayout.LayoutParams(-1,-2));
    }
    private void choice(LinearLayout parent,String title,boolean selected,Runnable action){
        TextView v=a.text(title,14,selected,selected?a.background:a.foreground);v.setGravity(Gravity.CENTER);v.setMinHeight(a.dp(48));v.setPadding(a.dp(4),a.dp(8),a.dp(4),a.dp(8));a.card(v,selected?a.foreground:a.surface,12);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);if(parent.getChildCount()>0)p.leftMargin=a.dp(8);parent.addView(v,p);a.touch(v,title,action);options.add(v);
    }
    private void controls(){
        boolean busy=active||pending||loading;
        for(View v:options){v.setEnabled(!busy);v.setAlpha(busy?.5f:1f);}
        if(start!=null){start.setEnabled(!pending&&!loading);start.setAlpha(pending||loading?.5f:1f);start.setText(active?t("Detener prueba","Stop test"):pending?t("Preparando…","Preparing…"):t("Iniciar prueba","Start test"));}
    }
    private void submit(){
        if(active||pending||loading)return;pending=true;controls();String requestId=UUID.randomUUID().toString();
        final JSONObject request;try{request=new JSONObject().put("tool","benchmark.wpa").put("request_id",requestId).put("args",new JSONObject().put("engine",engine).put("threads",threads));}catch(Exception e){pending=false;controls();return;}
        // Persist acceptance even if the user navigates away before the reply.
        io.execute(()->{JSONObject r;try{Bundle b=a.getContentResolver().call(CoreProvider.URI,"submit",request.toString(),null);r=new JSONObject(b.getString("json"));}catch(Exception e){r=CoreEngine.error(e);}
            JSONObject response=r;main.post(()->{pending=false;if(response.optBoolean("ok")){active=true;id=response.optJSONObject("job").optString("id");a.getPreferences(0).edit().putString("benchmark_job",id).apply();last=null;}
                if(destroyed)return;if(page.equals("benchmark")){controls();if(response.optBoolean("ok"))refresh();else a.notice(response.optString("detail",response.optString("error")));}});});
    }
    private void cancel(){if(id==null||pending)return;pending=true;controls();call("cancel",obj("id",id),r->{pending=false;controls();refresh();});}
    private void refresh(){
        if(!page.equals("benchmark")||!resumed||id==null)return;
        main.removeCallbacks(poll);call("get",obj("id",id),r->{loading=false;if(!r.optBoolean("ok")){active=false;controls();status.setText(r.optString("detail"));return;}
            last=r.getJSONObject("job");draw(last);if(active&&resumed)main.postDelayed(poll,800);});
    }
    private void draw(JSONObject job)throws Exception {
        String state=job.optString("state");active=state.equals("running")||state.equals("queued");controls();
        JSONObject data=active?job.optJSONObject("progress"):job.optJSONObject("result");
        JSONObject args=job.optJSONObject("args");String used=args==null?engine:args.optString("engine");
        if(data==null)data=new JSONObject();
        JSONArray rows=data.optJSONArray("rows");if(rows==null)rows=new JSONArray();
        double speed=data.optDouble("engine_keys_per_second",0);
        for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);if(r.optString("test").equals("wpa_engine")||(!used.equals("aircrack")&&r.optString("test").equals("wpa_4800")))speed=r.optDouble("engine_keys_per_second",0);}
        rate.setText(speed>0?String.format(Locale.getDefault(),"%,.0f",speed):"—");
        phase.setText(active?phaseLabel(data.optString("phase","queued")):
            state.equals("completed")?t("Prueba completada","Test complete"):
            state.equals("cancelled")?t("Prueba detenida","Test stopped"):t("Prueba incompleta","Incomplete test"));
        bar.setIndeterminate(active);bar.setMax(used.equals("aircrack")?5:4);bar.setProgress(rows.length());
        JSONObject tel=active?data:data.optJSONObject("after");if(tel==null)tel=new JSONObject();JSONObject temps=tel.optJSONObject("temperature_c");
        double sec=active?data.optDouble("seconds",0):data.optLong("wall_ms",job.optLong("duration_ms"))/1000.0;
        int usedThreads=args==null?threads:args.optInt("threads");
        String details=String.format(Locale.getDefault(),"%.1f s",sec)+"  ·  "+(used.equals("aircrack")?"CPU · "+usedThreads+" "+t(usedThreads==1?"hilo":"hilos",usedThreads==1?"thread":"threads"):"Mali-G76");
        if(temps!=null&&temps.has("battery"))details+=String.format(Locale.getDefault(),"\n%.1f °C · ",temps.optDouble("battery"))+t("batería","battery");
        if(tel.has("available_mib"))details+=String.format(Locale.getDefault(),"   %.0f MiB ",tel.optDouble("available_mib"))+t("libres","available");
        double rss=active?data.optDouble("engine_rss_mib",0):0;
        if(!active)for(int i=0;i<rows.length();i++){JSONObject g=rows.getJSONObject(i).optJSONObject("guard");if(g!=null)rss=Math.max(rss,g.optDouble("max_rss_kib",0)/1024.0);}
        if(rss>0)details+=String.format(Locale.getDefault(),"\n%.1f MiB · ",rss)+t(active?"RSS del motor":"RSS máximo del motor",active?"engine RSS":"engine peak RSS");
        meta.setText(details);resultBox.removeAllViews();
        for(int i=0;i<rows.length();i++){
            JSONObject r=rows.getJSONObject(i);String test=r.optString("test");if(!test.endsWith("_4800"))continue;
            String title=test.startsWith("pmkid")?"PMKID":"WPA2";
            resultBox.addView(a.text(title+" · 4.800",15,true,a.foreground));a.space(resultBox,7);
            String value=r.optBoolean("valid")?String.format(Locale.getDefault(),"%.2f s · %.0f ",r.optLong("wall_ms")/1000.0,r.optDouble("effective_keys_per_second"))+t("contraseñas/s efectivas","effective passwords/s"):t("No validado","Not validated");
            resultBox.addView(a.text(value,14,false,a.secondary));a.space(resultBox,16);
        }
        if(active)status.setText(t("Prueba en curso · puedes salir y volver sin interrumpirla.","Test running · you can leave and return without interrupting it."));
        else if(!state.equals("completed")){String why=data.optString("stop_reason",job.optString("error",""));status.setText(reason(why));}
        else if(!active)status.setText(t("Respuestas conocidas verificadas. Resultados guardados en Sesiones.","Known answers verified. Results saved in Sessions."));
    }
    private String reason(String value){
        if(value.equals("deadline"))return t("Se alcanzó el límite de tiempo. No se valida la velocidad.","Time limit reached. Throughput is not validated.");
        if(value.equals("thermal_limit"))return t("Prueba detenida por temperatura. Deja enfriar el teléfono.","Stopped by the thermal guard. Let the phone cool down.");
        if(value.equals("memory_limit"))return t("Prueba detenida para conservar memoria disponible.","Stopped to preserve available memory.");
        if(value.equals("cancelled"))return t("Has detenido la prueba. No quedan procesos de cálculo activos.","You stopped the test. No compute processes remain active.");
        if(value.startsWith("known_answer_failed"))return t("El motor no ha superado la validación. Detalles en Sesiones.","The engine did not pass validation. Details in Sessions.");
        if(value.equals("opencl_selftest_failed"))return t("La GPU ha fallado la autocomprobación criptográfica. El motor CPU sigue disponible.","GPU cryptographic self-test failed. The CPU engine is still available.");
        return t("Prueba incompleta: ","Incomplete test: ")+value;
    }
    private String phaseLabel(String phase){switch(phase){
        case "selftest_wpa":return t("Validando handshake…","Validating handshake…");case "selftest_pmkid":return t("Validando PMKID…","Validating PMKID…");
        case "wpa_engine":return t("Midiendo motor CPU…","Measuring CPU engine…");case "wpa_4800":return t("Probando 4.800 · WPA2","Testing 4,800 · WPA2");
        case "pmkid_4800":return t("Probando 4.800 · PMKID","Testing 4,800 · PMKID");default:return t("Preparando prueba…","Preparing test…");}}
}
