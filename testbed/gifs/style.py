"""The frame around every NYR plugin GIF: size, background, caption bar, brand, badges, chat panel, timing and encoding.
(Copied from NYR Fixes; the caption title now shrinks to stay clear of the brand block.)"""
import os
import re
import shutil
import subprocess
from functools import lru_cache

from PIL import Image, ImageDraw, ImageFont

import mc

W, H = 900, 700
HEADER = 96
ACCENT = (46, 204, 113)
DANGER = (235, 64, 52)
# NYR brand red, as on the store thumbnails; ACCENT and DANGER keep meaning fixed and stopped inside a scene.
BRAND = (224, 25, 45)
MARK = os.environ.get("NRYQ_MARK", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "media", "brand", "nryq-mark-small.png"))
FFMPEG = shutil.which("ffmpeg") or "ffmpeg"
FONT_BLACK = r"C:\Windows\Fonts\seguibl.ttf"
FONT_BOLD = r"C:\Windows\Fonts\segoeuib.ttf"
FONT_SEMI = r"C:\Windows\Fonts\seguisb.ttf"
PRODUCT = {"name": ""}
# Scene recordings and the GIF files they become, per product.
PRODUCTS = {"combattag": "CombatTag Pro", "chunkhopper": "ChunkHopper", "smarttick": "SmartTick", "dupesentry": "DupeSentry"}
# Each GIF lands in ../run/store/<store folder>/<name>.gif; the "before" scenes (1) run on a server without the plugin.
OUTPUTS = {
    "combattag-logger": "nyr-combattag-pro-1-combat-logger",
    "combattag-dummy": "nyr-combattag-pro-2-killable-dummy",
    "combattag-reconnect": "nyr-combattag-pro-3-clean-reconnect",
    "chunkhopper-ground": "nyr-chunk-hopper-1-drops-on-the-ground",
    "chunkhopper-vacuum": "nyr-chunk-hopper-2-collected-and-sold",
    "chunkhopper-filter": "nyr-chunk-hopper-3-filter-menu",
    "smarttick-awake": "nyr-smarttick-1-trading-hall-awake",
    "smarttick-asleep": "nyr-smarttick-2-brains-asleep",
    "smarttick-trading": "nyr-smarttick-3-trades-still-work",
    "dupesentry-spigot": "nyr-dupesentry-1-dupe-on-spigot",
    "dupesentry-blocked": "nyr-dupesentry-2-dupe-blocked",
    "dupesentry-vanilla": "nyr-dupesentry-3-vanilla-kept",
}
STORE = {"combattag": "nyr-combattag-pro", "chunkhopper": "nyr-chunk-hopper", "smarttick": "nyr-smarttick", "dupesentry": "nyr-dupesentry"}


def product_of(scene):
    return PRODUCTS.get(scene.split("-")[0], "")


def output_name(scene):
    return OUTPUTS.get(scene, "nyr-" + scene) + ".gif"
# Game chatter that is not part of what a GIF shows: advancements and command feedback.
NOISE = re.compile(r"has made the advancement|^Changed the block|^Replaced a slot|^Killed \d+ entit|^Gave \d+|^Teleported|^Set the time|^Summoned")


@lru_cache(maxsize=1)
def fonts():
    return {
        "title": ImageFont.truetype(FONT_BLACK, 30),
        "detail": ImageFont.truetype(FONT_SEMI, 17),
        "brand": ImageFont.truetype(FONT_BLACK, 21),
        "product": ImageFont.truetype(FONT_BOLD, 16),
        "badge": ImageFont.truetype(FONT_BOLD, 15),
        "badge_big": ImageFont.truetype(FONT_BLACK, 26),
        "hero": ImageFont.truetype(FONT_BLACK, 44),
        "name": ImageFont.truetype(FONT_BOLD, 18),
        "state": ImageFont.truetype(FONT_SEMI, 14),
    }


