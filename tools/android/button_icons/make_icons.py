#!/usr/bin/env python3
"""Remakes the Xbox-style button icons in this folder (32x32 RGBA PNGs) for ../button_icons.py.

Usage: make_icons.py   (needs Pillow and macOS's Arial Bold / Arial Narrow Bold)

Everything is drawn here, nothing comes from the game: badges to the look of Bloodborne's key guide icons (a dark disc
of radius 13 at (16,16) and a rounded rect (1,6)-(31,26), filled 46/46/46, lit at the top edge and dark at the bottom,
with a soft shadow), the face buttons' letters in the Xbox colours and the shoulder, stick, Menu and View labels in the
game's gold. Drawn at 8x and scaled down.
"""
import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter
out = Path(__file__).resolve().parent
FONT = "/System/Library/Fonts/Supplemental/Arial Bold.ttf"
NARROW = "/System/Library/Fonts/Supplemental/Arial Narrow Bold.ttf"
GOLD = (195, 161, 113, 255)
FILL = (46, 46, 46, 255)
K = 8  # supersampling

def bbox(im, a=200):
    xs = [x for x in range(32) for y in range(32) if im.getpixel((x, y))[3] >= a]
    ys = [y for x in range(32) for y in range(32) if im.getpixel((x, y))[3] >= a]
    return min(xs), min(ys), max(xs), max(ys)

def clear_inside(im, inside):
    im = im.copy()
    for y in range(32):
        for x in range(32):
            if inside(x + 0.5, y + 0.5): im.putpixel((x, y), FILL)
    return im

# The badges are drawn here (no game pixels), to the measured look of the originals: a disc of radius 13 at (16,16)
# and a rounded rect (1,6)-(30,25), filled 46/46/46, lit at the top edge and dark at the bottom, with a soft shadow.
def badge(shape):
    k = K
    im = Image.new("RGBA", (32 * k, 32 * k), (0, 0, 0, 0))
    shadow = Image.new("RGBA", im.size, (0, 0, 0, 0))
    ds = ImageDraw.Draw(shadow)
    if shape == "circle":
        box = (3 * k, 3 * k, 29 * k, 29 * k)
        ds.ellipse((box[0], box[1] + 1.2 * k, box[2], box[3] + 1.2 * k), fill=(0, 0, 0, 150))
    else:
        box = (1 * k, 6 * k, 31 * k, 26 * k)
        ds.rounded_rectangle((box[0], box[1] + 1.2 * k, box[2], box[3] + 1.2 * k), radius=4 * k, fill=(0, 0, 0, 120))
    im.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(0.8 * k)))
    # rim: a vertical gradient from light (top) to dark (bottom), then the fill inset by the rim width
    rim = Image.new("RGBA", im.size, (0, 0, 0, 0))
    top, bottom = 118, 20
    for y in range(box[1], box[3]):
        t = (y - box[1]) / max(1, box[3] - box[1] - 1)
        v = int(top + (bottom - top) * t)
        ImageDraw.Draw(rim).line((0, y, im.width, y), fill=(v, v, v, 248))
    mask = Image.new("L", im.size, 0)
    dm = ImageDraw.Draw(mask)
    inner = Image.new("L", im.size, 0)
    di = ImageDraw.Draw(inner)
    w = 1.3 * k
    if shape == "circle":
        dm.ellipse(box, fill=255)
        di.ellipse((box[0] + w, box[1] + w, box[2] - w, box[3] - w), fill=255)
    else:
        dm.rounded_rectangle(box, radius=4 * k, fill=255)
        di.rounded_rectangle((box[0] + w, box[1] + w, box[2] - w, box[3] - w), radius=3 * k, fill=255)
    im.paste(rim, (0, 0), mask)
    im.paste(Image.new("RGBA", im.size, FILL), (0, 0), inner)
    return im.resize((32, 32), Image.LANCZOS)

circle, rect = badge("circle"), badge("rect")
ccx, ccy, cr = 16.0, 16.0, 13.0
rx0, ry0, rx1, ry1 = 1, 6, 30, 25

