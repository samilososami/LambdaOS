package org.lambdaproject.lambda;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Root cooling controller for UCM. Runs inside the Warden worker.
 *  It caps CPU/GPU, dims the screen and (Ultra) drops radios/background
 *  load, restoring every value on exit. A journal lets a replaced worker
 *  recover a session that died mid-cooling. */
final class CoolingJob {
    interface Progress { void tick(JSONObject progress) throws Exception; }

    static final String BASE = "/data/local/tmp/lambda-operator-radio";
    private static final String JOURNAL = BASE + "/cooling.json";
    private static final int DEFAULT_TARGET = 42;
    private static final int TIMEOUT = 1800;
    private static final double ABORT_C = 78.0;

    private CoolingJob() {}

    static JSONObject run(JSONObject args, Progress progress) throws Exception {
        String mode = args.optString("mode", "smart");
        boolean ultra = mode.equals("ultra");
        int target = args.optInt("target_c", DEFAULT_TARGET);
        JSONObject snapshot = snapshot();
        writeJournal(snapshot);
        Set<String> actions = new LinkedHashSet<>();
        double start = 0;
        long started = System.currentTimeMillis();
        List<double[]> history = new ArrayList<>();
        List<Double> rawHistory = new ArrayList<>();
        JSONObject result = new JSONObject();
        try {
            applyCaps(ultra ? "ultra" : "smart", actions);
            if (ultra) applyExtras(actions);
            boolean escalated = ultra, extras = ultra;
            int coolHits = 0;
            while (true) {
                long elapsed = (System.currentTimeMillis() - started) / 1000;
                double raw = socTempC();
                rawHistory.add(raw);
                while (rawHistory.size() > 5) rawHistory.remove(0);
                double temp = 0;
                for (double sample : rawHistory) temp += sample;
                temp /= rawHistory.size();
                if (start == 0) start = temp;
                double battery = batteryTempC();
                history.add(new double[]{elapsed, temp});
                while (history.size() > 90) history.remove(0);
                double slope = slope(history);
                if (!escalated && elapsed >= 20 && temp > start - 0.3 && slope > -0.005) {
                    applyCaps("ultra", actions);
                    setBrightness(16, actions);
                    sh("am", "kill-all");
                    actions.add("escalated");
                    escalated = true;
                }
                if (!extras && elapsed >= 45 && slope > -0.002) {
                    applyExtras(actions);
                    extras = true;
                }
                int eta = etaSeconds(temp, target, slope);
                JSONObject p = new JSONObject()
                    .put("phase", escalated ? "cooling" : "smart")
                    .put("mode", mode).put("target_c", target).put("start_c", round(start))
                    .put("temp_c", round(temp)).put("raw_c", round(raw)).put("battery_c", round(battery))
                    .put("elapsed", elapsed).put("rate_c_per_min", round(slope * 60))
                    .put("eta_seconds", eta).put("actions", new JSONArray(actions));
                progress.tick(p);
                if (temp <= target) {
                    if (++coolHits >= 3) {
                        result.put("outcome", "target");
                        break;
                    }
                } else coolHits = 0;
                if (raw >= ABORT_C) {
                    result.put("outcome", "thermal_abort");
                    break;
                }
                if (elapsed >= TIMEOUT) {
                    result.put("outcome", "timeout");
                    break;
                }
                Thread.sleep(1000);
            }
            double end = 0;
            for (double sample : rawHistory) end += sample;
            end /= rawHistory.size();
            result.put("mode", mode).put("start_c", round(start)).put("end_c", round(end))
                .put("target_c", target).put("battery_c", round(batteryTempC()))
                .put("seconds", (System.currentTimeMillis() - started) / 1000)
                .put("actions", new JSONArray(actions));
            return result;
        } finally {
            restore(snapshot);
        }
    }

    /** Undo a cooling session left behind by a previous worker process. */
    static void recover() {
        File journal = new File(JOURNAL);
        if (!journal.isFile()) return;
        try {
            restore(new JSONObject(read(journal.getPath())));
        } catch (Exception ignored) {
            journal.delete();
        }
    }

    private static JSONObject snapshot() throws Exception {
        JSONObject s = new JSONObject();
        JSONObject cpu = new JSONObject();
        for (int i = 0; i < 16; i++) {
            File f = new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_max_freq");
            if (f.isFile()) cpu.put(Integer.toString(i), read(f.getPath()));
        }
        s.put("cpu", cpu);
        s.put("gpu_max", read("/sys/kernel/gpu/gpu_max_clock"));
        s.put("gpu_gov", read("/sys/kernel/gpu/gpu_governor"));
        s.put("brightness", sh("settings", "get", "system", "screen_brightness"));
        s.put("brightness_mode", sh("settings", "get", "system", "screen_brightness_mode"));
        s.put("wifi", sh("settings", "get", "global", "wifi_on"));
        s.put("bt", sh("settings", "get", "global", "bluetooth_on"));
        return s;
    }

    private static void applyCaps(String profile, Set<String> actions) {
        boolean ultra = profile.equals("ultra");
        capCluster(0, 3, ultra ? 1000000 : 1300000);
        capCluster(4, 5, ultra ? 1200000 : 1500000);
        capCluster(6, 7, ultra ? 1400000 : 1800000);
        write("/sys/kernel/gpu/gpu_max_clock", ultra ? "260000" : "433000");
        setBrightness(ultra ? 16 : 40, actions);
        actions.add(ultra ? "CPU/GPU max" : "CPU/GPU cap");
    }

