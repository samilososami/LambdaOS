package org.lambdaproject.lambda;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import org.json.JSONObject;

/** Observable resident coordinator; idle means no polling, scans or held wake lock. */
public final class CoreService extends Service {
    static final String CHANNEL_CORE="lambda-core",CHANNEL_CONNECT="lambda-connect",CHANNEL_DISPLAY="lambda-display";
    private static CoreService instance;
    private final Handler probe=new Handler(Looper.getMainLooper());
    private final java.util.Set<Integer> displayNotified=new java.util.HashSet<>();
    private DisplayManager displays;
    private final Runnable probeTask=new Runnable(){public void run(){
        try{if(CoreEngine.get(CoreService.this).probeDesktop())refresh();}catch(Exception ignored){}
        probe.postDelayed(this,30000);
    }};
    private final DisplayManager.DisplayListener displayListener=new DisplayManager.DisplayListener(){
        @Override public void onDisplayAdded(int displayId){suggestDisplay(displayId);}
        @Override public void onDisplayRemoved(int displayId){
            synchronized(displayNotified){displayNotified.remove(displayId);}
            getSystemService(NotificationManager.class).cancel(displayNotificationId(displayId));
        }
        @Override public void onDisplayChanged(int displayId){suggestDisplay(displayId);}
    };
    static void ensure(Context c) {
        c.startForegroundService(new Intent(c,CoreService.class));
    }
    static void update() {
        CoreService s=instance;
        if(s!=null)s.refresh();
    }
    private void refresh() {
        getSystemService(NotificationManager.class).notify(41,notification());
    }
    private Notification notification() {
        JSONObject active=null;
        try{active=CoreEngine.get(this).activeJob();}catch(Exception ignored){}
        Intent openIntent=new Intent(this,MainActivity.class);
        Notification.Builder builder=new Notification.Builder(this,"lambda-core")
            .setSmallIcon(R.drawable.ic_core).setContentTitle("Lambda Core")
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_SECRET);
        String text;
        if(active==null){
            text="Listo · Wi-Fi, Bluetooth y USB";
        }else{
            String id=active.optString("id"),tool=active.optString("tool"),label=active.optString("label"),detail=active.optString("detail");
            int count=Math.max(1,active.optInt("count",1));
            text=label+(detail.isEmpty()?"":" · "+detail)+(count>1?" · "+count+" tareas":"");
            if("system.desktop".equals(tool))openIntent.setClass(this,LexActivity.class);
            else openIntent.putExtra("open","radio.ap".equals(tool)?"ap":"sessions").putExtra("job",id);
            int code=id.hashCode()&0x7fffffff;
            PendingIntent stop=PendingIntent.getService(this,100+code,
                new Intent(this,CoreService.class).setAction("cancel").putExtra("job",id),
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(new Notification.Action.Builder(null,"Detener "+label,stop).build());
            PendingIntent open=PendingIntent.getActivity(this,200+code,openIntent,
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(new Notification.Action.Builder(null,"Abrir en Operator",open).build());
        }
        PendingIntent content=PendingIntent.getActivity(this,0,openIntent,
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return builder.setContentText(text).setContentIntent(content).build();
    }
    @Override public void onCreate() {
        super.onCreate(); instance=this;
        NotificationManager nm=getSystemService(NotificationManager.class);
        NotificationChannel core=new NotificationChannel(CHANNEL_CORE,"Lambda Core",NotificationManager.IMPORTANCE_LOW);
        core.setDescription("Estado del servicio de auditoría local");
        NotificationChannel connect=new NotificationChannel(CHANNEL_CONNECT,"Conexiones LEX",NotificationManager.IMPORTANCE_HIGH);
        connect.setDescription("Solicitudes de conexión desde la web LEX");
        NotificationChannel displayChannel=new NotificationChannel(CHANNEL_DISPLAY,"Pantallas externas",NotificationManager.IMPORTANCE_DEFAULT);
        displayChannel.setDescription("Sugerencia de abrir LEX al detectar una pantalla externa");
        nm.createNotificationChannel(core);
        nm.createNotificationChannel(connect);
        nm.createNotificationChannel(displayChannel);
        startForeground(41,notification());
        CoreEngine.get(this);
        displays=(DisplayManager)getSystemService(DISPLAY_SERVICE);
        if(displays!=null){
            displays.registerDisplayListener(displayListener,probe);
            // A monitor attached before the service started must still be offered.
            for(Display display:displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION))
                suggestDisplay(display.getDisplayId());
        }
        probe.postDelayed(probeTask,30000);
    }
    @Override public int onStartCommand(Intent i,int flags,int id) {
        if(i!=null&&i.getAction()!=null){
            switch(i.getAction()){
                case "cancel":{
                    String job=i.getStringExtra("job");
                    if(job!=null&&!job.isEmpty())CoreEngine.get(this).cancelJob(job);
                    else CoreEngine.get(this).cancelAll();
                    refresh();
                    break;
                }
                case "dismiss_display":{
                    int displayId=i.getIntExtra("display",-1);
                    if(displayId>=0){
                        getSystemService(NotificationManager.class).cancel(displayNotificationId(displayId));
                        synchronized(displayNotified){displayNotified.remove(displayId);}
                    }
                    break;
                }
                case "connect_allow":case "connect_trust":case "connect_deny":{
                    String host=i.getStringExtra("host");
                    if(host!=null&&!host.isEmpty()){
                        CoreEngine.get(this).decideConnect(host,
                            i.getAction().equals("connect_allow")?"allowed"
                            :i.getAction().equals("connect_trust")?"trusted":"denied");
                        cancelConnectPrompt(this,host);
                    }
                    refresh();
                    break;
                }
            }
        }
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() {
        probe.removeCallbacksAndMessages(null);
        if(displays!=null)try{displays.unregisterDisplayListener(displayListener);}catch(Exception ignored){}
        instance=null; super.onDestroy();
    }
    /** External display suggestion: shown once per display while it is attached. */
    private void suggestDisplay(int displayId){
        Display display=displays==null?null:displays.getDisplay(displayId);
        if(display==null||display.getDisplayId()==Display.DEFAULT_DISPLAY)return;
        if((display.getFlags()&Display.FLAG_PRESENTATION)==0)return;
        if(display.getState()!=Display.STATE_ON)return;
        synchronized(displayNotified){if(!displayNotified.add(displayId))return;}
        NotificationManager nm=getSystemService(NotificationManager.class);
        String name=display.getName();
        Intent open=new Intent(this,LexMonitorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPi=PendingIntent.getActivity(this,displayNotificationId(displayId),open,
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent dismiss=PendingIntent.getService(this,5000+displayId,
            new Intent(this,CoreService.class).setAction("dismiss_display").putExtra("display",displayId),
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        String question="Se ha detectado una pantalla externa ("+name+"). ¿Abrir LEX en ella?";
        nm.notify(displayNotificationId(displayId),new Notification.Builder(this,CHANNEL_DISPLAY)
            .setSmallIcon(R.drawable.ic_core)
            .setContentTitle("Pantalla externa detectada")
            .setContentText("¿Abrir LEX en "+name+"?")
            .setStyle(new Notification.BigTextStyle().bigText(question))
            .setAutoCancel(true).setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .addAction(new Notification.Action.Builder(null,"Abrir LEX",openPi).build())
            .addAction(new Notification.Action.Builder(null,"Ahora no",dismiss).build())
            .build());
    }
    private static int displayNotificationId(int displayId){return 200+(displayId&0xff);}

    /** Connection consent prompt. Static: CoreEngine (same :core process) posts
     *  it even if it runs before the service instance exists. */
    static void showConnectPrompt(Context c,String host){
        NotificationManager nm=c.getSystemService(NotificationManager.class);
        NotificationChannel channel=new NotificationChannel(CHANNEL_CONNECT,"Conexiones LEX",NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Solicitudes de conexión desde la web LEX");
        nm.createNotificationChannel(channel);
        String question=host+" quiere conectarse a este teléfono. ¿Permitir?";
        PendingIntent allow=PendingIntent.getService(c,requestCode(host,1),
            new Intent(c,CoreService.class).setAction("connect_allow").putExtra("host",host),
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent trust=PendingIntent.getService(c,requestCode(host,2),
            new Intent(c,CoreService.class).setAction("connect_trust").putExtra("host",host),
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent deny=PendingIntent.getService(c,requestCode(host,3),
            new Intent(c,CoreService.class).setAction("connect_deny").putExtra("host",host),
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(connectNotificationId(host),new Notification.Builder(c,CHANNEL_CONNECT)
            .setSmallIcon(R.drawable.ic_core)
            .setContentTitle("Conexión entrante")
            .setContentText(question)
            .setStyle(new Notification.BigTextStyle().bigText(question))
            .setAutoCancel(false).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .addAction(new Notification.Action.Builder(null,"Permitir",allow).build())
            .addAction(new Notification.Action.Builder(null,"Permitir siempre",trust).build())
            .addAction(new Notification.Action.Builder(null,"Denegar",deny).build())
            .build());
    }
    static void cancelConnectPrompt(Context c,String host){
        c.getSystemService(NotificationManager.class).cancel(connectNotificationId(host));
    }
    private static int connectNotificationId(String host){return 3000+(host.hashCode()&0x3ff);}
    private static int requestCode(String host,int action){return (host.hashCode()&0x0fffffff)*4+action;}
}
