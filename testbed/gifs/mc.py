"""Minecraft look for the NYR plugins' listing GIFs: textures, the bitmap font, item icons and textured boxes, all read from a
local Minecraft client jar (MC_CLIENT_JAR, default the 26.2 client). Nothing here is drawn by hand or generated."""
import hashlib
import io
import json
import math
import os
import re
import uuid
import zipfile
from functools import lru_cache

from PIL import Image, ImageDraw

JAR = os.environ.get("MC_CLIENT_JAR", os.path.expandvars(r"%APPDATA%\.minecraft\versions\26.2\26.2.jar"))
ZIP = zipfile.ZipFile(JAR)


@lru_cache(maxsize=None)
def asset_bytes(path):
    try:
        return ZIP.read("assets/minecraft/" + path)
    except KeyError:
        return None


@lru_cache(maxsize=None)
def texture(path, frame=0):
    data = asset_bytes("textures/" + path + ".png")
    if data is None:
        return None
    im = Image.open(io.BytesIO(data)).convert("RGBA")
    if im.height > im.width and im.height % im.width == 0:  # animated strip
        frames = im.height // im.width
        f = frame % frames
        im = im.crop((0, f * im.width, im.width, (f + 1) * im.width))
    return im


def frames_of(path):
    data = asset_bytes("textures/" + path + ".png")
    if data is None:
        return 1
    im = Image.open(io.BytesIO(data))
    return im.height // im.width if im.height > im.width and im.height % im.width == 0 else 1


def ref(name):
    return name.split(":", 1)[-1]


# ------------------------------------------------------------------ font

COLORS = {c: tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) for c, h in zip("0123456789abcdef", [
    "000000", "0000AA", "00AA00", "00AAAA", "AA0000", "AA00AA", "FFAA00", "AAAAAA",
    "555555", "5555FF", "55FF55", "55FFFF", "FF5555", "FF55FF", "FFFF55", "FFFFFF"])}


class Glyph:
    def __init__(self, mask, advance, top):
        self.mask, self.advance, self.top = mask, advance, top


@lru_cache(maxsize=1)
def glyphs():
    table = {}
    providers = json.loads(asset_bytes("font/include/default.json"))["providers"]
    providers.sort(key=lambda p: 0 if "ascii.png" in p.get("file", "") else 1)
    for p in providers:
        if p.get("type") != "bitmap":
            continue
        sheet = texture(ref(p["file"]).replace(".png", ""))
        if sheet is None:
            continue
        rows = p["chars"]
        cols = max(len(r) for r in rows)
        cw, ch = sheet.width // cols, sheet.height // len(rows)
        height = p.get("height", 8)
        scale = height / ch
        for ry, row in enumerate(rows):
            for cx, char in enumerate(row):
                if char in table or char == "\u0000":
                    continue
                cell = sheet.crop((cx * cw, ry * ch, cx * cw + cw, ry * ch + ch))
                alpha = cell.getchannel("A")
                box = alpha.getbbox()
                right = box[2] if box else 0
                mask = alpha.resize((max(1, round(cw * scale)), max(1, round(ch * scale))), Image.NEAREST)
                table[char] = Glyph(mask, int(0.5 + right * scale) + 1, 7 - p.get("ascent", 7))
    table[" "] = Glyph(Image.new("L", (1, 8)), 4, 0)
    return table


def parse_codes(text):
    color, bold = COLORS["f"], False
    i = 0
    while i < len(text):
        c = text[i]
        if c == "§" and i + 1 < len(text):
            code = text[i + 1].lower()
            if code == "x" and i + 13 < len(text):
                hexs = "".join(text[i + 3 + 2 * k] for k in range(6))
                try:
                    color = tuple(int(hexs[k:k + 2], 16) for k in (0, 2, 4))
                except ValueError:
                    pass
                i += 14
                continue
            if code in COLORS:
                color, bold = COLORS[code], False
            elif code == "l":
                bold = True
            elif code == "r":
                color, bold = COLORS["f"], False
            i += 2
            continue
        yield c, color, bold
        i += 1


def text_width(text):
    g = glyphs()
    width = 0
    for c, _color, bold in parse_codes(text):
        glyph = g.get(c)
        width += (glyph.advance if glyph else 6) + (1 if bold else 0)
    return width


@lru_cache(maxsize=20000)
def tinted(char, color, scale):
    glyph = glyphs().get(char)
    if glyph is None:
        return None
    mask = glyph.mask.resize((glyph.mask.width * scale, glyph.mask.height * scale), Image.NEAREST)
    im = Image.new("RGBA", mask.size, color + (255,))
    im.putalpha(mask)
    return im


