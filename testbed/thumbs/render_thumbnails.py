#!/usr/bin/env python3
"""BuiltByBit store thumbnails for the NYR Guardian plugins: 1920x1080 PNGs plus 480x270 feed-size previews, in the
layout every NYR store thumbnail shares.

The layout: near-black ground, a tracked eyebrow with a red rule, a heavy title, a subtitle,
feature columns, a divider, two centred proof lines, the NryQ logo top right and a cluster of Minecraft items on the
right. BuiltByBit bars AI-generated listing media, so every pixel comes from the local Minecraft client jar's textures
(itemart.py), the NryQ logo file, installed Windows fonts, or shapes drawn here.

Nothing on a proof line is typed in. @checks@, @servers@ and the whole "tested" line come from a live matrix report
(the newest one that ran every product being rendered, or --report), counting only that product's own checks; a report
with any failure, error or log problem on a server the product ran on stops the render. @bench@ comes from the
product's benchmark report (BENCH below) and stops the render when there is none.

    python render_thumbnails.py                         renders every product into ../run/thumbs
    python render_thumbnails.py nyr-combattag-pro ...   renders the named ones
    python render_thumbnails.py --debug --out DIR       outlines text boxes and the art's reach, writes to DIR
    python render_thumbnails.py --preview --out DIR     lays out with "0" in place of numbers, for art work only;
                                                        every file it writes is named *-PREVIEW-*.png

Exits 1 if any item art comes too close to text, the logo or the canvas edge.
"""
import argparse
import glob
import json
import os
import sys
from functools import lru_cache

sys.dont_write_bytecode = True
import numpy as np  # noqa: E402
from PIL import Image, ImageDraw, ImageFont  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
REPORTS = os.path.join(HERE, "..", "reports")
sys.path.insert(0, HERE)
import itemart as art  # noqa: E402

# ------------------------------------------------------------------ copy: final wording, change it here
# plugin = the matrix id whose checks the proof line counts (a list for the suite)

PRODUCTS = {
    "nyr-combattag-pro": {
        "plugin": "combattag",
        "title": "NYR COMBATTAG PRO",
        "subtitle": "ANTI-COMBAT-LOG WITH A KILLABLE DUMMY",
        "features": [
            ("ACTION BAR TIMER", "A clean combat countdown,", "no chat spam"),
            ("COMBAT-LOG DUMMY", "Log out in a fight: a killable", "dummy drops your gear"),
            ("ZERO DEPENDENCIES", "No Citizens, no NPC plugin,", "Folia included"),
        ],
        "proof": "@checks@ LIVE CHECKS • HARD-KILL TESTED • 0 FAILURES",
    },
    "nyr-chunk-hopper": {
        "plugin": "chunkhopper",
        "title": "NYR CHUNK HOPPER",
        "subtitle": "CHUNK DROP COLLECTOR & AUTO-SELLER",
        "features": [
            ("1 PER CHUNK", "Drops go straight in,", "never onto the ground"),
            ("AUTO-SELL", "Sells into the owner's balance", "with any Vault economy"),
            ("9-SLOT FILTER", "Pick what it collects", "in one simple menu"),
        ],
        "proof": "@checks@ LIVE CHECKS • @servers@ SERVERS • 0 FAILURES",
    },
    "nyr-smarttick": {
        "plugin": "smarttick",
        "title": "NYR SMARTTICK",
        "subtitle": "TRADING-HALL LAG GOVERNOR",
        "features": [
            ("SMART VILLAGER AI", "Trading-hall brains sleep", "while the server is behind"),
            ("TRADES STILL WORK", "Sleeping villagers trade", "and restock as usual"),
            ("NOTHING DELETED", "No villager, mob or item", "is ever removed"),
        ],
        "proof": "@checks@ LIVE CHECKS • @bench@ • 0 FAILURES",
    },
    # Only what the live run proved: five dupes shown working on Spigot without the plugin and stopped with it. The
    # container "desync" and "cloned UUID" columns are left off (no such dupe proved; items carry no UUID).
    "nyr-dupesentry": {
        "plugin": "dupesentry",
        "title": "NYR DUPESENTRY",
        "subtitle": "PISTON & PORTAL DUPE PATCHER",
        "features": [
            ("PORTAL & GRAVITY", "Sand can't be duplicated", "through end portals"),
            ("PISTON DUPERS", "TNT, carpet and rail", "dupers stopped"),
            ("VANILLA KEPT", "TNT cannons, slime pushes", "and portals still work"),
        ],
        "proof": "@checks@ LIVE CHECKS • 5 DUPES BLOCKED • 0 FAILURES",
    },
    # the plugins that shipped, together; its checks are all four plugins' checks, every plugin installed on every server
    "nyr-guardian-suite": {
        "plugin": ["combattag", "chunkhopper", "smarttick", "dupesentry"],
        "eyebrow": "MINECRAFT PLUGIN SUITE",
        "title": "NYR GUARDIAN SUITE",
        "subtitle": "FOUR PLUGINS, ONE DOWNLOAD",
        "features": [
            ("COMBATTAG PRO", "Combat loggers leave", "a killable dummy"),
            ("CHUNKHOPPER", "Chunk drops collected", "and sold"),
            ("SMARTTICK", "Trading halls sleep", "while the server lags"),
            ("DUPESENTRY", "Piston and portal", "dupes stopped"),
        ],
        "proof": "@checks@ LIVE CHECKS • 4 PLUGINS • 0 FAILURES",
    },
}

