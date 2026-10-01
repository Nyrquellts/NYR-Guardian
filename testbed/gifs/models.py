"""Textured box models seen through one fixed isometric camera: blocks, fences, cows, players, TNT. Every face is a crop of a
real texture from the client jar, mapped onto its parallelogram; nothing is painted."""
import math
from functools import lru_cache

from PIL import Image

import mc

YAW = 215.0
PITCH = 32.0
SHADE = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "west": 0.6, "east": 0.6}
NORMALS = {"up": (0, 1, 0), "down": (0, -1, 0), "north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0)}


def view(p):
    """Camera space of a world-space point: x right, y up, z towards the viewer."""
    return mc.rot_x(mc.rot_y(p, YAW), PITCH)


def visible(face):
    return view(NORMALS[face])[2] > 1e-6


def face_corners(x0, y0, z0, x1, y1, z1, face):
    """origin, u end and v end of a box face, with Minecraft's texture orientation."""
    if face == "up":
        return (x0, y1, z0), (x1, y1, z0), (x0, y1, z1)
    if face == "down":
        return (x0, y0, z1), (x1, y0, z1), (x0, y0, z0)
    if face == "north":
        return (x1, y1, z0), (x0, y1, z0), (x1, y0, z0)
    if face == "south":
        return (x0, y1, z1), (x1, y1, z1), (x0, y0, z1)
    if face == "west":
        return (x0, y1, z0), (x0, y1, z1), (x0, y0, z0)
    return (x1, y1, z1), (x1, y1, z0), (x1, y0, z1)


class Box:
    """An axis-aligned box in model pixels (16 = one block), its six face textures, and an optional swing about a pivot."""

    def __init__(self, lo, hi, faces, pivot=None, pitch=0.0):
        self.lo, self.hi, self.faces, self.pivot, self.pitch = lo, hi, faces, pivot, pitch


def draw_boxes(boxes, scale, yaw_deg=0.0, pad=4):
    """Renders boxes turned by yaw about the model origin; returns (sprite, anchor), anchor being where the origin lands."""
    parts = []
    for box in boxes:
        x0, y0, z0 = box.lo
        x1, y1, z1 = box.hi

        def place(p, box=box):
            if box.pivot is not None and box.pitch:
                px, py, pz = box.pivot
                q = mc.rot_x((p[0] - px, p[1] - py, p[2] - pz), box.pitch)
                p = (q[0] + px, q[1] + py, q[2] + pz)
            return view(mc.rot_y(p, yaw_deg))

        def turn(n, box=box):
            if box.pivot is not None and box.pitch:
                n = mc.rot_x(n, box.pitch)
            return mc.rot_y(n, yaw_deg)

        for face, tex in box.faces.items():
            if tex is None:
                continue
            normal = turn(NORMALS[face])
            if view(normal)[2] <= 1e-6:
                continue
            o, u, v = (place(p) for p in face_corners(x0, y0, z0, x1, y1, z1, face))
            w = (u[0] + v[0] - o[0], u[1] + v[1] - o[1], u[2] + v[2] - o[2])
            depth = (o[2] + u[2] + v[2] + w[2]) / 4
            if normal[1] > 0.5:
                factor = 1.0
            elif normal[1] < -0.5:
                factor = 0.5
            else:
                factor = 0.8 * abs(normal[2]) + 0.6 * abs(normal[0])
            parts.append((depth, tex, (o, u, v, w), factor))
    if not parts:
        return Image.new("RGBA", (1, 1)), (0, 0)
    k = scale / 16.0
    xs = [c[0] * k for part in parts for c in part[2]]
    ys = [-c[1] * k for part in parts for c in part[2]]
    minx, miny = min(xs), min(ys)
    w = int(math.ceil(max(xs) - minx)) + 2 * pad
    h = int(math.ceil(max(ys) - miny)) + 2 * pad
    sprite = Image.new("RGBA", (max(1, w), max(1, h)))
    ox, oy = -minx + pad, -miny + pad
    parts.sort(key=lambda part: part[0])
    for _depth, tex, (o, u, v, _w), factor in parts:
        po = (o[0] * k + ox, -o[1] * k + oy)
        pu = (u[0] * k + ox, -u[1] * k + oy)
        pv = (v[0] * k + ox, -v[1] * k + oy)
        big = tex.resize((max(1, tex.width * 4), max(1, tex.height * 4)), Image.NEAREST)
        mc.paste_face(sprite, mc.shade(big, factor), po, pu, pv)
    return sprite, (int(round(ox)), int(round(oy)))


def crop(tex, u, v, w, h, rotate=0, flip=False):
    if w <= 0 or h <= 0:
        return None
    piece = tex.crop((u, v, u + w, v + h))
    if flip:
        piece = piece.transpose(Image.FLIP_LEFT_RIGHT)
    if rotate:
        piece = piece.rotate(rotate, expand=True)
    return piece


def cube_faces(tex, u, v, w, h, d):
    """The six faces of a Java model cube with texture offset (u, v) and size w x h x d."""
    return {
        "up": crop(tex, u + d, v, w, d),
        "down": crop(tex, u + d + w, v, w, d),
        "west": crop(tex, u, v + d, d, h),
        "north": crop(tex, u + d, v + d, w, h),
        "east": crop(tex, u + d + w, v + d, d, h),
        "south": crop(tex, u + d + w + d, v + d, w, h),
    }


# ------------------------------------------------------------------ entities

@lru_cache(maxsize=None)
def cow_sprite(scale, yaw_bucket, baby, walk_phase=0):
    """A cow in the classic Java model, facing yaw_bucket * 22.5 degrees (mineflayer yaw convention)."""
    tex = mc.texture("entity/cow/cow_temperate")
    head = cube_faces(tex, 0, 0, 8, 8, 6)
    legs = cube_faces(tex, 0, 16, 4, 12, 4)
    body_uv = cube_faces(tex, 18, 4, 12, 18, 10)
    # The body is modelled standing up and turned 90 degrees onto its legs: its south face becomes the back, north the
    # belly, up the chest, down the rump, and its side faces turn a quarter.
    body = {
        "up": body_uv["south"].rotate(180),
        "down": body_uv["north"],
        "north": body_uv["up"],
        "south": body_uv["down"],
        "west": body_uv["west"].rotate(90, expand=True),
        "east": body_uv["east"].rotate(-90, expand=True),
    }
    swing = math.sin(walk_phase * math.pi / 2) * 28 if walk_phase else 0
    boxes = [
        Box((-6, 12, -8), (6, 22, 10), body),
        Box((-4, 16, -14), (4, 24, -8), head),
        Box((-8, 0, 5), (-4, 12, 9), legs, pivot=(-6, 12, 7), pitch=swing),
        Box((4, 0, 5), (8, 12, 9), legs, pivot=(6, 12, 7), pitch=-swing),
        Box((-8, 0, -8), (-4, 12, -4), legs, pivot=(-6, 12, -6), pitch=-swing),
        Box((4, 0, -8), (8, 12, -4), legs, pivot=(6, 12, -6), pitch=swing),
    ]
    if baby:
        boxes = [Box(tuple(c * 0.5 for c in b.lo), tuple(c * 0.5 for c in b.hi), b.faces,
                     tuple(c * 0.5 for c in b.pivot) if b.pivot else None, b.pitch) for b in boxes]
        # babies keep a big head
        boxes[1] = Box((-3, 7, -9), (3, 13, -3.5), head)
    return draw_boxes(boxes, scale, yaw_bucket * 22.5)


@lru_cache(maxsize=None)
def player_sprite(scale, yaw_bucket, name, walk_phase=0):
    skin, slim = mc.skin_for(name)
    arm = 3 if slim else 4
    head = cube_faces(skin, 0, 0, 8, 8, 8)
    hat = cube_faces(skin, 32, 0, 8, 8, 8)
    body = cube_faces(skin, 16, 16, 8, 12, 4)
    right_arm = cube_faces(skin, 40, 16, arm, 12, 4)
    left_arm = cube_faces(skin, 32, 48, arm, 12, 4)
    right_leg = cube_faces(skin, 0, 16, 4, 12, 4)
    left_leg = cube_faces(skin, 16, 48, 4, 12, 4)
    swing = math.sin(walk_phase * math.pi / 2) * 32 if walk_phase else 0
    boxes = [
        Box((-4, 0, -2), (0, 12, 2), right_leg, pivot=(-2, 12, 0), pitch=swing),
        Box((0, 0, -2), (4, 12, 2), left_leg, pivot=(2, 12, 0), pitch=-swing),
        Box((-4, 12, -2), (4, 24, 2), body),
        Box((-4 - arm, 12, -2), (-4, 24, 2), right_arm, pivot=(-4 - arm / 2, 22, 0), pitch=-swing),
        Box((4, 12, -2), (4 + arm, 24, 2), left_arm, pivot=(4 + arm / 2, 22, 0), pitch=swing),
        Box((-4, 24, -4), (4, 32, 4), head),
        Box((-4.5, 23.5, -4.5), (4.5, 32.5, 4.5), hat),
    ]
    return draw_boxes(boxes, scale, yaw_bucket * 22.5)


@lru_cache(maxsize=None)
def tnt_sprite(scale, flash):
    top, side, bottom = mc.texture("block/tnt_top"), mc.texture("block/tnt_side"), mc.texture("block/tnt_bottom")
    if flash:
        white = Image.new("RGBA", side.size, (255, 255, 255, 170))
        top, side, bottom = (Image.alpha_composite(t, white) for t in (top, side, bottom))
    faces = {"up": top, "down": bottom, "north": side, "south": side, "west": side, "east": side}
    return draw_boxes([Box((-8, 0, -8), (8, 16, 8), faces)], scale)


# ------------------------------------------------------------------ blocks

@lru_cache(maxsize=None)
def creeper_sprite(scale, yaw_bucket, flash=False):
    """A creeper from the vanilla model; flash turns it white, as it does just before it explodes."""
    tex = mc.texture("entity/creeper/creeper")
    if flash:
        white = Image.new("RGBA", tex.size, (255, 255, 255, 255))
        white.putalpha(tex.getchannel("A"))
        tex = Image.blend(tex, white, 0.6)
    head = cube_faces(tex, 0, 0, 8, 8, 8)
    body = cube_faces(tex, 16, 16, 8, 12, 4)
    leg = cube_faces(tex, 0, 16, 4, 6, 4)
    boxes = [
        Box((-4, 6, -2), (4, 18, 2), body),
        Box((-4, 18, -4), (4, 26, 4), head),
        Box((-4, 0, -6), (0, 6, -2), leg),
        Box((0, 0, -6), (4, 6, -2), leg),
        Box((-4, 0, 2), (0, 6, 6), leg),
        Box((0, 0, 2), (4, 6, 6), leg),
    ]
    return draw_boxes(boxes, scale, yaw_bucket * 22.5)


@lru_cache(maxsize=None)
def minecart_sprite(scale, yaw_bucket):
    """A minecart from the vanilla model: a 20x16 floor plate and four 16x8 walls, all from entity/minecart."""
    tex = mc.texture("entity/minecart/minecart")
    plate = cube_faces(tex, 0, 10, 20, 16, 2)
    wall = cube_faces(tex, 0, 0, 16, 8, 2)
    floor = {"up": plate["north"], "down": plate["south"], "north": plate["up"], "south": plate["up"],
             "west": plate["west"], "east": plate["east"]}
    side = wall["north"]
    along = {"up": wall["up"], "down": wall["down"], "north": side, "south": side, "west": wall["west"], "east": wall["east"]}
    across = {"up": wall["up"].rotate(90, expand=True), "down": wall["down"].rotate(90, expand=True), "north": wall["west"],
              "south": wall["east"], "west": side, "east": side}
    boxes = [
        Box((-10, 0, -8), (10, 2, 8), floor),
        Box((-8, 2, -8), (8, 10, -6), along),
        Box((-8, 2, 6), (8, 10, 8), along),
        Box((-10, 2, -8), (-8, 10, 8), across),
        Box((8, 2, -8), (10, 10, 8), across),
    ]
    return draw_boxes(boxes, scale, yaw_bucket * 22.5)


@lru_cache(maxsize=None)
def rail_sprite(scale, along_x):
    """A straight rail lying flat on the block below it."""
    tex = mc.texture("block/rail")
    top = tex.rotate(90) if along_x else tex
    faces = {"up": top, "down": None, "north": None, "south": None, "west": None, "east": None}
    return draw_boxes([Box((-8, 0, -8), (8, 0.5, 8), faces)], scale)


@lru_cache(maxsize=None)
def leash_knot_sprite(scale):
    tex = mc.texture("entity/lead_knot/lead_knot")
    faces = cube_faces(tex, 0, 0, 6, 8, 6)
    return draw_boxes([Box((-3, 6, -3), (3, 14, 3), faces)], scale)


GRASS_TINT = (0x91, 0xBD, 0x59)


def tint(tex, color):
    r, g, b, a = tex.split()
    r = r.point(lambda v: v * color[0] // 255)
    g = g.point(lambda v: v * color[1] // 255)
    b = b.point(lambda v: v * color[2] // 255)
    return Image.merge("RGBA", (r, g, b, a))


@lru_cache(maxsize=None)
def block_faces(name, frame=0):
    """Face textures for a full block, from its block model, with the grass tint where the model asks for it."""
    if name == "grass_block":
        top = tint(mc.texture("block/grass_block_top"), GRASS_TINT)
        side = mc.texture("block/grass_block_side").copy()
        side.alpha_composite(tint(mc.texture("block/grass_block_side_overlay"), GRASS_TINT))
        return {"up": top, "down": mc.texture("block/dirt"), "north": side, "south": side, "west": side, "east": side}
    if name in ("lava", "water"):
        still = mc.texture(f"block/{name}_still", frame)
        if name == "water":
            still = tint(still, (0x3F, 0x76, 0xE4))
        return {"up": still, "north": still, "south": still, "west": still, "east": still, "down": still}
    textures, _parents = mc.model_chain("block/" + name)

    def tex(*keys):
        for k in keys:
            r = mc.resolve_tex(textures, k)
            if r and mc.texture(r):
                return mc.texture(r, frame)
        return None
    return {"up": tex("up", "top", "end", "all", "particle"), "down": tex("down", "bottom", "end", "all", "particle"),
            "north": tex("north", "front", "side", "all", "particle"), "south": tex("south", "side", "all", "particle"),
            "west": tex("west", "side", "all", "particle"), "east": tex("east", "side", "all", "particle")}


FENCES = ("fence", "fence_gate", "wall", "iron_bars")


@lru_cache(maxsize=None)
def fence_sprite(scale, name, north, south, west, east):
    wood = name.replace("_fence_gate", "").replace("_fence", "")
    planks = mc.texture(f"block/{wood}_planks") or mc.texture("block/oak_planks")

    def faces():
        return {f: planks for f in ("up", "down", "north", "south", "west", "east")}
    boxes = [Box((-2, 0, -2), (2, 16, 2), faces())]
    for connected, lo, hi in ((north, (-1, 0, -8), (1, 0, -2)), (south, (-1, 0, 2), (1, 0, 8)),
                              (west, (-8, 0, -1), (-2, 0, 1)), (east, (2, 0, -1), (8, 0, 1))):
        if connected:
            for y0, y1 in ((6, 9), (12, 15)):
                boxes.append(Box((lo[0], y0, lo[2]), (hi[0], y1, hi[2]), faces()))
    return draw_boxes(boxes, scale)
