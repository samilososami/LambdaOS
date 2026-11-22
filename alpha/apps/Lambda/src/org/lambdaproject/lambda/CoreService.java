package org.lambdaproject.lambda;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/** Observable resident coordinator; idle means no polling, scans or held wake lock. */
public final class CoreService extends Service {
    private static CoreService instance;
    static void ensure(Context c) {
        c.startForegroundService(new Intent(c,CoreService.class));
    }
    static void update(String summary) {
        CoreService s=instance;
        if(s!=null) s.getSystemService(NotificationManager.class).notify(41,s.notification(summary));
    }
    private Notification notification(String summary) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent cancel=PendingIntent.getService(this,1,new Intent(this,CoreService.class).setAction("cancel"),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"lambda-core")
            .setSmallIcon(R.drawable.ic_core).setContentTitle("Lambda Core")
            .setContentText(summary).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(new Notification.Action.Builder(null,"Detener tareas",cancel).build()).build();
    }
    @Override public void onCreate() {
        super.onCreate(); instance=this;
        NotificationChannel channel=new NotificationChannel("lambda-core","Lambda Core",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Estado del servicio de auditoría local");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        startForeground(41,notification("Listo · Wi-Fi, Bluetooth y USB"));
        CoreEngine.get(this);
    }
    @Override public int onStartCommand(Intent i,int flags,int id) {
        if(i!=null && "cancel".equals(i.getAction())) CoreEngine.get(this).cancelAll();
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() { instance=null; super.onDestroy(); }
}
