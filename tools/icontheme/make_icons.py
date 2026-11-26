"""
Draws Searchlight's starter icon theme: glossy 3D objects, 192x192 PNG.

Every icon is built from flat shapes (masks) in the app's own colors; each shape is then given a
soft rounded height, lit by the same light from the top (diffuse, a glossy highlight and a top
sheen), and the whole object casts the same soft shadow below. One style across the set.

    python3 tools/icontheme/make_icons.py      # writes app/src/main/assets/icontheme/*.png

This is only the starter set: any 192x192 PNG dropped into assets/icontheme works (see theme.txt).
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

S = 768           # drawn at 4x, saved at 192
OUT = 192
LIGHT = np.array([-0.18, -0.72, 0.67])
LIGHT = LIGHT / np.linalg.norm(LIGHT)
HALF = (LIGHT + np.array([0, 0, 1.0]))
HALF = HALF / np.linalg.norm(HALF)


def blank():
    return Image.new("L", (S, S), 0)


def rgb(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], dtype=np.float32) / 255.0


def fill(mask, color):
    """A color for every pixel: one hex, or (top, bottom) / (start, end, 'diag') gradients."""
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32) / S
    if isinstance(color, str):
        return np.broadcast_to(rgb(color), (S, S, 3)).copy()
    a, b = rgb(color[0]), rgb(color[1])
    if len(color) > 2 and color[2] == "diag":
        t = np.clip((xx + yy) / 2, 0, 1)
    else:
        # The gradient across the shape's own height.
        rows = np.where(np.asarray(mask).max(axis=1) > 0)[0]
        y0, y1 = (rows[0], rows[-1]) if len(rows) else (0, S)
        t = np.clip((yy * S - y0) / max(1, y1 - y0), 0, 1)
    return a[None, None, :] * (1 - t[..., None]) + b[None, None, :] * t[..., None]


def lit(mask, color, depth=26, bump=1.0, gloss=0.55, sheen=True):
    """[mask] raised into a soft glossy object, lit from the top: RGBA float array."""
    m = np.asarray(mask, dtype=np.float32) / 255.0
    h = np.asarray(mask.filter(ImageFilter.GaussianBlur(depth)), dtype=np.float32) / 255.0
    h = np.power(np.clip(h, 0, 1), 0.6) * m
    gy, gx = np.gradient(h * depth * 2.2 * bump)
    n = np.dstack([-gx, -gy, np.ones_like(h)])
    n /= np.linalg.norm(n, axis=2, keepdims=True)
    diffuse = np.clip(n @ LIGHT, 0, 1)
    spec = np.power(np.clip(n @ HALF, 0, 1), 60) * gloss
    base = fill(mask, color)
    c = base * (0.42 + 0.72 * diffuse[..., None]) + spec[..., None]
    if sheen:
        # The glossy sheen over the upper part, fading down: the same on every object.
        rows = np.where(m.max(axis=1) > 0)[0]
        if len(rows):
            y0, y1 = rows[0], rows[-1]
            yy = np.arange(S, dtype=np.float32)[:, None]
            t = np.clip((yy - y0) / max(1, (y1 - y0) * 0.5), 0, 1)
            inner = np.asarray(mask.filter(ImageFilter.MinFilter(9)).filter(ImageFilter.GaussianBlur(6)), dtype=np.float32) / 255.0
            c = c + (0.28 * (1 - t) ** 1.6 * inner)[..., None]
    # A darker rim at the very edge: the object's depth.
    rim = np.clip(1 - h * 4, 0, 1) * m
    c = c * (1 - 0.25 * rim[..., None])
    return np.dstack([np.clip(c, 0, 1), m])


def over(dst, src):
    a = src[..., 3:4]
    out = dst.copy()
    out[..., :3] = src[..., :3] * a + dst[..., :3] * dst[..., 3:4] * (1 - a)
    out[..., 3:4] = a + dst[..., 3:4] * (1 - a)
    nz = out[..., 3:4] > 1e-6
    out[..., :3] = np.where(nz, out[..., :3] / np.maximum(out[..., 3:4], 1e-6), 0)
    return out


def shadow(mask, dy=22, blur=22, alpha=0.38):
    sh = Image.new("L", (S, S), 0)
    sh.paste(mask, (0, dy))
    a = np.asarray(sh.filter(ImageFilter.GaussianBlur(blur)), dtype=np.float32) / 255.0 * alpha
    return np.dstack([np.zeros((S, S, 3), np.float32), a])


def union(*masks):
    out = blank()
    for m in masks:
        out = Image.fromarray(np.maximum(np.asarray(out), np.asarray(m)))
    return out


def minus(a, b):
    return Image.fromarray(np.clip(np.asarray(a, dtype=np.int16) - np.asarray(b, dtype=np.int16), 0, 255).astype(np.uint8))


def shape(fn):
    m = blank()
    fn(ImageDraw.Draw(m))
    return m


def compose(layers):
    """[(mask, color, opts)...] bottom to top, with the whole object's shadow under them."""
    whole = union(*[l[0] for l in layers])
    img = np.zeros((S, S, 4), np.float32)
    img = over(img, shadow(whole))
    for mask, color, *opts in layers:
        img = over(img, lit(mask, color, **(opts[0] if opts else {})))
    return img


