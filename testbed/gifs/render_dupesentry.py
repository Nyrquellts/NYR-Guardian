#!/usr/bin/env python3
"""Draws the NYR DupeSentry recordings (gifs/scenes/dupesentry.mjs) as listing frames: the isometric world of render_world.py
with the rig's own blocks drawn in their shapes (piston facing east as the scene set it, piston head, torch, lever), the
lit TNT and items the camera received, the staff chat, the captions and the server's own TNT count.

    python render_dupesentry.py dupesentry-spigot|dupesentry-blocked|dupesentry-vanilla [--frame SECONDS]

Frames go to run/gifs/<scene>-frames for bbb_gifs.py. A moving piston block (the two ticks a pushed block travels) is
drawn as the block it becomes at that spot, from the recording's own next update there.
"""
import json
import os
import shutil
import sys

from PIL import Image

import mc
import models
import render_world as rw
import style

RUN, FPS, W, H = rw.RUN, rw.FPS, rw.W, rw.H


def piston_faces(front):
    """A piston facing east: the platform (or the inner face, once extended) east, the bottom west, sides turned to match."""
    side = mc.texture("block/piston_side")
    east_side = side.rotate(-90)
    return {"up": east_side, "down": east_side, "north": side.rotate(90), "south": east_side,
            "east": mc.texture(front), "west": mc.texture("block/piston_bottom")}


def head_sprite(px, sticky=False):
    top = mc.texture("block/piston_top_sticky" if sticky else "block/piston_top")
    side = mc.texture("block/piston_side").rotate(-90)
    arm = mc.texture("block/piston_side")
    plate = {"up": side, "down": side, "north": side, "south": side, "east": top, "west": top}
    rod = {k: models.crop(arm, 6, 4, 4, 12) for k in ("up", "down", "north", "south", "east", "west")}
    return models.draw_boxes([models.Box((4, 0, -8), (8, 16, 8), plate), models.Box((-12, 6, -2), (4, 10, 2), rod)], px)


def torch_sprite(px):
    tex = mc.texture("block/torch")
    stick = models.crop(tex, 7, 6, 2, 10)
    top = models.crop(tex, 7, 6, 2, 2)
    faces = {"up": top, "down": top, "north": stick, "south": stick, "west": stick, "east": stick}
    return models.draw_boxes([models.Box((-1, 0, -1), (1, 10, 1), faces)], px)


def lever_sprite(px):
    cobble = mc.texture("block/cobblestone")
    base = {k: cobble for k in ("up", "down", "north", "south", "west", "east")}
    tex = mc.texture("block/lever")
    stick = models.crop(tex, 7, 6, 2, 9)
    handle = {k: stick for k in ("up", "down", "north", "south", "west", "east")}
    return models.draw_boxes([models.Box((-3, 0, -4), (3, 3, 4), base),
                              models.Box((-1, 1, -1), (1, 10, 1), handle, pivot=(0, 1, 0), pitch=-40)], px)


def carpet_sprite(px, wool):
    tex = mc.texture("block/" + wool)
    faces = {k: tex for k in ("up", "down", "north", "south", "west", "east")}
    return models.draw_boxes([models.Box((-8, 0, -8), (8, 1, 8), faces)], px)


def block_sprite(world, name, x, y, z, px):
    if name in ("piston", "sticky_piston"):
        extended = world.blocks.get((x + 1, y, z)) == "piston_head"
        front = "block/piston_inner" if extended else ("block/piston_top_sticky" if name == "sticky_piston" else "block/piston_top")
        return models.draw_boxes([models.Box((-8, 0, -8), (8, 16, 8), piston_faces(front))], px)
    if name == "piston_head":
        return head_sprite(px, world.blocks.get((x - 1, y, z)) == "sticky_piston")
    if name.endswith("_carpet"):
        return carpet_sprite(px, name.replace("_carpet", "_wool"))
    if name == "rail":
        along_x = world.blocks.get((x - 1, y, z)) == "rail" or world.blocks.get((x + 1, y, z)) == "rail"
        return models.rail_sprite(px, True)
    if name == "torch":
        return torch_sprite(px)
    if name == "lever":
        return lever_sprite(px)
    faces = models.block_faces(name)
    return models.draw_boxes([models.Box((-8, 0, -8), (8, 16, 8), faces)], px)


def objects(world, cam, ground, t, px):
    """The shared renderer's entities, with this rig's blocks drawn in their own shapes."""
    blocks = world.blocks
    # the shared renderer draws the entities (lit TNT, items); it is shown only the ground, and the blocks are drawn here
    world.blocks = {k: v for k, v in blocks.items() if k[1] <= ground}
    try:
        keep = list(rw.objects(world, cam, ground, t, px))
    finally:
        world.blocks = blocks
    for (x, y, z), name in world.blocks.items():
        if y <= ground:
            continue
        sprite, anchor = block_sprite(world, name, x, y, z, px)
        sx, sy, depth = cam.point(x + 0.5, y, z + 0.5)
        keep.append((depth, sprite, int(sx - anchor[0]), int(sy - anchor[1]), None))
    keep.sort(key=lambda i: i[0])
    return keep