    private static void applyExtras(Set<String> actions) {
        sh("svc", "wifi", "disable");
        sh("svc", "bluetooth", "disable");
        sh("cmd", "deviceidle", "force-idle", "deep");
        sh("am", "kill-all");
        actions.add("Wi-Fi/BT off");
        actions.add("Doze");
        actions.add("background apps");
    }

    private static void capCluster(int from, int to, int cap) {
        for (int i = from; i <= to; i++) {
            String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            if (!new File(base + "scaling_max_freq").isFile()) continue;
            int value = cap;
            try {
                String available = read(base + "scaling_available_frequencies");
                int best = 0;
                for (String part : available.trim().split("\\s+")) {
                    if (part.isEmpty()) continue;
                    int frequency = Integer.parseInt(part);
                    if (frequency <= cap && frequency > best) best = frequency;
                }
                if (best > 0) value = best;
            } catch (Exception ignored) {}
            write(base + "scaling_max_freq", Integer.toString(value));
        }
    }

    private static void setBrightness(int value, Set<String> actions) {
        sh("settings", "put", "system", "screen_brightness_mode", "0");
        sh("settings", "put", "system", "screen_brightness", Integer.toString(value));
    }

    private static void restore(JSONObject s) {
        try { sh("cmd", "deviceidle", "unforce"); } catch (Exception ignored) {}
        restoreRadio("wifi", s.optString("wifi", "null"));
        restoreRadio("bluetooth", s.optString("bt", "null"));
        String mode = s.optString("brightness_mode", "null");
        String brightness = s.optString("brightness", "null");
        if (!"null".equals(mode)) sh("settings", "put", "system", "screen_brightness_mode", mode);
        if (!"null".equals(brightness)) sh("settings", "put", "system", "screen_brightness", brightness);
        String gpuMax = s.optString("gpu_max", "");
        String gpuGov = s.optString("gpu_gov", "");
        if (gpuMax.matches("[0-9]+")) write("/sys/kernel/gpu/gpu_max_clock", gpuMax);
        if (!gpuGov.isEmpty() && !"null".equals(gpuGov)) write("/sys/kernel/gpu/gpu_governor", gpuGov);
        JSONObject cpu = s.optJSONObject("cpu");
        if (cpu != null) {
            for (java.util.Iterator<String> it = cpu.keys(); it.hasNext(); ) {
                String index = it.next();
                String value = cpu.optString(index, "");
                if (value.matches("[0-9]+")) write("/sys/devices/system/cpu/cpu" + index + "/cpufreq/scaling_max_freq", value);
            }
        }
        new File(JOURNAL).delete();
    }

    private static void restoreRadio(String service, String state) {
        if (!"0".equals(state) && !"1".equals(state)) return;
        sh("svc", service, "1".equals(state) ? "enable" : "disable");
    }

    private static void writeJournal(JSONObject s) throws Exception {
        try (FileOutputStream out = new FileOutputStream(JOURNAL)) {
            out.write(s.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }

    static double socTempC() {
        double max = 0;
        File[] zones = new File("/sys/class/thermal").listFiles();
        if (zones == null) return 0;
        for (File zone : zones) {
            if (!zone.getName().startsWith("thermal_zone")) continue;
            try {
                String type = read(zone.getPath() + "/type");
                if (!type.equals("BIG") && !type.equals("MID") && !type.equals("LITTLE")
                    && !type.equals("G3D") && !type.equals("ISP")) continue;
                double value = Double.parseDouble(read(zone.getPath() + "/temp")) / 1000.0;
                if (value > -20 && value < 200 && value > max) max = value;
            } catch (Exception ignored) {}
        }
        return max;
    }

    static double batteryTempC() {
        File[] zones = new File("/sys/class/thermal").listFiles();
        if (zones == null) return 0;
        for (File zone : zones) {
            if (!zone.getName().startsWith("thermal_zone")) continue;
            try {
                if (!read(zone.getPath() + "/type").equals("battery")) continue;
                return Double.parseDouble(read(zone.getPath() + "/temp")) / 1000.0;
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private static double slope(List<double[]> history) {
        if (history.size() < 6) return 0;
        int n = Math.min(60, history.size());
        double sumX = 0, sumY = 0, sumXX = 0, sumXY = 0;
        for (int i = history.size() - n; i < history.size(); i++) {
            double x = history.get(i)[0], y = history.get(i)[1];
            sumX += x; sumY += y; sumXX += x * x; sumXY += x * y;
        }
        double den = n * sumXX - sumX * sumX;
        return den == 0 ? 0 : (n * sumXY - sumX * sumY) / den;
    }

    private static int etaSeconds(double temp, int target, double slope) {
        if (temp <= target) return 0;
        if (slope >= -0.002) return -1;
        int eta = (int) ((temp - target) / -slope);
        return Math.min(3600, Math.max(1, eta));
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.US_ASCII).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static void write(String path, String value) {
        try {
            Files.write(Paths.get(path), value.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ignored) {}
    }

    private static String sh(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            p.waitFor();
            return out.toString("US-ASCII").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
