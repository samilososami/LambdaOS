package org.lambdaproject.lambda;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.usb.*;
import android.os.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Native UI is an IPC client, not the owner of scans or their lifetime. */
final class OperatorUi {
    private final MainActivity a;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private String id,exportJson,pendingTool;
    private JSONObject pendingArgs;
    private TextView progress;
    private boolean viewingJob,resumed=true,destroyed;
    private BroadcastReceiver usbReceiver;
    private boolean submitting;
    private int generation;
    OperatorUi(MainActivity a){this.a=a;id=a.getPreferences(0).getString("last_core_job",null);}
    private String t(String es,String en){return a.tr(es,en);}
    private JSONObject obj(){return new JSONObject();}
    private JSONObject params(String k,Object v){try{return new JSONObject().put(k,v);}catch(Exception e){throw new IllegalStateException(e);}}
    private interface Result{void accept(JSONObject r)throws Exception;}
    private void call(String method,JSONObject req,Result result){
        int version=generation;
        io.execute(()->{JSONObject response;
            try{Bundle b=a.getContentResolver().call(CoreProvider.URI,method,req.toString(),null);response=new JSONObject(b.getString("json"));}
            catch(Exception e){response=CoreEngine.error(e);}
            JSONObject value=response;main.post(()->{if(method.equals("submit"))submitting=false;if(destroyed||version!=generation)return;try{result.accept(value);}catch(Exception e){a.notice(e.getMessage());}});
        });
    }
    void beforeRender(){generation++;viewingJob=false;main.removeCallbacks(poll);}
    void pause(){resumed=false;main.removeCallbacks(poll);}
    void resume(){resumed=true;if(viewingJob)main.post(poll);}
    void destroy(){destroyed=true;main.removeCallbacksAndMessages(null);if(usbReceiver!=null){a.unregisterReceiver(usbReceiver);usbReceiver=null;}io.shutdownNow();}
    private final Runnable poll=()->refreshJob(false);
    private String[] required(){return new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.POST_NOTIFICATIONS};}
    void permissions(){ArrayList<String> missing=new ArrayList<>();for(String p:required())if(a.checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)missing.add(p);
        if(missing.isEmpty())a.notice(t("Permisos concedidos.","Permissions granted."));else a.requestPermissions(missing.toArray(new String[0]),82);}
    void backgroundPermission(){
        if(a.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)==PackageManager.PERMISSION_GRANTED){a.notice(t("Acceso en segundo plano concedido.","Background access granted."));return;}
        new AlertDialog.Builder(a).setTitle(t("Auditorías en segundo plano","Background audits"))
            .setMessage(t("Android exige Ubicación → Permitir siempre para leer resultados Wi-Fi fuera de la app. Core solo escanea cuando se lo pides; no inicia seguimiento de ubicación.","Android requires Location → Allow all the time to read Wi-Fi results outside the app. Core scans only when requested; it does not start location tracking."))
            .setNegativeButton(t("Ahora no","Not now"),null).setPositiveButton(t("Abrir permisos","Open permissions"),(d,w)->a.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+a.getPackageName())))).show();
    }
    void permissionsResult(int code){if(code!=82)return;if(pendingTool!=null){String tool=pendingTool;JSONObject args=pendingArgs;pendingTool=null;run(tool,args,false);}else a.render();}
    private boolean authorized(String tool){
        if(tool.startsWith("wifi."))return a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        if(tool.startsWith("bluetooth."))return a.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&a.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
        return true;
    }
    private void run(String tool,JSONObject args){run(tool,args,true);}
    private void run(String tool,JSONObject args,boolean ask){
        if(submitting)return;
        if(!authorized(tool)){if(ask){pendingTool=tool;pendingArgs=args;permissions();}else a.notice(t("Faltan permisos. Actívalos en Ajustes → Lambda.","Permissions missing. Enable them in Settings → Lambda."));return;}
        JSONObject req=params("tool",tool);try{req.put("args",args).put("request_id",UUID.randomUUID().toString());}catch(Exception e){throw new IllegalStateException(e);}
        submitting=true;
        call("submit",req,r->{submitting=false;if(!r.optBoolean("ok")){a.notice(r.optString("detail"));return;}id=r.getJSONObject("job").getString("id");a.getPreferences(0).edit().putString("last_core_job",id).apply();a.navigate("job");});
    }
    private void analyze(String tool,String... sources){
        call("list",obj(),r->{JSONArray jobs=r.optJSONArray("jobs");if(jobs!=null)for(int i=0;i<jobs.length();i++){
            JSONObject j=jobs.getJSONObject(i);if(Arrays.asList(sources).contains(j.optString("tool"))&&Arrays.asList("completed","partial").contains(j.optString("state"))){run(tool,params("snapshot_id",j.getString("id")));return;}}
            a.notice(t("Primero realiza un escaneo. El análisis usa ese resultado guardado.","Run a scan first. Analysis uses that saved snapshot."));});
    }
    void category(int category){
        String[] titles={"Wi-Fi","Bluetooth","USB"};
        String[] desc={t("Redes, señal y configuración de seguridad.","Networks, signal and security configuration."),t("Dispositivos cercanos y servicios anunciados.","Nearby devices and advertised services."),t("Accesorios, interfaces y superficie USB.","Accessories, interfaces and USB exposure.")};
        if(category==0){if(a.handshake.category())return;}else a.heading(titles[category],desc[category]);
        TextView tag=a.text(t("AUDITORÍA LOCAL", "LOCAL AUDIT"),11,true,a.secondary);tag.setLetterSpacing(.13f);a.content.addView(tag);a.space(a.content,16);
        if(category==0){
            action(t("Escanear redes","Scan networks"),t("SSID · cifrado · canal · potencia","SSID · security · channel · signal"),0,true,()->run("wifi.scan",obj()));
            action(t("Auditar seguridad","Audit security"),t("Abiertas, WEP, WPA, TKIP y WPS","Open, WEP, WPA, TKIP and WPS"),0,false,()->analyze("wifi.audit","wifi.scan","wifi.cached"));
            action(t("Mapa de canales","Channel map"),t("Distribución de los puntos de acceso","Access-point distribution"),5,false,()->analyze("wifi.channels","wifi.scan","wifi.cached"));
            action(t("Mi conexión","My connection"),t("Direcciones · DNS · rutas · enlace","Addresses · DNS · routes · link"),4,false,()->run("wifi.link",obj()));
            action(t("Monitor de señal","Signal monitor"),t("Muestreo de tu conexión durante 12 s","Sample your connection for 12 s"),0,false,()->run("wifi.signal",params("seconds",12)));
            action(t("Últimos resultados Android","Android scan cache"),t("Sin solicitar otro escaneo","Without requesting another scan"),8,false,()->run("wifi.cached",obj()));
        }else if(category==1){
            action(t("Explorar Bluetooth LE","Explore Bluetooth LE"),t("Anuncios BLE · 12 segundos","BLE advertisements · 12 seconds"),1,true,()->run("bluetooth.ble_scan",params("seconds",12)));
            action(t("Buscar Bluetooth Classic","Discover Bluetooth Classic"),t("Dispositivos visibles · 15 segundos","Discoverable devices · 15 seconds"),1,false,()->run("bluetooth.classic_scan",params("seconds",15)));
            action(t("Auditar descubrimiento","Audit discovery"),t("Exposición de nombres y conectividad","Name and connection exposure"),4,false,()->analyze("bluetooth.audit","bluetooth.ble_scan","bluetooth.classic_scan","bluetooth.bonded"));
            action(t("Dispositivos vinculados","Paired devices"),t("Vínculos y UUID guardados","Bonds and cached UUIDs"),1,false,()->run("bluetooth.bonded",obj()));
            action(t("Estado del adaptador","Adapter status"),t("PHY · Bluetooth LE · capacidades","PHY · Bluetooth LE · capabilities"),6,false,()->run("bluetooth.status",obj()));
        }else{
            action(t("Inspeccionar USB","Inspect USB"),t("VID/PID · interfaces · endpoints","VID/PID · interfaces · endpoints"),2,true,()->run("usb.inventory",obj()));
            action(t("Auditar superficie USB","Audit USB exposure"),t("HID · almacenamiento · dispositivos compuestos","HID · storage · composite devices"),4,false,()->run("usb.audit",obj()));
            action(t("Observar conexiones","Watch connections"),t("Conexiones y desconexiones · 15 segundos","Attach and detach events · 15 seconds"),2,false,()->run("usb.watch",params("seconds",15)));
            action(t("Capacidades del sistema","System capabilities"),t("Kernel · interfaces · hardware detectado","Kernel · interfaces · detected hardware"),6,false,()->run("system.capabilities",obj()));
            a.space(a.content,12);a.content.addView(a.text(t("Conecta el accesorio mediante un adaptador USB-C OTG. El cable del PC no aparece como un periférico.","Connect an accessory through USB-C OTG. The PC cable is not a host-mode peripheral."),14,false,a.secondary));
        }
        a.space(a.content,16);
        a.content.addView(a.text(t("Las tareas siguen activas al salir de esta pantalla. Puedes detenerlas desde Sesiones.","Tasks continue when you leave this screen. Stop them from Sessions."),13,false,a.secondary));
    }
    private void action(String title,String subtitle,int glyph,boolean primary,Runnable run){
        LinearLayout row=new LinearLayout(a);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(a.dp(16),a.dp(17),a.dp(16),a.dp(17));
        int ink=primary?a.background:a.foreground;row.addView(new Glyph(a,glyph,ink),new LinearLayout.LayoutParams(a.dp(34),a.dp(34)));
        LinearLayout text=a.column();text.setPadding(a.dp(16),0,a.dp(4),0);text.addView(a.text(title,17,true,ink));a.space(text,6);text.addView(a.text(subtitle,12,false,primary?(a.dark?0xff505254:0xffD5D5D5):a.secondary));
        row.addView(text,new LinearLayout.LayoutParams(0,-2,1));a.card(row,primary?a.foreground:a.surface,16);
        a.content.addView(row,new LinearLayout.LayoutParams(-1,-2));a.touch(row,title,run);a.space(a.content,10);
    }
    void job(){viewingJob=true;if(id==null){a.heading(t("Sesión","Session"),t("Selecciona una tarea.","Select a task."));return;}a.heading(t("Preparando resultados","Preparing results"),t("Lambda Core conserva esta tarea.","Lambda Core retains this task."));refreshJob(true);}
    private void refreshJob(boolean first){
        if(!viewingJob||!resumed||id==null)return;
        call("get",params("id",id),r->{if(!r.optBoolean("ok")){a.notice(r.optString("detail"));return;}
            JSONObject job=r.getJSONObject("job");boolean active=Arrays.asList("running","queued").contains(job.optString("state"));
            if(first||!active||progress==null)drawJob(job);else progress.setText(t("En curso", "Running")+" · "+Math.max(0,(System.currentTimeMillis()-job.optLong("started_ms",job.optLong("created_ms")))/1000)+" s");
            if(active&&viewingJob&&resumed)main.postDelayed(poll,700);
        });
    }
    private String label(String tool){switch(tool){
        case "wps.oneshot":return "OneShot · WPS";
        case "benchmark.wpa":return "Cracking speed test · WPA/PMKID";
        case "crack.wpa":return "Cracking · WPA";
        case "wifi.connect_verified":return t("Conexión Wi-Fi","Wi-Fi connection");
        case "radio.scan":return t("Exploración en modo monitor","Monitor survey");case "radio.capture":return "Handshake capture";case "radio.deauth":return "Deauth";case "radio.release":return t("Restauración de antena","Adapter restoration");
        case "wifi.scan":return t("Redes cercanas","Nearby networks");case "wifi.cached":return t("Redes en caché","Cached networks");case "wifi.audit":return t("Seguridad Wi-Fi","Wi-Fi security");case "wifi.channels":return t("Mapa de canales","Channel map");case "wifi.link":return t("Mi conexión","My connection");case "wifi.signal":return t("Señal Wi-Fi","Wi-Fi signal");
        case "bluetooth.ble_scan":return t("Bluetooth LE","Bluetooth LE");case "bluetooth.classic_scan":return "Bluetooth Classic";case "bluetooth.audit":return t("Exposición Bluetooth","Bluetooth exposure");case "bluetooth.status":return t("Adaptador Bluetooth","Bluetooth adapter");case "bluetooth.bonded":return t("Dispositivos vinculados","Paired devices");case "bluetooth.gatt":return "Servicios GATT";
        case "usb.inventory":return t("Dispositivos USB","USB devices");case "usb.audit":return t("Superficie USB","USB exposure");case "usb.watch":return t("Conexiones USB","USB connections");case "usb.descriptors":return t("Descriptores USB","USB descriptors");default:return t("Capacidades del sistema","System capabilities");}}
    private String state(String s){switch(s){case "completed":return t("Completada","Completed");case "partial":return t("Resultado parcial","Partial result");case "queued":return t("En cola","Queued");case "running":return t("En curso","Running");case "cancelled":return t("Cancelada","Cancelled");case "interrupted":return t("Interrumpida","Interrupted");case "permission_denied":return t("Permiso pendiente","Permission required");default:return t("No completada","Not completed");}}
    private void drawJob(JSONObject job)throws Exception {
        a.content.removeAllViews();progress=null;String tool=job.getString("tool"),status=job.getString("state");
        a.heading(label(tool),state(status)+"  ·  "+new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date(job.getLong("created_ms"))));
        if(Arrays.asList("queued","running").contains(status)){
            ProgressBar bar=new ProgressBar(a);bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(a.foreground));
            LinearLayout center=a.column();center.setGravity(Gravity.CENTER);center.setPadding(0,a.dp(48),0,a.dp(48));center.addView(bar,new LinearLayout.LayoutParams(a.dp(42),a.dp(42)));a.space(center,24);
            progress=a.text(state(status),18,true,a.foreground);progress.setGravity(Gravity.CENTER);center.addView(progress);a.space(center,12);
            TextView hint=a.text(t("Puedes salir. El servicio sigue trabajando.","You can leave. The service keeps working."),13,false,a.secondary);hint.setGravity(Gravity.CENTER);center.addView(hint);a.content.addView(center);
            a.button(t("Detener tarea","Stop task"),()->call("cancel",params("id",id),r->refreshJob(true)));return;
        }
        if(job.has("error")){a.fact(t("Detalle","Detail"),job.getString("error"));
            if(job.getString("error").startsWith("location_access_not_allowed"))a.button(t("Permitir Wi-Fi en segundo plano","Allow background Wi-Fi"),this::backgroundPermission);
            else a.button(t("Revisar permisos","Review permissions"),this::permissions);
        }
        JSONObject result=job.optJSONObject("result");if(result!=null){
            a.fact(t("Resumen","Summary"),summary(tool,result));
            if(status.equals("partial"))a.fact(t("Aviso","Notice"),tool.equals("benchmark.wpa")?result.optString("stop_reason"):t("No se ha confirmado un escaneo nuevo. Se muestran datos de caché con su antigüedad.","A new scan was not confirmed. Cached observations include their age."));
            if(tool.equals("benchmark.wpa")){
                JSONArray rows=result.optJSONArray("rows");
                if(rows!=null)for(int n=0;n<rows.length();n++){JSONObject row=rows.getJSONObject(n);String test=row.optString("test");
                    if(!test.endsWith("_4800")&&!test.equals("wpa_engine"))continue;
                    double speed=row.optDouble(test.equals("wpa_engine")?"engine_keys_per_second":"effective_keys_per_second",0);
                    a.fact(test, String.format(Locale.getDefault(),"%.2f s · %.0f ",row.optLong("wall_ms")/1000.0,speed)+t("contraseñas/s","passwords/s"));
                }
            }
            if(result.has("networks"))networks(result.getJSONArray("networks"),job.getString("id"));
            if(result.has("channels"))channels(result.getJSONArray("channels"));
            if(result.has("samples"))signal(result);
            if(result.has("devices"))devices(result.getJSONArray("devices"),tool,job.getString("id"));
            if(result.has("findings"))findings(result.getJSONArray("findings"));
            if(tool.equals("wifi.link")){a.fact("SSID",result.optString("ssid"));a.fact(t("Señal / velocidad de enlace","Signal / link speed"),result.optInt("rssi_dbm")+" dBm · "+result.optInt("link_speed_mbps")+" Mbps");a.fact("DNS / IP / rutas",result.optJSONArray("links").toString(2));}
            if(tool.equals("bluetooth.status"))for(String k:new String[]{"enabled","le_2m_phy","le_coded_phy","extended_advertising","offloaded_filtering","bonded_count"})a.fact(k.replace('_',' '),String.valueOf(result.opt(k)));
            if(tool.equals("system.capabilities")){a.fact("Kernel",result.optString("kernel"));a.fact(t("Interfaces","Interfaces"),result.optJSONArray("interfaces").toString(2));a.fact(t("Monitor / inyección","Monitor / injection"),t("No verificados. Se necesita validar firmware y adaptador.","Not verified. Firmware and adapter validation is required."));}
            if(result.has("services"))a.fact("GATT",result.getJSONArray("services").toString(2));
            if(result.has("events"))a.fact(t("Eventos","Events"),result.getJSONArray("events").toString(2));
            if(result.has("descriptors_hex"))a.fact(t("Descriptores (hex)","Descriptors (hex)"),result.getString("descriptors_hex"));
            if(result.has("scope"))a.fact(t("Alcance de la prueba","Test scope"),scope(tool,result));
        }
        a.space(a.content,12);a.fact(t("Tiempo de ejecución","Execution time"),String.format(Locale.getDefault(),"%.2f s",job.optLong("duration_ms")/1000.0));
        a.button(t("Exportar informe JSON","Export JSON report"),()->{exportJson=job.toString();Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"lambda-"+job.optString("id")+".json");a.startActivityForResult(i,83);});
        a.button(t("Ver datos completos","View raw data"),()->{TextView raw=a.text(job.toString(),12,false,a.foreground);raw.setTextIsSelectable(true);raw.setPadding(a.dp(20),a.dp(16),a.dp(20),a.dp(16));ScrollView scroll=new ScrollView(a);scroll.addView(raw);new AlertDialog.Builder(a).setTitle("Core API v1").setView(scroll).setPositiveButton(t("Cerrar","Close"),null).show();});
    }
    private void networks(JSONArray nets,String snapshot)throws Exception {
        a.button(t("Auditar este escaneo","Audit this scan"),()->run("wifi.audit",params("snapshot_id",snapshot)));
        a.button(t("Ver sus canales","View its channels"),()->run("wifi.channels",params("snapshot_id",snapshot)));
        for(int i=0;i<nets.length();i++){JSONObject n=nets.getJSONObject(i);String ssid=n.optString("ssid");
            LinearLayout row=a.column();row.setPadding(a.dp(16),a.dp(15),a.dp(16),a.dp(15));a.card(row,a.surface,12);
            row.addView(a.text(ssid.isEmpty()?t("SSID oculto","Hidden SSID"):ssid,17,true,a.foreground));a.space(row,7);
            row.addView(a.text(n.optString("security")+"  ·  "+n.optString("band")+"  ·  ch "+n.optInt("channel"),13,false,a.secondary));a.space(row,6);
            row.addView(a.text(n.optInt("rssi_dbm")+" dBm  ·  "+n.optString("bssid"),12,false,a.secondary));
            a.space(row,9);row.addView(new Meter(a,n.optInt("rssi_dbm"),-100,-30,a.foreground,a.divider),new LinearLayout.LayoutParams(-1,a.dp(3)));
            a.content.addView(row);a.space(a.content,8);a.touch(row,ssid,()->new AlertDialog.Builder(a).setTitle(n.optString("ssid")).setMessage(n.toString()).setPositiveButton(t("Cerrar","Close"),null).show());
        }if(nets.length()==0)a.fact(t("Sin resultados","No results"),t("No se observaron redes en esta muestra.","No networks were observed in this sample."));
    }
    private void channels(JSONArray rows)throws Exception {int largest=1;for(int i=0;i<rows.length();i++)largest=Math.max(largest,rows.getJSONObject(i).getInt("count"));
        for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);a.fact(r.getString("band")+"  /  "+t("Canal ","Channel ")+r.getInt("channel"),r.getInt("count")+" BSSID · "+r.getInt("strongest_dbm")+" dBm");a.content.addView(new Meter(a,r.getInt("count"),0,largest,a.foreground,a.divider),new LinearLayout.LayoutParams(-1,a.dp(8)));a.space(a.content,20);}}
    private void signal(JSONObject r)throws Exception {a.fact(t("Rango de señal","Signal range"),r.getInt("min_dbm")+" → "+r.getInt("max_dbm")+" dBm");JSONArray samples=r.getJSONArray("samples");
        LinearLayout chart=new LinearLayout(a);chart.setGravity(Gravity.BOTTOM);for(int i=0;i<samples.length();i++){int dbm=samples.getJSONObject(i).getInt("rssi_dbm");View v=new View(a);v.setBackgroundColor(a.foreground);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,a.dp(Math.max(4,dbm+110)),1);p.setMargins(a.dp(2),0,a.dp(2),0);chart.addView(v,p);}a.content.addView(chart,new LinearLayout.LayoutParams(-1,a.dp(110)));a.space(a.content,24);}
    private void devices(JSONArray items,String tool,String snapshot)throws Exception {
        boolean usb=tool.startsWith("usb.");
        if(!usb&&!tool.equals("bluetooth.audit"))a.button(t("Analizar esta muestra","Analyze this sample"),()->run("bluetooth.audit",params("snapshot_id",snapshot)));
        if(items.length()==0)a.fact(t("Sin dispositivos","No devices"),usb?t("Conecta un accesorio OTG y vuelve a inspeccionar.","Attach an OTG accessory and inspect again."):t("No se han observado dispositivos en esta muestra.","No devices were observed in this sample."));
        for(int i=0;i<items.length();i++){
            JSONObject d=items.getJSONObject(i);String name=d.optString("name");if(name.isEmpty()||name.equals("null"))name=usb?"USB":t("Sin nombre","Unnamed");
            String sub=usb?d.optString("vid_pid")+" · "+d.getJSONArray("interfaces").length()+t(" interfaces"," interfaces"):d.optString("address")+(d.has("rssi_dbm")?" · "+d.optInt("rssi_dbm")+" dBm":"");
            final String title=name, detail=d.toString(2);
            action(name,sub,usb?2:1,false,()->{
                AlertDialog.Builder dialog=new AlertDialog.Builder(a).setTitle(title).setMessage(detail).setNegativeButton(t("Cerrar","Close"),null);
                if(tool.equals("bluetooth.ble_scan")&&d.optBoolean("connectable"))dialog.setPositiveButton(t("Inspeccionar GATT","Inspect GATT"),(v,w)->new AlertDialog.Builder(a).setTitle(t("Conectar al dispositivo","Connect to device"))
                    .setMessage(t("Solo para un dispositivo propio o autorizado. Se enumerarán los servicios; no se leerán ni escribirán valores.","Use an owned or authorized device. Services will be enumerated; no values will be read or written."))
                    .setNegativeButton(t("Cancelar","Cancel"),null).setPositiveButton(t("Conectar","Connect"),(x,y)->{JSONObject p=params("snapshot_id",snapshot);try{p.put("address",d.getString("address"));run("bluetooth.gatt",p);}catch(Exception e){a.notice(e.getMessage());}}).show());
                if(usb)dialog.setPositiveButton(t("Descriptores","Descriptors"),(v,w)->usbDescriptors(d,snapshot));dialog.show();
            });
        }
    }
    private void usbDescriptors(JSONObject d,String snapshot){try{
        UsbManager u=a.getSystemService(UsbManager.class);UsbDevice device=u.getDeviceList().get(d.getString("device"));if(device==null){a.notice(t("El accesorio se ha desconectado.","Accessory disconnected."));return;}
        JSONObject p=params("snapshot_id",snapshot).put("device",d.getString("device"));
        if(u.hasPermission(device)){run("usb.descriptors",p);return;}
        String action="org.lambdaproject.lambda.USB_PERMISSION";
        if(usbReceiver!=null){a.notice(t("Hay una solicitud USB pendiente.","A USB permission request is pending."));return;}
        usbReceiver=new BroadcastReceiver(){public void onReceive(Context c,Intent intent){a.unregisterReceiver(this);usbReceiver=null;if(u.hasPermission(device))run("usb.descriptors",p);else a.notice(t("Acceso USB no concedido.","USB access not granted."));}};
        a.registerReceiver(usbReceiver,new IntentFilter(action),Context.RECEIVER_NOT_EXPORTED);
        u.requestPermission(device,PendingIntent.getBroadcast(a,88,new Intent(action).setPackage(a.getPackageName()),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));
    }catch(Exception e){a.notice(e.getMessage());}}
    private void findings(JSONArray items)throws Exception {
        if(items.length()==0)a.fact(t("Observaciones","Observations"),t("Sin observaciones con estas reglas. No equivale a ausencia de vulnerabilidades.","No findings under these rules. This is not proof of no vulnerabilities."));
        for(int i=0;i<items.length();i++){JSONObject f=items.getJSONObject(i);a.fact(findingTitle(f),f.optString("subject")+"\n"+findingDetail(f));a.separator(a.content);a.space(a.content,16);}
    }
    private String summary(String tool,JSONObject r){
        if(tool.equals("benchmark.wpa"))return r.optString("engine")+" · "+t("Datos sintéticos · ","Synthetic inputs · ")+(r.optBoolean("known_answers_passed")?t("respuestas conocidas verificadas","known answers verified"):t("validación incompleta","validation incomplete"));
        if(a.english)return r.optString("summary");
        switch(tool){
            case "wifi.scan":case "wifi.cached":return r.optJSONArray("networks").length()+" puntos de acceso · "+(r.optBoolean("fresh")?"escaneo nuevo":"datos en caché");
            case "wifi.audit":return r.optInt("bssid_count")+" puntos de acceso · "+r.optInt("open_count")+" abiertos · "+r.optInt("legacy_count")+" con seguridad antigua · "+r.optInt("wps_count")+" WPS";
            case "wifi.channels":return r.optJSONArray("channels").length()+" canales primarios observados";
            case "wifi.link":return r.optJSONArray("links").length()==0?"Sin conexión Wi-Fi activa":"Configuración actual de la conexión";
            case "wifi.signal":return r.optJSONArray("samples").length()+" muestras · "+Math.round(r.optDouble("average_dbm"))+" dBm de media";
            case "bluetooth.status":return r.optBoolean("enabled")?"Bluetooth activado":"Bluetooth desactivado";
            case "bluetooth.ble_scan":case "bluetooth.classic_scan":case "bluetooth.bonded":return r.optJSONArray("devices").length()+" dispositivos";
            case "bluetooth.gatt":return r.optJSONArray("services").length()+" servicios GATT";
            case "bluetooth.audit":return r.optJSONArray("findings").length()+" observaciones sobre el descubrimiento";
            case "usb.inventory":case "usb.audit":return r.optJSONArray("devices").length()+" accesorios USB conectados";
            case "usb.watch":return r.optJSONArray("events").length()+" cambios de conexión observados";
            case "usb.descriptors":return r.optInt("bytes")+" bytes de descriptores";
            default:return "Capacidades nativas · Android "+android.os.Build.VERSION.RELEASE;
        }
    }
    private String scope(String tool,JSONObject r){if(a.english)return r.optString("scope");switch(tool){
        case "wifi.audit":return "Análisis de lo que anuncian los puntos de acceso. No realiza autenticación, inyección ni pruebas de contraseñas.";
        case "wifi.channels":return "Cantidad de puntos de acceso por canal primario. No mide ocupación del aire ni interferencias; los anchos de canal pueden solaparse.";
        case "wifi.signal":return "RSSI de la conexión actual informado por Android. No mide velocidad de descarga ni pérdida de paquetes.";
        case "bluetooth.ble_scan":return "Anuncios BLE y respuestas de escaneo. Sin emparejar ni acceder a valores GATT.";
        case "bluetooth.classic_scan":return "Descubrimiento activo de dispositivos visibles. Sin emparejar ni enumerar servicios.";
        case "bluetooth.bonded":return "Vínculos y UUID guardados en el teléfono. No abre conexiones remotas.";
        case "bluetooth.audit":return "Nombres, direcciones y anuncios no demuestran vulnerabilidades ni identidades. Las direcciones BLE pueden rotar.";
        case "bluetooth.gatt":return "Conexión y enumeración de servicios. Sin leer, escribir ni suscribirse a características. Sus propiedades no demuestran el nivel de autenticación.";
        case "usb.inventory":case "usb.audit":return "Periféricos conectados al S10 mediante OTG. El PC que proporciona ADB no aparece en este inventario.";
        case "usb.descriptors":return "Descriptores estándar almacenados. No reclama interfaces ni envía transferencias de control, datos o HID.";
        default:return "Se utilizan APIs Android con permisos concedidos. La configuración del kernel no demuestra por sí sola el funcionamiento de un adaptador.";
    }}
    private String findingTitle(JSONObject f){if(a.english)return f.optString("code");switch(f.optString("code")){
        case "open_network":return "Red abierta";case "legacy_security":return "Seguridad antigua";case "tkip_advertised":return "TKIP anunciado";case "wps_advertised":return "WPS anunciado";case "mixed_security_ssid":return "Distintos cifrados para el mismo SSID";
        case "advertised_name":return "Nombre visible";case "connectable_advertisement":return "Conexión anunciada";case "hid_interface":return "Interfaz HID";case "mass_storage":return "Almacenamiento USB";case "composite_device":return "Dispositivo compuesto";case "adb_setting":return "Depuración USB";default:return f.optString("code");}}
    private String findingDetail(JSONObject f){if(a.english)return f.optString("detail");switch(f.optString("code")){
        case "open_network":return "No anuncia cifrado del enlace. No se ha realizado ninguna captura de tráfico.";
        case "legacy_security":return "Anuncia WEP o WPA antiguo, posiblemente junto a WPA2. Revisa la configuración del punto de acceso.";
        case "tkip_advertised":return "Anuncia TKIP. En tu punto de acceso, utiliza AES/CCMP si es compatible.";
        case "wps_advertised":return "Anuncia WPS. No se ha intentado ningún PIN ni comprobado una vulnerabilidad.";
        case "mixed_security_ssid":return "Puede ser una configuración legítima. No demuestra la presencia de un punto de acceso falso.";
        case "advertised_name":return "El nombre es observable durante el descubrimiento. No garantiza la identidad del dispositivo.";
        case "connectable_advertisement":return "Anuncia que admite conexión. No se han probado sus permisos ni autenticación.";
        case "hid_interface":return "Puede proporcionar entrada HID. Su presencia no demuestra actividad maliciosa.";
        case "mass_storage":return "Interfaz de almacenamiento detectada. No se ha abierto ni modificado su sistema de archivos.";
        case "composite_device":return "Presenta distintas clases de interfaces. Comprueba que correspondan al accesorio esperado.";
        case "adb_setting":return "Estado leído de Android. Consulta los datos completos para el valor; no enumera hosts autorizados.";
        default:return f.optString("detail");}}
    void sessions(){a.heading(t("Sesiones","Sessions"),t("Trabajos persistentes. Datos solo en este dispositivo.","Persistent jobs. Data stays on this device."));
        call("list",obj(),r->{JSONArray jobs=r.optJSONArray("jobs");if(jobs==null){a.notice(r.optString("detail"));return;}if(jobs.length()==0)a.fact(t("Todo preparado","Ready"),t("Inicia una tarea desde Wi-Fi, Bluetooth o USB.","Start a task from Wi-Fi, Bluetooth or USB."));
            for(int i=0;i<jobs.length();i++){JSONObject j=jobs.getJSONObject(i);String date=new SimpleDateFormat("HH:mm · dd MMM",Locale.getDefault()).format(new Date(j.getLong("created_ms")));
                action(label(j.getString("tool")),state(j.getString("state"))+" · "+date,8,false,()->{id=j.optString("id");a.getPreferences(0).edit().putString("last_core_job",id).apply();if(j.optString("tool").equals("wps.oneshot")){a.wps.openJob(id);}else if(j.optString("tool").equals("crack.wpa")){a.cracking.openJob(id);}else if(j.optString("tool").startsWith("radio.")&&!j.optString("tool").equals("radio.release")){a.handshake.openJob(id,j.optString("tool"));}else a.navigate("job");});}});
    }
    boolean onActivityResult(int request,int result,Intent data){if(request!=83)return false;if(result!=Activity.RESULT_OK||data==null||data.getData()==null||exportJson==null)return true;
        try(OutputStream out=a.getContentResolver().openOutputStream(data.getData())){if(out==null)throw new java.io.IOException();out.write(exportJson.getBytes(StandardCharsets.UTF_8));a.notice(t("Informe exportado.","Report exported."));}catch(Exception e){a.notice(t("No se pudo exportar.","Export failed."));}return true;}
    private static final class Meter extends View {
        private final float value;private final int fg,bg;private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Meter(Context c,float n,float min,float max,int fg,int bg){super(c);value=Math.max(0,Math.min(1,(n-min)/(max-min)));this.fg=fg;this.bg=bg;}
        @Override protected void onDraw(Canvas c){p.setColor(bg);c.drawRoundRect(0,0,getWidth(),getHeight(),getHeight()/2f,getHeight()/2f,p);p.setColor(fg);c.drawRoundRect(0,0,getWidth()*value,getHeight(),getHeight()/2f,getHeight()/2f,p);}
    }
}
