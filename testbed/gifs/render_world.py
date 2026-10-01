#!/usr/bin/env python3
"""Draws capture.mjs world recordings (farm, drops) as listing GIFs: an isometric view of the recorded blocks and entities,
with the chat the players received and a caption bar.

    python render_world.py farm|drops [--frame SECONDS]

Only what the recording holds is drawn, at the recorded times; textures, models and the font come from the client jar.
"""
import json
import math
import os
import shutil
import subprocess
import sys
from functools import lru_cache

from PIL import Image, ImageDraw, ImageFilter, ImageFont

import mc
import models
import style
from render_menu import tooltip_lines

HERE = os.path.dirname(os.path.abspath(__file__))
RUN = os.path.join(HERE, "..", "run", "gifs")
FPS = 20
W, H = style.W, style.H


# ------------------------------------------------------------------ camera

class Camera:
    """Fits the recorded box into the picture below the caption bar."""

    def __init__(self, region, top, bottom, margin=36):
        lo, hi = region["min"], region["max"]
        # from the island's dirt edge to head height above the ground; the empty air higher up is not worth the room
        corners = [(x, y, z) for x in (lo["x"], hi["x"] + 1) for y in (lo["y"] - 2, lo["y"] + 3) for z in (lo["z"], hi["z"] + 1)]
        cams = [models.view(c) for c in corners]
        minx, maxx = min(c[0] for c in cams), max(c[0] for c in cams)
        miny, maxy = min(c[1] for c in cams), max(c[1] for c in cams)
        avail_w, avail_h = W - 2 * margin, bottom - top - 2 * margin
        self.scale = min(avail_w / (maxx - minx), avail_h / (maxy - miny))
        self.ox = (W - (maxx - minx) * self.scale) / 2 - minx * self.scale
        self.oy = top + (bottom - top - (maxy - miny) * self.scale) / 2 + maxy * self.scale

    def point(self, x, y, z):
        c = models.view((x, y, z))
        return self.ox + c[0] * self.scale, self.oy - c[1] * self.scale, c[2]


# ------------------------------------------------------------------ world state replay

class World:
    def __init__(self, data):
        self.blocks = {(b[0], b[1], b[2]): b[3] for b in data["blocks"]}
        self.version = 0
        self.entities = {}  # id -> list of states (t, state)
        self.live = {}
        self.hearts = []
        self.explosions = []
        self.poofs = []
        self.chat = {}
        self.inventory = {}
        self.newest = {}  # bot -> [(t, key, item)], items in the order they turned up, still held
        self.lost = []  # (t, item): stacks that left a hotbar, to recognise the item entity a thrown stack becomes
        self.leashes = {}  # mob id -> holder id
        self.ground = data.get("ground", -1000)

    def apply(self, e, t):
        k = e["type"]
        if k == "block":
            key = (e["x"], e["y"], e["z"])
            if e["name"] in ("air", "void_air", "cave_air"):
                self.blocks.pop(key, None)
            else:
                self.blocks[key] = e["name"]
            self.version += 1
        elif k == "entity":
            prev = self.live.get(e["id"])
            self.live[e["id"]] = {**e, "t": e["t"], "prev": prev, "born": (prev or {}).get("born", e["t"]), "moved_at": e["t"] if prev and (abs(prev["x"] - e["x"]) + abs(prev["z"] - e["z"])) > 0.02 else (prev or {}).get("moved_at", -10_000)}
        elif k == "gone":
            gone = self.live.pop(e["id"], None)
            self.leashes.pop(e["id"], None)
            if gone and gone["kind"] in ("player", "cow") and gone["y"] >= self.ground:
                self.poofs.append((t, gone["x"], gone["y"], gone["z"]))
        elif k == "leash":
            if e["holder"] in (-1, 0, None):
                self.leashes.pop(e["id"], None)
            else:
                self.leashes[e["id"]] = e["holder"]
        elif k == "hearts":
            self.hearts.append((t, e["id"]))
        elif k == "explode":
            self.explosions.append((t, e["x"], e["y"], e["z"]))
        elif k == "chat":
            self.chat.setdefault(e["bot"], []).append((t, e["text"]))
        elif k == "inventory":
            old = self.inventory.get(e["bot"], [])
            before = {json.dumps(i, sort_keys=True) for i in old}
            now = [json.dumps(i, sort_keys=True) for i in e["items"]]
            self.lost.extend((t, i) for i in old if json.dumps(i, sort_keys=True) not in now)
            self.inventory[e["bot"]] = e["items"]
            held = self.newest.setdefault(e["bot"], [])
            held.extend((t, key, item) for key, item in zip(now, e["items"]) if key not in before)
            held[:] = [h for h in held if h[1] in now]