def resolve_moving(events):
    """A moving_piston at a spot becomes the block the next update there sets (the block the piston delivers)."""
    out = []
    for i, e in enumerate(events):
        if e["type"] == "block" and e["name"] == "moving_piston":
            final = next((f["name"] for f in events[i + 1:] if f["type"] == "block" and (f["x"], f["y"], f["z"]) == (e["x"], e["y"], e["z"])
                          and f["name"] != "moving_piston" and f["t"] - e["t"] < 600), "air")
            e = {**e, "name": final}
        out.append(e)
    return out


def render(scene, only_frame=None):
    data = json.load(open(os.path.join(RUN, f"{scene}.json"), encoding="utf-8"))
    style.PRODUCT["name"] = style.product_of(scene)
    events = resolve_moving(sorted(data["events"], key=lambda e: e["t"]))
    events, duration = style.warp(events, max_gap=1400, tail=900, idle=lambda e: e["type"] in ("entity", "gone"))
    events.sort(key=lambda e: e["t"])
    region, ground = data["region"], data["ground"]
    # the rig and the spots its blocks reach, not the whole recorded island
    lo, hi = dict(region["min"]), dict(region["max"])
    lo["x"], hi["x"], lo["z"], hi["z"] = lo["x"] + 1, hi["x"] - 1, lo["z"] + 1, hi["z"] - 1
    data["blocks"] = [b for b in data["blocks"] if lo["x"] <= b[0] <= hi["x"] and lo["z"] <= b[2] <= hi["z"]]
    frame_box = {"min": {**lo, "y": ground + 1}, "max": hi}
    cam = rw.Camera(frame_box, style.HEADER + 8, H - 110)
    px = cam.scale
    world = rw.World(data)
    captions = [e for e in events if e["type"] == "caption"]
    counts = [e for e in events if e["type"] == "count"]
    frames_dir = os.path.join(RUN, f"{scene}-frames")
    if only_frame is None:
        shutil.rmtree(frames_dir, ignore_errors=True)
    os.makedirs(frames_dir, exist_ok=True)
    start = captions[0]["t"] + 120
    total = int((duration - start) / 1000 * FPS)
    times = [only_frame * 1000] if only_frame is not None else [start + i * 1000 / FPS for i in range(total)]
    cursor = 0
    terrain_cache = {}
    for n, t in enumerate(times):
        while cursor < len(events) and events[cursor]["t"] <= t:
            world.apply(events[cursor], events[cursor]["t"])
            cursor += 1
        key = world.version
        if key not in terrain_cache:
            terrain_cache[key] = rw.terrain(world, cam, ground, int(t / 50))
        canvas = style.background().copy()
        shake = 0
        for (et, *_rest) in world.explosions:
            if 0 <= t - et < 300:
                shake = int(6 * (1 - (t - et) / 300))
        canvas.alpha_composite(terrain_cache[key], (shake, 0))
        for depth, sprite, x, y, label in objects(world, cam, ground, t, px):
            canvas.alpha_composite(sprite, (x + shake, y))
        rw.draw_effects(canvas, world, cam, t, px)
        shown = next((c for c in reversed(counts) if c["t"] <= t), None)
        if shown:
            # name the two TNTs the server counted: the lit one (an entity) and the TNT block
            for s in world.live.values():
                if s["kind"] == "tnt":
                    ex, ey, ez = rw.interpolate(s, t)
                    sx, sy, _ = cam.point(ex, ey + 0.5, ez)
                    rw.nametag(canvas, "lit TNT", sx - px * 1.55 + shake, sy)
            for (bx, by, bz), bname in world.blocks.items():
                if bname == "tnt" and by > ground:
                    sx, sy, _ = cam.point(bx + 0.5, by + 1.0, bz + 0.5)
                    rw.nametag(canvas, "TNT block", sx + shake, sy - px * 0.2)
        if shown:
            after = shown["after"]
            total_tnt = after["lit"] + after["blocks"]
            style.badge(canvas, W - 20 - 300, style.HEADER + 16, "TNT after the push (server count)",
                        f"{after['lit']} lit + {after['blocks']} block = {total_tnt}", danger=total_tnt > 1)
        style.chat_panel(canvas, world.chat, t, {}, order=("NyrOp",), names={"NyrOp": "Staff"}, width_px=W - 32, lines=3)
        caption = next((c for c in reversed(captions) if c["t"] <= t), None)
        style.header(canvas, caption, t)
        name = os.path.join(frames_dir, f"t{int(t):06d}.png" if only_frame is not None else f"f{n:04d}.png")
        canvas.convert("RGB").save(name)
    return name if only_frame is not None else f"{total} frames in {frames_dir}"


if __name__ == "__main__":
    scene = sys.argv[1]
    if len(sys.argv) > 3 and sys.argv[2] == "--frame":
        print(render(scene, float(sys.argv[3])))
    else:
        print(render(scene))
