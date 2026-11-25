# Operator: airodump-ng handshake recorder

Date: 2026-10-31. Physical device: Samsung Galaxy S10 `beyond1lte`, internal
BCM4375B1/Nexmon adapter. No ROM, kernel or firmware flash; no data wipe.

## Implemented scope

- Selected-target handshake recording now uses airodump-ng instead of tcpdump.
  The preliminary passive channel survey continues using the proven tcpdump path.
- The selected BSSID and channel come from the current authorized survey. No
  arbitrary CLI arguments, file paths, stdin commands or automatic deauth added.
- Aireplay-ng and the separately confirmed one-burst control remain unchanged.
- Airodump's native WPA-handshake header is read incrementally, checked against
  the selected BSSID, and accompanied by saved, structurally decoded EAPOL frames.
  SSID rows, wrong BSSIDs, malformed headers and stale files cannot satisfy the
  tested status-reader contract.
- The result separates `airodump_handshake_reported`, `handshake_detected`,
  legacy `pair_observed`, and `cryptographic_validation` (false).
- UI uses “Handshake detectado” for new native-detected receipts. It does not
  claim a validated password, checked MIC, or four messages for every capture.
- Capture stops automatically on detection; manual stop and resource/time limits
  remain. Temporary native PCAP/CSV/log files are separate per run. Exports retain
  only the chosen AP's beacon and EAPOL, not unrelated/encrypted user traffic.

## Native component provenance and limitation

This development integration uses the retained Nexmon Android arm64
**Airodump-ng 1.2 rc4 port**, not a current/reproducibly built upstream release.
One hard-coded helper-directory literal is relocated to the existing root-private
`/data/lambda-op`, as already done for the Nexmon aireplay binary. The original
input is preserved and hash-pinned.

- Original airodump SHA-256:
  `a8cccc7fca0fbd80f21024557922710398a08054255996a38eb3ea685b4fcdc0`.
- Adapted airodump SHA-256:
  `7a12d0d2f3bf46ebd4295a523d59df2d93b3c5d92540d1f95e0c53034fe52278`.
- Unchanged aireplay SHA-256:
  `6ad8512e36c718d612aaaf8845bc33a9f4610e91a5428dba67c279644c0780da`.

Before redistribution in a ROM, replace the compatibility binary with a reviewed
source build and include corresponding sources, patches and licenses. See
`alpha/apps/Lambda/THIRD_PARTY_RADIO.md`. External adapters were not attached or
tested in this run; internal Nexmon success is not an external-driver guarantee.

## Tests completed

| Test | Evidence/result |
|---|---|
| Status reader on host | 15 checks passed: real-format header, ANSI, split reads, target mismatch, SSID/message spoofing, malformed/overlong lines, unexpected truncation |
| Isolated capture-control tests on S10 | 24 checks passed, no RF or Binder calls |
| Passive cancellation on S10 | 12,977 ms; airodump captured a real beacon; zero EAPOL, no false detection; cancellation and restoration succeeded |
| One UI-confirmed inline deauth on lab | 25 checks passed; one aireplay cycle; normal APK UID 10128 submitted the confirmation |
| Real handshake | Native airodump detection true; 14 EAPOL frames retained; recording ended automatically; Wi-Fi restored |
| Independent PCAP inspection | TShark decoded one target beacon and 14 target EAPOL frames, including M1, M2, M3 and M4; no unrelated BSSID |
| Native UI lifecycle on new backend | One passive capture survived Back/Home, resumed without duplication, cancelled and restored; menu/Activity recreation returned to antenna choice; Sessions retained the unchanged receipt |
| Build entry points | Helper staging/build works as kali and via sudo, with identical native output hashes; root invocation drops artifact ownership to kali |

The 43,158 ms positive capture includes time spent on UI confirmations and negative
control tests; it is **not** a measurement of handshake detection latency.
Tool-reported deauth transmission is not proof of client impact. No password was
guessed, supplied or cryptographically tested.

### Evidence

- Passive result: `alpha/out/operator-handshake/tests/airodump-passive-cancel.json`;
  job `lab-d1076d0d4c-action`.
- Positive result: `alpha/out/operator-handshake/tests/airodump-inline.json`;
  capture `017e1d3d-837e-4555-8f52-59469c33a3af`.
- PCAP: `alpha/out/operator-handshake/tests/airodump-inline.pcap`;
  SHA-256 `9ee9e19820c1ceb2cafb975228bfdbb9e33a76f0819e6e85a8ba6eb1ce3cc6c9`.
- Native UI screenshots: `alpha/out/operator-handshake/airodump-inline-*.png`.
  The actual result screen was visually inspected.
- Lifecycle result:
  `alpha/out/operator-handshake/airodump-lifecycle/tests/ui-menu-reentry.json`;
  capture `8590c005-d322-4152-bcf1-d3412d048962`. Duration 24,857 ms, zero EAPOL,
  no native detection, no stale success inherited from the previous run.

## Installed APK

`alpha/out/operator-handshake/Lambda-operator2.apk`

SHA-256: `9846a92b956a9cd5aabbd217d35a2efccd4129da4408d6fdd0afa5c6e6876bcd`.
Existing Lambda signing key retained. Previous APK checkpoint:
`alpha/out/operator-handshake/Lambda-before-airodump.apk`.

The installed `base.apk` hash matched this artifact. Final state: zero active
Core jobs, no recorder/sender left running, Wi-Fi reconnected to the lab, ADB UID
2000 and SELinux Enforcing. No crash-buffer entries appeared after test start.

Provisioning uses the existing explicit maintenance root worker, not an APK
granting itself root. The ordinary application remains unprivileged. As before,
the development worker must be provisioned again after reboot; this task does not
claim a persistent ROM-integrated service. SELinux remains Enforcing.

## References consulted

- [Official airodump-ng documentation](https://www.aircrack-ng.org/doku.php?id=airodump-ng).
- [Nexmon Aircrack-ng reference source](https://github.com/seemoo-lab/nexmon/blob/68720406f54ff16cad28fd3c51f2392aefad9fb7/utilities/aircrack-ng/src/airodump-ng.c):
  native WPA state/indicator and header layout; not asserted to be the exact
  reproducible source of the retained binary.
