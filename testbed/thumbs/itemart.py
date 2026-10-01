"""Minecraft items and blocks as lit, chunky 3D objects for the NYR store thumbnails (copied from NYR Fixes; the shield
plate added).

Nothing here is painted or generated. Every face samples a texture read from the local Minecraft client jar through the
shared gif tools (../gifs/mc.py and ../gifs/models.py), always nearest-neighbour:

- items are their 16x16 sprites extruded into voxels: each opaque texel is a small cube whose front and back are the
  sprite and whose open sides take that texel's colour (the shape the game itself builds for item/generated models);
- blocks and the anvil are their block models' own elements, UVs and face rotations, read from the jar;
- the cow is the cow model's boxes exactly as models.cow_sprite builds them.

A z-buffered rasterizer draws the faces at supersampled resolution with one directional light from the upper left.
Soft contact shadows, the enchantment glint (the game's glint texture added on top) and the backdrop glow are computed
here too.
"""
import json
import math
import os
import sys
from functools import lru_cache

import numpy as np
from PIL import Image, ImageFilter

sys.dont_write_bytecode = True  # leave no caches next to the shared gif tools
GIFS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "gifs")
if GIFS not in sys.path:
    sys.path.insert(0, GIFS)
import mc  # noqa: E402
import models  # noqa: E402

LIGHT = np.array([-0.5, 0.62, 0.6]) / np.linalg.norm([-0.5, 0.62, 0.6])  # towards the light: left, up, towards the viewer
AMBIENT, DIFFUSE, MAX_SHADE = 0.46, 0.66, 1.06
INSIDE = 0.55  # inner faces seen through a cage are in the cage's own shade
ALPHA_CUT = 128
FRONT_BIAS = 0.6  # supersampled pixels: a sprite face wins ties against the voxel sides that share its edges


def rotation(steps):
    """Rotation matrix for [(axis, degrees), ...] applied in order about the camera axes (x right, y up, z to viewer)."""
    m = np.eye(3)
    for axis, deg in steps:
        a = math.radians(deg)
        c, s = math.cos(a), math.sin(a)
        r = {"x": ((1, 0, 0), (0, c, -s), (0, s, c)),
             "y": ((c, 0, s), (0, 1, 0), (-s, 0, c)),
             "z": ((c, -s, 0), (s, c, 0), (0, 0, 1))}[axis]
        m = np.array(r) @ m
    return m


def shade_for(normal):
    return min(MAX_SHADE, AMBIENT + DIFFUSE * max(0.0, float(normal @ LIGHT)))


def rgba(image):
    return np.asarray(image.convert("RGBA"), dtype=np.uint8)


# ------------------------------------------------------------------ geometry: faces are (origin, u end, v end, texels, normal, bias)

@lru_cache(maxsize=None)
def item_faces(texture, thickness=1.0):
    """A sprite extruded into voxels, centred on the origin, one texel per unit."""
    tex = rgba(mc.texture(texture))
    h, w = tex.shape[:2]
    solid = tex[..., 3] >= ALPHA_CUT
    d = thickness / 2
    faces = [((-w / 2, h / 2, d), (w / 2, h / 2, d), (-w / 2, -h / 2, d), tex, (0, 0, 1), FRONT_BIAS),
             ((w / 2, h / 2, -d), (-w / 2, h / 2, -d), (w / 2, -h / 2, -d), tex[:, ::-1], (0, 0, -1), FRONT_BIAS)]
    for v in range(h):
        for u in range(w):
            if not solid[v, u]:
                continue
            c = tex[v:v + 1, u:u + 1]
            x0, x1, y0, y1 = u - w / 2, u + 1 - w / 2, h / 2 - v, h / 2 - v - 1
            if u == 0 or not solid[v, u - 1]:
                faces.append(((x0, y0, -d), (x0, y0, d), (x0, y1, -d), c, (-1, 0, 0), 0))
            if u == w - 1 or not solid[v, u + 1]:
                faces.append(((x1, y0, d), (x1, y0, -d), (x1, y1, d), c, (1, 0, 0), 0))
            if v == 0 or not solid[v - 1, u]:
                faces.append(((x0, y0, -d), (x1, y0, -d), (x0, y0, d), c, (0, 1, 0), 0))
            if v == h - 1 or not solid[v + 1, u]:
                faces.append(((x0, y1, d), (x1, y1, d), (x0, y1, -d), c, (0, -1, 0), 0))
    return faces


