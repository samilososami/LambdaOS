package org.lambdaproject.lambda;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.TreeMap;

/** Pure, deterministic observations, not claims that a device is exploitable. */
final class AuditAnalysis {
    static String security(String flags) {
        String c=flags.toUpperCase(Locale.ROOT);
        if(c.contains("WEP"))return "WEP";
        if(c.contains("SAE"))return c.contains("PSK")?"WPA2/WPA3":"WPA3";
        if(c.contains("OWE"))return "OWE";
        if(c.contains("EAP"))return "Enterprise";
        if(c.contains("RSN")||c.contains("WPA2"))return "WPA2";
        if(c.contains("WPA"))return "WPA";
        return "Open";
    }
    static int channel(int mhz) {
        if(mhz==2484)return 14;
        if(mhz>=2412&&mhz<=2472)return (mhz-2407)/5;
        if(mhz==5935)return 2;
        if(mhz>=5955&&mhz<=7115)return (mhz-5950)/5;
        if(mhz>=5000&&mhz<=5895)return (mhz-5000)/5;
        return 0;
    }
    static String band(int mhz){return mhz<3000?"2.4 GHz":mhz<5925?"5 GHz":"6 GHz";}
    static JSONObject finding(String level,String code,String subject,String detail)throws Exception {
        return new JSONObject().put("level",level).put("code",code).put("subject",subject).put("detail",detail);
    }
    static JSONObject wifi(JSONObject source)throws Exception {
        JSONArray rows=source.getJSONArray("networks"),findings=new JSONArray();
        int open=0,legacy=0,wps=0;
        TreeMap<String,java.util.Set<String>> modes=new TreeMap<>();
        for(int i=0;i<rows.length();i++){
            JSONObject n=rows.getJSONObject(i);String sec=n.getString("security"),name=n.optString("ssid");
            String subject=(name.isEmpty()?"Hidden SSID":name)+" · "+n.optString("bssid");
            if(sec.equals("Open")){open++;findings.put(finding("warning","open_network",subject,"No link-layer encryption advertised; this is not a traffic interception test."));}
            if(sec.equals("WEP")||sec.equals("WPA")||n.optString("capabilities").contains("[WPA-")){legacy++;findings.put(finding("warning","legacy_security",subject,"Legacy WEP/WPA protection advertised, possibly alongside WPA2. Verify the access-point configuration."));}
            if(n.optString("capabilities").contains("TKIP"))findings.put(finding("warning","tkip_advertised",subject,"TKIP advertised; prefer AES/CCMP on your access point."));
            if(n.optString("capabilities").contains("WPS")){wps++;findings.put(finding("info","wps_advertised",subject,"WPS advertised. No PIN attempt or vulnerability test was performed."));}
            if(!name.isEmpty()){if(!modes.containsKey(name))modes.put(name,new java.util.HashSet<>());modes.get(name).add(sec);}
        }
        for(String name:modes.keySet())if(modes.get(name).size()>1) findings.put(finding("review","mixed_security_ssid",name,"The same SSID advertises different security modes. This can be legitimate; it is not proof of an evil twin."));
        return new JSONObject().put("summary",rows.length()+" BSSIDs · "+open+" open · "+legacy+" legacy · "+wps+" WPS")
            .put("findings",findings).put("bssid_count",rows.length()).put("open_count",open).put("legacy_count",legacy).put("wps_count",wps)
            .put("scope","Advertisement analysis only; no authentication, deauthentication, injection or password testing.");
    }
    static JSONObject channels(JSONObject source)throws Exception {
        TreeMap<String,JSONObject> counts=new TreeMap<>();JSONArray rows=source.getJSONArray("networks");
        for(int i=0;i<rows.length();i++){
            JSONObject n=rows.getJSONObject(i);int f=n.getInt("frequency_mhz");String k=String.format(Locale.ROOT,"%05d",f);
            JSONObject c=counts.get(k);if(c==null){c=new JSONObject().put("channel",channel(f)).put("frequency_mhz",f).put("band",band(f)).put("count",0).put("strongest_dbm",-127);counts.put(k,c);}
            c.put("count",c.getInt("count")+1).put("strongest_dbm",Math.max(c.getInt("strongest_dbm"),n.getInt("rssi_dbm")));
        }
        return new JSONObject().put("channels",new JSONArray(counts.values())).put("summary",counts.size()+" primary channels observed")
            .put("scope","BSSID count by primary channel, not airtime utilization or spectrum interference. Channel widths may overlap.");
    }
    static JSONObject bluetooth(JSONObject source)throws Exception {
        JSONArray items=source.getJSONArray("devices"),findings=new JSONArray();
        for(int i=0;i<items.length();i++){
            JSONObject d=items.getJSONObject(i);String name=d.optString("name",d.optString("address"));
            if(!d.optString("name").isEmpty())findings.put(finding("info","advertised_name",name,"A device name is observable during discovery; this is not an identity guarantee."));
            if(d.optBoolean("connectable"))findings.put(finding("review","connectable_advertisement",name,"Connection capability advertised. Authentication and GATT permissions were not tested."));
        }
        return new JSONObject().put("summary",items.length()+" discovered devices reviewed").put("findings",findings)
            .put("scope","No vulnerability inference from names, manufacturer IDs, addresses or RSSI. BLE addresses can rotate.");
    }
}
