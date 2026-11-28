> Nota de publicación: los identificadores de laboratorio (SSID/BSSID/MAC) se han redactado en esta copia pública; el original privado se conserva sin cambios.

# Lambda Operator — AP / evil twin

Date: 2026-11-22. Lambda Operator **0.2.0-operator.5**, versionCode **25**.
Development APK signed with the existing `lambdaos-release-v1` `releasekey`
(certificate SHA-256 `dd4c8fe7ce0cb97d53df455c8f2ddc08ba90759985d6bfa2c9ed91759d12f6e6`)
and installed with `adb install -r`; app data and receipts were preserved.

## Delivered

- **Core tool `radio.ap`** (typed, allowlisted, same queue/receipt path as
  capture/deauth). Arguments: `session`, `bssid`, `authorized`, `seconds`
  (integer 30–300), `sniff`, `portal`. Validation rejects non-boolean consent,
  unknown fields, broadcast/zero BSSID, string or out-of-range duration.
- **Worker `evilTwin`**: reuses the selected adapter from the survey, restores
  monitor mode, then starts an open clone AP with `/vendor/bin/hw/hostapd`,
  local `dnsmasq` DHCP/DNS (`192.168.50.1/24`, pool `.10–.100`), optional
  `tcpdump` AP-side capture (snaplen 128, bounded), live progress (DHCP clients,
  packets) and guaranteed restore. The restore journal records `monitor=false`,
  so crash recovery restores Wi-Fi without issuing Nexmon monitor commands.
- **UI**: the Wi-Fi category's AP tab now opens `ApUi` with the same
  internal/external pattern as handshake and deauth. External selection waits
  for a USB adapter and reuses `radio_status`. Flow: passive survey → target
  selection with an explicit authorization dialog → running screen
  (time, DHCP clients, packets) → stop/restore → result with optional `.pcap`
  save (only when the capture is ≤ 120 KB).

## Verified on the S10

- Host build, release-key signing and `adb install -r` succeeded; installed
  version is `0.2.0-operator.5` (vc 25) with the release certificate.
- Worker replaced and running as root; `radio_status` reports available.
- `status` lists `radio.ap` and reports `0.2.0-operator.5`.
- Contract test (`alpha/testing/radio_contract_test.py`): all 20 cases rejected
  before queueing, including the 8 new AP cases (consent, duration, unknown
  field, broadcast BSSID, empty session).
- Error path: valid-shaped `radio.ap` with an unknown session → job `failed`
  with `scan_session_expired`; `wlan0` remained `type managed`, Wi-Fi enabled.
- Manual AP stack on internal `wlan0`: hostapd accepted a generated config,
  `iw` reported `type AP` and the configured SSID; dnsmasq started with the
  configured DHCP range and DNS; the exact restore sequence returned
  `type managed` and `wifi_on=1`.
- **Real clone AP running on the S10** against the owner's `2.4GHz_TEST`
  (BSSID `xx:xx:xx:xx:xx:xx` redacted, channel 6): `iw` reported `type AP` and the
  cloned SSID, `hostapd` ran as user `wifi`, `dnsmasq` and the bounded
  `tcpdump` were active, and the UI showed the running screen
  (`0` clients, `0` packets without a station attached).
- The 180 s run completed with outcome `deadline`, `0` clients,
  `17` packets / `1,980 B` captured into the receipt and `restored: true`;
  `wlan0` returned to `type managed` and Wi-Fi was enabled. No `hostapd`,
  `dnsmasq` or `tcpdump` process remained.

Two fixes were required for the real path:

- hostapd runs as uid `wifi`, not root; it cannot traverse the worker's `0700`
  directory. Its config now lives in `/data/local/tmp/lambda-ap` (traversable,
  config `0644`) and only that file is exposed.
- Switching from monitor to AP must not re-enable Wi-Fi and disable it again:
  the framework keeps `wlan0` busy long enough for hostapd to fail. The worker
  now stops Nexmon, sets the interface to managed and starts hostapd while
  Wi-Fi remains disabled; the journal keeps the pre-survey Wi-Fi state.

## Not verified

- **Full end-to-end against a real authorized target.** Requires the user's
  explicitly authorized lab AP and an unlocked device; not performed.
- **External adapter AP mode.** No adapter connected. `88XXAU` AP support via
  hostapd/nl80211 is untested; the worker fails with `ap_start_failed` if the
  driver rejects it.