@lru_cache(maxsize=None)
def shield_faces(thickness=1.6):
    """The plain shield's plate as the game textures it: the 12 x 22 front and back crops of
    entity/shield/shield_base_nopattern, extruded like an item sprite so its iron rim shows on the sides."""
    tex = rgba(mc.texture("entity/shield/shield_base_nopattern"))
    front = np.ascontiguousarray(tex[1:23, 1:13])
    back = np.ascontiguousarray(tex[1:23, 14:26])
    h, w = front.shape[:2]
    solid = front[..., 3] >= ALPHA_CUT
    d = thickness / 2
    faces = [((-w / 2, h / 2, d), (w / 2, h / 2, d), (-w / 2, -h / 2, d), front, (0, 0, 1), FRONT_BIAS),
             ((w / 2, h / 2, -d), (-w / 2, h / 2, -d), (w / 2, -h / 2, -d), back[:, ::-1], (0, 0, -1), FRONT_BIAS)]
    for v in range(h):
        for u in range(w):
            if not solid[v, u]:
                continue
            c = front[v:v + 1, u:u + 1]
            x0, x1, y0, y1 = u - w / 2, u + 1 - w / 2, h / 2 - v, h / 2 - v - 1
            if u == 0 or not solid[v, u - 1]:
                faces.append(((x0, y0, -d), (x0, y0, d), (x0, y1, -d), c, (-1, 0, 0), 0))
            if u == w - 1 or not solid[v, u + 1]:
                faces.append(((x1, y0, d), (x1, y0, -d), (x1, y1, d), c, (1, 0, 0), 0))
            if v == 0 or not solid[v - 1, u]:
                faces.append(((x0, y0, -d), (x1, y0, -d), (x0, y0, d), c, (0, 1, 0), 0))
            if v == h - 1 or not solid[v + 1, u]:
                faces.append(((x0, y1, d), (x1, y1, d), (x0, y1, -d), c, (0, -1, 0), 0))
    return faces


@lru_cache(maxsize=None)
def head_faces(texture="entity/skeleton/skeleton"):
    """A mob head as the game's head model builds it: the 8 x 8 x 8 cube at texture offset (0, 0), face to the north."""
    tex = mc.texture(texture)
    faces = []
    for face, image in models.cube_faces(tex, 0, 0, 8, 8, 8).items():
        if image is not None:
            o, pu, pv = models.face_corners(-4, -4, -4, 4, 4, 4, face)
            faces.append((o, pu, pv, rgba(image), models.NORMALS[face], 0))
    return faces


@lru_cache(maxsize=None)
def shulker_box_faces(color="purple"):
    """A closed shulker box as the game draws it (its block model has no elements; the shulker's shell model does): the
    16 x 8 x 16 base at texture offset (0, 28) and the 16 x 12 x 16 lid at (0, 0) over it, from entity/shulker/shulker_<colour>."""
    tex = mc.texture(f"entity/shulker/shulker_{color}")
    faces = []
    for (u, v, h), (y0, y1) in (((0, 28, 8), (-8, 0)), ((0, 0, 12), (-4, 8))):
        for face, image in models.cube_faces(tex, u, v, 16, h, 16).items():
            if image is not None:
                o, pu, pv = models.face_corners(-8, y0, -8, 8, y1, 8, face)
                faces.append((o, pu, pv, rgba(image), models.NORMALS[face], 0))
    return faces


UV_DEFAULT = {  # the game's uv for a face without one, from the element's from/to
    "down": lambda f, t: (f[0], 16 - t[2], t[0], 16 - f[2]),
    "up": lambda f, t: (f[0], f[2], t[0], t[2]),
    "north": lambda f, t: (16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]),
    "south": lambda f, t: (f[0], 16 - t[1], t[0], 16 - f[1]),
    "west": lambda f, t: (f[2], 16 - t[1], t[2], 16 - f[1]),
    "east": lambda f, t: (16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]),
}


