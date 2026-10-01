#!/usr/bin/env python3
"""Draws capture.mjs menu recordings (illegal) as a listing GIF: the chest both players have open, drawn large, with each
player's cursor and tooltips, what changed in which slot, the chat both players received and a caption bar. Slots,
hovers, changes and chat are exactly the recording's.

    python render_menu.py illegal [--frame SECONDS]
"""
import json
import os
import shutil
import sys
from functools import lru_cache

from PIL import Image, ImageDraw

import mc
import style

HERE = os.path.dirname(os.path.abspath(__file__))
RUN = os.path.join(HERE, "..", "run", "gifs")
S = 3
FPS = 20
W, H = style.W, style.H
CHIP_Y = style.HEADER + 14
CHEST_X, CHEST_Y = 20, style.HEADER + 66
CARD_X, CARD_Y, CARD_W = CHEST_X + 176 * S + 20, style.HEADER + 14, W - (CHEST_X + 176 * S + 20) - 20
FLASH_MS = 2600

RARITY = {"totem_of_undying": "§e", "enchanted_golden_apple": "§d", "command_block": "§d", "bedrock": "§f"}
NAMES = {"sharpness": "Sharpness", "looting": "Looting", "mending": "Mending", "unbreaking": "Unbreaking", "protection": "Protection"}
TITLES = {"container.chest": "Chest", "container.chestDouble": "Large Chest", "container.barrel": "Barrel", "container.shulkerBox": "Shulker Box"}
WHO = {"NyrOp": ("Staff", "creative"), "Kai": ("Kai", "survival")}


