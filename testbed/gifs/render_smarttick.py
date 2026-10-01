#!/usr/bin/env python3
"""Draws the NYR SmartTick recordings (scenes/smarttick.mjs) as listing GIFs.

    python render_smarttick.py smarttick-awake|smarttick-asleep|smarttick-trading [--frame SECONDS]

The hall scenes are an isometric view of the recorded corner of the hall: its blocks and villagers as the camera's client
received them, with badges that show the numbers the server printed in that recording (Paper's /mspt, SmartTick's count).
The trading scene draws the merchant window from the packets Kai received (window, slots, trade list) with the client
jar's villager GUI texture. Textures, models and the font come from the client jar; the villager model is built here.
"""
import json
import math
import os
import re
import shutil
import sys
from functools import lru_cache

from PIL import Image, ImageDraw

import mc
import models
import render_world as rw
import style

HERE = os.path.dirname(os.path.abspath(__file__))
RUN = os.path.join(HERE, "..", "run", "gifs")
FPS = 20
W, H = style.W, style.H
AMBER = (255, 190, 64)
MSPT = re.compile(r"(\d+(?:\.\d+)?)/(\d+(?:\.\d+)?)/(\d+(?:\.\d+)?)")
COUNT = re.compile(r"Villagers at the last scan: (\d+) loaded, (\d+) eligible, (\d+) asleep")


# ------------------------------------------------------------------ villager model

@lru_cache(maxsize=None)
def villager_texture():
    tex = mc.texture("entity/villager/villager").copy()
    for layer in ("entity/villager/type/plains", "entity/villager/profession/farmer"):
        over = mc.texture(layer)
        if over is not None:
            tex.alpha_composite(over)
    return tex


@lru_cache(maxsize=None)
def villager_sprite(scale, yaw_bucket):
    """A farmer in the Java villager model: head with nose and hat layer, body and robe, crossed arms, legs."""
    tex = villager_texture()
    cf = models.cube_faces
    B = models.Box
    arm = cf(tex, 44, 22, 4, 8, 4)
    boxes = [
        B((-4, 0, -2), (0, 12, 2), cf(tex, 0, 22, 4, 12, 4)),
        B((0, 0, -2), (4, 12, 2), cf(tex, 0, 22, 4, 12, 4)),
        B((-4, 12, -3), (4, 24, 3), cf(tex, 16, 20, 8, 12, 6)),
        B((-4.5, 3.5, -3.5), (4.5, 24.5, 3.5), cf(tex, 0, 38, 8, 20, 6)),
        B((-8, 15, -3), (-4, 23, 1), arm, pivot=(0, 21, -1), pitch=43),
        B((4, 15, -3), (8, 23, 1), arm, pivot=(0, 21, -1), pitch=43),
        B((-4, 15, -3), (4, 19, 1), cf(tex, 40, 38, 8, 4, 4), pivot=(0, 21, -1), pitch=43),
        B((-4, 24, -4), (4, 34, 4), cf(tex, 0, 0, 8, 10, 8)),
        B((-1, 23, -6), (1, 27, -4), cf(tex, 24, 0, 2, 4, 2)),
        B((-4.5, 23.5, -4.5), (4.5, 34.5, 4.5), cf(tex, 32, 0, 8, 10, 8)),
    ]
    return models.draw_boxes(boxes, scale, yaw_bucket * 22.5)


# ------------------------------------------------------------------ numbers the server printed

def readings(events):
    """(t, kind, value) for every /mspt 5 s average and SmartTick villager count that arrived in the chat."""
    out = []
    for e in events:
        if e["type"] != "chat":
            continue
        text = mc.strip_codes(e["text"])
        m = COUNT.search(text)
        if m:
            out.append((e["t"], "count", (int(m.group(1)), int(m.group(3)))))
        elif "/" in text and MSPT.search(text) and "avg" not in text:
            out.append((e["t"], "mspt", float(MSPT.search(text).group(1))))
    return out


