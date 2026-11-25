# Lambda Operator 2 — internal-radio implementation and QA

**Later update, 2026-10-31:** the user-requested inline Deauth button is now
implemented and real M1/M2 pairs have been observed on the own lab. Current APK
hash, final native workflow results and visual evidence are in
[the inline-deauth QA addendum](operator-inline-deauth-qa-2026-10-31.md). The
measurements and pending observations below describe the earlier overnight pass.

Date: 2026-10-31. Device: Samsung Galaxy S10 / beyond1lte, Android 15.
Installed APK: **0.2.0-operator.2**, versionCode **21**, signed with the existing
Lambda release certificate. No ROM, kernel or firmware was flashed in this pass.
No factory reset or user-data wipe was performed.

## Artifact

- APK: `alpha/out/operator-handshake/Lambda-operator2.apk`
- SHA-256: `513b73ff51a791185e6d4d963b67f45cc3629c4e5723b8e69e830ed586de12cc`
- Existing pre-change application sources: `alpha/out/operator-handshake/checkpoint/`.
- Build/install procedure: `bash alpha/scripts/install_operator_radio_dev.sh`.
- Java APK and native-helper build scripts work as kali and as root; root builds
  drop to kali for workspace ownership. The Android privileged worker itself
  intentionally requires root; the UI runs as an ordinary application UID.

## Implemented flow

Wi-Fi now has **Explorar / AP / Atacks**. The user-requested spelling “Atacks” is
preserved. AP is a clearly labelled future section, not a simulated working AP.
Existing Wi-Fi exploration, Bluetooth and USB functions remain available.

Atacks contains **Handshake capture** and a separate **Deauth** action:

1. Choose internal S10 or external USB adapter.
2. Internal: explain the temporary pause of normal Wi-Fi, activate Nexmon monitor
   mode, then survey allowed **2.4 GHz** channels automatically.
3. External: animated S10/USB insertion diagram and real adapter inventory. Already
   connected adapters are included; only supported, driver-bound interfaces can
   be selected. USB re-enumeration has an eight-second stability check.
4. Select a real observed BSSID, then explicitly confirm authorization for it.
5. Passive capture observes only that BSSID's beacon/EAPOL traffic, for up to three
   minutes. It never silently adds deauth or password guessing.
6. Deauth runs one aireplay cycle, with a ten-second limit and no automatic retry.
   A successful tool exit/report is **not** labelled proof of client disconnection.
7. Stop, timeout and ordinary failure restore the radio. Unused network selections
   expire after two minutes. Sessions can reopen running and completed jobs.
8. Export the retained PCAP through Android's document picker.

## Privileged execution and limits

The APK does not give itself root. An explicitly provisioned **development
worker** uses a closed Binder interface. There is no generic shell, network
listener, arbitrary executable, output path or deauth-count parameter exposed to
the application. Other app UIDs cannot call Core; authenticated owner ADB is
permitted, but only UID 0 may register/report as the radio worker.

The worker is isolated from adbd's process group, so returning ADB to UID 2000 no
longer kills it. It holds an exclusive lock and writes a private restoration
journal before changing radio state. A replacement worker restores the journal
and does not replay previously claimed operations automatically.

`radio-guard` bounds native subprocesses and removes them on timeout, cancellation
or parent death. SELinux remains **Enforcing**. No system partition or SELinux
policy was modified. An abrupt SIGKILL of the privileged worker still requires
restarting it to restore the journal; the native subprocess guard prevents an
orphan capture/injection tool but is not itself a Wi-Fi restoration daemon.

**This worker does not autostart after a phone reboot.** Persistent production
integration belongs in the next ROM with a dedicated init service and reviewed
SELinux policy. It is intentionally not disguised as an APK-only root solution.

## Real device results

Active tests were restricted to the user's **2.4GHz_TEST**, matched to the BSSID of
the S10's pre-existing connection and then re-observed in each fresh survey.
Other nearby networks were only passively observed, never selected for active tests.

