#!/usr/bin/env python3
"""Draws the NYR ChunkHopper listing recordings (scenes/chunkhopper.mjs) as GIF frames: the isometric farm from
render_world.py with a hopper and cactus drawn from their block models, the chunk's outline, a count of the item entities
lying in the chunk (the ones the camera's client was sent), what the Chunk Hopper holds (its own /chunkhopper info
answers), the filter menu from the window packets, Kai's chat and the caption bar.

    python render_chunkhopper.py chunkhopper-ground|chunkhopper-vacuum|chunkhopper-filter [--frame SECONDS]

Only what the recording holds is drawn, at the recorded times; textures, models and the font come from the client jar.
"""
import json
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

RUN = rw.RUN
FPS = 20
W, H = style.W, style.H
S = 3  # menu scale
CHUNK = (2, 2)


# ------------------------------------------------------------------ blocks the shared renderer draws as cubes

def crop(tex, box):
    return tex.crop(box) if tex else None


@lru_cache(maxsize=None)
def hopper_sprite(px):
    """The hopper's block model: the bowl, the middle and the spout, with the texture parts the model maps onto them."""
    side = mc.texture("block/hopper_outside")
    top = mc.texture("block/hopper_inside").copy()
    top.alpha_composite(mc.texture("block/hopper_top"))
    bowl = {"up": top, **{f: crop(side, (0, 0, 16, 6)) for f in ("north", "south", "west", "east")}}
    middle = {f: crop(side, (4, 6, 12, 12)) for f in ("north", "south", "west", "east")}
    spout = {f: crop(side, (6, 12, 10, 16)) for f in ("north", "south", "west", "east")}
    return models.draw_boxes([models.Box((-2, 0, -2), (2, 4, 2), spout), models.Box((-4, 4, -4), (4, 10, 4), middle),
                              models.Box((-8, 10, -8), (8, 16, 8), bowl)], px)


@lru_cache(maxsize=None)
def cactus_sprite(px):
    side = crop(mc.texture("block/cactus_side"), (1, 0, 15, 16))
    top = crop(mc.texture("block/cactus_top"), (1, 1, 15, 15))
    return models.draw_boxes([models.Box((-7, 0, -7), (7, 16, 7), {"up": top, "north": side, "south": side, "west": side, "east": side})], px)


class Without:
    """The world with some blocks left out, for the shared object drawing."""

    def __init__(self, world, names):
        self.live = world.live
        self.blocks = {k: v for k, v in world.blocks.items() if v not in names}


def objects(world, cam, ground, t, px):
    special = {"hopper": hopper_sprite, "cactus": cactus_sprite}
    items = rw.objects(Without(world, set(special)), cam, ground, t, px)
    for (x, y, z), name in world.blocks.items():
        if name in special and y > ground:
            sprite, anchor = special[name](px)
            sx, sy, depth = cam.point(x + 0.5, y, z + 0.5)
            items.append((depth, sprite, int(sx - anchor[0]), int(sy - anchor[1]), None))
    items.sort(key=lambda i: i[0])
    return items


# ------------------------------------------------------------------ badges

def items_in_chunk(world):
    cx, cz = CHUNK
    return sum(1 for s in world.live.values() if s["kind"] == "item" and cx * 16 <= s["x"] < cx * 16 + 16 and cz * 16 <= s["z"] < cz * 16 + 16)