def draw_text(canvas, x, y, text, scale=2, shadow=True):
    g = glyphs()
    cx = x
    for c, color, bold in parse_codes(text):
        glyph = g.get(c)
        if glyph is None:
            cx += 6 * scale
            continue
        if c != " ":
            top = y + glyph.top * scale
            if shadow:
                sh = tinted(c, tuple(v // 4 for v in color), scale)
                canvas.alpha_composite(sh, (int(cx + scale), int(top + scale)))
                if bold:
                    canvas.alpha_composite(sh, (int(cx + 2 * scale), int(top + scale)))
            im = tinted(c, color, scale)
            canvas.alpha_composite(im, (int(cx), int(top)))
            if bold:
                canvas.alpha_composite(im, (int(cx + scale), int(top)))
        cx += (glyph.advance + (1 if bold else 0)) * scale
    return cx


def strip_codes(text):
    return re.sub("§.", "", text or "")


def wrap(text, width):
    words = re.split(r"( )", text)
    lines, current, current_w = [], "", 0
    for word in words:
        ww = text_width(word)
        if current and current_w + ww > width:
            lines.append(current)
            codes = re.findall("§.", current)
            carry = "".join(codes[-2:]) if codes else ""
            current, current_w = carry + word.lstrip(), text_width(word.lstrip())
        else:
            current += word
            current_w += ww
    if current:
        lines.append(current)
    return lines


# ------------------------------------------------------------------ geometry

def rot_y(v, deg):
    a = math.radians(deg)
    x, y, z = v
    return (x * math.cos(a) + z * math.sin(a), y, -x * math.sin(a) + z * math.cos(a))


def rot_x(v, deg):
    a = math.radians(deg)
    x, y, z = v
    return (x, y * math.cos(a) - z * math.sin(a), y * math.sin(a) + z * math.cos(a))


def shade(tex, factor):
    if factor >= 0.999:
        return tex
    r, g, b, a = tex.split()
    r, g, b = (ch.point(lambda v, s=factor: int(v * s)) for ch in (r, g, b))
    return Image.merge("RGBA", (r, g, b, a))


def paste_face(out, tex, po, pu, pv, offset=(0, 0)):
    """Maps a texture onto the parallelogram origin po, u end pu, v end pv (screen points, in out's pixels)."""
    w, h = tex.size
    ux, uy = (pu[0] - po[0]) / w, (pu[1] - po[1]) / w
    vx, vy = (pv[0] - po[0]) / h, (pv[1] - po[1]) / h
    det = ux * vy - uy * vx
    if abs(det) < 1e-9:
        return
    xs = [po[0], pu[0], pv[0], pu[0] + pv[0] - po[0]]
    ys = [po[1], pu[1], pv[1], pu[1] + pv[1] - po[1]]
    x0, y0 = int(math.floor(min(xs))) - 1, int(math.floor(min(ys))) - 1
    x1, y1 = int(math.ceil(max(xs))) + 1, int(math.ceil(max(ys))) + 1
    if x1 - x0 <= 0 or y1 - y0 <= 0 or x1 - x0 > 4000 or y1 - y0 > 4000:
        return
    a, b = vy / det, -vx / det
    d, e = -uy / det, ux / det
    ox, oy = po[0] - x0, po[1] - y0
    c = -(a * ox + b * oy)
    f = -(d * ox + e * oy)
    layer = tex.transform((x1 - x0, y1 - y0), Image.AFFINE, (a, b, c, d, e, f), resample=Image.NEAREST)
    out.alpha_composite(layer, (x0 + offset[0], y0 + offset[1]))


# ------------------------------------------------------------------ skins

SKINS = ["alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"]


def java_uuid_hash(u):
    msb = int.from_bytes(u.bytes[:8], "big", signed=True)
    lsb = int.from_bytes(u.bytes[8:], "big", signed=True)

    def fold(v):
        v &= 0xFFFFFFFFFFFFFFFF
        return (v ^ (v >> 32)) & 0xFFFFFFFF
    h = fold(msb) ^ fold(lsb)
    return h - (1 << 32) if h >= (1 << 31) else h


def skin_for(name):
    """The default skin an offline-mode server gives this name, as the client picks it."""
    raw = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode()).digest())
    raw[6] = (raw[6] & 0x0F) | 0x30
    raw[8] = (raw[8] & 0x3F) | 0x80
    index = java_uuid_hash(uuid.UUID(bytes=bytes(raw))) % 18
    model = "slim" if index < 9 else "wide"
    return texture(f"entity/player/{model}/{SKINS[index % 9]}"), model == "slim"


# ------------------------------------------------------------------ item icons

