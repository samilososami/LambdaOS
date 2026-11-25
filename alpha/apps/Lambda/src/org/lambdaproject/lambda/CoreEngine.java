package org.lambdaproject.lambda;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CancellationException;

/** One durable queue in :core, independent of the Activity. All tools are bounded. */
final class CoreEngine {
    private static CoreEngine instance;
    static synchronized CoreEngine get(Context c) { if(instance==null)instance=new CoreEngine(c.getApplicationContext()); return instance; }
    static final String[] TOOLS={"wifi.scan","wifi.cached","wifi.audit","wifi.channels","wifi.link","wifi.signal",
        "bluetooth.status","bluetooth.ble_scan","bluetooth.classic_scan","bluetooth.bonded","bluetooth.audit","bluetooth.gatt",
        "usb.inventory","usb.audit","usb.watch","usb.descriptors","system.capabilities","radio.wifi_on","radio.scan","radio.capture","radio.deauth","radio.ap","radio.release","benchmark.wpa","crack.wpa","wifi.connect_verified","wps.oneshot","system.cooling","system.heater","system.desktop"};
    final Context context;
    private final SQLiteDatabase db;
    private JSONObject desktop;
    private int desktopMisses;
    // Web dashboard consent: host names approved once live in memory, "Permitir
    // siempre" persists; the real transport boundary is still USB debugging.
    private final java.util.Set<String> trustedHosts=new java.util.HashSet<>();
    private final java.util.Map<String,String> connectStates=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String,Long> connectSince=new java.util.concurrent.ConcurrentHashMap<>();
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<String,Job> running=new ConcurrentHashMap<>();
    private CoreEngine(Context c) {
        context=c;
        SQLiteOpenHelper helper=new SQLiteOpenHelper(c,"lambda-core.db",null,1){
            public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE jobs (id TEXT PRIMARY KEY, tool TEXT NOT NULL, state TEXT NOT NULL, record TEXT NOT NULL, created INTEGER NOT NULL)");}
            public void onUpgrade(SQLiteDatabase d,int a,int b){throw new IllegalStateException("Explicit migration required");}
        };
        db=helper.getWritableDatabase();
        try{
            String savedTrust=c.getSharedPreferences("core",Context.MODE_PRIVATE).getString("trusted_hosts","[]");
            JSONArray hosts=new JSONArray(savedTrust);
            for(int i=0;i<hosts.length();i++)trustedHosts.add(hosts.getString(i));
        }catch(Exception ignored){}
        // The LEX desktop outlives its start job; its state feeds the notification.
        try{
            String saved=c.getSharedPreferences("core",Context.MODE_PRIVATE).getString("desktop",null);
            if(saved!=null&&!saved.isEmpty()){
                JSONObject d=new JSONObject(saved);
                if(desktopReachable(d))desktop=d;
                else c.getSharedPreferences("core",Context.MODE_PRIVATE).edit().remove("desktop").apply();
            }
        }catch(Exception ignored){}
        // Never silently replay radio work after a process/boot interruption.
        try(Cursor rows=db.query("jobs",new String[]{"record"},"state IN ('queued','running')",null,null,null,null)) {
            while(rows.moveToNext()) {
                JSONObject r=new JSONObject(rows.getString(0));
                r.put("state","interrupted").put("error","core_restarted").put("finished_ms",System.currentTimeMillis()); save(r);
            }
        } catch(Exception e){throw new IllegalStateException(e);}
    }
    private static boolean portOpen(int port){
        try(java.net.Socket s=new java.net.Socket()){s.connect(new java.net.InetSocketAddress("127.0.0.1",port),250);return true;}
        catch(Exception e){return false;}
    }
    /** A single 250 ms connect can time out while Xvnc/websockify are busy
     *  accepting the viewer; retry before declaring the desktop dead. */
    private static boolean desktopReachable(JSONObject d){
        if(d==null)return false;
        int vnc=d.optInt("vnc_port",5901),web=d.optInt("web_port",6080);
        for(int attempt=0;attempt<3;attempt++){
            if(portOpen(vnc)&&portOpen(web))return true;
            try{Thread.sleep(120);}catch(Exception ignored){}
        }
        return false;
    }
    /** A start job may publish the desktop while the just-started sockets are
     *  still warming up; tolerate one missed check before clearing the state. */
    private boolean desktopCheck(){
        if(desktop==null){desktopMisses=0;return false;}
        if(desktopReachable(desktop)){desktopMisses=0;return true;}
        desktopMisses++;
        return desktopMisses<2;
    }
    private void saveDesktop(){
        android.content.SharedPreferences.Editor e=context.getSharedPreferences("core",Context.MODE_PRIVATE).edit();
        if(desktop==null)e.remove("desktop");else e.putString("desktop",desktop.toString());
        e.apply();
    }
    static JSONObject error(Exception e) {
        JSONObject r=new JSONObject();
        try {r.put("ok",false).put("error",e instanceof SecurityException?"permission_denied":e instanceof IllegalArgumentException?"invalid_request":"operation_failed")
            .put("detail",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}catch(Exception ignored){}
        return r;
    }
    static void keys(JSONObject r,String... allowed) {
        Iterator<String> keys=r.keys(); while(keys.hasNext()){String k=keys.next(); if(!Arrays.asList(allowed).contains(k))throw new IllegalArgumentException("unknown_field: "+k);}
    }
    static int duration(JSONObject r,int fallback) {
        if(!r.has("seconds"))return fallback;
        Object n=r.opt("seconds");
        if(!(n instanceof Integer) || (Integer)n<3 || (Integer)n>30)throw new IllegalArgumentException("seconds must be an integer in [3,30]");
        return (Integer)n;
    }
    static boolean bool(JSONObject r,String key,boolean fallback) {
        Object v=r.opt(key); if(v==null)return fallback; if(!(v instanceof Boolean))throw new IllegalArgumentException(key+" must be boolean");return (Boolean)v;
    }
    static String id(JSONObject r,String key) {
        Object v=r.opt(key);if(!(v instanceof String)||!((String)v).matches("[a-zA-Z0-9_-]{1,80}"))throw new IllegalArgumentException("invalid "+key);return (String)v;
    }
    synchronized JSONObject rpc(String method,JSONObject r,int uid)throws Exception {
        switch(method) {
            case "crack_dictionaries": keys(r);return new JSONObject().put("ok",true).put("dictionaries",CrackStore.list(context)).put("hybrid_available",new java.io.File(CrackBenchmark.BASE,"pmk-hybrid-worker").canExecute());            case "radio_status": keys(r);return RadioBridge.status();
            case "desktop_status": {
                keys(r);
                if(desktop!=null&&!desktopCheck()){desktop=null;desktopMisses=0;saveDesktop();CoreService.update();}
                return new JSONObject().put("ok",true).put("running",desktop!=null)
                    .put("desktop",desktop==null?JSONObject.NULL:new JSONObject(desktop.toString()));
            }
            case "radio_worker": return RadioBridge.worker(r,uid,this);
            case "connect": {
                keys(r,"host");
                String host=hostName(r.getString("host"));
                if(isTrusted(host))return new JSONObject().put("ok",true).put("state","trusted").put("host",host).put("trusted",true);
                String state=connectStates.get(host);
                long since=connectSince.getOrDefault(host,0L);
                long now=System.currentTimeMillis();
                if("pending".equals(state)&&now-since>120000){
                    // A prompt the user never saw expires so the next attempt alerts again.
                    connectStates.remove(host);connectSince.remove(host);state=null;
                }
                if(state==null||"pending".equals(state)){
                    connectStates.put(host,"pending");connectSince.put(host,now);
                    // The prompt is a notification; the service may be restarted
                    // by its own action, so no startForegroundService per poll.
                    CoreService.showConnectPrompt(context,host);
                    state="pending";
                }
                return new JSONObject().put("ok",true).put("state",state).put("host",host).put("trusted",false);
            }
            case "connect_status": {
                keys(r,"host");
                String host=hostName(r.getString("host"));
                String state=connectStates.get(host);
                boolean trusted=isTrusted(host);
                return new JSONObject().put("ok",true).put("host",host).put("trusted",trusted)
                    .put("state",trusted?"trusted":state==null?"none":state);
            }
            case "connect_forget": {
                keys(r,"host");
                String host=hostName(r.getString("host"));
                boolean removed=forgetTrusted(host);
                connectStates.remove(host);connectSince.remove(host);
                CoreService.cancelConnectPrompt(context,host);
                return new JSONObject().put("ok",true).put("host",host).put("removed",removed);
            }
            case "capture_deauth": {
                keys(r,"id","request_id","authorized");
                String captureId=id(r,"id"),requestId=id(r,"request_id");
                if(!bool(r,"authorized",false))throw new IllegalArgumentException("target_authorization_required");
                Job job=running.get(captureId);
                if(job==null||!job.tool().equals("radio.capture"))throw new IllegalStateException("capture_not_active");
                JSONObject previous=job.record.optJSONObject("deauth_request");
                if(previous!=null){
                    if(!previous.getString("id").equals(requestId)||previous.getInt("caller_uid")!=uid)
                        throw new IllegalStateException("capture_deauth_already_requested");
                    return new JSONObject().put("ok",true).put("replayed",true).put("request",previous);
                }
                synchronized(RadioBridge.class){
                    RadioBridge.checkDeauth(job);
                    JSONObject audit=new JSONObject().put("id",requestId).put("capture_id",captureId).put("caller_uid",uid)
                        .put("bssid",job.args().getString("bssid")).put("requested_ms",System.currentTimeMillis()).put("cycles",1);
                    // Persist intent before the root worker can claim it.
                    job.record.put("deauth_request",audit);save(job.record);
                    RadioBridge.requestDeauth(job,audit);
                    return new JSONObject().put("ok",true).put("replayed",false).put("request",audit);
                }
            }
            case "ap_control": {
                keys(r,"id","request_id","action","mac","authorized");
                String apJobId=id(r,"id"),requestId=id(r,"request_id");
                String action=r.getString("action");
                if(!Arrays.asList("kick","ban","unban").contains(action))throw new IllegalArgumentException("invalid_action");
                String mac=r.getString("mac");
                if(!mac.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")||(Integer.parseInt(mac.substring(0,2),16)&1)!=0||mac.equals("00:00:00:00:00:00"))throw new IllegalArgumentException("invalid_mac");
                if(!bool(r,"authorized",false))throw new IllegalArgumentException("target_authorization_required");
                Job job=running.get(apJobId);
                if(job==null||!job.tool().equals("radio.ap"))throw new IllegalStateException("ap_not_active");
                synchronized(RadioBridge.class){
                    RadioBridge.checkApControl(job);
                    JSONObject request=new JSONObject().put("id",requestId).put("action",action).put("mac",mac.toLowerCase(Locale.US))
                        .put("caller_uid",uid).put("requested_ms",System.currentTimeMillis())
                        .put("expires_elapsed_ms",SystemClock.elapsedRealtime()+6000);
                    RadioBridge.requestApControl(job,request);
                    return new JSONObject().put("ok",true).put("replayed",false).put("request",request);
                }
            }
            case "catalog": keys(r); return catalog();
            case "status":
                keys(r);return new JSONObject().put("ok",true).put("schema_version",1).put("version","0.2.0-operator.34")
                    .put("pid",android.os.Process.myPid()).put("active",running.size()).put("tools",new JSONArray(Arrays.asList(TOOLS)))
                    .put("privilege","Android API + explicit allowlisted maintenance root worker; no arbitrary shell").put("storage","private SQLite; recent 200 jobs in list; receipts retained");
            case "list": {
                keys(r,"include_hidden"); boolean hidden=bool(r,"include_hidden",false); JSONArray list=new JSONArray();
                try(Cursor rows=db.query("jobs",new String[]{"record"},null,null,null,null,"created DESC","200")){
                    while(rows.moveToNext()) {JSONObject j=new JSONObject(rows.getString(0));if(!hidden&&j.optBoolean("hidden"))continue;
                        j.remove("result");list.put(j);}
                } return new JSONObject().put("ok",true).put("jobs",list);
            }
            case "get": keys(r,"id");return new JSONObject().put("ok",true).put("job",load(id(r,"id")));
            case "cancel": {keys(r,"id");String id=id(r,"id");JSONObject record=load(id);Job job=running.get(id);
                if(job!=null){job.cancelled=true;record=job.record;record.put("cancel_requested",true);save(record);}
                return new JSONObject().put("ok",true).put("job",record);}
            case "submit": return submit(r,uid);
            default: throw new IllegalArgumentException("unknown_method");
        }
    }
    private JSONObject catalog()throws Exception {
        JSONArray entries=new JSONArray();
        for(String tool:TOOLS){
            JSONObject properties=new JSONObject();JSONArray required=new JSONArray();String effect="read_local_state";
            if(tool.equals("wifi.connect_verified")){properties.put("result_id",new JSONObject().put("type","string"));required.put("result_id");effect="enable_wifi_save_and_connect_verified_target";}
            if(tool.equals("radio.scan")){properties.put("adapter",new JSONObject().put("type","string"));required.put("adapter");effect="temporary_monitor_passive_survey";}
            if(tool.equals("radio.capture")||tool.equals("radio.deauth")){for(String k:new String[]{"session","bssid"}){properties.put(k,new JSONObject().put("type","string"));required.put(k);}properties.put("authorized",new JSONObject().put("type","boolean").put("description","Target-specific user consent recorded by Operator"));required.put("authorized");effect=tool.equals("radio.deauth")?"target_deauthentication_single_cycle_max_10s":"passive_target_eapol_capture_180s";}
            if(tool.equals("radio.ap")){
                for(String k:new String[]{"session","bssid"}){properties.put(k,new JSONObject().put("type","string"));}
                properties.put("snapshot_id",new JSONObject().put("type","string").put("description","A wifi.scan/wifi.cached snapshot containing the target; native scanner, no monitor mode"));
                properties.put("authorized",new JSONObject().put("type","boolean").put("description","Target-specific user consent recorded by Operator"));required.put("authorized");
                properties.put("mode",new JSONObject().put("type","string").put("enum",new JSONArray(Arrays.asList("clone","manual"))).put("description","manual creates the AP from the given fields without a survey"));
                properties.put("ssid",new JSONObject().put("type","string"));
                properties.put("security",new JSONObject().put("type","string").put("enum",new JSONArray(Arrays.asList("open","wpa2","wpa3","mixed"))));
                properties.put("passphrase",new JSONObject().put("type","string").put("description","Empty with security=open; 8-63 printable characters otherwise"));
                properties.put("channel",new JSONObject().put("type","integer").put("minimum",1).put("maximum",13).put("description","Absent = random channel"));
                properties.put("seconds",new JSONObject().put("type","integer").put("minimum",30).put("maximum",86400).put("description","Absent = until the user stops the AP"));
                properties.put("sniff",new JSONObject().put("type","boolean").put("description","Capture AP-side traffic to a bounded pcap"));
                properties.put("portal",new JSONObject().put("type","boolean").put("description","Captive portal: DNS answers with the AP address and an HTTP portal records form submissions"));
                properties.put("portal_template",new JSONObject().put("type","string").put("enum",new JSONArray(Arrays.asList("google","router","simple"))).put("description","Captive portal HTML template"));
                properties.put("upstream",new JSONObject().put("type","boolean").put("description","NAT the current upstream route (STA or mobile data) to the AP"));
                effect="open_ap_local_dhcp_dns_optional_upstream_sniff_until_stopped";
            }
            if(tool.equals("radio.release"))effect="restore_radio";
            if(tool.equals("wps.oneshot")){
                for(String k:new String[]{"snapshot_id","bssid","mode"}){properties.put(k,new JSONObject().put("type","string"));required.put(k);}
                properties.getJSONObject("mode").put("enum",new JSONArray(Arrays.asList("pixie","pin","pbc","bruteforce")));
                properties.put("max_attempts",new JSONObject().put("type","integer").put("enum",new JSONArray(Arrays.asList(10,50,100))));
                properties.put("start_prefix",new JSONObject().put("type","string").put("pattern","^[0-9]{4}$"));
                properties.put("pin",new JSONObject().put("type","string").put("pattern","^[0-9]{8}$"));
                properties.put("authorized",new JSONObject().put("type","boolean"));required.put("authorized");effect="target_wps_exchange_wifi_pause_120s_or_bounded_pin_batch_600s";
            }
            if(tool.equals("benchmark.wpa")){
                properties.put("engine",new JSONObject().put("type","string").put("enum",new JSONArray(Arrays.asList("aircrack","hashcat"))));
                properties.put("threads",new JSONObject().put("type","integer").put("enum",new JSONArray(Arrays.asList(1,4,8))));
                required.put("engine").put("threads");effect="exclusive_offline_synthetic_benchmark";
            }
            if(tool.equals("crack.wpa")){
                properties.put("capture_id",new JSONObject().put("type","string"));properties.put("dictionary_id",new JSONObject().put("type","string"));
                properties.put("engine",new JSONObject().put("type","string").put("enum",new JSONArray(Arrays.asList("auto","cpu","hybrid"))));
                properties.put("authorized",new JSONObject().put("type","boolean"));required.put("capture_id").put("dictionary_id").put("authorized");effect="offline_dictionary_check_exclusive_max_1h";
            }
            if(tool.equals("system.desktop")){
                properties.put("action",new JSONObject().put("type","string").put("enum",new JSONArray(Arrays.asList("start","stop"))).put("description","Start or stop the local LEX XFCE desktop"));
                required.put("action");effect="local_xfce_desktop_start_stop";
            }
            switch(tool){
                case "wifi.audit":case "wifi.channels":case "bluetooth.audit":case "bluetooth.gatt":case "usb.descriptors":
                    properties.put("snapshot_id",new JSONObject().put("type","string").put("pattern","^[a-zA-Z0-9_-]{1,80}$"));required.put("snapshot_id");break;
            }
            if(tool.equals("bluetooth.gatt")){properties.put("address",new JSONObject().put("type","string").put("description","Connectable BLE address in a snapshot less than 5 minutes old"));required.put("address");effect="connect_and_discover_services";}
            if(tool.equals("usb.descriptors")){properties.put("device",new JSONObject().put("type","string").put("description","USB device name from the snapshot; Android USB permission required"));required.put("device");effect="open_usb_read_cached_descriptors";}
            if(Arrays.asList("wifi.signal","bluetooth.ble_scan","bluetooth.classic_scan","usb.watch").contains(tool))properties.put("seconds",new JSONObject().put("type","integer").put("minimum",3).put("maximum",30));
            if(Arrays.asList("wifi.scan","bluetooth.ble_scan","bluetooth.classic_scan").contains(tool))effect="radio_discovery";
            JSONObject schema=new JSONObject().put("type","object").put("properties",properties).put("required",required).put("additionalProperties",false);
            entries.put(new JSONObject().put("name",tool).put("version",1).put("effect",effect).put("arguments",schema));
        }
        return new JSONObject().put("ok",true).put("schema_version",1).put("tools",entries);
    }
    private JSONObject submit(JSONObject r,int uid)throws Exception {
        keys(r,"tool","args","request_id","hidden");
        String tool=r.getString("tool");if(!Arrays.asList(TOOLS).contains(tool))throw new IllegalArgumentException("unknown_tool");
        JSONObject a=r.has("args")?r.getJSONObject("args"):new JSONObject();
        switch(tool){
            case "wps.oneshot":WpsJob.validate(a);break;
            case "system.cooling": keys(a,"mode","target_c");
                if(!Arrays.asList("smart","ultra").contains(a.optString("mode","smart")))throw new IllegalArgumentException("invalid cooling mode");
                if(a.has("target_c")&&(!(a.opt("target_c") instanceof Integer)||(Integer)a.opt("target_c")<35||(Integer)a.opt("target_c")>60))throw new IllegalArgumentException("target_c must be in [35,60]");
                break;
            case "system.heater": keys(a,"target_c","hold_seconds");
                if(a.has("target_c")&&(!(a.opt("target_c") instanceof Integer)||(Integer)a.opt("target_c")<40||(Integer)a.opt("target_c")>70))throw new IllegalArgumentException("target_c must be in [40,70]");
                if(a.has("hold_seconds")&&(!(a.opt("hold_seconds") instanceof Integer)||(Integer)a.opt("hold_seconds")<30||(Integer)a.opt("hold_seconds")>300))throw new IllegalArgumentException("hold_seconds must be in [30,300]");
                break;
            case "system.desktop": keys(a,"action");
                if(!Arrays.asList("start","stop").contains(a.optString("action","")))throw new IllegalArgumentException("invalid desktop action");
                break;
            case "wifi.connect_verified":keys(a,"result_id");id(a,"result_id");break;
            case "crack.wpa":CrackJob.validate(a);break;
            case "benchmark.wpa":CrackBenchmark.validate(a);break;
            case "radio.scan":keys(a,"adapter");if(!(a.opt("adapter") instanceof String)||a.getString("adapter").length()>180)throw new IllegalArgumentException("invalid_adapter");break;
            case "radio.capture":case "radio.deauth":keys(a,"session","bssid","authorized");id(a,"session");String bssid=a.getString("bssid");if(!bssid.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")||(Integer.parseInt(bssid.substring(0,2),16)&1)!=0||bssid.equals("00:00:00:00:00:00")||!bool(a,"authorized",false))throw new IllegalArgumentException("target_authorization_required");break;
            case "radio.ap": {
                String mode=a.optString("mode","clone");
                if(mode.equals("manual")){
                    keys(a,"mode","ssid","passphrase","security","channel","authorized","seconds","sniff","portal","portal_template","upstream","ntlm");
                    String ssid=a.optString("ssid","");
                    if(ssid.isEmpty()||ssid.indexOf('\n')>=0||ssid.indexOf('\0')>=0||ssid.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32)throw new IllegalArgumentException("invalid_ssid");
                    if(a.has("channel")&&(!(a.opt("channel") instanceof Integer)||(Integer)a.opt("channel")<1||(Integer)a.opt("channel")>13))throw new IllegalArgumentException("channel must be an integer in [1,13]");
                    String security=a.optString("security","open");
                    if(!Arrays.asList("open","wpa2","wpa3","mixed").contains(security))throw new IllegalArgumentException("invalid_security");
                    String pass=a.optString("passphrase","");
                    if(security.equals("open")){if(!pass.isEmpty())throw new IllegalArgumentException("open_network_has_no_passphrase");}
                    else if(!pass.matches("[\\x20-\\x7e]{8,63}"))throw new IllegalArgumentException("passphrase must be 8-63 printable characters");
                    if(!bool(a,"authorized",false))throw new IllegalArgumentException("target_authorization_required");
                }else if(a.has("snapshot_id")){
                    // Native Android scanner: no monitor mode, the STA link stays up.
                    keys(a,"snapshot_id","bssid","authorized","seconds","sniff","portal","portal_template","upstream","ntlm");
                    String targetBssid=a.getString("bssid").toLowerCase(Locale.US);
                    if(!targetBssid.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")||(Integer.parseInt(targetBssid.substring(0,2),16)&1)!=0||targetBssid.equals("00:00:00:00:00:00")||!bool(a,"authorized",false))throw new IllegalArgumentException("target_authorization_required");
                }else{
                    keys(a,"session","bssid","authorized","seconds","sniff","portal","portal_template","upstream","ntlm");id(a,"session");
                    String targetBssid=a.getString("bssid");
                    if(!targetBssid.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")||(Integer.parseInt(targetBssid.substring(0,2),16)&1)!=0||targetBssid.equals("00:00:00:00:00:00")||!bool(a,"authorized",false))throw new IllegalArgumentException("target_authorization_required");
                }
                if(a.has("seconds")&&(!(a.opt("seconds") instanceof Integer)||(Integer)a.opt("seconds")<30||(Integer)a.opt("seconds")>86400))throw new IllegalArgumentException("seconds must be an integer in [30,86400] or absent");
                bool(a,"sniff",true);bool(a,"portal",true);bool(a,"upstream",true);bool(a,"ntlm",false);
                if(a.has("portal_template")&&(!(a.opt("portal_template") instanceof String)||!a.getString("portal_template").matches("[a-z0-9_-]{1,20}")))throw new IllegalArgumentException("invalid_portal_template");break;
            }
            case "wifi.audit": case "wifi.channels": case "bluetooth.audit": keys(a,"snapshot_id");id(a,"snapshot_id");break;
            case "bluetooth.gatt": keys(a,"snapshot_id","address");id(a,"snapshot_id");if(!a.getString("address").matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))throw new IllegalArgumentException("invalid_address");break;
            case "usb.descriptors": keys(a,"snapshot_id","device");id(a,"snapshot_id");if(!(a.opt("device") instanceof String)||a.getString("device").length()>160)throw new IllegalArgumentException("invalid_device");break;
            case "wifi.signal": case "bluetooth.ble_scan": case "bluetooth.classic_scan": case "usb.watch":keys(a,"seconds");duration(a,12);break;
            default: keys(a);
        }
        String requestId=r.has("request_id")?id(r,"request_id"):UUID.randomUUID().toString();
        boolean hidden=bool(r,"hidden",false);
        JSONObject previous=optionalLoad(requestId);
        if(previous!=null) {
            if(!previous.getString("tool").equals(tool)||!canonical(previous.getJSONObject("args")).equals(canonical(a))
                ||previous.getInt("caller_uid")!=uid||previous.optBoolean("hidden")!=hidden)throw new IllegalArgumentException("request_id_conflict");
            return new JSONObject().put("ok",true).put("replayed",true).put("job",previous);
        }
        // Desktop start/stop use the single worker slot; do not queue them
        // behind an unbounded radio task where the UI would time out first.
        if(tool.equals("system.desktop"))
            for(Job live:running.values())
                if(!live.tool().equals("system.desktop"))throw new IllegalStateException("worker_busy: wait for "+live.tool());
        if(running.size()>=8)throw new IllegalArgumentException("queue_full");
        if(tool.equals("system.heater")&&!running.isEmpty())throw new IllegalStateException("heater_requires_idle_core");
        if(tool.equals("wps.oneshot")&&!running.isEmpty())throw new IllegalStateException("wps_requires_idle_core");
        for(Job live:running.values())if(live.tool().equals("wps.oneshot"))throw new IllegalStateException("wps_exclusive_lease");
        if(tool.equals("wifi.connect_verified")&&!running.isEmpty())throw new IllegalStateException("connection_requires_idle_core");
        for(Job live:running.values())if(live.tool().equals("wifi.connect_verified"))throw new IllegalStateException("connection_in_progress");
        if((tool.equals("benchmark.wpa")||tool.equals("crack.wpa"))&&!running.isEmpty())throw new IllegalStateException("benchmark_requires_idle_core");
        for(Job live:running.values())if(live.tool().equals("benchmark.wpa")||live.tool().equals("crack.wpa"))throw new IllegalStateException("benchmark_exclusive_lease");
        // One Wi-Fi task at a time: the radio tab shares a single radio and a
        // second job would silently queue behind an unbounded AP. Other
        // families (USB, Bluetooth) stay free for the future AP+HID combo.
        boolean wifiGroup=tool.startsWith("radio.")||tool.startsWith("wifi.");
        if(wifiGroup)for(Job live:running.values())if(live.tool().startsWith("radio.")||live.tool().startsWith("wifi."))throw new IllegalStateException("wifi_task_in_progress");
        if(context.getDatabasePath("lambda-core.db").length()>50L*1024*1024)throw new IllegalStateException("audit_storage_full: export reports before maintenance");
        CoreService.ensure(context);
        JSONObject record=new JSONObject().put("schema_version",1).put("id",requestId).put("tool",tool).put("args",a)
            .put("caller_uid",uid).put("hidden",hidden).put("state","queued").put("created_ms",System.currentTimeMillis());
        Job job=new Job(record);if(tool.equals("wps.oneshot"))job.workerInput=WpsJob.input(a,load(a.getString("snapshot_id")),System.currentTimeMillis());if(tool.equals("crack.wpa"))job.workerInput=crackInput(a);if(tool.equals("wifi.connect_verified"))job.workerInput=connectionInput(a);if(tool.equals("radio.ap")&&a.has("snapshot_id"))job.workerInput=apSnapshotInput(a);save(record);running.put(requestId,job);executor.execute(()->execute(job));
        return new JSONObject().put("ok",true).put("replayed",false).put("job",new JSONObject(record.toString()));
    }
    static String canonical(JSONObject a)throws Exception {
        java.util.TreeMap<String,Object> sorted=new java.util.TreeMap<>();Iterator<String> it=a.keys();while(it.hasNext()){String k=it.next();sorted.put(k,a.get(k));}return new JSONObject(sorted).toString();
    }
    private JSONObject connectionInput(JSONObject a)throws Exception {
        // Only private, verified receipts supply credentials. The caller cannot
        // substitute a password, SSID or BSSID, nor connect a benchmark fixture.
        JSONObject receipt=load(a.getString("result_id")),capture=receipt;
        if(!receipt.optString("state").equals("completed"))throw new IllegalArgumentException("verified_result_required");
        JSONObject result=receipt.optJSONObject("result"),verified;
        if(receipt.optString("tool").equals("radio.capture"))verified=result==null?null:result.optJSONObject("quick_crack");
        else if(receipt.optString("tool").equals("crack.wpa")){
            verified=result;capture=load(receipt.getJSONObject("args").getString("capture_id"));
        }else throw new IllegalArgumentException("verified_result_required");
        JSONObject source=capture.optJSONObject("result");
        if(verified==null||!verified.optString("outcome").equals("found")||!verified.optBoolean("cryptographic_validation")
                ||verified.optBoolean("synthetic")||!capture.optString("tool").equals("radio.capture")
                ||!capture.getJSONObject("args").optBoolean("authorized")||source==null||source.optBoolean("synthetic")
                ||!source.optBoolean("restored")||!source.optBoolean("handshake_detected"))throw new IllegalArgumentException("verified_result_required");
        JSONObject target=source.getJSONObject("target"),checked=verified.getJSONObject("target");
        String ssid=target.getString("ssid"),bssid=target.getString("bssid"),password=verified.getString("password");
        if(!ssid.equals(checked.optString("ssid"))||!bssid.equalsIgnoreCase(checked.optString("bssid"))
                ||!bssid.equalsIgnoreCase(capture.getJSONObject("args").optString("bssid"))
                ||!bssid.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
                ||ssid.isEmpty()||ssid.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32||ssid.indexOf('\0')>=0
                ||!password.matches("[\\x20-\\x7e]{8,63}"))throw new IllegalArgumentException("invalid_verified_target");
        return new JSONObject().put("ssid",ssid).put("bssid",bssid).put("password",password);
    }
    /** Native-scan clone input: only a completed wifi scan snapshot and a BSSID
     *  it actually contains can select the target; nothing caller-invented. */
    private JSONObject apSnapshotInput(JSONObject a)throws Exception {
        JSONObject snap=load(a.getString("snapshot_id"));
        if(!Arrays.asList("completed","partial").contains(snap.optString("state"))||!Arrays.asList("wifi.scan","wifi.cached").contains(snap.optString("tool")))throw new IllegalArgumentException("invalid_snapshot");
        JSONObject found=snap.optJSONObject("result");JSONArray nets=found==null?null:found.optJSONArray("networks");if(nets==null)nets=new JSONArray();
        String bssid=a.getString("bssid").toLowerCase(Locale.US);JSONObject chosen=null;
        for(int i=0;i<nets.length();i++){JSONObject n=nets.optJSONObject(i);if(n!=null&&n.optString("bssid").equalsIgnoreCase(bssid)){chosen=n;break;}}
        if(chosen==null)throw new IllegalArgumentException("network_not_in_snapshot");
        String ssid=chosen.optString("ssid");int ch=chosen.optInt("channel",0);
        if(ssid.isEmpty()||ssid.indexOf('\n')>=0||ssid.indexOf('\0')>=0||ssid.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32)throw new IllegalArgumentException("invalid_ssid");
        if(ch<1||ch>13)throw new IllegalArgumentException("target_not_2ghz");
        return new JSONObject().put("snapshot_clone",true).put("ssid",ssid).put("channel",ch).put("bssid",bssid)
            .put("sniff",a.optBoolean("sniff",true)).put("portal",a.optBoolean("portal",false))
            .put("upstream",a.optBoolean("upstream",true)).put("portal",a.optBoolean("portal",false)).put("portal_template",a.optString("portal_template","google")).put("seconds",a.has("seconds")?a.getInt("seconds"):0);
    }
    private JSONObject crackInput(JSONObject a)throws Exception {        JSONObject receipt=load(a.getString("capture_id")),source=receipt.optJSONObject("result");
        if(source==null||!Arrays.asList("completed","partial","cancelled","interrupted","failed").contains(receipt.optString("state")))throw new IllegalArgumentException("capture_not_ready");
        if(receipt.optString("tool").equals("benchmark.wpa")&&source.optBoolean("synthetic")&&source.optBoolean("known_answers_passed")){
            // Closed, reproducible offline acceptance fixture; never caller-provided paths.
            java.io.File cap=new java.io.File(CrackBenchmark.FIX+"wpa.cap");
            if(!CrackStore.hash(cap).equals("67fc2df304b06b92f8d7455574ee5b9fd478ecf408d29dd7dc789a865ea55e57"))throw new IllegalStateException("fixture_hash_mismatch");
            source=new JSONObject().put("handshake_detected",true).put("restored",true).put("synthetic",true)
                .put("target",new JSONObject().put("ssid","LambdaBenchmark").put("bssid","02:11:22:33:44:55"))
                .put("pcap_base64",android.util.Base64.encodeToString(java.nio.file.Files.readAllBytes(cap.toPath()),android.util.Base64.NO_WRAP));
        }else if(!receipt.optString("tool").equals("radio.capture")||!receipt.getJSONObject("args").optBoolean("authorized"))throw new IllegalArgumentException("invalid_capture_reference");
        if(!source.optBoolean("handshake_detected")||!source.optBoolean("restored"))throw new IllegalArgumentException("capture_not_ready");
        JSONObject minimal=new JSONObject();for(String key:new String[]{"target","pcap_base64","handshake_detected","restored","synthetic"})if(source.has(key))minimal.put(key,source.get(key));
        return new JSONObject().put("source",minimal).put("dictionary",CrackStore.selected(context,a.getString("dictionary_id"))).put("engine",a.optString("engine","auto"));
    }
    synchronized JSONObject load(String id)throws Exception {Job live=running.get(id);if(live!=null){JSONObject copy=new JSONObject(live.record.toString());JSONObject progress=live.progress;if(progress!=null)copy.put("progress",progress);return copy;}JSONObject r=optionalLoad(id);if(r==null)throw new IllegalArgumentException("job_not_found");return r;}
    private JSONObject optionalLoad(String id)throws Exception {
        try(Cursor row=db.query("jobs",new String[]{"record"},"id=?",new String[]{id},null,null,null)) {return row.moveToFirst()?new JSONObject(row.getString(0)):null;}
    }
    private synchronized void save(JSONObject r)throws Exception {
        ContentValues v=new ContentValues();v.put("id",r.getString("id"));v.put("tool",r.getString("tool"));v.put("state",r.getString("state"));v.put("record",r.toString());v.put("created",r.getLong("created_ms"));
        db.insertWithOnConflict("jobs",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    synchronized void captureCheckpoint(Job job,JSONObject checkpoint)throws Exception {
        if(!job.tool().equals("radio.capture")||!job.args().optBoolean("authorized")||!checkpoint.optBoolean("restored")||!checkpoint.optBoolean("handshake_detected")
                ||checkpoint.optString("pcap_base64").length()>160000||checkpoint.optString("pcap_base64").isEmpty())throw new IllegalArgumentException("invalid_capture_checkpoint");
        if(!job.args().getString("bssid").equalsIgnoreCase(checkpoint.getJSONObject("target").getString("bssid")))throw new IllegalArgumentException("checkpoint_target_mismatch");
        job.record.put("result",new JSONObject(checkpoint.toString())).put("capture_checkpoint_ms",System.currentTimeMillis());save(job.record);
    }
    synchronized JSONObject snapshot(JSONObject args,String... tools)throws Exception {
        JSONObject r=load(id(args,"snapshot_id"));
        if(!Arrays.asList(tools).contains(r.getString("tool"))||!r.has("result")||!Arrays.asList("completed","partial").contains(r.getString("state")))throw new IllegalArgumentException("invalid_snapshot");
        return r;
    }
    private void execute(Job job) {
        PowerManager.WakeLock lock=context.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Lambda:Audit");
        long start=SystemClock.elapsedRealtime();
        try {
            job.check();
            // radio.ap has no fixed duration: hold the CPU awake until it ends.
            if(job.tool().equals("radio.ap"))lock.acquire();
            else lock.acquire(job.tool().equals("crack.wpa")?3690000:job.tool().equals("wps.oneshot")?660000:job.tool().startsWith("system.")?3600000:job.tool().startsWith("radio.")||job.tool().equals("benchmark.wpa")?390000:65000);
            synchronized(this){job.record.put("state","running").put("started_ms",System.currentTimeMillis());save(job.record);}
            CoreService.update();
            boolean maintenance=job.tool().equals("wps.oneshot")||job.tool().startsWith("radio.")||job.tool().startsWith("system.")||job.tool().equals("benchmark.wpa")||job.tool().equals("crack.wpa")||job.tool().equals("wifi.connect_verified");
            JSONObject result=maintenance?RadioBridge.run(job):new AuditTools(this,job).run();
            if(!maintenance)job.check();
            if(result.toString().length()>350000)throw new IllegalStateException("result_too_large");
            synchronized(this){job.record.put("result",result).put("state",job.cancelled?"cancelled":result.optBoolean("partial")?"partial":"completed");updateDesktopLocked(job);}
        } catch(CancellationException e) {try{ synchronized(this){job.record.put("state","cancelled");}}catch(Exception ignored){} }
        catch(Exception e) {try{ synchronized(this){String message=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();job.record.put("state",job.cancelled&&!message.contains("restore_failed")?"cancelled":e instanceof SecurityException?"permission_denied":"failed").put("error",message);}}catch(Exception ignored){} }
        finally {
            if(lock.isHeld())lock.release();
            synchronized(this){try {if(job.progress!=null)job.record.put("progress",job.progress);job.record.put("finished_ms",System.currentTimeMillis()).put("duration_ms",SystemClock.elapsedRealtime()-start);updateDesktopLocked(job);save(job.record);
            }catch(Exception e){android.util.Log.e("LambdaCore","Persist job failed",e);}}
            running.remove(job.id());CoreService.update();
        }
    }
    /** Called with this monitor held, next to the job state, so a get/status
     *  can never observe the job and the desktop state split. */
    private void updateDesktopLocked(Job job){
        if(!job.tool().equals("system.desktop"))return;
        JSONObject result=job.record.optJSONObject("result");
        if(result==null)return;
        boolean running=result.optBoolean("running")||result.optInt("running",0)==1;
        if(running)desktop=result;
        else if("stop".equals(job.args().optString("action")))desktop=null;
        saveDesktop();
    }
    /** Cheap port probe used by the notification when no viewer is polling. */
    synchronized boolean probeDesktop(){
        if(desktop!=null&&!desktopCheck()){desktop=null;desktopMisses=0;saveDesktop();return true;}
        return false;
    }
    /** Host label shown on the phone; also the allowlist key. Rejects control
     *  characters and shell punctuation before it reaches a notification. */
    static String hostName(String raw){
        String value=raw==null?"":raw.trim();
        if(value.isEmpty()||value.length()>64)throw new IllegalArgumentException("invalid_host");
        if(!value.matches("[\\p{L}0-9 ._()\\[\\]-]+"))throw new IllegalArgumentException("invalid_host");
        return value;
    }
    private boolean isTrusted(String host){synchronized(trustedHosts){return trustedHosts.contains(host);}}
    private boolean forgetTrusted(String host){synchronized(trustedHosts){boolean removed=trustedHosts.remove(host);saveTrustedLocked();return removed;}}
    /** Called by CoreService from a notification action. Runs on the service
     *  main thread: touches only concurrent maps and the trusted-set lock. */
    void decideConnect(String host,String state){
        if(!Arrays.asList("allowed","denied","trusted").contains(state))return;
        if("trusted".equals(state)){
            synchronized(trustedHosts){trustedHosts.add(host);saveTrustedLocked();}
            connectStates.put(host,"trusted");
        }else connectStates.put(host,state);
        connectSince.put(host,System.currentTimeMillis());
    }
    private void saveTrustedLocked(){
        JSONArray array=new JSONArray();
        for(String host:trustedHosts)array.put(host);
        context.getSharedPreferences("core",Context.MODE_PRIVATE).edit().putString("trusted_hosts",array.toString()).apply();
    }
    void cancelAll(){for(Job j:running.values())j.cancelled=true;}
    void cancelJob(String id){
        if("lex.desktop".equals(id)){
            // The desktop is not an occupying job; stopping is a normal typed job.
            try{rpc("submit",new JSONObject().put("tool","system.desktop")
                .put("args",new JSONObject().put("action","stop"))
                .put("request_id",UUID.randomUUID().toString()),android.os.Process.myUid());}catch(Exception ignored){}
            return;
        }
        Job job=running.get(id);if(job!=null){job.cancelled=true;synchronized(this){try{job.record.put("cancel_requested",true);save(job.record);}catch(Exception ignored){}}}
    }
    /** Most relevant live job for the resident notification (radio first). */
    synchronized JSONObject activeJob()throws Exception {
        Job chosen=null;
        for(Job j:running.values())if(chosen==null||(j.tool().startsWith("radio.")&&!chosen.tool().startsWith("radio.")))chosen=j;
        if(chosen==null){
            if(desktop!=null)return new JSONObject().put("id","lex.desktop").put("tool","system.desktop")
                .put("label","LEX").put("detail","escritorio").put("count",1);
            return null;
        }
        JSONObject args=chosen.args();if(args==null)args=new JSONObject();
        String tool=chosen.tool(),detail="";
        if(tool.equals("radio.ap"))detail=args.optString("ssid","");
        else if(tool.equals("radio.capture")||tool.equals("radio.deauth"))detail=args.optString("bssid","");
        else if(tool.startsWith("radio.scan")||tool.equals("wifi.scan")||tool.equals("wifi.cached"))detail=args.optString("adapter","");
        else if(tool.equals("wps.oneshot"))detail=args.optString("ssid",args.optString("bssid",""));
        else if(tool.equals("system.cooling"))detail=args.optString("mode","smart")+" · "+args.optInt("target_c",42)+"°C";
        else if(tool.equals("system.heater"))detail="target "+args.optInt("target_c",65)+"°C";
        else if(tool.equals("system.desktop"))detail=args.optString("action","").equals("stop")?"deteniendo":"escritorio";
        return new JSONObject().put("id",chosen.id()).put("tool",tool).put("label",labelFor(tool)).put("detail",detail).put("count",running.size());
    }
    private static String labelFor(String tool){
        if(tool.equals("radio.ap"))return "AP";
        if(tool.equals("radio.capture"))return "Captura";
        if(tool.equals("radio.deauth"))return "Deauth";
        if(tool.startsWith("radio.scan")||tool.equals("wifi.scan")||tool.equals("wifi.cached"))return "Escaneo";
        if(tool.equals("wps.oneshot"))return "WPS";
        if(tool.equals("crack.wpa"))return "Cracking";
        if(tool.equals("benchmark.wpa"))return "Benchmark";
        if(tool.equals("system.cooling"))return "Cooling";
        if(tool.equals("system.heater"))return "Heater";
        if(tool.equals("system.desktop"))return "LEX";
        return tool;
    }
    static final class Job {
        final JSONObject record;volatile boolean cancelled;volatile JSONObject progress;
        JSONObject workerInput;
        Job(JSONObject r){record=r;}
        String id(){return record.optString("id");}String tool(){return record.optString("tool");}JSONObject args(){return record.optJSONObject("args");}
        void check(){if(cancelled)throw new CancellationException();}
        void pause(long ms)throws InterruptedException {long end=SystemClock.elapsedRealtime()+ms;while(SystemClock.elapsedRealtime()<end){check();Thread.sleep(Math.min(100,Math.max(1,end-SystemClock.elapsedRealtime())));}}
    }
}