def badge(canvas, x, y, label, value, color):
    f = style.fonts()
    d = ImageDraw.Draw(canvas)
    w = int(max(d.textlength(label, font=f["badge"]), d.textlength(value, font=f["badge_big"])) + 32)
    box = Image.new("RGBA", (w, 64))
    bd = ImageDraw.Draw(box)
    bd.rounded_rectangle([0, 0, w - 1, 63], radius=12, fill=(12, 14, 18, 225), outline=color + (255,), width=3)
    bd.text((16, 7), label, font=f["badge"], fill=(170, 176, 186, 255))
    bd.text((16, 24), value, font=f["badge_big"], fill=color + (255,))
    canvas.alpha_composite(box, (x, y))
    return w


# ------------------------------------------------------------------ the hall

@lru_cache(maxsize=None)
def faces_of(name):
    """A block's six faces; the composter's model names its textures in a form the shared model reader does not take."""
    try:
        return models.block_faces(name)
    except (AttributeError, KeyError, TypeError):
        pass
    whole = mc.texture(f"block/{name}")
    side = mc.texture(f"block/{name}_side") or whole
    top = mc.texture(f"block/{name}_top") or whole
    bottom = mc.texture(f"block/{name}_bottom") or top
    return {"up": top, "down": bottom, "north": side, "south": side, "west": side, "east": side}


def hall_objects(world, cam, ground, t, px, region):
    lo, hi = region["min"], region["max"]
    items = []
    for (x, y, z), name in world.blocks.items():
        if y <= ground:
            continue
        faces = faces_of(name)
        sprite, anchor = models.draw_boxes([models.Box((-8, 0, -8), (8, 16, 8), faces)], px)
        sx, sy, depth = cam.point(x + 0.5, y, z + 0.5)
        items.append((depth, sprite, int(sx - anchor[0]), int(sy - anchor[1])))
    for state in world.live.values():
        if state["kind"] != "villager":
            continue
        x, y, z = rw.interpolate(state, t)
        if y < ground + 0.5 or not (lo["x"] <= x <= hi["x"] + 1 and lo["z"] <= z <= hi["z"] + 1):
            continue  # the floors below, and the cells just outside the recorded blocks
        sprite, anchor = villager_sprite(px, rw.yaw_bucket(state["yaw"]))
        sx, sy, depth = cam.point(x, y, z)
        items.append((depth, sprite, int(sx - anchor[0]), int(sy - anchor[1])))
    items.sort(key=lambda i: i[0])
    return items


def render_hall(scene, data, only_frame):
    events, duration = style.warp(data["events"], max_gap=1200, tail=2800, idle=lambda e: e["type"] in ("entity", "gone"))
    events.sort(key=lambda e: e["t"])
    region, ground = data["region"], data["ground"]
    cam = rw.Camera(region, style.HEADER + 8, H - 110, margin=20)
    px = cam.scale
    world = rw.World(data)
    captions = [e for e in events if e["type"] == "caption"]
    values = readings(events)
    frames_dir = os.path.join(RUN, f"{scene}-frames")
    if only_frame is None:
        shutil.rmtree(frames_dir, ignore_errors=True)
    os.makedirs(frames_dir, exist_ok=True)
    start = min(e["t"] for e in events if e["type"] == "entity") + 60
    total = int((duration - start) / 1000 * FPS)
    times = [only_frame * 1000] if only_frame is not None else [start + i * 1000 / FPS for i in range(total)]
    cursor, typing, ground_img = 0, {}, None
    for n, t in enumerate(times):
        while cursor < len(events) and events[cursor]["t"] <= t:
            e = events[cursor]
            world.apply(e, e["t"])
            if e["type"] == "type":
                typing[e["bot"]] = (e["text"], e["t"], e["ms"])
            elif e["type"] == "send":
                typing.pop(e["bot"], None)
            cursor += 1
        if ground_img is None:
            ground_img = rw.terrain(world, cam, ground, 0)
        canvas = style.background().copy()
        canvas.alpha_composite(ground_img)
        for _depth, sprite, x, y in hall_objects(world, cam, ground, t, px, region):
            canvas.alpha_composite(sprite, (x, y))
        shown = [v for v in values if v[0] <= t]
        mspt = next((v[2] for v in reversed(shown) if v[1] == "mspt"), None)
        count = next((v[2] for v in reversed(shown) if v[1] == "count"), None)
        bx = 24
        if mspt is not None:
            color = style.DANGER if mspt >= 40 else AMBER if mspt >= 25 else style.ACCENT
            bx += badge(canvas, bx, style.HEADER + 16, "Paper /mspt, 5 s average", f"{mspt:g} ms per tick", color) + 14
        if count is not None:
            loaded, asleep = count
            badge(canvas, bx, style.HEADER + 16, "SmartTick: villagers loaded", f"{loaded:,}" + (f", {asleep:,} asleep" if asleep else ", all awake"),
                  style.ACCENT if asleep else (200, 204, 212))
        style.chat_panel(canvas, world.chat, t, typing, names={"NyrOp": "Staff"}, width_px=W - 32, lines=5)
        caption = next((c for c in reversed(captions) if c["t"] <= t), None)
        style.header(canvas, caption, t)
        name = os.path.join(frames_dir, f"t{int(t):06d}.png" if only_frame is not None else f"f{n:04d}.png")
        canvas.convert("RGB").save(name)
    return name if only_frame is not None else f"{total} frames in {frames_dir}"