- Client association, DHCP lease and captured traffic from a real station.
- **BSSID cloning on the internal radio.** dhd rejects the MAC change while the
  bus is suspended (`set cur_etheraddr failed`); not attempted. Only SSID and
  channel are cloned; the receipt reports `bssid_cloned:false`.
- Captive portal page: only captive DNS (every name resolves to the AP), no
  HTTP server or page yet.

## Limits

- Open network only (no WPA2 clone), 2.4 GHz, one radio, maximum 5 minutes.
- AP pcap is embedded in the receipt only when ≤ 120 KB.
- No deauth assist during the AP: it needs injection and a monitor interface
  on the same radio, which conflicts with AP mode.

## Artifacts

- Build: `alpha/out/lambda-0.2.0-operator.5-dev.apk`
- Release-signed: `alpha/out/operator-ap/lambda-0.2.0-operator.5-release.apk`
- Previous installed APK: `alpha/out/operator-ap/before-operator.5.apk`
- Worker on device: `/data/local/tmp/lambda-operator-radio/worker.apk`

## Second iteration — APSTA, BSSID clone, upstream, DHCP routing

- The AP now runs on a **virtual `ap0` interface**, not on `wlan0`. The phy
  reports `#{ AP } <= 2, #{ managed } <= 4`, so `wlan0` stays associated as a
  client while `ap0` beacons as an AP. This is what makes the upstream path
  possible on the internal radio.
- **BSSID cloning is possible**: while the phy is awake, `ip link set ap0
  address <target-bssid>` succeeds and hostapd uses it (`addr` verified). It is
  a toggle (`clone_bssid`), **off by default**: a clone with the real BSSID
  merges with the real AP in client scans (laptop `nmcli` showed one entry with
  cloning on and two entries with cloning off), so clients may never select it.
- **Upstream NAT**: toggle `upstream` (default on). The worker picks the current
  default route (`wlan0` STA or mobile data), enables `ip_forward`, installs
  `MASQUERADE` and the two `FORWARD` rules, and restores the previous values.
  Verified with `ping 8.8.8.8` through `wlan0` while the AP was active.
- **DHCP routing**: Android policy routing sent uid-0 DHCP replies to the
  `wlan0` table even though `ap0` owns 192.168.50.0/24. The client sent
  DISCOVER and dnsmasq logged OFFER, but no REQUEST arrived. The worker now
  adds priority rules for `192.168.50.0/24` and `255.255.255.255` to the main
  table and uses `dhcp-broadcast=tag:ap0`; `ip route get` now resolves both to
  `ap0`. End-to-end DHCP with a real client is **pending retest** after this
  change.
- Nexmon: the version check moved after Wi-Fi is turned off, with state waits
  and retries, fixing a transient `__nex_io` failure after an AP session.

## Third iteration — client DHCP and forwarding

- The owner's S23 (`s23u`, MAC `xx:xx:xx:xx:xx:xx` redacted) completed DHCP against `ap0`:
  DISCOVER → OFFER → REQUEST → ACK, address `192.168.50.10`. DNS also worked
  (queries answered and forwarded to 1.1.1.1/8.8.8.8).
- The client still showed "connected, without internet": its TCP SYNs reached
  `ap0` but never traversed FORWARD. Android's policy table `main` has **no
  default route**, and the rule list ends with `32000: from all unreachable`,
  so forwarded client traffic was dropped before netfilter. Per-interface
  `forwarding` was already 1.
- Rules added by the worker while the AP is active (removed on restore):
  `to 192.168.50.0/24 → main` (server replies), `from 192.168.50.0/24 →
  <upstream table>` (client internet), `iif ap0 → main`, and
  `to 255.255.255.255 → main`. The source rule is what the client traffic
  needs; `ip route get` with a non-local source is misleading here.
- The survey regression from the previous iteration (extra Wi-Fi on/off cycle
  left monitor unable to capture, 0 networks) was reverted: the worker now
  disables Wi-Fi, brings the interface up, checks Nexmon with retries, and uses
  the full Wi-Fi cycle only as a recovery path.
- **Pending:** confirm client internet after reconnecting with these rules
  active. The rules are deployed but the client had not reconnected yet.

## Fourth iteration — internet confirmed, speed tuning

