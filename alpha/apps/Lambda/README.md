# Lambda Operator · Core and offline cracking

System application: `org.lambdaproject.lambda` · `0.2.0-operator.5` (25).
Reference device: Galaxy S10 `beyond1lte`, LambdaOS Alpha 2 / Android 15.

## AP / evil twin · 2026-11-22

The Wi-Fi AP tab now runs the authorized `radio.ap` clone workflow
(hostapd + local DHCP/DNS, optional bounded sniffing). Delivered and verified
state, limits and pending device tests:
[`../../docs/operator-ap-2026-11-22.md`](../../docs/operator-ap-2026-11-22.md).

## Offline cracking integration · 2026-10-31

The S10 development installation now has two offline dictionary paths:

- After a detected handshake and restoration of normal radio state, the capture
  worker checkpoints the capture and runs the 4,800-entry CPU SHA-optimized
  Aircrack check. Failure or cancellation of that check preserves the capture.
- The dedicated screen accepts that saved capture, a bundled 203,109-entry
  dictionary or a user-imported `.txt`, and a CPU/hybrid/automatic engine choice.
  Automatic currently selects CPU below 24,000 entries and CPU + GPU otherwise.
  This threshold is a practical policy, not a measured optimum for every list.

The hybrid worker computes PMKs on CPU and Mali GPU in parallel. A separate
modified Aircrack build uses the PMK cache for handshake verification. Any found
candidate is checked again by the original Aircrack binary before being marked
verified. Hashcat is not used by this workflow. Dedicated PMKID-file import is
not implemented; the existing synthetic benchmark also covers PMKID.

The UI supports actual completed-batch progress, end-to-end keys/s, battery
temperature, cancellation and history. Verified passwords are displayed directly
in both result screens. Imports are limited to
printable ASCII passwords of 8–15 bytes, 64 MiB input and four million entries.
Cancellation retains the receipt and capture; a new check starts at the beginning.
There is no arbitrary shell or caller-supplied filesystem path in `crack.wpa`.

On the S10, the median complete quick check was 1.351 s. For a 96,000-entry
negative fixture, the hybrid median was 5,797 keys/s versus 3,750 on CPU. The
approximately 10,000 PMKs/s compute-only result is **not** full-workflow throughput.
See [integration report](../../docs/cracking-integration-2026-10-31.md) for methods,
evidence, thermal limits and untested paths.

**Deployment boundary:** the APK is installed over the existing system app. The
privileged radio/cracking worker and native tools are separately provisioned for
development under `/data/local/tmp`; that worker does not survive a phone reboot.
Core's boot receiver does not make this root worker persistent. No ROM was packed
or flashed for this integration. The saved-capture/UI paths were tested offline;
the newly wired automatic post-capture chain still needs a fresh on-device capture
acceptance pass. No new radio transmission was made by these cracking tests.

## Architecture

The native Activity is an IPC client. `CoreService` and `CoreProvider` live in
the separate `:core` process. A foreground service owns a serial, bounded work
queue; closing the screen does not cancel it. Idle Core performs no radio scans,
polling loop or held wake lock. The notification exposes a stop-tasks action.

The boot/package-update receiver starts Core after it has been enabled by opening
Operator. It is not direct-boot aware: encrypted audit history is opened after
the Android user unlocks. Core's original Android-API operations do not depend on
a helper launched from ADB; the development privileged extensions described above do.

SQLite stores request IDs, arguments, caller UID, timestamps, states and results.
Queued/running work found after a process restart becomes `interrupted`, never
silently replayed. Completed receipts are retained; the UI lists the latest 200.
New work is refused above 50 MiB of database storage instead of silently deleting
reports. Up to eight pending/running jobs are accepted, executed one at a time.
Original diagnostic radio/watch durations are limited to 3–30 seconds; GATT to
20 seconds, fresh Wi-Fi to 18 seconds. Those wake locks have a 65-second backstop.
Dedicated cracking has a one-hour session limit and a 61.5-minute wake-lock
backstop, released in `finally`. Native children run as shell UID 2000 with
thermal, memory, timeout and cancellation guards; thermal protections remain on.

This remains **not the entire C2 privileged broker**: GUI and Core have different
processes but the same Android application UID. No persistent custom root daemon
or new SELinux domain is installed. The allowlisted development root worker is
authenticated separately. SELinux remains enforcing. Lamb remains paused and has
not been granted access by this change. Cracking holds an exclusive Core lease;
it does not freeze unrelated system apps or disable thermal management.

