#!/bin/sh
# Install the LambdaOS GTK theme. POSIX sh; run as root inside the chroot,
# idempotent, no network. DESTDIR is honoured for staging.
set -eu

here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
dest="${DESTDIR:-}/usr/share/themes/LambdaOS"

mkdir -p "$dest"
rm -rf "$dest/gtk-3.0" "$dest/gtk-2.0"
cp -a "$here/LambdaOS/gtk-3.0" "$dest/gtk-3.0"
cp -a "$here/LambdaOS/gtk-2.0" "$dest/gtk-2.0"
cp -a "$here/LambdaOS/index.theme" "$dest/index.theme"

# Point the session at the theme and the DESIGN.md font stack. Set an
# existing key, create it when absent; a missing session bus, xfconfd or
# property must never abort the desktop session.
set_key() {
  xfconf-query -c "$1" -p "$2" -s "$4" 2>/dev/null ||
    xfconf-query -c "$1" -p "$2" -n -t "$3" -s "$4" 2>/dev/null ||
    true
}

set_key xsettings /Net/ThemeName string "LambdaOS"
set_key xsettings /Gtk/FontName string "Cantarell 10"
set_key xsettings /Gtk/MonospaceFontName string "Fira Code 10"