@lru_cache(maxsize=None)
def block_faces(name):
    """(faces, two_sided) of a block model's elements, centred on the block. two_sided marks models that show their
    inner faces (cube_all_inner_faces, the spawner cage)."""
    if name == "lava":  # the fluid has no block model; the still texture's first frame on every side
        sides = models.block_faces("lava")
        return [(*(tuple(c - 8 for c in p) for p in models.face_corners(0, 0, 0, 16, 16, 16, f)), rgba(sides[f]),
                 models.NORMALS[f], 0) for f in sides], False
    textures, parents = mc.model_chain("block/" + name)
    elements = None
    for parent in parents:
        data = json.loads(mc.asset_bytes("models/" + parent + ".json"))
        if "elements" in data:
            elements = data["elements"]
            break
    faces, two_sided = [], False
    for el in elements or ():
        lo, hi = el["from"], el["to"]
        if any(a > b for a, b in zip(lo, hi)):
            two_sided = True  # the inside-out copy: drawn as the back sides of the outer faces instead
            continue
        for face, spec in el["faces"].items():
            ref = mc.resolve_tex(textures, spec["texture"].lstrip("#"))
            image = mc.texture(ref) if ref else None
            if image is None:
                continue
            u1, v1, u2, v2 = spec.get("uv") or UV_DEFAULT[face](lo, hi)
            kx, ky = image.width / 16, image.height / 16
            piece = image.crop((round(min(u1, u2) * kx), round(min(v1, v2) * ky),
                                round(max(u1, u2) * kx), round(max(v1, v2) * ky)))
            if u2 < u1:
                piece = piece.transpose(Image.FLIP_LEFT_RIGHT)
            if v2 < v1:
                piece = piece.transpose(Image.FLIP_TOP_BOTTOM)
            if spec.get("rotation"):
                piece = piece.rotate(-spec["rotation"], expand=True)  # model rotations turn clockwise
            o, pu, pv = (tuple(c - 8 for c in p) for p in models.face_corners(*lo, *hi, face))
            faces.append((o, pu, pv, rgba(piece), models.NORMALS[face], 0))
    return faces, two_sided


@lru_cache(maxsize=1)
def cow_faces():
    """The boxes models.cow_sprite builds for an adult cow, taken as it hands them to draw_boxes."""
    boxes = []
    real = models.draw_boxes

    def capture(b, *_args, **_kwargs):
        boxes.extend(b)
        return Image.new("RGBA", (1, 1)), (0, 0)
    models.draw_boxes = capture
    try:
        models.cow_sprite.__wrapped__(16, 0, False, 0)
    finally:
        models.draw_boxes = real
    faces = []
    for box in boxes:
        for face, image in box.faces.items():
            if image is not None:
                o, pu, pv = models.face_corners(*box.lo, *box.hi, face)
                faces.append((o, pu, pv, rgba(image), models.NORMALS[face], 0))
    return faces


# ------------------------------------------------------------------ objects in a scene

class Thing:
    """Faces placed in the picture: at = (x, y) canvas pixels of the object's centre and z its depth (bigger is nearer,
    in canvas pixels); size = canvas pixels per texel; rot = [(axis, degrees), ...] turned in order about the camera."""

    def __init__(self, faces, at, size, rot=(), glint=None, two_sided=False, shadow=True):
        self.faces, self.at, self.size, self.rot = faces, at, size, tuple(rot)
        self.glint, self.two_sided, self.shadow = glint, two_sided, shadow


def item(name, at, size, rot=(), glint=None, thickness=1.0, **kw):
    return Thing(item_faces("item/" + name, thickness), at, size, rot, glint, **kw)


def block(name, at, size, rot=(), glint=None, **kw):
    faces, two_sided = block_faces(name)
    return Thing(faces, at, size, rot, glint, two_sided, **kw)