def resolve_item_model(node):
    t = ref(node.get("type", ""))
    if t == "model":
        return ("model", ref(node["model"]))
    if t == "special":
        return ("special", ref(node["model"]["type"]), node["model"].get("texture"))
    if "fallback" in node:
        return resolve_item_model(node["fallback"])
    if "cases" in node and node["cases"]:
        return resolve_item_model(node["cases"][0]["model"])
    if "entries" in node and node["entries"]:
        return resolve_item_model(node["entries"][0]["model"])
    if "on_false" in node:
        return resolve_item_model(node["on_false"])
    if "models" in node and node["models"]:
        return resolve_item_model(node["models"][0])
    return ("missing",)


def model_chain(path):
    textures, parents = {}, []
    while path:
        data = asset_bytes("models/" + path + ".json")
        if data is None:
            break
        m = json.loads(data)
        for k, v in m.get("textures", {}).items():
            textures.setdefault(k, v)
        parents.append(path)
        path = ref(m["parent"]) if "parent" in m else None
    return textures, parents


def resolve_tex(textures, key):
    v = textures.get(key)
    seen = 0
    while v and v.startswith("#") and seen < 8:
        v = textures.get(v[1:])
        seen += 1
    return ref(v) if v else None


def render_cube(faces, size=16.0, ry=225.0, rx=30.0, scale=0.625, lift=0.0, px=2):
    out = Image.new("RGBA", (16 * px, 16 * px))
    h = size / 2

    def project(p):
        q = rot_x(rot_y(p, ry), rx)
        return ((8 + q[0] * scale) * px, (8 - q[1] * scale - lift) * px, q[2])

    defs = {
        "up": ((-h, h, -h), (h, h, -h), (-h, h, h), (0, 1, 0)),
        "north": ((h, h, -h), (-h, h, -h), (h, -h, -h), (0, 0, -1)),
        "south": ((-h, h, h), (h, h, h), (-h, -h, h), (0, 0, 1)),
        "west": ((-h, h, -h), (-h, h, h), (-h, -h, -h), (-1, 0, 0)),
        "east": ((h, h, h), (h, h, -h), (h, -h, h), (1, 0, 0)),
    }
    visible = []
    for name, (o, ue, ve, n) in defs.items():
        nz = rot_x(rot_y(n, ry), rx)[2]
        if nz > 1e-6 and faces.get(name) is not None:
            po, pu, pv = project(o), project(ue), project(ve)
            visible.append(((po[2] + pu[2] + pv[2]) / 3, name, po, pu, pv))
    visible.sort()
    sides = [v for v in visible if v[1] != "up"]
    left_face = min(sides, key=lambda v: (v[2][0] + v[3][0]) / 2)[1] if sides else None
    for _depth, name, po, pu, pv in visible:
        tex = faces[name].resize((64, 64), Image.NEAREST)
        tex = shade(tex, 1.0 if name == "up" else (0.8 if name == left_face else 0.6))
        paste_face(out, tex, po, pu, pv)
    return out


@lru_cache(maxsize=1)
def missing_icon():
    return Image.new("RGBA", (32, 32), (80, 80, 80, 255))


@lru_cache(maxsize=None)
def base_icon(name, head=None, px=2):
    data = asset_bytes(f"items/{name}.json")
    kind = resolve_item_model(json.loads(data)["model"]) if data else ("model", "item/" + name)
    if kind[0] == "special":
        special = kind[1]
        if special in ("player_head", "head"):
            skin = skin_for(head or "Steve")[0] or texture("entity/player/wide/steve")
            crop = lambda x, y: skin.crop((x, y, x + 8, y + 8))
            layer = lambda x, y, ox: Image.alpha_composite(crop(x, y), crop(x + ox, y))
            faces = {"up": layer(8, 0, 32), "south": layer(8, 8, 32), "east": layer(16, 8, 32), "west": layer(0, 8, 32), "north": layer(24, 8, 32)}
            return render_cube(faces, size=8, ry=45, rx=30, scale=1.0, lift=-1.0, px=px)
        if special == "chest":
            t = texture("entity/chest/normal")
            top = t.crop((28, 0, 42, 14)).resize((16, 16), Image.NEAREST)
            front = Image.new("RGBA", (14, 15))
            front.paste(t.crop((14, 14, 28, 19)), (0, 0))
            front.paste(t.crop((14, 34, 28, 43)), (0, 5))
            side = Image.new("RGBA", (14, 15))
            side.paste(t.crop((0, 14, 14, 19)), (0, 0))
            side.paste(t.crop((0, 34, 14, 43)), (0, 5))
            front, side = front.resize((16, 16), Image.NEAREST), side.resize((16, 16), Image.NEAREST)
            return render_cube({"up": top, "north": front, "south": front, "east": side, "west": side}, px=px)
        return missing_icon()
    if kind[0] != "model":
        return missing_icon()
    textures, parents = model_chain(kind[1])
    flat = any(p.endswith("item/generated") or p.endswith("item/handheld") for p in parents)
    if flat or not any(p.startswith("block/") for p in parents):
        icon = Image.new("RGBA", (16, 16))
        for i in range(4):
            tref = resolve_tex(textures, f"layer{i}")
            if tref is None:
                break
            tex = texture(tref)
            if tex is not None:
                icon.alpha_composite(tex.resize((16, 16), Image.NEAREST))
        return icon.resize((16 * px, 16 * px), Image.NEAREST)

    def tex(*keys):
        for k in keys:
            r = resolve_tex(textures, k)
            if r and texture(r):
                return texture(r)
        return None
    faces = {"up": tex("up", "top", "end", "all", "particle"), "north": tex("north", "side", "all", "particle"),
             "south": tex("south", "side", "all", "particle"), "east": tex("east", "side", "all", "particle"),
             "west": tex("west", "side", "all", "particle")}
    return render_cube(faces, px=px)


