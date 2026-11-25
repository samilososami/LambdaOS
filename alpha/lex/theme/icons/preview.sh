#!/bin/sh
# Contact sheet: every icon at 48px (as shipped, tiles included) and at 18px
# rendered from the SVGs, on the #101112 panel background.
set -eu
cd "$(dirname "$0")"

# Project rule: a root invocation must not leave root-owned output in the tree.
if [ "$(id -u)" -eq 0 ] && id -u kali >/dev/null 2>&1; then
  exec setpriv --reuid=kali --regid=kali --init-groups -- "$0" "$@"
fi


OUT=../preview/icons.png
mkdir -p ../preview
TMP=$(mktemp -d "${TMPDIR:-/tmp}/lambdaos-preview.XXXXXX")
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
mkdir -p "$TMP/48" "$TMP/18"

for svg in LambdaOS/scalable/*/*.svg; do
  name=$(basename "$svg" .svg)
  rsvg-convert -w 18 -h 18 -o "$TMP/18/$name.png" "$svg"
  png=$(find LambdaOS/48x48 -name "$name.png" -print -quit)
  if [ -n "$png" ]; then
    cp "$png" "$TMP/48/$name.png"
  else
    rsvg-convert -w 48 -h 48 -o "$TMP/48/$name.png" "$svg"
  fi
done

montage -label '%t' "$TMP/48/"*.png -tile 10x -geometry 84x72+6+6 \
  -background '#101112' -fill '#9AA0A6' -font DejaVu-Sans -pointsize 8 \
  -gravity center "$TMP/row48.png"
# 18px row is unlabelled: it is a legibility check, and full names overlap at
# this width.
montage "$TMP/18/"*.png -tile 10x -geometry 48x30+4+4 \
  -background '#101112' "$TMP/row18.png"
convert "$TMP/row48.png" "$TMP/row18.png" -append \
  -bordercolor '#101112' -border 16 -depth 8 -strip "$OUT"
echo "preview.sh: wrote $OUT"
