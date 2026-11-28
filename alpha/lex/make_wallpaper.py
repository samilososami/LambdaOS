#!/usr/bin/env python3
"""Generate the LEX wallpaper identity: a painted background, the LambdaOS mark
sitting behind the silhouette on the right, and the fine dot field fading from
the top-left corner.

Deterministic, numpy + PIL, no network. Usage:
    make_wallpaper.py [WxH] [out.png] [--light]
Defaults to 2280x1080 and theme/wallpaper/lambdaos-WxH.png. The painted
background comes from theme/wallpaper/source/background-{dark,light}.png; if it
is missing the radial graphite gradient is used instead, so the script still
works on its own.
"""
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

BASE = Path(__file__).resolve().parent
SOURCE = BASE / "theme" / "wallpaper" / "source"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
REF_H = 1080
TOP_BAR = 44     # xfce4-panel height
DOCK_BAND = 110  # dock 76 + 12 px gap + margin
EDGE = 36        # dot fade ramp just outside the reserved bands


def painted(w: int, h: int, light: bool) -> Image.Image:
    """The soft silhouette painted for this build, or the radial gradient if absent."""
    name = "background-light.png" if light else "background-dark.png"
    path = SOURCE / name
    if path.is_file():
        plate = Image.open(path).convert("RGB").resize((w, h), Image.LANCZOS)
        if light:
            arr = np.array(plate).astype(np.float32)
            plate = Image.fromarray(np.clip(243 + (arr - 243) * 1.9, 0, 255).astype(np.uint8))
        return plate
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    radius = np.sqrt((x / (w * 1.05)) ** 2 + (y / (h * 0.88)) ** 2)
    stops = [0.0, 0.25, 0.62, 1.0]
    colors = [(0x29, 0x29, 0x29), (0x20, 0x20, 0x20), (0x10, 0x10, 0x10), (0x08, 0x08, 0x08)]
    channels = [np.interp(radius, stops, [c[i] for c in colors]) for i in range(3)]
    return Image.fromarray(np.dstack(channels).astype(np.uint8)).convert("RGB")


def mark(w: int, h: int, light: bool) -> Image.Image:
    """The LambdaOS mark, low contrast and faded at the bottom so the painted
    silhouette reads as sitting in front of it. Clipped by the bottom edge."""
    height = round(h * 0.74)
    glyph = Image.open(SOURCE / "mark.png").convert("RGBA")
    glyph = glyph.crop(glyph.getbbox()).resize((height, height), Image.LANCZOS)
    pad = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    # Right edge at 86% of the width: to the right, but not against the border.
    x = round(w * 0.86) - glyph.width
    y = h - round(glyph.height * 0.93)
    tint = (0xE2, 0xE2, 0xDC, 255) if light else (0x26, 0x28, 0x2A, 255)
    solid = Image.new("RGBA", glyph.size, tint)
    pad.paste(solid, (x, y), glyph)
    # Fade toward the bottom edge: the silhouette in front covers the lower part.
    ramp = np.clip((np.mgrid[0:h, 0:w][0].astype(np.float32) - h * 0.55) / (h * 0.5), 0, 1)
    alpha = np.array(pad)[:, :, 3].astype(np.float32) * (1 - 0.65 * ramp)
    out = np.array(pad)
    out[:, :, 3] = np.clip(alpha, 0, 255).astype(np.uint8)
    return Image.fromarray(out)


def render(w: int, h: int, light: bool = False) -> Image.Image:
    s = h / REF_H
    img = painted(w, h, light).convert("RGBA")
    img = Image.alpha_composite(img, mark(w, h, light))
    draw = ImageDraw.Draw(img)
    # Tick sits above the dock band so the band keeps no local detail.
    tick_y = h - DOCK_BAND - 24
    ink = (0x9a, 0x9a, 0x96, 255) if light else (0x55, 0x55, 0x55, 255)
    draw.line([(38 * s, tick_y), (59 * s, tick_y)], fill=ink, width=max(1, round(2 * s)))

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
            alpha = int(255 * (0.20 if light else 0.32) * max(0.0, 1.0 - d) ** 2 * band)
            if alpha > 3:
                tone = (0x2a, 0x2a, 0x28, alpha) if light else (0xdd, 0xdd, 0xdd, alpha)
                dot.ellipse([xx - r, yy - r, xx + r, yy + r], fill=tone)
            xx += spacing
        yy += spacing

    out = Image.alpha_composite(img, dots).convert("RGB")
    assert out.size == (w, h)
    return out


def main() -> int:
    args = [a for a in sys.argv[1:] if a != "--light"]
    light = "--light" in sys.argv[1:] or "--light" in sys.argv
    geom = args[0] if args else "2280x1080"
    m = re.fullmatch(r"([1-9]\d{2,4})x([1-9]\d{2,4})", geom)
    if not m:
        print(f"usage: {Path(sys.argv[0]).name} [WxH] [out.png]", file=sys.stderr)
        return 2
    w, h = int(m.group(1)), int(m.group(2))
    # The plain name is the one the desktop loads; the light plate ships beside it.
    suffix = "-light" if light else ""
    out = Path(args[1]) if len(args) > 1 else BASE / "theme" / "wallpaper" / f"lambdaos-{w}x{h}{suffix}.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    render(w, h, light).save(out)
    print(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
