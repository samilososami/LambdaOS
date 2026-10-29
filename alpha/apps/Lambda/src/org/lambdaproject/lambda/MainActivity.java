package org.lambdaproject.lambda;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private LinearLayout root;
    private LinearLayout content;
    private String page = "home";
    private int category = 0;
    private int background, surface, foreground, secondary, divider;
    private boolean dark, english;
    private JSONObject snapshot;
    private boolean collecting;
    private final int[] accents = {0xff4F9BA9, 0xff578FCE, 0xffA096BE, 0xffB58D6A, 0xffA2AAAF, 0xffA2AAAF};

    private String tr(String es, String en) { return english ? en : es; }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String[] categories() { return new String[]{"Wi-Fi", "Bluetooth", "USB", "CAN", tr("Red", "Network"), "Radio"}; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("appearance", MODE_PRIVATE);
        // Setup is the source of first-run defaults, never overwrite later app choices.
        if (!prefs.getBoolean("setup_defaults_imported", false)) {
            String layout = Settings.Secure.getString(getContentResolver(), "lambda_layout");
            String theme = Settings.Secure.getString(getContentResolver(), "lambda_theme");
            if (layout != null || theme != null) {
                SharedPreferences.Editor defaults = prefs.edit();
                if (!prefs.contains("icons")) defaults.putBoolean("icons", !"text".equals(layout));
                if (!prefs.contains("theme_mode") && theme != null) defaults.putString("theme_mode", theme);
                if (!prefs.contains("haptics")) defaults.putBoolean("haptics", Settings.System.getInt(getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0);
                defaults.putBoolean("setup_defaults_imported", true).apply();
            }
        }
        if (state != null) {
            page = state.getString("page", "home");
            category = state.getInt("category", 0);
        }
        render();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("page", page);
        state.putInt("category", category);
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void feedback(View view) {
        if (prefs.getBoolean("haptics", true)) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
    }

    private void navigate(String destination) {
        page = destination;
        render();
    }

    private TextView text(String value, float size, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private void space(LinearLayout parent, int height) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
    }

    private void separator(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(divider);
        parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private void touch(View view, String label, Runnable run) {
        view.setClickable(true);
        view.setFocusable(true);
        view.setContentDescription(label);
        view.setOnClickListener(clicked -> { feedback(clicked); run.run(); });
    }

    private void card(View view, int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radius));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x22FFFFFF : 0x18000000), shape, mask));
    }

    private void render() {
        english = prefs.getBoolean("english", Locale.getDefault().getLanguage().equals("en"));
        String mode = prefs.getString("theme_mode", Settings.Secure.getString(getContentResolver(), "lambda_theme"));
        dark = mode == null ? prefs.getBoolean("dark", true) : mode.equals("auto")
                ? (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                : !mode.equals("light");
        background = dark ? 0xff181B1D : 0xffF4F3EF;
        surface = dark ? 0xff23272A : 0xffE6E6E2;
        foreground = dark ? 0xffF1F0EC : 0xff1A1D1F;
        secondary = dark ? 0xffACB2B6 : 0xff62696E;
        divider = dark ? 0xff363C40 : 0xffD3D5D3;
        root = column();
        root.setBackgroundColor(background);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        header();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        content = column();
        content.setPadding(dp(20), dp(12), dp(20), dp(20));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -1));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        switch (page) {
            case "settings": settings(); break;
            case "category": category(); break;
            case "sessions": sessions(); break;
            case "files": files(); break;
            default: home(); break;
        }
        footer();
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(dark ? 0 : mask, mask);
            }
        }
        root.requestApplyInsets();
    }

    private void header() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(10), dp(12), dp(12));
        TextView title = text("Lambda", 30, true, foreground);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1));
        title.setGravity(Gravity.CENTER_VERTICAL);
        touch(title, tr("Inicio", "Home"), () -> navigate("home"));
        Glyph gear = new Glyph(this, page.equals("settings") ? 10 : 6, foreground);
        bar.addView(gear, new LinearLayout.LayoutParams(dp(48), dp(48)));
        card(gear, background, 24);
        touch(gear, page.equals("settings") ? tr("Volver", "Back") : tr("Ajustes", "Settings"),
            () -> navigate(page.equals("settings") ? "home" : "settings"));
        root.addView(bar);
    }

    private void home() {
        String[] names = categories();
        if (prefs.getBoolean("icons", true)) {
            for (int row = 0; row < 3; row++) {
                LinearLayout pair = new LinearLayout(this);
                for (int col = 0; col < 2; col++) {
                    final int index = row * 2 + col;
                    LinearLayout tile = column();
                    tile.setGravity(Gravity.CENTER);
                    tile.setPadding(dp(8), dp(20), dp(8), dp(20));
                    tile.setMinimumHeight(dp(154));
                    card(tile, surface, 16);
                    tile.addView(new Glyph(this, index, index < 2 ? accents[index] : foreground),
                        new LinearLayout.LayoutParams(dp(78), dp(78)));
                    space(tile, 12);
                    TextView label = text(names[index], 21, true, foreground);
                    label.setGravity(Gravity.CENTER);
                    label.setMaxLines(1);
                    label.setAutoSizeTextTypeUniformWithConfiguration(15, 21, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
                    tile.addView(label, new LinearLayout.LayoutParams(-1, dp(30)));
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
                    if (col == 0) params.rightMargin = dp(12);
                    pair.addView(tile, params);
                    touch(tile, names[index], () -> { category = index; navigate("category"); });
                }
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, 0, 1);
                if (row < 2) rowParams.bottomMargin = dp(12);
                content.addView(pair, rowParams);
            }
        } else {
            for (int i = 0; i < names.length; i++) {
                final int index = i;
                TextView row = text(names[i], 38, true, foreground);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinHeight(dp(86));
                card(row, background, 0);
                content.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
                touch(row, names[i], () -> { category = index; navigate("category"); });
                if (i < names.length - 1) separator(content);
            }
        }
    }

    private void footer() {
        separator(root);
        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(dp(16), dp(8), dp(16), dp(8));
        String[] routes = {"home", "sessions", "files"};
        String[] labels = {tr("Herramientas", "Tools"), tr("Sesiones", "Sessions"), tr("Archivos", "Files")};
        for (int i = 0; i < routes.length; i++) {
            final String route = routes[i];
            LinearLayout item = column();
            item.setGravity(Gravity.CENTER);
            boolean active = page.equals(route) || (i == 0 && page.equals("category"));
            int tint = active ? foreground : secondary;
            item.addView(new Glyph(this, 7 + i, tint), new LinearLayout.LayoutParams(dp(28), dp(28)));
            space(item, 4);
            item.addView(text(labels[i], 11, active, tint));
            nav.addView(item, new LinearLayout.LayoutParams(0, dp(54), 1));
            card(item, background, 12);
            touch(item, labels[i], () -> navigate(route));
        }
        root.addView(nav);
    }

    private void heading(String title, String subtitle) {
        content.addView(text(title, 31, true, foreground));
        space(content, 12);
        TextView body = text(subtitle, 15, false, secondary);
        body.setLineSpacing(dp(3), 1);
        content.addView(body);
        space(content, 24);
    }

    private void button(String label, Runnable run) {
        TextView button = text(label, 17, true, foreground);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(20), dp(16), dp(20), dp(16));
        button.setMinHeight(dp(56));
        card(button, surface, 12);
        content.addView(button, new LinearLayout.LayoutParams(-1, -2));
        touch(button, label, run);
        space(content, 12);
    }

    private void fact(String label, String value) {
        content.addView(text(label, 13, false, secondary));
        space(content, 6);
        TextView body = text(value, 16, false, foreground);
        body.setTextIsSelectable(true);
        content.addView(body);
        space(content, 20);
    }

    private void category() {
        heading(categories()[category], tr("Información y acceso a herramientas", "Information and tool access"));
        if (snapshot == null) {
            content.addView(text(tr("Leyendo el dispositivo…", "Reading the device…"), 16, false, secondary));
            collect(false);
            return;
        }
        JSONObject features = snapshot.optJSONObject("features");
        if (category == 0) {
            fact(tr("Hardware Wi-Fi", "Wi-Fi hardware"), supported(features.optBoolean("wifi")));
            int state = snapshot.optInt("wifiState", -1);
            fact(tr("Estado", "State"), state == 3 ? tr("Activado", "Enabled") : state == 1 ? tr("Desactivado", "Disabled") : tr("En transición o no disponible", "Transitioning or unavailable"));
            button(tr("Ajustes de Wi-Fi", "Wi-Fi settings"), () -> settingsIntent(Settings.ACTION_WIFI_SETTINGS));
        } else if (category == 1) {
            fact("Bluetooth", supported(features.optBoolean("bluetooth")));
            fact("Bluetooth LE", supported(features.optBoolean("bluetoothLE")));
            button(tr("Ajustes de Bluetooth", "Bluetooth settings"), () -> settingsIntent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } else if (category == 2) {
            fact("USB host", supported(features.optBoolean("usbHost")));
            JSONArray devices = snapshot.optJSONArray("usbDevices");
            fact(tr("Dispositivos USB conectados", "Connected USB devices"), Integer.toString(devices.length()));
            for (int i = 0; i < devices.length(); i++) {
                JSONObject device = devices.optJSONObject(i);
                fact(device.optString("name", "USB"), String.format(Locale.ROOT, "%04x:%04x · %d interfaces",
                    device.optInt("vendorId"), device.optInt("productId"), device.optInt("interfaceCount")));
            }
        } else if (category == 4) {
            JSONArray networks = snapshot.optJSONArray("networks");
            fact(tr("Redes visibles para Android", "Networks visible to Android"), Integer.toString(networks.length()));
            for (int i = 0; i < networks.length(); i++) {
                JSONObject network = networks.optJSONObject(i);
                String label = network.optString("interface", tr("Interfaz", "Interface"));
                String kind = network.optBoolean("wifi") ? "Wi-Fi" : network.optBoolean("cellular") ? tr("Móvil", "Cellular") : "Ethernet / VPN";
                fact(label, kind);
            }
        } else {
            fact(tr("Adaptador", "Adapter"), tr("La detección específica se integrará con Lambda Core.", "Device-specific detection will be integrated with Lambda Core."));
        }
        button(tr("Abrir NetHunter", "Open NetHunter"), () -> openPackage("com.offsec.nethunter"));
        button(tr("Actualizar información", "Refresh information"), () -> collect(false));
        space(content, 8);
        content.addView(text(tr("El control guiado de herramientas se incorporará en las próximas alphas.",
            "Guided tool control will be added in the next alphas."), 13, false, secondary));
    }

    private String supported(boolean present) { return present ? tr("Detectado", "Detected") : tr("No anunciado por Android", "Not reported by Android"); }

    private void settingsIntent(String action) {
        try { startActivity(new Intent(action)); }
        catch (Exception unavailable) { notice(tr("Este ajuste no está disponible.", "This setting is unavailable.")); }
    }

    private void openPackage(String name) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(name);
        if (launch == null) {
            notice(tr("Esta aplicación no está instalada.", "This application is not installed."));
            return;
        }
        startActivity(launch);
    }

    private void sessions() {
        heading(tr("Sesiones", "Sessions"), tr("Diagnósticos locales del dispositivo.", "Local device diagnostics."));
        button(tr("Crear diagnóstico", "Create diagnostic"), () -> collect(true));
        button(tr("Abrir terminal NetHunter", "Open NetHunter terminal"), () -> openPackage("com.offsec.nhterm"));
        if (snapshot != null) {
            space(content, 12);
            fact(tr("Dispositivo", "Device"), snapshot.optString("model") + " · " + snapshot.optString("device"));
            fact("Android", snapshot.optString("android"));
            fact("Kernel", snapshot.optString("kernel"));
            fact(tr("Memoria disponible / total", "Available / total memory"), gib(snapshot.optLong("memoryAvailableBytes")) + " / " + gib(snapshot.optLong("memoryTotalBytes")));
            fact(tr("Almacenamiento disponible", "Available storage"), gib(snapshot.optLong("storageAvailableBytes")));
        }
    }

    private String gib(long bytes) { return String.format(Locale.getDefault(), "%.2f GiB", bytes / (1024d * 1024 * 1024)); }

    private void collect(boolean save) {
        if (collecting) return;
        collecting = true;
        worker.execute(() -> {
            try {
                JSONObject data = HardwareSnapshot.collect(getApplicationContext());
                if (save) {
                    String filename = "diagnostic-" + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date()) + ".json";
                    try (FileOutputStream out = openFileOutput(filename, MODE_PRIVATE)) {
                        out.write(data.toString(2).getBytes(StandardCharsets.UTF_8));
                    }
                }
                runOnUiThread(() -> {
                    collecting = false;
                    if (isFinishing() || isDestroyed()) return;
                    snapshot = data;
                    render();
                    if (save) notice(tr("Diagnóstico guardado en Archivos.", "Diagnostic saved in Files."));
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    collecting = false;
                    if (!isFinishing() && !isDestroyed()) notice(tr("No se pudo leer el diagnóstico: ", "Could not read diagnostic: ") + error.getClass().getSimpleName());
                });
            }
        });
    }

    private File[] diagnostics() {
        File[] files = getFilesDir().listFiles((folder, name) -> name.startsWith("diagnostic-") && name.endsWith(".json"));
        if (files == null) return new File[0];
        java.util.Arrays.sort(files, (left, right) -> right.getName().compareTo(left.getName()));
        return files;
    }

    private void files() {
        heading(tr("Archivos", "Files"), tr("Solo en este dispositivo. Tú decides qué exportar.", "Only on this device. You choose what to export."));
        File[] files = diagnostics();
        if (files.length == 0) {
            content.addView(text(tr("Aún no hay diagnósticos guardados.", "No saved diagnostics yet."), 16, false, secondary));
            space(content, 20);
            button(tr("Crear el primero", "Create the first one"), () -> collect(true));
            return;
        }
        for (File file : files) {
            button(file.getName().replace("diagnostic-", "").replace(".json", ""), () -> export(file));
        }
    }

    private void export(File file) {
        prefs.edit().putString("export", file.getName()).apply();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, file.getName());
        startActivityForResult(intent, 20);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 20 || result != RESULT_OK || data == null || data.getData() == null) return;
        String filename = prefs.getString("export", "");
        if (!filename.matches("diagnostic-[0-9-]+\\.json")) return;
        try (java.io.FileInputStream input = openFileInput(filename);
             OutputStream output = getContentResolver().openOutputStream(data.getData())) {
            if (output == null) throw new java.io.IOException("No output stream");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            notice(tr("Exportado.", "Exported."));
        } catch (Exception failure) {
            notice(tr("No se pudo exportar.", "Could not export."));
        }
    }

    private void settings() {
        heading(tr("Ajustes", "Settings"), "LambdaOS · 0.1 alpha");
        toggle(tr("Iconos y etiquetas", "Icons and labels"), "icons", true);
        toggle(tr("Respuesta háptica", "Haptic feedback"), "haptics", true);
        toggle("English", "english", Locale.getDefault().getLanguage().equals("en"));
        space(content, 24);
        fact(tr("Versión de Lambda", "Lambda version"), "0.1.0-alpha.1");
        fact("Lamb AI Agent", tr("Pendiente de integración", "Integration pending"));
        content.addView(text("the system for hacking systems.", 14, false, secondary));
    }

    private void toggle(String label, String key, boolean fallback) {
        Switch control = new Switch(this);
        control.setText(label);
        control.setTextColor(foreground);
        control.setTextSize(17);
        control.setMinHeight(dp(62));
        control.setChecked(prefs.getBoolean(key, fallback));
        control.setPadding(0, dp(12), 0, dp(12));
        content.addView(control, new LinearLayout.LayoutParams(-1, -2));
        control.setOnCheckedChangeListener((button, checked) -> {
            feedback(button);
            prefs.edit().putBoolean(key, checked).apply();
            render();
        });
        separator(content);
    }

    private void notice(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    @Override public void onBackPressed() {
        if (!page.equals("home")) navigate("home");
        else super.onBackPressed();
    }
}
