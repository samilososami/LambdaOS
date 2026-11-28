#!/bin/sh
# LambdaOS LEX top bar + dock. Runs inside the chroot as root, with DISPLAY and
# a DBus session, before xfce4-panel starts. Idempotent; never aborts the
# session. Bar 72 high; dock 76 high, 48 icons, 14 pad (see the width note).
set -u

DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APPS=$DIR/../applications
HOME=${HOME:-/root}
CONF=$HOME/.config
XFCE=$CONF/xfce4
XFCONF=$XFCE/xfconf/xfce-perchannel-xml
PANELDIR=$XFCE/panel
APPDIR=$HOME/.local/share/applications
DOCKBG=$PANELDIR/dock-bg.png
XML=$XFCONF/xfce4-panel.xml
export HOME

mkdir -p "$XFCONF" "$PANELDIR" "$APPDIR" || exit 0

GEOM=${LEX_GEOMETRY:-2280x1080}
W=${GEOM%%x*}
H=${GEOM##*x}
case $W in ''|*[!0-9]*) W=2280 ;; esac
case $H in ''|*[!0-9]*) H=1080 ;; esac

have() { command -v "$1" >/dev/null 2>&1; }

# LambdaOS icons win; a stock icon keeps the dock usable until icons/ ships.
icon_available() {
  _n=$1
  [ -e "/usr/share/pixmaps/$_n.png" ] && return 0
  [ -e "/usr/share/pixmaps/$_n.xpm" ] && return 0
  for _d in "$HOME/.local/share/icons" /usr/local/share/icons /usr/share/icons; do
    [ -d "$_d" ] || continue
    find "$_d" -type f \( -name "$_n.png" -o -name "$_n.svg" -o -name "$_n.xpm" \) \
      -print -quit 2>/dev/null | grep -q . && return 0
  done
  return 1
}

TERM=
if have qterminal; then TERM=qterminal
elif have xfce4-terminal; then TERM=xfce4-terminal
elif have x-terminal-emulator; then TERM=x-terminal-emulator
fi
BROWSER=
if have firefox-esr; then BROWSER=firefox-esr
elif have chromium; then BROWSER=chromium
fi

CHOICES=$(mktemp "${TMPDIR:-/tmp}/lex-dock.XXXXXX") || exit 0
cleanup() { rm -f "$CHOICES"; }
trap cleanup EXIT INT TERM
add() { printf '%s|%s\n' "$1" "$2" >> "$CHOICES"; }

[ -n "$TERM" ] && add lambdaos-terminal utilities-terminal
have thunar && add lambdaos-files system-file-manager
[ -n "$BROWSER" ] && add lambdaos-browser "$BROWSER"
have mousepad && add lambdaos-editor accessories-text-editor
have wireshark && add lambdaos-wireshark wireshark
have nmap && [ "$TERM" = qterminal ] && add lambdaos-nmap nmap
have xfce4-screenshooter && add lambdaos-screenshot applets-screenshooter
have xfce4-settings-manager && add lambdaos-settings xfce4-settings

N=$(wc -l < "$CHOICES" | tr -d ' ')
[ "$N" -ge 1 ] 2>/dev/null || N=1
# Each launcher plugin button is panel-thickness wide (76): 48 icon + 14 pad
# per side. DESIGN's 10px gap would need 58px buttons, impossible at 76 high.
DOCKW=$((N * 76))
XPOS=$(( (W - DOCKW) / 2 ))
[ "$XPOS" -lt 0 ] && XPOS=0
YPOS=$(( H - 76 - 12 ))
[ "$YPOS" -lt 0 ] && YPOS=0
# 4.20 caps panel length at a percentage: calibrate it to the exact pixel width.
PCTI=$(( (DOCKW * 100000000 + W - 1) / W ))
PCT="$((PCTI / 1000000)).$(printf '%06d' $((PCTI % 1000000)))"
# Floating panels take the window centre, not its top-left.
SX=$((XPOS + DOCKW / 2))
SY=$((YPOS + 38))

install_app() { # desktop-id fallback-icon
  _src=$APPS/$1.desktop
  [ -f "$_src" ] || return 0
  _icon=$(sed -n 's/^Icon=//p' "$_src" | head -n 1)
  [ -n "$_icon" ] || _icon=$2
  icon_available "$_icon" || _icon=$2
  _exec=
  case $1 in
    lambdaos-terminal) [ "$TERM" = qterminal ] || _exec=$TERM ;;
    lambdaos-browser)  [ "$BROWSER" = firefox-esr ] || _exec=$BROWSER ;;
  esac
  if [ -n "$_exec" ]; then
    sed -e "s|^Icon=.*|Icon=$_icon|" \
        -e "s|^Exec=[^ ]*|Exec=$_exec|" "$_src" > "$APPDIR/$1.desktop"
  else
    sed -e "s|^Icon=.*|Icon=$_icon|" "$_src" > "$APPDIR/$1.desktop"
  fi
}

