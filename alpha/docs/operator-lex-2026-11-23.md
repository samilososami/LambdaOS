# LEX mode 1 — Kali XFCE desktop on the phone — 2026-11-23

Status: implemented and device-tested as `0.2.0-operator.27` (versionCode 47).
Mode 1 (desktop on the phone) is fully working. Modes 2 and 3 are integrated
as UI/discovery scaffolding (`LexMonitorActivity`, `LexWirelessActivity`); the
external monitor and wireless streaming paths remain unvalidated. Mode 4 (web
over ADB) is implemented and tested in `alpha/web/lex-dashboard` — see
`alpha/docs/lex-web-2026-11-23.md`. Version .27 applies the fixes from an
independent code review (see below).

## What was built

- Kali NetHunter full arm64 rootfs (1.8 GB `.tar.xz`, SHA-256 verified in
  `alpha/scripts/install_lex_dev.sh`) installed at `/data/local/lex/rootfs`
  (9.3 GB extracted, roughly 10 GB of device storage used).
- Device scripts `alpha/lex/start.sh` / `stop.sh`: bind mounts (proc, sys,
  dev, dev/pts, dev/shm), DNS, XFCE session, TigerVNC `Xvnc` on
  `127.0.0.1:5901` at **2280x1080 (19:9)**, websockify/noVNC on
  `127.0.0.1:6080`, status JSON and idempotent start/stop. The geometry
  matches the S10 landscape panel (3040x1440), so noVNC `resize=scale` fills
  the screen; the previous 1920x1080 left black side bars.
- Core tool `system.desktop` (`action=start|stop`) dispatched by RadioWarden;
  it runs only the fixed root-owned scripts, never caller paths or shell text.
  Contract tests reject unknown actions and fields.
- LEX tab in Operator (eighth category, screen-with-lambda glyph) with
  **Abrir escritorio** / **Detener escritorio**; `LexActivity` opens the
  desktop in landscape fullscreen through a WebView running noVNC against
  loopback, with cleartext restricted to `127.0.0.1`/`localhost`.
- **Desktop as a Core-visible task**: the start/stop jobs are short-lived, but
  Core keeps the desktop state (ports, password, running) in private
  preferences, verifies it against `127.0.0.1:5901` and `127.0.0.1:6080`, and
  exposes it through `desktop_status`. While the desktop runs, the resident
  notification shows **LEX · escritorio** with **Detener LEX** (enqueues a
  typed stop job) and **Abrir en Operator** (opens `LexActivity`). The desktop
  does not occupy the worker slot, so AP/cooling/cracking jobs can still run
  alongside it.
- **Stopping closes the viewer**: `LexActivity` polls `desktop_status`; when
  the desktop is stopped from the notification, the LEX page or another
  client, the viewer closes and MainActivity returns to the LEX tab
  (`open=lex_tab`). Killing Xvnc directly also closes it (port check).
- **Wallpaper**: `alpha/lex/make_wallpaper.py` generates the horizontal
  LambdaOS wallpaper (graphite radial gradient + fine dot field, the approved
  setup-background language, faint Λ). The provisioner pushes it and points
  the Kali `kali-16x9/default` symlink and the system xfconf default at it.
- **Robustness**: stale X locks are cleared before starting; stop kills every
  websockify process (0.13 multiprocessing helpers inherit the listening
  socket); start only reports running when both VNC and noVNC listen. When the
  desktop dies externally, `desktop_status` clears the state, refreshes the
  notification and the viewer closes.
- **Modes 2 and 3**: LEX page buttons open `LexMonitorActivity` (external
  display detection, Presentation with noVNC, phone trackpad via an
  input-only RFB client) and `LexWirelessActivity` (Wi-Fi Direct WFD sink
  discovery and a guarded `connectWifiDisplay` attempt). Hardware paths are
  untested; see `alpha/docs/lex-modes-integration-2026-11-23.md`.
- **Web dashboard over ADB**: `alpha/web/lex-dashboard` (Node, zero
  dependencies, loopback) with device metrics, Core jobs, screenshot,
  start/stop and an embedded noVNC iframe; see `alpha/docs/lex-web-2026-11-23.md`.
- **Worker resilience**: RadioWarden re-resolves Core's external provider
  after a Core process death instead of exiting; the same worker PID survives
  app force-stops/updates and reconnects.
- **Review hardening (.27)**: desktop start/stop are rejected with
  `worker_busy` while a non-desktop worker job runs (no silent queue behind an
  AP); the desktop state is written atomically with the job record and derived
  from the result even when the job was cancelled; the desktop scripts tick
  progress while they run (no 30 s heartbeat expiry) and a cancelled start
  runs `stop.sh`; the notification probes the desktop ports every 30 s when no
  viewer polls; the synthetic task id is `lex.desktop` (outside the accepted
  request-id alphabet); stop reports `stop_incomplete` if a listener survives;
  the start script verifies the VNC port and that websockify is ours, validates
  the geometry, and cleans up via a trap/stop on failure; the installer has an
  EXIT trap and an extraction marker; the wireless activity closes its P2P
  channel; the viewer is `singleTop`, pauses its status poll, and cancels a
  timed-out queued start.
