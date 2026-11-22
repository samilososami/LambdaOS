package org.lambdaproject.lambda;

import android.os.SystemClock;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Fixed synthetic inputs only. No caller-supplied hashes, commands or wordlists. */
final class CrackBenchmark {
    static final String BASE="/data/local/tmp/lambda-crack-bench", FIX=BASE+"/fixtures/";
    interface Progress { void update(JSONObject p)throws Exception; }
    private final Progress progress;
    private final JSONArray rows=new JSONArray();
    private final String engine;
    private final int threads;
    private final long started=SystemClock.elapsedRealtime();
    private String phase="validating";
    private volatile double liveRate;
    private volatile int childPid;
    private String stopReason;
    CrackBenchmark(JSONObject args,Progress p)throws Exception {validate(args);progress=p;engine=args.getString("engine");threads=args.getInt("threads");}
    static void validate(JSONObject a)throws Exception {
        CoreEngine.keys(a,"engine","threads");
        if(!Arrays.asList("aircrack","hashcat").contains(a.optString("engine")))throw new IllegalArgumentException("invalid_benchmark_engine");
        Object t=a.opt("threads");if(!(t instanceof Integer)||!Arrays.asList(1,4,8).contains(t))throw new IllegalArgumentException("threads must be 1, 4 or 8");
    }
    static JSONObject capabilities()throws Exception {
        return new JSONObject().put("cpu",new File(BASE+"/aircrack-ng").canExecute())
            .put("gpu_experimental",new File(BASE+"/gpu/hashcat").canExecute())
            .put("provisioned",new File(BASE+"/benchmark-guard").canExecute())
            .put("synthetic_only",true).put("candidates",4800).put("gpu_device_budget_mib",768);
    }
    private static String read(String path){try{return new String(Files.readAllBytes(new File(path).toPath()),StandardCharsets.UTF_8).trim();}catch(Exception e){return "";}}
    private static long number(String text,String key){Matcher m=Pattern.compile("(?m)^"+Pattern.quote(key)+"\\s+(\\d+)").matcher(text);return m.find()?Long.parseLong(m.group(1)):-1;}
    static JSONObject telemetry()throws Exception {
        JSONObject t=new JSONObject().put("elapsed_realtime_ms",SystemClock.elapsedRealtime());
        long mem=number(read("/proc/meminfo"),"MemAvailable:");if(mem>=0)t.put("available_mib",mem/1024.0);
        JSONObject temperatures=new JSONObject();File[] zones=new File("/sys/class/thermal").listFiles();
        if(zones!=null)for(File z:zones){String type=read(z+"/type");if(!Arrays.asList("battery","BIG","MID","LITTLE","G3D").contains(type))continue;
            try{temperatures.put(type,Double.parseDouble(read(z+"/temp"))/1000.0);}catch(NumberFormatException ignored){}}
        t.put("temperature_c",temperatures);
        for(String key:new String[]{"capacity","current_now","voltage_now","charge_counter"})try{t.put(key,Long.parseLong(read("/sys/class/power_supply/battery/"+key)));}catch(NumberFormatException ignored){}
        t.put("battery_status",read("/sys/class/power_supply/battery/status"));return t;
    }
    private void tick(JSONObject extra)throws Exception {
        JSONArray brief=new JSONArray();for(int i=0;i<rows.length();i++){JSONObject r=new JSONObject(rows.getJSONObject(i).toString());r.remove("output");r.remove("samples");brief.put(r);}
        JSONObject p=telemetry().put("phase",phase).put("engine",engine).put("seconds",(SystemClock.elapsedRealtime()-started)/1000.0)
            .put("rows",brief).put("engine_keys_per_second",liveRate);
        if(childPid>0){long rss=number(read("/proc/"+childPid+"/status"),"VmRSS:");if(rss>=0)p.put("engine_rss_mib",rss/1024.0);}
        if(extra!=null){Iterator<String> it=extra.keys();while(it.hasNext()){String k=it.next();p.put(k,extra.get(k));}}
        progress.update(p);
    }
    private static void verify(String name,String expected)throws Exception {
        byte[] bytes=Files.readAllBytes(new File(FIX+name).toPath());
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder hex=new StringBuilder();
        for(byte b:hash)hex.append(String.format(Locale.ROOT,"%02x",b&255));
        if(!expected.equals(hex.toString()))throw new IOException("fixture_hash_mismatch: "+name);
    }
    private static String hashFile(String path)throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(path)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}
        StringBuilder hex=new StringBuilder();for(byte b:md.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();
    }
    JSONObject run()throws Exception {
        JSONObject before=telemetry();boolean validated=false;
        try {
            if(!capabilities().optBoolean("provisioned"))throw new IOException("benchmark_not_provisioned");
            verify("quick-4800.txt","5dc33214cad9a11eb926de00f5ded20b1a5fc12ba3332319e054713265a35c51");
            verify("selftest.txt","d4f4af630dded02cd8713690a06b60ef9b0f6faff8379cebde550b03e2fc7ad6");
            verify("wpa.cap","67fc2df304b06b92f8d7455574ee5b9fd478ecf408d29dd7dc789a865ea55e57");
            verify("pmkid.txt","669e3884d13022d7752f0b1ab0045830d12b9594ff1a48d4089122bcbebb4242");
            verify("wpa.22000","a9d8324af6631fe6f4cd57d0a1c87f89cf9d6b7bd549599a3a11bd07c9bb3bcd");
            verify("pmkid.22000","bba730407fac3c5d3f4cb6fd87daf1cb83b66be422d97a68fa5d131488268e02");
            tick(null);
            for(String kind:new String[]{"wpa","pmkid"}) {
                JSONObject r=runProcess("selftest_"+kind,command(kind,true),engine.equals("hashcat")?60:15);
                boolean okay=r.optInt("exit",-1)==0&&r.optString("output").contains("LambdaBench2026!");
                r.put("valid",okay);rows.put(r);if(!okay)throw new IOException(r.optString("output").contains("OpenCL kernel self-test failed")?"opencl_selftest_failed":"known_answer_failed: "+kind);
            }
            validated=true;
            if(engine.equals("aircrack")){
                JSONObject speed=runProcess("wpa_engine",Arrays.asList(BASE+"/aircrack-ng","-S","-Z","10","-p",Integer.toString(threads)),15);
                speed.put("valid",speed.optInt("exit",-1)==0&&speed.optDouble("engine_keys_per_second",0)>0);
                rows.put(speed);
                if(!speed.optBoolean("valid"))throw new IOException("cpu_benchmark_failed");
            }
            for(String kind:new String[]{"wpa","pmkid"}) {
                JSONObject r=runProcess(kind+"_4800",command(kind,false),60);
                String output=r.optString("output");
                // Exhaustion is expected: the known synthetic password is absent from this list.
                boolean exhausted=r.optInt("exit",-1)==1 && (engine.equals("aircrack")?output.contains("KEY NOT FOUND"):output.contains("Exhausted")||output.contains("\"status\": 5")||output.contains("\"status\":5"));
                r.put("valid",exhausted).put("candidates",4800);
                if(exhausted)r.put("effective_keys_per_second",4800000.0/r.getLong("wall_ms"));
                rows.put(r);if(!exhausted)throw new IOException("exhaustion_not_verified: "+kind);
            }
        }catch(Exception e){stopReason=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
        JSONObject result=new JSONObject().put("schema_version",1).put("synthetic",true).put("ssid","LambdaBenchmark")
            .put("engine",engine).put("engine_version",engine.equals("aircrack")?"1.7":"8.0.0")
            .put("gpu_profile",engine.equals("hashcat")?"768 MiB unified budget (includes 512 MiB host reserve); no-inline kernels; workload 1; vector width 1":JSONObject.NULL)
            .put("threads",engine.equals("aircrack")?threads:JSONObject.NULL).put("known_answers_passed",validated)
            .put("partial",stopReason!=null).put("stop_reason",stopReason==null?JSONObject.NULL:stopReason)
            .put("rows",rows).put("before",before).put("after",telemetry()).put("wall_ms",SystemClock.elapsedRealtime()-started)
            .put("resource_policy","exclusive Lambda job; child UID 2000; native deadline/RSS/thermal guards; Android/ADB/thermal services unchanged")
            .put("model_coordination","Lamb not integrated; no arbitrary app freezing or process killing")
            .put("memory_metric","Engine peak RSS from wait4; not PSS or total GPU allocations")
            .put("dictionary","Probable-WPA Top4800 v2; CC-BY-SA-4.0; 8-15 characters");
        try{result.put("binary_sha256",hashFile(BASE+(engine.equals("aircrack")?"/aircrack-ng":"/gpu/hashcat")))
            .put("guard_sha256",hashFile(BASE+"/benchmark-guard")).put("fixture_manifest_sha256",hashFile(FIX+"manifest.json"));}
        catch(Exception e){result.put("provenance_error",e.getClass().getSimpleName());}
        return result;
    }
    private List<String> command(String kind,boolean self)throws Exception {
        String dictionary=FIX+(self?"selftest.txt":"quick-4800.txt");
        if(engine.equals("aircrack")){
            ArrayList<String> args=new ArrayList<>(Arrays.asList(BASE+"/aircrack-ng","-q","-p",Integer.toString(threads),"-e","LambdaBenchmark","-w",dictionary));
            if(kind.equals("pmkid"))args.addAll(Arrays.asList("-I",read(FIX+"pmkid.txt")));
            else args.addAll(Arrays.asList("-a","2",FIX+"wpa.cap"));return args;
        }
        return Arrays.asList(BASE+"/gpu/hashcat","-m","22000","-a","0","-D","2","-w","1","--backend-vector-width","1",
            "--runtime","30","--potfile-disable","--restore-disable","--status","--status-json","--status-timer","1",
            "--cache-path",BASE+"/cache","--session","lambda-benchmark",
            FIX+kind+".22000",dictionary);
    }
    private JSONObject runProcess(String name,List<String> command,int limit)throws Exception {
        phase=name;liveRate=0;childPid=0;tick(null);
        ArrayList<String> args=new ArrayList<>(Arrays.asList(BASE+"/benchmark-guard",Integer.toString(limit)));args.addAll(command);
        ProcessBuilder builder=new ProcessBuilder(args).redirectErrorStream(true).directory(new File(BASE+"/cache"));
        builder.environment().put("LD_LIBRARY_PATH",BASE+":/vendor/lib64");
        // Hashcat v8 charges its 512 MiB host reserve against unified-memory budgets.
        builder.environment().put("HASHCAT_DEVICE_MEM_LIMIT","768");
        builder.environment().put("HASHCAT_MEMORY","1");
        // The default inlined kernel exceeds the bounded cold-compile window on Mali.
        builder.environment().put("HASHCAT_FORCE_NO_INLINE","1");
        builder.environment().put("XDG_CACHE_HOME",BASE+"/cache");builder.environment().put("XDG_DATA_HOME",BASE+"/cache");
        long begin=SystemClock.elapsedRealtime();JSONObject initial=telemetry();
        java.lang.Process process=builder.start();StringBuilder output=new StringBuilder();
        Thread reader=new Thread(()->{
            try(BufferedReader in=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){String line;
                while((line=in.readLine())!=null){
                    synchronized(output){output.append(line).append('\n');if(output.length()>32000)output.delete(0,output.length()-32000);}
                    if(line.startsWith("LAMBDA_CHILD "))try{childPid=Integer.parseInt(line.substring(13).trim());}catch(Exception ignored){}
                    Matcher m=Pattern.compile("([0-9]+(?:\\.[0-9]+)?) k/s").matcher(line);while(m.find())liveRate=Double.parseDouble(m.group(1));
                    if(line.startsWith("{"))try{JSONArray devices=new JSONObject(line).optJSONArray("devices");if(devices!=null){double rate=0;for(int i=0;i<devices.length();i++)rate+=devices.getJSONObject(i).optDouble("speed",0);if(rate>0)liveRate=rate;}}catch(Exception ignored){}
                }
            }catch(IOException ignored){}
        },"LambdaBenchmarkOutput");reader.start();
        String interruption=null;JSONArray samples=new JSONArray();
        try {
            while(!process.waitFor(500,TimeUnit.MILLISECONDS)){
                if(SystemClock.elapsedRealtime()-begin>(limit+3L)*1000)throw new IOException("guard_timeout");
                JSONObject sample=telemetry();if(samples.length()<65&&(samples.length()==0||sample.getLong("elapsed_realtime_ms")-samples.getJSONObject(samples.length()-1).getLong("elapsed_realtime_ms")>=1000))samples.put(sample);
                tick(new JSONObject().put("phase_seconds",(SystemClock.elapsedRealtime()-begin)/1000.0));
            }
        }catch(Exception e){interruption=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
        finally {
            if(process.isAlive()){process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();}
            reader.join(1500);
        }
        long wall=SystemClock.elapsedRealtime()-begin;String log;synchronized(output){log=output.toString();}
        JSONObject result=new JSONObject().put("test",name).put("exit",process.isAlive()?-1:process.exitValue()).put("wall_ms",wall)
            .put("engine_keys_per_second",liveRate).put("output",log).put("before",initial).put("after",telemetry()).put("samples",samples);
        Matcher guard=Pattern.compile("LAMBDA_GUARD (\\{[^\\n]+\\})").matcher(log);
        if(guard.find()){JSONObject detail=new JSONObject(guard.group(1));result.put("guard",detail);if(!detail.optString("reason").equals("exit"))interruption=detail.optString("reason");}
        childPid=0;
        if(interruption!=null){rows.put(result.put("valid",false));throw new IOException(interruption);}
        return result;
    }
}