def save(img, name, out_dir):
    im = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8), "RGBA")
    im = im.resize((OUT, OUT), Image.LANCZOS)
    im.save(os.path.join(out_dir, name + ".png"), optimize=True)


def rot(mask, deg, center=None):
    return mask.rotate(deg, resample=Image.BICUBIC, center=center)


# ------------------------------------------------------------------ the starter set

def phone():
    def body(d):
        # The handle's curve, and the ear and mouth cups at its two ends.
        d.arc((180, 200, 588, 608), 180, 360, fill=255, width=118)
        d.rounded_rectangle((96, 360, 330, 520), 74, fill=255)
        d.rounded_rectangle((438, 360, 672, 520), 74, fill=255)
    # Tilted the way a handset rests: the curve at the lower left.
    m = rot(shape(body), 135, center=(384, 400)).filter(ImageFilter.GaussianBlur(8)).point(lambda p: 255 if p > 128 else 0)
    return compose([(m, ("#5BE37D", "#16994A"), {"depth": 30})])


def messages():
    bubble = shape(lambda d: (d.rounded_rectangle((104, 140, 664, 560), 190, fill=255),
                              d.polygon([(200, 500), (180, 668), (360, 540)], fill=255)))
    dots = shape(lambda d: [d.ellipse((x - 44, 306, x + 44, 394), fill=255) for x in (262, 384, 506)])
    return compose([(bubble, ("#5AA7FF", "#1A5FD6"), {"depth": 34}), (dots, "#FFFFFF", {"depth": 14, "gloss": 0.35, "sheen": False})])


def gmail():
    env = shape(lambda d: d.rounded_rectangle((100, 196, 668, 588), 60, fill=255))
    v = shape(lambda d: (d.line([(150, 250), (384, 430), (618, 250)], fill=255, width=74, joint="curve"),
                         d.ellipse((113, 213, 187, 287), fill=255), d.ellipse((581, 213, 655, 287), fill=255)))
    sides = shape(lambda d: (d.rounded_rectangle((100, 196, 188, 588), 40, fill=255), d.rounded_rectangle((580, 196, 668, 588), 40, fill=255)))
    return compose([(env, ("#FFFFFF", "#DCE1EA"), {"depth": 30}),
                    (sides, ("#F25A4C", "#C5221F"), {"depth": 16, "sheen": False}),
                    (v, ("#F25A4C", "#C5221F"), {"depth": 14, "sheen": False})])


def brave():
    pts = [(384, 96), (610, 168), (650, 236), (600, 470), (384, 676), (168, 470), (118, 236), (158, 168)]
    shield = shape(lambda d: d.polygon(pts, fill=255)).filter(ImageFilter.GaussianBlur(14)).point(lambda p: 255 if p > 128 else 0)
    face = shape(lambda d: d.polygon([(256, 300), (512, 300), (384, 520)], fill=255)).filter(ImageFilter.GaussianBlur(18)).point(lambda p: 255 if p > 110 else 0)
    eyes = shape(lambda d: (d.ellipse((316, 346, 360, 384), fill=255), d.ellipse((408, 346, 452, 384), fill=255)))
    return compose([(shield, ("#FF8A4C", "#E0390F"), {"depth": 34}),
                    (face, "#FFFFFF", {"depth": 18, "sheen": False}),
                    (eyes, ("#FF8A4C", "#E0390F"), {"depth": 6, "sheen": False, "gloss": 0.2})])


def quetta():
    orb = shape(lambda d: d.ellipse((124, 124, 644, 644), fill=255))
    q = minus(shape(lambda d: d.ellipse((236, 236, 532, 532), fill=255)), shape(lambda d: d.ellipse((316, 316, 452, 452), fill=255)))
    tail = shape(lambda d: d.line([(440, 440), (560, 560)], fill=255, width=72))
    return compose([(orb, ("#7C6CFF", "#2B2A9E", "diag"), {"depth": 40}), (union(q, tail), "#FFFFFF", {"depth": 16, "sheen": False})])