| Check | Observed result | Evidence under `alpha/out/operator-handshake/tests/` |
|---|---|---|
| Internal survey | 31 networks in 23.522 s in the final deauth run; own lab network found on channel 6 | `deauth-final.json` |
| Bounded deauth | Tool reported one broadcast deauth cycle; exit 0; 1.864 s including worker cleanup; Wi-Fi restored | `deauth-final.json` |
| Client disconnection | **Not independently verified**; `effect_verified=false` | `deauth-final.json` |
| Full passive capture window | 180.783 s; 0 EAPOL frames; timeout reported honestly; Wi-Fi restored | `capture-timeout-final.json` |
| Capture cancellation | 10.933 s capture in the explicit ten-second cancellation test; state `cancelled`; Wi-Fi restored | `capture-cancel-final.json` |
| Survey cancellation | Stopped during activation/survey; state `cancelled`; duration 5.586 s; Wi-Fi restored | `lifecycle.json` |
| Unused selection | Automatically restored normal Wi-Fi after the 120 s lease | `lifecycle.json` |
| Expired session | Rejected with `scan_session_expired`, no capture started | `lifecycle.json` |
| Missing external adapter | Rejected without changing the restored Wi-Fi state | `lifecycle.json` |
| Document export | Native picker saved a readable 439-byte radiotap PCAP containing the lab beacon, not a handshake | `ui-export.pcap` |
| Contract validation | 12 invalid requests rejected before queueing (unknown command/field, invalid/broadcast/zero address, false/string consent, arbitrary path, etc.) | `contracts-final.json` |
| Final APK smoke test | Fresh survey found 24 networks; capture cancelled after 4.554 s; Wi-Fi restored; normal ADB shell | `capture-release-smoke.json` |
| Worker impersonation from ADB shell | UID 2000 denied with `root_worker_only`; legitimate worker remained available | live RPC check |
| Native helper deadline/cancel | Exit 124 on deadline, 143 on cancellation, no surviving tcpdump | `guard.txt` |
| Native helper parent death | Tagged test parent killed; no live orphan guard/tcpdump | `parent-death.json` |

The complete three-minute capture was performed after returning ADB to the normal
shell. Backgrounding the UI and reopening through Sessions did not stop it.

The packet parser passes **10,158 synthetic checks**, including 10,000 malformed
random packets plus explicit EAPOL, truncation, replay/client mismatch, stale-pair,
zero nonce/MIC, invalid BSSID, link-type, bad-FCS, fragmentation, descriptor and
key-data-length cases. This is not 10,158 hardware tests. The same synthetic suite
also passes under Android's runtime on the S10 (`tests/parser-s10.txt`).
“M1 + M2 coincidentes” requires a same-client, same-replay pair within five seconds,
with nonzero distinct nonces and a nonzero MIC, and both frames retained in the
export. It is a **structural** check, not cryptographic password/MIC verification.

### Bugs actually found and corrected

- Nexmon accepted an unsuitable channel argument without tuning correctly:
  replaced it with the proper encoded 20 MHz 2.4 GHz chanspec and read-back check.
- The supplied wireless-tools helpers were Linux/glibc binaries, not Android:
  rebuilt actual iwpriv/iwconfig with the installed NDK, isolated them in a
  root-private directory and made a hash-pinned helper-path relocation in the
  retained Nexmon aireplay executable. No dummy tools or fake success statuses.
- `setsid`/`nohup` alone did not survive adbd's Android cgroup teardown. A real
  failed run is retained as `ui-capture-adb-restart-failure.json`; the replacement
  group was subsequently validated by the full capture with ADB unrooted.
- Live job progress previously came from stale SQLite data; it now uses the live
  job and a separate safely published progress snapshot.
- Fixed duplicate polling, cancellation status, stale selection UI, restoring a
  screen after configuration changes, footer selection, and a stale version label.
- Improved the handshake glyph, antenna drawing, centred card text, USB cable
  endpoint, linear looping and pausing animations when the window is hidden.

