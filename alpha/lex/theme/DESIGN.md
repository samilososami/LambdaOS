# LEX desktop visual system

Contract for the LambdaOS desktop (chroot Kali XFCE 4.20, arm64, no compositor).
Every theme part implements this file; do not invent a second palette.

## Canvas

Primary 2280x1080 landscape (S10 mode 1). Must also work at 1280x720 and
3040x1440. Anything positioned by pixel must read `LEX_GEOMETRY` (WxH), default
`2280x1080`.

## Tokens

| token | value | use |
|---|---|---|
| bg-0 | #08090A | behind the wallpaper |
| bg-1 | #101112 | window background, panel base |
| bg-2 | #17191B | titlebar, menus, dock |
| bg-3 | #1F2225 | hover |
| bg-4 | #2A2E33 | pressed / selected row |
| line | #26292D | 1px borders |
| line-soft | rgba(255,255,255,0.06) | separators on dark |
| fg | #E9E9E9 | primary text and icons |
| fg-dim | #9AA0A6 | secondary text |
| fg-muted | #6B7075 | disabled |
| accent | #E9E9E9 | brand is monochrome; selection uses fg, never color |
| danger | #D9534F | close/stop affordance on hover only |
| shadow | rgba(0,0,0,0.45) | dock and popups |

No gradients except the approved wallpaper. No blur. Transitions <= 120ms;
without a compositor most states are instant. Rounded corners max 18px.

## Typography

Stack: `Cantarell`, `DejaVu Sans`, sans-serif. Both ship in the chroot; Inter is
not installed anywhere in the system, so it must not be named. Monospace:
`Fira Code`, `DejaVu Sans Mono`. Bar/menu/title 11.5px; body 12.5px; terminal
12.5px. Vendored fonts, if any, must ship their licence.

## Top bar (Kali-like, subtle)

- 72px tall, full width, bg #141516 at 98%, 1px bottom border line-soft, 10px
  horizontal padding, 40px icons.
- Left: Lambda menu (40px glyph), separator.
- Centre-left: window buttons with labels (tasklist), max 50% of the width.
- Right: CPU/RAM/battery status (genmon), separator, clock with date and time
  (`%a %d %b` above `%H:%M`).

## Bottom dock (macOS-like, centred)

- Horizontally centred, 12px above the bottom edge; height 76px; inner padding
  14px; icon 48px; gap 10px; radius 18px; bg #17191B at 88%; 1px border
  line-soft; soft shadow.
- Active app: 4px dot under the icon, fg. Hover: bg-3 rounded 12px.
- No magnification, no bounce, no animation.
- Sizing rule: fixed pixel width computed from the number of launchers; a
  background image is generated at exactly that size so corners never stretch.

## Windows

- Titlebar 32px; active bg #17191B, inactive #121416; 1px border line; top
  corners radius 8px; title 11.5px semibold fg (inactive fg-dim).
- Buttons 14px glyphs; hover bg-3; close hover danger.
- Menus/popups: bg #17191B, border line, radius 8px, item 26px, padding 10px,
  selected bg-4, text #FFFFFF.
- Scrollbars 8px: thumb #2A2E33, hover #3A3F45, no steppers.

## Icons

- Base grid 24, stroke 1.6, round caps and joins, no fills except tiny details.
- App icons: 48px squircle tile (#1C2023, radius 12) with a 24px monochrome
  glyph (#E9E9E9); secondary detail may use #9AA0A6. No colour.
- Panel: 40px glyphs at 1.4 stroke inside the 72px bar; the dock keeps 48px
  icons at 1.4 stroke.
- The theme inherits `Adwaita` and `hicolor` for anything not drawn.

## Ownership

`wallpaper/ icons/ gtk/ xfwm4/ panel/ applications/ preview/` one agent per
subtree. Every subtree has `apply.sh`: POSIX sh, idempotent, runs inside the
chroot as root, no network, no apt. Previews are 2280x1080 PNGs in `preview/`.