# xfce4-panel's launcher shows one icon per plugin; give each app its own.
PID=20
DOCKIDS=
DOCKPLUGINS=
while IFS='|' read -r id fb; do
  install_app "$id" "$fb"
  [ -f "$APPDIR/$id.desktop" ] || continue
  rm -rf "$PANELDIR/launcher-$PID"
  mkdir -p "$PANELDIR/launcher-$PID"
  cp "$APPDIR/$id.desktop" "$PANELDIR/launcher-$PID/"
  DOCKIDS="$DOCKIDS        <value type=\"int\" value=\"$PID\"/>
"
  DOCKPLUGINS="$DOCKPLUGINS    <property name=\"plugin-$PID\" type=\"string\" value=\"launcher\">
      <property name=\"disable-tooltips\" type=\"bool\" value=\"true\"/>
      <property name=\"items\" type=\"array\">
        <value type=\"string\" value=\"$id.desktop\"/>
      </property>
    </property>
"
  PID=$((PID + 1))
done < "$CHOICES"

# Dock background at exactly the dock size so the 18px corners never stretch.
rm -f "$DOCKBG"
SVG=
if have rsvg-convert; then
  SVG=$(mktemp "${TMPDIR:-/tmp}/lex-dock.XXXXXX.svg") || SVG=
  if [ -n "$SVG" ]; then
    cat > "$SVG" <<SVGEOF
<svg xmlns="http://www.w3.org/2000/svg" width="$DOCKW" height="76" viewBox="0 0 $DOCKW 76">
  <defs>
    <filter id="s" x="-10%" y="-10%" width="120%" height="130%">
      <feGaussianBlur stdDeviation="4"/>
    </filter>
    <mask id="m">
      <rect width="$DOCKW" height="76" fill="#FFFFFF"/>
      <rect x="0.5" y="0.5" width="$((DOCKW - 1))" height="75" rx="18" fill="#000000"/>
    </mask>
  </defs>
  <rect x="2" y="2" width="$((DOCKW - 4))" height="72" rx="18" fill="#000000" fill-opacity="0.45" filter="url(#s)" mask="url(#m)"/>
  <rect x="0.5" y="0.5" width="$((DOCKW - 1))" height="75" rx="18" fill="#17191B" fill-opacity="0.88" stroke="#FFFFFF" stroke-opacity="0.06" stroke-width="1"/>
</svg>
SVGEOF
    rsvg-convert -w "$DOCKW" -h 76 -o "$DOCKBG" "$SVG" 2>/dev/null || true
    rm -f "$SVG"
  fi
fi
if [ ! -s "$DOCKBG" ] && have python3; then
  python3 - "$DOCKW" "$DOCKBG" <<'PY' 2>/dev/null || true
import sys
from PIL import Image, ImageChops, ImageDraw, ImageFilter
w, out = int(sys.argv[1]), sys.argv[2]
s, h = 4, 76
img = Image.new("RGBA", (w * s, h * s), (0, 0, 0, 0))
sh = Image.new("RGBA", img.size, (0, 0, 0, 0))
d = ImageDraw.Draw(sh)
d.rounded_rectangle((2 * s, 2 * s, (w - 2) * s, 72 * s), radius=18 * s, fill=(0, 0, 0, 115))
sh = sh.filter(ImageFilter.GaussianBlur(4 * s))
body = (0.5 * s, 0.5 * s, (w - 0.5) * s - 1, (h - 0.5) * s - 1)
cut = Image.new("L", img.size, 255)
ImageDraw.Draw(cut).rounded_rectangle(body, radius=18 * s, fill=0)
sh.putalpha(ImageChops.multiply(sh.getchannel("A"), cut))
img.alpha_composite(sh)
d = ImageDraw.Draw(img)
d.rounded_rectangle(body, radius=18 * s, fill=(23, 25, 27, 224), outline=(255, 255, 255, 15), width=s)
img.resize((w, h), Image.LANCZOS).save(out)
PY
fi
if [ -s "$DOCKBG" ] && have identify; then
  [ "$(identify -format '%wx%h' "$DOCKBG" 2>/dev/null)" = "${DOCKW}x76" ] || rm -f "$DOCKBG"
fi
if [ -s "$DOCKBG" ]; then
  BGSTYLE=2
  BGIMAGE="        <property name=\"background-image\" type=\"string\" value=\"$DOCKBG\"/>"
else
  BGSTYLE=1
  BGIMAGE=
  echo "lex-panel: dock background generation failed, using solid" >&2
fi

MENUICON=lambdaos-menu
icon_available "$MENUICON" || MENUICON=xfce4-appfinder

cat > "$XML" <<EOF
<?xml version="1.0" encoding="UTF-8"?>