## Visual verification / fidelity ledger

Concept: `alpha/out/operator-handshake/design/flow-concept.png`.
Native captures: `06-choice-refined.png`, `09-capture.png`, `11-usb-final.png`,
`12-text130.png`, `13-atacks-final.png`, `14-external-final.png` in the same output
directory. Latest release screenshots are additionally saved as `15-release.png`
and `16-release-atacks.png`.

Browser/IAB was **not applicable**: this is native Android, not a browser app.
Screenshots were taken through ADB on the actual **1440 × 3040 / 560 dpi** S10;
accessibility-tree inspection, real taps and direct `view_image` comparisons were
used. The concept and rendered UI were inspected together, not merely compiled.

| Comparison point | Finding / fix / intentional deviation |
|---|---|
| Copy and navigation | Exact Explorar / AP / Atacks, Handshake capture and Interna / Externa preserved. Deauth is the later explicit user addition. |
| Hierarchy and spacing | Open dark layout, side-by-side equal-width antenna choices, consistent gutters; no clipped app header or primary actions in checked states. |
| Typography | Existing native sans-serif family, medium actions, centred choice labels; real device checked at 100% and 130% text scale. |
| Palette | Existing #101112 / #1D1F20 / #EEEEEC / #A4A7A9 tokens; no glassmorphism or new accent colours. |
| Icons and imagery | ISC Lucide handshake replaces the rough first drawing. S10 camera, USB-C plug and antenna remain scalable monochrome native line art. Cable now joins the connector correctly. |
| Motion | 150 ms page transitions, 2.4 s insertion/capture cycles, no simulated percentage; loops stop offscreen and respect disabled animations. |
| Real-data states | No fake adapter in the waiting screen, no sample networks, no fake handshake. Explanatory timeout/error/expiry copy is a functional addition to the concept. |
| Existing app shell | Existing Lambda header and three-item footer retained rather than replacing them with the concept's illustrative two-item footer. Deliberate integration difference. |
| Native file dialog | Android document picker intentionally retained; its layout/orientation is system-controlled, not part of the Operator concept. |

This verifies the implemented visual direction and flow against the concept with
the listed deliberate differences; it is not a claim that a generated mockup and
native Android widgets are pixel-identical. No material clipping/overlap was found
in the checked app screens. Physical external-adapter states remain pending.

### Motion measurements

- USB insertion screen: **1,711 rendered frames**, **0 janky frames**, median
  **5 ms**, p95 **6 ms**, p99 **8 ms** in a roughly 28.6-second gfxinfo sample.
- With animator scale 0: **1 rendered frame**, no continuous loop, over a roughly
  32.8-second sample. A static connection illustration remains visible.
- These are limited native-rendering samples, not an overall OS performance claim.
- Font and animator settings were restored to their original **1.0 / 1.0** values.
- Observed battery-temperature samples were approximately 28.3–29.7 °C, without
  modifying thermal limits, governors or clocks.

## Still pending — do not overclaim

1. A **real complete M1/M2 exchange** while a second authorized client reconnects.
   No EAPOL handshake occurred during the passive windows tonight. The parser's
   synthetic success path is not a substitute for that physical test.
2. Independent observation of a client's response to deauth, including PMF cases.
3. ALFA AWUS036ACH physical flow, unplug/replug and actual external capture;
   explicitly deferred to tomorrow by the user. Inventory/waiting UI is implemented.
4. 5 GHz monitor/capture/injection validation; the current UI deliberately states
   **2.4 GHz** only. No regulatory/DFS override is performed.
5. Persistent ROM service integration and corresponding packaging/licensing.
   Firmware and the ROM were untouched during this APK iteration.

Raw test reports and survey screenshots contain nearby SSIDs/BSSIDs. They are
local QA evidence, **not intended for public GitHub assets**. Do not publish them
without removing those identifiers. Native source/license provenance is recorded
in `alpha/apps/Lambda/THIRD_PARTY_RADIO.md` and the native helper manifest.