def holds_badge(canvas, x, y, holds, counters, t):
    """What /chunkhopper info last said the hopper holds, as item icons with their totals."""
    f = style.fonts()
    pairs = re.findall(r"(\d+) ([a-z_]+)", holds or "")
    label = "Chunk Hopper holds" + ("" if pairs else ": nothing")
    sub = f"collected {counters['collected']}, earned {counters['earned']}" if counters else ""
    d = ImageDraw.Draw(canvas)
    width = max(int(d.textlength(label, font=f["badge"])), int(d.textlength(sub, font=f["state"])), 36 * max(1, len(pairs)) + 8) + 32
    box = Image.new("RGBA", (width, 94 if pairs else 60))
    bd = ImageDraw.Draw(box)
    bd.rounded_rectangle([0, 0, box.width - 1, box.height - 1], radius=12, fill=(12, 14, 18, 215), outline=style.ACCENT + (255,), width=3)
    bd.text((16, 7), label, font=f["badge"], fill=(170, 176, 186, 255))
    if sub:
        bd.text((16, 27), sub, font=f["state"], fill=(120, 220, 120, 255))
    for i, (count, name) in enumerate(pairs):
        ix, iy = 16 + i * 36, 50
        box.alpha_composite(mc.item_icon(name, False, t, 2), (ix, iy))
        text = count
        mc.draw_text(box, ix + 34 - mc.text_width(text) * 2, iy + 18, "§f" + text, 2)
    canvas.alpha_composite(box, (x - box.width, y))


# ------------------------------------------------------------------ the filter menu

@lru_cache(maxsize=None)
def menu_gui(rows):
    """A chest window of rows with the player's inventory under it, as the game draws it (two parts of generic_54)."""
    gui = mc.texture("gui/container/generic_54")
    top_h = 17 + rows * 18
    panel = Image.new("RGBA", (176, top_h + 96))
    panel.alpha_composite(gui.crop((0, 0, 176, top_h)), (0, 0))
    panel.alpha_composite(gui.crop((0, 126, 176, 222)), (0, top_h))
    return panel.resize((176 * S, (top_h + 96) * S), Image.NEAREST)


