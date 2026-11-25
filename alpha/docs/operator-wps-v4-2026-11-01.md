# OneShot WPS — progress and diagnosis update

Date: 2026-11-01. Lambda Operator **0.2.0-operator.4**, versionCode **24**.
This supersedes the implementation details of the initial operator.3 WPS report;
the older report remains a historical record, including its earlier test results.

## Delivered

- Native progress panel: actual protocol stages M1–M8, current message and direction,
  elapsed time, exchange attempts, retries, distinct PIN candidates and repeated
  message observations. Preparing a message is labelled "preparado", not proof
  that a radio frame was successfully transmitted.
- Last six activities displayed, ten retained in each bounded telemetry snapshot.
  No raw supplicant output, PIN guesses or network keys in progress events.
- Push-button waiting state: **Esperando WPS button…**. Association/authentication
  and message exchange replace that state when observed; no simulated percentage.
- Separate M2D/deferred, NACK/rejected, timeout, insufficient Pixie data, calculated
  PIN not found, confirmed credentials and explicitly reported lockout outcomes.
- Fourth mode: bounded online PIN brute-force batches, adapted from OneShot's
  two-part search and reusing its checksum/parser. This does not call upstream's
  interactive prompts, recursive retries or unbounded loop.
- Batch choices: **10 / 50 / 100 exchanges**, default 10; four-digit starting prefix;
  600 s session limit, 30 s per exchange and 3 s pause between exchanges. A timeout
  may retry the same candidate twice. Retries consume the exchange budget and the
  counter increments only when that retry actually starts. Ambiguous rejections
  or explicitly reported lockouts stop the batch, rather than skipping candidates.
- A received M5 alone is not enough to advance the search stage: the supplicant
  must also have constructed M6. Final success still requires WPS-SUCCESS, a key
  and association to the exact selected BSSID. It is not cryptographic AP identity.
- Native guard limits: existing 130 s hard limit for normal modes; allowlisted
  `--bruteforce` enables a 610 s hard limit. Core/bridge deadlines cover cleanup.
- Each exchange gets a fresh supplicant process and control socket, preventing
  late output, old credentials and unread CANCEL replies from crossing candidates.

This is a **bounded batch**, not an exhaustive full-PIN-space search. No automatic
lockout bypass, automatic resume, external-antenna WPS or saved WPS connection was
added. Existing snapshot freshness, target-specific confirmation and exclusive
radio ownership remain in effect.

## False-lock diagnosis

Pinned OneShot-Extended commit `3f19bb0c8b60cc7c584ea5a6e758a15e79920f99`
sets `IS_LOCKED` for M2D and for early NACK. The previous integration surfaced
that heuristic directly as a locked router. The new adapter does not do so.

