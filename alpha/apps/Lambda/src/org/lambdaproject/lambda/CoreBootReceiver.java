package org.lambdaproject.lambda;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public final class CoreBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (c.getSharedPreferences("core",0).getBoolean("enabled",false)) CoreService.ensure(c);
    }
}
