# Cracking benchmark: third-party components

The benchmark API measures fixed synthetic WPA2/PMKID fixtures only. It does not
accept arbitrary shell commands or capture files. The separate `crack.wpa`
integration now accepts authorized saved-handshake receipts, with the additional
source/attribution requirements below.

| Component | Version/source | License source |
|---|---|---|
| Aircrack-ng | 1.7; pinned archive in `build_cracking_bench.py` | Original `LICENSE` and accompanying notices in the source archive |
| OpenSSL | 3.5.9; pinned archive | Original `LICENSE.txt` (Apache-2.0) |
| Hashcat | 8.0.0; pinned source archive in `build_hashcat_bench.py` | `docs/license.txt` (MIT) and `docs/license_libs/` |
| Hashcat research only | 6.2.6, commit `e5b302363654cf4717dbe5bbe417504fc5d0ca72`; Android affinity and memory-budget patches in `build_cracking_research.py` | Original `docs/license.txt` and `docs/license_libs/` in the retained source tree |
| Android NDK libc++ | NDK 27.1.12297006 | NDK toolchain `NOTICE` |
| Probable-Wordlists | [Top4800 WPA v2 by berzerk0](https://github.com/berzerk0/Probable-Wordlists) | CC-BY-SA-4.0; attribution and share-alike apply |
| Probable-Wordlists, dedicated list | `Real-Passwords/WPA-Length/Top204Thousand-WPA-probable-v2.txt`, commit `2df55facf06c7742f2038a8f6607ea9071596128` | Same upstream `License.txt`, CC-BY-SA-4.0 |

The quick dictionary is the original 4,800-entry file, not a newly generated
password corpus. All entries are printable ASCII, unique and 8–15 bytes long.
Its SHA-256 is
`5dc33214cad9a11eb926de00f5ded20b1a5fc12ba3332319e054713265a35c51`.
The preparation script refuses an upstream change rather than silently replacing it.

The dedicated list is an adaptation of berzerk0's Top204Thousand-WPA-probable-v2:
printable ASCII, lengths 8–15, duplicates removed while retaining upstream order.
It contains 203,109 entries (2,109,521 bytes). The source is pinned at
[this exact upstream revision](https://github.com/berzerk0/Probable-Wordlists/blob/2df55facf06c7742f2038a8f6607ea9071596128/Real-Passwords/WPA-Length/Top204Thousand-WPA-probable-v2.txt).
Source SHA-256: `e416890a4742481971ba6d85039e0072111c92cb46c8cb7f3f8f22b865f48bf4`.
Filtered SHA-256: `5ac6754f8411ce895f69a43ab741606d8634fa6ac88018555aca4d5d745b184d`.
`prepare_dedicated_dictionary.py` records this provenance and checks both content
and counts. The app links the source/license and identifies the filtering.
Distribute this adapted list under the same CC-BY-SA-4.0 terms, with attribution
and the modifications identified; do not relabel it as a Lambda-created corpus.

Retain the upstream license texts, source/build scripts, attribution and
corresponding source obligations when distributing these binaries in a ROM.
These tools are currently provisioned separately for development; they are not
silently embedded in a release ZIP.

The isolated Aircrack CPU SHA variant is a modified build, not an unmodified
upstream release. Retain `build_cracking_research.py`, `pmk-sha1.h`, upstream
source and corresponding license notices with any future binary distribution.
The synthetic CPU/GPU probes remain research tools. The separate general-purpose
hybrid integration uses `pmk-hybrid-worker.c`, `pmk-hybrid.cl`, `pmk-sha1.h`,
`pmk-cache.h`, `benchmark-guard.c` and `build_cracking_hybrid.py`. It links OpenSSL
and modifies a separate Aircrack copy; preserve all corresponding sources,
patch/build scripts and upstream notices when distributing binaries. The original
Aircrack remains available for independent confirmation. No Hashcat kernel is used
in this hybrid path. A successful local build is not a completed ROM license bundle.

The fixture generator creates a local `LambdaBenchmark` WPA2 exchange and PMKID
with an explicitly synthetic key. It never reads captured radio traffic. The
single-key correctness fixture is separate from the 4,800-entry performance list.
