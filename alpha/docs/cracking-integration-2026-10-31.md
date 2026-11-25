# Lambda Operator — offline WPA cracking integration

Date: 2026-10-31. Device: Samsung Galaxy S10, `beyond1lte`, Android 15,
kernel `4.14.356-lambdaos+`. Package `org.lambdaproject.lambda`, versionCode 22,
versionName `0.2.0-operator.2`.

## Result and deployment boundary

The CPU quick check and CPU/GPU dedicated workflow are implemented and installed
in the S10 development environment. This is an APK plus separately provisioned
native binaries and privileged worker, **not a newly flashed ROM**. The worker
currently needs restarting after a phone reboot. No factory reset, partition
write, thermal-policy change or SELinux relaxation was performed.

All acceptance work here was offline: synthetic fixtures and a previously saved,
authorized `2.4GHz_TEST` handshake. No new deauthentication, monitor-mode scan or
radio capture was initiated in this integration pass. Credentials are omitted
from this report and redacted from the acceptance JSON outputs.

## Implemented behavior

1. A successful handshake capture restores the radio and checkpoints its evidence
   to Core before the automatic 4,800-entry CPU quick check. The capture consent
   discloses that offline check. Error/cancellation does not mean dictionary exhaustion.
2. An unsuccessful quick check exposes the dedicated cracking route. A dedicated
   job confirms the target, dictionary and user authorization; the native UI shows
   completed-batch progress, elapsed time, end-to-end speed and battery temperature.
3. The dedicated dictionary contains 203,109 entries. Users may import `.txt`
   lists through Android's document picker. Imported passwords must be printable
   ASCII, 8–15 bytes; input is capped at 64 MiB, four million entries and ten lists.
   Invalid lines are excluded and counted. Imported duplicate lines remain attempts;
   built-in lists are deduplicated. Arbitrary filesystem paths are not accepted.
4. Automatic engine selection uses CPU below 24,000 entries and hybrid above it.
   Explicit CPU/hybrid options remain available. This threshold is a practical
   starting policy; a complete crossover sweep has not been performed.
5. Closing/backgrounding the Activity leaves the job running. Stop requests kill
   native work, preserve capture/history and report a partial result. Starting
   another check begins from the start: resumable dictionary offsets are not implemented.
6. Found passwords are displayed directly in the result UI. A candidate must
   belong to the current batch and pass a second verification by the original
   Aircrack binary before `cryptographic_validation=true` is reported. Earlier
   screenshots used masking; that default was removed on 2026-11-01.

The dedicated path currently consumes saved WPA/WPA2-PSK EAPOL handshakes, not
arbitrary PMKID files or WPA3-SAE/enterprise credentials. The older development
benchmark also has synthetic PMKID tests; that is a different interface.

## Engines and measurement definitions

The quick path uses the previously optimized CPU SHA Aircrack build, eight
threads, with up to 4,800 entries per batch. Starting the GPU costs enough time
that it loses on this small workload.

The hybrid path keeps an OpenCL context alive, divides PMK derivation between CPU
and Mali GPU, and uses 19,200-entry batches. A separate Aircrack build consumes
those PMKs and performs the handshake checks. Original Aircrack independently
confirms positive results. Hashcat is not involved in this working path.

**PMKs/s** measures only PMK calculation. **End-to-end keys/s** includes setup,
dictionary/capture checks, known-answer self-test, PMK work, Aircrack verification
and cleanup. Full-job wall time starts in the worker; queue/UI/ADB observation
latency is not included. Negative batches have an exact completed-entry count.
For a positive batch the receipt exposes an upper bound rather than claiming an
exact index from Aircrack's parallel execution.

## S10 timings

Each row in the main table is the median of three runs, using the same negative
synthetic handshake. CPU and hybrid order was interleaved. The long comparison
used a deterministic 96,000-entry fixture; it is not a measure of dictionary quality.

| Complete workload | CPU SHA | Hybrid CPU + GPU |
|---|---:|---:|
| 4,800 candidates: wall time | **1.351 s** | 2.516 s |
| 4,800 candidates: keys/s | 3,552.92 | 1,907.79 |
| 96,000 candidates: wall time | 25.603 s | **16.559 s** |
| 96,000 candidates: keys/s | 3,749.56 | **5,797.45** |