def interpolate(state, t):
    prev = state.get("prev")
    if not prev:
        return state["x"], state["y"], state["z"]
    span = max(1, state["t"] - prev["t"])
    k = min(1.0, max(0.0, (t - state["t"]) / span + 1.0))
    # positions arrive every tick; draw between the last two for smooth motion
    k = min(1.0, max(0.0, (t - prev["t"]) / span))
    return (prev["x"] + (state["x"] - prev["x"]) * k, prev["y"] + (state["y"] - prev["y"]) * k, prev["z"] + (state["z"] - prev["z"]) * k)


# ------------------------------------------------------------------ terrain

def terrain(world, cam, ground, frame):
    """The ground layer and everything level with it, as one image; blocks above it are drawn with the entities."""
    img = Image.new("RGBA", (W, H))
    ground_blocks = {k: v for k, v in world.blocks.items() if k[1] <= ground}
    xs = [k[0] for k in ground_blocks] or [0]
    zs = [k[2] for k in ground_blocks] or [0]
    faces = []
    for (x, y, z), name in ground_blocks.items():
        tex = models.block_faces(name, frame // 2 if name == "lava" else 0)
        for face in ("up", "north", "south", "west", "east"):
            if not models.visible(face):
                continue
            if face != "up":
                nx, nz = {"north": (0, -1), "south": (0, 1), "west": (-1, 0), "east": (1, 0)}[face]
                if (x + nx, y, z + nz) in ground_blocks:
                    continue
            o, u, v = models.face_corners(x, y, z, x + 1, y + 1, z + 1, face)
            depth = sum(models.view(p)[2] for p in (o, u, v)) / 3
            faces.append((depth, tex[face], (o, u, v), face, name))
            if face != "up" and name in ("grass_block", "stone_bricks", "lava"):
                # the island's edge: two blocks of dirt under the ground, fading into the background
                for below in (1, 2):
                    o2, u2, v2 = models.face_corners(x, y - below, z, x + 1, y - below + 1, z + 1, face)
                    faces.append((depth - below * 0.01, models.block_faces("dirt")[face], (o2, u2, v2), face, "edge%d" % below))
    faces.sort(key=lambda f: f[0])
    for _depth, tex, (o, u, v), face, name in faces:
        if tex is None:
            continue
        po, pu, pv = cam.point(*o)[:2], cam.point(*u)[:2], cam.point(*v)[:2]
        factor = models.SHADE[face]
        if name.startswith("edge"):
            factor *= 0.85 if name == "edge1" else 0.7
        big = tex.resize((64, 64), Image.NEAREST)
        mc.paste_face(img, mc.shade(big, factor), po, pu, pv)
    return img


def chunk_lines(img, cam, ground, region, chunks, color=(255, 255, 255, 70), width=2):
    d = ImageDraw.Draw(img)
    lo, hi = region["min"], region["max"]
    y = ground + 1.02
    for x in range(lo["x"], hi["x"] + 2):
        if x % 16 == 0:
            a, b = cam.point(x, y, lo["z"]), cam.point(x, y, hi["z"] + 1)
            d.line([a[:2], b[:2]], fill=color, width=width)
    for z in range(lo["z"], hi["z"] + 2):
        if z % 16 == 0:
            a, b = cam.point(lo["x"], y, z), cam.point(hi["x"] + 1, y, z)
            d.line([a[:2], b[:2]], fill=color, width=width)


def outline_chunk(img, cam, ground, cx, cz, color, region, width=4):
    """The chunk's border on the ground, cut to the recorded island."""
    lo, hi = region["min"], region["max"]
    x0, x1 = max(cx * 16, lo["x"]), min(cx * 16 + 16, hi["x"] + 1)
    z0, z1 = max(cz * 16, lo["z"]), min(cz * 16 + 16, hi["z"] + 1)
    if x0 >= x1 or z0 >= z1:
        return
    y = ground + 1.03
    pts = [cam.point(x0, y, z0)[:2], cam.point(x1, y, z0)[:2], cam.point(x1, y, z1)[:2], cam.point(x0, y, z1)[:2]]
    ImageDraw.Draw(img).line(pts + [pts[0]], fill=color, width=width, joint="curve")


# ------------------------------------------------------------------ objects

def yaw_bucket(yaw):
    return int(round((yaw % (2 * math.pi)) / (2 * math.pi) * 16)) % 16


def fence_connections(world, x, y, z):
    def joins(dx, dz):
        n = world.blocks.get((x + dx, y, z + dz))
        return n is not None and ("fence" in n or n not in ("air", "grass", "short_grass", "tall_grass"))
    return joins(0, -1), joins(0, 1), joins(-1, 0), joins(1, 0)


@lru_cache(maxsize=None)
def shadow(size):
    img = Image.new("RGBA", (size * 2, size))
    ImageDraw.Draw(img).ellipse([0, 0, size * 2 - 1, size - 1], fill=(0, 0, 0, 70))
    return img.filter(ImageFilter.GaussianBlur(1.5))


def objects(world, cam, ground, t, px):
    """Everything above the ground, sorted far to near: blocks, cows, players, items, TNT."""
    items = []
    for (x, y, z), name in world.blocks.items():
        if y <= ground:
            continue
        if "fence" in name:
            sprite, anchor = models.fence_sprite(px, name.replace("_gate", "") if "gate" in name else name, *fence_connections(world, x, y, z))
        elif name in ("chest", "trapped_chest"):
            icon = mc.base_icon("chest", None, max(1, int(px / 16)))
            sprite, anchor = icon, (icon.width // 2, int(icon.height * 0.8))
        elif name == "rail":
            along_x = world.blocks.get((x - 1, y, z)) == "rail" or world.blocks.get((x + 1, y, z)) == "rail"
            sprite, anchor = models.rail_sprite(px, along_x)
        else:
            faces = models.block_faces(name)
            sprite, anchor = models.draw_boxes([models.Box((-8, 0, -8), (8, 16, 8), faces)], px)
        sx, sy, depth = cam.point(x + 0.5, y, z + 0.5)
        items.append((depth, sprite, int(sx - anchor[0]), int(sy - anchor[1]), None))
    # dropped stacks lying on one spot, spread side by side so each one can be seen
    piles = {}
    for state in world.live.values():
        if state["kind"] == "item":
            piles.setdefault((round(state["x"] * 2), round(state["z"] * 2)), []).append(state["id"])
    for state in world.live.values():
        x, y, z = interpolate(state, t)
        if y < ground - 0.5:
            continue  # fallen into a hole in the ground: hidden by the island
        sx, sy, depth = cam.point(x, y, z)
        moving = t - state.get("moved_at", -10_000) < 200
        phase = (int(t / 80) % 4) if moving else 0
        kind = state["kind"]
        if kind == "cow":
            sprite, anchor = models.cow_sprite(px, yaw_bucket(state["yaw"]), bool(state.get("baby")), phase)
        elif kind == "player":
            sprite, anchor = models.player_sprite(px, yaw_bucket(state["yaw"]), state.get("name") or "Steve", phase)
        elif kind == "creeper":
            # a lit creeper flashes white, faster as it swells
            age = t - state.get("born", t)
            sprite, anchor = models.creeper_sprite(px, yaw_bucket(state["yaw"]), int(age / (180 if age < 900 else 90)) % 2 == 1)
        elif kind == "minecart":
            sprite, anchor = models.minecart_sprite(px, yaw_bucket(state["yaw"]))
        elif kind == "leash_knot":
            sprite, anchor = models.leash_knot_sprite(px)
        elif kind == "tnt":
            sprite, anchor = models.tnt_sprite(px, int(t / 250) % 2 == 1)
            if world.blocks.get((math.floor(x), math.floor(y), math.floor(z))) in ("lava", "water"):
                # sunk in a pool: drawn half under the surface, cut off where the surface is
                surface = cam.point(x, math.floor(y) + 1, z)[1]
                sx, sy, depth = cam.point(x, math.floor(y) + 0.45, z)
                keep = int(surface - (sy - anchor[1]) + px * 0.25)
                sprite = sprite.crop((0, 0, sprite.width, max(1, min(sprite.height, keep))))
        elif kind == "item" and state.get("item"):
            size = max(14, int(px * 0.8))
            icon = mc.base_icon(state["item"], None, 2).resize((size, size), Image.NEAREST)
            pile = sorted(piles[(round(state["x"] * 2), round(state["z"] * 2))])
            spread = (pile.index(state["id"]) - (len(pile) - 1) / 2) * size * 0.62
            bob = math.sin(t / 300.0 + state["id"]) * px * 0.06
            sprite, anchor = icon, (int(size // 2 - spread), int(size + px * 0.12 + bob))
        else:
            continue
        if kind != "leash_knot":
            sh = shadow(max(6, int(px * (0.55 if kind in ("cow", "player", "minecart") else 0.3))))
            items.append((depth - 0.001, sh, int(sx - sh.width / 2), int(sy - sh.height / 2), None))
        label = state.get("name") if kind == "player" else state.get("label") if kind == "cow" else None
        lift = 2.15 if kind == "player" else 1.75
        items.append((depth, sprite, int(sx - anchor[0]), int(sy - anchor[1]), (label, sx, sy - px * lift) if label else None))
    items.sort(key=lambda i: i[0])
    return items


@lru_cache(maxsize=None)
def particle(name, size):
    tex = mc.texture("particle/" + name)
    return tex.resize((size, size), Image.NEAREST) if tex else None


def draw_effects(canvas, world, cam, t, px):
    for (ht, eid) in world.hearts:
        age = t - ht
        state = world.live.get(eid)
        if 0 <= age < 1400 and state:
            x, y, z = state["x"], state["y"], state["z"]
            for i in range(3):
                a = age - i * 180
                if a < 0:
                    continue
                sx, sy, _ = cam.point(x + (i - 1) * 0.35, y + 1.2 + a / 900.0, z)
                heart = particle("heart", max(10, int(px * 0.35)))
                canvas.alpha_composite(heart, (int(sx - heart.width / 2), int(sy)))
    for (et, x, y, z) in world.explosions:
        age = t - et
        if 0 <= age < 900:
            for i in range(10):
                frame = min(15, int(age / 55) + i % 3)
                ang = i * 2.4
                r = 0.3 + (i % 4) * 0.45
                sx, sy, _ = cam.point(x + math.cos(ang) * r, y + 0.4 + (i % 3) * 0.5, z + math.sin(ang) * r)
                p = particle(f"explosion_{frame}", int(px * (1.1 + (i % 3) * 0.3)))
                if p:
                    canvas.alpha_composite(p, (int(sx - p.width / 2), int(sy - p.height / 2)))
    for (pt, x, y, z) in world.poofs:
        age = t - pt
        if 0 <= age < 700:
            for i in range(8):
                frame = min(7, int(age / 90))
                ang = i * 0.785
                sx, sy, _ = cam.point(x + math.cos(ang) * 0.5 * (1 + age / 700), y + 0.8 + (i % 3) * 0.4, z + math.sin(ang) * 0.5 * (1 + age / 700))
                p = particle(f"generic_{7 - frame}", int(px * 0.4))
                if p:
                    canvas.alpha_composite(p, (int(sx - p.width / 2), int(sy - p.height / 2)))


@lru_cache(maxsize=1)
def hotbar_image():
    return mc.texture("gui/sprites/hud/hotbar").resize((182 * 2, 22 * 2), Image.NEAREST)


def draw_leashes(canvas, world, cam, t, px):
    """A lead from each leashed mob to what holds it: a fence knot or a player's hand, sagging a little."""
    d = ImageDraw.Draw(canvas)
    for mob_id, holder_id in world.leashes.items():
        mob, holder = world.live.get(mob_id), world.live.get(holder_id)
        if not mob or not holder:
            continue
        mx, my, mz = interpolate(mob, t)
        hx, hy, hz = interpolate(holder, t)
        a = cam.point(mx, my + 0.85, mz)
        b = cam.point(hx, hy + (1.1 if holder["kind"] == "player" else 0.6), hz)
        sag = px * 0.35
        points = [(a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k + sag * 4 * k * (1 - k)) for k in [i / 12 for i in range(13)]]
        # thick enough to follow at listing size: a dark edge under the lead's own brown
        width = max(3, round(px / 8))
        d.line(points, fill=(38, 24, 12, 255), width=width + 2, joint="curve")
        d.line(points, fill=(150, 104, 60, 255), width=width, joint="curve")


def count_kind(world, kind, cx, cz):
    return sum(1 for s in world.live.values() if s["kind"] == kind and cx * 16 <= s["x"] < cx * 16 + 16 and cz * 16 <= s["z"] < cz * 16 + 16)


def inventory_badge(canvas, x, y, label, items, t):
    """A player's hotbar as the recording last saw it, under a label."""
    f = style.fonts()
    box = Image.new("RGBA", (364 + 24, 44 + 44))
    bd = ImageDraw.Draw(box)
    bd.rounded_rectangle([0, 0, box.width - 1, box.height - 1], radius=12, fill=(12, 14, 18, 215), outline=(60, 64, 72, 255), width=2)
    bd.text((14, 8), label, font=f["badge"], fill=(170, 176, 186, 255))
    box.alpha_composite(hotbar_image(), (12, 34))
    for i, entry in enumerate(items[:9]):
        name, count = (entry["name"], entry["count"]) if isinstance(entry, dict) else (entry[0], entry[1])
        glint = bool(entry.get("glint")) if isinstance(entry, dict) else False
        sx, sy = 12 + (3 + 20 * i) * 2, 34 + 3 * 2
        box.alpha_composite(mc.item_icon(name, glint, t, 2), (sx, sy))
        if count > 1:
            text = str(count)
            mc.draw_text(box, sx + (17 - mc.text_width(text)) * 2, sy + 9 * 2, "§f" + text, 2)
    canvas.alpha_composite(box, (x, y))


def nametag(canvas, label, sx, sy):
    width = mc.text_width(label) * 2
    box = Image.new("RGBA", (width + 8, 22), (0, 0, 0, 110))
    canvas.alpha_composite(box, (int(sx - width / 2 - 4), int(sy - 2)))
    mc.draw_text(canvas, int(sx - width / 2), int(sy + 1), label, 2, shadow=False)


# ------------------------------------------------------------------ scenes

# Scenes whose action keeps to the middle of the recorded box: blocks left out of the picture on each side (low x, high x,
# low z, high z), so the camera comes closer.
VIEWS = {"illegal-anywhere": (1, 2, 3, 3)}


def render(scene, only_frame=None):
    data = json.load(open(os.path.join(RUN, f"{scene}.json"), encoding="utf-8"))
    style.PRODUCT["name"] = style.product_of(scene)
    if scene in VIEWS:
        lo, hi = dict(data["region"]["min"]), dict(data["region"]["max"])
        cut = VIEWS[scene]
        lo["x"], hi["x"], lo["z"], hi["z"] = lo["x"] + cut[0], hi["x"] - cut[1], lo["z"] + cut[2], hi["z"] - cut[3]
        data["region"] = {"min": lo, "max": hi}
        data["blocks"] = [b for b in data["blocks"] if lo["x"] <= b[0] <= hi["x"] and lo["z"] <= b[2] <= hi["z"]]
    # players walking are the story; cows wandering, items settling and TNT sinking count as idle and are shortened
    events, duration = style.warp(data["events"], max_gap=1400, tail=2400,
                                  idle=lambda e: e["type"] in ("entity", "gone", "leash") and e.get("kind") != "player")
    badges = data.get("badges") or []
    hotbars = data.get("hotbars") or (["Kai", "Luna"] if scene.startswith("drops") else [])
    events.sort(key=lambda e: e["t"])
    region, ground = data["region"], data["ground"]
    cam = Camera(region, style.HEADER + 8, H - 8)
    px = cam.scale
    world = World(data)
    captions = [e for e in events if e["type"] == "caption"]
    frames_dir = os.path.join(RUN, f"{scene}-frames")
    if only_frame is None:
        shutil.rmtree(frames_dir, ignore_errors=True)
    os.makedirs(frames_dir, exist_ok=True)
    # from the first tick the camera saw the entities, so the opening frame is never an empty island
    start = min(e["t"] for e in events if e["type"] == "entity") + 60
    total = int((duration - start) / 1000 * FPS)
    times = [only_frame * 1000] if only_frame is not None else [start + i * 1000 / FPS for i in range(total)]
    cursor = 0
    terrain_cache = {}
    typing = {}
    for n, t in enumerate(times):
        while cursor < len(events) and events[cursor]["t"] <= t:
            e = events[cursor]
            world.apply(e, e["t"])
            if e["type"] == "type":
                typing[e["bot"]] = (e["text"], e["t"], e["ms"])
            elif e["type"] == "send":
                typing.pop(e["bot"], None)
            cursor += 1
        frame = int(t / 50)
        key = (world.version, frame // 2 % 20 if any(v == "lava" for v in world.blocks.values()) else 0)
        if key not in terrain_cache:
            terrain_cache[key] = terrain(world, cam, ground, frame)
            if badges:
                chunk_lines(terrain_cache[key], cam, ground, region, None)
        canvas = style.background().copy()
        shake = 0
        for (et, *_rest) in world.explosions:
            if 0 <= t - et < 300:
                shake = int(6 * (1 - (t - et) / 300))
        canvas.alpha_composite(terrain_cache[key], (shake, 0))

        shown = []
        for i, b in enumerate(badges):
            count = count_kind(world, b["kind"], *b["chunk"])
            if b.get("always") or count:
                full = count >= b["limit"]
                shown.append((b, count, full))
                colour = (235, 64, 52, 255) if full else ((255, 214, 64, 230) if i == 0 else (120, 220, 120, 230))
                outline_chunk(canvas, cam, ground, *b["chunk"], colour, region)

        labels = []
        for depth, sprite, x, y, label in objects(world, cam, ground, t, px):
            canvas.alpha_composite(sprite, (x + shake, y))
            if label:
                labels.append(label)
        draw_leashes(canvas, world, cam, t, px)
        draw_effects(canvas, world, cam, t, px)
        if data.get("tooltips"):
            # a thrown stack on the ground: the tooltip of the stack that left a hotbar as the item entity appeared
            for s in world.live.values():
                if s["kind"] != "item":
                    continue
                source = next((i for lt, i in reversed(world.lost) if i["name"] == s.get("item") and abs(s["born"] - lt) < 1500), None)
                if source and (source.get("enchantments") or source.get("unbreakable")):
                    ix, iy, iz = interpolate(s, t)
                    sx, sy, _ = cam.point(ix, iy + 0.4, iz)
                    mc.draw_tooltip(canvas, int(sx), int(sy), tooltip_lines(source), 2)
        for label, sx, sy in labels:
            nametag(canvas, label, sx + shake, sy)
        # a label on a block the scene points at, shown from the caption that names it
        for mark in data.get("marks", []):
            named = next((c for c in captions if c["title"] == mark.get("from")), None)
            if named is None or t >= named["t"]:
                mx, my, _ = cam.point(mark["x"], mark["y"], mark["z"])
                nametag(canvas, mark["text"], mx + shake, my)

        for k, (b, count, full) in enumerate(shown):
            style.badge(canvas, 24, style.HEADER + 18 + k * 74, f"Chunk {b['chunk'][0]}, {b['chunk'][1]}", f"{count} / {b['limit']} {b['label']}", danger=full)
        for i, who in enumerate(hotbars):
            if who in world.inventory:
                items = world.inventory[who]
                left = not badges and i == 0
                bx = 20 if left else W - 20 - 388
                inventory_badge(canvas, bx, style.HEADER + 14, f"{who}'s inventory" + ("" if items else ": empty"), items, t)
                # tooltips of the two items that last turned up in the hotbar, newest on the right, as the game shows them
                if who in data.get("tooltips", []) and world.newest.get(who):
                    tx = bx
                    for _t, _key, item in world.newest[who][-2:]:
                        lines = tooltip_lines(item)
                        mc.draw_tooltip(canvas, tx - 14, style.HEADER + 14 + 88 + 36, lines, 2)
                        tx += (max(mc.text_width(line) for line in lines) + 8) * 2 + 10

        style.chat_panel(canvas, world.chat, t, typing, order=("Kai", "Luna", "NyrOp"), names={"NyrOp": "Staff"}, width_px=W - 32, lines=6)
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
