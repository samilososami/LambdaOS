# Operator UCM/SUCM, FDT and Temperature Monitor — 2026-11-23

Status: implemented, installed and device-tested as
`0.2.0-operator.20` (versionCode 40), worker `RadioWarden` (renamed from `RadioWorker` in 0.2.0-operator.21). The values below are the S10 thermal zones. **The large temperature
is the SoC junction, not the skin**: it has little thermal mass and can drop
tens of degrees in seconds; the battery/body line is the slow proxy.

## What was built

- **Cooling entry:** Lambda Operator → **Sistema** (new seventh category
  tile) → **Smart Ultra Cooling** or **Ultra Cooling**. The page shows the
  current SoC temperature; the buttons start the fullscreen cooling activity.
- **FDT entry:** Operator → Settings → **Developer mode** → **Temperature** →
  **Force Device temperature** → warning → target picker (50/55/60/65/70 °C)
  → hold picker (30/60/120/300 s). The target is the SoC; hold 120–300 s to
  warm the body, not just the die. This is a developer-only tool; it requires
  an idle Core and does not change persistent hardware settings.
- **Temperature Monitor:** Developer mode → Temperature → **Temperature
  Monitor** starts the same fullscreen activity in passive mode. It submits no
  Core job and changes nothing: it samples the SoC thermal zones every 500 ms
  and shows the start temperature, current temperature, elapsed counter,
  rate of change (°C/min, linear regression over the last 60 s), min/max
  during the session and the **battery/body** temperature with its own
  min/max. It works even if the root worker is offline, because it only reads
  sysfs. It is intended for the comparison flow: heat with FDT, measure natural
  cooling with the monitor, then repeat with UCM. The FDT screen has direct
  **Monitor cooling** and **Smart cooling** handoff buttons so the monitor or
  UCM starts at the hot SoC instead of after a navigation gap.
- **`system.cooling`** (Warden job): `mode=smart|ultra`, `target_c` (default
  42, UI uses 38). It snapshots CPU max frequencies, GPU max/governor,
  brightness, brightness mode, Wi-Fi and Bluetooth state in
  `/data/local/tmp/lambda-operator-radio/cooling.json`, applies limits and
  restores everything in `finally`. A worker restart recovers a leftover
  journal.
  - Smart: LITTLE 1.3 GHz / MID 1.5 GHz / BIG 1.8 GHz, GPU 433 MHz,
    brightness 40; if the smoothed temperature has not dropped after 20 s it
    escalates to the Ultra caps, dims to 16 and runs `am kill-all`; after 45 s
    without a drop it also disables Wi-Fi/Bluetooth and forces Doze.
  - Ultra: LITTLE 1.0 / MID 1.2 / BIG 1.4 GHz, GPU 260 MHz, brightness 16,
    Wi-Fi/Bluetooth off, Doze deep, `am kill-all`.
  - Target: 38 °C for the Operator entry. The job ends when the 10-sample
    smoothed SoC temperature stays at or below the target for 3 samples, or
    after 30 minutes. Safety abort at 78 °C raw. It restores and deletes the
    journal on every exit, including cancellation.
  - Progress: `phase`, `mode`, `temp_c` (smoothed), `raw_c`, `battery_c`,
    `start_c`, `target_c`, `rate_c_per_min`, `eta_seconds` (linear regression
    over the last samples, -1 while unmeasurable), `actions`.
- **`system.heater`** (FDT job): spins one CPU thread per online core, warms
  to the selected target, then **holds for 30 seconds** with a bang-bang
  controller (heat on below target−1 °C, off above target+1 °C) so the body
  gets warm, not just the die. Progress includes `phase=heating|holding`,
  `hold_remaining`, `temp_c` (5-sample average), `raw_c`, `battery_c`,
  `threads`. Safety abort at 75 °C raw, 20-minute timeout.
- **Fullscreen activity** (`CoolingActivity`): immersive system bars,
  orientation locked, keep-screen-on, back consumed, no in-app exit except
  **Parar** or the job finishing. It polls the Core job every 700 ms and shows
  the mode's action title, icon, large temperature, target, ETA/prediction,
  progress bar, actions and Parar.
  - Cooling: snowflake icon, title **“Cooling down the device”**, subtitle
    “Smart Ultra Cooling Mode” / “Ultra Cooling Mode”.
  - FDT: fire vector icon, title **“Heating device up”**, subtitle “Force
    Device Temperature”, **“Manteniendo 50 °C · N s”** during the hold.
  - On failure (for example worker offline) it shows the error and a **Cerrar**
    button; that is the only exception to the no-exit rule.
- **Notification:** active system jobs appear as `Cooling · smart · 38°C` or
  `Heater · target 65°C` with the existing per-job stop/open actions.
- **Core validation:** `system.cooling` accepts only `mode=smart|ultra` and
  `target_c` 35–60; `system.heater` accepts `target_c` 40–70; unknown fields
  are rejected. `system.heater` requires an empty Core queue. Both are in the
  Warden maintenance path with a one-hour wake lock and bridge deadline.
