# LEX wireless scan, external-display prompt and web consent — 2026-11-24

Status: implemented and device-tested as `0.2.0-operator.28` (versionCode 48).
WebUSB protocol is unit-verified; its live browser handshake is not yet
validated (it needs a human click in Chrome plus the native Android USB
debugging prompt).

> **Transport update (0.2.0-operator.29).** The local bridge described in
> section 4 was removed at the user's request: `server.mjs` is now a static
> file server and WebUSB is the only transport. The consent RPCs and the
> notifications in section 3 are unchanged. See
> `alpha/docs/lex-wireless-2026-11-24.md` for the LAN/Miracast work that
> followed.

## 1. Wi-Fi Direct scan no longer dies

`LexWirelessActivity` only polled `requestPeers` and called `discoverPeers`
once. `dumpsys wifip2p` showed the framework stopping the find exactly 2 minutes
later (`P2P_FIND_STOPPED_EVENT`); after that the screen still claimed
“Buscando…” but nothing was scanned. Tapping “Buscar de nuevo” could also land
on `BUSY`, which the old code printed as a terminal error.

Changes:

- A `BroadcastReceiver` for `WIFI_P2P_PEERS_CHANGED_ACTION`,
  `WIFI_P2P_DISCOVERY_CHANGED_ACTION` and `WIFI_P2P_STATE_CHANGED_ACTION`
  updates the list immediately and re-arms discovery when the framework stops
  it.
- The 4 s tick renews discovery every 25 s and treats `BUSY` as “already
  scanning”; transient `ERROR` retries after 8 s.
- `FLAG_KEEP_SCREEN_ON` while the screen is open (discovery pauses when the
  display sleeps).
- The empty list now says it is still scanning, and devices are only kept when
  their Wi-Fi Display information is enabled.

Verified on the S10 on 2026-11-24: after 150 s of continuous scanning the list
showed two real LG webOS TVs (`UP78006LB`, `UQ79006LA`). `dumpsys wifip2p`
recorded the framework stop (`BLOCK_DISCOVERY` + `P2P_FIND_STOPPED_EVENT`) and a
new `DISCOVER_PEERS` from `org.lambdaproject.lambda` **0.8 s later**, without
user action.

## 2. External display suggestion

`CoreService` (process `:core`, foreground) registers a
`DisplayManager.DisplayListener`. When a display with `FLAG_PRESENTATION`
arrives and is on, it posts one high-importance notification per display:

- Title: **Pantalla externa detectada**
- Text: **¿Abrir LEX en <name>?**
- Actions: **Abrir LEX** (opens `LexMonitorActivity`) and **Ahora no**.
- The notification is cancelled when the display is removed.

Verification without a physical monitor used the Developer Options overlay
display:

```sh
adb shell settings put global overlay_display_devices '1280x720/213'
# notification "Pantalla externa detectada" appears
adb shell settings put global overlay_display_devices ''
# notification disappears
```

`dumpsys display` confirmed the overlay carried `FLAG_PRESENTATION`; launching
`LexMonitorActivity` from that state created the `Presentation` and started the
desktop (noVNC WebView on 6080) — so mode 2's display path is exercised, but a
real USB-C/DisplayPort monitor remains untested.

## 3. Connection consent for the web dashboard

The dashboard now asks the phone before it starts talking, and the phone can
remember the host.

### Core RPCs (typed, same allowlist rules as the rest)

| Method | Payload | Result |
| --- | --- | --- |
| `connect` | `{"host":"…"}` | `trusted` if remembered; otherwise creates/refreshes a prompt and returns `pending`; returns `allowed`/`denied` after a decision. |
| `connect_status` | `{"host":"…"}` | Read-only state: `none`, `pending`, `allowed`, `denied`, `trusted`. Never prompts. |
| `connect_forget` | `{"host":"…"}` | Removes the remembered host and clears its decision. |

Host names are trimmed and validated: 1–64 characters from letters, digits,
space, `. _ ( ) [ ] -`. One-time approvals live in memory for the process;
**Permitir siempre** persists the name in Core's private preferences
(`trusted_hosts`). A pending prompt older than 2 minutes is refreshed on the
next `connect` call.

The prompt is a notification with three actions; the text is exactly
`<host> quiere conectarse a este teléfono. ¿Permitir?`. Verified on the S10:

- The shade showed `kali-prueba quiere conectarse a este teléfono. ¿Permitir?`
  with **Permitir / Permitir siempre / Denegar**.