- XFCE tuning needed by the chroot:
  - `TMPDIR=/tmp` and `XDG_RUNTIME_DIR=/tmp/runtime-root`: Android exports
    `TMPDIR=/data/local/tmp`, which does not exist inside the chroot and made
    GTK abort.
  - `GLYCIN_DISABLE_SANDBOX=i-know-the-risks` after upgrading glycin to
    2.2.2: glycin's bwrap sandbox cannot create namespaces here and every
    icon load aborted apps with a GTK assertion. This is a development-chroot
    trade-off, not a ROM setting.
  - xfce4-screensaver autostart disabled and `xset s off -dpms`; the
    screensaver painted a black fullscreen window and hid the desktop.
  - Kali-Dark theme, Flat-Remix-Blue-Dark icons; panel plugin ids 16/18
    removed (listed without definitions, caused a "(null)" plugin dialog).
  - xfwm4 compositing disabled.

## Verification (S10, 2026-11-23)

- Rootfs hash matches `SHA256SUMS`; `start.sh` returns `running:true`; 5901
  and 6080 listen on loopback only.
- From the phone: LEX tab → **Abrir escritorio** → `LexActivity` in landscape
  shows the Kali desktop (panel, icons, whisker menu). Tapping the whisker
  menu with a finger opens the Kali applications menu, so touch works through
  noVNC.
- **Detener escritorio**: `system.desktop` job completed, `status.json`
  `running:false`, listeners closed; **Abrir escritorio** starts it again and
  reopens the viewer.
- Contract suite 40/40; `status` reports `0.2.0-operator.26`.
- Notification: with the desktop running, the shade shows “LEX · escritorio”
  with both actions; **Abrir en Operator** opened `LexActivity`, **Detener
  LEX** stopped the desktop, closed 5901/6080 and returned the notification to
  “Listo · Wi-Fi, Bluetooth y USB”. Tapping the notification itself also
  targets `LexActivity`. The state survives a Core process restart (prefs +
  port check).
- Viewer auto-close: three start → open viewer → notification stop cycles all
  ended back on MainActivity/LEX with no listeners left. Killing Xvnc while
  the viewer was open closed it on the next poll and cleared the state;
  restarting worked.
- Wallpaper: the S10 landscape desktop now shows the LambdaOS dot-gradient
  wallpaper edge-to-edge at 2280x1080.
- Idle desktop (viewer open): app 8.2% CPU (5-minute average), Xtigervnc RSS
  ~97 MB, xfdesktop ~61 MB, panel ~37 MB, xfwm4 ~36 MB; SoC BIG 35 °C,
  battery 28.2 °C. Frame rate was not measured.
- Keyboard: tapping the noVNC canvas/whisker search focuses noVNC's input and
  the Android IME appears; typing `term` filtered the whisker menu, opening
  Terminal Emulator and running `ls` in the Kali shell via the IME. Touch +
  system keyboard work end-to-end.
- External death: killing websockify (both-port check) cleared the state,
  returned the notification to “Listo…” and closed the viewer. Killing Xvnc
  did the same through the VNC port.
- Web dashboard: Restore/Start set the 6080/5901 forwards and the iframe
  showed the desktop; Stop removed the forwards and showed the empty state;
  Start again restored it. Jobs and screenshot cards showed live data.
- Modes 2/3 UI: no-monitor setup screen verified; Wi-Fi Direct discovered a
  real LG webOS TV advertising Wi-Fi Display (not connected to).
- Worker: force-stopping the app kept the worker PID alive and
  `radio_status.available` was true after the app restarted.
- Start/stop job durations observed: start ~18.7 s, stop ~15–19 s (chroot
  scripts are synchronous); pending optimization.
- .27 review fixes verified on the S10: cancelling a start 2 s in leaves the
  job `cancelled`, no listeners, no chroot mounts and an idle notification;
  a desktop start during a running AP is rejected with
  `worker_busy: wait for radio.ap`; after killing Xvnc with no viewer open the
  30 s notification probe returns the shade to “Listo” and `desktop_status`
  to false; the notification stop action with `lex.desktop` stops the desktop;
  the contract suite stays at 40/40 and the lifecycle test still passes.

## Limits / pending

- Frame rate at 2280x1080 remains unmeasured; CPU rendering is the limit.
- Start/stop are slow (~15–19 s); an optimization is listed in IDEAS.
- Modes 2–3 hardware paths and the build's byte reproducibility are pending.
- Mode 2 (review): an RFB error is terminal until the activity is recreated
  and it has no `desktop_status` poll, so an external desktop stop/restart
  leaves the Presentation stale. The hardware is untested; fixing this is
  pending before mode 2 is presented as usable.
- If Core dies in the middle of a start, the completed desktop is untracked
  until the next start job (start.sh is idempotent and re-adopts it); an
  automatic adoption from the root status file is not implemented.
- Web dashboard (review): a 504 from a slow start/stop abandons the still
  running job without repairing forwards, and the Fullscreen link puts the VNC
  password in browser history. Both are documented for a follow-up.
- Performance/thermal at 2280x1080 (2.46 MP) were not measured; CPU rendering
  (llvmpipe) is expected to be the limit. Native 3040x1440 would be crisper but
  doubles the pixels.
- The desktop runs as root inside the chroot and is loopback-only; the rootfs
  is a development artefact, not ROM integration.
- Mode 4 (web over ADB) is implemented and tested. Modes 2–3 are integrated
  as UI/discovery scaffolding; their hardware paths remain untested.
