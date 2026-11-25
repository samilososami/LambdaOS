package org.lambdaproject.lambda;

import android.app.admin.DeviceAdminReceiver;

/** Device-owner admin used only to allow silent lock-task kiosk on UCM/FDT.
 *  Development provisioning: adb shell dpm set-device-owner
 *  org.lambdaproject.lambda/.LambdaDeviceAdmin (no accounts, single user). */
public final class LambdaDeviceAdmin extends DeviceAdminReceiver {}