# ------------------------------------------------------------------ the trade window

S = 3
GUI = (36, style.HEADER + 8)


@lru_cache(maxsize=1)
def lang():
    return json.loads(mc.asset_bytes("lang/en_us.json"))


def window_title(raw, level):
    """The merchant title as the game shows it: the profession's name, then the villager's level."""
    name = lang().get(raw, raw)
    level_name = lang().get(f"merchant.level.{level}")
    return f"{name} - {level_name}" if level_name else name


@lru_cache(maxsize=None)
def gui_background():
    return mc.texture("gui/container/villager").crop((0, 0, 276, 166)).resize((276 * S, 166 * S), Image.NEAREST)


@lru_cache(maxsize=None)
def sprite(path, w, h):
    return mc.texture(path).resize((w * S, h * S), Image.NEAREST)


@lru_cache(maxsize=None)
def button(highlighted):
    """The 88 x 20 trade button, nine-sliced from the game's 200 x 20 button sprite."""
    src = mc.texture("gui/sprites/widget/button_highlighted" if highlighted else "gui/sprites/widget/button")
    img = Image.new("RGBA", (88, 20))
    img.paste(src.crop((0, 0, 44, 20)), (0, 0))
    img.paste(src.crop((200 - 44, 0, 200, 20)), (44, 0))
    return img.resize((88 * S, 20 * S), Image.NEAREST)


def draw_item(canvas, name, count, x, y):
    if not name:
        return
    icon = mc.item_icon(name, False, 0, S)
    canvas.alpha_composite(icon.resize((16 * S, 16 * S), Image.NEAREST), (GUI[0] + x * S, GUI[1] + y * S))
    if count and count > 1:
        text = str(count)
        mc.draw_text(canvas, GUI[0] + (x + 17) * S - mc.text_width(text) * S, GUI[1] + (y + 9) * S, "§f" + text, S)