## Final handoff state

### Additional polish and independent checks

- Wi-Fi arcs now illuminate from the dot outwards with a smooth fade, rather
  than starting with the outer arc. Reduced-motion mode shows the full static icon.
- Resuming a session first says it is checking status; it no longer briefly
  implies that monitor mode is being activated again.
- Cancellation is labelled **Captura detenida**, with separate explanations for
  cancellation, timeout and size limit. Stop requests remain visible while the
  service confirms cleanup, instead of being overwritten by the next progress poll.
- A real native-UI test started a passive capture of the own lab, went back,
  backgrounded the activity, reopened the same job, and stopped it. Exactly one
  capture was created; elapsed capture time was **19.421 s** and Wi-Fi was restored.
  Evidence: `tests/ui-lifecycle-polish.json`, screenshots `17-polish-scanning.png`,
  `18-polish-resumed.png`, `19-polish-stopped.png`.
- This instrumented capture/navigation sample rendered **829 frames**, with
  **2 janky frames (0.24%)**, p95 **17 ms** and p99 **18 ms**. It is a different
  workload from the idle USB illustration sample above and is not a direct
  performance comparison (`tests/gfx-polish-ui.txt`).
- Killing only the idle maintenance worker exposed a stale-heartbeat case: an
  unclaimed job waited 30 s and incorrectly suggested restoration might be pending
  for that job. The bridge now distinguishes an unclaimed operation from a claimed
  one. The repeated fault test returned `radio_worker_offline` in **11.332 s**
  end-to-end (**9.631 s** job duration), with no RF command delivered and Wi-Fi
  unchanged. The worker was restarted and survived returning ADB to UID 2000.
  Evidence: `tests/worker-fault-polish.json`; screenshot `20-worker-offline.png`.
- The final package retains the existing signing key. The installed APK bytes are
  checked against the local artifact; no ROM partition is changed by this update.

### Public reference PCAPs — offline, not captured on this phone

The same decoder was additionally run on the laptop and **on the S10** against
three public Aircrack-ng reference files at revision
`7e2256f38f3590af4c19a6ae14ed79bab02242e6`. Wireshark/tshark independently agreed
with the message counts below. No passwords were tested and no radio transmission
was involved. This proves decoding of recorded exchanges, **not** live reception.

| Reference | Packets | M1 / M2 / M3 / M4 | Structural M1+M2 recognized |
|---|---:|---|---|
| `wpa.cap` | 13 | 1 / 2 / 1 / 0 | Yes |
| `wpa2.eapol.cap` | 5 | 1 / 1 / 1 / 1 | Yes |
| `wpa2-psk-linksys.cap` | 499 | 3 / 3 / 3 / 3 | Yes |

`wpa.cap` has the old Prism link type 119. Its 144-byte Prism headers were checked
with tshark and stripped **only in the offline fixture** using
`editcap -F pcap -T ieee-802-11 -C 144 wpa.cap wpa-dot11.cap`. The deployed decoder
still explicitly accepts raw 802.11 (105) and radiotap (127), not Prism. The other
two fixtures are unchanged. Evidence: `tests/reference-pcaps-host.txt`,
`tests/reference-pcaps-s10.txt`, `alpha/testing/WifiReferencePcapTest.java`.

Original fixture SHA-256 values:

```
f1ec61a5a2342a07e58d0b6b36bdee5c26bb06f46d1b31acfd0b1972f1b4f20e  wpa.cap
d3f0331b528e4ddb0a4192af8cb949b4bb8233b9a1e05a4b567a90bdd4335f24  wpa2.eapol.cap
e6a3a0f4ef454d5f881964220fe9f0e19dd9ab1c867f86bf0f85a25b416baccf  wpa2-psk-linksys.cap
```

