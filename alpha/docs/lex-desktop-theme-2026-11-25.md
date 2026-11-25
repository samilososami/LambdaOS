# LEX desktop: slim session, custom theme and native viewer

Date: 2026-11-25. Device: SM-G973F (`beyond1lte`), Kali arm64 chroot at
`/data/local/lex/rootfs`, Xvnc 1.15 at 2280x1080, Operator `0.2.0-operator.31`.

## Decision

Three independent reviews (keep XFCE slim / switch to openbox / switch to
Wayland) and a cross-examination round reached:

- **Wayland (labwc + wayvnc)**: not lighter, needs ZRLE in our client and a
  measured spike; deferred.
- **Openbox + custom bar**: real delta vs slim XFCE is ~25-35 MB, within the
  estimate error, and costs a new WM theme/menu. Conceded by its own advocate.
- **Slim XFCE**: chosen. Keep `xfwm4` + `xfce4-panel`; drop `xfce4-session`,
  `xfdesktop4`, `xfce4-power-manager`, `xfce4-settings`; add `xsettingsd`,
  `dunst` (history off) and `feh` for the wallpaper. A separate GTK dock with
  XShape gives the macOS-like floating pill that a panel cannot clip.

## Measured on the S10 (PSS, idle, Xvnc running)

| process | PSS |
|---|---|
| Xtigervnc | 52.9 MB |
| xfce4-panel (top bar) | 36.1 MB |
| xfwm4 | 30.7 MB |
| dock.py (GTK3) | 30.1 MB |
| dunst | 2.1 MB |
| xsettingsd | 0.6 MB |
| vncconfig + dbus | 1.7 MB |
| **total** | **~154 MB** |

Desktop without the VNC server: ~101 MB. `MemAvailable` 4.74 GB. Before the
trim the same tree measured ~236 MB RSS for the XFCE daemons alone.

## What exists

- `alpha/lex/theme/`: `DESIGN.md` (contract), `wallpaper/`, `icons/` (theme
  `LambdaOS`, 38 SVGs + 319 PNGs), `gtk/` + `xfwm4/` (`LambdaOS` themes),
  `panel/` (top bar XML + rounded dock background), `applications/` (8 desktop
  entries), `dock/` (`dock.py`, XShape pill, Wnck active dots), `apply.sh`
  (runs inside the session bus before the session starts).
- `alpha/lex/start.sh`: slim session. No session manager; xsettingsd, feh,
  xfwm4, xfce4-panel, dunst and the dock, all in one `dbus-launch` session.
  `LEX_GEOMETRY` reaches the chroot; dunst/xsettingsd configs are rewritten
  each start.
- `alpha/lex/deploy-theme.sh`: pushes `theme/` into the chroot rootfs.
- `alpha/apps/Lambda`: `LexRfbClient` (RFB 3.8, VncAuth, Hextile/Raw/CopyRect),
  `LexDesktopView` (framebuffer, touch, IME), `LexActivity` rewritten without
  WebView. Version `0.2.0-operator.31`, signed with the LambdaOS releasekey.
- Host tests in `alpha/lex/test/` (DES KAT, Hextile fixtures, fake RFB server,
  pointer/key bytes) all pass.

## Verified

- Desktop starts with the slim session and the theme applied; wallpaper, top
  bar (Lambda menu, workspace thumbnails, tasklist, clock), dock pill and
  active-app dot render correctly at 2280x1080.
- `dump_xsettings` reports `Net/ThemeName=LambdaOS`, `Net/IconThemeName=LambdaOS`,
  `Gtk/FontName=Cantarell 10`, `Gtk/MonospaceFontName=Fira Code 10`.
- Thunar renders with the dark GTK theme and the monochrome folder icons.
- A synthetic click (`xdotool`) on the top-bar Lambda mark opens Whisker.
- Dock clicks work on device: window `899,992,482x76`; clicking the first icon
  launched qterminal (one process), a second click activated the existing window
  without spawning another, the active dot appeared, and the pill stayed above
  the maximised terminal. The earlier "click does nothing" was a test
  coordinate outside the pill, not a dock bug; the input shape is now set
  explicitly anyway.

## Pending / known defects

- **Native viewer on device**: not yet exercised. The S10 was locked, so
  `LexActivity` stayed paused and the RFB client correctly disconnected; no
  ESTABLISHED connection to 5901. Needs an unlocked device.
- Whisker category icons still come from Kali's coloured set (not monochrome).
- Top-bar tasklist hides labels; workspace names were replaced by thumbnails.
- No desktop icons by design (`xfdesktop` removed).
- `GTK2` theme is syntax-checked only; no GTK2 app installed.
- CSD apps (libadwaita/GTK4) draw their own headerbar under xfwm4; not used by
  the current app set.

## Apply

```sh
adb push alpha/lex/start.sh /data/local/lex/start.sh
sh alpha/lex/deploy-theme.sh          # needs root adb
adb shell 'sh /data/local/lex/stop.sh; sh /data/local/lex/start.sh'
```

Packages added inside the chroot: `feh`, `dunst`, `xsettingsd`, `scrot`,
`xdotool` (the last two only for capture/tests).
