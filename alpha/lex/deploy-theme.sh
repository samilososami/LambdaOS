#!/bin/sh
# Push the LEX theme into the S10's chroot rootfs, where start.sh applies it.
# Development-only helper: needs an authorized USB device and root adb (the
# project's adbd already runs with root on the lab device).
set -eu
cd "$(dirname "$0")"
adb wait-for-device
adb root >/dev/null 2>&1 || true
adb wait-for-device
DEST=/data/local/lex/rootfs/usr/local/share/lambdaos-theme
adb shell "mkdir -p $DEST"
tar -C theme -cf - --exclude preview . | adb shell "tar -C $DEST -xf -"
adb shell "chmod -R a+rX $DEST; find $DEST -name apply.sh -exec chmod 755 {} +"
adb shell "ls -1 $DEST"
