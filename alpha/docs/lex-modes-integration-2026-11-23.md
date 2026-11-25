# LEX modes 2 and 3 — integration notes — 2026-11-23

Status: **integrated in `0.2.0-operator.25/26` and device-tested at the UI
level.** Mode 1 (`LexActivity`) remains the only fully working desktop mode.
Mode 2 now shows its no-monitor setup screen on the S10; mode 3 discovers real
Wi-Fi Display sinks and lists them. The external-display and wireless paths
themselves remain unvalidated (no monitor was available; the discovered TV was
not connected to, to avoid acting on the user's equipment unattended).

The manifest entries, the `NEARBY_WIFI_DEVICES` permission and the two LEX
page buttons from this document were applied by the main session. Sections
below are kept as the integration reference.

New files:

- `alpha/apps/Lambda/src/org/lambdaproject/lambda/LexMonitorActivity.java` —
  mode 2: USB-C/DisplayPort monitor as an Android `Presentation` running
  noVNC, with the phone as an absolute touch surface, click buttons, scroll
  strip and system-IME keyboard.
- `alpha/apps/Lambda/src/org/lambdaproject/lambda/LexRfbInput.java` —
  input-only RFB 3.3/3.7/3.8 client (VncAuth, PointerEvent, KeyEvent), no
  framebuffer requests.
- `alpha/apps/Lambda/src/org/lambdaproject/lambda/LexWirelessActivity.java` —
  mode 3 scaffold: Wi-Fi Direct discovery of WFD sinks plus a guarded,
  reflection-based attempt at the platform Wi-Fi Display connect.

## Behaviour in the code

### Mode 2 — USB-C display

1. Detection uses
   `DisplayManager.getDisplays(DISPLAY_CATEGORY_PRESENTATION)` and accepts a
   display only when its id is not `DEFAULT_DISPLAY`, its state is
   `STATE_ON` and `FLAG_PRESENTATION` is set. A `DisplayListener` handles
   hotplug while the activity is resumed; `onResume` re-scans.
2. With no display, a dark setup screen says to connect a USB-C monitor, with
   **Reintentar** and **Volver**. It never pretends the monitor is there.
3. With a display, the activity calls `desktop_status`. If a desktop is
   already running it attaches; otherwise it submits the typed
   `system.desktop` start job, polls `get` for up to 90 s, and then reads
   `vnc_port`, `web_port`, `geometry` and `password` from the job result.
   Those are the same fields the LEX `start.sh` writes to `status.json`.
4. The Presentation loads
   `http://127.0.0.1:<web_port>/vnc.html?autoconnect=1&resize=scale&reconnect=true&view_only=0&password=<urlencoded>`
   in a WebView, matching mode 1. The RFB input client connects to
   `127.0.0.1:<vnc_port>`; both are loopback.
5. Only a desktop this activity started is stopped on exit. Attaching to a
   desktop started elsewhere (mode 1, notification, CLI) leaves it running.
   The stop is submitted as a best-effort typed job from `onDestroy`; if the
   process dies first, the desktop simply stays up.
6. Failure reporting: no display, desktop start failure, Presentation
   failure and RFB auth/disconnect each have a visible status or a setup
   screen. No path claims success without a running server and a displayed
   Presentation.

Trackpad semantics: the touch area maps **absolutely** onto the VNC geometry
(`e.getX()/width*2280`, `e.getY()/height*1080`). Touching moves the pointer
without a click; a tap (release before 10 dp of movement) is a left click at
that point; a drag presses the left button once the finger passes 10 dp and
releases on lift, so window drags work. The **Clic izq./Clic der.** buttons
click at the last pointer position (masks 1/4). The scroll strip sends wheel
buttons 4/5. The keyboard is a transparent 1 dp `EditText` whose
`InputConnection` forwards commits, backspace and control keys; the **Ctrl**
button is sticky for the next printable character. Printable ASCII only
(0x20–0x7E). RFB keysyms: Enter `0xFF0D`, Backspace `0xFF08`, Tab `0xFF09`,
Escape `0xFF1B`, arrows `0xFF51–0xFF54`, Control `0xFFE3`.

VncAuth: the password from the Core receipt is 12 hex characters; TigerVNC
only uses the first 8. `LexRfbInput` builds the 8-byte DES key by reversing
each byte's bits, then DES-ECB encrypts the 16-byte server challenge. The
result is checked against an independent known-answer vector (see
*Validation* below).

### Mode 3 — wireless display (scaffold only)

- `WifiP2pManager` discovery; peers with `getWfdInfo() != null` are kept only
  if `getDeviceType()` is `PRIMARY_SINK`, `SECONDARY_SINK` or
  `SOURCE_OR_PRIMARY_SINK`. The SDK 35 stubs expose `getDeviceType()` but not
  `isSink()`, so the constants are used directly.
- Rows show name, address and peer status in the LambdaOS dark list. A
  selected row enables **Conectar pantalla**.
- Connect uses reflection on `DisplayManager.connectWifiDisplay(String)`.
  `SecurityException` (direct or wrapped in `InvocationTargetException`)
  reports that `CONFIGURE_WIFI_DISPLAY` is a system/signature permission and
  needs ROM integration. `NoSuchMethodException` reports that the API is
  unavailable. After a call that did not throw, the activity waits 3.5 s and
  looks for a new presentation display; it says "unconfirmed" either way.
- This only discovers sinks and attempts the **platform mirror**, which
  mirrors the phone screen. It does not put the XFCE desktop on the TV. An
  independent wireless desktop needs a WFD source encoder or a LEX receiver;
  that is future work.

## Integration snippets (exact, not applied)

### 1. AndroidManifest.xml — activities

Insert next to the existing `LexActivity` entry. `exported="false"` is
required because both are internal Operator screens. The `configChanges`
matter: opening the IME or changing display layout must not recreate the
activity, or the Presentation and RFB session would be torn down.

```xml
        <activity android:name=".LexMonitorActivity" android:exported="false"
            android:screenOrientation="landscape"
            android:configChanges="orientation|screenSize|screenLayout|keyboard|keyboardHidden" />
        <activity android:name=".LexWirelessActivity" android:exported="false"
            android:screenOrientation="portrait"
            android:configChanges="orientation|screenSize|screenLayout|keyboard|keyboardHidden" />
```

No new `uses-feature` is needed. The existing
`android.hardware.usb.host` feature is USB host mode, not DisplayPort alt
mode, and is unrelated to mode 2.

### 2. AndroidManifest.xml — permissions

The current manifest already declares `ACCESS_FINE_LOCATION` and
`ACCESS_COARSE_LOCATION` (lines 9–10). The minimal, behaviour-preserving
addition for Android 13+ is one line:

```xml
    <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />
```

If the whole app is moved to the Android 13 permission model, the location
lines can become:

```xml
    <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" android:maxSdkVersion="32" />
```

Do not apply the second form blindly: the rest of Operator (Wi-Fi scan,
audit, radio tools) also uses location, and must be re-checked on API 33+.
`LexWirelessActivity` works either way: it requests
`NEARBY_WIFI_DEVICES` on API 33+, `ACCESS_FINE_LOCATION` below 33, and if the
permission is not declared in the manifest it shows the integration message
instead of crashing or looping on a permission dialog. On API < 33 it also
warns when system location is off, which Android requires for Wi-Fi Direct
discovery.

`android:usesPermissionFlags="neverForLocation"` is deliberately not
recommended here: it is a location-privacy assertion for the whole app, and
Operator's Wi-Fi tooling may not satisfy it.

### 3. MainActivity.java — two buttons on the LEX page

Current method (unchanged):

```java
    private void lex() {
        heading("LEX", tr("Escritorio Kali XFCE en el móvil.", "Kali XFCE desktop on the phone."));
        button(tr("Abrir escritorio", "Open desktop"), () -> startActivity(new android.content.Intent(this, LexActivity.class)));
        button(tr("Detener escritorio", "Stop desktop"), this::stopLex);
        content.addView(text(tr("Se abre en horizontal y tarda unos segundos. Toca y arrastra para el ratón; el icono de teclado de noVNC abre el teclado. Volver cierra el visor y deja el escritorio activo.",
            "Opens in landscape and takes a few seconds. Tap and drag for the pointer; the noVNC keyboard button opens the keyboard. Back closes the viewer and leaves the desktop running."), 13, false, secondary));
    }
```

Add after the **Abrir escritorio** call, using the same `button(String,
Runnable)` helper:

```java
        button(tr("Monitor USB-C", "USB-C monitor"), () -> startActivity(new android.content.Intent(this, LexMonitorActivity.class)));
        button(tr("Pantalla inalámbrica", "Wireless display"), () -> startActivity(new android.content.Intent(this, LexWirelessActivity.class)));
```

Nothing else in `MainActivity` needs to change for the buttons. The
activities are launched directly, the same way mode 1 is.

### 4. Glyph.java — no changes

No icons were added, so there is no new `Glyph` case. The new screens use
text-only controls in the existing near-black/off-white palette (`#101112`
background, `#1D1F20` surface, `#EEEEEC` foreground, `#A4A7A9` secondary,
`#4F9BA9` accent for the armed Ctrl state). If icons are wanted later, this
would be a new case in `Glyph.onDraw`; `Glyph.java` was not edited.

## API-level guards

- `minSdkVersion` is 29. `DisplayManager`, `Presentation`, `WebView`,
  `WifiP2pManager` and `WifiP2pWfdInfo` all exist at 29.
- `NEARBY_WIFI_DEVICES` is only requested under
  `Build.VERSION.SDK_INT >= 33`; below that the activity requests
  `ACCESS_FINE_LOCATION`. `LocationManager.isLocationEnabled()` is API 28.
- Edge-to-edge insets and `WindowInsets.Type` are guarded by
  `SDK_INT >= 30`, matching `LexActivity`/`MainActivity`.
- `DisplayManager.connectWifiDisplay(String)` is not in the public SDK (it is
  a `@SystemApi` guarded by `CONFIGURE_WIFI_DISPLAY`). The code uses
  reflection and handles `NoSuchMethodException` (including hidden-API
  blocking), `SecurityException`, `InvocationTargetException` and other
  reflective/runtime failures.
- `WifiP2pWfdInfo.isSink()`/`isSource()` are not in the SDK 35 stubs; the code
  uses the public `getDeviceType()` constants.

## Validation performed

- Build: `python3 alpha/scripts/build_lambda.py` passed on 2026-11-23.
  Artifact `alpha/out/lambda-0.2.0-operator.24-dev.apk`,
  SHA-256 `5ddf9ccfc9f026c7e76af44d8614cdc80d616b60a4d78332c67e54c00db53a0f`,
  549,862 bytes, signed (v3) and zipaligned; `apksigner verify` and
  `zipalign -c 4` passed. The report still says
  `device_tested: false`. The APK was **not installed**.
  The existing build is not byte-reproducible: two consecutive builds from
  identical sources produced different `classes.dex` bytes (502,240 vs
  502,448 bytes) and different hashes, with the APK size unchanged. The hash
  above identifies this local artifact only, not a reproducible build.
- The three new classes are present in `classes.dex`
  (`LexMonitorActivity`, `LexWirelessActivity`, `LexRfbInput` and their inner
  classes), despite not being in the manifest.
- RFB/VncAuth host tests (throwaway harness outside the repository, using the
  compiled `LexRfbInput`):
  - Known-answer vector: password `01234567`, challenge `00..0f`, DES key
    `0c8c4ccc2cac6cec` (bit-reversed), expected ciphertext
    `e78701495117e1afd62fb5d65bb98b20` (computed independently with OpenSSL).
    Passed.
  - Mock RFB 3.8 server: version negotiation, VncAuth selection/response,
    `SecurityResult`, `ClientInit`, `ServerInit` (2280×1080), `SetEncodings`
    raw, then pointer press/release, wheel press/release and key down/up
    frames were byte-checked. Passed.
  - Mock RFB 3.3 server (4-byte security type, no `SecurityResult`).
    Passed.
  - Authentication failure: server sends result 1 plus
    `"Authentication failure"`; the client reports
    `vnc_auth_failed: Authentication failure` through `onError` and does not
    crash. Passed.
- No test was added to the repository; these were one-off host checks, since
  the delivery was limited to the new files above.

## Device checks after integration (2026-11-23)

- Manifest: both activities exported=false with the documented `configChanges`;
  `NEARBY_WIFI_DEVICES` declared and granted.
- MainActivity LEX page: **Monitor USB-C** and **Pantalla inalámbrica** buttons
  added.
- Mode 2 with no monitor: the S10 shows “Conecta un monitor USB-C”, the honest
  “not validated on hardware in this build” note, **Reintentar** and **Volver**.
- Mode 3: the runtime permission was granted; Wi-Fi Direct discovery ran and
  listed a real LG webOS TV advertising Wi-Fi Display. **Connect was not
  attempted** (it would act on the user's TV unattended); the guarded
  `connectWifiDisplay` path and native mirroring remain untested.
- Start/stop of the desktop, notification and viewer are unchanged by these
  activities.

## Hardware paths that remain untested

These are **not** validated and must not be recorded as working:

1. USB-C/DisplayPort output detection and hotplug on this S10 with
   LineageOS 22.2 (`dp_sec`, exynos DisplayPort). No monitor was available.
2. `Presentation` on the external display, including whether LineageOS
   exposes it as `DISPLAY_CATEGORY_PRESENTATION` with `FLAG_PRESENTATION`.
3. `WebView` rendering inside a `Presentation` on a secondary display. Some
   WebView builds only render on the default display; if this fails, the
   code reports the exception but has no fallback. The design fallback is a
   native `SurfaceView` RFB viewer (framebuffer decoding), which is not
   implemented here.
4. noVNC plus the separate RFB input client attached to the same Xvnc at the
   same time, and Xvnc's multi-client behaviour under TigerVNC on this
   rootfs.
5. The system IME forwarding text/backspace/Enter through the transparent
   `EditText` against a real XFCE session; hardware keyboards and IMEs that
   only emit `KeyEvent` for letters are not covered.
6. Real VncAuth against the chroot's Xvnc. The DES algorithm and framing are
   test-covered, but the chroot password file, the 8-character truncation
   and the websockify/noVNC URL were not exercised on the device.
7. Pointer mapping, drag behaviour, scroll direction and touch latency on
   the physical panel; performance and thermals on the external display
   (the design target is 1080p30, not measured).
8. Wi-Fi Direct discovery of WFD sinks on this ROM, including whether
   available sinks advertise `WfdInfo` at all.
9. `connectWifiDisplay` hidden-API access and `CONFIGURE_WIFI_DISPLAY` on a
   normal app; the expected result is a refusal, which the UI reports, but
   this was not observed. No Miracast sink was available.
10. Native Wi-Fi Display mirroring itself (AOSP `WifiDisplayController`),
    and the fact that it mirrors the phone screen rather than the XFCE
    desktop.
11. DisplayPort audio: not routed on LineageOS; not attempted here.

## Known limits

- The desktop is shared state. Mode 2 attaches to an existing desktop and
  does not stop it. If mode 2 started the desktop, it submits a stop on
  exit. If the activity process is killed abruptly, that stop may not be
  sent; the desktop remains up and visible in Operator's notification.
- Pausing the activity (home, lock, another app) dismisses the Presentation,
  so the monitor blanks while the activity is not resumed. The desktop keeps
  running and comes back on resume. This is intentional; a background
  presentation would need a foreground service and a display-owner policy.
- The RFB client is input-only by design. It cannot be a fallback viewer for
  the Presentation.
- Input is printable ASCII plus the listed control keys. Accented text,
  clipboard, Cmd/Super and multi-key chords other than sticky Ctrl are not
  supported.
- The absolute touch mapping is a deliberate choice for a 2280×1080 desktop
  on a roughly 1:1 phone surface. A relative trackpad would need gain and
  pointer state; it was not implemented.
- Mode 3 is a scaffold, not a working wireless desktop.
