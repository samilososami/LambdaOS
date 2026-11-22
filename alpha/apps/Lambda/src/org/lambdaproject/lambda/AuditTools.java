package org.lambdaproject.lambda;

import android.Manifest;
import android.app.AppOpsManager;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.usb.*;
import android.location.LocationManager;
import android.net.*;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import android.provider.Settings;
import org.json.*;
import java.io.File;
import java.net.NetworkInterface;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded native adapters. No shell interpolation, root daemon or destructive radio operations. */
final class AuditTools {
    private final CoreEngine core;private final CoreEngine.Job job;private final Context c;private final JSONObject args;
    AuditTools(CoreEngine core,CoreEngine.Job job){this.core=core;this.job=job;c=core.context;args=job.args();}
    JSONObject run()throws Exception {
        switch(job.tool()){
            case "wifi.scan":return wifiScan(true);
            case "wifi.cached":return wifiScan(false);
            case "wifi.audit":case "wifi.channels": {
                JSONObject snap=core.snapshot(args,"wifi.scan","wifi.cached");
                JSONObject out=job.tool().equals("wifi.audit")?AuditAnalysis.wifi(snap.getJSONObject("result")):AuditAnalysis.channels(snap.getJSONObject("result"));
                return out.put("snapshot_id",snap.getString("id")).put("snapshot_created_ms",snap.getLong("created_ms"));
            }
            case "wifi.link":return wifiLink();
            case "wifi.signal":return wifiSignal();
            case "bluetooth.status":return bluetoothStatus();
            case "bluetooth.bonded":return bluetoothBonded();
            case "bluetooth.ble_scan":return bleScan();
            case "bluetooth.classic_scan":return classicScan();
            case "bluetooth.audit": {
                JSONObject snap=core.snapshot(args,"bluetooth.ble_scan","bluetooth.classic_scan","bluetooth.bonded");
                return AuditAnalysis.bluetooth(snap.getJSONObject("result")).put("snapshot_id",snap.getString("id"));
            }
            case "bluetooth.gatt":return gatt();
            case "usb.inventory":return usbInventory();
            case "usb.audit":return usbAudit();
            case "usb.watch":return usbWatch();
            case "usb.descriptors":return usbDescriptors();
            case "system.capabilities":return capabilities();
            default:throw new IllegalArgumentException("unknown_tool");
        }
    }
    private void permission(String p){
        if(c.checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("Grant "+p);
        if(p.equals(Manifest.permission.ACCESS_FINE_LOCATION)){
            AppOpsManager ops=c.getSystemService(AppOpsManager.class);
            int access=ops==null?AppOpsManager.MODE_ERRORED:ops.noteOpNoThrow(
                AppOpsManager.OPSTR_FINE_LOCATION,android.os.Process.myUid(),c.getPackageName(),c.getAttributionTag(),"Wi-Fi audit");
            if(access!=AppOpsManager.MODE_ALLOWED)
                throw new SecurityException("location_access_not_allowed: background Wi-Fi audits require Location allowed all the time");
        }
    }
    private WifiManager wifi(){WifiManager w=c.getSystemService(WifiManager.class);if(w==null)throw new IllegalStateException("wifi_unavailable");return w;}
    private void wifiPermission(){permission(Manifest.permission.ACCESS_FINE_LOCATION);if(!c.getSystemService(LocationManager.class).isLocationEnabled())throw new IllegalStateException("location_disabled: Android requires Location for Wi-Fi scanning");}
    private void await(CountDownLatch latch,int seconds)throws Exception {long end=SystemClock.elapsedRealtime()+seconds*1000L;while(SystemClock.elapsedRealtime()<end){job.check();if(latch.await(100,TimeUnit.MILLISECONDS))return;}}
    private JSONObject wifiScan(boolean fresh)throws Exception {
        wifiPermission();WifiManager w=wifi();if(!w.isWifiEnabled())throw new IllegalStateException("wifi_disabled");
        boolean requested=false,updated=false;long start=SystemClock.elapsedRealtime();
        if(fresh){
            CountDownLatch done=new CountDownLatch(1);boolean[] ok={false};
            BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context context,Intent i){ok[0]=i.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED,false);done.countDown();}};
            c.registerReceiver(receiver,new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),Context.RECEIVER_EXPORTED);
            try {requested=w.startScan();if(requested)await(done,18);updated=done.getCount()==0&&ok[0];}finally{c.unregisterReceiver(receiver);}
        }
        List<android.net.wifi.ScanResult> scans=new ArrayList<>(w.getScanResults());scans.sort((a,b)->Integer.compare(b.level,a.level));
        JSONArray rows=new JSONArray();long now=SystemClock.elapsedRealtime();int freshCount=0;
        for(android.net.wifi.ScanResult s:scans){if(rows.length()>=128)break;
            long age=Math.max(0,now-s.timestamp/1000);if(s.timestamp/1000>=start-1000)freshCount++;
            rows.put(new JSONObject().put("ssid",s.SSID).put("bssid",s.BSSID).put("rssi_dbm",s.level)
                .put("frequency_mhz",s.frequency).put("channel",AuditAnalysis.channel(s.frequency)).put("band",AuditAnalysis.band(s.frequency))
                .put("width_code",s.channelWidth).put("capabilities",s.capabilities).put("security",AuditAnalysis.security(s.capabilities)).put("age_ms",age));
        }
        boolean current=fresh&&requested&&updated&&(rows.length()==0||freshCount>0);
        return new JSONObject().put("networks",rows).put("total_observed",scans.size()).put("truncated",scans.size()>128)
            .put("fresh",current).put("scan_requested",requested).put("results_updated",updated).put("partial",fresh&&!current)
            .put("source",current?"Android scan broadcast + observation timestamps":"Android scan cache")
            .put("summary",rows.length()+" BSSIDs · "+(current?"fresh scan":"cached observations"))
            .put("note",current?"Active Android discovery; not monitor mode.":"Cached results are not evidence of a new successful scan. Android can throttle scan requests.");
    }
    private JSONObject wifiLink()throws Exception {
        permission(Manifest.permission.ACCESS_FINE_LOCATION);
        WifiInfo w=wifi().getConnectionInfo();JSONObject r=new JSONObject().put("enabled",wifi().isWifiEnabled());
        if(w!=null)r.put("ssid",w.getSSID()).put("bssid",w.getBSSID()).put("rssi_dbm",w.getRssi()).put("link_speed_mbps",w.getLinkSpeed())
            .put("frequency_mhz",w.getFrequency()).put("supplicant_state",w.getSupplicantState().name());
        ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);JSONArray links=new JSONArray();
        for(Network n:cm.getAllNetworks()){
            NetworkCapabilities caps=cm.getNetworkCapabilities(n);if(caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))continue;
            LinkProperties p=cm.getLinkProperties(n);JSONObject entry=new JSONObject().put("validated",caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            if(p!=null){JSONArray addresses=new JSONArray(),dns=new JSONArray(),routes=new JSONArray();
                for(LinkAddress a:p.getLinkAddresses())addresses.put(a.toString());for(java.net.InetAddress a:p.getDnsServers())dns.put(a.getHostAddress());for(RouteInfo route:p.getRoutes())routes.put(route.toString());
                entry.put("interface",p.getInterfaceName()).put("addresses",addresses).put("dns",dns).put("routes",routes).put("mtu",p.getMtu()).put("private_dns_active",p.isPrivateDnsActive());}
            links.put(entry);
        }
        return r.put("links",links).put("summary",links.length()==0?"No active Wi-Fi link":"Wi-Fi link and route configuration");
    }
    private JSONObject wifiSignal()throws Exception {
        permission(Manifest.permission.ACCESS_FINE_LOCATION);WifiManager w=wifi();if(!w.isWifiEnabled())throw new IllegalStateException("wifi_disabled");
        WifiInfo first=w.getConnectionInfo();if(first==null||first.getNetworkId()<0)throw new IllegalStateException("wifi_not_connected");
        JSONArray samples=new JSONArray();int seconds=CoreEngine.duration(args,12),min=0,max=-127,sum=0;String target=first.getBSSID();
        for(int i=0;i<seconds;i++){job.check();WifiInfo info=w.getConnectionInfo();if(info==null||info.getNetworkId()<0||!Objects.equals(target,info.getBSSID()))throw new IllegalStateException("wifi_link_changed");
            int rssi=info.getRssi();samples.put(new JSONObject().put("elapsed_s",i).put("rssi_dbm",rssi).put("speed_mbps",info.getLinkSpeed()));min=Math.min(min,rssi);max=Math.max(max,rssi);sum+=rssi;job.pause(1000);}
        return new JSONObject().put("samples",samples).put("bssid",target).put("min_dbm",min).put("max_dbm",max).put("average_dbm",sum/(double)seconds)
            .put("summary",seconds+" samples · "+Math.round(sum/(double)seconds)+" dBm average").put("scope","Android-reported RSSI of the existing connection. No throughput or packet-loss test.");
    }
    private BluetoothAdapter bt(boolean enabled){permission(Manifest.permission.BLUETOOTH_CONNECT);BluetoothManager m=c.getSystemService(BluetoothManager.class);BluetoothAdapter a=m==null?null:m.getAdapter();if(a==null)throw new IllegalStateException("bluetooth_unavailable");if(enabled&&!a.isEnabled())throw new IllegalStateException("bluetooth_disabled");return a;}
    private JSONObject bluetoothStatus()throws Exception {
        BluetoothAdapter a=bt(false);return new JSONObject().put("enabled",a.isEnabled()).put("name",a.getName()).put("discovering",a.isDiscovering())
            .put("le_2m_phy",a.isLe2MPhySupported()).put("le_coded_phy",a.isLeCodedPhySupported()).put("extended_advertising",a.isLeExtendedAdvertisingSupported())
            .put("offloaded_filtering",a.isOffloadedFilteringSupported()).put("bonded_count",a.getBondedDevices().size()).put("summary",a.isEnabled()?"Bluetooth enabled":"Bluetooth disabled");
    }
    private JSONObject device(BluetoothDevice d)throws Exception {
        JSONObject r=new JSONObject().put("address",d.getAddress()).put("name",d.getName()==null?"":d.getName()).put("type",d.getType()).put("bond_state",d.getBondState());
        BluetoothClass cl=d.getBluetoothClass();if(cl!=null)r.put("class",cl.getDeviceClass());
        ParcelUuid[] ids=d.getUuids();JSONArray uuids=new JSONArray();if(ids!=null)for(ParcelUuid id:ids)uuids.put(id.toString());return r.put("cached_uuids",uuids);
    }
    private JSONObject bluetoothBonded()throws Exception {
        JSONArray out=new JSONArray();for(BluetoothDevice d:bt(false).getBondedDevices())out.put(device(d));return new JSONObject().put("devices",out).put("summary",out.length()+" paired devices").put("scope","Local pairing records and cached UUIDs; no remote connection.");
    }
    private JSONObject bleScan()throws Exception {
        permission(Manifest.permission.BLUETOOTH_SCAN);BluetoothLeScanner scanner=bt(true).getBluetoothLeScanner();if(scanner==null)throw new IllegalStateException("ble_scanner_unavailable");
        Map<String,JSONObject> seen=Collections.synchronizedMap(new LinkedHashMap<>());AtomicInteger failure=new AtomicInteger(0);int seconds=CoreEngine.duration(args,12);
        ScanCallback callback=new ScanCallback(){
            public void onScanResult(int type,android.bluetooth.le.ScanResult s){
                try {synchronized(seen){String address=s.getDevice().getAddress();if(seen.size()>=128&&!seen.containsKey(address))return;
                    JSONObject r=device(s.getDevice()).put("rssi_dbm",s.getRssi()).put("connectable",s.isConnectable()).put("last_seen_ms",System.currentTimeMillis());
                    ScanRecord rec=s.getScanRecord();if(rec!=null){if(rec.getDeviceName()!=null)r.put("name",rec.getDeviceName());
                        r.put("advertisement_hex",hex(rec.getBytes(),512)).put("tx_power_dbm",rec.getTxPowerLevel());JSONArray services=new JSONArray();if(rec.getServiceUuids()!=null)for(ParcelUuid id:rec.getServiceUuids())services.put(id.toString());r.put("service_uuids",services);
                        JSONArray manufacturers=new JSONArray();for(int i=0;i<rec.getManufacturerSpecificData().size();i++)manufacturers.put(String.format(Locale.ROOT,"0x%04X",rec.getManufacturerSpecificData().keyAt(i)));r.put("manufacturer_ids",manufacturers);
                    }seen.put(address,r);}
                }catch(Exception e){failure.compareAndSet(0,-1);}
            }
            public void onScanFailed(int code){failure.set(code);}
        };
        try {
            scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build(),callback);
            for(int i=0;i<seconds*10;i++){job.pause(100);if(failure.get()!=0)throw new IllegalStateException("ble_scan_failed_"+failure.get());}
        }finally{scanner.stopScan(callback);}
        JSONArray rows; synchronized(seen){rows=new JSONArray(seen.values());}
        return new JSONObject().put("devices",rows).put("seconds",seconds).put("summary",rows.length()+" BLE advertisers · "+seconds+" s")
            .put("scope","Advertisements/scan responses only. No pairing, GATT access or writes.").put("limit",128);
    }
    private JSONObject classicScan()throws Exception {
        permission(Manifest.permission.BLUETOOTH_SCAN);BluetoothAdapter a=bt(true);if(a.isDiscovering())throw new IllegalStateException("bluetooth_discovery_busy");
        Map<String,JSONObject> seen=Collections.synchronizedMap(new LinkedHashMap<>());CountDownLatch done=new CountDownLatch(1);int seconds=CoreEngine.duration(args,15);
        BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context context,Intent i){try{
            if(BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(i.getAction())){done.countDown();return;}
            BluetoothDevice d=i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice.class);
            if(d!=null)synchronized(seen){if(seen.size()<128||seen.containsKey(d.getAddress()))seen.put(d.getAddress(),device(d).put("rssi_dbm",i.getShortExtra(BluetoothDevice.EXTRA_RSSI,(short)-127)));}
        }catch(Exception ignored){}}};
        IntentFilter filter=new IntentFilter(BluetoothDevice.ACTION_FOUND);filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        c.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED);boolean started=false;
        try{started=a.startDiscovery();if(!started)throw new IllegalStateException("classic_discovery_rejected");await(done,seconds);}
        finally{if(started)a.cancelDiscovery();c.unregisterReceiver(receiver);}
        JSONArray rows;synchronized(seen){rows=new JSONArray(seen.values());}
        return new JSONObject().put("devices",rows).put("summary",rows.length()+" discoverable Classic devices").put("scope","Active Bluetooth discovery; not pairing or service enumeration.");
    }
    private JSONObject gatt()throws Exception {
        permission(Manifest.permission.BLUETOOTH_SCAN);BluetoothAdapter a=bt(true);
        JSONObject snap=core.snapshot(args,"bluetooth.ble_scan");String address=args.getString("address");boolean found=false;
        if(System.currentTimeMillis()-snap.getLong("created_ms")>300000)throw new IllegalArgumentException("snapshot_expired: rescan before connecting");
        JSONArray devices=snap.getJSONObject("result").getJSONArray("devices");for(int i=0;i<devices.length();i++){JSONObject d=devices.getJSONObject(i);if(address.equals(d.optString("address"))&&d.optBoolean("connectable"))found=true;}
        if(!found)throw new IllegalArgumentException("target_not_connectable_in_snapshot");
        CountDownLatch done=new CountDownLatch(1);JSONArray services=new JSONArray();AtomicInteger status=new AtomicInteger(-1);
        BluetoothGattCallback cb=new BluetoothGattCallback(){
            public void onConnectionStateChange(BluetoothGatt g,int s,int state){if(s!=BluetoothGatt.GATT_SUCCESS){status.set(s);done.countDown();}else if(state==BluetoothProfile.STATE_CONNECTED){if(!g.discoverServices()){status.set(-2);done.countDown();}}else if(state==BluetoothProfile.STATE_DISCONNECTED){done.countDown();}}
            public void onServicesDiscovered(BluetoothGatt g,int s){status.set(s);try{if(s==0)for(BluetoothGattService service:g.getServices()){
                JSONArray chars=new JSONArray();for(BluetoothGattCharacteristic ch:service.getCharacteristics()){
                    JSONArray descriptors=new JSONArray();for(BluetoothGattDescriptor d:ch.getDescriptors())descriptors.put(d.getUuid().toString());
                    chars.put(new JSONObject().put("uuid",ch.getUuid().toString()).put("properties",ch.getProperties()).put("descriptors",descriptors));}
                services.put(new JSONObject().put("uuid",service.getUuid().toString()).put("characteristics",chars));
            }}catch(Exception e){status.set(-3);}done.countDown();}
        };
        BluetoothGatt g=a.getRemoteDevice(address).connectGatt(c,false,cb,BluetoothDevice.TRANSPORT_LE);if(g==null)throw new IllegalStateException("gatt_connect_failed");
        try{await(done,20);if(done.getCount()!=0)throw new IllegalStateException("gatt_timeout");if(status.get()!=0)throw new IllegalStateException("gatt_status_"+status.get());
            return new JSONObject().put("address",address).put("services",services).put("summary",services.length()+" GATT services")
                .put("scope","Connection and service discovery only. No characteristic values read, written or subscribed. Properties are not an authentication test.");
        }finally{g.disconnect();g.close();}
    }
    private UsbManager usb(){return c.getSystemService(UsbManager.class);}
    private JSONObject usbDevice(UsbDevice d)throws Exception {
        JSONObject r=new JSONObject().put("device",d.getDeviceName()).put("name",d.getProductName()).put("vendor_id",d.getVendorId()).put("product_id",d.getProductId())
            .put("vid_pid",String.format(Locale.ROOT,"%04x:%04x",d.getVendorId(),d.getProductId())).put("class",d.getDeviceClass()).put("permission",usb().hasPermission(d));
        JSONArray interfaces=new JSONArray();for(int i=0;i<d.getInterfaceCount();i++){UsbInterface face=d.getInterface(i);JSONArray eps=new JSONArray();for(int j=0;j<face.getEndpointCount();j++){UsbEndpoint ep=face.getEndpoint(j);
            eps.put(new JSONObject().put("address",ep.getAddress()).put("direction",ep.getDirection()==UsbConstants.USB_DIR_IN?"IN":"OUT").put("type",ep.getType()).put("max_packet_size",ep.getMaxPacketSize()).put("interval",ep.getInterval()));}
            interfaces.put(new JSONObject().put("id",face.getId()).put("class",face.getInterfaceClass()).put("subclass",face.getInterfaceSubclass()).put("protocol",face.getInterfaceProtocol()).put("endpoints",eps));}
        return r.put("interfaces",interfaces);
    }
    private JSONObject usbInventory()throws Exception {
        if(usb()==null)throw new IllegalStateException("usb_unavailable");JSONArray devices=new JSONArray();for(UsbDevice d:usb().getDeviceList().values()){if(devices.length()<64)devices.put(usbDevice(d));}
        return new JSONObject().put("devices",devices).put("host_feature",c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST))
            .put("summary",devices.length()+" USB host devices").put("scope","Devices attached to the S10 in USB host/OTG mode. The PC providing ADB is not a host-mode peripheral.");
    }
    private JSONObject usbAudit()throws Exception {
        JSONObject inventory=usbInventory();JSONArray findings=new JSONArray(),devices=inventory.getJSONArray("devices");
        for(int i=0;i<devices.length();i++){JSONObject d=devices.getJSONObject(i);JSONArray interfaces=d.getJSONArray("interfaces");Set<Integer> classes=new HashSet<>();for(int j=0;j<interfaces.length();j++)classes.add(interfaces.getJSONObject(j).getInt("class"));
            if(classes.contains(UsbConstants.USB_CLASS_HID))findings.put(AuditAnalysis.finding("review","hid_interface",d.getString("vid_pid"),"HID input interface present. Presence alone is not evidence of malicious input."));
            if(classes.contains(UsbConstants.USB_CLASS_MASS_STORAGE))findings.put(AuditAnalysis.finding("info","mass_storage",d.getString("vid_pid"),"Mass-storage interface present. No filesystem was opened or modified."));
            if(classes.size()>1)findings.put(AuditAnalysis.finding("review","composite_device",d.getString("vid_pid"),"Multiple USB interface classes. Verify they match the intended accessory."));
        }
        boolean adb=Settings.Global.getInt(c.getContentResolver(),Settings.Global.ADB_ENABLED,0)!=0;
        findings.put(AuditAnalysis.finding("info","adb_setting","Samsung S10",adb?"USB debugging enabled. This setting alone does not prove the currently authorized hosts.":"USB debugging setting disabled."));
        return inventory.put("findings",findings).put("adb_enabled",adb).put("summary",devices.length()+" USB devices · "+findings.length()+" observations");
    }
    private JSONObject usbWatch()throws Exception {
        JSONArray events=new JSONArray();JSONObject before=usbInventory();
        BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context context,Intent i){try{synchronized(events){if(events.length()<128){UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE,UsbDevice.class);events.put(new JSONObject().put("time_ms",System.currentTimeMillis()).put("event",i.getAction()).put("device",d==null?JSONObject.NULL:usbDevice(d)));}}}catch(Exception ignored){}}};
        IntentFilter filter=new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);c.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED);
        int seconds=CoreEngine.duration(args,15);try{job.pause(seconds*1000L);}finally{c.unregisterReceiver(receiver);}
        return new JSONObject().put("before",before).put("after",usbInventory()).put("events",events).put("summary",events.length()+" USB changes · "+seconds+" s");
    }
    private JSONObject usbDescriptors()throws Exception {
        JSONObject snap=core.snapshot(args,"usb.inventory","usb.audit");String name=args.getString("device");JSONObject expected=null;
        if(System.currentTimeMillis()-snap.getLong("created_ms")>300000)throw new IllegalArgumentException("snapshot_expired");
        JSONArray items=snap.getJSONObject("result").getJSONArray("devices");for(int i=0;i<items.length();i++)if(name.equals(items.getJSONObject(i).optString("device")))expected=items.getJSONObject(i);
        UsbDevice d=usb().getDeviceList().get(name);if(expected==null||d==null||expected.getInt("vendor_id")!=d.getVendorId()||expected.getInt("product_id")!=d.getProductId())throw new IllegalStateException("usb_target_changed");
        if(!usb().hasPermission(d))throw new SecurityException("usb_permission_required");
        UsbDeviceConnection connection=usb().openDevice(d);if(connection==null)throw new IllegalStateException("usb_open_failed");
        try {byte[] raw=connection.getRawDescriptors();return new JSONObject().put("device",name).put("descriptors_hex",hex(raw,4096)).put("bytes",raw==null?0:raw.length)
            .put("summary",(raw==null?0:raw.length)+" descriptor bytes").put("scope","Cached standard descriptors only. No interface claimed and no control/bulk/HID transfer sent.");}finally{connection.close();}
    }
    private JSONObject capabilities()throws Exception {
        JSONObject r=HardwareSnapshot.collect(c);JSONArray interfaces=new JSONArray();Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();
        if(all!=null)while(all.hasMoreElements()){NetworkInterface n=all.nextElement();interfaces.put(new JSONObject().put("name",n.getName()).put("up",n.isUp()).put("loopback",n.isLoopback()).put("mtu",n.getMTU()));}
        r.put("interfaces",interfaces).put("core_process",android.os.Process.myPid()).put("uid",android.os.Process.myUid())
            .put("summary","Native Wi-Fi / Bluetooth / USB adapters · Android "+Build.VERSION.RELEASE)
            .put("monitor_mode","not_verified").put("packet_injection","not_verified").put("hid_emulation","not_exercised")
            .put("scope","This service uses granted Android APIs. Kernel build flags do not establish firmware or attached-adapter functionality.");return r;
    }
    private static String hex(byte[] bytes,int limit){if(bytes==null)return "";StringBuilder s=new StringBuilder();for(int i=0;i<Math.min(bytes.length,limit);i++)s.append(String.format(Locale.ROOT,"%02x",bytes[i]&255));return s.toString();}
}