# @bench@: which benchmark report a product quotes and how its numbers read on the proof line.
BENCH = {
    "nyr-smarttick": ("bench-smarttick-*.json", lambda data: f"{max(run['villagers'] for run in data['runs']):,} VILLAGER BENCHMARK"),
}

# ------------------------------------------------------------------ art: what each thumbnail shows, in canvas pixels
# at = (x, y, depth) of the object's centre, bigger depth is nearer; size = canvas pixels per texel; rot = turns applied
# in order about the camera axes (y turns, x tips the top towards the viewer, z rolls in the picture plane).

GLINT = {"strength": 0.32, "tile": 2.2, "angle": -24.0}


def scene_combattag_pro():
    # items: a diamond sword crossed with a netherite axe, and a skull
    return [
        art.item("diamond_sword", (1590, 615, -60), 20.0, [("y", 18), ("x", 12), ("z", 18)], glint=GLINT, thickness=1.5),
        art.item("netherite_axe", (1690, 625, -40), 19.0, [("y", -18), ("x", 12), ("z", -62)], thickness=1.5),
        art.skull((1645, 815, 60), 12.5, [("y", 205), ("x", 14)]),
    ]


def scene_chunk_hopper():
    # items: an enchanted hopper with gold ingots, emeralds and a cactus around it
    return [
        art.block("hopper", (1590, 640, 0), 17.5, [("y", 215), ("x", 22)], glint={**GLINT, "strength": 0.16}),
        art.block("cactus", (1765, 520, -80), 8.0, [("y", 150), ("x", 24), ("z", -5)]),
        art.item("gold_ingot", (1395, 640, 90), 10.0, [("y", 16), ("x", 10), ("z", 10)], thickness=1.5),
        art.item("emerald", (1440, 790, 70), 9.5, [("y", -16), ("x", 10), ("z", -8)], thickness=1.5),
        art.item("emerald", (1770, 780, 70), 8.5, [("y", 14), ("x", 10), ("z", 12)], thickness=1.5),
    ]


def scene_smarttick():
    # items: a clock, a villager spawn egg and a redstone repeater (with the composter a trading hall villager works at)
    return [
        art.item("clock_16", (1520, 540, -40), 18.0, [("y", 16), ("x", 10), ("z", 6)], thickness=1.5),
        art.block("composter", (1735, 575, -90), 9.0, [("y", 150), ("x", 24), ("z", -4)]),
        art.item("villager_spawn_egg", (1655, 765, 40), 14.0, [("y", -10), ("x", 6), ("z", -6)], thickness=1.5),
        art.block("repeater_1tick", (1440, 770, 70), 12.0, [("y", 36), ("x", 26), ("z", 2)]),
    ]


