# Operator WPS — development integration

## Status

Native **Wi-Fi → WPS → OneShot** added to Lambda Operator, version
`0.2.0-operator.3` / versionCode 23. Installed on the reference Galaxy S10
(`beyond1lte`) with the existing release certificate. No ROM, kernel or firmware
was flashed and no user data was cleared.

**An actual WPS exchange has not been validated against a router.** Installation,
dependency execution, discovery, UI navigation and isolated contracts are not proof
of radio compatibility or credential recovery. This remains a development build.

## Upstream and components

- OneShot-Extended: https://github.com/chkndrp/OneShot-Extended
- Pinned commit: `3f19bb0c8b60cc7c584ea5a6e758a15e79920f99`.
- Original OneShot lineage: https://github.com/kimocoder/OneShot
- Pixiewps: https://github.com/wiire-a/pixiewps — native Android ARM64 build.
- Python 3.14.8 and wpa_supplicant 2.12 from Termux packages, relocated into
  `/data/local/tmp/lambda-wps/usr`. The Termux application is not required.
- Python adapter imports upstream parser/Pixiewps classes; it does not invoke
  upstream interactive CLI, process-killing routine, saved-PIN prompts or online
  PIN brute-force loop. This is a controlled integration, not every upstream mode.
- Upstream GPL-2.0-or-later license preserved. The adapter uses the same license.
  Before public binary redistribution, include corresponding upstream/modified
  sources and notices for all runtime dependencies.
- Dependencies are pinned by URL/version/SHA-256 in
  `out/operator-wps/downloads/runtime-lock.json`. Initial trust is HTTPS plus
  recorded checksums, **not** verified APT repository signatures. Package
  maintainer scripts are not run.

## Implemented surface

1. WPS tab alongside Explore, AP and Atacks (existing spelling retained).
2. Native OneShot introduction and root-worker readiness check.
3. Android Wi-Fi discovery filtered to `[WPS]` advertisements.
4. Individual BSSID selection; SSIDs are display data, never shell arguments.
5. Pixie Dust, known eight-digit WPS PIN, and push-button configuration.
6. Target-specific confirmation before an exchange, including Wi-Fi interruption
   and potential router lockout warning.
7. Actual job phases, elapsed time, cancellation and Sessions recovery.
8. Results distinguish received credentials, unconfirmed result, errors and stops.

Initial interface is **internal wlan0 only**. External adapters, continuous online
brute force, four-digit/empty PINs, automatic connection/saving of WPS credentials,
and ROM persistence are not implemented in this increment.

## Execution boundary

Core validates known fields, boolean authorization, BSSID, mode, PIN checksum,
source-job identity, observed WPS capability and observation age (at most 120 s).
The worker receives a validated observation, not arbitrary interface/command text.
Core requires an exclusive idle lease. The app itself does not gain unrestricted
root or expose a network control port.

The isolated subprocess gets one JSON request on stdin. Progress never contains
raw supplicant debug output. A success requires WPS-SUCCESS, a received network
key and an association to the selected BSSID. Those checks reject obvious mismatches;
they do not provide cryptographic AP identity authentication.

The adapter uses a 120 s deadline; a native guard enforces a 130 s hard process
deadline and terminates its owned process group on cancellation or worker death.
One initial exchange and, if Pixie Dust finds a PIN, one verification exchange;
no automatic retry on router lockout. Setup/restoration add overhead.

Wi-Fi enable state is journalled and restored after process cleanup, with startup
recovery for an interrupted worker. The journal is removed only after the setting
matches. This does not prove the prior network reconnects successfully. The full
radio handoff and restoration cycle still require a real authorized test.

Credentials/receipts remain in the existing app-private Core database; they are
shown only in the result view, not emitted into raw worker debug logs. Local
ADB/root RPC retains the existing trusted-owner access policy.

## Verified

