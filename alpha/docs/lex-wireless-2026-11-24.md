# LEX wireless display: LAN discovery and what this build can send

Date: 2026-11-24
Build: LambdaOS `0.2.0-operator.29` (versionCode 49)

## Reported problem

The LEX “Pantalla inalámbrica” screen did not list the user's Sony KD TV or the
NVIDIA Shield, even though both are on the same network as the S10.

## Findings

1. **The scan only spoke Wi-Fi Direct.** `LexWirelessActivity` filtered
   `WifiP2pManager` peers by `WifiP2pWfdInfo` and nothing else. A device only
   appears there when it advertises itself as a Wi-Fi Display (Miracast) sink.

2. **Those two devices do not advertise Miracast.**
   - The Shield is a Chromecast built-in receiver; it has no Miracast sink.
   - The Sony KD-43X81K is a 2022 Google TV. Sony's support pages say Google TV
     models and Android TV models after 2020 do not include Screen mirroring;
     the model itself answered only `_googlecast._tcp` and `_airplay._tcp`.

3. **Both answer mDNS on the LAN.** A host-side scan found
   `_googlecast._tcp` and `_airplay._tcp` for the Sony, `_googlecast._tcp` for
   the Shield, plus SSDP root devices for both. This is why a LAN discovery path
   is required.

4. **The system's own cast picker was empty because the feature was off.**
   `dumpsys display` showed `WifiDisplayStatus{... mWifiDisplayOnSetting=false}`
   and `mAvailableWifiDisplayPeers: size=2`, and `settings get global
   wifi_display_on` returned `0`. The system's `WifiDisplayAdapter` saw the
   sinks but refused to publish them. After setting it to `1`, the Cast screen
   listed both LG TVs with `canConnect true`.

5. **The app cannot start a Wi-Fi Display session itself.** It is installed as
   an updated system app but is not signed with the platform key (app cert
   `c33691fd…`, platform `cc3c37e3…`), so it does not hold
   `CONFIGURE_WIFI_DISPLAY`. The direct `connectWifiDisplay` attempt is refused
   by `DisplayManagerService.connectWifiDisplay` and the activity falls back to
   the system picker, which is the supported route for a normal app.

## Changes

`alpha/apps/Lambda/src/org/lambdaproject/lambda/LexWirelessActivity.java`

- `NsdManager` discovery for `_googlecast._tcp` and `_airplay._tcp`, with a
  serialized resolve queue and a name shown immediately while the address
  resolves.
- One merged list: Wi-Fi Direct peers (`Miracast`), Chromecast and AirPlay
  devices (`Chromecast · LAN`, `AirPlay · LAN`). IPv6 link-local addresses are
  hidden; the protocol label is the useful part.
- Wi-Fi Direct entries now carry the `Miracast · <status>` label, so the two
  sources are distinguishable at a glance.
- Selecting a Miracast sink tries the direct call, then opens the system cast
  picker. If `wifi_display_on` is `0`, the status line tells the user to open
  the ⋮ menu and enable “Enable wireless display” before choosing the TV.
- Selecting a Chromecast/AirPlay device explains that this build has no sender
  for it and opens the same picker, which will only list Miracast sinks.
- NSD discovery restarts cleanly on “Buscar de nuevo” (old listeners stopped,
  queue and kind map cleared), and all NSD callbacks are marshalled to the main
  thread.

## Observed on the S10 (Android 15, `2.4GHz_TEST`)

`uiautomator dump` of the activity after ~9 s:

```
Wi-Fi Direct 2 · LAN 5 · buscando…
BRAVIA 4K VH21            Chromecast · LAN
SONY KD 43X81K            AirPlay · LAN
SHIELD Android TV         Chromecast · LAN
AudioPro C10 MkII         AirPlay · LAN
ADDON C10 MkII Speak      Chromecast · LAN
[LG] webOS TV OLED55B56LA  Miracast · disponible
[LG] webOS TV UP78006LB    Miracast · disponible
```

After enabling the global toggle, `adb shell am start -a
android.settings.CAST_SETTINGS` listed both LG TVs, and `dumpsys display`
reported:

```
featureState=3, scanState=1, activeDisplayState=0,
displays=[[LG] webOS TV OLED55B56LA …, isAvailable true, canConnect true,
          [LG] webOS TV UP78006LB …, isAvailable true, canConnect true]
```

