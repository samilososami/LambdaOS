#!/bin/sh
# Apply the LambdaOS LEX theme inside the chroot, before startxfce4.
# Runs with DISPLAY and a DBus session bus; idempotent. A failing part must
# never abort the desktop session.
set -u
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

for part in icons gtk xfwm4 wallpaper panel applications; do
  script="$DIR/$part/apply.sh"
  [ -f "$script" ] || continue
  sh "$script" || echo "lex-theme: $part failed" >&2
done
