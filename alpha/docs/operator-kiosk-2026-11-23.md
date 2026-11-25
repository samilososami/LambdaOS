# Operator kiosk, RadioWarden and UCM tweaks — 2026-11-23

Status: implemented, installed and device-tested as `0.2.0-operator.21`
(versionCode 41) on the S10, release-signed with `lambdaos-release-v1`
(`dd4c8fe7…`). Device owner is development provisioning, not ROM integration.

## What changed

- **RadioWorker → RadioWarden**: the root maintenance class is now
  `org.lambdaproject.lambda.RadioWarden` (file, scripts, tests, docs). The
  worker lock, journal and recovery are unchanged; the start command and the
  ContentProvider attribution tag (`LambdaRadioWarden`) were updated.
- **Real kiosk for UCM/SUCM/FDT/monitor**: new `LambdaDeviceAdmin`
  (`BIND_DEVICE_ADMIN`, `@xml/device_admin`). `CoolingActivity` calls
  `setLockTaskPackages` + `startLockTask` when Lambda is device owner or the
  package is allowlisted, and `exitKiosk()` runs before every finish/handoff.
- **Bug found while testing**: `finish()` while the task was locked froze the
  screen (our `finishing` flag was set but the system did not remove the
  activity) and `am force-stop` was ignored while locked. `finishNow()` now
  stops lock task first and then finishes; a root `kill -9` of the UI process
  also releases the mode.
- **UCM/Smart blocked while an AP runs**: before starting cooling the UI
  queries `list`; if a `radio.ap` job is queued/running it shows “AP activo”
  and asks to stop the AP first. The root worker handles one job at a time, so
  cooling cannot run concurrently with an AP; this is a UI block, not a new
  Core policy.
- **Monitor rate window**: Temperature Monitor needs ≥8 s of samples before
  showing a rate; before that it shows “Calculando velocidad…”. The
  battery/body line remains the slow reference.

## Device provisioning / removal

- Set (no accounts, single user):
  `adb shell dpm set-device-owner org.lambdaproject.lambda/.LambdaDeviceAdmin`
- Remove (an admin set through adb is test-only):
  `adb shell dpm remove-active-admin org.lambdaproject.lambda/.LambdaDeviceAdmin`
- The ROM must provision its own device owner during setup; this dev command
  is not a substitute.

## Verification (S10, 2026-11-23)

- Build/signing: `lambda-0.2.0-operator.21-dev.apk`;
  `out/operator-ap/lambda-0.2.0-operator.21-release.apk`; certificate
  `dd4c8fe7…`.
- Contract suite: 37/37 rejections pass.
- `status` reports `0.2.0-operator.21`; `radio_status.available=true`; the
  worker PID command line is `org.lambdaproject.lambda.RadioWarden`.
- Kiosk: `mLockTaskModeState=LOCKED`; `input keyevent 3` (HOME) did not leave
  `CoolingActivity`; tapping **Cerrar** returned to Operator with
  `mLockTaskModeState=NONE`.
- AP warning: manual open AP `kiosktest` (channel 6) running; tapping
  **Ultra Cooling** showed the “AP activo” dialog; dismissing it created no
  `system.cooling` job, the AP was cancelled and `wifi_on=1`.
- Monitor: 2 s after launch it shows “Calculando velocidad…”; later it shows
  the rate over the ≥8 s window (for example 2.2 °C/min at 43 s).

## Limits

- Lock task blocks the Home gesture (verified); the Recents and power menu
  behaviour was not tested. Power off/restart remains a deliberate exit.
- Root ADB can kill the UI process and release the mode; this protects against
  a stuck kiosk but is not a user-facing escape.
- Device owner is provisioned only on this development S10. Without it the
  screen keeps the previous immersive, back-blocked behaviour.
- `system.cooling` can still run concurrently with an AP job; the warning is
  UI-only, and a Core exclusivity policy is not implemented.
