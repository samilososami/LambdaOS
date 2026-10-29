package org.lambdaproject.lambda;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import org.json.JSONArray;
import org.json.JSONObject;

/** Public, read-only Android APIs. No shell, root escalation, scanning or network requests. */
final class HardwareSnapshot {
    static JSONObject collect(Context context) throws Exception {
        JSONObject result = new JSONObject();
        result.put("schema", 1);
        result.put("capturedAtEpochMs", System.currentTimeMillis());
        result.put("appVersion", "0.1.0-alpha.1");
        result.put("model", Build.MODEL);
        result.put("device", Build.DEVICE);
        result.put("android", Build.VERSION.RELEASE);
        result.put("sdk", Build.VERSION.SDK_INT);
        result.put("build", Build.DISPLAY);
        result.put("kernel", System.getProperty("os.version", "unknown"));
        result.put("abis", new JSONArray(Build.SUPPORTED_ABIS));

        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        context.getSystemService(ActivityManager.class).getMemoryInfo(memory);
        result.put("memoryTotalBytes", memory.totalMem);
        result.put("memoryAvailableBytes", memory.availMem);
        StatFs storage = new StatFs(Environment.getDataDirectory().getAbsolutePath());
        result.put("storageTotalBytes", storage.getTotalBytes());
        result.put("storageAvailableBytes", storage.getAvailableBytes());

        PackageManager pm = context.getPackageManager();
        JSONObject features = new JSONObject();
        features.put("wifi", pm.hasSystemFeature(PackageManager.FEATURE_WIFI));
        features.put("bluetooth", pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH));
        features.put("bluetoothLE", pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE));
        features.put("usbHost", pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST));
        result.put("features", features);

        WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
        result.put("wifiState", wifi == null ? -1 : wifi.getWifiState());
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        JSONArray networks = new JSONArray();
        if (cm != null) {
            for (Network network : cm.getAllNetworks()) {
                LinkProperties links = cm.getLinkProperties(network);
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                JSONObject item = new JSONObject();
                if (links != null) item.put("interface", links.getInterfaceName());
                if (caps != null) {
                    item.put("wifi", caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
                    item.put("cellular", caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
                    item.put("ethernet", caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
                    item.put("validated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                }
                networks.put(item);
            }
        }
        result.put("networks", networks);

        JSONArray devices = new JSONArray();
        UsbManager usb = context.getSystemService(UsbManager.class);
        if (usb != null) {
            for (UsbDevice device : usb.getDeviceList().values()) {
                JSONObject item = new JSONObject();
                item.put("vendorId", device.getVendorId());
                item.put("productId", device.getProductId());
                item.put("interfaceCount", device.getInterfaceCount());
                item.put("name", device.getProductName());
                devices.put(item);
            }
        }
        result.put("usbDevices", devices);
        JSONObject packages = new JSONObject();
        for (String name : new String[]{"com.offsec.nethunter", "com.offsec.nhterm"}) {
            try {
                packages.put(name, pm.getPackageInfo(name, 0).versionName);
            } catch (PackageManager.NameNotFoundException absent) {
                packages.put(name, JSONObject.NULL);
            }
        }
        result.put("packages", packages);
        result.put("capabilityNote", "Hardware presence does not establish monitor, injection, CAN or HID support.");
        return result;
    }
}