For the longer workload this is about **54.6% more throughput** and **35.3% less
wall time**. It does not justify calling the full app a 10,000-keys/s cracker.

A separate run launched entirely through the APK exhausted the bundled 203,109
entries in **32.557 s (6,238.57 keys/s)** while also checking Activity backgrounding.
This is one run, not a repeated median. Its PMK-only work took 23.219 s; the rest
of the full time is real overhead, not omitted work.

The final 19,200-PMK correctness case computed its batch in 1.888 s, approximately
10,170 PMKs/s, excluding setup and handshake verification. Thermal state, batch
length, dictionary and target data affect timings. No claim of sustained one-hour
throughput or battery-life gain is made.

## Validation evidence

Local raw evidence lives under `alpha/out/cracking-integration/`:

| Evidence | What was checked |
|---|---|
| `acceptance-1793455853/results.json` | 28 checks: quick exhaustion, positive saved-lab capture, 4,801-entry boundary, independent confirmation, malformed input, authorization, idempotency, exclusivity, cancellation, preserved capture and idle state |
| `acceptance-1793457697/results.json` | Same 28 checks repeated successfully on the final compact-layout APK; the long cancellation case uses the automatic hybrid engine |
| `hybrid-pmk-1793456575/results.json` | 24,756 individual PMKs across 12 cases, every byte compared with host `hashlib.pbkdf2_hmac`; multiple SSID byte lengths including 1/31/32 and UTF-8, passwords 8–15 and varied batch sizes |
| `hybrid-acceptance-1793456841/results.json` | 47 checks: positive MIC verification and original-CPU confirmation, CPU/hybrid A/B, exact exhaustion counts and cancellation |
| `ui-acceptance.json` | Real Android document-picker import: three valid lines retained, two rejected; native 203,109-entry job, automatic hybrid choice and background/return without cancellation |
| `../cracking-speed/controls-qa.json` | 13 benchmark/control checks, including rejection, identity restriction, idempotency, lease and cancellation |
| `final-install-verification.json` | 17 final checks: installed APK hash/version, native hashes, target device, idle Core, available development worker, no compute children, shell ADB, enforcing SELinux and no crash records for the current app/Core PIDs |

`radio_contract_test.py` also rejected all 12 malformed requests before queueing
on the final APK. Some rejection cases fail an earlier authorization gate rather
than isolating the intended address validator, so these are contract rejection
checks, not complete branch coverage. Final clean screenshots are
`ui-final-exhausted.png` and `ui-final-found-masked.png`; the earlier
`ui-active.png` captured an Android transition and is not used as visual evidence.
The positive-result UI was originally checked for masked-by-default display,
explicit reveal and re-masking after leaving/re-entering. That behavior was later
removed at the user's request; those screenshots document the previous UI. The
final read-only verifier does not hash the root
worker APK itself because its directory is intentionally inaccessible to shell;
the installation log records that separate deployment.

The correctness harness checks **all** generated PMKs against an independent host
implementation. In normal use the native helper cross-checks 18 samples per
larger batch (all entries for batches of up to 32), followed by full Aircrack MIC
checking and independent original-binary confirmation of any found password.
Sampling in production is not presented as independent verification of every PMK.

The measured hybrid stop latency was 2.815 s including test RPC/polling overhead.
The guards stop a process group, not unrelated Android applications. Native
children run as UID 2000 with empty supplemental groups, guarded time limits,
parent-death cleanup and bounded memory. Core retains an exclusive cracking lease.
The model-unloading/resource-coordination design for Lamb is still future work.

An early implementation rejected Aircrack's SIMD padding as an unexpected cache
key. This was corrected to derive only padding/end-marker lanes on CPU while
still rejecting missing real candidates. The failing attempt was an error, never
reported as a completed negative dictionary result. All reported final correctness
and hybrid acceptance runs are after that correction.

## Thermal and resource observations

Across the repeated CPU/hybrid acceptance workload, sampled maxima were 82 °C on
the CPU-labelled BIG/MID/LITTLE sensors, 62 °C on G3D and **36.6 °C on the battery**.
CPU sensor readings are not battery or surface temperature. The sequence reached
close to the native 85 °C SoC stop threshold, so long-duration testing remains
important. The battery stop threshold is 42 °C. Existing Android/kernel thermal
management was not disabled and no overclocking was introduced.