def calendar():
    # The live date is drawn on it by the launcher (the day on the page, the month on the band).
    page = shape(lambda d: d.rounded_rectangle((128, 150, 640, 650), 92, fill=255))
    band = Image.fromarray((np.asarray(page) * (np.arange(S)[:, None] < 300)).astype(np.uint8))
    rings = shape(lambda d: (d.rounded_rectangle((250, 104, 298, 216), 24, fill=255), d.rounded_rectangle((470, 104, 518, 216), 24, fill=255)))
    return compose([(page, ("#FFFFFF", "#DFE3EA"), {"depth": 30}), (band, ("#FF6B5E", "#D93025"), {"depth": 14}),
                    (rings, ("#6B7280", "#30353D"), {"depth": 10, "sheen": False})])


def clock():
    # The hands are drawn by the launcher, live (or at 10:10).
    bells = shape(lambda d: (d.pieslice((120, 92, 330, 302), 180, 360, fill=255), d.pieslice((438, 92, 648, 302), 180, 360, fill=255)))
    bells = rot(bells, 0)
    feet = shape(lambda d: (d.line([(250, 610), (190, 690)], fill=255, width=40), d.line([(518, 610), (578, 690)], fill=255, width=40)))
    rim = shape(lambda d: d.ellipse((134, 150, 634, 650), fill=255))
    face = shape(lambda d: d.ellipse((184, 200, 584, 600), fill=255))
    ticks = blank()
    td = ImageDraw.Draw(ticks)
    for k in range(12):
        a = math.pi * 2 * k / 12
        r0, r1 = (150, 182) if k % 3 else (136, 182)
        td.line([(384 + r0 * math.sin(a), 400 - r0 * math.cos(a)), (384 + r1 * math.sin(a), 400 - r1 * math.cos(a))], fill=255, width=16 if k % 3 else 22)
    return compose([(union(bells, feet), ("#F4F6FA", "#9AA3B2"), {"depth": 18}), (rim, ("#FF6B5E", "#C5221F"), {"depth": 36}),
                    (face, ("#FFFFFF", "#E7EAF0"), {"depth": 10, "bump": 0.4}), (ticks, "#3A4150", {"depth": 4, "sheen": False, "gloss": 0.2})])


def camera():
    body = shape(lambda d: (d.rounded_rectangle((96, 236, 672, 610), 96, fill=255), d.rounded_rectangle((270, 176, 498, 290), 46, fill=255)))
    ring = shape(lambda d: d.ellipse((244, 284, 524, 564), fill=255))
    glass = shape(lambda d: d.ellipse((294, 334, 474, 514), fill=255))
    glint = shape(lambda d: d.ellipse((330, 360, 392, 410), fill=255))
    flash = shape(lambda d: d.rounded_rectangle((540, 276, 620, 326), 22, fill=255))
    return compose([(body, ("#4A505C", "#1C1F26"), {"depth": 32}), (ring, ("#E9EDF3", "#8B94A3"), {"depth": 22}),
                    (glass, ("#3C4FA8", "#0B1030"), {"depth": 20, "gloss": 0.9}), (glint, "#BFD4FF", {"depth": 6, "sheen": False}),
                    (flash, ("#FFE27A", "#F4B400"), {"depth": 8, "sheen": False})])


def photos():
    frame = shape(lambda d: d.rounded_rectangle((132, 120, 636, 660), 70, fill=255))
    pic = shape(lambda d: d.rounded_rectangle((182, 170, 586, 540), 34, fill=255))
    hills = Image.fromarray(np.minimum(np.asarray(shape(lambda d: (d.polygon([(182, 540), (330, 360), (470, 540)], fill=255),
                                                                     d.polygon([(380, 540), (500, 410), (620, 540)], fill=255)))),
                                       np.asarray(pic)))
    sun = shape(lambda d: d.ellipse((440, 210, 530, 300), fill=255))
    return compose([(frame, ("#FFFFFF", "#DDE2EA"), {"depth": 30}), (pic, ("#7CC4FF", "#3D8BF2"), {"depth": 8, "bump": 0.4, "sheen": False}),
                    (sun, ("#FFE27A", "#F4B400"), {"depth": 10, "sheen": False}), (hills, ("#5BD67D", "#1E8E3E"), {"depth": 12, "sheen": False})])


def maps():
    base = shape(lambda d: d.ellipse((190, 560, 578, 690), fill=255))
    pin = shape(lambda d: (d.ellipse((214, 96, 554, 436), fill=255), d.polygon([(232, 340), (536, 340), (384, 640)], fill=255)))
    pin = pin.filter(ImageFilter.GaussianBlur(10)).point(lambda p: 255 if p > 128 else 0)
    hole = shape(lambda d: d.ellipse((316, 198, 452, 334), fill=255))
    return compose([(base, ("#5BD67D", "#1E8E3E"), {"depth": 14}), (minus(pin, hole), ("#FF6B5E", "#C5221F"), {"depth": 40})])


