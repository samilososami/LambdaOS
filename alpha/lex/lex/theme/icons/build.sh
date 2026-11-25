#!/bin/sh
# Render every LambdaOS icon SVG to the fixed-size PNG dirs and compose the
# 48px+ app tiles. Host-only, idempotent, no network.
set -eu
cd "$(dirname "$0")"

# Project rule: a root invocation must not leave root-owned output in the tree.
if [ "$(id -u)" -eq 0 ] && id -u kali >/dev/null 2>&1; then
  exec setpriv --reuid=kali --regid=kali --init-groups -- "$0" "$@"
fi


if ! command -v rsvg-convert >/dev/null 2>&1; then
  echo "build.sh: rsvg-convert is required (librsvg2-bin)" >&2
  exit 1
fi
if ! command -v convert >/dev/null 2>&1; then
  echo "build.sh: ImageMagick convert is required for the app tiles" >&2
  exit 1
fi

THEME=LambdaOS
TMP=$(mktemp -d "${TMPDIR:-/tmp}/lambdaos-icons.XXXXXX")
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

render() { rsvg-convert -w "$3" -h "$3" -o "$2" "$1"; }

# Plain glyphs. App glyphs only render small: 48px and up use the tiled form.
for svg in "$THEME"/scalable/*/*.svg; do
  ctx=$(basename "$(dirname "$svg")")
  name=$(basename "$svg" .svg)
  case $ctx in
    apps|categories) sizes="16 22 24 32" ;;
    *) sizes="16 22 24 32 48 64 128 256" ;;
  esac
  for s in $sizes; do
    mkdir -p "$THEME/${s}x${s}/$ctx"
    render "$svg" "$THEME/${s}x${s}/$ctx/$name.png" "$s"
  done
done

# Panel context: the same tray glyphs, placed where an 18px top bar looks.
for name in network-wireless network-wired battery audio-volume-high bluetooth-active; do
  for s in 16 22 24; do
    mkdir -p "$THEME/${s}x${s}/panel"
    render "$THEME/scalable/status/$name.svg" "$THEME/${s}x${s}/panel/$name.png" "$s"
  done
done

# App tiles: 24px glyph on the 48px #1C2023 squircle, scaled by size.
tile() { # $1 svg  $2 name  $3 outdir  $4 size
  s=$4
  g=$((s / 2))
  r=$((12 * s / 48))
  render "$1" "$TMP/glyph.png" "$g"
  convert -size "${s}x${s}" xc:none -fill '#1C2023' \
    -draw "roundrectangle 0,0,$((s - 1)),$((s - 1)),$r,$r" "$TMP/tile.png"
  convert "$TMP/tile.png" "$TMP/glyph.png" -gravity center -composite "$3/$2.png"
}

for svg in "$THEME"/scalable/apps/*.svg; do
  name=$(basename "$svg" .svg)
  for s in 48 64 128 256; do
    mkdir -p "$THEME/${s}x${s}/apps"
    # The menu mark stays a bare glyph; the dock apps get the squircle tile.
    if [ "$name" = lambdaos-menu ]; then
      render "$svg" "$THEME/${s}x${s}/apps/$name.png" "$s"
    else
      tile "$svg" "$name" "$THEME/${s}x${s}/apps" "$s"
    fi
  done
done
for s in 48 64 128 256; do
  mkdir -p "$THEME/${s}x${s}/apps"
  tile "$THEME/scalable/categories/preferences-system.svg" preferences-system \
       "$THEME/${s}x${s}/apps" "$s"
done

# Every themed name must exist as SVG and as a 48px PNG.
for name in utilities-terminal system-file-manager accessories-text-editor \
            web-browser accessories-calculator preferences-system \
            applets-screenshooter system-search network-wireless network-wired \
            security-high folder user-home computer drive-harddisk \
            image-x-generic text-x-generic application-pdf package-x-generic \
            audio-x-generic video-x-generic edit-copy edit-paste edit-delete \
            document-save go-previous go-next go-up view-refresh window-close \
            window-minimize window-maximize system-shutdown system-log-out \
            battery audio-volume-high bluetooth-active lambdaos-menu; do
  find "$THEME/scalable" -name "$name.svg" | grep -q . ||
    { echo "build.sh: missing SVG for $name" >&2; exit 1; }
  find "$THEME/48x48" -name "$name.png" | grep -q . ||
    { echo "build.sh: missing 48px PNG for $name" >&2; exit 1; }
done

echo "build.sh: $(find "$THEME/scalable" -name '*.svg' | wc -l) SVGs," \
     "$(find "$THEME" -name '*.png' | wc -l) PNGs"
