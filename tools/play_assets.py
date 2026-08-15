#!/usr/bin/env python3
"""
Generates the Play Store graphics from the same geometry as the launcher icon.

The store icon is not a separate drawing: it is the adaptive icon's foreground
scaled so the mark occupies exactly the fraction of the frame it occupies on a
home screen. Adaptive icons draw on a 108dp canvas of which only the middle
72dp survives masking, so the store icon uses 512/72 as its scale factor. Draw
it any other way and the icon in the listing looks like a different size from
the icon the user ends up with.

Everything is supersampled and downscaled with LANCZOS: the ring is thin enough
that aliasing on the curve is visible at listing size.

    python3 tools/play_assets.py
"""

import os

from PIL import Image, ImageDraw, ImageFont

INK = (15, 18, 20)
BONE = (231, 226, 216)
FONT_LIGHT = "/usr/share/fonts/google-noto/NotoSans-Light.ttf"

# Mark proportions, in the 108dp units of ic_launcher_foreground.xml.
RING_R, RING_STROKE, DOT_R = 23.0, 6.0, 7.0
ADAPTIVE_VISIBLE = 72.0


def draw_mark(draw, cx, cy, scale):
    """Ring and centre dot. `scale` converts icon-canvas dp to pixels."""
    ring_r, stroke, dot_r = RING_R * scale, RING_STROKE * scale, DOT_R * scale
    draw.ellipse(
        [cx - ring_r, cy - ring_r, cx + ring_r, cy + ring_r],
        outline=BONE,
        width=round(stroke),
    )
    draw.ellipse([cx - dot_r, cy - dot_r, cx + dot_r, cy + dot_r], fill=BONE)


def store_icon(path, size=512, ss=4):
    img = Image.new("RGB", (size * ss,) * 2, INK)
    draw_mark(ImageDraw.Draw(img), size * ss / 2, size * ss / 2,
              (size * ss) / ADAPTIVE_VISIBLE)
    img.resize((size, size), Image.LANCZOS).save(path, "PNG")
    print(f"{path}  {size}x{size}")


def feature_graphic(path, w=1024, h=500, ss=2):
    img = Image.new("RGB", (w * ss, h * ss), INK)
    # Text goes on an RGBA layer so the subtitle can carry the same 45% opacity
    # the app uses for secondary text, instead of a hand-mixed grey.
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)

    title_text = "FocusPhone"
    sub_text = "the apps you blocked stay blocked"
    title = ImageFont.truetype(FONT_LIGHT, 84 * ss)
    sub = ImageFont.truetype(FONT_LIGHT, 32 * ss)

    # Play crops this image on some surfaces, so the mark and the text are
    # measured and centred as one lockup rather than placed at fixed offsets.
    mark_r = (RING_R + RING_STROKE / 2) * (95.0 / RING_R) * ss
    gap = 78 * ss
    text_w = max(d.textlength(title_text, font=title),
                 d.textlength(sub_text, font=sub))
    total = mark_r * 2 + gap + text_w
    left = (w * ss - total) / 2

    draw_mark(d, left + mark_r, h * ss / 2, (95.0 / RING_R) * ss)
    x = left + mark_r * 2 + gap
    d.text((x, 232 * ss), title_text, font=title, fill=BONE + (255,), anchor="ls")
    d.text((x, 292 * ss), sub_text, font=sub, fill=BONE + (115,), anchor="ls")

    Image.alpha_composite(img.convert("RGBA"), layer).convert("RGB") \
        .resize((w, h), Image.LANCZOS).save(path, "PNG")
    print(f"{path}  {w}x{h}  lockup margin {left / ss:.0f}px each side")


if __name__ == "__main__":
    os.makedirs("play", exist_ok=True)
    store_icon("play/icon-512.png")
    feature_graphic("play/feature-graphic-1024x500.png")
