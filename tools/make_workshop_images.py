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


def map_scene(w, h, discs, seed=5):
    """Fog of war with terrain revealed inside the given (cx, cy, r) discs, at pixel-art resolution."""
    rng = random.Random(seed)
    noise = value_noise(w, h, 18, rng)
    detail = value_noise(w, h, 6, rng)
    img = Image.new("RGB", (w, h), FOG)
    d = ImageDraw.Draw(img)
    for _ in range(w * h // 40):
        d.point((rng.randrange(w), rng.randrange(h)), fill=FOG_SPECK)

    def inside(x, y, pad=0):
        return any((x - cx) ** 2 + (y - cy) ** 2 <= (r + pad) ** 2 for cx, cy, r in discs)

    def height(x, y):
        return noise(x, y) * 0.8 + detail(x, y) * 0.2

    for y in range(h):
        for x in range(w):
            if inside(x, y):
                img.putpixel((x, y), terrain_color(height(x, y)))
            elif inside(x, y, 1):
                img.putpixel((x, y), EDGE)
    for _ in range(w * h // 180):
        x, y = rng.randrange(w), rng.randrange(h)
        if inside(x, y, -2) and terrain_color(height(x, y)) == TERRAIN[4][1]:
            d.rectangle([x, y, x + 1, y + 1], fill=(38, 76, 36))
    return img


def preview(path):
    img = map_scene(160, 160, [(54, 104, 40), (110, 98, 40)])
    d = ImageDraw.Draw(img)
    player(d, 30, 96, (196, 72, 64))
    player(d, 114, 92, (64, 120, 196))
    sync_arrows(d, 50, 110, 44)
    marker(d, 84, 130)

    img = img.resize((640, 640), Image.NEAREST)
    d = ImageDraw.Draw(img)
    d.text((320, 92), "TEAM MAP SYNC", font=font("ariblk.ttf", 60), fill=CREAM, anchor="mm", stroke_width=6, stroke_fill=OUTLINE)
    img.save(path, optimize=True)


TEXT_DIM = (196, 210, 222)


def card(path, title_zh, title_en, lines, art, seed):
    """1280x720 feature card: fog-of-war map background, text panel on the left, pixel art on the right."""
    w, h = 1280, 720
    low = map_scene(w // 4, h // 4, art["discs"], seed=seed)
    art["draw"](ImageDraw.Draw(low), low)
    img = low.resize((w, h), Image.NEAREST).convert("RGBA")
    overlay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    od.rounded_rectangle([60, 70, 700, 640], radius=18, fill=(10, 16, 28, 215), outline=(246, 232, 196, 90), width=2)
    img = Image.alpha_composite(img, overlay)
    d = ImageDraw.Draw(img)
    d.text((100, 110), title_zh, font=font("msyhbd.ttc", 52), fill=CREAM)
    d.text((102, 182), title_en, font=font("segoeuib.ttf", 26), fill=ACCENT)
    y = 250
    for zh, en in lines:
        d.rectangle([102, y + 14, 114, y + 26], fill=ACCENT)
        d.text((134, y - 4), zh, font=font("msyh.ttc", 28), fill=CREAM)
        d.text((134, y + 36), en, font=font("segoeuib.ttf", 19), fill=TEXT_DIM)
        y += 92
    if "overlay" in art:
        art["overlay"](d)
    d.text((w - 40, h - 30), "Team Map Sync · Necesse", font=font("segoeuib.ttf", 20), fill=TEXT_DIM, anchor="rm")
    img.convert("RGB").save(path, optimize=True)


def server_box(d, x, y):
    """A small server rack; (x, y) is its top-left corner."""
    d.rectangle([x - 1, y - 1, x + 22, y + 30], fill=OUTLINE)
    d.rectangle([x, y, x + 21, y + 29], fill=(92, 100, 118))
    for i in range(3):
        yy = y + 3 + i * 9
        d.rectangle([x + 3, yy, x + 18, yy + 5], fill=(54, 60, 74))
        d.rectangle([x + 14, yy + 2, x + 15, yy + 3], fill=(120, 220, 120))


def on_land(img, x, y):
    """Nearest grass pixel to (x, y), so characters never stand in water."""
    grass = TERRAIN[3][1]
    for r in range(0, 40):
        for dy in range(-r, r + 1):
            for dx in range(-r, r + 1):
                px, py = x + dx, y + dy
                if 0 <= px < img.width and 0 <= py < img.height and all(
                        img.getpixel((px + ox, py + oy)) == grass for ox in (-3, 0, 3) for oy in (-2, 0)):
                    return px, py
    return x, y


def player_on_land(d, img, x, y, shirt):
    px, py = on_land(img, x, y)
    player(d, px, py, shirt)


def vertical_arrows(d, x, y0, y1):
    """Down and up arrows between the server and the map."""
    for xx, start, end in ((x, y0, y1), (x + 9, y1, y0)):
        step = 1 if end > start else -1
        base = end - 6 * step
        lo, hi = min(start, base), max(start, base)
        d.rectangle([xx - 2, lo, xx + 2, hi], fill=OUTLINE)
        d.polygon([(xx, end + 2 * step), (xx - 6, base - step), (xx + 6, base - step)], fill=OUTLINE)
        d.rectangle([xx - 1, lo + 1, xx + 1, hi - 1], fill=ACCENT)
        d.polygon([(xx, end), (xx - 4, base), (xx + 4, base)], fill=ACCENT)


ART_LIVE = {
    "discs": [(212, 104, 36), (268, 96, 36)],
    "draw": lambda d, img: (player_on_land(d, img, 200, 112, (196, 72, 64)), player_on_land(d, img, 280, 104, (64, 120, 196)),
                            sync_arrows(d, 210, 270, 46)),
}
ART_CATCHUP = {
    "discs": [(240, 124, 38)],
    "draw": lambda d, img: (server_box(d, 229, 14), vertical_arrows(d, 236, 50, 84),
                            player_on_land(d, img, 240, 132, (64, 120, 196))),
}


def _draw_markers(d, img):
    marker(d, 214, 104)
    marker(d, 258, 128)
    marker(d, 282, 90)


def _marker_option(d):
    """An abstract "share with team" option drawn at full resolution, not a screenshot of the game's UI."""
    x, y = 790, 120
    d.rounded_rectangle([x, y, x + 410, y + 84], radius=8, fill=(60, 70, 88), outline=OUTLINE, width=4)
    d.rectangle([x + 24, y + 22, x + 64, y + 62], fill=OUTLINE)
    d.rectangle([x + 28, y + 26, x + 60, y + 58], fill=CREAM)
    d.line([(x + 33, y + 43), (x + 42, y + 52), (x + 56, y + 31)], fill=(60, 140, 60), width=6)
    d.text((x + 84, y + 24), "共享给队伍", font=font("msyhbd.ttc", 28), fill=CREAM, anchor="lm")
    d.text((x + 84, y + 60), "Share with team", font=font("segoeuib.ttf", 20), fill=ACCENT, anchor="lm")


ART_MARKERS = {"discs": [(246, 112, 46)], "draw": _draw_markers, "overlay": _marker_option}


def key_art(src, path):
    """Title over the Codex-generated key art (docs/workshop/images/codex/key-art.png, text-free by design)."""
    w, h = 1280, 720
    img = Image.open(src).convert("RGB").resize((w, h), Image.LANCZOS)
    d = ImageDraw.Draw(img)
    d.text((w // 2, 112), "TEAM MAP SYNC", font=font("ariblk.ttf", 92), fill=CREAM, anchor="mm", stroke_width=8, stroke_fill=OUTLINE)
    d.text((w // 2, 196), "队友探索，一图共享", font=font("msyhbd.ttc", 40), fill=ACCENT, anchor="mm", stroke_width=4, stroke_fill=OUTLINE)
    d.text((w // 2, 246), "Share map exploration and markers with your team", font=font("segoeuib.ttf", 26), fill=CREAM,
           anchor="mm", stroke_width=3, stroke_fill=OUTLINE)
    img.save(path, optimize=True)


def main():
    res = ROOT / "src/main/resources"
    out = ROOT / "docs/workshop/images"
    res.mkdir(parents=True, exist_ok=True)
    out.mkdir(parents=True, exist_ok=True)
    preview(res / "preview.png")
    codex_art = out / "codex/key-art.png"
    if codex_art.exists():
        key_art(codex_art, out / "00-key-art.png")
    card(out / "01-live.png", "队友探索，实时共享", "Your teammates' exploration, on your map", [
        ("队友看到的区域几秒内出现在你的地图上", "What a teammate uncovers shows up within seconds"),
        ("挖墙、盖房等地形变化也会同步", "Terrain changes they see are synced too"),
        ("只在同一队伍内共享", "Shared only within the same team"),
        ("服务端只转发压缩数据，几乎不占 TPS", "Server only relays compressed data; negligible TPS cost"),
    ], ART_LIVE, seed=11)
    card(out / "02-catchup.png", "离线回来，地图补齐", "Catch up on what you missed", [
        ("服务端为每个队伍保存合并后的地图", "The server keeps one merged map per team"),
        ("上线时自动补上队伍新探索的区域", "Joining fills in what your team explored"),
        ("你以前探索过的区域也会共享给队伍", "Your earlier exploration is shared as well"),
        ("后台限速发送，不卡顿", "Sent in the background with a bandwidth cap"),
    ], ART_CATCHUP, seed=23)
    card(out / "03-markers.png", "队伍共享标记", "Team map markers", [
        ("编辑标记时勾选「共享给队伍」", "Tick \"Share with team\" when editing a marker"),
        ("队友几秒内就能看到，修改和删除同步", "Teammates see it within seconds; edits and deletes sync"),
        ("不勾选的标记仍是你的个人标记", "Unticked markers stay personal"),
        ("标记保存在服务端，换电脑也在", "Stored on the server, not on one PC"),
    ], ART_MARKERS, seed=31)
    for p in [res / "preview.png", *sorted(out.glob("*.png"))]:
        print(p, Image.open(p).size, p.stat().st_size // 1024, "KB")


if __name__ == "__main__":
    main()
