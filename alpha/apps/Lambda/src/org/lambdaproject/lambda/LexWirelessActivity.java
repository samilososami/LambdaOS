package org.lambdaproject.lambda;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.location.LocationManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** LEX mode 3: find screens on Wi-Fi Direct (Miracast) and on the local
 *  network (Chromecast / AirPlay), and do what this build can actually do.
 *
 *  Miracast is the only sink protocol a normal app can drive, and the system
 *  owns the connection: without the platform signature (CONFIGURE_WIFI_DISPLAY)
 *  the direct call is refused, so the screen falls back to the system's cast
 *  picker. Chromecast and AirPlay are shown because their devices answer over
 *  LAN, but this build has no sender for them: Cast mirroring needs Google Play
 *  Services and AirPlay mirroring needs its own sender implementation. */
public final class LexWirelessActivity extends Activity {
    private static final int BG=0xff101112,SURFACE=0xff1D1F20,SELECTED=0xff243033,
        FG=0xffEEEEEC,SECONDARY=0xffA4A7A9;
    private static final int PERMISSION=11;
    private static final int KIND_MIRACAST=0,KIND_CAST=1,KIND_AIRPLAY=2;
    private static final String NSD_CAST="_googlecast._tcp.",NSD_AIRPLAY="_airplay._tcp.";

    private static final class Sink {
        final String key;
        final int kind;
        final String name;
        final String address;
        final String note;
        Sink(String key,int kind,String name,String address,String note){
            this.key=key;this.kind=kind;this.name=name;this.address=address;this.note=note;
        }
    }

    private final Handler main=new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String,Sink> sinks=new LinkedHashMap<>();
    private final Runnable refresh=new Runnable(){
        @Override public void run(){
            if(destroyed||!resumed)return;
            requestPeers();
            if(!discoveryActive||SystemClock.elapsedRealtime()-discoverAt>25000)discover();
            main.postDelayed(this,4000);
        }
    };