- Tapping **Permitir siempre** flipped `connect_status` to `trusted`, removed
  the notification and persisted the host.
- A second host was denied through the action and reported `denied`.
- `connect_forget` removed the first host; `connect_status` returned `none`.

This is a consent/UX gate, not a new cryptographic boundary: any host already
authorized by Android's USB-debugging dialog can call Core directly, and the
allowlist key is the display name the page sends.

## 4. Web dashboard: Connect gate and WebUSB

`alpha/web/lex-dashboard` gained a connection card:

- Host name field, prefilled with the bridge machine's `os.hostname()` (or the
  browser platform in WebUSB-only mode).
- **Connect · local bridge** calls `POST /api/connect`, then polls
  `GET /api/connect?host=…` until the phone decides.
- **Connect over USB** runs `public/adb-webusb.js` in the browser:
  `navigator.usb.requestDevice` for the ADB interface
  (`class 0xff / subclass 0x42 / protocol 0x01`), the ADB handshake, then
  `content call … --method connect`. Android shows its own “Allow USB
  debugging?” prompt for this browser's RSA key the first time.
- Data, jobs, screenshot and Start/Stop stay disabled until the phone approves;
  the page shows `Connected · <host> approved on the phone.`

Web Serial cannot carry ADB — the phone exposes a vendor-specific USB
interface, not a serial port — so the in-browser transport uses WebUSB.

### In-browser ADB client (`public/adb-webusb.js`)

Dependency-free, ~450 lines: packet framing and magic, CNXN negotiation,
AUTH token/signature/public-key flow, RSA key generation through WebCrypto
(RSASSA-PKCS1-v1_5, SHA-1), Android's 524-byte `RSAPublicKey` blob (n0inv and
R² mod n computed with BigInt), and one shell stream at a time
(OPEN/WRTE/OKAY/CLSE). The key is kept in `localStorage`.

Known limits: USB mode can start/stop the desktop and read telemetry, but it
cannot create `adb forward`, so the live noVNC iframe requires the local
bridge. If the local adb server owns the interface, claiming fails and the page
explains that `adb kill-server` is needed.

### Verified

- Node protocol self-test (`node -e "require('./public/adb-webusb.js').selfTest()"`):
  CNXN byte order, 524-byte key blob, `n0inv·n0 ≡ −1 (mod 2^32)`, exponent and
  Core result parsing all pass.
- Node WebCrypto produced a SHA-1 PKCS#1 v1.5 signature that
  `crypto.verify('sha1')` accepts — the same signature adb requires.
- The bridge flow was exercised end-to-end in Chromium (Playwright) against the
  S10: connect with `fsociety` → trusted → metrics, jobs and screenshot loaded
  → Start produced **Desktop live**, the noVNC iframe and the Fullscreen link
  → the state survived past the 30 s notification probe → Stop removed the
  forwards and returned the empty state.

### Pending

- Live WebUSB handshake: device chooser, native USB debugging prompt and the
  consent prompt in a real Chrome window (needs a human click). Test with
  `adb kill-server` so the browser can claim the interface.
- Platform notes (udev rules on Linux, WinUSB driver on Windows) should be
  verified on those hosts.

## 5. Desktop state probe fix

`desktop_status`/`probeDesktop` used a single 250 ms loopback connect per port.
Under load after a start, a missed connect marked a live desktop as stopped.
Now each check retries three times and the state is cleared only after two
consecutive failed checks; a start job's freshest state is not lost to one
transient timeout.

## Limits

- Physical USB-C monitor and real Miracast connection remain untested.
- The allowlist trusts a display name, not a key fingerprint.
- `connect_forget` has no Operator UI yet.

## Update (0.2.0-operator.29)

- The bridge transport and every `/api/*` endpoint were deleted; the page is
  static and connects only over WebUSB.
- `claimInterface` now tries every ADB-candidate interface three times, keeps
  the granted device for a retry without the chooser, and prints the OS-specific
  `adb kill-server` recovery. `test-adb-claim.cjs` covers the retry and the
  error text.
- Screenshots moved to the WebUSB link with a binary `shell:` call
  (`screencap -p`), so the device-screen panel still works without any bridge.
- Because a browser cannot open host TCP ports, the embedded noVNC iframe and
  the Fullscreen link were removed; the desktop card now only starts/stops the
  typed job and points to `adb forward tcp:6080 tcp:6080` for watching it.
- The VNC password is no longer rendered anywhere in the page.