The parser now rejects declared key data extending past the EAPOL payload,
fragments requiring reassembly, and incompatible descriptor types/versions when
matching a pair. It also recognizes M2 with the Secure flag during rekeying; the
old parser incorrectly rejected that case. These checks were reviewed against
[AOSP's EAPOL structures](https://android.googlesource.com/platform/external/wpa_supplicant_8/+/refs/heads/main/src/common/wpa_common.h)
and [supplicant implementation](https://android.googlesource.com/platform/external/wpa_supplicant_8/+/refs/heads/main/src/rsn_supp/wpa.c).
Fixture provenance: [Aircrack-ng test directory](https://github.com/aircrack-ng/aircrack-ng/tree/7e2256f38f3590af4c19a6ae14ed79bab02242e6/test).

The S10's actual root-only tcpdump binary was also exercised **offline** with the
production BSSID/beacon/EAPOL filter: it retained the WPA2 fixture's beacon plus
all four EAPOL messages, and zero packets for an unrelated BSSID. Evidence:
`tests/reference-filter-s10.txt`, `tests/reference-filter-unrelated-s10.txt`.
The first standalone Android fixture-runner launch failed because its DEX was not
built (host javac defaulted to class-file version 69, unsupported by D8). It left
an `app_process` crash record naming `WifiReferencePcapTest`; rebuilding every
test class with `javac --release 8` fixed the harness. This is distinct from an
Operator APK crash and must not be concealed by clearing the device logs.

The native error/capture/result screenshots were compared again with the same
concept: copy, hierarchy, gutters, type scale, monochrome palette and icon/motion
treatment. The explanatory cancellation and service-error copy is a functional
addition; the existing app header/footer remain intentional deviations. No clipped
primary actions were observed in these checked states.

The final APK hash above was checked after installation. Package Manager reports
versionCode 21 / 0.2.0-operator.2 for the installed update (the original system APK
remains below it). Core reports zero active jobs; normal Wi-Fi is enabled, ADB is
UID 2000, and SELinux remains Enforcing. No captures or deauth jobs are left running.

## Completion audit of the overnight objective

The preceding work made concrete progress: installed APKs, real radio and UI
tests, corrected failures, reference-decoder evidence and device-state recovery.
It was not an unchanged-status wait. The current objective is **not yet fully
proven**, because the end-to-end live handshake success case still needs an
authorized second client to reconnect to `2.4GHz_TEST`.

| Requirement | Authoritative evidence | Audit conclusion |
|---|---|---|
| Install the implementation on the reference S10 | Package Manager, matching local/installed final SHA-256 | Verified |
| Internal adapter activation and automatic real survey | Fresh on-device surveys and UI test | Verified for allowed 2.4 GHz channels |
| Select the authorized lab and run bounded capture | Own connection BSSID matched to fresh survey; capture receipts | Verified |
| Capture a complete live exchange | Zero EAPOL frames in live windows; reference PCAPs are offline | **Not verified; requires another client reconnecting** |
| Deauth on the exact lab only | One bounded cycle reported by tool, no automatic retry | Transmission report verified; client effect still unverified |
| Cancellation, timeout, stale selection and recovery | Lifecycle, three-minute timeout, UI and worker-fault reports | Verified in tested cases |
| Smooth, coherent native UI and animations | Concept/screenshot comparisons; font/reduced-motion checks; gfxinfo | Verified in checked native states; success UI still lacks a live event |
| External-adapter work | Inventory and waiting animation implemented | Physical tests explicitly deferred to tomorrow |
| Leave a usable phone | Wi-Fi on, ADB shell connected, root worker available, Core active=0 | Verified |
| Preserve ROM, firmware and data | APK/dev-worker install only; no partition flash or wipe | Preserved |

Do not solve the remaining gap by labeling a public/synthetic fixture as an S10
capture, or by repeatedly transmitting deauth without an observable client. The
next meaningful hardware step is one controlled reconnection by a second
authorized client while the S10 captures. External testing remains deferred, not
implicitly authorized for tonight. Production init-service integration is a
separate ROM-packaging task, not a reason to flash the ROM during this QA pass.