- Inserting the FORWARD rules at the **top** of the chain (before netd's
  `oem_fwd`/`fw_FORWARD`) fixed the forwarding: the owner's S23 confirmed
  internet through the AP. FORWARD counters climbed to thousands of packets and
  MASQUERADE was active; DHCP ACK, DNS and TCP all worked end-to-end.
- Full root-cause chain: DHCP OFFER egress (fixed with `to 192.168.50.0/24`
  and broadcast rules), then forwarded traffic dropped because Android's rule
  list ends with `from all unreachable` and `main` has no default route
  (fixed with `from 192.168.50.0/24 → <upstream table>`, `iif ap0 → main` and
  top-of-chain FORWARD accepts).
- Speed: hostapd was started with `wmm_enabled=0`, which forces the AP leg to
  legacy 11g rates. The worker now uses `wmm_enabled=1`, `ieee80211n=1` and
  `ht_capab=[SHORT-GI-20]`. Pending the owner's confirmation of throughput;
  same-channel AP+STA on one radio still shares airtime, so the twin will
  always be slower than connecting directly.
- Survey robustness: when a sweep returns zero networks (a known transient
  after some AP sessions), the worker runs one full Wi-Fi cycle and rescans
  automatically.

## Fifth iteration — IPv6 (SLAAC), guard limit, stray cleanup

- **IPv6 on the AP**: `fd50::1/64` on the AP interface plus a minimal Router
  Advertisement sender (`ra_sender.py`, raw ICMPv6, no external daemon). The
  worker enables IPv6 on the interface, waits for the autoconfigured
  `fe80::` link-local (required for the RA source) and only then starts the
  sender; if it never appears the receipt reports `ipv6:false`. RDNSS
  advertises `fd50::1`. Router lifetime is 0: the S10 has no IPv6 upstream
  (`wlan0` has no global v6 and the kernel lacks NPTv6 SNPT/DNPT), so client
  IPv6 is local to the AP; Internet still goes over IPv4 NAT.
- Two `ra_sender.py` bugs fixed before it worked: the link-local check read
  `/proc/net/if_inet6` scope as 0x10 (link-local scope is 0x20) and the RDNSS
  option was 28 bytes with length 3 (must be 24 with 2 bytes of padding).
  Verified on air: `fe80::… > ff02::1: ICMP6 router advertisement` every 8 s.
- **`radio-guard` cap raised 220→620 s** (recompiled with the same NDK; the AP
  op itself is bounded by Core at 600 s). The previous cap silently killed the
  sniffer (`ap_sniff_failed`) once the AP default duration grew past 180 s.
- **Stray daemon cleanup**: worker start now kills orphaned
  hostapd/dnsmasq/tcpdump/radio-guard/ra-sender from replaced workers. Two
  orphaned pairs found on the phone were the main heat source (BIG 76 °C,
  load 13 with no job running); after cleanup the phone cooled to ~39-43 °C.
- Client-side IPv6/SLAAC validation still pending: the phone had not
  reconnected within the test window. Device-side check to use when it does:
  `ip -6 neigh show dev ap0` should show the client's `fd50::` address.

## Sixth iteration — manual AP, live client UI, faster survey

- **Faster survey** (shared by AP/handshake/deauth): one pass at 300 ms dwell
  (beacons repeat ~10/s), progress RPC every four channels instead of per
  channel, and the post-disable wait now polls `wifi_on` instead of a fixed
  sleep. Measured end-to-end: ~11 s (was ~20 s). WPS is unaffected: it already
  uses Android's native scanner, not this sweep.
- **Manual AP** (`radio.ap` with `mode:"manual"`): SSID, optional BSSID
  (empty → random locally administered MAC), optional passphrase, security
  OPEN/WPA2/WPA3-SAE/mixed and channel 1-13. Validation: SSID 1-32 bytes;
  empty passphrase forces OPEN (passphrase with OPEN is rejected); non-OPEN
  requires 8-63 printable characters; a partially typed BSSID is rejected;
  the same explicit authorization gate applies. Manual mode skips the survey
  and the monitor transition entirely (also the coolest way to run the AP).
- **Live client UI**: connected stations from `iw station dump` (count drops
  back to 0 on leave), hostname/IP from the dnsmasq leases, a rolling console
  log with timestamps (connected/left/kicked/banned/unbanned), a banned list
  with one-tap unban, and long-press on a client card offering kick or
  kick+ban. Delivery goes through a new `ap_control` Core RPC with the same
  single-delivery contract as the capture deauth; bans are enforced by
  kick-on-sight while the AP runs.
