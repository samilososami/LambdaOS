package org.lambdaproject.lambda;

import android.os.SystemClock;
import android.system.Os;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Offline dictionary checking. Captures survive every terminal outcome. */
final class CrackJob {
    static final String BASE=CrackBenchmark.BASE, ENGINE=BASE+"/aircrack-ng-sha";
    private final JSONObject source,dictionary;
    private final CrackBenchmark.Progress progress;
    private final long start=SystemClock.elapsedRealtime();
    private long tested,total;
    private String phase="crack_validating";
    private File run;
    private HybridPmk hybrid;
    private boolean useHybrid;
    private final String requestedEngine;
    private double computeSeconds;
    private long computed;
    CrackJob(JSONObject source,JSONObject dictionary,CrackBenchmark.Progress progress,String engine){this.source=source;this.dictionary=dictionary;this.progress=progress;total=dictionary.optLong("candidates");requestedEngine=engine;}
    CrackJob(JSONObject source,JSONObject dictionary,CrackBenchmark.Progress progress){this(source,dictionary,progress,"cpu");}
    static void validate(JSONObject a)throws Exception {
        CoreEngine.keys(a,"capture_id","dictionary_id","authorized","engine");CoreEngine.id(a,"capture_id");
        if(!Arrays.asList("auto","cpu","hybrid").contains(a.optString("engine","auto")))throw new IllegalArgumentException("invalid_cracking_engine");
        String d=CoreEngine.id(a,"dictionary_id");if(!Arrays.asList("quick","probable-long").contains(d)&&!d.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("invalid_dictionary_id");
        if(!CoreEngine.bool(a,"authorized",false))throw new IllegalArgumentException("target_authorization_required");
    }
    private void tick()throws Exception {
        JSONObject p=CrackBenchmark.telemetry().put("phase",phase).put("tested",tested).put("total",total)
            .put("target",source.optJSONObject("target"))
            .put("engine",useHybrid?"hybrid-pmk-aircrack":"aircrack-sha").put("seconds",(SystemClock.elapsedRealtime()-start)/1000.0)
            .put("keys_per_second",tested*1000.0/Math.max(1,SystemClock.elapsedRealtime()-start));
        progress.update(p);
    }
    private File write(String name,byte[] bytes)throws Exception {
        File f=new File(run,name);try(FileOutputStream out=new FileOutputStream(f)){out.write(bytes);out.getFD().sync();}Os.chmod(f.getPath(),0644);return f;
    }
    private JSONObject batch(File capture,JSONObject target,File words,boolean original)throws Exception {
        File key=new File(run,"key.txt");key.delete();
        boolean cache=!original&&useHybrid&&phase.equals("cracking");
        ArrayList<String> args=new ArrayList<>(Arrays.asList(BASE+"/benchmark-guard","60",original?BASE+"/aircrack-ng":cache?BASE+"/aircrack-ng-hybrid":ENGINE,
            "-q","-a","2","-p","8","-b",target.getString("bssid"),"-e",target.getString("ssid"),"-w",words.getPath(),"-l",key.getPath(),capture.getPath()));
        ProcessBuilder pb=new ProcessBuilder(args).directory(run).redirectErrorStream(true);pb.environment().put("LD_LIBRARY_PATH",BASE+":/vendor/lib64");
        if(cache)pb.environment().put("LAMBDA_PMK_CACHE",new File(run,"pmk-cache.bin").getPath());
        Process process=pb.start();StringBuilder log=new StringBuilder();
        Thread reader=new Thread(()->{try(Reader in=new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8)){char[] b=new char[2048];int n;while((n=in.read(b))!=-1)synchronized(log){log.append(b,0,n);if(log.length()>32000)log.delete(0,log.length()-32000);}}catch(IOException ignored){}});reader.start();
        long begin=SystemClock.elapsedRealtime();
        try{while(!process.waitFor(250,TimeUnit.MILLISECONDS)){tick();if(SystemClock.elapsedRealtime()-begin>65000)throw new IOException("guard_timeout");}}
        finally {if(process.isAlive()){process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();}reader.join(1500);}
        String output;synchronized(log){output=log.toString();}
        Matcher m=Pattern.compile("LAMBDA_GUARD (\\{[^\\n]+\\})").matcher(output);if(!m.find())throw new IOException("guard_receipt_missing");
        JSONObject guard=new JSONObject(m.group(1));if(!guard.optString("reason").equals("exit"))throw new IOException(guard.optString("reason"));
        if(cache&&!output.contains("LAMBDA_CACHE ready "))throw new IOException("hybrid_cache_not_used");
        int code=process.exitValue();
        if(code==0&&key.isFile()&&key.length()<=64){byte[] password=Files.readAllBytes(key.toPath());
            // Aircrack writes the exact bytes, without a terminator.
            if(password.length<8||password.length>63)throw new IOException("invalid_engine_key");
            return new JSONObject().put("outcome","found").put("password",new String(password,StandardCharsets.US_ASCII)).put("guard",guard);
        }
        if(code==1&&output.contains("KEY NOT FOUND"))return new JSONObject().put("outcome","exhausted").put("guard",guard);
        throw new IOException("capture_or_engine_error_exit_"+code);
    }
    JSONObject run()throws Exception {
        JSONObject result=new JSONObject().put("engine","aircrack-sha").put("dictionary",dictionary.optString("id"))
            .put("total",total).put("cryptographic_validation",false).put("partial",true).put("capture_preserved",true);
        try {
            result.put("before",CrackBenchmark.telemetry()).put("dictionary_sha256",dictionary.optString("sha256"));
            useHybrid=requestedEngine.equals("hybrid")||(requestedEngine.equals("auto")&&total>=24000);
            result.put("engine",useHybrid?"hybrid-pmk-aircrack":"aircrack-sha");
            tick();if(!new File(ENGINE).canExecute())throw new IOException("optimized_engine_unavailable");
            result.put("cpu_engine_sha256",CrackStore.hash(new File(ENGINE))).put("guard_sha256",CrackStore.hash(new File(BASE,"benchmark-guard")));
            if(useHybrid){if(!new File(BASE,"pmk-hybrid-worker").canExecute()||!new File(BASE,"aircrack-ng-hybrid").canExecute())throw new IOException("hybrid_not_provisioned");
                result.put("hybrid_worker_sha256",CrackStore.hash(new File(BASE,"pmk-hybrid-worker"))).put("hybrid_kernel_sha256",CrackStore.hash(new File(BASE,"pmk-hybrid.cl"))).put("hybrid_verifier_sha256",CrackStore.hash(new File(BASE,"aircrack-ng-hybrid")));}
            if(!source.optBoolean("handshake_detected")||!source.optBoolean("restored"))throw new IOException("capture_not_ready");
            JSONObject target=source.getJSONObject("target");String bssid=target.getString("bssid"),ssid=target.getString("ssid");
            if(!bssid.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")||ssid.getBytes(StandardCharsets.UTF_8).length<1||ssid.getBytes(StandardCharsets.UTF_8).length>32)throw new IOException("target_ssid_required");
            result.put("target",target);
            byte[] cap=Base64.decode(source.getString("pcap_base64"),Base64.DEFAULT);if(cap.length<24||cap.length>120000)throw new IOException("capture_size_invalid");
            File dictionaryFile=new File(dictionary.getString("path"));if(total<1||total>CrackStore.MAX_WORDS||dictionaryFile.length()>CrackStore.MAX_BYTES)throw new IOException("dictionary_size_invalid");
            if(!CrackStore.hash(dictionaryFile).equals(dictionary.getString("sha256")))throw new IOException("dictionary_hash_mismatch");
            File parent=new File(BASE,"runs");if(!parent.isDirectory()&&!parent.mkdir())throw new IOException("run_directory_unavailable");
            run=Files.createTempDirectory(parent.toPath(),"crack-").toFile();Os.chown(run.getPath(),2000,2000);Os.chmod(run.getPath(),0700);
            File capture=write("input.cap",cap);
            phase="crack_selftest";tick();
            JSONObject known=new JSONObject().put("ssid","LambdaBenchmark").put("bssid","02:11:22:33:44:55");
            JSONObject self=batch(new File(CrackBenchmark.FIX+"wpa.cap"),known,new File(CrackBenchmark.FIX+"selftest.txt"),false);
            if(!self.optString("password").equals("LambdaBench2026!"))throw new IOException("known_answer_failed");
            if(useHybrid){phase="crack_gpu_starting";tick();hybrid=new HybridPmk(run,ssid,unused->tick());result.put("hybrid_setup_s",hybrid.last.optDouble("setup_s"));}
            phase="cracking";tick();
            try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(dictionaryFile),StandardCharsets.US_ASCII))){
                boolean done=false;while(!done){
                    if(SystemClock.elapsedRealtime()-start>3600000)throw new IOException("session_limit_1h");
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();ArrayList<String> candidates=new ArrayList<>();String word;
                    while(candidates.size()<(useHybrid?19200:4800)&&(word=in.readLine())!=null){byte[] w=word.getBytes(StandardCharsets.US_ASCII);if(!CrackStore.valid(w))throw new IOException("dictionary_invalid_entry");candidates.add(word);bytes.write(w);bytes.write(10);}
                    if(candidates.isEmpty())break;
                    tick();File words=write("batch.txt",bytes.toByteArray());
                    if(hybrid!=null){JSONObject metrics=hybrid.calculate(candidates.size());computed+=metrics.optInt("count");computeSeconds+=metrics.optDouble("compute_s");}
                    JSONObject b=batch(capture,target,words,false);
                    if(b.optString("outcome").equals("found")){
                        String password=b.getString("password");if(!candidates.contains(password))throw new IOException("engine_key_not_in_batch");
                        phase="crack_confirming";tick();
                        JSONObject confirm=batch(capture,target,write("verify.txt",(password+"\n").getBytes(StandardCharsets.US_ASCII)),true);
                        if(!password.equals(confirm.optString("password")))throw new IOException("independent_verification_failed");
                        result.put("outcome","found").put("password",password).put("cryptographic_validation",true).put("partial",false)
                            .put("tested_upper_bound",tested+candidates.size());done=true;
                    }else{tested+=candidates.size();tick();}
                }
            }
            if(!result.has("outcome")){if(tested!=total)throw new IOException("dictionary_count_mismatch");result.put("outcome","exhausted").put("partial",false);}
        }catch(Exception e){String why=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();result.put("outcome",why.equals("cancelled")?"cancelled":"error").put("stop_reason",why).put("partial",true);}
        finally{if(hybrid!=null)hybrid.close();if(run!=null){File[] files=run.listFiles();if(files!=null)for(File f:files)f.delete();run.delete();}}
        return result.put("after",CrackBenchmark.telemetry()).put("computed_pmks",computed).put("compute_seconds",computeSeconds).put("tested",tested).put("wall_ms",SystemClock.elapsedRealtime()-start)
            .put("keys_per_second",tested*1000.0/Math.max(1,SystemClock.elapsedRealtime()-start));
    }
}