Device state changed on the S10 for this validation:
`settings put global wifi_display_on 1`. It is the user-facing “wireless
display” toggle and is reversible with `settings put global wifi_display_on 0`.

## Why there is no DeX-like extended display without a receiver

- **DeX itself is Samsung firmware.** On a LineageOS base there is no DeX
  service to call; wired DeX also needs a display that speaks USB-C DP alt mode,
  and wireless DeX talks to a Samsung-controlled sink.
- **Miracast mirrors the default display.** The AOSP Wi-Fi Display source
  presents the default display to the sink; an extended second display is not a
  standard Miracast sink feature. A sink must also advertise itself; the Shield
  never does, and Sony's Google TV models dropped the feature.
- **Chromecast mirroring is Cast Streaming.** The reference implementation is
  Google's open-source `openscreen`/libcast: TLS Cast channel, RTP/RTCP,
  congestion control, AES and a receiver app. It can be integrated into
  Android with the NDK, but it is a C++ project of its own, and this build has
  no Google Play Services Cast SDK.
- **AirPlay mirroring needs a sender.** The FairPlay handshake and the H.264
  mirroring session are proprietary in Apple's stack, but open-source Android
  senders exist (Kite/doubletake, LGPL-3.0); integrating one is a project, not a
  drop-in library.
- **DLNA/DIAL move media, not a display.** They can push a URL to a renderer but
  cannot carry a live screen.

Practical options for a DeX-like result, in order of effort:

1. Wired: USB-C → HDMI/DP and `LexMonitorActivity` (already implemented as a
   `Presentation`); this is the closest thing to extended DeX on this ROM.
2. Miracast mirror to a sink that advertises (the LG TVs here); mirrored, not
   extended, and the system picker performs the connection.
3. Install a receiver app on the TV (VNC, spacedesk, etc.) — the user explicitly
   does not want a LEX receiver on the TV, and this note records the trade-off.
4. A Cast Streaming sender based on `openscreen` (months of work, not started).

## ROM-level path (planned, not implemented)

The app is a normal app; the ROM is not. With ROM changes the direct path opens:

- **Platform signature + `CONFIGURE_WIFI_DISPLAY`**: sign Operator with the
  platform key. `DisplayManager.connectWifiDisplay` would connect directly to
  Miracast sinks (the LG TVs). It does not help the Sony, which does not
  advertise Wi-Fi Display.
- **AirPlay sender for the Sony**: the KD-43X81K advertises `_airplay._tcp`
  (AirPlay 2). The shortest open-source reference is
  [`itsHenry35/Kite`](https://github.com/itsHenry35/Kite) (LGPL-3.0, Android
  10+, MediaProjection + H.264, Go core `doubletake`), with
  [`jqssun/android-display-mirror`](https://github.com/jqssun/android-display-mirror)
  as an alternative on the same base. This is a real sender, not a receiver.
- **Cast sender for the Sony/Shield**: `openscreen`/libcast (Apache-2.0) with
  MediaProjection and H.264. More work: Cast LAUNCH, the WebRTC namespace,
  RTP/RTCP, AES and congestion control, plus an NDK build. No Google Play
  Services required.
- **Effort**: weeks, not a patch. Licensing: Kite is LGPL-3.0 (link it
  dynamically or run it out of process); openscreen is Apache-2.0.

Until that exists, the Sony row is honest: it is detected over AirPlay, the
button says there is no sender in this build, and tapping it explains why
instead of opening a settings screen that cannot help.

## Not tested

- An actual Wi-Fi Display session (tapping a TV in the system picker). It would
  take over the TV screen, so it waits for the user's go-ahead.
- Sending a screen to the Sony or the Shield. There is no sender path on this
  build; the screen lists them so the limitation is visible instead of silent.

## Sources

- Sony, “How to use the Screen mirroring function on my BRAVIA TV”:
  <https://sony.co.in/electronics/support/articles/00044548>
- Sony support article 00226734 (models without Screen mirroring):
  <https://sony.com.sg/electronics/support/articles/00226734>
- OpenScreen / libcast (Cast Streaming):
  <https://github.com/openscreenproject/openscreen>
- Kite, open-source Android AirPlay sender:
  <https://github.com/itsHenry35/Kite>
- android-display-mirror (AirPlay path on doubletake):
  <https://github.com/jqssun/android-display-mirror>
