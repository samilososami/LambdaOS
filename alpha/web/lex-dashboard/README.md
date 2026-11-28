# LambdaOS LEX dashboard

A static page for opening the S10's LEX desktop in a Chromium browser. Chrome
talks to the phone directly over **WebUSB**; the bundled Node server only
serves files. It never runs adb, holds device state or exposes an API.

## How the desktop works

The page carries its own ADB client (`public/adb-webusb.js`). It claims the
phone's vendor-specific USB interface, authenticates with an RSA key kept in
`localStorage`, and speaks CNXN/AUTH/OPEN/WRTE/OKAY/CLSE. For the desktop it
opens one long-lived shell v2 link:

```
shell,v2,raw:nc 127.0.0.1 5901
```

TigerVNC on the phone listens on loopback only and uses VncAuth. The page
reads the password from `/data/local/lex/vncpass` over adb (this needs adb
root), opens the link, and hands the channel to the vendored noVNC RFB client:
`public/lex-desktop.js` imports `public/novnc/core/rfb.js` and constructs
`RFB(container, channel, …)`.

- Mouse and keyboard are RFB pointer and key events; stdin travels in shell v2
  envelopes with id 0, stdout comes back with id 1.
- The cursor is drawn by the client. Moving it does not wait for server
  damage; the pointer events still go to the phone.
- Frame updates are push, not polling. noVNC enables continuous updates when
  the server offers them.
- Shell v2 packets are routed per link, so a concurrent `echo` and the desktop
  stream do not steal each other's packets. USB writes are serialized because
  Chrome rejects overlapping `transferOut` calls.

## Flow

1. **connect** — one button. WebUSB only, so Chromium-based browsers on
   `localhost`/`127.0.0.1` or HTTPS.
2. **authorize** — the page retries `echo ok` for up to 75 seconds until the
   phone's “Allow USB debugging?” prompt is accepted.
3. **identify** — `getprop ro.build.flavor` must contain `lambda` and
   `beyond1lte`. Anything else stops with “that device is not an S10 running
   LambdaOS”.
4. **consent** — the page runs
   `am start -n org.lambdaproject.lambda/.ConsentActivity --es token <uuid>`.
   The phone shows a fullscreen Accept/Reject request. The activity writes
   `/sdcard/Android/data/org.lambdaproject.lambda/files/consent-<token>` with
   `accepted` or `rejected`, and the page polls that file. The token is new on
   every attempt: consent is per request, not remembered.
5. **start** — Core `desktop_status`; if the desktop is stopped, the page
   submits the typed `system.desktop` job with `action: start` and polls the
   job until it completes.
6. **live** — read the VNC password, open the `nc` stream, attach RFB.

Nothing is read from the screen before an `accepted`.

## Requirements

- Chrome or Edge, in a secure context: `http://localhost`, `http://127.0.0.1`
  or HTTPS. A plain-HTTP LAN address has no WebUSB.
- The S10 connected by USB, unlocked, with USB debugging enabled and this
  computer authorized.
- The computer's adb server stopped while the browser owns the interface:

  ```sh
  adb kill-server
  ```

- **adb root on the phone**, to read `/data/local/lex/vncpass` (the file is
  root-owned, mode 600). Without it the page shows the error and asks for root.
- The LEX desktop installed and startable through Lambda Core
  `system.desktop`. The page starts it when `desktop_status` says it is not
  running.
- Linux: a udev rule for vendor `04e8` with `TAG+="uaccess"`. Windows: the ADB
  interface must be bound to WinUSB (Zadig); Samsung's own driver blocks
  WebUSB.

## Run

```sh
cd alpha/web/lex-dashboard
node server.mjs
```

It serves `public/` on <http://127.0.0.1:7788>; open
<http://localhost:7788>. `--port` / `-p` (also `PORT`) changes the port.
`--host` (also `HOST`) defaults to `127.0.0.1`. The server answers GET and
HEAD only, refuses paths outside `public/`, and sends a restrictive CSP,
`nosniff`, `no-referrer` and `X-Frame-Options: DENY`. It has no other
behaviour and no dependencies.

Any static host works too, as long as the page is served from a secure
context. Binding `--host 0.0.0.0` publishes an unauthenticated page and still
needs HTTPS for WebUSB.

## Tests

```sh
node test-adb-mock.mjs
```

Drives the real `public/adb-webusb.js` against a scripted adbd and prints one
PASS/FAIL line per check; it exits non-zero on failure. It covers the
handshake, `shell,v2,raw:` framing (stdout id 1, exit status id 3), concurrent
shells not stealing packets, a 63 KB binary frame arriving whole, `openStream`
opening a channel, ordered byte-identical chunk echo, a shell running while
the stream is open, and one close event with code 1000.

Other checks:

```sh
node test-adb-claim.cjs
node -e "require('./public/adb-webusb.js').selfTest()"
```

The first covers ADB interface selection, the claim retry after adb releases
the interface and the `adb kill-server` message. The second covers the ADB
header, the 524-byte RSA public-key blob, `n0inv · n0 ≡ −1 (mod 2^32)`, the
Core result parser and shell quoting.

## Known limits

- Continuous full-screen motion is bounded by the phone's encoder: roughly
  100–300 ms per full frame, a 3–6 fps class for sustained changes. Small
  updates are much faster.
- Desktop geometry is 2280 × 1080. The page keeps that aspect ratio, scales
  the RFB viewport to fit and letterboxes in black in fullscreen.
- The VNC password is unavailable without adb root.
- TigerVNC is bound to `127.0.0.1:5901`. The adb stream is the only path to it;
  a browser cannot create host TCP forwards.
- One phone at a time. The chooser filters on the ADB interface and Samsung's
  vendor id `04e8`.
- The browser's RSA key is a real ADB key in `localStorage`; clearing site data
  revokes it and Android asks again.
- The page cannot run `adb kill-server`; only the person at the computer can
  free the interface.
- The first frame after connect arrives when the encoder produces it;
  latency varies with desktop load.

## Third-party

`public/novnc/` is a vendored noVNC core (`core/`, plus `vendor/pako/`),
licensed under MPL-2.0. `public/novnc/LICENSE.txt` records the source (the
Debian package's `/usr/share/novnc`) and the exceptions: `core/crypto/des.js`
is BSD 2-clause, and pako is MIT with zlib-licensed zlib port files. The files
are unmodified. `public/lex-desktop.js` is LambdaOS code, not part of noVNC.

The page is static, so the only server traffic is fetching files. The ADB key
is generated in the browser and stored in `localStorage`; the VNC password is
read over USB and stays in the page.
