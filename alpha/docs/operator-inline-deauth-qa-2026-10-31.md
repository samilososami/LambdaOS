# Lambda Operator — deauth inside an active capture

Date: 2026-10-31. Native Samsung Galaxy S10 / beyond1lte, internal BCM4375B1 +
Nexmon. This is an APK/maintenance-worker update, not a ROM flash or factory reset.

Installed artifact: `alpha/out/operator-handshake/Lambda-operator2.apk`, version
0.2.0-operator.2 / code 21, existing Lambda release certificate.
SHA-256: `a55b5cb393352d85ec1ce7bddcf97da8f34370a8029d8155e047654899c9d327`.
The installed APK's hash was read back and matches. The pre-change signed APK is
retained at `alpha/out/operator-handshake/checkpoint/Lambda-before-inline-deauth.apk`.

## Delivered behavior

- Handshake capture has **Deauth** beside **Detener**, only during capture.
- A separate confirmation names the selected SSID/BSSID and explains that clients
  can disconnect. Dismissing the dialog does not request a transmission.
- One bounded aireplay cycle per capture, at most ten seconds, no retries.
- The original tcpdump continues while a separate guarded sender runs. Its parser
  continues draining EAPOL; the action does not retune or restore the interface.
- A matching M1/M2 pair completes capture automatically; Stop cancels it. Cleanup
  stops the sender/recorder and restores the prior Wi-Fi state.
- Outcome text separates tool-reported transmission from unverified client impact.
  The final receipt retains both the capture and the optional deauth report.
- The separate Deauth flow remains available. The theme, animation, existing
  navigation and ordinary passive capture behavior remain unchanged.

## Contract and privilege

`capture_deauth` accepts only `id` (active capture job), `request_id`, and Boolean
`authorized`. The trusted local caller is checked by CoreProvider. Core binds the
request to the existing target; callers cannot supply another BSSID, interface,
program, shell command, duration or count. Intent is saved in the private SQLite
receipt before it becomes available to the root worker.

The command additionally binds the capture token and survey session, expires in
five seconds, and is delivered at most once. A replacement worker, wrong token,
completion, cancellation or expired request cannot replay it. The worker checks
the binding again and refuses a new burst in the final 15 seconds of capture.
No implicit transmission happens when starting a passive capture.

The development worker still requires explicit provisioning and does not survive
a phone reboot. ADB is returned to shell UID 2000; SELinux remains Enforcing.

## Tests and evidence

Local evidence lives in `alpha/out/operator-handshake/`. Raw reports/PCAPs are
private lab artifacts, not public repository assets.

- **24 isolated Android-runtime contract checks** pass under normal kali and a
  root invocation of the test script (which drops to kali for workspace writes).
  Tests execute in a separate unprivileged VM: no Binder or RF calls. Cases cover
  token/session/target binding, expiry, cancellation, completion, worker changes,
  last-15s protection, duplicate delivery and root-only worker registration.
- **12 existing invalid radio-contract cases** still fail before queueing.
- The native UI test rejects **8 malformed or inapplicable inline requests** and
  checks that dismissing the confirmation neither transmits nor consumes a burst.
- On the first real UI run against the already-connected, freshly re-observed
  **2.4GHz_TEST**, the sender reported one cycle with exit 0 in **1.626 s**.
  Capture observed **5 EAPOL frames** and ended with a matching M1/M2 pair after
  **41.684 s** total capture/cleanup time. Wi-Fi restoration was confirmed.
- Independent `tshark` inspection of `tests/inline-first-pair.pcap` identifies
  message numbers **1, 1, 1, 2, 3**, all from the selected lab BSSID. This is a
  real capture, not a synthetic fixture. Neither a password nor a cryptographic
  MIC was checked, and client disconnection was not independently measured.
- The first test harness incorrectly expected capture to remain running after
  the burst; the real pair had already completed it. The assertion failed, while
  the application behaved correctly. That initial evidence is retained as
  `tests/inline-first-pair.json`; the harness now accepts genuine early completion.
- The **final installed build** passes **25 native workflow checks**. The same
  tcpdump PID was observed before and after the burst. A duplicate request was
  rejected and the recorder/parser continued until an actual M1/M2 pair arrived.
  This run collected **6 EAPOL frames**, completed in **40.477 s**, and the sender
  reported exit 0 after **1.218 s**. `tshark` identifies messages **2, 3, 4, 1, 2,
  3** in the retained PCAP. Evidence: `tests/inline-deauth.json` and
  `tests/inline-deauth.pcap`.
- Final state: zero active jobs, no tcpdump/aireplay/guard left running, development
  worker available, normal Wi-Fi reconnected to the lab, ADB shell UID 2000 and
  SELinux Enforcing. Font/animator scales remain 1.0. Battery sample: 28.8 °C.
- A final passive cancellation regression stopped capture in **3.154 s**, with
  zero EAPOL and **no deauth request/result**; Wi-Fi restoration succeeded.
  Evidence: `tests/inline-passive-cancel.json`. The unchanged offline parser also
  recognizes structural pairs in both newly captured real PCAPs. No new entries
  appeared in the crash buffer during this iteration's time window.

Commands:

```sh
python3 alpha/testing/run_radio_control_tests.py
python3 alpha/testing/radio_contract_test.py
# Active test, exactly one UI-confirmed cycle on the verified own lab:
python3 alpha/testing/radio_inline_deauth_test.py --send-one
```

## Visual fidelity

Reference: `alpha/out/operator-handshake/design/flow-concept.png`, plus the accepted
native capture screen `18-polish-resumed.png`. Native ADB screenshots and real
UIAutomator taps replace Browser/IAB: this is not a web app. Checked at the actual
**1440 × 3040** S10 resolution with direct `view_image` inspection.
Final-build screenshots: `22-inline-capture.png`, `23-inline-confirm.png`,
`25-inline-result.png`. Result details remain scrollable to **Nueva captura**.

| Point | Comparison and outcome |
|---|---|
| Copy | Title, target, counters, Stop and Sessions link preserved; the requested Deauth action and necessary confirmation/result copy are the intentional additions. |
| Layout | Equal-width Deauth/Stop row replaces the full-width Stop button at the same vertical position. No additional decorative panels. |
| Typography | Existing native sans-serif, 16sp medium button text, existing title/body hierarchy retained. |
| Palette | Same near-black background, dark-gray secondary action and off-white primary action; no new accent or glass effects. |
| Spacing | Existing gutters, 56dp action height and 12dp inter-button gap; no overlap or clipped primary actions in the reviewed capture/confirmation states. |
| Imagery/motion | Existing phone/laptop line illustration and real-state-linked capture loop unchanged; no generated replacement assets. |
| Shell | Existing Lambda header and three-tab footer retained, as in the already accepted native implementation. |

The addition is faithful to that existing native design, with the explicitly
requested action-row change. The native confirmation dialog is intentionally
reused. Above-the-fold copy has no unrelated marketing or new navigation.

## Deferred

- External ALFA hardware testing remains separate; no external adapter was used.
- Persistent ROM service packaging remains separate.
- Cracking is only an idea recorded in `IDEAS.md`; no cracking binary was installed
  and no password search was performed by this change.
