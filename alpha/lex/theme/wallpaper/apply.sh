#!/bin/sh
# LEX wallpaper apply: run inside the chroot as root from the X session, after
# DBus is up and with DISPLAY set. Idempotent; no network, no apt.
set -eu

here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
dst=/usr/share/backgrounds/lambdaos

# Only the three generated canvases are shipped; unknown geometry falls back
# to the primary one.
geom=${LEX_GEOMETRY:-2280x1080}
case "$geom" in
  2280x1080|1280x720|3040x1440) ;;
  *) geom=2280x1080 ;;
esac

install -d -m 0755 "$dst"
for f in lambdaos-2280x1080.png lambdaos-1280x720.png lambdaos-3040x1440.png; do
  [ -f "$here/$f" ] || { echo "apply.sh: missing $here/$f" >&2; exit 1; }
  install -m 0644 "$here/$f" "$dst/$f"
done

# XFCE creates one backdrop property per monitor/workspace, and not all of them
# exist in every session. Set every last-image/image-style that is listed; one
# absent or unsettable entry must not abort the rest.
xfconf-query -c xfce4-desktop -l 2>/dev/null | while IFS= read -r prop; do
  case "$prop" in
    */last-image) xfconf-query -c xfce4-desktop -p "$prop" -s "$dst/lambdaos-$geom.png" 2>/dev/null || true ;;
    */image-style) xfconf-query -c xfce4-desktop -p "$prop" -s 5 2>/dev/null || true ;;
  esac
done

exit 0
