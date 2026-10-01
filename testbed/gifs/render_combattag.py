#!/usr/bin/env python3
"""Draws NYR CombatTag Pro's listing scenes (scenes/combattag.mjs recordings) as GIF frames: render_world.py's isometric
arena, plus what that renderer does not draw: the mannequin dummy with its name and the line under it, hits (the game's
red hurt tint) and swings, each player's action bar, and Kai's death screen with the game's font and button sprites.
Only what the recording holds is drawn, at the recorded times.

    python render_combattag.py combattag-logger|combattag-dummy|combattag-reconnect [--frame SECONDS]
"""
import json
import math
import os
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
# The action-bar readouts belong to the countdown part of a scene: from this caption on they are no longer drawn (Luna's
# own countdown keeps running in the game), so the scene's end is a still picture the GIF can hold.
BARS_UNTIL = {"combattag-dummy": "Luna kills the dummy"}
# Where a recording runs on after its last caption, the frames stop this many ms after it (the 2.5 to 3 s hold).
END_AFTER_LAST_CAPTION = {"combattag-dummy": 3300}


class World(rw.World):
    def __init__(self, data):
        super().__init__(data)
        self.hurts, self.swings, self.dying, self.dummies, self.bars = {}, {}, {}, {}, {}
        self.seen = set()
        self.death = None
        self.respawned = None

    def apply(self, e, t):
        k = e["type"]
        if k == "entity" and e.get("name"):
            self.seen.add(e["name"])
        if k == "entity" and e["id"] not in self.live:
            self.dying.pop(e["id"], None)  # back in the world (a respawned player keeps his entity id): alive again
        if k == "gone":
            gone = self.live.pop(e["id"], None)
            # a player who logs out just vanishes; an entity that died goes in a puff after its death animation
            if gone and e["id"] in self.dying:
                self.poofs.append((t, gone["x"], gone["y"], gone["z"]))
        elif k == "poof":
            self.dying[e["id"]] = t
        elif k == "hurt":
            self.hurts[e["id"]] = t
        elif k == "swing":
            self.swings[e["id"]] = t
        elif k == "dummy":
            self.dummies[e["id"]] = (e.get("name") or "", e.get("description") or "")
        elif k == "actionbar":
            self.bars[e["bot"]] = (t, e["text"])
        elif k == "death_screen":
            self.death = (t, e.get("text") or "", e.get("score"))
        elif k == "respawn":
            self.respawned = t
        else:
            super().apply(e, t)

    def online(self, name):
        return any(s["kind"] == "player" and s.get("name") == name for s in self.live.values())


@lru_cache(maxsize=None)
def figure(scale, yaw_bucket, name, walk_phase, hurt, swing):
    """models.player_sprite with the game's red hurt tint and the right arm swung forward (swing: 0 to 1 of a swing)."""
    skin, slim = mc.skin_for(name)
    if hurt:
        red = Image.new("RGBA", skin.size, (255, 40, 40, 255))
        red.putalpha(skin.getchannel("A"))
        skin = Image.blend(skin, red, 0.45)
    arm = 3 if slim else 4
    cf = models.cube_faces
    stride = math.sin(walk_phase * math.pi / 2) * 32 if walk_phase else 0
    boxes = [
        models.Box((-4, 0, -2), (0, 12, 2), cf(skin, 0, 16, 4, 12, 4), pivot=(-2, 12, 0), pitch=stride),
        models.Box((0, 0, -2), (4, 12, 2), cf(skin, 16, 48, 4, 12, 4), pivot=(2, 12, 0), pitch=-stride),
        models.Box((-4, 12, -2), (4, 24, 2), cf(skin, 16, 16, 8, 12, 4)),
        models.Box((-4 - arm, 12, -2), (-4, 24, 2), cf(skin, 40, 16, arm, 12, 4), pivot=(-4 - arm / 2, 22, 0), pitch=-stride - 95 * swing),
        models.Box((4, 12, -2), (4 + arm, 24, 2), cf(skin, 32, 48, arm, 12, 4), pivot=(4 + arm / 2, 22, 0), pitch=stride),
        models.Box((-4, 24, -4), (4, 32, 4), cf(skin, 0, 0, 8, 8, 8)),
        models.Box((-4.5, 23.5, -4.5), (4.5, 32.5, 4.5), cf(skin, 32, 0, 8, 8, 8)),
    ]
    return models.draw_boxes(boxes, scale, yaw_bucket * 22.5)