def glyph(draw_fn):
    """A glyph layer: draw_fn(draw, scale) on a K-times canvas, scaled down to 32x32."""
    big = Image.new("RGBA", (32 * K, 32 * K), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(big), K)
    return big.resize((32, 32), Image.LANCZOS)

def text(label, color, cx, cy, size, font=FONT, spacing=0):
    def fn(d, k):
        f = ImageFont.truetype(font, int(size * k))
        if spacing:
            widths = [d.textlength(ch, font=f) for ch in label]
            total = sum(widths) + spacing * k * (len(label) - 1)
            x = cx * k - total / 2
            for ch, w in zip(label, widths):
                d.text((x, cy * k), ch, font=f, fill=color, anchor="lm"); x += w + spacing * k
        else:
            d.text((cx * k, cy * k), label, font=f, fill=color, anchor="mm")
    return fn

def compose(badge, *fns):
    im = badge.copy()
    for fn in fns: im.alpha_composite(glyph(fn))
    return im

def menu_lines(color):
    def fn(d, k):
        w, h, gap = 10 * k, 1.9 * k, 3.6 * k
        for i in (-1, 0, 1):
            y = ccy * k + i * gap
            d.rounded_rectangle((ccx * k - w / 2, y - h / 2, ccx * k + w / 2, y + h / 2), radius=h / 2, fill=color)
    return fn

def view_squares(color):
    def fn(d, k):
        s, t = 7.2 * k, 1.6 * k  # square size, line width
        bx, by = ccx * k - 1.6 * k, ccy * k + 1.6 * k  # back square (lower left), front (upper right)
        d.rounded_rectangle((bx - s / 2 + 3.2 * k, by - s / 2 - 3.2 * k, bx + s / 2 + 3.2 * k, by + s / 2 - 3.2 * k),
                            radius=1.2 * k, fill=color)
        d.rounded_rectangle((bx - s / 2, by - s / 2, bx + s / 2, by + s / 2), radius=1.2 * k, outline=color, width=int(t))
        d.rectangle((bx - s / 2 + t, by - s / 2 + t, bx + s / 2 - t, by + s / 2 - t), fill=(0, 0, 0, 0))
    return fn

A, B, X, Y = (112, 184, 64, 255), (205, 62, 52, 255), (64, 128, 222, 255), (226, 182, 48, 255)
RCX, RCY = (rx0 + rx1 + 1) / 2, (ry0 + ry1 + 1) / 2
icons = {
    "a": compose(circle, text("A", A, ccx, ccy + 0.6, 19.5)),
    "b": compose(circle, text("B", B, ccx + 0.4, ccy + 0.6, 19.5)),
    "x": compose(circle, text("X", X, ccx, ccy + 0.6, 19.5)),
    "y": compose(circle, text("Y", Y, ccx, ccy + 0.6, 19.5)),
    "lb": compose(rect, text("LB", GOLD, RCX, RCY + 0.5, 15, FONT, -0.6)),
    "rb": compose(rect, text("RB", GOLD, RCX, RCY + 0.5, 15, FONT, -0.6)),
    "lt": compose(rect, text("LT", GOLD, RCX, RCY + 0.5, 15, FONT, -0.6)),
    "rt": compose(rect, text("RT", GOLD, RCX, RCY + 0.5, 15, FONT, -0.6)),
    "ls": compose(circle, text("LS", GOLD, ccx, ccy + 0.5, 14, NARROW, 0.2)),
    "rs": compose(circle, text("RS", GOLD, ccx, ccy + 0.5, 14, NARROW, 0.2)),
    "menu": compose(circle, menu_lines((222, 222, 222, 255))),
    "view": compose(circle, view_squares((222, 222, 222, 255))),
}
for name, im in icons.items():
    im.save(out / f"{name}.png")  # 8-bit RGBA, not interlaced: what button_icons.py reads
print(f"{len(icons)} icons in {out}")

