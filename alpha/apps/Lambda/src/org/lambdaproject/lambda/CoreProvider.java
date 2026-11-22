package org.lambdaproject.lambda;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import org.json.JSONObject;

/** Versioned IPC shared by Operator and the local, authenticated ADB/root CLI. */
public final class CoreProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://org.lambdaproject.lambda.core");
    @Override public boolean onCreate() { return true; }
    private int authorize() {
        int uid = Binder.getCallingUid();
        // Same full UID, not appId: profiles cannot read one another's audit data.
        boolean localOwner = Process.myUid() / 100000 == 0 && (uid == 0 || uid == 2000);
        if (uid != Process.myUid() && !localOwner) throw new SecurityException("Untrusted Lambda Core caller");
        return uid;
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        int caller = authorize();
        long identity = Binder.clearCallingIdentity();
        Bundle reply = new Bundle();
        try {
            if (arg != null && arg.length() > (caller==0&&method.equals("radio_worker")?300000:8192)) throw new IllegalArgumentException("request_too_large");
            JSONObject request = new JSONObject(arg == null ? "{}" : arg);
            reply.putString("json", CoreEngine.get(getContext()).rpc(method, request, caller).toString());
        } catch (Exception e) {
            reply.putString("json", CoreEngine.error(e).toString());
        } finally { Binder.restoreCallingIdentity(identity); }
        return reply;
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String sort) { authorize(); throw new UnsupportedOperationException("Use versioned call RPC"); }
    @Override public String getType(Uri u) { authorize(); return "application/json"; }
    @Override public Uri insert(Uri u,ContentValues v) { authorize(); throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u,String s,String[] a) { authorize(); throw new UnsupportedOperationException(); }
    @Override public int update(Uri u,ContentValues v,String s,String[] a) { authorize(); throw new UnsupportedOperationException(); }
}