def objects(world, cam, ground, t, px):
    """Players, dummies and dropped stacks, far to near, each with the name lines the game shows over it."""
    items = []
    piles = {}
    for s in world.live.values():
        if s["kind"] == "item":
            piles.setdefault((round(s["x"] * 2), round(s["z"] * 2)), []).append(s["id"])
    for s in world.live.values():
        x, y, z = rw.interpolate(s, t)
        if y < ground - 0.5:
            continue
        sx, sy, depth = cam.point(x, y, z)
        kind = s["kind"]
        labels = []
        if kind in ("player", "mannequin"):
            moving = kind == "player" and t - s.get("moved_at", -10_000) < 200
            phase = (int(t / 80) % 4) if moving else 0
            hurt = 0 <= t - world.hurts.get(s["id"], -10_000) < 500 or s["id"] in world.dying
            since = t - world.swings.get(s["id"], -10_000)
            swing = round(math.sin(math.pi * since / 300) * 4) / 4 if 0 <= since < 300 else 0
            if kind == "player":
                owner = s.get("name") or "Steve"
                labels = [owner]
            else:
                name, description = world.dummies.get(s["id"], ("", ""))
                owner = mc.strip_codes(name) or "Kai"
                labels = [line for line in (name, description) if line]
            sprite, anchor = figure(px, rw.yaw_bucket(s["yaw"]), owner, phase, hurt, swing)
        elif kind == "item" and s.get("item"):
            size = max(14, int(px * 0.8))
            icon = mc.base_icon(s["item"], None, 2).resize((size, size), Image.NEAREST)
            pile = sorted(piles[(round(s["x"] * 2), round(s["z"] * 2))])
            spread = (pile.index(s["id"]) - (len(pile) - 1) / 2) * size * 0.62
            bob = math.sin(t / 300.0 + s["id"]) * px * 0.06
            sprite, anchor = icon, (int(size // 2 - spread), int(size + px * 0.12 + bob))
        else:
            continue
        sh = rw.shadow(max(6, int(px * (0.55 if kind != "item" else 0.3))))
        items.append((depth - 0.001, sh, int(sx - sh.width / 2), int(sy - sh.height / 2), None))
        items.append((depth, sprite, int(sx - anchor[0]), int(sy - anchor[1]), (labels, sx, sy) if labels else None))
    items.sort(key=lambda i: i[0])
    return items


def panel(canvas, x, y, label, text, alpha=255):
    """A readout of what a player's own screen showed, under whose screen it was."""
    f = style.fonts()
    width = max(250, mc.text_width(text) * 2 + 28)
    box = Image.new("RGBA", (width, 58))
    bd = ImageDraw.Draw(box)
    bd.rounded_rectangle([0, 0, width - 1, 57], radius=12, fill=(12, 14, 18, 215), outline=(60, 64, 72, 255), width=2)
    bd.text((14, 6), label, font=f["badge"], fill=(170, 176, 186, 255))
    mc.draw_text(box, 14, 30, text, 2)
    if alpha < 255:
        box.putalpha(box.getchannel("A").point(lambda v: v * alpha // 255))
    canvas.alpha_composite(box, (x, y))


@lru_cache(maxsize=None)
def button(kind):
    return mc.texture("gui/sprites/widget/" + kind).resize((400, 40), Image.NEAREST)


@lru_cache(maxsize=1)
def death_gradient():
    """The death screen's own fillGradient, 0x60500000 at the top to 0xA0803030 at the bottom."""
    h = H - style.HEADER
    strip = Image.new("RGBA", (1, h))
    for yy in range(h):
        k = yy / (h - 1)
        strip.putpixel((0, yy), (int(80 + 48 * k), int(48 * k), int(48 * k), int(96 + 64 * k)))
    return strip.resize((W, h))


def death_screen(canvas, text, score, age):
    """Kai's death screen as the client lays it out at GUI scale 2: title at 2x, the death message, the score, and the
    Respawn and Title Screen buttons, disabled for the first second as in the game."""
    top = style.HEADER
    # Kai's own view of the world is not in the recording, so a plain dark backdrop stands behind the game's red gradient
    canvas.alpha_composite(Image.new("RGBA", (W, H - top), (14, 16, 20, 255)), (0, top))
    canvas.alpha_composite(death_gradient(), (0, top))

    def centered(y, s, scale):
        mc.draw_text(canvas, int(W / 2 - mc.text_width(s) * scale / 2), top + y, s, scale)
    centered(2 * 60, "You Died!", 4)
    centered(2 * 85, "§f" + text, 2)
    if isinstance(score, (int, float)):
        centered(2 * 100, "Score: §e" + str(int(score)), 2)
    active = age >= 1000
    gui_h = (H - top) / 2
    for i, label in enumerate(("Respawn", "Title Screen")):
        by = int((gui_h / 4 + 72 + 24 * i) * 2)
        canvas.alpha_composite(button("button" if active else "button_disabled"), (W // 2 - 200, top + by))
        centered(by + 12, ("§f" if active else "§7") + label, 2)
    mc.draw_text(canvas, 18, top + 14, "§7Kai's screen", 2)


def render(scene, only_frame=None):
    data = json.load(open(os.path.join(RUN, f"{scene}.json"), encoding="utf-8"))
    style.PRODUCT["name"] = style.product_of(scene)
    # the players and the story move things; items settling and the dummy's knockback count as idle and are shortened
    events, duration = style.warp(data["events"], max_gap=1400, tail=2400,
                                  idle=lambda e: e["type"] in ("entity", "gone") and e.get("kind") not in ("player", None))
    events.sort(key=lambda e: e["t"])
    region, ground = data["region"], data["ground"]
    cam = rw.Camera(region, style.HEADER + 8, H - 8)
    px = cam.scale
    world = World(data)
    captions = [e for e in events if e["type"] == "caption"]
    frames_dir = os.path.join(RUN, f"{scene}-frames")
    if only_frame is None:
        shutil.rmtree(frames_dir, ignore_errors=True)
    os.makedirs(frames_dir, exist_ok=True)
    start = min(e["t"] for e in events if e["type"] == "entity") + 60
    if scene in END_AFTER_LAST_CAPTION and captions:
        duration = min(duration, captions[-1]["t"] + END_AFTER_LAST_CAPTION[scene])
    total = int((duration - start) / 1000 * FPS)
    times = [only_frame * 1000] if only_frame is not None else [start + i * 1000 / FPS for i in range(total)]
    cursor = 0
    terrain = {}
    for n, t in enumerate(times):
        while cursor < len(events) and events[cursor]["t"] <= t:
            world.apply(events[cursor], events[cursor]["t"])
            cursor += 1
        if world.version not in terrain:
            terrain[world.version] = rw.terrain(world, cam, ground, int(t / 50))
        canvas = style.background().copy()
        canvas.alpha_composite(terrain[world.version])
        labels = []
        for _depth, sprite, x, y, label in objects(world, cam, ground, t, px):
            canvas.alpha_composite(sprite, (x, y))
            if label:
                labels.append(label)
        rw.draw_effects(canvas, world, cam, t, px)
        for lines, sx, sy in labels:
            # the name on top; a mannequin's description on the line under it, where the game draws it
            for i, line in enumerate(lines):
                rw.nametag(canvas, line, sx, sy - px * 2.15 - (len(lines) - 1 - i) * 24)

        dead = world.death and (world.respawned is None or t < world.respawned)
        if dead:
            # Kai's own screen: the scene's readouts are not part of it
            death_screen(canvas, world.death[1], world.death[2], t - world.death[0])
        else:
            # each player's action bar while they are on the server and their client still shows it (60 ticks, the last
            # 20 fading)
            row = 0
            until = next((c["t"] for c in captions if c["title"] == BARS_UNTIL.get(scene)), None)
            for who in ("Kai", "Luna"):
                if who in world.bars and world.online(who) and (until is None or t < until):
                    bt, text = world.bars[who]
                    age = t - bt
                    if 0 <= age < 3000:
                        alpha = 255 if age < 2000 else int(255 * (3000 - age) / 1000)
                        panel(canvas, 20, style.HEADER + 14 + row * 66, f"{who}'s action bar", text, alpha)
                        row += 1
            keep = data.get("keep_after_quit") or (["Kai"] if scene == "combattag-logger" else [])
            for i, who in enumerate(data.get("hotbars") or []):
                if who not in world.inventory:
                    continue
                online = world.online(who) or who not in world.seen
                if not online and who not in keep:
                    continue
                items = world.inventory[who]
                label = f"{who}'s inventory" if online else f"{who} logged out with"
                rw.inventory_badge(canvas, W - 20 - 388 if i == 0 else 20, style.HEADER + 14 + (0 if i == 0 or row == 0 else row * 66),
                                   label + ("" if items else ": empty"), items, t)
        style.chat_panel(canvas, world.chat, t, None, order=("Kai", "Luna"), names={}, width_px=W - 32, lines=4)
        caption = next((c for c in reversed(captions) if c["t"] <= t), None)
        style.header(canvas, caption, t)
        name = os.path.join(frames_dir, f"t{int(t):06d}.png" if only_frame is not None else f"f{n:04d}.png")
        canvas.convert("RGB").save(name)
    if only_frame is not None:
        return name
    return f"{frames_dir}: {total} frames at {FPS} fps"


if __name__ == "__main__":
    scene = sys.argv[1]
    if len(sys.argv) > 3 and sys.argv[2] == "--frame":
        print(render(scene, float(sys.argv[3])))
    else:
        print(render(scene))
