package org.lambdaproject.lambda;

import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Root initiates Binder RPC; no open network socket and no arbitrary shell API. */
final class RadioBridge {
    private static long heartbeat;
    private static String worker="";
    private static JSONArray adapters=new JSONArray();
    private static JSONObject benchmark=new JSONObject();
    private static JSONObject wps=new JSONObject();
    private static CoreEngine.Job active;
    private static JSONObject answer;
    private static String token;
    private static boolean claimed;
    private static JSONObject deauthControl;
    private static boolean deauthDelivered;
    static synchronized void checkDeauth(CoreEngine.Job job)throws Exception {
        if(active!=job||!claimed||answer!=null||!job.tool().equals("radio.capture")||job.cancelled)
            throw new IllegalStateException("capture_not_active");
        if(SystemClock.elapsedRealtime()-heartbeat>=12000)throw new IllegalStateException("radio_worker_offline");
        JSONObject progress=job.progress;
        if(progress==null||!progress.optString("phase").equals("capturing"))throw new IllegalStateException("capture_not_ready");
        if(progress.optLong("seconds")>=165)throw new IllegalStateException("capture_ending");
        if(deauthControl!=null)throw new IllegalStateException("capture_deauth_already_requested");
    }
    static synchronized void requestDeauth(CoreEngine.Job job,JSONObject audit)throws Exception {
        checkDeauth(job);
        deauthControl=new JSONObject().put("id",audit.getString("id")).put("capture_token",token)
            .put("session",job.args().getString("session")).put("bssid",job.args().getString("bssid"))
            .put("expires_elapsed_ms",SystemClock.elapsedRealtime()+5000);
        deauthDelivered=false;
    }
    static synchronized JSONObject status()throws Exception {
        return new JSONObject().put("ok",true).put("available",!worker.isEmpty()&&SystemClock.elapsedRealtime()-heartbeat<12000)
            .put("adapters",adapters).put("benchmark",benchmark).put("wps",wps).put("persistence","development_worker_until_reboot");
    }
    static synchronized JSONObject worker(JSONObject r,int uid,CoreEngine core)throws Exception {
        if(uid!=0)throw new SecurityException("root_worker_only");
        CoreEngine.keys(r,"worker","adapters","benchmark","wps","token","progress","result","error","capture_checkpoint");
        String name=CoreEngine.id(r,"worker");
        // The root worker holds an exclusive filesystem lock. A new identity
        // means recovery/restart, never permission to replay a claimed action.
        if(!worker.isEmpty()&&!worker.equals(name)&&active!=null&&claimed){
            answer=new JSONObject().put("error","radio_worker_restarted");RadioBridge.class.notifyAll();
        }
        worker=name;heartbeat=SystemClock.elapsedRealtime();
        if(r.has("wps"))wps=r.getJSONObject("wps");
        if(r.has("benchmark"))benchmark=r.getJSONObject("benchmark");
        if(r.has("adapters")){JSONArray list=r.getJSONArray("adapters");if(list.length()>32)throw new IllegalArgumentException("adapter_limit");adapters=list;}
        if(r.has("token")){
            if(active==null||!r.getString("token").equals(token))return new JSONObject().put("ok",false).put("cancel",true);
            if(r.has("capture_checkpoint"))core.captureCheckpoint(active,r.getJSONObject("capture_checkpoint"));
            if(r.has("progress"))active.progress=new JSONObject(r.getJSONObject("progress").toString());
            if(r.has("result")||r.has("error")){answer=new JSONObject(r.toString());RadioBridge.class.notifyAll();}
        }
        JSONObject reply=new JSONObject().put("ok",true).put("cancel",active!=null&&active.cancelled);
        if(active!=null&&!claimed){claimed=true;reply.put("command",new JSONObject().put("tool",active.tool()).put("args",active.workerInput==null?active.args():active.workerInput).put("token",token));}
        // At most once, only in a heartbeat from the running capture. No replay
        // after a lost reply, cancellation, completion, timeout or worker restart.
        if(active!=null&&answer==null&&!active.cancelled&&deauthControl!=null&&!deauthDelivered
                &&r.has("token")&&r.has("progress")&&r.getJSONObject("progress").optString("phase").equals("capturing")
                &&SystemClock.elapsedRealtime()<deauthControl.getLong("expires_elapsed_ms")){
            deauthDelivered=true;reply.put("capture_deauth",new JSONObject(deauthControl.toString()));
        }
        return reply;
    }
    static JSONObject run(CoreEngine.Job job)throws Exception {
        synchronized(RadioBridge.class){
            if(worker.isEmpty()||SystemClock.elapsedRealtime()-heartbeat>=12000)throw new IllegalStateException("radio_worker_offline");
            if(active!=null)throw new IllegalStateException("radio_busy");
            active=job;answer=null;claimed=false;token=UUID.randomUUID().toString();deauthControl=null;deauthDelivered=false;
        }
        long deadline=SystemClock.elapsedRealtime()+(job.tool().equals("crack.wpa")?3660000:job.tool().equals("wps.oneshot")?650000:360000);
        try {
            synchronized(RadioBridge.class){
                while(answer==null){
                    long missed=SystemClock.elapsedRealtime()-heartbeat;
                    // No command was handed out: do not claim a monitor change
                    // or a failed restoration of this task. Claimed operations
                    // retain the longer grace period and recovery warning.
                    if(!claimed&&missed>=12000)throw new IllegalStateException("radio_worker_offline");
                    if(missed>30000)throw new IllegalStateException("radio_worker_lost_restore_pending");
                    if(SystemClock.elapsedRealtime()>deadline){job.cancelled=true;throw new IllegalStateException("radio_worker_deadline");}
                    RadioBridge.class.wait(300);
                }
                if(answer.has("error"))throw new IllegalStateException(answer.getString("error"));
                JSONObject result=answer.getJSONObject("result");
                if(deauthControl!=null&&!result.has("deauth"))result.put("deauth",new JSONObject().put("id",deauthControl.getString("id"))
                    .put("outcome",deauthDelivered?"unconfirmed":"not_started").put("transmission_reported",false).put("effect_verified",false));
                return result;
            }
        }finally{synchronized(RadioBridge.class){active=null;answer=null;token=null;claimed=false;deauthControl=null;deauthDelivered=false;}}
    }
}