def scene_dupesentry():
    # items: an end portal frame, an anvil, a purple shulker box and a diamond above it
    return [
        art.block("end_portal_frame_filled", (1600, 700, 0), 14.0, [("y", 215), ("x", 22)]),
        art.block("anvil", (1760, 560, -80), 8.5, [("y", 150), ("x", 24), ("z", -5)]),
        art.shulker_box((1425, 600, -40), 7.0, [("y", 30), ("x", 24), ("z", 4)]),
        art.item("diamond", (1585, 480, 60), 11.0, [("y", -14), ("x", 8), ("z", 6)], glint=GLINT, thickness=1.5),
    ]


def scene_guardian_suite():
    # one piece for each plugin: the sword, the glinted hopper, the clock and the portal frame
    return [
        art.item("diamond_sword", (1545, 520, -90), 18.0, [("y", 18), ("x", 12), ("z", 18)], glint=GLINT, thickness=1.5),
        art.item("clock_16", (1760, 510, -70), 12.0, [("y", -16), ("x", 10), ("z", -6)], thickness=1.5),
        art.block("hopper", (1480, 735, 10), 11.5, [("y", 215), ("x", 22)], glint={**GLINT, "strength": 0.16}),
        art.block("end_portal_frame_filled", (1720, 735, 0), 10.5, [("y", 215), ("x", 22)]),
    ]


SCENES = {
    "nyr-combattag-pro": scene_combattag_pro,
    "nyr-chunk-hopper": scene_chunk_hopper,
    "nyr-smarttick": scene_smarttick,
    "nyr-dupesentry": scene_dupesentry,
    "nyr-guardian-suite": scene_guardian_suite,
}

# ------------------------------------------------------------------ style

W, H = 1920, 1080
OUT = os.path.join(HERE, "..", "run", "thumbs")
LOGO = os.environ.get("NRYQ_LOGO", os.path.join(HERE, "..", "..", "media", "brand", "nryq-logo.png"))
FONT_DIR = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts")

BG = 11  # #0b0b0b
WHITE = (255, 255, 255)
RED = (0xE0, 0x19, 0x2D)
GRAY_TEXT = (0xD8, 0xD8, 0xD8)
SEPARATOR = (0x3A, 0x3A, 0x3A)
DIVIDER = (0x33, 0x33, 0x33)

HEAVY = ("seguibl.ttf", None)  # Segoe UI Black
EYEBROW = ("bahnschrift.ttf", "Regular")
CONDENSED_BOLD = ("bahnschrift.ttf", "Bold Condensed")
CONDENSED = ("bahnschrift.ttf", "Condensed")
BULLET = HEAVY  # a round bullet for both proof lines, centred on the capitals

MARGIN = round(0.04 * W)
LOGO_W = round(0.11 * W)
LOGO_INSET = (round(0.025 * W), round(0.025 * H))
ART_BOX = (1080, 280, W, 920)  # where the item cluster may draw
EDGE = round(0.03 * W)  # art keeps this far from the canvas edge
FEATURES_RIGHT = round(0.655 * W)
FEATURE_PAD = 26
DIVIDER_RIGHT = round(0.68 * W)
SUBTITLE_RIGHT = round(0.69 * W)


@lru_cache(maxsize=None)
def font(face, size):
    file, variation = face
    f = ImageFont.truetype(os.path.join(FONT_DIR, file), size)
    if variation:
        f.set_variation_by_name(variation)
    return f


def cap(f):
    return -f.getbbox("H", anchor="ls")[1]


def layout_runs(runs):
    """Places [(text, font, rise)] runs one after another by advance width; returns placements and the ink's left and
    right edges."""
    x, lo, hi, placed = 0.0, None, None, []
    for s, f, rise in runs:
        b = f.getbbox(s, anchor="ls")
        if b[2] > b[0]:
            lo = x + b[0] if lo is None else min(lo, x + b[0])
            hi = x + b[2] if hi is None else max(hi, x + b[2])
        placed.append((x, s, f, rise))
        x += f.getlength(s)
    return placed, lo, hi