The minimum sampled system `MemAvailable` was 4,654.43 MiB. This is **not** a PSS,
GPU allocation or whole-workload peak-memory measurement. Tests were tethered and
the battery was full; they do not establish energy per candidate or battery drain.
The one-hour session limit is an implemented bound, not a one-hour endurance result.

## Artifacts and provenance

Final compact-layout signed APK:
`alpha/out/cracking-speed/Lambda-operator-benchmark.apk`

Current visible-result + Connect APK SHA-256:
`9541d1b2ba57fffbd8850d1dedcb21e427186062df22d5f2ab3146d6c0085ed7`.
Previous visible-result-only APK:
`ee89ba276d2bbfaf34db66bc20e31722de2ed8d93c03a39c9426e97fb41ce542`.
The earlier masked-result validation APK was
`a8719250a66c76833513fb8becd0dad7a5db015d0514f666d3af51cca8541123`.
Signing certificate SHA-256:
`dd4c8fe7ce0cb97d53df455c8f2ddc08ba90759985d6bfa2c9ed91759d12f6e6`.

Native build manifests and pinned hashes are in
`alpha/out/cracking-speed/hybrid-build-manifest.json`:

| Artifact | SHA-256 |
|---|---|
| Optimized CPU Aircrack | `c1d6114351352e683e51712be8d5769e34493ecda95aef7af06a29b2db73295c` |
| Hybrid Aircrack | `a50a332e94ecd9f0d2a5f109c1aee5e411ab34dc54219e9b4efc5983e827125c` |
| Hybrid PMK helper | `d38c26117a8c80a4b4e8e7aff9bd943ff208b24ee810afaa48a06f40bd463bdf` |
| Native guard | `3f86d4329b14d0897d5df7e657828be00c66bd2aa6653aebfa270f4ee2373b80` |
| OpenCL source | `d46e4a6fed2c2c54dfdbd933e92d5a3805928c8cd374ffcc17369848b45a9b6f` |

The dedicated wordlist is berzerk0's Probable-Wordlists Top204Thousand WPA v2 at
commit `2df55facf06c7742f2038a8f6607ea9071596128`, filtered/deduplicated to ASCII
8–15. The output has 203,109 entries and SHA-256
`5ac6754f8411ce895f69a43ab741606d8634fa6ac88018555aca4d5d745b184d`.
Attribution, CC-BY-SA-4.0 and corresponding-source obligations are described in
[third-party components](cracking-benchmark-third-party.md). A future ROM release
must include the appropriate notices and corresponding source/build changes.

## Remaining acceptance / release work

- Fresh capture → restored radio → durable checkpoint → automatic quick-check
  acceptance on the actual phone. It is wired in code, but the tests in this report
  deliberately reused saved captures instead of transmitting radio traffic.
- Forced-process-death testing of the new pre-quick capture checkpoint. Normal
  cancellation, backgrounding and preservation were checked; they are not a
  substitute for power-loss testing.
- Sustained thermal/energy testing, alternate permitted capture variants, broader
  UI accessibility/layout testing and separate dedicated PMKID import if wanted.
- ROM integration of the native tools, restricted worker startup/lifecycle and
  source/license bundle. The APK update alone is not reboot-persistent deployment
  of the privileged worker.

## Follow-up: requested test on `2.4GHz_TEST`

The current connected SSID and BSSID were checked against a fresh internal-antenna
survey before selecting the target in the native UI. The passive capture then ran
for 180.670 s without observing any EAPOL frames. No deauthentication was requested.
The outcome was correctly partial/timeout, not a successful handshake, and no
automatic quick check was launched without a usable capture. Normal Wi-Fi was
restored and reconnected to the original target; Core returned idle, ADB remained
shell UID 2000, and SELinux remained enforcing.

Evidence: `alpha/out/cracking-integration/live-capture-1793458768/results.json`
and its `result-masked.png`. The initial driver attempt used the former confirmation
button label (`Capturar`); it stopped before capture, restored Wi-Fi, and was
corrected to the current `Capturar y comprobar`. That was a harness mismatch,
not an application crash or a successful capture.

Separately, both engines were run against the **previously saved real handshake**
from this same SSID/BSSID, source receipt `017e1d3d-837e-4555-8f52-59469c33a3af`:

