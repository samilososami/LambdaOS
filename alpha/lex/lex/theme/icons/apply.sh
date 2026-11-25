#!/bin/sh
# Install the LambdaOS icon theme inside the Kali chroot. Root, offline,
# idempotent. A missing icon-cache tool must never fail the session.
set -eu
cd "$(dirname "$0")"

DEST=/usr/share/icons/LambdaOS
if [ "$(id -u)" -ne 0 ]; then
  echo "apply.sh: must run as root inside the chroot" >&2
  exit 1
fi

mkdir -p "$DEST"
cp -a LambdaOS/. "$DEST"/

if command -v gtk-update-icon-cache >/dev/null 2>&1; then
  gtk-update-icon-cache -f -t "$DEST" >/dev/null 2>&1 ||
    gtk-update-icon-cache -f "$DEST" >/dev/null 2>&1 || true
fi
echo "apply.sh: LambdaOS icons installed in $DEST"