- **Icons:** the snowflake is derived from the user-provided reference
  (`design/ucm-snowflake.png` → tinted `res/drawable/ucm_snowflake.png`).
  The FDT flame is a vector drawable (`res/drawable/ucm_fire.xml`) because the
  chat attachment was not persisted as a project file.

## Verification (S10, 2026-11-23)

- Build/signing: `lambda-0.2.0-operator.16-release.apk`, release certificate
  `dd4c8fe7…`; install succeeded; worker version `0.2.0-operator.16`;
  `radio_status.available=true`.
- Contract suite: **36/36** rejection cases pass, including the new
  `system.cooling`/`system.heater` invalid-mode, invalid-target and
  unknown-field cases.
- Smart cooling from a hot state: heater to 63.2 °C, cooling started, heater
  cancelled; smoothed 47 → 44 → 41.3 → 39.8 → 37.2 → **36.7 °C in 13 s**;
  ETA 3/2/0 s; action `CPU/GPU cap`; target 38 reached.
- Ultra cooling: during the run CPU was 949/1066/1378 MHz (LITTLE/MID/BIG),
  GPU 260 MHz, brightness 16, Wi-Fi and Bluetooth 0; temperature 45 → 36.9 °C
  in 13 s; afterwards CPU 1.95/2.314/2.73 GHz, GPU 702 MHz, brightness 59,
  Wi-Fi/Bluetooth 1, Doze unforced, `cooling.json` deleted.
- FDT: target 50 reached, **30 s hold** observed with `phase=holding` and
  countdown, temperature oscillating ~48.6–55 °C, heat flag toggling; job
  completed and activity returned to Operator. Target picker 50–70 works;
  FDT stop button works; warning dialog precedes the picker. With a 60 s hold
  at target 60: SoC 59.2 °C, **battery/body 29.7 °C**; the monitor handoff
  started at SoC 56.0 °C / body 29.7 °C and reached 45.6 °C SoC after 3 s
  while the body stayed at 29.7 °C. This confirms the fast signal is the SoC
  junction, not the skin. The hold picker 30/60/120/300 s is in the UI.
- UCM handoff: pressing **Smart cooling** on the FDT screen starts the UCM
  activity at the hot SoC; pressing **Monitor cooling** starts the monitor.
  The FDT activity is finished so the new screen is the only one on top.
- SoC/body consistency: FDT, UCM and monitor use the same 5-sample SoC
  average and display the battery/body temperature separately.
- UCM UI: fullscreen screenshots verified for FDT and SUCM/UCM; System page
  shows the live SoC temperature and both cooling buttons; home tile “Sistema”
  is visible after compacting the grid; target picker and developer entry
  verified by real taps.
- Temperature page and monitor: Developer mode → Temperature shows the two
  tiles; the monitor opened with the thermometer icon and title “Temperature
  Monitor”, starting at 35.0 °C, showing 31.0 °C after 23 s, **Bajando
  9.1 °C/min**, battery 29.0 °C, min 31.0 °C, max 42.0 °C; Cerrar returned to
  Operator. CPU/GPU/brightness/Wi-Fi/Bluetooth remained untouched and no
  cooling journal was created.
- Worker offline path: FDT showed **“No se pudo iniciar · radio_worker_offline”**
  with a Cerrar button (the dev worker had been killed by force-stopping the
  app/Core). Restarting the worker restored normal operation.

## Limits / pending

- Fullscreen is immersive, locked and back-blocked, but the OS home gesture can
  still leave the activity. True kiosk pinning needs device-owner or lock-task
  allowlisting; not implemented.
- UCM target is fixed at 38 °C in the UI; FDT hold is fixed at 30 s. They can
  be made configurable in Core settings later.
- The prediction is a linear regression over the last temperature samples; it
  shows “Calculando descenso…” until there is enough slope, and it is an
  estimate, not a guaranteed time.
- The monitor's rate is a regression over a short window; the die sensors are
  fast and can show large °C/min values that do not match the skin. The start
  and max values can include a transient after launch. This is why every screen
  now shows the battery/body line: it is the slow proxy for the physical
  device, while the large figure is the SoC junction. For a realistic body
  soak, use FDT with a 120–300 s hold; a 30 s hold only heats the die.
- The die sensors react in seconds; the 30 s FDT hold is what warms the body.
  Skin temperature was not measured.
- Ultra disables Wi-Fi/Bluetooth; if an AP job is running, its upstream will
  drop. This is intentional for the extreme mode but should be surfaced in the
  UI before starting it during an AP.
- The Warden rename and the remaining “Sistema” functions (thermal detail,
  battery/charging, CPU/GPU, memory/storage, service status) are not part of
  this iteration.
- The cooling/heater jobs rely on the development root worker; a Core
  force-stop kills it and it must be restarted by ADB until the ROM
  integration replaces the development provisioning.