M2D is a negotiation deferral and is not by itself proof of lockout. For example,
the upstream [registrar implementation](https://android.googlesource.com/platform/external/wpa_supplicant_6/+/526fc2a7dc09b4450086cdec313a5c44d36b10fd/src/wps/wps_registrar.c)
builds M2D separately from the setup-locked condition. WPS configuration errors
distinguish [setup locked (15), timeout and password-authentication failure (18)](https://raw.githubusercontent.com/vanhoefm/hostap-wpa3/master/src/wps/wps_defs.h).

Here, lockout is reported only from an anchored WPS-FAIL event carrying code 15
after association to the selected BSSID. A generic M2D, NACK, timeout, arbitrary
SSID text or unassociated lock event cannot establish it. No local AP-mode event
is misused as evidence about the remote router.

**This fixes an identifiable false-positive path. It does not prove the current
WPS status of MIWIFI_4PUQ or guarantee that Pixie Dust works on that router.**
No WPS exchange or brute-force attempt against a router was started by the
assistant during this update. User-reported tests are not counted as assistant
verification.

## Verification

| Test | Result | Boundary |
|---|---|---|
| Adapter tests | 22 passed as kali; 22 passed as root | Synthetic supplicant, no RF |
| Android contract tests | 26 passed on S10 | Validation only, no RF |
| IPC rejection checks | 4 passed on installed Core | Consent, checksum, missing source, unknown command |
| Native guard | 5 checks passed on S10 | Unknown option, invalid input, normal cancel, brute cancel, parent death |
| Native UI fixtures | 6 screenshots captured on S10 | Methods, brute selection/scroll, PBC, exchange, M2D result |
| Build/sign/install | Passed, existing release certificate | In-place APK update; no reset or ROM flash |
| Device files | APK, adapter and guard hashes match host | Exact installed worker/runtime artifacts |
| Real WPS success / timed-out Wi-Fi handoff | Not tested this update | Requires router-specific active validation |

Synthetic tests exercise M2D and early NACK without claiming lockout, explicit
lockout, unassociated lock events, correct/incorrect target association, missing
success event, duplicate messages, PBC wait state, PIN rejection, batch limits,
same-candidate retries, deadline, second-half transition and stop-on-ambiguity.

The guard's newly selected 610 s full wall-clock timeout was not waited out;
cancellation was executed with `--bruteforce`. The prior 130.55 s measurement in
the operator.3 report is historical, not a newly repeated measurement.

Reproduce with `scripts/test_wps_adapter.py`, `scripts/build_wps.py`,
`tests/WpsContractTest.java`, `tests/wps_guard_device.py`,
`scripts/verify_wps_install.py`. `tests/WpsUiPreview.java` is separate signed
instrumentation: it supplies fixtures directly to the view, never Core.submit.
Its APK was removed from the S10 after QA. Source/test artifacts remain local.
The build and host test commands support both kali and root; root builds drop to
kali. Android radio operations require the existing privileged worker, not an
unrestricted root app or arbitrary shell endpoint.

## Visual verification / fidelity ledger

The frontend skill guided a targeted extension of the established native UI.
Reference: `/home/kali/Documents/Claude/LambdaOS/alpha/out/operator-wps/07-methods.png`.
New captures: `/home/kali/Documents/Claude/LambdaOS/alpha/out/operator-wps/wps-qa-*.png`.
`view_image` inspected the reference and new rendered captures in the same QA
pass, including the scrolled brute-force controls. Capture size: **1440 × 3040**.
ADB plus Android Instrumentation/UiAutomation replaces Browser/IAB because this
surface is a native Android activity, not an HTML page.

| Comparison | Reference → implementation | Disposition |
|---|---|---|
| Palette | Same near-black background, charcoal surfaces and off-white text | Preserved |
| Typography | MainActivity sans-serif hierarchy and medium-weight buttons | Preserved, readable in viewed states |
| Spacing | Same 20 dp margins, rounded cards, vertical rhythm | Preserved; working illustration reduced to fit status and stop |
| Icons/assets | Existing phone/Wi-Fi RadioIllustration, unchanged navigation icons | Reused; new M1–M8 marks are native data UI |
| Insets | Header, status area and bottom navigation unchanged | No observed overlap in captured states |
| Controls | Four method rows and 48 dp batch choices; scroll to prefix/Continue | Visible and reachable in scrolled capture |
| Live status | Generic exchange-only text → stage, counters and activity history | Intentional requested addition |
| Copy diff | Added fourth mode, batch settings, exact PBC wait label and qualified failure text | Intentional requested changes; no marketing filler |

The inspected dark S10 surfaces are faithful to the existing visual system;
no material mismatch was observed in those captures. This is not an assertion of
10/10 coverage: light theme, enlarged accessibility text, motion timing under
actual RF load and every live-router outcome remain unverified. Fixtures test
rendering, not real network progress. No permanent fixture hooks are shipped in
the production app. Final screenshots are retained as local verification evidence,
not public website assets; temporary phone-side copies were removed.

## Artifacts and limitations

APK: `out/operator-wps/Lambda-operator-wps.apk`

SHA-256: `8e359546471cba5c3a936a74a32110288327a25229bcc9f7fffe590e8593a4eb`

Adapter SHA-256: `1c4b598c500a82de2b722cd62456235c21ffe2ba5d07c1505739b21b82913045`

Guard SHA-256: `274a546f925dd26ac9e89f2ec9a8d6e6b69703a202361410e6623b63e382ed26`

Previous operator.3 APK and app source checkpoint:
`out/operator-wps/checkpoint-v3/`.

This update is installed as an APK/runtime, **not packed into a new ROM**.
The privileged worker is still a development service until reboot. Instrumentation
ended its process during QA; it was restarted explicitly after tests with Core
idle. The app can show worker unavailability honestly if that service is absent.
Wi-Fi restoration means its previous enable setting is restored, not proof of
reconnection to the original AP. Real handoff/restore validation remains pending.

Final handoff check (2026-11-02, Europe/Madrid): ADB uid 2000, SELinux Enforcing,
Core active jobs 0, worker available, WPS provisioned, Wi-Fi enabled. The four IPC
rejection checks passed again after `adb unroot`. The instrumentation package is
absent and Lambda MainActivity is the resumed activity. No phone reboot occurred.