    private WifiP2pManager p2p;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private NsdManager nsd;
    private final List<NsdManager.DiscoveryListener> nsdListeners=new ArrayList<>();
    private final ArrayDeque<NsdServiceInfo> resolveQueue=new ArrayDeque<>();
    private final Map<String,Integer> nsdKinds=new HashMap<>();
    private boolean resolving;
    private LinearLayout list;
    private TextView state;
    private Button connectButton;
    private String selectedKey,p2pSignature="";
    private boolean destroyed,resumed,discoveryActive,searching;
    private long discoverAt;

    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private String tr(String es,String en){return Locale.getDefault().getLanguage().equals("en")?en:es;}

    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        // Wi-Fi Direct discovery pauses when the screen turns off.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
        receiver=new BroadcastReceiver(){
            @Override public void onReceive(Context context,Intent intent){
                String action=intent==null?null:intent.getAction();
                if(action==null)return;
                if(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)){
                    requestPeers();
                }else if(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION.equals(action)){
                    int value=intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE,
                        WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED);
                    discoveryActive=value==WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED;
                    if(resumed&&!destroyed&&!discoveryActive){
                        main.postDelayed(LexWirelessActivity.this::discover,800);
                    }
                }else if(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)){
                    int wifi=intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,-1);
                    if(wifi==WifiP2pManager.WIFI_P2P_STATE_DISABLED){
                        discoveryActive=false;searching=false;
                    }else if(wifi==WifiP2pManager.WIFI_P2P_STATE_ENABLED&&resumed)discover();
                }
            }
        };
        IntentFilter filter=new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver,filter);
        begin();
    }

    private View buildUi(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(20),dp(16),dp(20),dp(20));

        TextView tag=label("LEX · MODO 3",11,true,SECONDARY);
        tag.setLetterSpacing(.12f);
        root.addView(tag);
        space(root,8);
        root.addView(label(tr("Pantalla inalámbrica","Wireless display"),30,true,FG));
        space(root,8);
        TextView body=label(tr("Receptores Miracast por Wi-Fi Direct y equipos Chromecast/AirPlay por la red local.",
            "Miracast sinks over Wi-Fi Direct plus Chromecast/AirPlay devices on the local network."),14,false,SECONDARY);
        body.setLineSpacing(dp(3),1);
        root.addView(body);
        space(root,12);
        state=label(tr("Iniciando…","Starting…"),13,false,SECONDARY);
        state.setLineSpacing(dp(2),1);
        root.addView(state);

        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        list=new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0,dp(12),0,dp(12));
        scroll.addView(list,new ScrollView.LayoutParams(-1,-2));
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        connectButton=actionButton(tr("Conectar pantalla","Connect display"),this::connectSelected);
        connectButton.setEnabled(false);
        connectButton.setAlpha(.4f);
        root.addView(connectButton,new LinearLayout.LayoutParams(-1,dp(52)));
        space(root,8);
        root.addView(actionButton(tr("Buscar de nuevo","Search again"),()->begin()),
            new LinearLayout.LayoutParams(-1,dp(48)));
        space(root,14);
        TextView note=label(tr("Miracast espeja sin apps, pero la TV debe estar en modo «Duplicar pantalla». Chromecast y AirPlay se detectan aquí, aunque este build no tiene emisor para ellos (Cast exige Play Services; AirPlay, su propio emisor). La pantalla extendida tipo DeX necesita USB-C→HDMI o una app receptora: ningún televisor la acepta sin receptor.",
            "Miracast mirrors without apps, but the TV must be in “Screen mirroring” mode. Chromecast and AirPlay are detected here, yet this build has no sender for them (Cast needs Play Services; AirPlay needs its own sender). A DeX-like extended display needs USB-C→HDMI or a receiver app: no TV accepts one without a receiver."),12,false,SECONDARY);
        note.setLineSpacing(dp(2),1);
        root.addView(note);
        return root;
    }

    private TextView label(String text,float size,boolean bold,int color){
        TextView view=new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP,size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    private Button actionButton(String text,Runnable action){
        Button button=new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP,14);
        button.setTextColor(FG);
        button.setBackground(rounded(SURFACE,12));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setOnClickListener(view->action.run());
        return button;
    }

    private void space(LinearLayout parent,int height){
        parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));
    }

    private GradientDrawable rounded(int color,int radius){
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private void setState(String message){
        if(state!=null)state.setText(message);
    }

    private void begin(){
        if(destroyed)return;
        sinks.clear();
        selectedKey=null;
        p2pSignature="";
        searching=false;
        discoveryActive=false;
        startNsd();
        beginWifiDirect();
        renderList();
    }

    // ---- Local network discovery (mDNS) ----------------------------------

    private void startNsd(){
        if(nsd==null)nsd=(NsdManager)getSystemService(NSD_SERVICE);
        if(nsd==null)return;
        for(NsdManager.DiscoveryListener listener:nsdListeners)
            try{nsd.stopServiceDiscovery(listener);}catch(Exception ignored){}
        nsdListeners.clear();
        resolveQueue.clear();
        nsdKinds.clear();
        resolving=false;
        discoverType(NSD_CAST,KIND_CAST);
        discoverType(NSD_AIRPLAY,KIND_AIRPLAY);
    }

    private void discoverType(final String type,final int kind){
        NsdManager.DiscoveryListener listener=new NsdManager.DiscoveryListener(){
            @Override public void onDiscoveryStarted(String serviceType){}
            @Override public void onStartDiscoveryFailed(String serviceType,int errorCode){
                main.post(()->setState(tr("El descubrimiento LAN falló (código ","LAN discovery failed (code ")
                    +errorCode+tr("). Wi-Fi Direct sigue activo.",") . Wi-Fi Direct still runs.")));
            }
            @Override public void onStopDiscoveryFailed(String serviceType,int errorCode){}
            @Override public void onDiscoveryStopped(String serviceType){}
            @Override public void onServiceFound(NsdServiceInfo info){
                main.post(()->enqueueResolve(info,kind));
            }
            @Override public void onServiceLost(NsdServiceInfo info){
                main.post(()->{
                    Integer lostKind=nsdKinds.remove(info.getServiceName());
                    sinks.remove(nsdKey(lostKind==null?kind:lostKind,info.getServiceName()));
                    renderList();
                });
            }
        };
        nsdListeners.add(listener);
        try{
            nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,listener);
        }catch(RuntimeException ignored){}
    }

    private static String nsdKey(int kind,String serviceName){
        return "nsd:"+kind+":"+serviceName;
    }

    private void enqueueResolve(NsdServiceInfo info,int kind){
        if(destroyed||info==null)return;
        String key=nsdKey(kind,info.getServiceName());
        nsdKinds.put(info.getServiceName(),kind);
        if(!sinks.containsKey(key))sinks.put(key,new Sink(key,kind,prettyName(info.getServiceName()),null,protocolLabel(kind)));
        renderList();
        resolveQueue.add(info);
        pumpResolve();
    }

    private void pumpResolve(){
        if(destroyed||resolving||resolveQueue.isEmpty())return;
        final NsdServiceInfo info=resolveQueue.poll();
        resolving=true;
        try{
            nsd.resolveService(info,new NsdManager.ResolveListener(){
                @Override public void onResolveFailed(NsdServiceInfo serviceInfo,int errorCode){
                    main.post(()->{resolving=false;pumpResolve();});
                }
                @Override public void onServiceResolved(NsdServiceInfo serviceInfo){
                    main.post(()->{
                        resolving=false;
                        Integer known=nsdKinds.get(serviceInfo.getServiceName());
                        int kind=known==null?KIND_CAST:known;
                        String key=nsdKey(kind,serviceInfo.getServiceName());
                        String host=serviceInfo.getHost()==null?null:serviceInfo.getHost().getHostAddress();
                        // Drop IPv6 link-local noise (fe80::…%wlan0); the name is enough.
                        if(host!=null&&host.indexOf(':')>=0)host=null;
                        sinks.put(key,new Sink(key,kind,prettyName(serviceInfo.getServiceName()),host,protocolLabel(kind)));
                        renderList();
                        pumpResolve();
                    });
                }
            });
        }catch(RuntimeException e){
            resolving=false;
            main.postDelayed(this::pumpResolve,500);
        }
    }

    private static String prettyName(String raw){
        if(raw==null)return "";
        String value=raw.replaceAll("-[0-9a-fA-F]{32}$","");
        return value.replace('-',' ').replace('_',' ').trim();
    }

    private static String protocolLabel(int kind){
        switch(kind){
            case KIND_CAST:return "Chromecast · LAN";
            case KIND_AIRPLAY:return "AirPlay · LAN";
            default:return "Miracast · Wi-Fi Direct";
        }
    }

    // ---- Wi-Fi Direct (Miracast) ------------------------------------------

    private void beginWifiDirect(){
        p2p=(WifiP2pManager)getSystemService(WIFI_P2P_SERVICE);
        if(p2p==null)return;
        String permission=requiredPermission();
        if(!granted(permission)){
            if(!declared(permission)){
                setState(tr("El permiso ","The permission ")+permission
                    +tr(" no está declarado en AndroidManifest: requiere integración de la app.",
                        " is not declared in AndroidManifest: requires app integration."));
                return;
            }
            requestPermissions(new String[]{permission},PERMISSION);
            return;
        }
        if(Build.VERSION.SDK_INT<33){
            LocationManager location=(LocationManager)getSystemService(LOCATION_SERVICE);
            if(location!=null&&!location.isLocationEnabled())
                setState(tr("La ubicación está desactivada: Android la exige para Wi-Fi Direct en esta versión.",
                    "Location is off: Android requires it for Wi-Fi Direct on this version."));
        }
        ensureChannel();
        discover();
    }

    private void ensureChannel(){
        if(channel!=null||p2p==null)return;
        try{
            channel=p2p.initialize(this,getMainLooper(),()->{
                channel=null;
                setState(tr("Canal Wi-Fi Direct perdido. Pulsa Buscar de nuevo.",
                    "Wi-Fi Direct channel lost. Tap Search again."));
            });
        }catch(SecurityException e){
            channel=null;
        }catch(RuntimeException e){
            channel=null;
            setState(tr("No se pudo abrir Wi-Fi Direct: ","Could not start Wi-Fi Direct: ")+describe(e));
        }
    }

    private void discover(){
        if(destroyed||p2p==null||channel==null)return;
        if(!searching){
            searching=true;
            updateScanState();
        }
        try{
            p2p.discoverPeers(channel,new WifiP2pManager.ActionListener(){
                @Override public void onSuccess(){
                    discoverAt=SystemClock.elapsedRealtime();
                    discoveryActive=true;
                    updateScanState();
                    requestPeers();
                }
                @Override public void onFailure(int reason){
                    if(reason==WifiP2pManager.BUSY){
                        discoveryActive=true;
                        discoverAt=SystemClock.elapsedRealtime();
                        return;
                    }
                    searching=false;
                    discoveryActive=false;
                    setState(tr("No se pudo buscar: ","Discovery failed: ")+reasonText(reason));
                    if(reason!=WifiP2pManager.P2P_UNSUPPORTED)
                        main.postDelayed(()->{if(!destroyed)discover();},8000);
                }
            });
        }catch(SecurityException e){
            searching=false;
            setState(tr("Permiso denegado para Wi-Fi Direct.","Permission denied for Wi-Fi Direct."));
        }catch(RuntimeException e){
            searching=false;
            setState(tr("Wi-Fi Direct falló: ","Wi-Fi Direct failed: ")+describe(e));
        }
    }

    private void requestPeers(){
        if(destroyed||p2p==null||channel==null)return;
        try{p2p.requestPeers(channel,this::renderPeers);}
        catch(RuntimeException ignored){}
    }

    private void renderPeers(WifiP2pDeviceList peers){
        if(destroyed||list==null)return;
        List<WifiP2pDevice> found=new ArrayList<>();
        if(peers!=null)for(WifiP2pDevice device:peers.getDeviceList()){
            WifiP2pWfdInfo wfd=device.getWfdInfo();
            if(wfd==null||!wfd.isEnabled())continue;
            int type=wfd.getDeviceType();
            if(type==WifiP2pWfdInfo.DEVICE_TYPE_PRIMARY_SINK
                ||type==WifiP2pWfdInfo.DEVICE_TYPE_SECONDARY_SINK
                ||type==WifiP2pWfdInfo.DEVICE_TYPE_SOURCE_OR_PRIMARY_SINK)found.add(device);
        }
        StringBuilder signature=new StringBuilder();
        for(WifiP2pDevice device:found)signature.append(device.deviceAddress).append(':')
            .append(device.deviceName).append(':').append(device.status).append(';');
        if(signature.toString().equals(p2pSignature))return;
        p2pSignature=signature.toString();
        sinks.keySet().removeIf(key->key.startsWith("wfd:"));
        for(WifiP2pDevice device:found){
            String name=TextUtils.isEmpty(device.deviceName)?tr("Receptor sin nombre","Unnamed sink"):device.deviceName;
            sinks.put("wfd:"+device.deviceAddress,new Sink("wfd:"+device.deviceAddress,KIND_MIRACAST,
                name,device.deviceAddress,"Miracast · "+statusText(device.status)));
        }
        renderList();
    }

    // ---- UI ---------------------------------------------------------------

    private void renderList(){
        if(destroyed||list==null)return;
        list.removeAllViews();
        if(sinks.isEmpty()){
            TextView empty=label(discoveryActive||searching
                ?tr("Buscando por Wi-Fi Direct y por la red local…",
                    "Scanning Wi-Fi Direct and the local network…")
                :tr("No se han encontrado pantallas anunciadas.",
                    "No advertised displays found."),14,false,SECONDARY);
            empty.setPadding(dp(4),dp(8),dp(4),dp(8));
            list.addView(empty,new LinearLayout.LayoutParams(-1,-2));
        }else{
            for(Sink sink:sinks.values())addRow(sink);
        }
        if(selectedKey!=null&&!sinks.containsKey(selectedKey))selectedKey=null;
        updateScanState();
        updateConnect();
    }

    private void addRow(final Sink sink){
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16),dp(12),dp(16),dp(12));
        row.setBackground(rounded(sink.key.equals(selectedKey)?SELECTED:SURFACE,12));
        row.setTag(sink.key);
        row.setClickable(true);
        row.setFocusable(true);
        TextView name=label(TextUtils.isEmpty(sink.name)?tr("Pantalla sin nombre","Unnamed display"):sink.name,16,true,FG);
        StringBuilder detail=new StringBuilder(sink.note);
        if(!TextUtils.isEmpty(sink.address))detail.append(" · ").append(sink.address);
        TextView meta=label(detail.toString(),13,false,SECONDARY);
        row.addView(name);
        space(row,4);
        row.addView(meta);
        row.setContentDescription(name.getText()+" "+detail);
        row.setOnClickListener(view->{
            selectedKey=sink.key;
            for(int i=0;i<list.getChildCount();i++){
                View child=list.getChildAt(i);
                if(child.getTag() instanceof String)
                    child.setBackground(rounded(selectedKey.equals(child.getTag())?SELECTED:SURFACE,12));
            }
            updateConnect();
        });
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.bottomMargin=dp(8);
        list.addView(row,params);
    }

    private void updateConnect(){
        if(connectButton==null)return;
        Sink sink=selectedKey==null?null:sinks.get(selectedKey);
        boolean miracast=sink!=null&&sink.kind==KIND_MIRACAST;
        connectButton.setEnabled(miracast);
        connectButton.setAlpha(miracast?1f:.4f);
        connectButton.setText(sink==null||miracast
            ?tr("Conectar pantalla","Connect display")
            :tr("Sin emisor en este build","No sender in this build"));
    }

    private void updateScanState(){
        if(state==null)return;
        int wifiDirect=0,lan=0;
        for(Sink sink:sinks.values()){
            if(sink.kind==KIND_MIRACAST)wifiDirect++;
            else lan++;
        }
        if(sinks.isEmpty()&&!searching)return;
        setState(tr("Wi-Fi Direct ","Wi-Fi Direct ")+wifiDirect+tr(" · LAN "," · LAN ")+lan
            +(searching?tr(" · buscando…"," · scanning…"):tr(" · búsqueda renovándose"," · scan renewing")));
    }

    private String statusText(int status){
        switch(status){
            case WifiP2pDevice.AVAILABLE:return tr("disponible","available");
            case WifiP2pDevice.INVITED:return tr("invitado","invited");
            case WifiP2pDevice.CONNECTED:return tr("conectado","connected");
            case WifiP2pDevice.FAILED:return tr("fallido","failed");
            case WifiP2pDevice.UNAVAILABLE:return tr("no disponible","unavailable");
            default:return "status "+status;
        }
    }

    private void connectSelected(){
        Sink sink=selectedKey==null?null:sinks.get(selectedKey);
        if(sink==null){
            setState(tr("Selecciona una pantalla de la lista.","Select a display from the list."));
            return;
        }
        if(sink.kind!=KIND_MIRACAST){
            setState(tr("«","“")+sink.name+tr("» anuncia ","” advertises ")+sink.note
                +tr(". Este build no puede enviar pantalla por ese protocolo: no hay emisor Cast/AirPlay todavía. Solo Miracast (Wi-Fi Direct) transmite directamente desde la app.",
                    ". This build cannot send a screen over that protocol: there is no Cast/AirPlay sender yet. Only Miracast (Wi-Fi Direct) streams directly from the app."));
            return;
        }
        if(TextUtils.isEmpty(sink.address)||!sink.address.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")){
            setState(tr("Dirección de receptor inválida.","Invalid sink address."));
            return;
        }
        DisplayManager displays=(DisplayManager)getSystemService(DISPLAY_SERVICE);
        if(displays==null){
            setState(tr("DisplayManager no está disponible.","DisplayManager is not available."));
            return;
        }
        try{
            Method connect=DisplayManager.class.getMethod("connectWifiDisplay",String.class);
            connect.invoke(displays,sink.address);
            setState(tr("Solicitud aceptada para ","Request accepted for ")+sink.name
                +tr(". Sin confirmar: comprueba la pantalla receptora.",". Unconfirmed: check the sink."));
            verifyLater(displays);
        }catch(SecurityException e){
            setState(miracastHint(sink.name));
            openCastSettings();
        }catch(NoSuchMethodException e){
            setState(tr("connectWifiDisplay no está disponible; abriendo el selector del sistema.",
                "connectWifiDisplay is not available; opening the system picker."));
            openCastSettings();
        }catch(InvocationTargetException e){
            Throwable cause=e.getCause()==null?e:e.getCause();
            setState(cause instanceof SecurityException
                ?miracastHint(sink.name)
                :tr("El sistema rechazó la conexión: ","The system rejected the connection: ")+describe(cause)
                    +tr(". Abriendo el selector del sistema."," . Opening the system picker."));
            openCastSettings();
        }catch(ReflectiveOperationException|RuntimeException e){
            setState(tr("No se pudo invocar connectWifiDisplay: ","Could not invoke connectWifiDisplay: ")+describe(e));
        }
    }

    private void openCastSettings(){
        try{
            // Public on AOSP but not in every SDK stub: use the documented value.
            startActivity(new Intent("android.settings.CAST_SETTINGS"));
        }catch(RuntimeException e){
            try{startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));}
            catch(RuntimeException ignored){}
        }
    }

    /** The platform refuses direct calls without CONFIGURE_WIFI_DISPLAY, and its
     *  own picker finds nothing until the user turns the global toggle on. */
    private String miracastHint(String name){
        boolean enabled;
        try{enabled=Settings.Global.getInt(getContentResolver(),"wifi_display_on",0)!=0;}
        catch(RuntimeException e){enabled=false;}
        if(enabled)
            return tr("La app no tiene firma de plataforma (CONFIGURE_WIFI_DISPLAY). Abriendo el selector del sistema: elige «",
                "The app is not platform-signed (CONFIGURE_WIFI_DISPLAY). Opening the system picker: choose “")+name
                +tr("» allí.","” there.");
        return tr("Abre el menú ⋮ de la pantalla que aparece y activa «Activar pantalla inalámbrica»; luego elige «",
            "Open the ⋮ menu on the screen that appears and enable “Enable wireless display”; then choose “")+name
            +tr("».","”.");
    }

    private void verifyLater(DisplayManager displays){
        main.postDelayed(()->{
            if(destroyed)return;
            try{
                for(Display display:displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)){
                    if(display.getDisplayId()!=Display.DEFAULT_DISPLAY&&display.getState()==Display.STATE_ON){
                        setState(tr("Pantalla detectada: ","Display detected: ")+display.getName()
                            +tr(" — verificación visual pendiente."," — visual check pending."));
                        return;
                    }
                }
            }catch(Exception ignored){}
            setState(tr("Solicitud enviada; no se detecta ninguna pantalla. Sin confirmar.",
                "Request sent; no display detected. Unconfirmed."));
        },3500);
    }

    private static String requiredPermission(){
        return Build.VERSION.SDK_INT>=33
            ?Manifest.permission.NEARBY_WIFI_DEVICES
            :Manifest.permission.ACCESS_FINE_LOCATION;
    }

    private boolean granted(String permission){
        return checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED;
    }

    private boolean declared(String permission){
        try{
            PackageInfo info=getPackageManager().getPackageInfo(getPackageName(),PackageManager.GET_PERMISSIONS);
            String[] requested=info.requestedPermissions;
            if(requested!=null)for(String value:requested)if(permission.equals(value))return true;
        }catch(Exception ignored){}
        return false;
    }

    private String reasonText(int reason){
        switch(reason){
            case WifiP2pManager.P2P_UNSUPPORTED:return tr("Wi-Fi Direct no soportado","Wi-Fi Direct unsupported");
            case WifiP2pManager.BUSY:return tr("ocupado","busy");
            case WifiP2pManager.ERROR:return tr("error interno","internal error");
            default:return "reason "+reason;
        }
    }

    private static String describe(Throwable error){
        Throwable cause=error;
        while(cause.getCause()!=null&&cause.getCause()!=cause)cause=cause.getCause();
        String message=cause.getMessage();
        return message==null||message.trim().isEmpty()?cause.getClass().getSimpleName():message.trim();
    }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request!=PERMISSION)return;
        if(granted(requiredPermission()))beginWifiDirect();
        else setState(tr("Permiso denegado: Wi-Fi Direct no puede buscar receptores. La LAN sigue visible.",
            "Permission denied: Wi-Fi Direct cannot scan. LAN results remain visible."));
    }

    @Override protected void onResume(){
        super.onResume();
        resumed=true;
        if(channel!=null){
            main.removeCallbacks(refresh);
            main.postDelayed(refresh,1500);
            if(!discoveryActive)main.postDelayed(()->{if(resumed&&!destroyed)discover();},600);
        }
    }

    @Override protected void onPause(){
        resumed=false;
        main.removeCallbacks(refresh);
        super.onPause();
    }

    @Override protected void onDestroy(){
        destroyed=true;
        main.removeCallbacksAndMessages(null);
        if(receiver!=null)try{unregisterReceiver(receiver);}catch(Exception ignored){}
        receiver=null;
        if(nsd!=null)for(NsdManager.DiscoveryListener listener:nsdListeners)
            try{nsd.stopServiceDiscovery(listener);}catch(Exception ignored){}
        nsdListeners.clear();
        nsd=null;
        if(p2p!=null&&channel!=null)try{p2p.stopPeerDiscovery(channel,null);}
        catch(Exception ignored){}
        if(channel!=null)try{channel.close();}catch(Exception ignored){}
        channel=null;
        p2p=null;
        super.onDestroy();
    }
}