| Run | Dictionary | Result | Full worker time |
|---|---|---|---:|
| `own-lab-crack-bb1b4ecfa37e4d63` | Quick, 4,800 entries, CPU | Found; independently verified by original Aircrack | 1.466 s |
| `own-lab-crack-ce9cfdce08444fa7` | Dedicated, 203,109 entries, hybrid | Found in the first batch; independently verified | 4.400 s |

These are single positive-result trials, not exhaustive throughput measurements.
The processed-count upper bounds were 4,800 and 19,200 respectively; neither
result claims the complete dedicated dictionary was traversed. The password is
not reproduced here. Verification against an older capture does not establish
that a subsequently changed network password would be the same.

The fresh **capture → checkpoint → automatic quick check** success path therefore
remains pending a new client connection while capture is active. The standalone
CPU and hybrid checks against the existing real target capture both passed.

### Authorized deauth-assisted follow-up · 2026-11-01

The same exact SSID/BSSID was selected again from a fresh internal survey. Once
capture was active, the user-authorized `capture_deauth` control accepted exactly
one bounded broadcast burst. The native receipt reported one cycle, exit code 0,
1.622 s elapsed and `transmission_reported=true`. The application deliberately
keeps `effect_verified=false`: a tool receipt proves transmission, not which client
reaction caused a subsequently observed exchange.

This run observed four EAPOL frames, a matching pair and Airodump's handshake
indicator. It completed capture in 6.963 s, restored Wi-Fi, saved a durable capture
checkpoint and automatically entered the 4,800-entry CPU quick check. The quick
check found a candidate in 1.587 s and original Aircrack independently verified it.
The screenshot from that run used the former masked UI; the current build displays
verified passwords directly. Normal Wi-Fi reconnected to the original AP;
Core returned idle, ADB was UID 2000 and SELinux remained enforcing.

Evidence: `alpha/out/cracking-integration/live-deauth-1793539115/results.json`
and `result-masked.png`. The first validator treated only `sent`/`capture_ended` as
valid receipt labels, while the implementation correctly emits
`transmission_reported`; this test-harness expectation was corrected without
issuing a second deauth. The application result itself was already completed and
valid. This closes the live capture → checkpoint → automatic quick-check acceptance
path for the tested S10/internal-adapter/lab-AP configuration.

The current APK was then rebuilt with verified passwords visible by default on
both result screens. UI inspection confirmed that each screen contains the exact
verified receipt value without a reveal control or bullet mask. The verifier did
not print or screenshot that value. Controls remained 13/13 and final install
verification 17/17. Future live acceptance runs skip screenshots when a password
is present to avoid leaving an additional credential-bearing artifact.

### Connect from either verified result · 2026-11-01

Installed a shared **Conectar** action in automatic and dedicated results.
Only an existing verified receipt supplies the credentials. The new typed
`wifi.connect_verified` operation rejects synthetic/unverified/missing sources,
unknown arguments and caller-provided target/password overrides. It saves the
normal Android network, uses the captured BSSID, and verifies the actual primary
association plus local IP before displaying **Conectado**. No internet assertion
is made. The connection receipt does not duplicate the recovered password.

The real UI test began with Wi-Fi disabled before each button press. Both paths
enabled Wi-Fi and connected to the expected owned AP in **2.882 s** (single trials,
worker time, not a performance distribution). Double taps produced exactly one
job per action. Six malformed/unverified requests were rejected. USB ADB remained
available at UID 2000, SELinux enforcing, and final installation checks passed
17/17. No new capture, deauth or cracking workload was run for this change.

Evidence: `alpha/out/cracking-integration/verified-wifi-connect.json` and
`final-install-verification.json`. Test driver:
`alpha/testing/test_verified_wifi_connect.py`. No credential-bearing screenshot
or raw positive cracking receipt was exported by this acceptance run.

Follow-up: saved-network and automatic reconnection were also verified on the
phone. `cmd wifi list-networks` includes `2.4GHz_TEST`; its configuration reports
`allowAutojoin=true` and `NETWORK_SELECTION_ENABLED`. After disabling and enabling
only the S10's Wi-Fi, it rejoined the same SSID/BSSID in 4.3 s without pressing
Connect or issuing another `connect-network` command. The existing implementation
already saves the network with autojoin enabled (it does not pass CLI `-d`), so
this verification required no further APK change. Reconnection after a reboot
was not tested in this follow-up.