def runs_for(s, f, tracking=0.0, bullets=False):
    """Text as runs: one run, or one per character when tracked, or split around bullets drawn from BULLET and centred
    on the capitals of f."""
    if tracking:
        return [(ch, f, 0) for ch in s], tracking
    if bullets and "\u2022" in s:
        b = font(BULLET, f.size)
        box = b.getbbox("\u2022", anchor="ls")
        rise = -cap(f) / 2 - (box[1] + box[3]) / 2
        runs = []
        for i, part in enumerate(s.split("\u2022")):
            if i:
                runs.append(("\u2022", b, rise))
            runs.append((part, f, 0))
        return runs, 0.0
    return [(s, f, 0)], 0.0


def ink_width(s, f, tracking=0.0, bullets=False):
    runs, extra = runs_for(s, f, tracking, bullets)
    placed, lo, hi = layout_runs(runs)
    return hi - lo + extra * (len(runs) - 1)


def fit(face, texts, width, largest, smallest=10, bullets=False):
    """The largest size up to largest at which every text fits width."""
    for size in range(largest, smallest - 1, -1):
        if all(ink_width(t, font(face, size), bullets=bullets) <= width for t in texts):
            return size
    return smallest


def size_for_cap(face, cap_px):
    size = round(cap_px / 0.7)
    while cap(font(face, size)) > cap_px and size > 8:
        size -= 1
    return size


class Page:
    def __init__(self, image):
        self.image, self.draw, self.boxes = image, ImageDraw.Draw(image), []

    def text(self, x, baseline, s, f, fill, label, tracking=0.0, center=False, bullets=False):
        """Draws s with its ink starting at x (or centred on x) on the baseline; records and returns the ink box."""
        runs, extra = runs_for(s, f, tracking, bullets)
        placed, lo, hi = layout_runs(runs)
        hi += extra * (len(runs) - 1)
        left = x - lo - ((hi - lo) / 2 if center else 0)
        for i, (rx, rs, rf, rise) in enumerate(placed):
            self.draw.text((left + rx + extra * i, baseline + rise), rs, font=rf, fill=fill, anchor="ls")
        b = f.getbbox(s, anchor="ls")
        box = (left + lo, baseline + min(b[1], -cap(f)), left + hi, baseline + b[3])
        self.boxes.append((label, box))
        return box

    def rect(self, box, fill, label=None):
        self.draw.rectangle(box, fill=fill)
        if label:
            self.boxes.append((label, box))


class Layout:
    """Sizes and positions shared by the whole family, fitted to the longest copy of all products; the feature row is
    fitted to the products with the same number of columns."""

    def __init__(self, products, logo_left, columns=3, like=None):
        titles = [p["title"].upper() for p in products]
        self.eyebrow = font(EYEBROW, round(0.035 * H))
        self.tracking = 0.3 * self.eyebrow.size
        self.title = font(HEAVY, min(size_for_cap(HEAVY, 0.15 * H),
                                     fit(HEAVY, titles, logo_left - round(0.03 * W) - MARGIN, 260)))
        self.subtitle = font(HEAVY, fit(HEAVY, [p["subtitle"].upper() for p in products], SUBTITLE_RIGHT - MARGIN,
                                        round(0.065 * H)))
        self.column = (FEATURES_RIGHT - MARGIN) / columns
        self.square = round(0.014 * H)
        self.square_gap = round(0.6 * self.square)
        widths = [self.column - FEATURE_PAD] + [self.column - 2 * FEATURE_PAD] * (columns - 2) + [self.column - FEATURE_PAD]
        row = [p for p in products if len(p["features"]) == columns]
        # never larger than the layout it is like, so a row of four reads as the same family as a row of three
        self.feature = font(CONDENSED_BOLD, min([
            fit(CONDENSED_BOLD, [p["features"][i][0].upper() for p in row], w - self.square - self.square_gap,
                round(0.045 * H)) for i, w in enumerate(widths)] + ([like.feature.size] if like else [])))
        self.desc = font(CONDENSED, min([
            fit(CONDENSED, [line for p in row for line in p["features"][i][1:]], w, round(0.028 * H))
            for i, w in enumerate(widths)] + ([like.desc.size] if like else [])))
        self.tested = font(CONDENSED_BOLD, fit(CONDENSED_BOLD, [p["tested"].upper() for p in products], W - 2 * MARGIN,
                                               round(0.042 * H), bullets=True))
        self.proof = font(HEAVY, fit(HEAVY, [p["proof"].upper() for p in products], W - 2 * MARGIN, round(0.06 * H),
                                     bullets=True))
        # vertical rhythm, top down, and the two proof lines up from the bottom
        self.eyebrow_base = round(0.15 * H) + cap(self.eyebrow)
        self.title_base = self.eyebrow_base + round(0.045 * H) + cap(self.title)
        self.subtitle_base = self.title_base + round(0.052 * H) + cap(self.subtitle)
        self.features_top = self.subtitle_base + round(0.085 * H)
        self.feature_base = self.features_top + cap(self.feature)
        self.desc1_base = self.feature_base + round(1.6 * self.desc.size)
        self.desc2_base = self.desc1_base + round(1.36 * self.desc.size)
        self.divider_y = self.desc2_base + round(0.064 * H)
        self.proof_base = H - round(0.072 * H)
        self.tested_base = self.proof_base - cap(self.proof) - round(0.032 * H)

    def describe(self):
        return ", ".join(f"{k}={v.size if isinstance(v, ImageFont.FreeTypeFont) else round(v, 1)}"
                         for k, v in vars(self).items())