def slot_xy(slot):
    if slot == 0:
        return 136, 37
    if slot == 1:
        return 162, 37
    if slot == 2:
        return 220, 37
    if 3 <= slot < 30:
        i = slot - 3
        return 108 + (i % 9) * 18, 84 + (i // 9) * 18
    return 108 + (slot - 30) * 18, 142


def render_trading(scene, data, only_frame):
    events, duration = style.warp(data["events"], max_gap=1300, tail=2800)
    events.sort(key=lambda e: e["t"])
    names = {int(k): v for k, v in (data.get("item_names") or {}).items()}
    captions = [e for e in events if e["type"] == "caption"]
    frames_dir = os.path.join(RUN, f"{scene}-frames")
    if only_frame is None:
        shutil.rmtree(frames_dir, ignore_errors=True)
    os.makedirs(frames_dir, exist_ok=True)
    start = events[0]["t"]
    total = int((duration - start) / 1000 * FPS)
    times = [only_frame * 1000] if only_frame is not None else [start + i * 1000 / FPS for i in range(total)]
    cursor, typing, chats = 0, {}, {}
    window, trades, selected, last_check, level = None, None, None, [], None
    for n, t in enumerate(times):
        while cursor < len(events) and events[cursor]["t"] <= t:
            e = events[cursor]
            k = e["type"]
            if k == "chat":
                chats.setdefault(e["bot"], []).append((e["t"], e["text"]))
                plain = mc.strip_codes(e["text"])
                if "villager(s) within" in plain:
                    last_check = [e["text"]]
                elif plain.strip().startswith("- ") and last_check:
                    last_check.append(e["text"])
            elif k == "type":
                typing[e["bot"]] = (e["text"], e["t"], e["ms"])
            elif k == "send":
                typing.pop(e["bot"], None)
            elif k == "window":
                window = {"title": e["title"], "slots": list(e["slots"])}
            elif k == "slot" and window is not None and e["slot"] < len(window["slots"]):
                window["slots"][e["slot"]] = e["item"]
            elif k == "close":
                window, selected = None, None
            elif k == "trades":
                trades, level = e["offers"], e.get("level")
            elif k == "select":
                selected = e["index"]
            cursor += 1
        canvas = style.background().copy()
        if window is not None:
            canvas.alpha_composite(gui_background(), GUI)
            title = window_title(mc.strip_codes(window["title"] or ""), level)
            mc.draw_text(canvas, GUI[0] + (107 + (276 - 107) // 2) * S - mc.text_width(title) * S // 2, GUI[1] + 6 * S, "§8" + title, S, shadow=False)
            mc.draw_text(canvas, GUI[0] + (5 + 44) * S - mc.text_width("Trades") * S // 2, GUI[1] + 6 * S, "§8Trades", S, shadow=False)
            for i, offer in enumerate(trades or []):
                y = 18 + 20 * i
                canvas.alpha_composite(button(selected == i), (GUI[0] + 5 * S, GUI[1] + y * S))
                draw_item(canvas, names.get(offer.get("a")), offer.get("ac"), 5 + 5, y + 2)
                canvas.alpha_composite(sprite("gui/sprites/container/villager/trade_arrow", 10, 9), (GUI[0] + (5 + 55) * S, GUI[1] + (y + 5) * S))
                draw_item(canvas, names.get(offer.get("out")), offer.get("oc"), 5 + 68, y + 2)
            for slot, it in enumerate(window["slots"][:39]):
                if it:
                    x, y = slot_xy(slot)
                    draw_item(canvas, it["name"], it.get("count"), x, y)
        elif last_check:
            # the latest /smarttick check reply, large: what the server said about the villager
            y = style.HEADER + 150
            for line in last_check[-3:]:
                mc.draw_text(canvas, 40, y, line, 3)
                y += 40
        style.chat_panel(canvas, chats, t, typing, names={"NyrOp": "Staff"}, width_px=W - 32, lines=3)
        caption = next((c for c in reversed(captions) if c["t"] <= t), None)
        style.header(canvas, caption, t)
        name = os.path.join(frames_dir, f"t{int(t):06d}.png" if only_frame is not None else f"f{n:04d}.png")
        canvas.convert("RGB").save(name)
    return name if only_frame is not None else f"{total} frames in {frames_dir}"


def render(scene, only_frame=None):
    data = json.load(open(os.path.join(RUN, f"{scene}.json"), encoding="utf-8"))
    style.PRODUCT["name"] = style.product_of(scene)
    if scene == "smarttick-trading":
        return render_trading(scene, data, only_frame)
    return render_hall(scene, data, only_frame)


if __name__ == "__main__":
    if len(sys.argv) > 3 and sys.argv[2] == "--frame":
        print(render(sys.argv[1], float(sys.argv[3])))
    else:
        print(render(sys.argv[1]))
