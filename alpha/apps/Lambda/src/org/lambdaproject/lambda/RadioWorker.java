package org.lambdaproject.lambda;

import android.content.Context;
import android.os.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Maintenance worker launched explicitly by authorized ADB root, never by an APK.
 * Allowlisted monitor/survey/EAPOL/one-cycle deauth/restore. No shell interpolation.
 */
public final class RadioWorker {
    private static final String BASE="/data/local/tmp/lambda-operator-radio";
    private static final String NEX=BASE+"/nexutil",LIB=BASE+"/libnexmon.so";
    private final String identity=UUID.randomUUID().toString();
    private Object provider,activityManager;
    private java.lang.reflect.Method providerCall;
    private final Binder providerToken=new Binder();
    private String token,iface,ifindex,kind,originalType,session;
    private boolean originalWifi,originalUp,changed;private volatile boolean closing;
    private long lease;
    private java.lang.Process capture;
    private DeauthBurst burst;
    private final LinkedHashMap<String,JSONObject> networks=new LinkedHashMap<>();
    private final HashMap<String,Integer> observationChannels=new HashMap<>();
    private java.nio.channels.FileLock processLock;
    public static void main(String[] args)throws Exception {
        if(android.os.Process.myUid()!=0)throw new SecurityException("Requires authorized maintenance root");
        if(!read("/sys/class/dmi/id/product_name").isEmpty())throw new IllegalStateException("Android only");
        RadioWorker w=new RadioWorker();w.start();
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
        Object holder=Class.forName("android.app.IActivityManager").getMethod("getContentProviderExternal",String.class,int.class,IBinder.class,String.class)
            .invoke(activityManager,"org.lambdaproject.lambda.core",0,providerToken,"LambdaRadioWorker");
        if(holder==null)throw new IOException("core_provider_missing");
        provider=holder.getClass().getField("provider").get(holder);
        providerCall=Class.forName("android.content.IContentProvider").getMethod("call",android.content.AttributionSource.class,String.class,String.class,String.class,Bundle.class);
        if(!cmd("/system/bin/getprop","ro.product.device").trim().equals("beyond1lte"))throw new IllegalStateException("Unexpected device");
        if(!cmd("/system/bin/getenforce").trim().equals("Enforcing"))throw new IllegalStateException("SELinux must remain enforcing");
        // Recover an interrupted internal monitor session before accepting work.
        File journal=new File(BASE,"restore.json");
        if(journal.exists()){JSONObject old=new JSONObject(read(journal.getPath()));iface=old.getString("iface");ifindex=old.getString("ifindex");kind=old.getString("kind");originalType=old.optString("type","managed");originalWifi=old.optBoolean("wifi");originalUp=old.optBoolean("up");changed=true;restore();}
        WpsJob.recover();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{closing=true;try{restore();}catch(Exception ignored){}}));
        System.out.println("Lambda radio worker ready; allowlisted bounded operations; SELinux enforcing");
        try {
            while(!closing&&!Thread.currentThread().isInterrupted()){
                if(changed&&SystemClock.elapsedRealtime()>lease)restore();
                JSONObject response=rpc(new JSONObject().put("adapters",inventory()).put("benchmark",CrackBenchmark.capabilities()).put("wps",WpsJob.capabilities()));
                if(!response.optBoolean("ok"))throw new IOException(response.optString("detail","worker_rejected"));
                JSONObject command=response.optJSONObject("command");
                if(command!=null)execute(command);else Thread.sleep(1200);
            }
        }finally{restore();processLock.release();lockFile.close();}
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
            if(tool.equals("radio.scan"))result=survey(a);
            else if(tool.equals("radio.capture"))result=record(a);
            else if(tool.equals("radio.deauth"))result=deauth(a);
            else if(tool.equals("radio.release")){CoreEngine.keys(a);restore();result=new JSONObject().put("restored",true);}
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
    private static String read(String p){try{return new String(java.nio.file.Files.readAllBytes(new File(p).toPath()),StandardCharsets.UTF_8).trim();}catch(Exception e){return "";}}
    private static String cmd(String... args)throws Exception {
        java.lang.Process p=new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        Thread drain=new Thread(()->{try{byte[] b=new byte[4096];int n;while((n=p.getInputStream().read(b))!=-1)if(out.size()<200000)out.write(b,0,n);}catch(IOException ignored){}});drain.start();
        if(!p.waitFor(7,TimeUnit.SECONDS)){p.destroyForcibly();drain.join(1000);throw new IOException("command_timeout: "+new File(args[0]).getName());}
        drain.join(1000);String value=out.toString("UTF-8");
        if(p.exitValue()!=0)throw new IOException(new File(args[0]).getName()+": "+value.substring(0,Math.min(300,value.length())));
        return value;
    }
    private static String match(String text,String regex){Matcher m=Pattern.compile(regex).matcher(text);return m.find()?m.group(1):"";}
    private String nex(String... extra)throws Exception {
        ArrayList<String> args=new ArrayList<>(Arrays.asList(NEX,"-I","wlan0"));args.addAll(Arrays.asList(extra));
        String out=cmd(args.toArray(new String[0]));if(out.contains("error")||out.contains("Error")||out.contains("failed"))throw new IOException("nexmon: "+out.substring(0,Math.min(180,out.length())));return out;
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
        JSONObject journal=new JSONObject().put("iface",iface).put("ifindex",ifindex).put("kind",kind).put("type",originalType).put("wifi",originalWifi).put("up",originalUp);
        try(FileOutputStream out=new FileOutputStream(new File(BASE,"restore.json"))){out.write(journal.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        changed=true;lease=SystemClock.elapsedRealtime()+120000;
        tick(new JSONObject().put("phase","activating").put("adapter",d));
        if(kind.equals("internal")){
            if(!nex("-g0x600","-l128").contains("nexmon"))throw new IOException("nexmon_not_verified");
            cmd("/system/bin/svc","wifi","disable");Thread.sleep(2200);cmd("/system/bin/ip","link","set",iface,"up");
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
        boolean same=ifindex!=null&&ifindex.equals(read("/sys/class/net/"+iface+"/ifindex"));
        if(kind.equals("internal")){
            try{nex("-m0");}catch(Exception e){errors.add("monitor_disable");}
            try{cmd("/system/bin/svc","wifi",originalWifi?"enable":"disable");}catch(Exception e){errors.add("wifi_restore");}
        }else if(same){
            try{cmd("/system/bin/ip","link","set",iface,"down");cmd("/system/bin/iw","dev",iface,"set","type",originalType);if(originalUp)cmd("/system/bin/ip","link","set",iface,"up");}catch(Exception e){errors.add("external_restore");}
        }
        if(!errors.isEmpty())throw new IOException("restore_failed: "+errors);
        changed=false;session=null;new File(BASE,"restore.json").delete();
    }
    private JSONObject survey(JSONObject args)throws Exception {
        CoreEngine.keys(args,"adapter");prepare(args.getString("adapter"));ArrayList<Integer> channels=channels();
        File file=new File(BASE,"survey.pcap");startCapture(file,"type mgt subtype beacon or type mgt subtype probe-resp");
        PcapTail tail=new PcapTail(file);ArrayList<Integer> failed=new ArrayList<>();
        try {
            for(int pass=0;pass<2;pass++)for(int ch:channels){
                tick(new JSONObject().put("phase","scanning").put("channel",ch).put("networks",networks.size()).put("band","2.4 GHz"));
                try{channel(ch);}catch(IOException e){if(!failed.contains(ch))failed.add(ch);continue;}
                Thread.sleep(500);present();if(!capture.isAlive())throw new IOException("tcpdump_failed: "+read(BASE+"/tcpdump.log"));
                tail.drain((packet,record)->{WifiFrames.Frame f=WifiFrames.decode(packet,tail.link);if(f==null||!f.beacon||f.bssid.isEmpty())return;
                    int observed=f.channel==0?ch:f.channel;if(observed!=ch)return;
                    JSONObject old=networks.get(f.bssid);if(old==null&&networks.size()>=128)return;
                    JSONObject n=new JSONObject().put("ssid",f.ssid).put("bssid",f.bssid).put("channel",observed).put("security",f.security).put("rssi_dbm",f.signal).put("capture_supported",f.security.equals("WPA2")||f.security.equals("WPA")||f.security.equals("WPA2/WPA3"));
                    networks.put(f.bssid,n);observationChannels.put(f.bssid,observed);
                });
                if(file.length()>8*1024*1024)throw new IOException("survey_size_limit");
            }
        }finally{stopCapture();tail.close();file.delete();}
        lease=SystemClock.elapsedRealtime()+120000;
        JSONArray rows=new JSONArray();ArrayList<JSONObject> sorted=new ArrayList<>(networks.values());sorted.sort((x,y)->Integer.compare(y.optInt("rssi_dbm"),x.optInt("rssi_dbm")));for(JSONObject n:sorted)rows.put(n);
        return new JSONObject().put("session",session).put("networks",rows).put("band","2.4 GHz").put("failed_channels",new JSONArray(failed)).put("lease_seconds",120).put("expires_elapsed_ms",lease).put("monitor",true).put("partial",!failed.isEmpty());
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
    interface Packet {void accept(byte[] bytes,byte[] record)throws Exception;}
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
