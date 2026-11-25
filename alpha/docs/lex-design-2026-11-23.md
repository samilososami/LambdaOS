# LEX — Lambda EXperience design — 2026-11-23

Status: mode 1 (desktop on the phone) implemented and device-tested; mode 4
(web over ADB) implemented and tested in `alpha/web/lex-dashboard`; modes 2–3
integrated as UI/discovery scaffolding with the hardware paths untested.
`0.2.0-operator.26` includes the LEX tab, the `system.desktop` Core job, the
mode-2 monitor activity and the mode-3 wireless sink scanner. See
`alpha/docs/operator-lex-2026-11-23.md`, `alpha/docs/lex-web-2026-11-23.md`
and `alpha/docs/lex-modes-integration-2026-11-23.md`.

## Modes

1. **Phone-only desktop**: S10 in landscape showing a Kali/Debian XFCE
   desktop with LambdaOS wallpaper/branding.
2. **USB-C external monitor** (USB-C→HDMI or USB-C→USB-C), automatic
   detection: desktop on the monitor; the phone becomes a trackpad plus
   on-screen keyboard; reduce CPU/thermal load while casting.
3. **Wireless desktop**, DeX-like but vendor-neutral: scan nearby
   displays/TVs, pick one, connection animation, desktop on the display;
   phone as trackpad/keyboard; Wi-Fi Direct.
4. **Web over ADB**: any PC with `adb` opens the desktop in a browser; the
   host keyboard/mouse control it with low latency.

All modes expose a Bluetooth keyboard/mouse pairing action; a USB keyboard or
mouse through a hub works through Android input.

## Decisions from research

- **Desktop**: Kali arm64 chroot (NetHunter rootfs tar; a Debian arm64 image
  is the smaller neutral alternative) + TigerVNC `Xvnc` bound to `127.0.0.1` +
  XFCE, following NetHunter KeX's architecture (minimal bind mounts,
  `xstartup`), without NetHunter's setuid `sudo`, `setenforce 0` or
  `remount,suid /data` practices.
- **Web mode**: noVNC + websockify inside the chroot on `127.0.0.1:6080`;
  the PC only runs `adb forward tcp:6080 tcp:6080` and opens the browser.
  Port 5901 is forwarded too for native VNC clients. Nothing is exposed on
  the network; adb forward listens on the host loopback only.
- **USB-C monitor**: the S10 kernel has Exynos DisplayPort
  (`CONFIG_EXYNOS_DISPLAYPORT`, `dp_sec` sysfs) and Android exposes external
  displays. Use a `Presentation` with a `SurfaceView` fed by an RFB client,
  plus a phone-side trackpad UI. LineageOS has no Samsung DeX framework, so
  this is our own desktop (mirror/extend), and DP audio is not routed on
  LineageOS (separate work).
- **Wireless**: the LineageOS 22.2 tree enables `config_enableWifiDisplay=true`,
  P2P and WFD encoders. The AOSP path mirrors the primary display; an
  independent desktop needs our own WFD source (or a LEX receiver app).
  First gate: test native Miracast mirroring against a real sink.
- **Input**: the phone UI sends events to the RFB session directly; for
  Android displays, `INJECT_EVENTS` requires a system app or signature
  permission.

## Phases

- **H1 (done, mode 1)**: Kali arm64 rootfs in `/data/local/lex`, XFCE +
  TigerVNC on loopback, start/stop/status scripts, LEX tab and `LexActivity`
  viewer over noVNC. Verified on the phone with touch.
- **H2 (done)**: noVNC/websockify inside the chroot and the local web dashboard
  (`alpha/web/lex-dashboard`) with start/stop, forwards, device metrics and
  Core jobs; verified end-to-end.
- **H3 (partially done for mode 1)**: LEX tab in Operator and the
  `system.desktop` Core job supervised by Warden (start/stop, session result
  with VNC password). Remaining: separate `lex.*` naming and a status job.
- **H4**: on-phone view (embedded noVNC/WebView or a VNC client), landscape
  and trackpad-first layout; Bluetooth keyboard/mouse shortcut.
- **H5 (scaffolded)**: USB-C external display mode — detection, Presentation
  with noVNC and a phone trackpad via an input-only RFB client; no monitor was
  available to validate it.
- **H6 (partially scaffolded)**: wireless discovery works (a real LG webOS TV
  advertising WFD was found); `connectWifiDisplay` and native Miracast remain
  untested; an independent desktop still needs our own WFD source.

## Gates / risks

- `chroot` and bind mounts under SELinux Enforcing: test early; if denied,
  add policy in the Core domain, not `setenforce 0`.
- Exynos 9820 CPU rendering: 1080p30 is the realistic target; measure fps and
  temperature.
- USB-C DP output on LineageOS 22.2 is unverified on this S10; community
  reports for S10e/22.1 mention flicker/reboot and no DP audio.
- Miracast on this firmware is unverified; 5 GHz/country code may fall back to
  2.4 GHz.
- LEX must be Core/Warden jobs with receipts, not arbitrary shell from a
  launcher script.

## References

- Kali NetHunter KeX: https://www.kali.org/docs/nethunter/nethunter-kex-manager/
- TigerVNC Xvnc: https://manpages.debian.org/trixie/tigervnc-standalone-server/Xvnc.1.en.html
- noVNC: https://github.com/novnc/noVNC
- websockify: https://github.com/novnc/websockify
- scrcpy virtual display: https://github.com/Genymobile/scrcpy/blob/master/doc/virtual-display.md
- Android Presentation: https://developer.android.com/reference/android/app/Presentation
- LineageOS exynos9820 common tree: https://github.com/LineageOS/android_device_samsung_exynos9820-common
- AOSP WifiDisplayController: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/display/WifiDisplayController.java
