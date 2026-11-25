#!/bin/sh
# Render the canonical SVG sources in this directory to the PNG assets used by
# the XFWM4 theme. Host-side maintenance tool; requires rsvg-convert.
# The installed theme does not need this script or the SVGs.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
out=$(dirname -- "$here")
for svg in "$here"/*.svg; do
  rsvg-convert --format=png -o "$out/$(basename -- "$svg" .svg).png" "$svg"
done
