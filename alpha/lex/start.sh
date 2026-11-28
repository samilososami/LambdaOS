#!/system/bin/sh
# LEX desktop (mode 1): Kali chroot + XFCE over TigerVNC on loopback.
# Runs as root from RadioWarden; idempotent; prints status JSON.
set -eu
PATH=/system/bin:/system/xbin
BASE=/data/local/lex
MNT=$BASE/rootfs
DISP=1
# 19:9 like the S10 landscape panel (3040x1440), so noVNC fills the width.
GEOM=${LEX_GEOMETRY:-2280x1080}
echo "$GEOM" | grep -qE '^[0-9]{3,4}x[0-9]{3,4}$' || GEOM=2280x1080

# Any failure before the desktop is confirmed must not leave mounts or
# daemons behind. stop.sh is idempotent.
cleanup(){ rc=$?; if [ "$rc" -ne 0 ]; then sh "$BASE/stop.sh" >/dev/null 2>&1 || true; fi; }
trap cleanup EXIT

mounted() { grep -q " $1 " /proc/mounts 2>/dev/null; }

mkdir -p "$MNT/proc" "$MNT/sys" "$MNT/dev" "$MNT/dev/pts" "$MNT/dev/shm" "$MNT/run" "$MNT/tmp"
# A killed Xvnc can leave a stale lock; vncserver would then refuse to start.
XPID=$(cat "$MNT/tmp/.X$DISP-lock" 2>/dev/null | tr -dc '0-9' || true)
if [ -n "$XPID" ] && [ ! -d "/proc/$XPID" ]; then
  rm -f "$MNT/tmp/.X$DISP-lock" "$MNT/tmp/.X11-unix/X$DISP" 2>/dev/null || true
fi
mounted "$MNT/proc"     || mount -t proc proc "$MNT/proc"
mounted "$MNT/sys"      || mount -t sysfs sysfs "$MNT/sys"
mounted "$MNT/dev"      || mount -o bind /dev "$MNT/dev"
mounted "$MNT/dev/pts"  || mount -t devpts devpts "$MNT/dev/pts"
mounted "$MNT/dev/shm"  || mount -t tmpfs -o mode=1777 tmpfs "$MNT/dev/shm"
mkdir -p "$MNT/run" "$MNT/tmp"
printf 'nameserver 1.1.1.1\nnameserver 9.9.9.9\n' > "$MNT/etc/resolv.conf"

# XFCE session; vncconfig keeps the clipboard in sync. TigerVNC 1.15 uses
# ~/.config/tigervnc; a legacy ~/.vnc directory would trigger a failing migration.
VNC=$MNT/root/.config/tigervnc
rm -rf "$MNT/root/.vnc"
mkdir -p "$VNC" "$MNT/root/.config/autostart"
cat > "$MNT/root/.config/autostart/xfce4-screensaver.desktop" <<'HIDDEN'
[Desktop Entry]
Type=Application
Name=xfce4-screensaver
Hidden=true
HIDDEN
# The slim session has no xfsettingsd/xfce4-notifyd: xsettingsd feeds GTK
# settings and dunst replaces the notification daemon. Rewritten every start.
mkdir -p "$MNT/root/.config/dunst" "$MNT/root/.config/xsettingsd"
cat > "$MNT/root/.config/dunst/dunstrc" <<'DUNST'
[global]
    font = Cantarell 10
    frame_width = 1
    frame_color = "#26292D"
    separator_color = "#26292D"
    background = "#17191B"
    foreground = "#E9E9E9"
    highlight = "#E9E9E9"
    history_length = 0
    width = 360
    origin = top-right
    offset = 12x40
    transparency = 0
DUNST
cat > "$MNT/root/.config/xsettingsd/xsettingsd.conf" <<'XSET'
Net/ThemeName "LambdaOS"
Net/IconThemeName "LambdaOS"
Gtk/CursorThemeName "Adwaita"
Gtk/FontName "Cantarell 10"
Gtk/MonospaceFontName "Fira Code 10"
Xft/Antialias 1
Xft/Hinting 1
Xft/RGBA "none"
Xft/DPI 98304
XSET