def slot_xy(slot, rows):
    size = rows * 9
    if slot < size:
        return 8 + (slot % 9) * 18, 18 + (slot // 9) * 18
    i = slot - size
    top_h = 17 + rows * 18
    if i < 27:
        return 8 + (i % 9) * 18, top_h + 14 + (i // 9) * 18
    return 8 + (i - 27) * 18, top_h + 72


def draw_menu(canvas, menu, t):
    rows = max(1, menu["size"] // 9)
    gui = menu_gui(rows)
    shade = Image.new("RGBA", (W, H - style.HEADER), (8, 9, 12, 120))
    canvas.alpha_composite(shade, (0, style.HEADER))
    ox, oy = (W - gui.width) // 2, style.HEADER + (H - style.HEADER - gui.height) // 2
    canvas.alpha_composite(gui, (ox, oy))
    title = mc.strip_codes(menu["title"] or "")
    mc.draw_text(canvas, ox + 8 * S, oy + 6 * S, "§8" + title, S, shadow=False)
    mc.draw_text(canvas, ox + 8 * S, oy + (17 + rows * 18 + 3) * S, "§8Inventory", S, shadow=False)
    for slot, item in enumerate(menu["slots"]):
        gx, gy = slot_xy(slot, rows)
        px, py = ox + gx * S, oy + gy * S
        changed = menu["changed"].get(slot)
        if changed is not None and 0 <= t - changed < 2400:
            age = t - changed
            alpha = 170 if age < 1600 else int(170 * (2400 - age) / 800)
            canvas.alpha_composite(Image.new("RGBA", (16 * S, 16 * S), style.ACCENT + (alpha,)), (px, py))
        if menu["hover"] == slot:
            canvas.alpha_composite(Image.new("RGBA", (16 * S, 16 * S), (255, 255, 255, 110)), (px, py))
        if not item:
            continue
        canvas.alpha_composite(mc.item_icon(item["name"], item.get("glint"), t, S), (px, py))
        if item.get("count", 1) > 1:
            count = str(item["count"])
            mc.draw_text(canvas, px + (17 - mc.text_width(count)) * S, py + 9 * S, "§f" + count, S)


# ------------------------------------------------------------------ frames

def render(scene, only_frame=None):
    data = json.load(open(os.path.join(RUN, f"{scene}.json"), encoding="utf-8"))
    style.PRODUCT["name"] = style.product_of(scene)
    events, duration = style.warp(data["events"], max_gap=1400, tail=2400,
                                  idle=lambda e: e["type"] in ("entity", "gone") and e.get("kind") != "player")
    events.sort(key=lambda e: e["t"])
    # the listing rule: the finished state (the last caption) stays up about 3 s before the loop, not longer
    duration = min(duration, max(e["t"] for e in events if e["type"] == "caption") + 3000)
    region, ground = data["region"], data["ground"]
    cam = rw.Camera(region, style.HEADER + 8, H - 8)
    px = cam.scale
    world = rw.World(data)
    captions = [e for e in events if e["type"] == "caption"]
    frames_dir = os.path.join(RUN, f"{scene}-frames")
    if only_frame is None:
        shutil.rmtree(frames_dir, ignore_errors=True)
    os.makedirs(frames_dir, exist_ok=True)
    start = min(e["t"] for e in events if e["type"] == "entity") + 60
    total = int((duration - start) / 1000 * FPS)
    times = [only_frame * 1000] if only_frame is not None else [start + i * 1000 / FPS for i in range(total)]
    cursor, terrain_cache, typing = 0, {}, {}
    holds, counters, menu = None, None, None
    danger = not scene.endswith(("vacuum", "filter"))
    for n, t in enumerate(times):
        while cursor < len(events) and events[cursor]["t"] <= t:
            e = events[cursor]
            world.apply(e, e["t"])
            k = e["type"]
            if k == "type":
                typing[e["bot"]] = (e["text"], e["t"], e["ms"])
            elif k == "send":
                typing.pop(e["bot"], None)
            elif k == "holds":
                holds = e["text"]
            elif k == "counters":
                counters = e
            elif k == "window":
                menu = {"size": e["size"], "title": e["title"], "slots": list(e["slots"]), "changed": {}, "hover": None}
            elif k == "slot" and menu is not None and 0 <= e["slot"] < len(menu["slots"]):
                if menu["slots"][e["slot"]] != e["item"]:
                    menu["changed"][e["slot"]] = e["t"]
                menu["slots"][e["slot"]] = e["item"]
            elif k == "hover" and menu is not None:
                menu["hover"] = e["slot"]
            elif k == "close":
                menu = None
            cursor += 1
        key = world.version
        if key not in terrain_cache:
            img = rw.terrain(world, cam, ground, int(t / 50))
            rw.outline_chunk(img, cam, ground, *CHUNK, (255, 214, 64, 230), region)
            terrain_cache[key] = img
        canvas = style.background().copy()
        canvas.alpha_composite(terrain_cache[key], (0, 0))
        for _depth, sprite, x, y, label in objects(world, cam, ground, t, px):
            canvas.alpha_composite(sprite, (x, y))
        rw.draw_effects(canvas, world, cam, t, px)
        spot = data.get("hopper")
        if spot and world.blocks.get((spot["x"], spot["y"], spot["z"])) == "hopper":
            # a label over the block the scene is about, as render_world's marks
            sx, sy, _ = cam.point(spot["x"] + 0.5, spot["y"] + 2.1, spot["z"] + 0.5)
            rw.nametag(canvas, "Chunk Hopper", sx, sy)

        count = items_in_chunk(world)
        style.badge(canvas, 24, style.HEADER + 18, f"On the ground in chunk {CHUNK[0]}, {CHUNK[1]}",
                    f"{count} item entit{'y' if count == 1 else 'ies'}", danger=danger and count > 0)
        if holds is not None:
            holds_badge(canvas, W - 24, style.HEADER + 18, holds, counters, t)
        if menu is not None:
            draw_menu(canvas, menu, t)
        style.chat_panel(canvas, world.chat, t, typing, order=("Kai",), width_px=W - 32, lines=4)
        caption = next((c for c in reversed(captions) if c["t"] <= t), None)
        style.header(canvas, caption, t)
        name = os.path.join(frames_dir, f"t{int(t):06d}.png" if only_frame is not None else f"f{n:04d}.png")
        canvas.convert("RGB").save(name)
    if only_frame is not None:
        return name
    return style.encode(frames_dir, os.path.join(RUN, style.output_name(scene)), FPS, total)


if __name__ == "__main__":
    scene = sys.argv[1]
    if len(sys.argv) > 3 and sys.argv[2] == "--frame":
        print(render(scene, float(sys.argv[3])))
    else:
        print(render(scene))
