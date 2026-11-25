# LambdaOS LEX dashboard

A static web dashboard for the S10. Chrome talks to the phone directly over
**WebUSB**; this repository's Node server only serves files and never runs adb,
holds device state or exposes an API.

Before it reads anything, the page asks the phone for consent: Lambda Core posts
a notification — “<host> quiere conectarse a este teléfono. ¿Permitir?” — with
**Permitir**, **Permitir siempre** and **Denegar**. “Permitir siempre” remembers
the name in Core's private preferences.

## Why WebUSB and not Web Serial

ADB runs over a vendor-specific USB interface (class `0xff`, subclass `0x42`,
protocol `0x01`). That is not a serial port, so Web Serial cannot open it.
`public/adb-webusb.js` is a dependency-free ADB client for that interface: it
opens the interface, authenticates with an RSA key held in `localStorage`
(Android shows its normal “Allow USB debugging?” prompt the first time), and
implements CNXN/AUTH/OPEN/WRTE/OKAY/CLSE for `shell:` commands.

## Requirements

- Chrome or Edge (WebUSB), on `http://127.0.0.1:…`, `http://localhost:…` or
  HTTPS. A plain-HTTP LAN address is not a secure context and has no WebUSB.
- The S10 plugged in by USB with USB debugging enabled.
- The computer's adb server stopped while the browser owns the interface:

  ```sh
  adb kill-server
  ```

- Node.js 18+ only if you use the bundled static server. Any static host works.
- Lambda Core `0.2.0-operator.29` or newer on the phone.

## Run

```sh
cd alpha/web/lex-dashboard
node server.mjs --port 8881
```

Open <http://127.0.0.1:8881>. Use `--host 0.0.0.0` only if you understand that
the page then needs HTTPS for WebUSB, and that you are publishing a page with no
authentication. Stop with Ctrl-C.

Checks:

```sh
node --check server.mjs
node --check public/app.js
node --check public/adb-webusb.js
node test-adb-claim.cjs
node -e "require('./public/adb-webusb.js').selfTest()"
```

`test-adb-claim.cjs` covers interface selection (0xff/0x42/0x01 first), the
claim retry after adb releases the interface, the `adb kill-server` error text
and the “no ADB interface” refusal. `selfTest()` covers the ADB header, the
524-byte RSA public-key blob, `n0inv · n0 ≡ −1 (mod 2^32)`, the Core result
parser and shell quoting.

## Connecting

1. Plug in the S10; unlock it.
2. On the computer: `adb kill-server`.
3. Press **Connect over USB** and pick the phone in Chrome's chooser. The first
   time, Chrome asks for the device and Android asks to allow USB debugging.
4. The page calls Core `connect` with this device's name. Approve the LEX
   notification on the phone — once, or “Permitir siempre”.
5. If the claim fails because adb still owns the interface, the page says so
   and asks you to run `adb kill-server`; press **Connect** again. Chrome keeps
   the USB permission until the cable is replugged, so there is no second
   chooser.

On Linux, add a udev rule for vendor `04e8` with `TAG+="uaccess"`. On Windows,
the ADB interface must be bound to WinUSB (Zadig); Samsung's own driver blocks
WebUSB.

## What it shows

- **Device pill**: model, Android release, Core worker availability.
- **Desktop card**: start/stop the Kali XFCE session through the typed
  `system.desktop` Core job and show its state. The live noVNC view is not
  embedded: WebUSB cannot create host TCP forwards. To watch it, on a computer
  with adb access run `adb forward tcp:6080 tcp:6080` and open
  `http://127.0.0.1:6080/vnc.html`. The VNC password is never displayed.
- **Metrics**: battery, Wi-Fi, `/data`, Core version, worker.
- **Jobs**: the 10 most recent Core jobs.
- **Device screen**: a `screencap -p` PNG taken over the USB link, refreshed on
  demand.

The page polls every 5 seconds and pauses while the tab is hidden. It reads one
ADB stream at a time; USB mode runs the metrics, desktop and job reads
sequentially.

## Security posture

- The page is static: no server API, no adb invocation, no stored credentials.
- Every phone action goes through Lambda Core's typed `ContentProvider` RPC, or
  through the page's own ADB `shell:` stream. Start/Stop are only triggered by
  an explicit click after the consent gate.
- The consent allowlist is a UX gate, not a cryptographic identity: any host
  Android has already authorized for USB debugging can call Core directly.
  WebUSB adds the browser's own RSA key and Android's native prompt.
- Core exposes `connect_forget` over the typed RPC to revoke a remembered host;
  there is no Operator UI for it yet.
- The static server uses a restrictive CSP, `nosniff`, `no-referrer`,
  `X-Frame-Options: DENY` and `frame-ancestors 'none'`, and loads no external
  assets.

## Limits

- No noVNC embed and no adb forward over WebUSB: a browser cannot listen on a
  host TCP port.
- One phone at a time. The chooser filters on the ADB interface and Samsung's
  vendor id `04e8`.
- The browser key is a real ADB key in `localStorage`; clearing site data
  revokes it and Android will ask again.
- `screencap` captures whatever is on screen, including notifications or the
  lockscreen. Nothing is stored by the page or the server.
- The page cannot run `adb kill-server` for you; only you can stop the host adb
  server.
- **ROM issue found during the live test:** `adbd` loads keys only from
  `/adb_keys` (a product symlink), while the framework writes an approved key
  to `/data/misc/adb/adb_keys`. Accepting Android's prompt authorizes the
  session, but the key is not reloaded after `adbd` restarts. Fixing the ROM to
  load the user key file (AOSP behaviour) removes the repeated prompt. Accepting
  the prompt also resets the USB function; the page reopens the link once and
  retries the first Core call when it sees the cancelled transfer.

## Validation performed (2026-11-24, Operator 0.2.0-operator.29)

- `node --check` on `server.mjs`, `public/app.js` and `public/adb-webusb.js`.
- `test-adb-claim.cjs`: ADB interface chosen over other vendor interfaces; claim
  succeeds on the third attempt after two simulated `EBUSY` failures; failure
  text mentions `adb kill-server`; a non-ADB device is refused.
- `selfTest()`: protocol math for the ADB public key and Core result parsing.
- Chromium (Playwright) against the static server on `:8881`: no console errors;
  the bridge button, embedded iframe and Fullscreen link are gone; host defaults
  to the platform name; the error path re-enables **Connect over USB** and keeps
  the host editable.
- Real Chrome on `http://127.0.0.1:8881`: page and scripts load; `navigator.usb`
  is available. A live link still needs the human device chooser (and Android's
  USB-debugging prompt), so the full connect was not completed headlessly.
- Earlier consent verification (Operator .28) still applies: the real shade
  showed the prompt and **Permitir siempre** persisted the name.

## History

The first version of this dashboard used a local bridge: the Node server shelled
out to adb and exposed `/api/*`, and the page embedded noVNC when 6080 was
forwarded. It worked, but the connection could not be made from a plain browser,
which is what the LEX page is for. On 2026-11-24 the bridge transport and its
endpoints were removed; the server is now a static file server and WebUSB is the
only transport. The old `/api/*` behaviour is not documented here because it no
longer exists.