def cow(at, size, rot=(), **kw):
    return Thing(cow_faces(), at, size, rot, **kw)


def shield(at, size, rot=(), **kw):
    return Thing(shield_faces(), at, size, rot, **kw)


def shulker_box(at, size, rot=(), color="purple", **kw):
    return Thing(shulker_box_faces(color), at, size, rot, **kw)


def skull(at, size, rot=(), texture="entity/skeleton/skeleton", **kw):
    """A skeleton skull (or any mob head texture laid out like it); turn it y 180 to face the viewer."""
    return Thing(head_faces(texture), at, size, rot, **kw)


class Raster:
    """A z-buffer over one canvas region, at ss x ss samples per canvas pixel."""

    def __init__(self, box, ss):
        self.x0, self.y0, x1, y1 = box
        self.ss = ss
        self.w, self.h = (x1 - self.x0) * ss, (y1 - self.y0) * ss
        self.rgb = np.zeros((self.h, self.w, 3), np.float32)
        self.depth = np.full((self.h, self.w), -np.inf, np.float32)
        self.oid = np.full((self.h, self.w), -1, np.int16)

    def face(self, p0, pu, pv, tex, shade, oid):
        """The parallelogram p0 + s (pu - p0) + t (pv - p0), points as (x, y down, depth), with texel (0, 0) at p0."""
        ex, ey, ez = pu[0] - p0[0], pu[1] - p0[1], pu[2] - p0[2]
        fx, fy, fz = pv[0] - p0[0], pv[1] - p0[1], pv[2] - p0[2]
        det = ex * fy - ey * fx
        if abs(det) < 1e-6:
            return
        xs = (p0[0], pu[0], pv[0], pu[0] + fx)
        ys = (p0[1], pu[1], pv[1], pu[1] + fy)
        bx0, bx1 = max(0, math.floor(min(xs))), min(self.w, math.ceil(max(xs)) + 1)
        by0, by1 = max(0, math.floor(min(ys))), min(self.h, math.ceil(max(ys)) + 1)
        if bx0 >= bx1 or by0 >= by1:
            return
        gx = (np.arange(bx0, bx1, dtype=np.float32) + 0.5 - p0[0])[None, :]
        gy = (np.arange(by0, by1, dtype=np.float32) + 0.5 - p0[1])[:, None]
        s = (gx * fy - gy * fx) / det
        t = (gy * ex - gx * ey) / det
        es, et = 0.4 / max(1.0, math.hypot(ex, ey)), 0.4 / max(1.0, math.hypot(fx, fy))  # close hairline seams
        inside = (s > -es) & (s < 1 + es) & (t > -et) & (t < 1 + et)
        th, tw = tex.shape[:2]
        texel = tex[np.clip((t * th).astype(np.int32), 0, th - 1), np.clip((s * tw).astype(np.int32), 0, tw - 1)]
        z = p0[2] + s * ez + t * fz
        depth = self.depth[by0:by1, bx0:bx1]
        win = inside & (texel[..., 3] >= ALPHA_CUT) & (z > depth)
        if not win.any():
            return
        depth[win] = z[win]
        self.rgb[by0:by1, bx0:bx1][win] = texel[..., :3][win].astype(np.float32) * shade
        self.oid[by0:by1, bx0:bx1][win] = oid

    def draw(self, oid, thing):
        ss = self.ss
        r = rotation(thing.rot)
        pts = np.array([p for f in thing.faces for p in f[:3]], dtype=np.float64)
        centre = (pts.min(axis=0) + pts.max(axis=0)) / 2
        k = thing.size * ss
        ox, oy, oz = (thing.at[0] - self.x0) * ss, (thing.at[1] - self.y0) * ss, thing.at[2] * ss
        for p0, pu, pv, tex, normal, bias in thing.faces:
            n = r @ np.asarray(normal, dtype=np.float64)
            back = n[2] <= 1e-6
            if back and not thing.two_sided:
                continue
            shade = shade_for(-n) * INSIDE if back else shade_for(n)
            screen = []
            for p in (p0, pu, pv):
                q = r @ (np.asarray(p, dtype=np.float64) - centre)
                screen.append((ox + q[0] * k, oy - q[1] * k, oz + q[2] * k + bias))
            self.face(*screen, tex, shade, oid)

    def glint(self, oid, strength=0.8, angle=-18.0, tile=1.4, phase=(0.0, 0.0)):
        """The game's enchantment glint texture added over one object's pixels, as the glint shader adds it."""
        ys, xs = np.nonzero(self.oid == oid)
        if len(xs) == 0:
            return
        g = rgba(mc.texture("misc/enchanted_glint_item"))[..., :3].astype(np.float32)
        n = g.shape[0]
        span = max(np.ptp(xs), np.ptp(ys)) * tile
        a = math.radians(angle)
        x, y = xs - xs.mean(), ys - ys.mean()
        u = np.floor(((x * math.cos(a) + y * math.sin(a)) / span + phase[0]) * n).astype(np.int64) % n
        v = np.floor(((-x * math.sin(a) + y * math.cos(a)) / span + phase[1]) * n).astype(np.int64) % n
        self.rgb[ys, xs] += g[v, u] * strength

    def resolve(self, count):
        """Canvas-resolution premultiplied colour, coverage, and each object's coverage."""
        ss, h, w = self.ss, self.h // self.ss, self.w // self.ss

        def down(a):
            return a.reshape(h, ss, w, ss, *a.shape[2:]).mean(axis=(1, 3))
        cover = self.oid >= 0
        color = down(np.clip(self.rgb, 0, 255) * cover[..., None])
        return color, down(cover.astype(np.float32)), [down((self.oid == i).astype(np.float32)) for i in range(count)]