def rows_of(size):
    return max(1, min(6, size // 9))


@lru_cache(maxsize=None)
def chest_gui(rows):
    """The chest window without the player's inventory: title bar, the chest's rows, and the window's bottom edge."""
    gui = mc.texture("gui/container/generic_54")
    top_h = 17 + rows * 18
    panel = Image.new("RGBA", (176, top_h + 7))
    panel.alpha_composite(gui.crop((0, 0, 176, top_h)), (0, 0))
    panel.alpha_composite(gui.crop((0, 215, 176, 222)), (0, top_h))
    return panel.resize((176 * S, (top_h + 7) * S), Image.NEAREST)


def slot_origin(slot):
    return 8 + (slot % 9) * 18, 18 + (slot // 9) * 18


def enchant_name(key):
    return NAMES.get(key, mc.pretty(key))


def tooltip_lines(item):
    name = item["name"]
    title = item.get("title") or ((("§b" if item.get("enchantments") else RARITY.get(name, "§f"))) + mc.pretty(name))
    lines = [title]
    for enchant, level in (item.get("enchantments") or {}).items():
        lines.append("§7" + enchant_name(enchant) + " " + mc.level_text(level))
    if item.get("damageBonus"):
        lines.append("")
        lines.append("§7When in Main Hand:")
        lines.append(f"§9+{int(item['damageBonus'])} Attack Damage")
    if item.get("unbreakable"):
        lines.append("§9Unbreakable")
    if item.get("maxStack"):
        lines.append(f"§8Stacks to {item['maxStack']}")
    return lines


def change_of(before, after):
    """What happened to one slot, in words, and whether the stack was taken."""
    if after is None:
        return "Taken, kept in quarantine", True
    notes = []
    was, now = before.get("enchantments") or {}, after.get("enchantments") or {}
    for key, level in was.items():
        if key not in now:
            notes.append(f"{enchant_name(key)} {level} removed")
        elif now[key] != level:
            notes.append(f"{enchant_name(key)} {level} → {now[key]}")
    if before.get("unbreakable") and not after.get("unbreakable"):
        notes.append("Can break again")
    if before.get("damageBonus") and not after.get("damageBonus"):
        notes.append(f"+{int(before['damageBonus'])} attack damage removed")
    if before.get("count") != after.get("count"):
        notes.append(f"{before['count']} → {after['count']}, the rest to quarantine")
    return (", ".join(notes) or "Fixed"), False


class Player:
    def __init__(self, name):
        self.name = name
        self.window = None
        self.chat = []
        self.hover = None
        self.cursor = None
        self.cursor_from = None
        self.cursor_t = 0
        self.typing = None
        self.inventory = []

    def apply(self, e, scene):
        k = e["type"]
        if k == "window":
            self.window = {"id": e["id"], "title": e["title"], "size": e["size"], "slots": list(e["slots"])}
            self.hover = None
            scene.slots = list(e["slots"][: e["size"]])
            scene.size, scene.title = e["size"], e["title"]
        elif k == "slot" and self.window and e["id"] == self.window["id"] and 0 <= e["slot"] < self.window["size"]:
            before = self.window["slots"][e["slot"]]
            self.window["slots"][e["slot"]] = e["item"]
            scene.slots[e["slot"]] = e["item"]
            if before != e["item"]:
                scene.change(e["slot"], before, e["item"], e["t"])
        elif k == "close":
            self.window, self.hover, self.cursor = None, None, None
        elif k == "chat":
            self.chat.append((e["t"], e["text"]))
        elif k == "hover":
            if e["slot"] is None:
                self.hover = None
            elif self.window:
                self.move_to(e["slot"], e["t"])
        elif k == "type":
            self.typing = (e["text"], e["t"], e["ms"])
        elif k == "send":
            self.typing = None
        elif k == "inventory":
            self.inventory = e["items"]

    def move_to(self, slot, t):
        gx, gy = slot_origin(slot)
        target = (CHEST_X + (gx + 8) * S, CHEST_Y + (gy + 8) * S)
        start = self.cursor_pos(t) if self.cursor else (target[0] + 90, target[1] + 110)
        self.cursor_from, self.cursor, self.cursor_t = start, target, t
        self.hover = (slot, t)

    def cursor_pos(self, t):
        if self.cursor is None:
            return None
        k = min(1.0, max(0.0, (t - self.cursor_t) / 260.0))
        k = k * k * (3 - 2 * k)
        fx, fy = self.cursor_from
        return fx + (self.cursor[0] - fx) * k, fy + (self.cursor[1] - fy) * k


class Scene:
    """The one chest both players look at: its slots as the latest open window shows them, and what changed in it."""

    def __init__(self):
        self.slots, self.size, self.title = [], 27, "container.chest"
        self.changes = []  # (slot, before, after, t)

    def change(self, slot, before, after, t):
        self.changes.append((slot, before, after, t))

    @staticmethod
    def open_by_anyone(players):
        return any(p.window for p in players)


def viewer_chip(canvas, x, p, t):
    f = style.fonts()
    name, mode = WHO.get(p.name, (p.name, ""))
    open_ = p.window is not None
    width = 254
    box = Image.new("RGBA", (width, 40))
    d = ImageDraw.Draw(box)
    d.rounded_rectangle([0, 0, width - 1, 39], radius=10, fill=(12, 14, 18, 225), outline=(style.ACCENT if open_ else (60, 64, 72)) + (255,), width=2)
    box.alpha_composite(mc.base_icon("player_head", p.name).resize((24, 24), Image.NEAREST), (10, 8))
    d.text((42, 9), name, font=f["name"], fill=(255, 255, 255, 255))
    nw = d.textlength(name, font=f["name"])
    d.text((42 + nw + 8, 12), mode, font=f["state"], fill=(150, 156, 166, 255))
    state = "chest open" if open_ else "chest closed"
    sw = d.textlength(state, font=f["state"])
    d.ellipse([width - 22 - sw, 16, width - 14 - sw, 24], fill=(style.ACCENT if open_ else (90, 94, 102)) + (255,))
    d.text((width - 10 - sw, 12), state, font=f["state"], fill=(210, 214, 220, 255) if open_ else (130, 136, 146, 255))
    canvas.alpha_composite(box, (x, CHIP_Y))


def draw_chest(canvas, scene, players, t):
    live = scene.open_by_anyone(players)
    rows = rows_of(scene.size)
    gui = chest_gui(rows)
    canvas.alpha_composite(gui, (CHEST_X, CHEST_Y))
    mc.draw_text(canvas, CHEST_X + 8 * S, CHEST_Y + 6 * S, "§8" + TITLES.get(scene.title, mc.pretty(scene.title)), S, shadow=False)
    hovered = {p.hover[0] for p in players if p.hover and 0 <= t - p.hover[1]}
    for slot in range(min(scene.size, rows * 9)):
        gx, gy = slot_origin(slot)
        px, py = CHEST_X + gx * S, CHEST_Y + gy * S
        for (cslot, _before, after, ct) in scene.changes:
            if cslot == slot and 0 <= t - ct < FLASH_MS:
                age = t - ct
                alpha = 170 if age < FLASH_MS - 800 else int(170 * (FLASH_MS - age) / 800)
                canvas.alpha_composite(Image.new("RGBA", (16 * S, 16 * S), (style.DANGER if after is None else style.ACCENT) + (alpha,)), (px, py))
        if slot in hovered:
            canvas.alpha_composite(Image.new("RGBA", (16 * S, 16 * S), (255, 255, 255, 110)), (px, py))
        item = scene.slots[slot] if slot < len(scene.slots) else None
        if not item:
            continue
        canvas.alpha_composite(mc.item_icon(item["name"], item.get("glint"), t, S), (px, py))
        if item.get("count", 1) > 1:
            count = str(item["count"])
            mc.draw_text(canvas, px + (17 - mc.text_width(count)) * S, py + 9 * S, "§f" + count, S)
    if not live:
        # nobody has it open any more: the last view, dimmed
        shade = Image.new("RGBA", gui.size, (14, 16, 20, 150))
        canvas.alpha_composite(shade, (CHEST_X, CHEST_Y))


def card(canvas, height, title):
    f = style.fonts()
    box = Image.new("RGBA", (CARD_W, height))
    d = ImageDraw.Draw(box)
    d.rounded_rectangle([0, 0, CARD_W - 1, height - 1], radius=12, fill=(12, 14, 18, 225), outline=(60, 64, 72, 255), width=2)
    d.text((16, 12), title, font=f["name"], fill=(255, 255, 255, 255))
    return box, d


def rules_card(canvas):
    f = style.fonts()
    box, d = card(canvas, 180, "Who is checked")
    lines = [("Survival players: on join, every", (210, 214, 220)), ("minute, and when they open a", (210, 214, 220)),
             ("container, click, pick up or drop.", (210, 214, 220)), ("", None),
             ("Creative players are not checked,", (150, 156, 166)), ("so staff see the chest as it is.", (150, 156, 166))]
    for i, (text, color) in enumerate(lines):
        if text:
            d.text((16, 46 + i * 21), text, font=f["state"], fill=color + (255,))
    canvas.alpha_composite(box, (CARD_X, CARD_Y))


def changes_card(canvas, scene, t):
    f = style.fonts()
    shown = [c for c in scene.changes if c[3] <= t]
    if not shown:
        return False
    first = min(c[3] for c in shown)
    height = 52 + 40 * len(shown)
    box, d = card(canvas, height, "When Kai opened it")
    for i, (slot, before, after, ct) in enumerate(sorted(shown)):
        k = min(1.0, max(0.0, (t - first - i * 120) / 250.0))
        if k <= 0:
            continue
        y = 46 + i * 40
        icon = mc.item_icon(before["name"], before.get("glint"), t, 2)
        box.alpha_composite(icon, (14, y + 4))
        text, taken = change_of(before, after)
        d.text((56, y), mc.pretty(before["name"]), font=f["badge"], fill=(255, 255, 255, int(255 * k)))
        d.text((56, y + 18), text, font=f["state"], fill=(style.DANGER if taken else style.ACCENT) + (int(255 * k),))
    canvas.alpha_composite(box, (CARD_X, CARD_Y))
    return True


@lru_cache(maxsize=1)
def hotbar_image():
    return mc.texture("gui/sprites/hud/hotbar").resize((182 * 2, 22 * 2), Image.NEAREST)


def inventory_card(canvas, p, t, y):
    f = style.fonts()
    box = Image.new("RGBA", (176 * S, 96))
    d = ImageDraw.Draw(box)
    d.rounded_rectangle([0, 0, box.width - 1, 95], radius=12, fill=(12, 14, 18, 225), outline=(60, 64, 72, 255), width=2)
    label = "Staff's inventory" + ("" if p.inventory else ": empty")
    d.text((16, 10), label, font=f["badge"], fill=(170, 176, 186, 255))
    box.alpha_composite(hotbar_image(), (16, 38))
    for i, (name, count) in enumerate(p.inventory[:9]):
        sx, sy = 16 + (3 + 20 * i) * 2, 38 + 3 * 2
        box.alpha_composite(mc.item_icon(name, False, t, 2), (sx, sy))
        if count > 1:
            text = str(count)
            mc.draw_text(box, sx + (17 - mc.text_width(text)) * 2, sy + 9 * 2, "§f" + text, 2)
    canvas.alpha_composite(box, (CHEST_X, y))


def draw_overlay(canvas, p, t):
    if not p.window:
        return
    pos = p.cursor_pos(t)
    if not pos:
        return
    if p.hover and t - p.hover[1] > 180:
        slot = p.hover[0]
        item = p.window["slots"][slot] if slot < len(p.window["slots"]) else None
        if item:
            mc.draw_tooltip(canvas, int(pos[0]), int(pos[1]), tooltip_lines(item), 2)
    mc.draw_cursor(canvas, pos[0], pos[1])


def render(scene_name, only_frame=None):
    data = json.load(open(os.path.join(RUN, f"{scene_name}.json"), encoding="utf-8"))
    style.PRODUCT["name"] = style.product_of(scene_name)
    events, duration = style.warp(data["events"], tail=2600)
    events.sort(key=lambda e: e["t"])
    players = {name: Player(name) for name in data["bots"]}
    scene = Scene()
    captions = [e for e in events if e["type"] == "caption"]
    frames_dir = os.path.join(RUN, f"{scene_name}-frames")
    if only_frame is None:
        shutil.rmtree(frames_dir, ignore_errors=True)
    os.makedirs(frames_dir, exist_ok=True)
    start = min(e["t"] for e in events if e["type"] == "window") + 50
    total = int((duration - start) / 1000 * FPS)
    times = [only_frame * 1000] if only_frame is not None else [start + i * 1000 / FPS for i in range(total)]
    chest_bottom = CHEST_Y + (17 + 3 * 18 + 7) * S
    cursor = 0
    for n, t in enumerate(times):
        while cursor < len(events) and events[cursor]["t"] <= t:
            e = events[cursor]
            if e.get("bot") in players:
                players[e["bot"]].apply(e, scene)
            cursor += 1
        canvas = style.background().copy()
        order = list(players.values())
        for i, p in enumerate(order):
            viewer_chip(canvas, CHEST_X + i * (176 * S - 254), p, t)
        draw_chest(canvas, scene, order, t)
        if not changes_card(canvas, scene, t):
            rules_card(canvas)
        staff = players.get("NyrOp")
        closed = all(p.window is None for p in order) and scene.changes
        if staff and closed:
            inventory_card(canvas, staff, t, chest_bottom + 12)
        chats = {p.name: p.chat for p in order}
        typing = {p.name: p.typing for p in order if p.typing}
        free = H - 14 - (chest_bottom + 12 + (108 if closed else 0))
        style.chat_panel(canvas, chats, t, typing, names={"NyrOp": "Staff"}, width_px=W - 32, lines=max(3, (free - 26) // 20))
        for p in order:
            draw_overlay(canvas, p, t)
        caption = next((c for c in reversed(captions) if c["t"] <= t), None)
        style.header(canvas, caption, t)
        name = os.path.join(frames_dir, f"t{int(t):06d}.png" if only_frame is not None else f"f{n:04d}.png")
        canvas.convert("RGB").save(name)
    if only_frame is not None:
        return name
    return style.encode(frames_dir, os.path.join(RUN, style.output_name(scene_name)), FPS, total)


if __name__ == "__main__":
    scene_arg = sys.argv[1]
    if len(sys.argv) > 3 and sys.argv[2] == "--frame":
        print(render(scene_arg, float(sys.argv[3])))
    else:
        print(render(scene_arg))