cat > "$VNC/xstartup" <<'XSTART'
#!/bin/sh
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
# Android exports TMPDIR=/data/local/tmp, absent inside the chroot; GTK aborts.
export TMPDIR=/tmp
export XDG_RUNTIME_DIR=/tmp/runtime-root
# glycin's bwrap sandbox cannot create namespaces inside this chroot; without
# this every GTK icon load aborts the app (confirmed on glycin 2.2.2).
export GLYCIN_DISABLE_SANDBOX=i-know-the-risks
mkdir -p "$XDG_RUNTIME_DIR" 2>/dev/null
chmod 700 "$XDG_RUNTIME_DIR" 2>/dev/null
unset SESSION_MANAGER
unset DBUS_SESSION_BUS_ADDRESS
xset s off -dpms 2>/dev/null || true
GEOM=${LEX_GEOMETRY:-2280x1080}
W=${GEOM%x*}
H=${GEOM#*x}
# Same source the app uses: Settings.Secure lambda_theme, and "auto" follows the system.
theme_name() {
  _t=$(settings get secure lambda_theme 2>/dev/null | tr -d '\r')
  case "$_t" in
    light) echo light ;;
    auto) case "$(cmd uimode night 2>/dev/null)" in *yes*) echo dark ;; *) echo light ;; esac ;;
    *) echo dark ;;
  esac
}
THEME=$(theme_name)
vncconfig -nowin &
# Slim session: no xfce4-session/xfdesktop/power-manager. xfwm4 + panel + dock,
# all inside one session bus so xfconf and notifications work.
exec dbus-launch --exit-with-session /bin/sh -c '
  # vncserver does not pass the caller environment to the session, so the image
  # loader settings have to be set here or GTK aborts on the first icon.
  export TMPDIR=/tmp XDG_RUNTIME_DIR=/tmp/runtime-root GLYCIN_DISABLE_SANDBOX=i-know-the-risks
  # The wallpaper apply reads this, so it has to be set before it runs.
  export LEX_WALLPAPER=$3
  if [ -f /usr/local/share/lambdaos-theme/apply.sh ]; then
    /bin/sh /usr/local/share/lambdaos-theme/apply.sh
  fi
  W=$1; H=$2; THEME=$3
  PLATE=lambdaos-${W}x${H}.png
  [ "$THEME" = light ] && PLATE=lambdaos-${W}x${H}-light.png
  if command -v feh >/dev/null 2>&1; then
    feh --bg-fill "/usr/share/backgrounds/lambdaos/$PLATE" 2>/dev/null \
      || xsetroot -solid "#101112" 2>/dev/null || true
  else
    xsetroot -solid "#101112" 2>/dev/null || true
  fi
  command -v xsettingsd >/dev/null 2>&1 && xsettingsd >/dev/null 2>&1 &
  xfwm4 >/dev/null 2>&1 &
  xfce4-panel >/dev/null 2>&1 &
  command -v dunst >/dev/null 2>&1 && dunst >/dev/null 2>&1 &
  if [ -x /usr/local/share/lambdaos-theme/dock/dock.py ]; then
    /usr/bin/python3 /usr/local/share/lambdaos-theme/dock/dock.py >/dev/null 2>&1 &
  fi
  wait
' sh "$W" "$H" "$THEME"
XSTART
chmod 755 "$VNC/xstartup"

# Per-session VNC password, returned to Operator through the job receipt.
if [ ! -s "$BASE/vncpass" ]; then
  head -c 64 /dev/urandom | sha256sum | cut -c1-12 > "$BASE/vncpass"
fi
chmod 600 "$BASE/vncpass"
chroot "$MNT" /bin/sh -c '/usr/bin/vncpasswd -f > /root/.config/tigervnc/passwd' < "$BASE/vncpass"
chroot "$MNT" /bin/chmod 600 /root/.config/tigervnc/passwd

CHROOT_PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
# Start noVNC first so its Python startup overlaps vncserver's; it only
# connects to 5901 when a client arrives.
if ! grep -q ":17C0 .* 0A" /proc/net/tcp; then
  # Kill stale websockify multiprocessing helpers that survived a stop.
  for c in /proc/[0-9]*/cmdline; do
    cmd=$( { tr '\0' ' ' < "$c"; } 2>/dev/null || true )
    case "$cmd" in
      *"/usr/bin/websockify"*)
        p=${c#/proc/}; p=${p%/cmdline}
        [ "$p" != "$$" ] && kill -9 "$p" 2>/dev/null || true
        ;;
    esac
  done
  chroot "$MNT" /bin/sh -c "export PATH=$CHROOT_PATH; exec /usr/bin/websockify --web=/usr/share/novnc 127.0.0.1:6080 127.0.0.1:5901" \
    </dev/null >"$BASE/websockify.log" 2>&1 &
  echo $! > "$BASE/websockify.pid"
fi

if [ ! -e "$MNT/tmp/.X$DISP-lock" ]; then
  chroot "$MNT" /bin/sh -c "export PATH=$CHROOT_PATH HOME=/root LEX_GEOMETRY=$GEOM; exec /usr/bin/vncserver :$DISP -localhost yes -geometry $GEOM -depth 24 -name LEX" \
    </dev/null >"$BASE/vnc.log" 2>&1 || true
fi

RUN=0
if [ -s "$MNT/tmp/.X$DISP-lock" ]; then
  XPID=$(cat "$MNT/tmp/.X$DISP-lock" 2>/dev/null | tr -dc '0-9' || true)
  case "$XPID" in
    *[!0-9]*|"") ;;
    *) [ -d "/proc/$XPID" ] && RUN=1 ;;
  esac
fi
# noVNC must be listening too, otherwise the viewer would attach to nothing.
WEB=0
n=0
while [ "$n" -lt 10 ]; do
  grep -q ":17C0 .* 0A" /proc/net/tcp && { WEB=1; break; }
  sleep 0.5
  n=$((n+1))
done
VNC=0
if grep -q ":170D .* 0A" /proc/net/tcp; then VNC=1; fi
OURS=0
for c in /proc/[0-9]*/cmdline; do
  cmd=$( { tr '\0' ' ' < "$c"; } 2>/dev/null || true )
  case "$cmd" in
    *"/usr/bin/websockify"*) OURS=1; break ;;
  esac
done
# Live sync: theme-watch.sh follows the phone's light/dark setting while the
# desktop runs. setsid detaches it, or the adb shell that ran us takes it down.
if [ -f "$BASE/theme-watch.sh" ]; then
  nohup setsid sh "$BASE/theme-watch.sh" >/dev/null 2>&1 &
fi

PASS=$(cat "$BASE/vncpass")
if [ "$RUN" = 1 ] && [ "$VNC" = 1 ] && [ "$WEB" = 1 ] && [ "$OURS" = 1 ]; then RUNJSON=true; else RUNJSON=false; fi
if [ "$RUNJSON" = false ]; then sh "$BASE/stop.sh" >/dev/null 2>&1 || true; fi
printf '{"running":%s,"vnc_port":5901,"web_port":6080,"geometry":"%s","password":"%s"}\n' \
  "$RUNJSON" "$GEOM" "$PASS" > "$BASE/status.json"
chmod 600 "$BASE/status.json"
cat "$BASE/status.json"
