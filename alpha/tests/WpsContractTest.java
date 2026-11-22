package org.lambdaproject.lambda;
import org.json.*;
/** Run on Android with app_process. Pure contract tests: no Wi-Fi/worker calls. */
public final class WpsContractTest {
    static int count;
    interface Check {void run()throws Exception;}
    static void reject(String name,Check check)throws Exception {try{check.run();}catch(IllegalArgumentException e){count++;System.out.println("PASS "+name);return;}throw new AssertionError(name);}
    static JSONObject args()throws Exception{return new JSONObject().put("snapshot_id","fixture").put("bssid","02:00:00:00:00:01").put("mode","pixie").put("authorized",true);}
    static JSONObject scan(long now)throws Exception{return new JSONObject().put("tool","wifi.scan").put("state","completed").put("finished_ms",now).put("result",new JSONObject().put("networks",new JSONArray().put(new JSONObject().put("bssid","02:00:00:00:00:01").put("capabilities","[WPA2-PSK-CCMP][WPS]").put("age_ms",1000))));}
    public static void main(String[] ignored)throws Exception {
        long now=1700000000000L;
        WpsJob.validate(args());count++;
        WpsJob.validate(args().put("mode","pin").put("pin","12345670"));count++;
        WpsJob.validate(args().put("mode","pbc"));count++;
        JSONObject input=WpsJob.input(args(),scan(now),now);
        if(input.getLong("expires_ms")!=now+119000||!input.getString("interface").equals("wlan0"))throw new AssertionError("selection");count++;
        reject("consent false",()->WpsJob.validate(args().put("authorized",false)));
        reject("consent string",()->WpsJob.validate(args().put("authorized","true")));
        reject("invalid checksum",()->WpsJob.validate(args().put("mode","pin").put("pin","12345678")));
        reject("unknown mode",()->WpsJob.validate(args().put("mode","anything")));
        WpsJob.validate(args().put("mode","bruteforce").put("max_attempts",10).put("start_prefix","0000"));count++;
        reject("missing brute budget",()->WpsJob.validate(args().put("mode","bruteforce")));
        reject("excessive brute budget",()->WpsJob.validate(args().put("mode","bruteforce").put("max_attempts",1000).put("start_prefix","0000")));
        reject("string budget",()->WpsJob.validate(args().put("mode","bruteforce").put("max_attempts","10").put("start_prefix","0000")));
        reject("wrong prefix",()->WpsJob.validate(args().put("mode","bruteforce").put("max_attempts",10).put("start_prefix","1;id")));
        reject("irrelevant brute options",()->WpsJob.validate(args().put("max_attempts",10).put("start_prefix","0000")));
        JSONObject brute=WpsJob.input(args().put("mode","bruteforce").put("max_attempts",50).put("start_prefix","0123"),scan(now),now);
        if(brute.getInt("max_attempts")!=50||!brute.getString("start_prefix").equals("0123"))throw new AssertionError("brute input");count++;
        reject("injection",()->WpsJob.validate(args().put("bssid","02:00:00:00:00:01;id")));
        reject("multicast",()->WpsJob.validate(args().put("bssid","ff:ff:ff:ff:ff:ff")));
        reject("extra command",()->WpsJob.validate(args().put("command","id")));
        reject("missing scan",()->WpsJob.input(args(),null,now));
        reject("future scan",()->WpsJob.input(args(),scan(now+1),now));
        reject("expired scan",()->WpsJob.input(args(),scan(now-120001),now));
        reject("wrong tool",()->WpsJob.input(args(),scan(now).put("tool","usb.inventory"),now));
        reject("running scan",()->WpsJob.input(args(),scan(now).put("state","running"),now));
        reject("unknown target",()->WpsJob.input(args().put("bssid","02:00:00:00:00:02"),scan(now),now));
        JSONObject stale=scan(now);stale.getJSONObject("result").getJSONArray("networks").getJSONObject(0).put("age_ms",120001);
        reject("cached stale observation",()->WpsJob.input(args(),stale,now));
        JSONObject noWps=scan(now);noWps.getJSONObject("result").getJSONArray("networks").getJSONObject(0).put("capabilities","[WPA2-PSK-CCMP]");
        reject("no WPS advertised",()->WpsJob.input(args(),noWps,now));
        System.out.println("PASS "+count+" contracts; no radio operations");
    }
}
