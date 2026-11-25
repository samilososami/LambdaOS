#!/usr/bin/env bash
# Provision the LEX Kali chroot on the development S10. Host script (kali user).
# Downloads and verifies the NetHunter rootfs, extracts it as root, installs the
# device scripts and, if the rootfs lacks them, noVNC/websockify inside the chroot.
set -euo pipefail
base=$(cd "$(dirname "$0")/.." && pwd)
phone=${ANDROID_SERIAL:?set ANDROID_SERIAL to the target device}
arch=arm64
name="kali-nethunter-rootfs-full-$arch.tar.xz"
cache="$base/out/lex"
tarball="$cache/$name"
url="https://kali.download/nethunter-images/current/rootfs/$name"

mkdir -p "$cache"
if [ ! -f "$tarball" ]; then
  curl -fL --retry 3 -o "$tarball" "$url"
fi
curl -fsS -o "$cache/SHA256SUMS" "https://kali.download/nethunter-images/current/rootfs/SHA256SUMS"
( cd "$cache" && grep " $name\$" SHA256SUMS | sha256sum -c - )

adb -s "$phone" get-state >/dev/null
adb -s "$phone" root >/dev/null 2>&1 || true
adb -s "$phone" wait-for-device
test "$(adb -s "$phone" shell getprop ro.product.device | tr -d '\r')" = beyond1lte

adb -s "$phone" shell 'mkdir -p /data/local/lex; chmod 700 /data/local/lex'
if ! adb -s "$phone" shell '[ -f /data/local/lex/rootfs/.lex-complete ]'; then
  if adb -s "$phone" shell '[ -x /data/local/lex/rootfs/bin/sh ]'; then
    # Older complete install: adopt it instead of re-pushing 1.8 GB.
    adb -s "$phone" shell 'touch /data/local/lex/rootfs/.lex-complete'
  else
    adb -s "$phone" push "$tarball" /data/local/lex/rootfs.tar.xz
    adb -s "$phone" shell 'cd /data/local/lex && tar -xJf rootfs.tar.xz && mv kali-arm64 rootfs && rm -f rootfs.tar.xz && touch rootfs/.lex-complete'
  fi
fi
adb -s "$phone" push "$base/lex/start.sh" /data/local/lex/start.sh
adb -s "$phone" push "$base/lex/stop.sh" /data/local/lex/stop.sh
adb -s "$phone" shell 'mkdir -p /data/local/lex/rootfs/usr/share/backgrounds/lambdaos'
adb -s "$phone" push "$base/lex/lambdaos-wallpaper.png" /data/local/lex/rootfs/usr/share/backgrounds/lambdaos/lambdaos.png
adb -s "$phone" shell 'chmod 644 /data/local/lex/rootfs/usr/share/backgrounds/lambdaos/lambdaos.png; ln -sfn /usr/share/backgrounds/lambdaos/lambdaos.png /data/local/lex/rootfs/usr/share/backgrounds/kali-16x9/default; chmod 755 /data/local/lex/start.sh /data/local/lex/stop.sh'
adb -s "$phone" shell "sed -i 's|/usr/share/backgrounds/kali-16x9/default|/usr/share/backgrounds/lambdaos/lambdaos.png|g' /data/local/lex/rootfs/etc/xdg/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml 2>/dev/null || true"

if ! adb -s "$phone" shell '[ -x /data/local/lex/rootfs/usr/bin/websockify ] && [ -d /data/local/lex/rootfs/usr/share/novnc ]'; then
  echo "Installing noVNC/websockify inside the chroot…"
  trap 'adb -s "$phone" shell "sh /data/local/lex/stop.sh" >/dev/null 2>&1 || true' EXIT
  adb -s "$phone" shell 'sh /data/local/lex/start.sh >/dev/null 2>&1 || true'
  adb -s "$phone" shell 'chroot /data/local/lex/rootfs /bin/sh -c "export DEBIAN_FRONTEND=noninteractive; apt-get update -qq && apt-get install -y -qq --no-install-recommends novnc websockify"'
  adb -s "$phone" shell 'sh /data/local/lex/stop.sh >/dev/null 2>&1 || true'
  trap - EXIT
fi
echo "LEX rootfs ready at /data/local/lex/rootfs"
