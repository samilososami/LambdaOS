package org.lambdaproject.lambda;

import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.Bitmap;
import java.io.*;
import org.json.*;

/** Separate test-only instrumentation. Fixtures never reach Core.submit. */
public final class WpsUiPreview extends Instrumentation {
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    interface Scene {void show(MainActivity a)throws Exception;}
    void capture(MainActivity a,String name,Scene scene)throws Exception{
        final Exception[] error={null};
        runOnMainSync(()->{try{scene.show(a);}catch(Exception e){error[0]=e;}});
        if(error[0]!=null)throw error[0];waitForIdleSync();SystemClock.sleep(700);
        Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap==null)throw new IOException("screenshot unavailable");
        File file=new File(getTargetContext().getExternalFilesDir(null),"wps-qa-"+name+".png");
        try(FileOutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
        Bundle result=new Bundle();result.putString("screenshot",file.toString());sendStatus(0,result);
    }
    @Override public void onStart(){
        try{
            Bundle state=getTargetContext().getContentResolver().call(CoreProvider.URI,"status","{}",null);
            if(new JSONObject(state.getString("json")).getInt("active")!=0)throw new IllegalStateException("Core busy");
            Intent intent=new Intent().setClassName(getTargetContext(),"org.lambdaproject.lambda.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            MainActivity a=(MainActivity)startActivitySync(intent);
            JSONObject target=new JSONObject().put("ssid","Lambda QA · simulación").put("bssid","02:00:00:00:00:01");
            capture(a,"methods",v->{v.wps.target=target;v.wps.method="pixie";v.wps.go("method");});
            capture(a,"brute",v->{v.wps.target=target;v.wps.method="bruteforce";v.wps.go("method");});
            capture(a,"brute-controls",v->{android.view.View parent=(android.view.View)v.content.getParent();if(parent instanceof android.widget.ScrollView)((android.widget.ScrollView)parent).fullScroll(android.view.View.FOCUS_DOWN);});
            JSONObject p=new JSONObject().put("phase","wps_wait_button").put("attempts",1).put("max_attempts",1).put("seconds",15).put("events",new JSONArray().put(new JSONObject().put("code","wps_wait_button").put("seconds",2)));
            capture(a,"pbc",v->{v.wps.id=null;v.wps.receipt=new JSONObject().put("args",new JSONObject().put("mode","pbc"));v.wps.go("working");v.wps.updateProgress(p,false,false);});
            JSONObject exchange=new JSONObject().put("phase","wps_exchange").put("mode","bruteforce").put("attempts",4).put("max_attempts",10).put("retries",1).put("candidates",3).put("message",5).put("direction","rx").put("message_mask",31).put("seconds",48).put("retransmissions",2).put("events",new JSONArray()
                .put(new JSONObject().put("code","wps_retry_wait").put("seconds",26))
                .put(new JSONObject().put("code","wps_associated").put("seconds",30))
                .put(new JSONObject().put("code","wps_exchange").put("message",3).put("direction","rx").put("seconds",34))
                .put(new JSONObject().put("code","wps_exchange").put("message",4).put("direction","tx").put("seconds",35))
                .put(new JSONObject().put("code","wps_exchange").put("message",5).put("direction","rx").put("seconds",48)));
            capture(a,"exchange",v->{v.wps.receipt=new JSONObject().put("args",new JSONObject().put("mode","bruteforce"));v.wps.go("working");v.wps.updateProgress(exchange,false,false);});
            capture(a,"m2d",v->{v.wps.receipt=new JSONObject().put("state","partial").put("result",new JSONObject().put("reason","negotiation_deferred").put("restored",true).put("telemetry",p));v.wps.go("result");});
            runOnMainSync(()->{a.wps.id=null;a.wps.receipt=null;a.wps.go("intro");});
            Bundle result=new Bundle();result.putString("result","PASS six visual fixtures; no WPS submission");finish(Activity.RESULT_OK,result);
        }catch(Exception e){Bundle b=new Bundle();b.putString("error",e.toString());finish(Activity.RESULT_CANCELED,b);}
    }
}