- **Thermal guard**: the worker samples the BIG zone each tick and stops the
  AP (`outcome:"thermal_stop"`) at ≥ 78 °C, so the phone can no longer cook
  during long sessions.
- Fixes found by device tests: `ap_control` crashed the session until the
  worker's key allowlist accepted `caller_uid`/`requested_ms`; the bridge kept
  the control pending after delivery (`ap_control_busy`); the ack window was
  too short for slow polls. Kick/ban/unban now verified end-to-end.
- Not yet verified: a real client joining a WPA2/WPA3 manual AP (open and
  clone flows are verified), and the manual AP on an external adapter.

## Seventh iteration — native scan, real bans, unbounded AP

- **Native scanner for the internal AP flow**: cloning no longer runs a monitor
  survey. The app uses Android's own scan (`wifi.scan`) and Core passes only a
  completed snapshot plus a BSSID that snapshot actually contains to the
  worker (`snapshot_clone` workerInput). External adapters keep the monitor
  sweep. A small allowlisted op `radio.wifi_on` turns Wi-Fi back on first
  (the scanner refuses to run with Wi-Fi disabled; third-party apps cannot
  enable it themselves). Monitor mode stays for handshake/deauth.
- **Custom AP BSSID removed**: dhd ignores a per-vif MAC change for the data
  path — hostapd beacons with the custom MAC but AP-to-client frames never
  arrive, so clients associate and hang on DHCP. Manual APs always use the
  driver's MAC; the random/custom BSSID fields are gone.
- **Manual form**: SSID, optional password (empty = OPEN), WPA2/WPA3/MIXTO
  chips (no OPEN chip), optional channel field (empty = random 1-13).
- **Unbounded AP**: no `seconds` argument from the UI; jobs run until the user
  stops them. The wakelock is held without timeout, the bridge deadline is a
  24 h safety net, and the sniffer rotates pcap segments (radio-guard's 620 s
  cap) that are merged for the receipt.
- **Real bans**: hostapd runs with `macaddr_acl=0` + `deny_mac_file`; a ban
  rewrites the file and reloads hostapd, so the client cannot reassociate
  (verified: kicked + blocked until unbanned). Unban does the same in reverse.
- **Clients only with an IP**: the live list and counter only include stations
  that already have a DHCP lease, so "conectado" means usable.
- **Resume**: opening the AP tab (or tapping the Core notification, which now
  deep-links) reattaches to a running `radio.ap` job, whoever started it.
- **Exclusivity**: one Wi-Fi job at a time (`radio.*` and `wifi.*`); a second
  submission is rejected with `wifi_task_in_progress`. USB/Bluetooth families
  are not blocked by the rule, though the shared single-thread executor still
  queues them behind a running AP for now.

## Eighth iteration — captive portal

- Toggle **"Portal cautivo"** in the AP tab (before cloning or building a manual
  AP) plus a template picker: `google` and `router` are adapted from
  FluxionNetwork/fluxion (GPL-3.0, static HTML only; the worker replaces the
  PHP handlers — see `alpha/apps/Lambda/portal/NOTICE.md`); `simple` is
  original. Fluxion ships no Facebook template, so none was added.
- The radio worker runs a minimal HTTP server on `192.168.50.1:80` (root):
  it serves the template and its assets, answers the Android/Apple/Windows
  connectivity probes with a 302 to the portal (which triggers the system
  "sign in to network" flow), and records every POSTed form as a credential
  entry `{time, ip, data}`.
- dnsmasq adds `dhcp-option=114` (captive portal URL) and keeps
  `address=/#/192.168.50.1` while the portal is on.
- Captured credentials appear in the live console, in a dedicated
  "Credenciales del portal" section of the running screen, and in the job
  receipt.
- Verified on the S10 with local curl: GET `/` returns the Google template
  (200), `/generate_204` returns 302, and a POSTed
  `email=test@example.com&password=hunter2` shows up in the console and the
  credentials list; the AP restored cleanly afterwards.
- Deploy note: the templates live in `alpha/apps/Lambda/portal/` and must be
  pushed to `/data/local/tmp/lambda-operator-radio/portal/<template>/` when
  provisioning the worker.