@lru_cache(maxsize=1)
def glint_texture():
    return texture("misc/enchanted_glint_item")


def item_icon(name, glint=False, t_ms=0, px=2):
    icon = base_icon(name, None, px)
    if not glint:
        return icon
    g = glint_texture()
    size = icon.size[0]
    shift = int((t_ms / 3000.0) % 1.0 * g.width)
    tiled = Image.new("RGB", (g.width * 2, g.height * 2))
    for ox in (0, g.width):
        for oy in (0, g.height):
            tiled.paste(g.convert("RGB"), (ox, oy))
    window = tiled.crop((shift, shift // 2, shift + g.width, shift // 2 + g.height)).rotate(-30, resample=Image.NEAREST)
    window = window.resize((size, size), Image.BILINEAR)
    base = icon.copy()
    pi, pg = base.load(), window.load()
    for y in range(size):
        for x in range(size):
            r, gg, b, a = pi[x, y]
            if a:
                qr, qg, qb = pg[x, y]
                pi[x, y] = (min(255, r + int(qr * 0.55)), min(255, gg + int(qg * 0.4)), min(255, b + int(qb * 0.65)), a)
    return base


def pretty(name):
    """totem_of_undying -> Totem of Undying, as the game names it."""
    words = name.split("_")
    return " ".join(w if i and w in ("of", "and", "the", "on", "a", "with") else w.capitalize() for i, w in enumerate(words))


ROMAN = ["", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"]


def level_text(level):
    return ROMAN[level] if 0 < level <= 10 else str(level)


# ------------------------------------------------------------------ tooltip and cursor

def draw_tooltip(canvas, x, y, lines, scale=2):
    if not lines:
        return
    tw = max(text_width(line) for line in lines)
    th = 8 + (len(lines) - 1) * 10 + (2 if len(lines) > 1 else 0)
    gx, gy = x // scale + 12, y // scale - 12
    gx = min(gx, canvas.width // scale - tw - 8)
    gy = max(gy, 4)
    box = Image.new("RGBA", ((tw + 8) * scale, (th + 8) * scale))
    d = ImageDraw.Draw(box)
    d.rectangle([scale, 0, (tw + 7) * scale - 1, (th + 8) * scale - 1], fill=(16, 0, 16, 240))
    d.rectangle([0, scale, (tw + 8) * scale - 1, (th + 7) * scale - 1], fill=(16, 0, 16, 240))
    for i in range(th + 6):
        k = i / max(1, th + 5)
        col = (int(80 * (1 - k) + 40 * k), 0, int(255 * (1 - k) + 127 * k), 255)
        d.rectangle([scale, (1 + i) * scale, 2 * scale - 1, (2 + i) * scale - 1], fill=col)
        d.rectangle([(tw + 6) * scale, (1 + i) * scale, (tw + 7) * scale - 1, (2 + i) * scale - 1], fill=col)
    d.rectangle([scale, scale, (tw + 7) * scale - 1, 2 * scale - 1], fill=(80, 0, 255, 255))
    d.rectangle([scale, (th + 6) * scale, (tw + 7) * scale - 1, (th + 7) * scale - 1], fill=(40, 0, 127, 255))
    canvas.alpha_composite(box, (gx * scale - 3 * scale, gy * scale - 3 * scale))
    ty = gy * scale + scale
    for i, line in enumerate(lines):
        draw_text(canvas, gx * scale + scale, ty, line, scale)
        ty += (10 + (2 if i == 0 else 0)) * scale


def draw_cursor(canvas, x, y, pressed=False):
    pts = [(0, 0), (0, 17), (4, 13), (7, 20), (10, 19), (7, 12), (12, 12)]
    k = 0.85 if pressed else 1.0
    poly = [(x + px * k * 1.15, y + py * k * 1.15) for px, py in pts]
    ImageDraw.Draw(canvas).polygon(poly, fill=(255, 255, 255, 255), outline=(0, 0, 0, 255), width=2)
