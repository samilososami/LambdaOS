# Operator NTLM capture (WPAD / LLMNR / SMB) — 2026-11-23

Status: implemented, built and device-tested on the S10 as
`0.2.0-operator.8` (versionCode 28). A real Windows client has not been
tested yet; the lab validation used Samba and impacket clients reached over
`adb forward`, plus crafted name-resolution packets.

## Scope

The AP now has an `ntlm` option (UI switch “Capturar hashes NTLM (WPAD)”,
default on). While the AP runs, the root worker starts
`alpha/apps/Lambda/ntlm-capture.py`, a Python-stdlib responder that:

- answers LLMNR (UDP 5355, 224.0.0.252), NBT-NS (UDP 137) and mDNS
  (UDP 5353, 224.0.0.251) queries with `192.168.50.1`, so Windows name
  lookups for stale, mistyped or proxy names land on the phone;
- serves a WPAD PAC file (`PROXY 192.168.50.1:3128`) on TCP 8079, announced
  via DHCP option 252, and on `/wpad.dat` over the portal's port 80 when the
  captive portal is also enabled;
- runs an NTLM proxy on TCP 3128 that answers `407 Proxy Authentication
  Required` and captures the NetNTLM challenge response;
- runs a fake SMB2 server on TCP 445 that negotiates NTLMSSP (SPNEGO with
  `negState`/`supportedMech`), captures the Type 3 message and then fails
  the logon. SMB1 multi-protocol negotiate requests are answered with an
  SMB2 wildcard negotiation so clients switch dialects.

The portal and the NTLM capture are independent options and can be combined.
With the portal on, DNS already answers every name with `192.168.50.1`; with
the portal off, normal DNS forwarding is untouched and the capture relies on
multicast name poisoning and WPAD.

Why this works without the victim entering a URL: Windows resolves names in
the background all the time (WPAD auto-detection, Chrome's DNS-redirect
probe, Explorer/application access to stale shares and printers). When one of
those names resolves to the phone, Windows automatically authenticates with
the logged-in session over SMB or the proxy, and the responder records the
NetNTLMv1/v2 challenge response. Association alone does not send a hash;
there must be a name-resolution/authentication trigger. Android and iOS do
not produce NetNTLM at all.

Captures are appended as JSON lines, shown in the running screen under
“Credenciales y hashes” and in the job result (rolling cap of 50), in hashcat
format:

- NetNTLMv2 (mode 5600): `USER::DOMAIN:CHALLENGE:PROOF:BLOB`
- NetNTLMv1 (mode 5500): `USER::DOMAIN:LM:NT:CHALLENGE`

There is no relay and no cracking in this iteration.

## Files

- `alpha/apps/Lambda/ntlm-capture.py` — new responder (no third-party code).
- `RadioWarden.java` — `ntlm` argument, DHCP option 252 (initial and
  portal-release configs), responder lifecycle, capture ingestion into the
  credentials list, `/wpad.dat` in `PortalServer`, `killStrays()` pattern.
- `CoreEngine.java` — accepts and validates `ntlm` as a boolean in manual,
  native-scan and legacy AP modes.
- `ApUi.java` — switch on the AP screen, argument plumbing, labels.
- `radio_contract_test.py` — `ap string ntlm` rejection case.
- `AndroidManifest.xml` — `0.2.0-operator.8`, versionCode 28.

## Safety boundary

The responder accepts packets and connections only from `192.168.50.0/24`
plus loopback (loopback exists for adb-forward testing only). The upstream
network is never poisoned or served; `killStrays` removes orphans at worker
start. The worker still exposes no arbitrary shell: the script is started by
the worker with fixed arguments.

## Verification performed (2026-11-23)

Build and deployment:

- `build_lambda.py` produced `lambda-0.2.0-operator.8-dev.apk`; signing used
  `lambdaos-release-v1` (`dd4c8fe7…`), release APK sha256
  `e37dc0ea4c73d4cae837c3519e094316389d3ae6b1905db5effa1e10f2ab1228`;
  `adb install` succeeded and the worker restarted.
