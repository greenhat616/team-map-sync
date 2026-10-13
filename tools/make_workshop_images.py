"""Generates the Workshop preview for Team Map Sync.

The scene is drawn at low resolution and upscaled with nearest-neighbour so it reads as pixel art, matching
Necesse's look (and Coop Pause's preview); the title is drawn afterwards at full resolution so it stays crisp.
The game only shows the Workshop upload button for a mod whose jar contains resources/preview.png.

Usage: python tools/make_workshop_images.py .
"""
import random
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(sys.argv[1])
FONTS = Path("C:/Windows/Fonts")

FOG = (18, 24, 38)
FOG_SPECK = (28, 36, 54)
EDGE = (10, 14, 22)
CREAM = (246, 232, 196)
OUTLINE = (34, 24, 20)
ACCENT = (255, 196, 84)
# Terrain bands by height, low to high
TERRAIN = [
    (0.30, (46, 88, 150)),   # deep water
    (0.40, (70, 128, 188)),  # water
    (0.46, (214, 196, 140)), # sand
    (0.66, (86, 140, 62)),   # grass
    (0.80, (56, 104, 48)),   # forest
    (1.01, (124, 120, 112)), # rock
]


def font(name, size):
    return ImageFont.truetype(str(FONTS / name), size)


def value_noise(w, h, cell, rng):
    """Smooth 2D noise in [0, 1] from a random lattice, bilinearly interpolated."""
    gw, gh = w // cell + 2, h // cell + 2
    grid = [[rng.random() for _ in range(gw)] for _ in range(gh)]

    def at(x, y):
        gx, gy = x / cell, y / cell
        x0, y0 = int(gx), int(gy)
        tx, ty = gx - x0, gy - y0
        tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
        top = grid[y0][x0] * (1 - tx) + grid[y0][x0 + 1] * tx
        bottom = grid[y0 + 1][x0] * (1 - tx) + grid[y0 + 1][x0 + 1] * tx
        return top * (1 - ty) + bottom * ty

    return at


def terrain_color(height):
    for limit, color in TERRAIN:
        if height < limit:
            return color
    return TERRAIN[-1][1]


def player(d, x, y, shirt, s=1):
    """A tiny Necesse-ish settler: head, shirt, legs. (x, y) is the feet."""
    def px(a, b, c, e, f):
        d.rectangle([x + a * s, y + b * s, x + c * s - 1, y + e * s - 1], fill=f)
    px(-4, -9, 5, -3, OUTLINE)            # outline so they read on any terrain
    px(-4, -16, 4, -8, OUTLINE)
    px(-3, -14, 3, -8, (232, 186, 150))   # head
    px(-3, -15, 3, -13, (92, 60, 40))     # hair
    px(-1, -11, 0, -10, OUTLINE)          # eye
    px(1, -11, 2, -10, OUTLINE)
    px(-4, -8, 4, -3, shirt)              # body
    px(-3, -3, -1, 0, (60, 60, 90))       # legs
    px(1, -3, 3, 0, (60, 60, 90))


def marker(d, x, y):
    """A map pin; (x, y) is its tip."""
    d.polygon([(x, y), (x - 5, y - 7), (x + 5, y - 7)], fill=OUTLINE)
    d.ellipse([x - 6, y - 15, x + 6, y - 3], fill=OUTLINE)
    d.polygon([(x, y - 2), (x - 4, y - 7), (x + 4, y - 7)], fill=(214, 70, 60))
    d.ellipse([x - 5, y - 14, x + 5, y - 4], fill=(214, 70, 60))
    d.ellipse([x - 2, y - 11, x + 2, y - 7], fill=CREAM)


def sync_arrows(d, x0, x1, y):
    """Two opposite outlined arrows between the players: tiles flow both ways."""
    for yy, start, end in ((y, x0, x1), (y + 9, x1, x0)):
        step = 1 if end > start else -1
        tip = end
        base = end - 6 * step
        lo, hi = min(start, base), max(start, base)
        d.rectangle([lo, yy - 2, hi, yy + 2], fill=OUTLINE)
        d.polygon([(tip + 2 * step, yy), (base - step, yy - 6), (base - step, yy + 6)], fill=OUTLINE)
        d.rectangle([lo + 1, yy - 1, hi - 1, yy + 1], fill=ACCENT)
        d.polygon([(tip, yy), (base, yy - 4), (base, yy + 4)], fill=ACCENT)


def preview(path):
    w = h = 160
    rng = random.Random(5)
    noise = value_noise(w, h, 18, rng)
    detail = value_noise(w, h, 6, rng)
    img = Image.new("RGB", (w, h), FOG)
    d = ImageDraw.Draw(img)
    for _ in range(w * h // 40):
        d.point((rng.randrange(w), rng.randrange(h)), fill=FOG_SPECK)

    # Two discovered discs, one per teammate, overlapping in the middle.
    discs = [(54, 104, 40), (110, 98, 40)]

    def inside(x, y, pad=0):
        return any((x - cx) ** 2 + (y - cy) ** 2 <= (r + pad) ** 2 for cx, cy, r in discs)

    for y in range(h):
        for x in range(w):
            if inside(x, y):
                img.putpixel((x, y), terrain_color(noise(x, y) * 0.8 + detail(x, y) * 0.2))
            elif inside(x, y, 1):
                img.putpixel((x, y), EDGE)
    # Scattered trees on forest tiles
    for _ in range(140):
        x, y = rng.randrange(w), rng.randrange(h)
        if inside(x, y, -2) and terrain_color(noise(x, y) * 0.8 + detail(x, y) * 0.2) == TERRAIN[4][1]:
            d.rectangle([x, y, x + 1, y + 1], fill=(38, 76, 36))

    player(d, 30, 96, (196, 72, 64))
    player(d, 114, 92, (64, 120, 196))
    sync_arrows(d, 50, 110, 44)
    marker(d, 84, 130)

    img = img.resize((640, 640), Image.NEAREST)
    d = ImageDraw.Draw(img)
    d.text((320, 92), "TEAM MAP SYNC", font=font("ariblk.ttf", 60), fill=CREAM, anchor="mm", stroke_width=6, stroke_fill=OUTLINE)
    img.save(path, optimize=True)


def main():
    res = ROOT / "src/main/resources"
    res.mkdir(parents=True, exist_ok=True)
    out = res / "preview.png"
    preview(out)
    print(out, Image.open(out).size, out.stat().st_size // 1024, "KB")


if __name__ == "__main__":
    main()
