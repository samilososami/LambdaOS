#!/usr/bin/env python3
"""Generate the LEX wallpaper identity: radial graphite gradient, fine dot
field fading with distance, clipped Lambda mark on the right, one tick line.

Deterministic, numpy + PIL, no network. Usage:
    make_wallpaper.py [WxH] [out.png]
Defaults to 2280x1080 and theme/wallpaper/lambdaos-WxH.png.
"""
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

BASE = Path(__file__).resolve().parent
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
REF_H = 1080
TOP_BAR = 28     # xfce4-panel height
DOCK_BAND = 110  # dock 76 + 12 px gap + margin
EDGE = 36        # dot fade ramp just outside the reserved bands


def render(w: int, h: int) -> Image.Image:
    s = h / REF_H
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    radius = np.sqrt((x / (w * 1.05)) ** 2 + (y / (h * 0.88)) ** 2)
    stops = [0.0, 0.25, 0.62, 1.0]
    colors = [(0x29, 0x29, 0x29), (0x20, 0x20, 0x20), (0x10, 0x10, 0x10), (0x08, 0x08, 0x08)]
    channels = [np.interp(radius, stops, [c[i] for c in colors]) for i in range(3)]
    img = Image.fromarray(np.dstack(channels).astype(np.uint8)).convert("RGBA")
    draw = ImageDraw.Draw(img)

    # Clipped Lambda mark on the right, as on the setup plate.
    font = ImageFont.truetype(FONT, round(780 * s))
    draw.text((w - 40 * s, h + 20 * s), "Λ", font=font, fill=(0x1b, 0x1b, 0x1b, 255), anchor="rs")
    # Tick sits above the dock band so the band keeps no local detail.
    tick_y = h - DOCK_BAND - 24
    draw.line([(38 * s, tick_y), (59 * s, tick_y)], fill=(0x55, 0x55, 0x55, 255),
              width=max(1, round(2 * s)))

    dots = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    dot = ImageDraw.Draw(dots)
    spacing = 26 * s
    r = max(0.8, 1.1 * s)
    yy = 13 * s
    while yy < h:
        band = (min(1.0, max(0.0, (yy - TOP_BAR) / EDGE))
                * min(1.0, max(0.0, (h - DOCK_BAND - yy) / EDGE)))
        xx = 13 * s
        while xx < w:
            d = ((xx / (w * 0.62)) ** 2 + (yy / (h * 0.72)) ** 2) ** 0.5
            alpha = int(255 * 0.32 * max(0.0, 1.0 - d) ** 2 * band)
            if alpha > 3:
                dot.ellipse([xx - r, yy - r, xx + r, yy + r], fill=(0xdd, 0xdd, 0xdd, alpha))
            xx += spacing
        yy += spacing

    out = Image.alpha_composite(img, dots).convert("RGB")
    assert out.size == (w, h)
    return out


def main() -> int:
    geom = sys.argv[1] if len(sys.argv) > 1 else "2280x1080"
    m = re.fullmatch(r"([1-9]\d{2,4})x([1-9]\d{2,4})", geom)
    if not m:
        print(f"usage: {Path(sys.argv[0]).name} [WxH] [out.png]", file=sys.stderr)
        return 2
    w, h = int(m.group(1)), int(m.group(2))
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else BASE / "theme" / "wallpaper" / f"lambdaos-{w}x{h}.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    render(w, h).save(out)
    print(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
