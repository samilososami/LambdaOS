# Handshake capture: fresh menu entry

Date: 2026-10-31. Tested on the physical Samsung Galaxy S10 `beyond1lte`.
Scope: APK navigation fix, not a ROM/kernel flash or a change of radio backend.

## Problem and change

`HandshakeUi.open()` treated a saved terminal result as a session to resume.
Consequently, opening Handshake capture from Wi-Fi / Atacks displayed the old
result (including “Sin handshake completo”) instead of the antenna chooser.
This was reproduced on the device before changing the APK; the retained receipt
in that reproduction was a successful M1+M2 capture, showing the same routing bug.

- Menu entry now resumes only queued/running work.
- A terminal/missing saved job starts at antenna selection with cleared transient
  UI state. No historical Core record is deleted.
- Sessions still explicitly opens old results through `openJob()`.
- A completed survey releases its remaining monitor lease before starting fresh.
- An RPC failure does not discard the saved job or assume that it has finished.
- No palette, animation, packet parser, injection logic or radio binary changed.

## Physical regression

Executed `python3 alpha/testing/radio_ui_lifecycle_test.py` using UIAutomator
nodes from the actual screen. This run used only passive reception on the
authorized lab `2.4GHz_TEST`; no deauth was requested.

| Check | Observed result |
|---|---|
| Existing terminal receipt → menu | Antenna chooser, not old result |
| New internal survey | Completed; lab SSID/BSSID matched the current connection |
| Back/Home during capture | Same capture continued in Core |
| Menu while capture running | Resumed that capture; no duplicate capture |
| Explicit stop | Cancelled; adapter restoration confirmed |
| Recreated Activity with saved terminal job | Menu opened antenna chooser |
| Sessions → finished capture | Correct stopped-result screen remained available |
| Menu after viewing that history | Antenna chooser again |
| Stored result | Unchanged after navigation |
| Final radio state | Zero active jobs; worker available; Wi-Fi reconnected to lab |

Scan ID: `b061e142-f27a-4914-8014-35f27e5f776c`.
Capture ID: `866c611f-8824-444b-b527-4975f805a0a4`.
The passive capture ran for 23,857 ms and observed zero EAPOL frames. This was a
navigation/cancellation regression, not evidence of a newly captured handshake.

Machine-readable evidence: `alpha/out/operator-handshake/tests/ui-menu-reentry.json`.
Screenshots: `41-menu-reentry-before.png` through `48-menu-reentry-final.png` in
`alpha/out/operator-handshake/`. Before/after and final chooser were visually
inspected. The old `ui-lifecycle-polish.json` evidence was not overwritten.

## Installed build

- APK: `alpha/out/operator-handshake/Lambda-operator2.apk`.
- SHA-256: `0942199deb79642ae275d37352505b7f42caa70b43fa5ea3327418b6dca3dedb`.
- Installed `base.apk` SHA-256 matched the local signed artifact.
- Existing Lambda signing certificate retained; installation used `adb install -r`.
- Rollback APK: `alpha/out/operator-handshake/Lambda-operator2-before-menu-reentry.apk`.
- No app-data wipe, system reboot, factory reset or ROM flash.
- ADB restored to shell UID 2000; SELinux remained Enforcing.

## Current radio tools / separate future decision

The flow still uses Nexmon/nexutil for the internal monitor interface, tcpdump
for packet collection, the local `WifiFrames` parser for AP/EAPOL interpretation,
and aireplay-ng for bounded deauth. Neither airodump-ng nor mdk4 was added here.
Structural M1+M2 detection is not a cryptographic password/MIC validation.

Upstream references reviewed for the user's backend question:

- [airodump-ng](https://www.aircrack-ng.org/doku.php?id=airodump-ng): packet capture,
  AP/client observations and capture/CSV outputs.
- [aireplay-ng](https://www.aircrack-ng.org/doku.php?id=aireplay-ng): injection,
  including deauthentication used for handshake collection.
- [mdk4](https://github.com/aircrack-ng/mdk4): additional wireless test modes.

Recommendation, not an implemented migration: evaluate airodump-ng for richer
capture/survey data while retaining the working fallback, keep aireplay-ng for
the current capture-assist action, and evaluate mdk4 separately if its additional
test modes are required. Linux upstream support alone does not verify Android
ARM64/Nexmon compatibility; validate the actual S10 before changing the backend.