@lru_cache(maxsize=1)
def logo_mark():
    """The NryQ logo cropped to its content (its matte is 0-1 noise on pure black) and scaled to LOGO_W."""
    src = Image.open(LOGO).convert("RGB")
    value = np.asarray(src).max(axis=2)
    ys, xs = np.nonzero(value > 2)
    crop = src.crop((int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1))
    return crop.resize((LOGO_W, round(crop.height * LOGO_W / crop.width)), Image.LANCZOS)


def logo_box():
    mark = logo_mark()
    x1, y0 = W - LOGO_INSET[0], LOGO_INSET[1]
    return (x1 - mark.width, y0, x1, y0 + mark.height)


def render(slug, layout):
    product, things, L = PRODUCTS[slug], SCENES[slug](), layout
    xs, ys = [t.at[0] for t in things], [t.at[1] for t in things]
    glow = ((min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2, 900, 700)
    canvas = art.backdrop((W, H), glow, base=BG, lift=32.0)
    coverage = art.render_cluster(canvas, ART_BOX, things, ss=3, shadow=0.55)
    image = Image.fromarray(np.uint8(np.clip(np.round(canvas), 0, 255)))
    page = Page(image)

    eyebrow = page.text(MARGIN, L.eyebrow_base, product.get("eyebrow", "MINECRAFT PLUGIN"), L.eyebrow, WHITE, "eyebrow",
                        tracking=L.tracking)
    rule_h = max(2, round(0.006 * H))
    rule_x = round(eyebrow[2] + 0.9 * L.eyebrow.size)
    rule_y = round(L.eyebrow_base - cap(L.eyebrow) / 2 - rule_h / 2)
    page.rect((rule_x, rule_y, rule_x + round(0.07 * W) - 1, rule_y + rule_h - 1), RED, "rule")

    page.text(MARGIN, L.title_base, product["title"].upper(), L.title, WHITE, "title")
    page.text(MARGIN, L.subtitle_base, product["subtitle"].upper(), L.subtitle, WHITE, "subtitle")

    sq = L.square
    for i, (title, line1, line2) in enumerate(product["features"]):
        left = MARGIN + i * L.column
        x = round(left + (FEATURE_PAD if i else 0))
        sq_y = round(L.feature_base - cap(L.feature) / 2 - sq / 2)
        page.rect((x, sq_y, x + sq - 1, sq_y + sq - 1), RED)
        page.text(x + sq + L.square_gap, L.feature_base, title.upper(), L.feature, WHITE, f"feature {i + 1}")
        page.text(x, L.desc1_base, line1, L.desc, GRAY_TEXT, f"feature {i + 1} line 1")
        page.text(x, L.desc2_base, line2, L.desc, GRAY_TEXT, f"feature {i + 1} line 2")
        if i:
            page.rect((round(left), L.features_top - 6, round(left) + 1, L.desc2_base + round(0.3 * L.desc.size)),
                      SEPARATOR)
    page.rect((MARGIN, L.divider_y, DIVIDER_RIGHT - 1, L.divider_y + 1), DIVIDER, "divider")

    page.text(W / 2, L.tested_base, product["tested"].upper(), L.tested, WHITE, "tested line", center=True, bullets=True)
    page.text(W / 2, L.proof_base, product["proof"].upper(), L.proof, WHITE, "proof line", center=True, bullets=True)

    # the logo, composited lighten: its black matte takes the ground's colour and vanishes
    lx0, ly0, lx1, ly1 = logo_box()
    arr = np.array(image)
    arr[ly0:ly1, lx0:lx1] = np.maximum(arr[ly0:ly1, lx0:lx1], np.asarray(logo_mark(), dtype=np.uint8))
    page.boxes.append(("logo", (lx0, ly0, lx1, ly1)))
    return Image.fromarray(arr), page.boxes, coverage


def check(boxes, coverage, margin=14):
    """Item art must keep clear of text, rules and the logo, and of the canvas edge."""
    problems = []
    for label, (x0, y0, x1, y1) in boxes:
        x0, y0 = max(0, int(x0) - margin), max(0, int(y0) - margin)
        x1, y1 = min(W, int(x1) + margin + 1), min(H, int(y1) + margin + 1)
        if coverage[y0:y1, x0:x1].max(initial=0) > 0.02:
            problems.append(f"art within {margin}px of the {label}")
    ys, xs = np.nonzero(coverage > 0.02)
    if len(xs) and (xs.min() < EDGE or xs.max() >= W - EDGE or ys.min() < EDGE or ys.max() >= H - EDGE):
        problems.append(f"art within {EDGE}px of the canvas edge")
    return problems


def outline(image, boxes, coverage):
    d = ImageDraw.Draw(image)
    for _label, box in boxes:
        d.rectangle(box, outline=(0, 200, 255))
    ys, xs = np.nonzero(coverage > 0.02)
    if len(xs):
        d.rectangle((xs.min(), ys.min(), xs.max(), ys.max()), outline=(255, 200, 0))


SOFTWARE_ORDER = ["paper", "purpur", "spigot", "folia"]


def version_key(v):
    return tuple(int(p) for p in v.split("."))


def box_version(v):
    """How BuiltByBit's version boxes name it: 26.1.2 is "26.1", 1.20.6 stays "1.20.6"."""
    parts = v.split(".")
    return ".".join(parts[:2]) if int(parts[0]) >= 26 else v


def newest_report(plugins):
    """The newest matrix report in which every one of the plugins ran with checks."""
    for path in sorted(glob.glob(os.path.join(REPORTS, "matrix-*.json")), reverse=True):
        data = json.load(open(path, encoding="utf-8"))
        ran = {p["plugin"] for s in data["summaries"] for p in s.get("plugins", []) if not p.get("skipped") and p["checks"]}
        if set(plugins) <= ran:
            return path
    raise SystemExit(f"no matrix report in {os.path.normpath(REPORTS)} ran all of {', '.join(plugins)}: run matrix.mjs first")


def product_numbers(report, plugins):
    """(checks, servers, failures, tested line) for these plugins' own checks in one matrix report."""
    data = json.load(open(report, encoding="utf-8"))
    checks = failures = 0
    servers, software, versions = 0, set(), set()
    for summary in data["summaries"]:
        mine = [p for p in summary.get("plugins", []) if p["plugin"] in plugins]
        ran = [p for p in mine if not p.get("skipped") and p["checks"]]
        if not mine:
            continue
        # a server's log problems and errors count against every plugin that was on it
        failures += len(summary.get("logProblems", [])) + (1 if summary.get("error") else 0)
        if not ran:
            continue
        servers += 1
        for p in ran:
            checks += len(p["checks"])
            failures += sum(1 for c in p["checks"] if not c["ok"]) + (1 if p.get("error") else 0)
        name, _, version = summary["target"].partition("-")
        software.add(name)
        versions.add(version)
    if not servers:
        raise SystemExit(f"{os.path.basename(report)} has no checks for {', '.join(plugins)}")
    ordered = sorted(versions, key=version_key)
    span = box_version(ordered[0]) if len(ordered) == 1 else f"{box_version(ordered[0])} – {box_version(ordered[-1])}"
    names = " • ".join(s.upper() for s in SOFTWARE_ORDER if s in software)
    tested = f"LIVE-TESTED ON {names} ({span})"
    print(f"{os.path.basename(report)} for {', '.join(plugins)}: {checks} checks on {servers} servers, {failures} failures; {tested}")
    return checks, servers, failures, tested


def bench_text(slug):
    pattern, words = BENCH[slug]
    found = sorted(glob.glob(os.path.join(REPORTS, pattern)))
    if not found:
        raise SystemExit(f"{slug} quotes a benchmark, and there is no {pattern} in {os.path.normpath(REPORTS)}")
    data = json.load(open(found[-1], encoding="utf-8"))
    text = words(data)
    print(f"{os.path.basename(found[-1])} for {slug}: {text}")
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("products", nargs="*", help="slugs to render (default: all)")
    parser.add_argument("--debug", action="store_true", help="outline text boxes and the art's reach")
    parser.add_argument("--out", default=OUT, help="output directory (default: ../run/thumbs)")
    parser.add_argument("--report", help="the matrix report the proof lines quote (default: the newest that ran them all)")
    parser.add_argument("--preview", action="store_true", help="placeholder numbers, files named *-PREVIEW-*, for art work")
    args = parser.parse_args()
    for slug in args.products:
        if slug not in PRODUCTS:
            parser.error(f"unknown product {slug}; known: {', '.join(PRODUCTS)}")
    slugs = args.products or list(PRODUCTS)
    plugin_ids = lambda slug: [PRODUCTS[slug]["plugin"]] if isinstance(PRODUCTS[slug]["plugin"], str) else PRODUCTS[slug]["plugin"]
    if args.preview:
        for slug in slugs:
            product = PRODUCTS[slug]
            product["tested"] = "LIVE-TESTED ON PAPER • PURPUR • SPIGOT • FOLIA (1.20.6 – 26.1)"
            product["proof"] = product["proof"].replace("@checks@", "000").replace("@servers@", "00").replace("@bench@", "0,000 PREVIEW")
    else:
        report = args.report or newest_report(sorted({p for slug in slugs for p in plugin_ids(slug)}))
        for slug in slugs:
            product = PRODUCTS[slug]
            checks, servers, failures, tested = product_numbers(report, plugin_ids(slug))
            if failures:
                raise SystemExit(f"{slug}: the matrix report has {failures} failure(s) on its servers: a thumbnail cannot claim 0 failures")
            product["tested"] = tested
            product["proof"] = product["proof"].replace("@checks@", f"{checks:,}").replace("@servers@", str(servers))
            if "@bench@" in product["proof"]:
                product["proof"] = product["proof"].replace("@bench@", bench_text(slug))
    os.makedirs(args.out, exist_ok=True)
    columns = sorted({len(p["features"]) for p in PRODUCTS.values()})
    for p in PRODUCTS.values():  # products not rendered still size the family layout
        p.setdefault("tested", "LIVE-TESTED ON PAPER • PURPUR • SPIGOT • FOLIA (1.20.6 – 26.1)")
    family = Layout(list(PRODUCTS.values()), logo_box()[0], 3)
    layouts = {n: family if n == 3 else Layout(list(PRODUCTS.values()), logo_box()[0], n, like=family) for n in columns}
    for n, layout in layouts.items():
        print(f"layout ({n} columns):", layout.describe())
    failed = False
    for slug in slugs:
        image, boxes, coverage = render(slug, layouts[len(PRODUCTS[slug]["features"])])
        problems = check(boxes, coverage)
        if args.debug:
            outline(image, boxes, coverage)
        path = os.path.join(args.out, f"{slug}-PREVIEW-thumbnail.png" if args.preview else f"{slug}-thumbnail.png")
        image.save(path, optimize=True)
        image.resize((480, 270), Image.LANCZOS).save(path[:-4] + "-small.png", optimize=True)
        print(os.path.normpath(path), "OK" if not problems else "PROBLEMS: " + "; ".join(problems))
        failed |= bool(problems)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
