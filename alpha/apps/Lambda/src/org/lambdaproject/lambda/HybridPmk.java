package org.lambdaproject.lambda;

import org.json.*;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Persistent, guarded CPU+GPU calculator; never decides that a MIC is valid. */
final class HybridPmk implements AutoCloseable {
    final Process process;final Thread reader;final BlockingQueue<String> lines=new ArrayBlockingQueue<>(128);
    final OutputStream input;final CrackBenchmark.Progress progress;JSONObject last=new JSONObject();
    HybridPmk(File run,String ssid,CrackBenchmark.Progress progress)throws Exception {
        this.progress=progress;StringBuilder hex=new StringBuilder();for(byte b:ssid.getBytes(StandardCharsets.UTF_8))hex.append(String.format(Locale.ROOT,"%02x",b&255));
        ProcessBuilder pb=new ProcessBuilder(CrackBenchmark.BASE+"/benchmark-guard","3600",CrackBenchmark.BASE+"/pmk-hybrid-worker",run.getPath(),hex.toString()).directory(run).redirectErrorStream(true);
        pb.environment().put("LD_LIBRARY_PATH",CrackBenchmark.BASE+":/vendor/lib64");process=pb.start();input=process.getOutputStream();
        reader=new Thread(()->{try(BufferedReader in=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){String l;while((l=in.readLine())!=null){if(l.length()>4096)l=l.substring(0,4096);if(!lines.offer(l))break;}}catch(IOException ignored){}});reader.start();
        try{await("ready",60000);}catch(Exception e){close();throw e;}
    }
    JSONObject await(String phase,long timeout)throws Exception {
        long deadline=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<deadline){
            String line=lines.poll(250,TimeUnit.MILLISECONDS);progress.update(new JSONObject());
            if(line!=null&&line.startsWith("LAMBDA_PMK ")){JSONObject r=new JSONObject(line.substring(11));if(r.optString("phase").equals(phase)){last=r;return r;}}
            if(line!=null&&line.startsWith("LAMBDA_GUARD "))throw new IOException("hybrid_stopped: "+new JSONObject(line.substring(13)).optString("reason"));
            if(line==null&&!process.isAlive())throw new IOException("hybrid_exit_"+process.exitValue());
        }throw new IOException("hybrid_timeout");
    }
    JSONObject calculate(int count)throws Exception {input.write("NEXT\n".getBytes(StandardCharsets.US_ASCII));input.flush();JSONObject r=await("batch",60000);if(r.optInt("count")!=count)throw new IOException("hybrid_count_mismatch");return r;}
    @Override public void close(){
        try{input.write("STOP\n".getBytes(StandardCharsets.US_ASCII));input.flush();input.close();}catch(Exception ignored){}
        try{if(!process.waitFor(500,TimeUnit.MILLISECONDS)){process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();}reader.join(1500);}catch(InterruptedException e){process.destroy();Thread.currentThread().interrupt();}
    }
}
