/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.setupwizard;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.provider.Settings;
import java.io.IOException;

/** Applies the approved Arista pair once. Changing night mode never calls this. */
final class LambdaInitialWallpaper {
    private static final String MARKER = "lambda_initial_wallpaper";

    static void apply(Context context, SharedPreferences prefs, String theme) throws IOException {
        var resolver = context.getContentResolver();
        boolean applied = prefs.getBoolean("wallpaper_applied", false)
                || Settings.Secure.getString(resolver, MARKER) != null;
        boolean provisioned = Settings.Secure.getInt(resolver,
                Settings.Secure.USER_SETUP_COMPLETE, 0) != 0;
        if (!LambdaWallpaperPolicy.shouldApply(prefs.getBoolean("restored", false),
                applied, provisioned)) return;

        // Automatic appearance is sampled NOW, not followed after provisioning.
        boolean resolvedLight = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO;
        boolean light = LambdaWallpaperPolicy.isLight(theme, resolvedLight);
        int image = light ? R.drawable.lambda_wallpaper_light : R.drawable.lambda_wallpaper_dark;
        try (var stream = context.getResources().openRawResource(image)) {
            int wallpaperId = WallpaperManager.getInstance(context).setStream(stream, null, true,
                    WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
            if (wallpaperId <= 0) throw new IOException("Initial wallpaper was not applied");
        }
        // Persist only after WallpaperManager accepted both destinations.
        if (!Settings.Secure.putString(resolver, MARKER,
                light ? "arista-light-v1" : "arista-dark-v1")) {
            throw new IOException("Initial wallpaper marker was not saved");
        }
        prefs.edit().putBoolean("wallpaper_applied", true).commit();
    }

    private LambdaInitialWallpaper() {}
}