def soft(mask, dx, dy, blur):
    h, w = mask.shape
    moved = Image.new("L", (w, h))
    moved.paste(Image.fromarray(np.uint8(np.clip(mask * 255, 0, 255))), (round(dx), round(dy)))
    return np.asarray(moved.filter(ImageFilter.GaussianBlur(blur)), np.float32) / 255


def render_cluster(canvas, box, things, ss=3, shadow=0.6):
    """Draws things into canvas (float RGB array, modified in place) inside box = (x0, y0, x1, y1), with soft shadows
    thrown down and right, away from the light, onto whatever lies behind each object and onto the backdrop. Returns
    the cluster's coverage over the whole canvas."""
    x0, y0, x1, y1 = box
    raster = Raster(box, ss)
    for oid, thing in enumerate(things):
        raster.draw(oid, thing)
    for oid, thing in enumerate(things):
        if thing.glint:
            raster.glint(oid, **(thing.glint if isinstance(thing.glint, dict) else {}))
    color, alpha, masks = raster.resolve(len(things))
    h, w = alpha.shape
    backdrop = np.ones((h, w), np.float32)
    for i, thing in enumerate(things):
        if not thing.shadow:
            continue
        s = soft(masks[i], thing.size * 1.1, thing.size * 1.5, thing.size * 1.3) * shadow
        behind = np.zeros((h, w), np.float32)
        for j, other in enumerate(things):
            if other.at[2] < thing.at[2]:
                behind += masks[j]
        color *= (1 - s * np.minimum(behind, 1))[..., None]
        backdrop *= 1 - s
    region = canvas[y0:y1, x0:x1]
    region *= backdrop[..., None]
    region[:] = color + region * (1 - alpha[..., None])
    coverage = np.zeros(canvas.shape[:2], np.float32)
    coverage[y0:y1, x0:x1] = alpha
    return coverage


def backdrop(size, glow, base=11.0, lift=22.0, seed=7):
    """Near-black ground with one very soft radial lift; a little noise keeps the 8-bit gradient from banding."""
    w, h = size
    cx, cy, rx, ry = glow
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    g = np.exp(-(((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2) * 2.0)
    noise = np.random.default_rng(seed).random((h, w), dtype=np.float32) - 0.5
    v = base + lift * g + noise
    return np.repeat(v[..., None], 3, axis=2)