def spotify():
    disc = shape(lambda d: d.ellipse((112, 112, 656, 656), fill=255))
    grooves = blank()
    gd = ImageDraw.Draw(grooves)
    for r in (250, 214, 178):
        gd.ellipse((384 - r, 384 - r, 384 + r, 384 + r), outline=255, width=8)
    label = shape(lambda d: d.ellipse((290, 290, 478, 478), fill=255))
    hole = shape(lambda d: d.ellipse((364, 364, 404, 404), fill=255))
    return compose([(disc, ("#2BD46A", "#118A3E"), {"depth": 40}), (grooves, "#0D6B30", {"depth": 3, "sheen": False, "gloss": 0.3}),
                    (minus(label, hole), ("#1A1D22", "#000000"), {"depth": 10, "sheen": False})])


def youtube():
    tv = shape(lambda d: d.rounded_rectangle((96, 210, 672, 606), 120, fill=255))
    ant = shape(lambda d: (d.line([(384, 214), (300, 110)], fill=255, width=26), d.line([(384, 214), (468, 110)], fill=255, width=26),
                           d.ellipse((280, 90, 320, 130), fill=255), d.ellipse((448, 90, 488, 130), fill=255)))
    feet = shape(lambda d: (d.rounded_rectangle((210, 580, 280, 660), 24, fill=255), d.rounded_rectangle((488, 580, 558, 660), 24, fill=255)))
    play = shape(lambda d: d.polygon([(320, 300), (320, 516), (500, 408)], fill=255)).filter(ImageFilter.GaussianBlur(10)).point(lambda p: 255 if p > 128 else 0)
    return compose([(union(ant, feet), ("#9AA3B2", "#4A505C"), {"depth": 10}), (tv, ("#FF5A4F", "#C4130E"), {"depth": 36}),
                    (play, "#FFFFFF", {"depth": 16, "sheen": False})])


def canva():
    orb = shape(lambda d: d.ellipse((120, 120, 648, 648), fill=255))
    c = shape(lambda d: d.arc((244, 244, 524, 524), 40, 320, fill=255, width=70))
    caps = shape(lambda d: [d.ellipse((384 + 140 * math.cos(math.radians(a)) - 35, 384 + 140 * math.sin(math.radians(a)) - 35,
                                      384 + 140 * math.cos(math.radians(a)) + 35, 384 + 140 * math.sin(math.radians(a)) + 35), fill=255) for a in (40, 320)])
    return compose([(orb, ("#1ED6DC", "#7D2AE8", "diag"), {"depth": 42}), (union(c, caps), "#FFFFFF", {"depth": 16, "sheen": False})])


def settings():
    g = blank()
    gd = ImageDraw.Draw(g)
    gd.ellipse((184, 184, 584, 584), fill=255)
    for k in range(8):
        a = math.pi * 2 * k / 8
        cx, cy = 384 + 236 * math.cos(a), 384 + 236 * math.sin(a)
        w = 62
        pts = [(cx + w * math.cos(a + s) * 1.0 + 0, cy + w * math.sin(a + s)) for s in (math.pi / 2, -math.pi / 2)]
        gd.polygon([(384 + 160 * math.cos(a + 0.33), 384 + 160 * math.sin(a + 0.33)), (cx + 40 * math.cos(a + 1.2), cy + 40 * math.sin(a + 1.2)),
                    (cx + 40 * math.cos(a - 1.2), cy + 40 * math.sin(a - 1.2)), (384 + 160 * math.cos(a - 0.33), 384 + 160 * math.sin(a - 0.33))], fill=255)
    g = g.filter(ImageFilter.GaussianBlur(8)).point(lambda p: 255 if p > 128 else 0)
    hole = shape(lambda d: d.ellipse((310, 310, 458, 458), fill=255))
    hub = shape(lambda d: d.ellipse((280, 280, 488, 488), outline=255, width=24))
    return compose([(minus(g, hole), ("#D5DAE3", "#6B7383"), {"depth": 34}), (hub, ("#8C95A6", "#4A5160"), {"depth": 6, "sheen": False})])


ICONS = {
    "phone": phone, "messages": messages, "gmail": gmail, "brave": brave, "quetta": quetta, "calendar": calendar,
    "clock": clock, "camera": camera, "photos": photos, "maps": maps, "spotify": spotify, "youtube": youtube,
    "canva": canva, "settings": settings,
}

if __name__ == "__main__":
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, "app", "src", "main", "assets", "icontheme")
    os.makedirs(out_dir, exist_ok=True)
    for name, fn in ICONS.items():
        save(fn(), name, out_dir)
        print("wrote", name)
