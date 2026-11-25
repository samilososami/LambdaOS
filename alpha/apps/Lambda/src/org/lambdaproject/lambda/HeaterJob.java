package org.lambdaproject.lambda;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Developer-only heater for testing UCM. Spins one thread per CPU until the
 *  sustained temperature (not a one-second die spike) reaches the target. */
final class HeaterJob {
    interface Progress { void tick(JSONObject progress) throws Exception; }

    private static final int TIMEOUT = 1200;
    private static final int DEFAULT_HOLD = 30;
    private static final double ABORT_C = 75.0;
    private static volatile double sink;

    private HeaterJob() {}

    static JSONObject run(JSONObject args, Progress progress) throws Exception {
        int target = args.optInt("target_c", 65);
        int holdSeconds = Math.max(30, Math.min(300, args.optInt("hold_seconds", DEFAULT_HOLD)));
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        long started = System.currentTimeMillis();
        final boolean[] running = {true};
        final boolean[] heat = {true};
        List<Thread> pool = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread thread = new Thread(() -> {
                double value = 1.000001;
                while (running[0]) {
                    if (heat[0]) {
                        for (int n = 0; n < 20000; n++) value = Math.sqrt(value * value + 1.0);
                        sink = value;
                    } else {
                        try { Thread.sleep(50); } catch (InterruptedException ignored) { return; }
                    }
                }
            }, "ucm-heater-" + i);
            thread.setDaemon(true);
            thread.start();
            pool.add(thread);
        }
        List<Double> history = new ArrayList<>();
        try {
            String outcome = "timeout";
            double start = 0;
            int hotHits = 0;
            boolean holding = false;
            long holdStart = 0;
            while (true) {
                long elapsed = (System.currentTimeMillis() - started) / 1000;
                double raw = CoolingJob.socTempC();
                double battery = CoolingJob.batteryTempC();
                history.add(raw);
                while (history.size() > 5) history.remove(0);
                double temp = 0;
                for (double sample : history) temp += sample;
                temp /= history.size();
                if (start == 0) start = temp;
                JSONObject p = new JSONObject().put("mode", "heater")
                    .put("target_c", target).put("start_c", Math.round(start * 10) / 10.0)
                    .put("temp_c", Math.round(temp * 10) / 10.0).put("raw_c", Math.round(raw * 10) / 10.0)
                    .put("battery_c", Math.round(battery * 10) / 10.0)
                    .put("elapsed", elapsed).put("threads", threads);
                if (!holding) {
                    p.put("phase", "heating");
                    if (temp >= target) {
                        if (++hotHits >= 3) {
                            holding = true;
                            holdStart = elapsed;
                            heat[0] = temp < target + 1.0;
                        }
                    } else hotHits = 0;
                } else {
                    int remaining = holdSeconds - (int) (elapsed - holdStart);
                    if (remaining <= 0) { outcome = "target"; break; }
                    if (temp < target - 1.0) heat[0] = true;
                    else if (temp > target + 1.0) heat[0] = false;
                    p.put("phase", "holding").put("hold_remaining", remaining);
                }
                p.put("heat", heat[0]);
                progress.tick(p);
                if (raw >= ABORT_C) { outcome = "thermal_abort"; break; }
                if (elapsed >= TIMEOUT) break;
                Thread.sleep(1000);
            }
            double end = 0;
            for (double sample : history) end += sample;
            end /= history.size();
            return new JSONObject().put("outcome", outcome).put("mode", "heater")
                .put("start_c", Math.round(start * 10) / 10.0)
                .put("end_c", Math.round(end * 10) / 10.0)
                .put("target_c", target).put("battery_c", Math.round(CoolingJob.batteryTempC() * 10) / 10.0)
                .put("seconds", (System.currentTimeMillis() - started) / 1000)
                .put("hold_seconds", holdSeconds).put("threads", threads);
        } finally {
            running[0] = false;
            for (Thread thread : pool) thread.interrupt();
        }
    }
}

