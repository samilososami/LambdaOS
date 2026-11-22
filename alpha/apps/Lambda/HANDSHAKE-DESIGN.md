# Handshake capture · Operator 2

Concept: `alpha/out/operator-handshake/design/flow-concept.png`.
Native Android, not a web view. Existing header, three-item footer and theme are
retained; the concept's two-item footer is intentionally not adopted.

## Visual contract

- Background #101112, surface #1D1F20, foreground #EEEEEC, secondary #A4A7A9.
- Existing light theme uses its existing tokens, without changing OS appearance.
- 20dp horizontal gutters, 8/12/20/24dp rhythm, 48dp minimum interactive targets.
- 28–31sp titles, 17sp medium actions, 14–15sp body, 12sp metadata.
- Outline monochrome handshake, S10/USB antenna and Wi-Fi glyphs. The device
  animation is a scalable native line pictogram, not a rasterized interface.
- Chips: Explorar / AP / Atacks; Atacks contains Handshake capture and Deauth.
- Equal-width Interna / Externa choices, Samsung S10 / USB · OTG subtitles.
- 150–220ms page transitions; USB plug loop ~2.4s; honor animator scale zero.
- No fake percentage, adapter, network, packet count, success or timing.

## States

Wi-Fi tags → Atacks → Handshake capture → antenna choice.
Internal: clear warning that normal internal Wi-Fi is temporarily paused → real
monitor activation → passive channel survey → network choice → target-specific
authorization → bounded passive EAPOL capture → results/export.
External: animated OTG guidance + live inventory (including already attached and
multiple adapters) → choose a driver-bound supported interface → stable identity
check → monitor/survey → target choice → authorization → capture.

Cancellation, no broker, unsupported USB, missing driver, re-enumeration, empty
survey, WPA3-only/open network, no client connection, deadline, process death and
restore errors must be visible, never replaced with a successful simulation.
Back/Home do not silently terminate a running capture. Explicit stop does.
Entering Handshake capture or Deauth from the menu resumes a queued/running job
only. A terminal job instead opens fresh antenna selection; previous results
remain in Sessions. Reopening from Sessions explicitly displays that receipt.
Starting fresh clears UI selection/result state, not the Core history. Merely
opening the menu never submits another capture or deauth request.

## Privilege boundary

APK Core uses a bounded allowlisted root worker over Binder. No shell strings or
user supplied file paths. The development worker is provisioned by authorized ADB
and is not persistent across reboot; future ROM integration needs its own init
service and reviewed SELinux domain. Do not pretend an APK grants itself root.
SELinux stays enforcing. The later user request adds a separate Deauth operation:
fresh observed BSSID, explicit confirmation, one tool cycle, ten-second deadline,
no automatic retry. Transmission reported by a tool is not proof of client impact.
No password guessing or association is included. Passive handshake capture never
sends deauth implicitly. The worker restores the adapter in finally.

### Capture backend

The preliminary passive channel survey still uses tcpdump. Once the user has
selected and authorized a target, the handshake recorder is airodump-ng, fixed to
that BSSID/channel with no hopping or active-scan option. Aireplay-ng remains the
separate optional sender. Both run under the bounded parent-death-aware guard.
The pinned legacy port's stdin pipe stays open so its input thread blocks instead
of spinning on EOF. No UI text or network-controlled string is sent to stdin.

The worker reads only the target's native WPA-handshake **header**, not arbitrary
mentions in SSIDs/client rows. A result is marked detected only alongside at least
two retained, structurally decoded EAPOL frames. `airodump_handshake_reported` and
`handshake_detected` are distinct from the additional legacy `pair_observed`
M1/M2 check and from cryptographic verification (always false here). The display
says “Handshake detectado”, not “password validated” or “all four messages”.
Old receipts retain their original M1+M2 wording.

Each run has a new private capture directory, eliminating stale console/PCAP
results. Record time remains 180 seconds, native guard 205 seconds. Raw PCAP,
console and CSV are bounded; only the selected target's beacon and EAPOL records
are retained for export (under 120 KB). Unrelated/data frames are not exported.
Stop drains the final flushed PCAP before removing temporary files and restoring
Wi-Fi. This implementation uses the development port documented in
`THIRD_PARTY_RADIO.md`; it is not a release-ready upstream source build.

### Optional deauth inside a capture

The capture screen adds a same-height pair: surface-colored **Deauth**, white
**Detener** (56dp, 12dp gap, existing 16sp medium type). No new palette, decorative
assets or navigation. A separate native confirmation names the selected SSID and
BSSID. It warns of disconnection and the one-burst/10s limit. Cancelling it has no
effect. The control is disabled before capture, after a request, during stopping
and in the last 15s. Progress/results distinguish tool-reported transmission from
unverified client impact. This requested addition is the only capture-layout
change from the accepted native screen.

`capture_deauth` is a control RPC, not another queued radio job. It takes only the
capture ID, request ID and explicit authorization, never a new target/interface
or count. Core persists intent before delivery, binds the command to the current
capture token/session/BSSID, and delivers at most once with a five-second expiry.
The worker checks that binding again. A separate guarded sender runs while the
same tcpdump process and EAPOL parser continue. It does not tune or restore the
interface. Stop/completion cleans up both processes before restoring Wi-Fi. The
standalone Deauth action shares the bounded sender but owns its own restoration.

## Verification

ADB screenshots on the actual S10 replace Browser/IAB because this is native
Android. Compare hierarchy, spacing, typography, palette, icon treatment and
selected states against the concept. Test packet parsing independently from
hardware reception; a synthetic fixture is not a real captured handshake.