### Permissions and background work

Wi-Fi observations require precise location permission and Android's Location
switch, even though Core does not request GPS fixes. Running those operations
without the Activity also requires `ACCESS_BACKGROUND_LOCATION` ("Allow all the
time"). Operator's settings explain this and link to the app permission screen;
the background grant is not bundled into the first foreground permission dialog.
An AppOps check rejects disallowed access as `permission_denied` rather than
presenting an empty cache as a successful observation. Bluetooth uses the
separate Nearby Devices grants; USB access remains per peripheral.

The S10 test installation has the background Wi-Fi permission granted. Core
does not scan automatically at boot: boot starts the coordinator, while each
radio operation still needs an explicit request. Android scan throttling remains
in effect and cached results are identified as such.

## Operations

| Name | Arguments | Result / limitation |
|---|---|---|
| `wifi.scan` | none | Scan broadcast plus observation timestamps; reports cache fallback as partial |
| `wifi.cached` | none | Existing Android observations, no new scan request |
| `wifi.audit` | `snapshot_id` | Open/legacy/TKIP/WPS advertisements and mixed-security SSIDs |
| `wifi.channels` | `snapshot_id` | BSSID counts by primary channel, not airtime/spectrum utilization |
| `wifi.link` | none | Existing link RSSI, link speed, IP, DNS, routes, validation state |
| `wifi.signal` | optional `seconds` | RSSI samples; stops if connected BSSID changes |
| `bluetooth.status` | none | Native adapter/PHY capabilities |
| `bluetooth.ble_scan` | optional `seconds` | Bounded discovery of advertisements, UUIDs, manufacturer IDs, RSSI |
| `bluetooth.classic_scan` | optional `seconds` | Active discovery, without pairing |
| `bluetooth.bonded` | none | Local bonds and cached UUIDs |
| `bluetooth.audit` | `snapshot_id` | Observed name/connectability exposure, not vulnerability detection |
| `bluetooth.gatt` | `snapshot_id`, `address` | Connect to an explicitly selected, connectable device; enumerate services only |
| `usb.inventory` | none | Host-mode peripherals, VID/PID, classes, interfaces and endpoints |
| `usb.audit` | none | HID/storage/composite classes and the local ADB setting |
| `usb.watch` | optional `seconds` | Attach/detach events plus before/after inventory |
| `usb.descriptors` | `snapshot_id`, `device` | Cached standard descriptors; Android's per-device USB permission required |
| `system.capabilities` | none | Local device/kernel/network interface inventory |

GATT and USB descriptor targets must belong to a compatible snapshot less than
five minutes old. The UI asks before a GATT connection. This version performs
no characteristic reads/writes/subscriptions and no USB control/bulk/HID writes.
Targeted hardware paths still require testing with an authorized peripheral.

The table above describes the original Android-API diagnostic operations, not
the later `radio.*`, `benchmark.wpa` and `crack.wpa` extensions. Their native
adapters, authorization and device evidence are documented separately. No HID
payload execution, Bluetooth flooding or RF exploit is implied by this table.

## IPC and CLI

Authority: `content://org.lambdaproject.lambda.core`

`crack.wpa` takes `capture_id`, `dictionary_id`, `authorized: true` and optional
`engine: auto|cpu|hybrid`. `crack_dictionaries` lists bundled/imported dictionaries.
Only a compatible authorized saved-capture receipt (or the pinned synthetic test
receipt) is accepted. Starting a dedicated job requires an explicit target
confirmation in the UI; the automatic quick check is disclosed in capture consent.

`ContentResolver.call(uri, method, requestJson, null)` returns a Bundle containing
the `json` field. Methods: `status`, `catalog`, `submit`, `list`, `get`, `cancel`.
`catalog` describes the argument schemas and effects. Unknown tools/fields,
incorrect types and out-of-range values are rejected before adapter execution.

The provider derives identity from Binder, never from JSON. It accepts only the
same full UID or root/ADB shell for the primary Android user. Another APK,
including one with the same signing certificate but a different UID, is not a
trusted client. Future Lamb/third-party clients require a separately reviewed
authorization extension. CRUD/file access is not exposed; there are no URI grants.

```json
{"tool":"wifi.scan","args":{},"request_id":"survey-001","hidden":false}
```

The same request ID, caller, tool, arguments and visibility returns the stored
receipt. Reuse with a different request is rejected. `hidden` changes listing
visibility only; the durable audit receipt remains. Explicit
`list {"include_hidden":true}` includes it.

The ROM build includes `/product/bin/lambda` through `common.mk` / `Android.bp`.
For the current APK-only update, a test copy is at `/data/local/tmp/lambda`:

```sh
adb shell /data/local/tmp/lambda status
adb shell /data/local/tmp/lambda catalog
adb shell '/data/local/tmp/lambda run wifi.scan "{}" --id survey-001'
adb shell /data/local/tmp/lambda get survey-001
adb shell '/data/local/tmp/lambda run usb.inventory "{}" --hide --id usb-001'
```

Ordinary untrusted Android terminal apps are not automatically privileged. Use
an authenticated ADB shell or an explicitly authorized root shell. This is not a
network HTTP service or an arbitrary-shell executor.

## Build, signing and ROM staging

```sh
set -e
python3 alpha/scripts/build_lambda.py
node alpha/scripts/sign_operator.mjs \
  alpha/out/lambda-0.2.0-operator.2-dev.apk \
  alpha/out/cracking-speed/Lambda-operator-benchmark.apk
adb install --no-incremental -r \
  alpha/out/cracking-speed/Lambda-operator-benchmark.apk
```

The build/sign scripts work when invoked as `kali` or root; privileged invocations
drop to `kali` before producing artifacts. Signing preserves the existing
`lambdaos-release-v1/releasekey` identity. Private key material stays outside
source and uses a transient tmpfs file during signing. No platform-key migration,
factory reset, ROM flash or firmware change is needed for the tested APK update.

`stage_source_tree.sh` already copies this source directory into
`packages/apps/Lambda`. The next ROM build also includes the CLI. The current
installed update is in `/data/app` over the existing system package; a factory
reset before flashing that next ROM would restore the old bundled APK.

The cracking development installer is `alpha/scripts/install_cracking_bench_dev.sh`.
It expects the built CPU/research dependencies and prepared fixtures, plus outputs
from `prepare_dedicated_dictionary.py` and `build_cracking_hybrid.py`. Run it only
with Core idle. It restarts the development worker without rebooting the phone
and restores ADB to shell UID 2000. Staging the APK source alone is insufficient
to ship the separate native tools/root-worker lifecycle in a ROM.

## Connecting after a verified result

Both automatic and dedicated positive results expose **Conectar / Connect**.
The action submits `wifi.connect_verified` with only a `result_id`. Core resolves
the SSID, BSSID and verified password from private receipts, rejects synthetic
fixtures/unverified results and prevents caller-supplied credential substitution.
The privileged worker restores monitor mode if necessary, enables normal Wi-Fi
and asks Android to save/connect the WPA-PSK network pinned to the captured BSSID.
CLI acceptance alone is not success: the primary Wi-Fi association must match
that SSID/BSSID, report `COMPLETED` and have a local IP address. This does not
claim internet access. Connection receipts contain no password.

The UI shows Connecting, Connected or a retryable failure. A second tap cannot
submit duplicate work. The bounded connection attempt runs independently of the
screen; navigation does not trigger capture, deauth or another cracking pass.
An unavailable AP or a changed password can fail despite a historically valid
capture. This action has the same development-worker-until-reboot limitation as
the other privileged extensions; it is not yet a ROM-persistent service.

Real S10 acceptance: `alpha/testing/test_verified_wifi_connect.py` toggles only
the phone's Wi-Fi and checks both buttons against the existing authorized
`2.4GHz_TEST` receipts. It does not perform capture or deauthentication.

## Verification and source references

See [validation report](../../OPERATOR-V1-VALIDATION.md). Device-test results are
kept separately from fixture/IPC isolation tests and untested hardware paths.
Raw local JSON reports contain nearby radio identifiers: do not publish them
without redaction.

- [Android Wi-Fi scanning](https://developer.android.com/develop/connectivity/wifi/wifi-scan): permission, throttling and cache semantics.
- [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions): scan and connection runtime permissions.
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types): connected-device service declaration and prerequisites.
- [AppOpsManager](https://developer.android.com/reference/android/app/AppOpsManager): effective operation permission checks in addition to the runtime grant.