| Check | Result | Scope |
|---|---|---|
| APK compile, signature and install | Passed | Existing release identity, no data reset |
| Python imports / dependency executables | Passed on S10 | Does not transmit WPS |
| Adapter protocol tests | 9 tests passed as kali and root | Synthetic supplicant socket and real upstream parser |
| Contract tests | 19 assertions passed on Android | No radio operations |
| Guard invalid-input test | Passed on S10 | Rejected before supplicant |
| Guard cancellation | Passed on S10 | Adapter child terminated |
| Guard parent-death handling | Passed on S10 | Guard and adapter terminated |
| Guard hard timeout | Passed on S10, 130.55 s | No input request, no radio |
| IPC negative tests | Four passed against installed Core | Consent, PIN, source receipt, unknown command |
| Native UI and discovery | Passed on S10 | Real WPS-advertising observations listed |
| Active WPS exchange / recovery | Pending | Needs an explicitly selected authorized router |

Protocol fixtures cover success, missing success event, wrong BSSID, rejection,
lockout and NACK. They also check temporary config/socket cleanup and that only
the final result contains the synthetic key. Contracts cover fresh/stale/missing
observations, absence of WPS, source type/state, authorization, unknown fields,
command-like BSSID input and PIN checksums.

Reproducible scripts: `scripts/test_wps_adapter.py`, `scripts/build_wps.py`,
`tests/WpsContractTest.java`, `tests/wps_guard_device.py`,
`scripts/verify_wps_install.py`. Guard tests send either invalid input or no input;
they do not start supplicant or test any neighboring network.

## Native visual QA

The frontend guide was applied to the **existing native design system**, not a
new web UI. Reference: `out/operator-wps/before.png` and the existing Handshake
component family. No replacement concept or new branding was introduced.
ADB screenshots at **1440 × 3040**, UIAutomator trees, taps and back navigation were
used instead of Browser/IAB because these are native Android views. Reference and
new captures were inspected with view_image.

| Comparison point | Evidence / decision |
|---|---|
| Palette | Existing #101112 background, #1D1F20 surfaces, #EEEEEC foreground retained |
| Typography | Existing MainActivity heading/body/button helpers and weight hierarchy |
| Insets and shell | Existing header/footer, status bar and cutout handling retained; no clipped heading |
| Navigation | Four equal-weight chips; WPS selected state visible; Back returns without starting work |
| Controls | Native rounded controls, at least 48 dp; PIN selection and numeric input are actual widgets |
| Assets | Existing RadioIllustration phone/Wi-Fi paths reused, no generic raster replacement |
| Layout | Existing 20 dp content margin and vertical scroll; long real network list scrolls above footer |
| Copy | Intentional additions only: WPS workflow, target/method information and safety/readiness text |

Local screenshots `03-wps-tab.png`, `04-oneshot.png`, `06-networks.png`,
`07-methods.png`, `08-confirm.png`, `09-pin.png` are under `out/operator-wps`.
Network screenshots/trees are private QA evidence, **not public website assets**.
Dark S10 layout inspected; light theme, larger accessibility text and all actual
radio-result states are not yet visually verified. The native surfaces inspected
are consistent with the existing design; full end-to-end approval remains pending.

## Build and deployment

Use `prepare_wps_runtime.py`, `build_wps.py`, `build_lambda.py`, then
`sign_operator.mjs` and `install_wps_dev.py`. Build scripts accept kali or root
(root builds drop to kali). Privileged device work stays in the allowlisted worker.

APK: `out/operator-wps/Lambda-operator-wps.apk`.
SHA-256: `016dbdc77d38c7798d15ff8f71cde322a21cd84175b54367910a0d8882184f79`.
Pre-install APK and worker are preserved in `out/operator-wps/checkpoint`.
The root radio worker remains the existing **development worker until reboot**;
this APK installation alone does not make the added runtime part of the ROM.
At handoff, ADB was restored to uid 2000 (non-root), the privileged worker remained
available, Wi-Fi stayed enabled, and neither radio nor WPS restoration journal
remained pending. No active WPS session was started during this integration.

Next validation: choose an authorized router with WPS enabled, record its exact
BSSID and permitted mode, test one bounded session, then verify cancellation,
timeout, Wi-Fi restoration and results against that router. Do not infer target
authorization merely because a nearby BSSID appeared in discovery.
