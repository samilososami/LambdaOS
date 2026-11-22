package org.lambdaproject.lambda;

import android.os.SystemClock;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Fixed OneShot adapter. Owns only its private subprocess group and Wi-Fi lease. */
final class WpsJob {
    static final String BASE="/data/local/tmp/lambda-wps";
    interface Progress {void tick(JSONObject p)throws Exception;}
    static void validate(JSONObject a)throws Exception {
        CoreEngine.keys(a,"snapshot_id","bssid","mode","pin","authorized","max_attempts","start_prefix");
        CoreEngine.id(a,"snapshot_id");String b=a.getString("bssid"),mode=a.getString("mode");
        if(!b.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")||(Integer.parseInt(b.substring(0,2),16)&1)!=0||b.equals("00:00:00:00:00:00"))throw new IllegalArgumentException("invalid_bssid");
        if(!Arrays.asList("pixie","pin","pbc","bruteforce").contains(mode))throw new IllegalArgumentException("invalid_wps_mode");
        if(mode.equals("bruteforce")){
            Object budget=a.opt("max_attempts");
            if(!(budget instanceof Integer)||!Arrays.asList(10,50,100).contains((Integer)budget))throw new IllegalArgumentException("invalid_attempt_budget");
            if(!(a.opt("start_prefix") instanceof String)||!a.getString("start_prefix").matches("[0-9]{4}"))throw new IllegalArgumentException("invalid_start_prefix");
        }else if(a.has("max_attempts")||a.has("start_prefix"))throw new IllegalArgumentException("unexpected_bruteforce_options");
        String pin=a.optString("pin","");
        if(mode.equals("pin")){
            if(!(a.opt("pin") instanceof String)||!pin.matches("[0-9]{8}"))throw new IllegalArgumentException("invalid_wps_pin");
            int sum=0;for(int i=0;i<8;i++)sum+=(pin.charAt(i)-'0')*(i%2==0?3:1);
            if(sum%10!=0)throw new IllegalArgumentException("invalid_wps_pin_checksum");
        }else if(a.has("pin"))throw new IllegalArgumentException("unexpected_wps_pin");
        if(!CoreEngine.bool(a,"authorized",false))throw new IllegalArgumentException("target_authorization_required");
    }
    static JSONObject input(JSONObject a,JSONObject snapshot,long now)throws Exception {
        validate(a);
        if(snapshot==null)throw new IllegalArgumentException("wps_scan_required");
        if(!Arrays.asList("wifi.scan","wifi.cached").contains(snapshot.optString("tool"))||!Arrays.asList("completed","partial").contains(snapshot.optString("state")))throw new IllegalArgumentException("wps_scan_required");
        long elapsed=now-snapshot.getLong("finished_ms");
        if(elapsed<0||elapsed>120000)throw new IllegalArgumentException("wps_scan_expired");
        JSONArray rows=snapshot.getJSONObject("result").getJSONArray("networks");
        for(int i=0;i<rows.length();i++){
            JSONObject n=rows.getJSONObject(i);
            if(!n.optString("bssid").equalsIgnoreCase(a.getString("bssid")))continue;
            if(!n.optString("capabilities").contains("[WPS]")||n.optLong("age_ms",-1)<0||n.optLong("age_ms",Long.MAX_VALUE)>120000-elapsed)throw new IllegalArgumentException("wps_not_advertised_or_stale");
            JSONObject input=new JSONObject().put("interface","wlan0").put("bssid",n.getString("bssid").toLowerCase(Locale.ROOT)).put("mode",a.getString("mode"))
                .put("pin",a.optString("pin","")).put("target",n).put("expires_ms",snapshot.getLong("finished_ms")+120000-n.optLong("age_ms"));
            if(a.getString("mode").equals("bruteforce"))input.put("max_attempts",a.getInt("max_attempts")).put("start_prefix",a.getString("start_prefix"));
            return input;
        }
        throw new IllegalArgumentException("wps_target_not_in_scan");
    }
    static JSONObject capabilities()throws Exception {
        boolean ready=new File(BASE,"runtime-ready.json").isFile()&&new File(BASE,"wps-guard").canExecute()&&new File(BASE,"usr/bin/python3").canExecute();
        return new JSONObject().put("provisioned",ready).put("engine","OneShot-Extended").put("modes",new JSONArray(Arrays.asList("pixie","pin","pbc","bruteforce")))
            .put("interface","wlan0").put("max_seconds",120).put("bruteforce_max_seconds",600).put("bruteforce_max_attempts",100).put("active_radio_verified",false);
    }
    private static String command(String... args)throws Exception {
        Process p=new ProcessBuilder(args).redirectErrorStream(true).start();ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        Thread drain=new Thread(()->{try(InputStream in=p.getInputStream()){byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1)if(bytes.size()<16384)bytes.write(b,0,n);}catch(IOException ignored){}});drain.start();
        if(!p.waitFor(7,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("wps_system_command_timeout");}drain.join(1000);
        if(p.exitValue()!=0)throw new IOException("wps_system_command_failed");return bytes.toString("UTF-8").trim();
    }
    static synchronized void recover()throws Exception {
        File journal=new File(BASE,"restore.json");if(!journal.exists())return;
        JSONObject saved=new JSONObject(new String(Files.readAllBytes(journal.toPath()),StandardCharsets.UTF_8));
        command("/system/bin/svc","wifi",saved.getBoolean("wifi")?"enable":"disable");
        boolean restored=false;
        for(int i=0;i<20;i++){
            if(command("/system/bin/settings","get","global","wifi_on").equals(saved.getBoolean("wifi")?"1":"0")){restored=true;break;}
            Thread.sleep(250);
        }
        if(!restored)throw new IOException("wps_restore_pending");
        if(!journal.delete())throw new IOException("wps_restore_journal_failed");
    }
    static JSONObject run(JSONObject request,Progress progress)throws Exception {
        if(!capabilities().getBoolean("provisioned"))throw new IOException("wps_runtime_missing");
        if(System.currentTimeMillis()>request.getLong("expires_ms"))throw new IOException("wps_scan_expired");
        if(!command("/system/bin/iw","dev","wlan0","info").contains("type managed"))throw new IOException("wps_interface_busy");
        boolean wifi=command("/system/bin/settings","get","global","wifi_on").equals("1");
        JSONObject journal=new JSONObject().put("wifi",wifi);
        try(FileOutputStream out=new FileOutputStream(new File(BASE,"restore.json"))){out.write(journal.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        Process process=null;long start=SystemClock.elapsedRealtime();JSONObject result=null;
        try {
            progress.tick(new JSONObject().put("phase","wps_preparing"));
            command("/system/bin/svc","wifi","disable");
            for(int i=0;i<5;i++){Thread.sleep(500);progress.tick(new JSONObject().put("phase","wps_preparing"));}
            command("/system/bin/ip","link","set","wlan0","up");
            boolean brute=request.getString("mode").equals("bruteforce");
            ProcessBuilder pb=brute?new ProcessBuilder(BASE+"/wps-guard","--bruteforce"):new ProcessBuilder(BASE+"/wps-guard");pb.directory(new File(BASE));pb.redirectError(new File("/dev/null"));
            Map<String,String> env=pb.environment();env.put("PATH",BASE+"/usr/bin:/system/bin");env.put("LD_LIBRARY_PATH",BASE+"/usr/lib");env.remove("LD_PRELOAD");
            env.put("PYTHONHOME",BASE+"/usr");env.put("HOME",BASE+"/home");env.put("TMPDIR",BASE+"/tmp");env.put("PYTHONDONTWRITEBYTECODE","1");
            process=pb.start();
            JSONObject input=new JSONObject(request.toString());input.remove("target");input.remove("expires_ms");
            try(OutputStream out=process.getOutputStream()){out.write((input.toString()+"\n").getBytes(StandardCharsets.UTF_8));}
            AtomicReference<JSONObject> current=new AtomicReference<>(new JSONObject().put("phase","wps_exchange"));
            AtomicReference<JSONObject> terminal=new AtomicReference<>();AtomicReference<String> failure=new AtomicReference<>();
            final Process child=process;
            Thread reader=new Thread(()->{try(BufferedReader in=new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8))){
                String line;int lines=0;while((line=in.readLine())!=null){if(line.length()>16384||++lines>4096){failure.set("wps_protocol_limit");break;}
                    JSONObject event=new JSONObject(line);
                    if(event.has("result"))terminal.set(event.getJSONObject("result"));
                    else if(event.has("error"))failure.set(event.getString("error"));else if(event.has("phase"))current.set(event);
                }
            }catch(Exception e){failure.set("wps_protocol_error");}},"wps-events");reader.start();
            while(process.isAlive()){
                JSONObject p=new JSONObject(current.get().toString()).put("seconds",(SystemClock.elapsedRealtime()-start)/1000).put("target",request.getJSONObject("target"));progress.tick(p);
                if(failure.get()!=null)throw new IOException(failure.get());
                if(SystemClock.elapsedRealtime()-start>(brute?615000:132000))throw new IOException("wps_timeout");
                Thread.sleep(250);
            }
            reader.join(1500);if(reader.isAlive())throw new IOException("wps_protocol_timeout");
            if(failure.get()!=null)throw new IOException(failure.get());
            if(process.exitValue()==124)throw new IOException("wps_timeout");
            if(process.exitValue()!=0||terminal.get()==null)throw new IOException("wps_process_failed");
            result=terminal.get();
            if(result.has("telemetry"))progress.tick(new JSONObject(result.getJSONObject("telemetry").toString()).put("phase","wps_restoring").put("seconds",(SystemClock.elapsedRealtime()-start)/1000));
            if(!result.optString("bssid").equalsIgnoreCase(request.getString("bssid")))throw new IOException("wps_target_mismatch");
            result.put("target",request.getJSONObject("target")).put("engine","OneShot-Extended").put("elapsed_ms",SystemClock.elapsedRealtime()-start);
        }finally{
            if(process!=null&&process.isAlive()){process.destroy();if(!process.waitFor(3,TimeUnit.SECONDS))throw new IOException("wps_cleanup_pending");}
            recover();
        }
        return result.put("restored",true).put("partial",!result.optBoolean("protocol_confirmed"));
    }
}
