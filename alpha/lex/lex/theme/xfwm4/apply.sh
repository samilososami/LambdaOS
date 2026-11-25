#!/bin/sh
# Install the LambdaOS XFWM4 theme. POSIX sh; run as root inside the chroot,
# idempotent, no network. DESTDIR is honoured for staging.
set -eu

here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
dest="${DESTDIR:-}/usr/share/themes/LambdaOS"

mkdir -p "$dest"
rm -rf "$dest/xfwm4"
cp -a "$here/LambdaOS" "$dest/xfwm4"

# Set one xfconf key; create it when absent, and never fail the session when
# the property or the session bus is missing.
set_key() {
  xfconf-query -c "$1" -p "$2" -s "$4" 2>/dev/null ||
    xfconf-query -c "$1" -p "$2" -n -t "$3" -s "$4" 2>/dev/null ||
    true
}

set_key xfwm4 /general/theme string "LambdaOS"
set_key xfwm4 /general/title_font string "Cantarell Bold 10"
set_key xfwm4 /general/title_alignment string "center"
set_key xfwm4 /general/button_layout string "|HMC"
set_key xfwm4 /general/box_move bool "false"
set_key xfwm4 /general/box_resize bool "false"
set_key xfwm4 /general/double_click_action string "maximize"