- `radio_contract_test.py`: 31 rejection cases pass, including
  `ap string ntlm` (`ntlm must be a boolean`).

AP run (manual `lambdantlm`, open, `ntlm=true`, portal off, no upstream):

- responder log: SMB 445, LLMNR 5355, NBT-NS 137, mDNS 5353, HTTP 80,
  proxy 3128, PAC 8079 listening.
- `ap-dnsmasq.conf` contains
  `dhcp-option=252,http://192.168.50.1:8079/wpad.dat`.
- SMB: `smbclient` in pure SMB2 and through the SMB1 multi-protocol
  negotiation both ended in `NT_STATUS_LOGON_FAILURE` and produced a
  NetNTLMv2 capture.
- Proxy: an impacket client completed 407 → Type 1 → Type 2 → Type 3 and
  produced a NetNTLMv2 capture.
- Cryptographic check: for both captures,
  `HMAC-MD5(ResponseKeyNT, challenge + blob)` equals the captured proof for
  the known test password (user `lambda`, domain `WORKGROUP`), so the stored
  lines are valid NetNTLMv2 for hashcat mode 5600.
- WPAD: the PAC was served correctly on 8079 and on port 80 with the portal
  off.
- Portal + capture: with the portal owning port 80, the responder kept
  SMB/proxy/8079 and `PortalServer` served `/wpad.dat` (200, PAC) next to the
  portal page (200); checked from the phone against `192.168.50.1` because the
  portal binds the AP address, not loopback.
- LLMNR: crafted queries were answered with `A 192.168.50.1` both unicast and
  multicast; NBT-NS was answered; the mDNS reply was observed on `ap0` with
  tcpdump.
- Cleanup: the job result retained the capture and `ntlm=true`, the capture
  and log files were deleted when the AP stopped, `ap0` was removed and
  Wi-Fi was restored.

### Windows client test (partial, 2026-11-23)

A real Windows machine joined the AP at `192.168.50.10` with upstream on. It
fetched `/wpad.dat` four times and Windows used the proxy: the NCSI probes
(`www.msftconnecttest.com`, `ipv6.msftconnecttest.com`) reached port 3128 and
received 407 repeatedly. No Type 1/Type 3 was observed and no capture was
written, including after the browser was opened; no non-NCSI request reached
the proxy. Hypotheses for the next session: the PAC's `DIRECT` fallback may
let browsers bypass the proxy after a 407; request headers are not logged, so
a Type 1 cannot be confirmed from the log; HTTPS navigation needs CONNECT
verification; WinHTTP services may never send NTLM. This remains open in
`IDEAS.md`'s TO-DO section.

## Not verified / limitations

- No real Windows client yet: Windows SPNEGO behaviour, WPAD auto-detect and
  the practical LLMNR/NBT-NS trigger rate still need a physical test.
- macOS only in limited proxy/SMB cases; Android/iOS never send NetNTLM.
- SMB1-only clients are not served (we answer with SMB2).
- IPv6 LLMNR/mDNS and IPv6 WPAD are not implemented; IPv4 only.
- NTLM relay is out of scope. Cracking NetNTLMv2 needs hashcat/John outside
  the device; the Lambda cracker remains WPA-only.
- No export action for the captures yet (shown in the session and receipt).
- Installing the operator APK kills the app/Core in development; relaunch the
  app and restart the worker as usual.

## Deploy notes

The responder script must be present next to `worker.apk`:

```
adb push alpha/apps/Lambda/ntlm-capture.py /data/local/tmp/lambda-operator-radio/ntlm-capture.py
adb shell chmod 755 /data/local/tmp/lambda-operator-radio/ntlm-capture.py
```

The worker runs it with the Termux runtime
(`/data/local/tmp/lambda-wps/usr/bin/python3`, `LD_LIBRARY_PATH` to
`/data/local/tmp/lambda-wps/usr/lib`). If the script is missing, the AP runs
normally and the console log reports “script de captura NTLM no desplegado”.
