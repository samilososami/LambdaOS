#!/system/bin/sh
# Follow the phone's light/dark setting while the LEX desktop runs and repaint the
# root window when it flips. Started detached by start.sh; exits on its own when
# Xvnc is gone, so stop.sh does not have to know about it.
set -u
BASE=${BASE:-/data/local/lex}
MNT=${MNT:-$BASE/rootfs}
DISP=${DISP:-1}
GEOM=${GEOM:-2280x1080}
export PATH=/system/bin:/system/xbin

# Same source the app uses: Settings.Secure lambda_theme, "auto" follows the system.
theme_name() {
  _t=$(settings get secure lambda_theme 2>/dev/null | tr -d '\r')
  case "$_t" in
    light) echo light ;;
    auto) case "$(cmd uimode night 2>/dev/null)" in *yes*) echo dark ;; *) echo light ;; esac ;;
    *) echo dark ;;
  esac
}

CUR=$(theme_name)
while ps -A -o NAME 2>/dev/null | grep -q "^Xtigervnc"; do
  sleep 10
  NOW=$(theme_name)
  [ "$NOW" = "$CUR" ] && continue
  CUR=$NOW
  PLATE="lambdaos-${GEOM}.png"
  [ "$NOW" = light ] && PLATE="lambdaos-${GEOM}-light.png"
  # PATH inside the chroot, or feh (in /usr/bin) is not found.
  chroot "$MNT" /bin/sh -c "export PATH=/usr/bin:/bin DISPLAY=:$DISP XAUTHORITY=/root/.Xauthority; \
    feh --bg-fill /usr/share/backgrounds/lambdaos/$PLATE" >/dev/null 2>&1
done
