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
        "usb.inventory","usb.audit","usb.watch","usb.descriptors","system.capabilities","radio.scan","radio.capture","radio.deauth","radio.release","benchmark.wpa","crack.wpa","wifi.connect_verified","wps.oneshot"};
    final Context context;
    private final SQLiteDatabase db;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<String,Job> running=new ConcurrentHashMap<>();
    private CoreEngine(Context c) {
        context=c;
        SQLiteOpenHelper helper=new SQLiteOpenHelper(c,"lambda-core.db",null,1){
            public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE jobs (id TEXT PRIMARY KEY, tool TEXT NOT NULL, state TEXT NOT NULL, record TEXT NOT NULL, created INTEGER NOT NULL)");}
            public void onUpgrade(SQLiteDatabase d,int a,int b){throw new IllegalStateException("Explicit migration required");}
        };
        db=helper.getWritableDatabase();
        // Never silently replay radio work after a process/boot interruption.
        try(Cursor rows=db.query("jobs",new String[]{"record"},"state IN ('queued','running')",null,null,null,null)) {
            while(rows.moveToNext()) {
                JSONObject r=new JSONObject(rows.getString(0));
                r.put("state","interrupted").put("error","core_restarted").put("finished_ms",System.currentTimeMillis()); save(r);
            }
        } catch(Exception e){throw new IllegalStateException(e);}
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
            case "crack_dictionaries": keys(r);return new JSONObject().put("ok",true).put("dictionaries",CrackStore.list(context)).put("hybrid_available",new java.io.File(CrackBenchmark.BASE,"pmk-hybrid-worker").canExecute());
            case "radio_status": keys(r);return RadioBridge.status();
            case "radio_worker": return RadioBridge.worker(r,uid,this);
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
            case "catalog": keys(r); return catalog();
            case "status":
                keys(r);return new JSONObject().put("ok",true).put("schema_version",1).put("version","0.2.0-operator.4")
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
            case "wifi.connect_verified":keys(a,"result_id");id(a,"result_id");break;
            case "crack.wpa":CrackJob.validate(a);break;
            case "benchmark.wpa":CrackBenchmark.validate(a);break;
            case "radio.scan":keys(a,"adapter");if(!(a.opt("adapter") instanceof String)||a.getString("adapter").length()>180)throw new IllegalArgumentException("invalid_adapter");break;
            case "radio.capture":case "radio.deauth":keys(a,"session","bssid","authorized");id(a,"session");String bssid=a.getString("bssid");if(!bssid.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")||(Integer.parseInt(bssid.substring(0,2),16)&1)!=0||bssid.equals("00:00:00:00:00:00")||!bool(a,"authorized",false))throw new IllegalArgumentException("target_authorization_required");break;
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
        if(running.size()>=8)throw new IllegalArgumentException("queue_full");
        if(tool.equals("wps.oneshot")&&!running.isEmpty())throw new IllegalStateException("wps_requires_idle_core");
        for(Job live:running.values())if(live.tool().equals("wps.oneshot"))throw new IllegalStateException("wps_exclusive_lease");
        if(tool.equals("wifi.connect_verified")&&!running.isEmpty())throw new IllegalStateException("connection_requires_idle_core");
        for(Job live:running.values())if(live.tool().equals("wifi.connect_verified"))throw new IllegalStateException("connection_in_progress");
        if((tool.equals("benchmark.wpa")||tool.equals("crack.wpa"))&&!running.isEmpty())throw new IllegalStateException("benchmark_requires_idle_core");
        for(Job live:running.values())if(live.tool().equals("benchmark.wpa")||live.tool().equals("crack.wpa"))throw new IllegalStateException("benchmark_exclusive_lease");
        if(context.getDatabasePath("lambda-core.db").length()>50L*1024*1024)throw new IllegalStateException("audit_storage_full: export reports before maintenance");
        CoreService.ensure(context);
        JSONObject record=new JSONObject().put("schema_version",1).put("id",requestId).put("tool",tool).put("args",a)
            .put("caller_uid",uid).put("hidden",hidden).put("state","queued").put("created_ms",System.currentTimeMillis());
        Job job=new Job(record);if(tool.equals("wps.oneshot"))job.workerInput=WpsJob.input(a,load(a.getString("snapshot_id")),System.currentTimeMillis());if(tool.equals("crack.wpa"))job.workerInput=crackInput(a);if(tool.equals("wifi.connect_verified"))job.workerInput=connectionInput(a);save(record);running.put(requestId,job);executor.execute(()->execute(job));
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
    private JSONObject crackInput(JSONObject a)throws Exception {
        JSONObject receipt=load(a.getString("capture_id")),source=receipt.optJSONObject("result");
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
            job.check(); lock.acquire(job.tool().equals("crack.wpa")?3690000:job.tool().equals("wps.oneshot")?660000:job.tool().startsWith("radio.")||job.tool().equals("benchmark.wpa")?390000:65000);
            synchronized(this){job.record.put("state","running").put("started_ms",System.currentTimeMillis());save(job.record);}
            CoreService.update(job.tool()+" · en curso");
            boolean maintenance=job.tool().equals("wps.oneshot")||job.tool().startsWith("radio.")||job.tool().equals("benchmark.wpa")||job.tool().equals("crack.wpa")||job.tool().equals("wifi.connect_verified");
            JSONObject result=maintenance?RadioBridge.run(job):new AuditTools(this,job).run();
            if(!maintenance)job.check();
            if(result.toString().length()>350000)throw new IllegalStateException("result_too_large");
            synchronized(this){job.record.put("result",result).put("state",job.cancelled?"cancelled":result.optBoolean("partial")?"partial":"completed");}
        } catch(CancellationException e) {try{ synchronized(this){job.record.put("state","cancelled");}}catch(Exception ignored){} }
        catch(Exception e) {try{ synchronized(this){String message=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();job.record.put("state",job.cancelled&&!message.contains("restore_failed")?"cancelled":e instanceof SecurityException?"permission_denied":"failed").put("error",message);}}catch(Exception ignored){} }
        finally {
            if(lock.isHeld())lock.release();
            synchronized(this){try {if(job.progress!=null)job.record.put("progress",job.progress);job.record.put("finished_ms",System.currentTimeMillis()).put("duration_ms",SystemClock.elapsedRealtime()-start);save(job.record);
            }catch(Exception e){android.util.Log.e("LambdaCore","Persist job failed",e);}}
            running.remove(job.id());CoreService.update(running.isEmpty()?"Listo · Wi-Fi, Bluetooth y USB":running.size()+" tareas pendientes");
        }
    }
    void cancelAll(){for(Job j:running.values())j.cancelled=true;}
    static final class Job {
        final JSONObject record;volatile boolean cancelled;volatile JSONObject progress;
        JSONObject workerInput;
        Job(JSONObject r){record=r;}
        String id(){return record.optString("id");}String tool(){return record.optString("tool");}JSONObject args(){return record.optJSONObject("args");}
        void check(){if(cancelled)throw new CancellationException();}
        void pause(long ms)throws InterruptedException {long end=SystemClock.elapsedRealtime()+ms;while(SystemClock.elapsedRealtime()<end){check();Thread.sleep(Math.min(100,Math.max(1,end-SystemClock.elapsedRealtime())));}}
    }
}