<channel name="xfce4-panel" version="1.0">
  <property name="configver" type="int" value="2"/>
  <property name="panels" type="array">
    <value type="int" value="1"/>
    <value type="int" value="2"/>
    <property name="panel-1" type="empty">
      <property name="position" type="string" value="p=6;x=0;y=0"/>
      <property name="length" type="uint" value="100"/>
      <property name="length-adjust" type="bool" value="true"/>
      <property name="position-locked" type="bool" value="true"/>
      <property name="size" type="uint" value="72"/>
      <property name="icon-size" type="uint" value="40"/>
      <property name="background-style" type="uint" value="1"/>
      <property name="background-rgba" type="array">
        <value type="double" value="0.078431"/>
        <value type="double" value="0.082353"/>
        <value type="double" value="0.086275"/>
        <value type="double" value="0.980000"/>
      </property>
      <property name="plugin-ids" type="array">
        <value type="int" value="1"/>
        <value type="int" value="2"/>
        <value type="int" value="3"/>
        <value type="int" value="4"/>
        <value type="int" value="5"/>
        <value type="int" value="6"/>
        <value type="int" value="7"/>
      </property>
    </property>
    <property name="panel-2" type="empty">
      <property name="position" type="string" value="p=0;x=$SX;y=$SY"/>
      <property name="length" type="double" value="$PCT"/>
      <property name="length-adjust" type="bool" value="false"/>
      <property name="position-locked" type="bool" value="true"/>
      <property name="size" type="uint" value="76"/>
      <property name="icon-size" type="uint" value="48"/>
      <property name="background-style" type="uint" value="$BGSTYLE"/>
      <property name="background-rgba" type="array">
        <value type="double" value="0.090196"/>
        <value type="double" value="0.098039"/>
        <value type="double" value="0.105882"/>
        <value type="double" value="0.878431"/>
      </property>
$BGIMAGE
      <property name="plugin-ids" type="array">
$DOCKIDS      </property>
    </property>
  </property>
  <property name="plugins" type="empty">
    <property name="plugin-1" type="string" value="whiskermenu">
      <property name="button-icon" type="string" value="$MENUICON"/>
      <property name="button-title" type="string" value=""/>
      <property name="show-button-icon" type="bool" value="true"/>
      <property name="show-button-title" type="bool" value="false"/>
      <property name="button-single-row" type="bool" value="true"/>
    </property>
    <property name="plugin-2" type="string" value="separator">
      <property name="style" type="uint" value="0"/>
    </property>
    <property name="plugin-3" type="string" value="tasklist">
      <property name="show-handle" type="bool" value="false"/>
      <property name="show-labels" type="bool" value="true"/>
      <property name="grouping" type="uint" value="1"/>
      <property name="flat-buttons" type="bool" value="true"/>
    </property>
    <property name="plugin-4" type="string" value="separator">
      <property name="expand" type="bool" value="true"/>
      <property name="style" type="uint" value="0"/>
    </property>
    <property name="plugin-5" type="string" value="genmon">
      <property name="command" type="string" value="$DIR/status.sh"/>
      <property name="update-interval" type="uint" value="5"/>
      <property name="use-label" type="bool" value="false"/>
      <property name="font" type="string" value="DejaVu Sans Mono 13"/>
    </property>
    <property name="plugin-6" type="string" value="separator">
      <property name="style" type="uint" value="2"/>
      <property name="expand" type="bool" value="false"/>
    </property>
    <property name="plugin-7" type="string" value="clock">
      <!-- No "mode" key: leaving it unset is the digital face. Any explicit value
           selects another one (0 a bare dial, 1/2 a dot grid). -->
      <!-- No frame: its border cut into the status text sitting next to it. -->
      <property name="show-frame" type="bool" value="false"/>
      <!-- Layout 0 is date over time; 3 drops the date. -->
      <property name="digital-layout" type="uint" value="0"/>
      <property name="digital-time-format" type="string" value="  %H:%M"/>
      <property name="digital-date-format" type="string" value="  %a %d %b"/>
      <property name="digital-time-font" type="string" value="DejaVu Sans 17"/>
      <property name="digital-date-font" type="string" value="DejaVu Sans 13"/>
    </property>
$DOCKPLUGINS  </property>
</channel>
EOF

# Workspace count lives in xfwm4; touch only this key.
if have xfconf-query; then
  xfconf-query -c xfwm4 -p /general/workspace_count -s 2 -t int 2>/dev/null \
    || xfconf-query -c xfwm4 -p /general/workspace_count -n -t int -s 2 2>/dev/null \
    || true
fi

chmod 755 "$DIR/status.sh" 2>/dev/null || true
# Panel keeps per-plugin state by id; stale ids from older layouts confuse it.
rm -f "$PANELDIR"/genmon-*.rc "$PANELDIR"/clock-*.rc 2>/dev/null || true
for d in "$PANELDIR"/launcher-*; do
  [ -d "$d" ] || continue
  case "${d##*/}" in launcher-11|launcher-12|launcher-13|launcher-14|launcher-15|launcher-16|launcher-17|launcher-18) rm -rf "$d" ;; esac
done

have update-desktop-database && update-desktop-database "$APPDIR" >/dev/null 2>&1 || true