@lru_cache(maxsize=1)
def background():
    bg = Image.new("RGBA", (W, H), (14, 16, 20, 255))
    glow = Image.new("RGBA", (W, H))
    d = ImageDraw.Draw(glow)
    for i in range(18):
        r = 420 - i * 20
        d.ellipse([W // 2 - r * 1.6, H // 2 - r, W // 2 + r * 1.6, H // 2 + r], fill=(40, 90, 70, 6))
    bg.alpha_composite(glow)
    d = ImageDraw.Draw(bg)
    d.rectangle([0, 0, W, HEADER - 1], fill=(10, 11, 14, 255))
    d.rectangle([0, HEADER - 1, W, HEADER - 1], fill=(44, 48, 56, 255))
    return bg


def header(canvas, caption, t):
    d = ImageDraw.Draw(canvas)
    f = fonts()
    d.rectangle([0, 0, W, HEADER - 1], fill=(10, 11, 14, 255))
    d.rectangle([0, HEADER - 1, W, HEADER - 1], fill=(44, 48, 56, 255))
    if caption:
        k = min(1.0, (t - caption["t"]) / 250.0)
        a = int(255 * k)
        d.rectangle([22, 22, 27, 76], fill=BRAND + (a,))
        # the title shrinks to stay clear of the brand block on the right
        title_font = f["title"]
        for size in range(30, 21, -1):
            title_font = ImageFont.truetype(FONT_BLACK, size)
            if d.textlength(caption["title"], font=title_font) <= W - 330:
                break
        d.text((40, 16 + (30 - title_font.size) // 2), caption["title"], font=title_font, fill=(255, 255, 255, a))
        d.text((41, 56), caption.get("detail", ""), font=f["detail"], fill=(185, 190, 198, a))
    # the NYR brand block: the NryQ fang mark, then NYR over the product name
    name = PRODUCT["name"] or ""
    nyr_w = d.textlength("NYR", font=f["brand"])
    text_w = max(nyr_w + 26, d.textlength(name, font=f["product"]))
    x_text = W - 26 - text_w
    d.text((x_text, 18), "NYR", font=f["brand"], fill=(255, 255, 255, 255))
    d.rectangle([x_text + nyr_w + 7, 31, x_text + nyr_w + 25, 35], fill=BRAND + (255,))
    d.text((x_text, 50), name, font=f["product"], fill=(200, 204, 212, 255))
    mark = brand_mark()
    canvas.alpha_composite(mark, (int(x_text - 14 - mark.width), (HEADER - mark.height) // 2))


@lru_cache(maxsize=1)
def brand_mark():
    """The NryQ fang mark, scaled down unchanged."""
    mark = Image.open(MARK).convert("RGBA")
    height = 50
    return mark.resize((round(mark.width * height / mark.height), height), Image.LANCZOS)


def badge(canvas, x, y, label, value, danger=False):
    f = fonts()
    d = ImageDraw.Draw(canvas)
    lw = d.textlength(label, font=f["badge"])
    vw = d.textlength(value, font=f["badge_big"])
    w = int(max(lw, vw) + 32)
    color = DANGER if danger else ACCENT
    box = Image.new("RGBA", (w, 64))
    bd = ImageDraw.Draw(box)
    bd.rounded_rectangle([0, 0, w - 1, 63], radius=12, fill=(12, 14, 18, 215), outline=color + (255,), width=3)
    bd.text((16, 7), label, font=f["badge"], fill=(170, 176, 186, 255))
    bd.text((16, 24), value, font=f["badge_big"], fill=color + (255,))
    canvas.alpha_composite(box, (x, y))


def chat_panel(canvas, chats, t, typing=None, order=(), names=None, x=16, width_px=560, lines=4):
    """The last chat lines the players received, newest at the bottom, each marked with whose chat it is."""
    names = names or {}
    merged = []
    for bot, entries in chats.items():
        for ct, text in entries:
            plain = mc.strip_codes(text).strip()
            if ct <= t and plain and not NOISE.search(plain):
                merged.append((ct, bot, text))
    # by time only: lines of one reply share a timestamp and must keep the order they arrived in
    merged.sort(key=lambda m: m[0])
    messages = []
    seen = {}
    for ct, bot, text in merged:
        plain = mc.strip_codes(text).strip()
        if plain in seen and ct - seen[plain] < 1000:
            continue  # a broadcast every player received once: shown once, under whoever got it first
        seen[plain] = ct
        tag = f"\u00a78[\u00a77{names.get(bot, bot)}\u00a78] "
        messages.append([(ct, (tag if i == 0 else "      ") + line) for i, line in enumerate(mc.wrap(text, width_px // 2 - 60))])
    # whole messages only, newest first until the panel is full
    shown = []
    for message in reversed(messages):
        if shown and len(shown) + len(message) > lines:
            break
        shown = message + shown
    line_h = 20
    typed = None
    if typing:
        for bot, (text, tt, ms) in typing.items():
            k = min(1.0, max(0.0, (t - tt) / ms))
            typed = (bot, text[: max(1, int(len(text) * k + 0.999))])
    total = len(shown) * line_h + (26 if typed else 0)
    if total == 0:
        return
    y = H - 14 - total
    for ct, text in shown:
        age = t - ct
        alpha = 150 if age < 7000 else max(0, int(150 * (1 - (age - 7000) / 1500)))
        if alpha <= 0:
            y += line_h
            continue
        bar = Image.new("RGBA", (width_px, line_h), (0, 0, 0, alpha))
        canvas.alpha_composite(bar, (x, y))
        mc.draw_text(canvas, x + 4, y + 2, text, 2)
        y += line_h
    if typed:
        bot, shown_text = typed
        bar = Image.new("RGBA", (width_px, 24), (0, 0, 0, 170))
        canvas.alpha_composite(bar, (x, y + 2))
        cursor = "_" if int(t / 300) % 2 == 0 else ""
        mc.draw_text(canvas, x + 4, y + 6, "\u00a7f" + shown_text + cursor, 2)


def warp(events, max_gap=1150, tail=1600, keep=(), idle=None, hold=2600):
    """Shortens idle stretches so the GIF keeps moving. Stretches with only background motion (event types listed in keep, or
    events the idle predicate accepts) count as idle. A caption stays up for at least hold ms (never longer than it did in
    the recording): the idle stretches before the next caption are given back the time it needs."""
    idle = idle or (lambda e: e["type"] in keep)
    moments = sorted({e["t"] for e in events if not idle(e)})
    if not moments:
        return events, tail
    gaps = [0] + [min(moments[i] - moments[i - 1], max_gap) for i in range(1, len(moments))]
    captions = sorted({e["t"] for e in events if e["type"] == "caption"})
    if hold:
        index = {m: i for i, m in enumerate(moments)}
        for a, b in zip(captions, captions[1:]):
            span = range(index[a] + 1, index[b] + 1)
            short = hold - sum(gaps[j] for j in span)
            # first give the idle stretches back the time they lost, then lengthen pauses (never the ticks of something moving)
            lost = {j: moments[j] - moments[j - 1] - gaps[j] for j in span}
            if short > 0 and sum(lost.values()) > 0:
                give, total = min(short, sum(lost.values())), sum(lost.values())
                for j in span:
                    gaps[j] += give * lost[j] / total
                short -= give
            pauses = {j: gaps[j] for j in span if moments[j] - moments[j - 1] >= 250}
            if short > 0 and sum(pauses.values()) > 0:
                total = sum(pauses.values())
                for j, gap in pauses.items():
                    gaps[j] += short * gap / total
    mapping, out = [], moments[0]
    for i, m in enumerate(moments):
        out = m if i == 0 else out + gaps[i]
        mapping.append((m, out))

    def remap(t):
        # piecewise: between two kept moments, time runs at the speed that fits the shortened gap
        lo, hi = 0, len(mapping) - 1
        if t <= mapping[0][0]:
            return t - mapping[0][0] + mapping[0][1]
        if t >= mapping[-1][0]:
            return mapping[-1][1] + (t - mapping[-1][0])
        while hi - lo > 1:
            mid = (lo + hi) // 2
            if mapping[mid][0] <= t:
                lo = mid
            else:
                hi = mid
        (a_in, a_out), (b_in, b_out) = mapping[lo], mapping[hi]
        return a_out + (t - a_in) * (b_out - a_out) / max(1, b_in - a_in)
    out = []
    for e in events:
        e = dict(e)
        e["t"] = remap(e["t"])
        out.append(e)
    end = max(e["t"] for e in out) + tail
    if hold and captions:
        end = max(end, remap(captions[-1]) + hold)
    return out, end


def encode(frames_dir, out, fps, total):
    subprocess.run([FFMPEG, "-y", "-loglevel", "error", "-framerate", str(fps), "-i", os.path.join(frames_dir, "f%04d.png"),
                    "-vf", "split[a][b];[a]palettegen=max_colors=256:stats_mode=diff[p];[b][p]paletteuse=dither=none:diff_mode=rectangle",
                    "-loop", "0", out], check=True)
    size = os.path.getsize(out) / 1e6
    return f"{out}: {total} frames, {size:.1f} MB"
