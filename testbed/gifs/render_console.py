#!/usr/bin/env python3
"""Draws capture.mjs staff-command recordings (illegal-quarantine) as a listing GIF: the commands staff typed, the
quarantine list, restore and inspect replies laid out as cards, the staff hotbar and the chat itself. Every card is built
from the chat lines the plugin sent in the recording; nothing on it is written by this script.

    python render_console.py illegal-quarantine [--frame SECONDS]
"""
import json
import os
import re
import shutil
import sys

from PIL import Image, ImageDraw

import mc
import style
from render_menu import hotbar_image, tooltip_lines

HERE = os.path.dirname(os.path.abspath(__file__))
RUN = os.path.join(HERE, "..", "run", "gifs")
FPS = 20
W, H = style.W, style.H
GREY, DIM, WHITE = (170, 176, 186), (120, 126, 136), (255, 255, 255)
AMBER = (255, 190, 64)
# what the check's configured action would do to the item, had the plugin found it on a survival player
ACTIONS = {"fix": "would be fixed", "remove": "would be taken", "report": "reported only", "off": "check off"}

LISTED = re.compile(r"^\[Illegal Items\] Quarantine: (?P<count>\d+) stack\(s\), page (?P<page>\S+)$")
ENTRY = re.compile(r"^(?P<id>\d+-\d+) (?P<when>\d{4}-\d\d-\d\d \d\d:\d\d:\d\d) (?P<what>.+?) from (?P<who>\S+) "
                   r"\((?P<where>.+?)\): (?P<why>.+)$")
RESTORED = re.compile(r"^\[Illegal Items\] Gave you (?P<what>.+?) from quarantine (?P<id>\d+-\d+)\. (?P<note>.+)$")
INSPECTED = re.compile(r"^\[Illegal Items\] (?P<what>[^:]+):$")
FINDING = re.compile(r"^- (?P<kind>.+?) \((?P<action>\w+)\): (?P<detail>.+)$")


def stack_of(text):
    """'64 bedrock' -> (64, 'bedrock'); 'command block' -> (1, 'command_block')."""
    m = re.match(r"^(\d+) (.+)$", text)
    count, name = (int(m.group(1)), m.group(2)) if m else (1, text)
    return count, name.replace(" ", "_")


class Console:
    """What staff have been told so far, read from the chat lines as they arrive."""

    def __init__(self):
        self.listed = None  # (t, count, page)
        self.entries = {}  # id -> dict, in arrival order
        self.restored = {}  # id -> (t, note)
        self.inspect = None  # {"t", "what", "findings"}
        self.inventory = []
        self.inventory_t = 0

    def chat(self, t, text):
        plain = mc.strip_codes(text).strip()
        if m := LISTED.match(plain):
            self.listed = (t, int(m["count"]), m["page"])
        elif m := ENTRY.match(plain):
            count, name = stack_of(m["what"])
            self.entries.setdefault(m["id"], {"t": t, "count": count, "name": name, **m.groupdict()})
        elif m := RESTORED.match(plain):
            self.restored[m["id"]] = (t, m["note"])
        elif m := INSPECTED.match(plain):
            self.inspect = {"t": t, "what": m["what"], "findings": []}
        elif (m := FINDING.match(plain)) and self.inspect:
            self.inspect["findings"].append(m.groupdict())


def fade(t, since, delay=0, ms=250):
    return min(1.0, max(0.0, (t - since - delay) / ms))


def faded(image, k):
    """The image with its alpha scaled by k, colours untouched."""
    if k >= 1:
        return image
    out = image.copy()
    out.putalpha(out.getchannel("A").point(lambda a: int(a * k)))
    return out


def panel(width, height, outline=(60, 64, 72)):
    box = Image.new("RGBA", (width, height))
    d = ImageDraw.Draw(box)
    d.rounded_rectangle([0, 0, width - 1, height - 1], radius=12, fill=(12, 14, 18, 225), outline=outline + (255,), width=2)
    return box, d


def slot(box, x, y, name, count, glint, t):
    """An inventory slot as the game draws it, at twice size, with the item and its count."""
    d = ImageDraw.Draw(box)
    d.rectangle([x, y, x + 35, y + 35], fill=(139, 139, 139, 255))
    d.rectangle([x, y, x + 35, y + 1], fill=(55, 55, 55, 255))
    d.rectangle([x, y, x + 1, y + 35], fill=(55, 55, 55, 255))
    d.rectangle([x, y + 34, x + 35, y + 35], fill=(255, 255, 255, 255))
    d.rectangle([x + 34, y, x + 35, y + 35], fill=(255, 255, 255, 255))
    box.alpha_composite(mc.item_icon(name, glint, t, 2), (x + 2, y + 2))
    if count > 1:
        text = str(count)
        mc.draw_text(box, x + 2 + (17 - mc.text_width(text)) * 2, y + 2 + 9 * 2, "§f" + text, 2)


def pill(d, right, y, text, colour, filled):
    f = style.fonts()
    w = d.textlength(text, font=f["state"]) + 20
    x = right - w
    if filled:
        d.rounded_rectangle([x, y, right, y + 24], radius=12, fill=colour + (255,))
        d.text((x + 10, y + 3), text, font=f["state"], fill=(12, 14, 18, 255))
    else:
        d.rounded_rectangle([x, y, right, y + 24], radius=12, outline=colour + (255,), width=2)
        d.text((x + 10, y + 3), text, font=f["state"], fill=colour + (255,))


def quarantine_card(canvas, c, t, x, y, width):
    f = style.fonts()
    rows = list(c.entries.values())  # in the order the plugin listed them
    height = 54 + 66 * max(3, len(rows))
    box, d = panel(width, height)
    d.text((18, 13), "Quarantine", font=f["name"], fill=WHITE + (255,))
    if c.listed:
        note = f"{c.listed[1]} stacks listed · page {c.listed[2]}"
        d.text((width - 18 - d.textlength(note, font=f["badge"]), 15), note, font=f["badge"], fill=GREY + (255,))
    if not rows:
        d.text((18, 58), "Every stack Illegal Items takes waits here for staff.", font=f["badge"], fill=DIM + (255,))
    for i, e in enumerate(rows):
        k = fade(t, e["t"], i * 140)
        if k <= 0:
            continue
        row = Image.new("RGBA", (width - 4, 66))
        rd = ImageDraw.Draw(row)
        restored = c.restored.get(e["id"])
        done = restored is not None and t >= restored[0]
        slot(row, 16, 12, e["name"], e["count"], False, t)
        name = mc.pretty(e["name"]) + (f"  ×{e['count']}" if e["count"] > 1 else "")
        rd.text((66, 6), name, font=f["name"], fill=WHITE + (255,))
        rd.text((66, 29), f"from {e['who']} · {e['where']} · {e['when']}", font=f["state"], fill=GREY + (255,))
        rd.text((66, 46), e["why"], font=f["state"], fill=AMBER + (255,))
        if done:
            pill(rd, width - 22, 8, "Given back to staff", style.ACCENT, True)
        else:
            pill(rd, width - 22, 8, "Kept", GREY, False)
        idw = rd.textlength(e["id"], font=f["state"])
        rd.text((width - 22 - idw - 2, 38), e["id"], font=f["state"], fill=DIM + (255,))
        if i:
            rd.line([16, 0, width - 20, 0], fill=(36, 40, 46, 255), width=1)
        box.alpha_composite(faded(row, k), (2, 46 + i * 66 + int((1 - k) * 10)))
    canvas.alpha_composite(box, (x, y))
    return height


def hotbar_card(canvas, c, t, x, y, held):
    f = style.fonts()
    box, d = panel(388, 88)
    d.text((14, 8), "Staff's hotbar" + ("" if c.inventory else ": empty"), font=f["badge"], fill=GREY + (255,))
    box.alpha_composite(hotbar_image(), (12, 34))
    for i, item in enumerate(c.inventory[:9]):
        sx, sy = 12 + (3 + 20 * i) * 2, 34 + 3 * 2
        box.alpha_composite(mc.item_icon(item["name"], item.get("glint"), t, 2), (sx, sy))
        if item.get("count", 1) > 1:
            text = str(item["count"])
            mc.draw_text(box, sx + (17 - mc.text_width(text)) * 2, sy + 9 * 2, "§f" + text, 2)
    if held is not None:
        selection = mc.texture("gui/sprites/hud/hotbar_selection")
        selection = selection.resize((selection.width * 2, selection.height * 2), Image.NEAREST)
        box.alpha_composite(selection, (12 + held * 40 - 2, 32))
    canvas.alpha_composite(box, (x, y))


def restore_card(canvas, c, t, x, y, width):
    f = style.fonts()
    if not c.restored:
        return 0
    rid, (rt, note) = max(c.restored.items(), key=lambda kv: kv[1][0])
    entry = c.entries.get(rid)
    k = fade(t, rt)
    box, d = panel(width, 90, outline=style.ACCENT)
    if entry:
        slot(box, 16, 27, entry["name"], entry["count"], False, t)
        title = f"{mc.pretty(entry['name'])}" + (f" ×{entry['count']}" if entry["count"] > 1 else "") + " given back"
    else:
        title = "Given back"
    d.text((66, 14), title, font=f["name"], fill=style.ACCENT + (255,))
    words, line, lines = note.split(), "", []
    for word in words:
        trial = (line + " " + word).strip()
        if d.textlength(trial, font=f["state"]) > width - 84 and line:
            lines.append(line)
            line = word
        else:
            line = trial
    lines.append(line)
    for i, text in enumerate(lines[:2]):
        d.text((66, 40 + i * 19), text, font=f["state"], fill=GREY + (255,))
    canvas.alpha_composite(faded(box, k), (x, y))
    return 90


def inspect_card(canvas, c, t, x, y, width, held_item):
    f = style.fonts()
    if not c.inspect or t < c.inspect["t"]:
        return
    findings = c.inspect["findings"]
    height = 70 + 46 * len(findings)
    box, d = panel(width, height)
    count, name = stack_of(c.inspect["what"])
    glint = bool(held_item and held_item.get("glint"))
    slot(box, 16, 14, name, count, glint, t)
    d.text((66, 12), f"Inspect: {mc.pretty(name)}", font=f["name"], fill=WHITE + (255,))
    d.text((66, 35), f"{len(findings)} finding{'s' if len(findings) != 1 else ''} · the item is left as it is", font=f["state"], fill=GREY + (255,))
    for i, finding in enumerate(findings):
        k = fade(t, c.inspect["t"], 150 + i * 140)
        if k <= 0:
            continue
        yy = 64 + i * 46
        a = int(255 * k)
        d.line([16, yy, width - 16, yy], fill=(36, 40, 46, a), width=1)
        d.text((18, yy + 4), finding["kind"].capitalize(), font=f["badge"], fill=style.DANGER + (a,))
        d.text((18, yy + 23), finding["detail"], font=f["state"], fill=(210, 214, 220, a))
        action = ACTIONS.get(finding["action"], finding["action"])
        tw = d.textlength(action, font=f["state"])
        d.text((width - 18 - tw, yy + 6), action, font=f["state"], fill=AMBER + (a,))
    canvas.alpha_composite(box, (x, y))


def render(scene, only_frame=None):
    data = json.load(open(os.path.join(RUN, f"{scene}.json"), encoding="utf-8"))
    style.PRODUCT["name"] = style.product_of(scene)
    events, duration = style.warp(data["events"], tail=2600)
    events.sort(key=lambda e: e["t"])
    staff = data["bots"][0]
    captions = [e for e in events if e["type"] == "caption"]
    frames_dir = os.path.join(RUN, f"{scene}-frames")
    if only_frame is None:
        shutil.rmtree(frames_dir, ignore_errors=True)
    os.makedirs(frames_dir, exist_ok=True)
    start = min(e["t"] for e in events) + 40
    total = int((duration - start) / 1000 * FPS)
    times = [only_frame * 1000] if only_frame is not None else [start + i * 1000 / FPS for i in range(total)]
    console = Console()
    chats, typing = {}, {}
    cursor = 0
    path = None
    for n, t in enumerate(times):
        while cursor < len(events) and events[cursor]["t"] <= t:
            e = events[cursor]
            if e["type"] == "chat":
                chats.setdefault(e["bot"], []).append((e["t"], e["text"]))
                if e["bot"] == staff:
                    console.chat(e["t"], e["text"])
            elif e["type"] == "inventory" and e["bot"] == staff:
                console.inventory, console.inventory_t = e["items"], e["t"]
            elif e["type"] == "type":
                typing[e["bot"]] = (e["text"], e["t"], e["ms"])
            elif e["type"] == "send":
                typing.pop(e["bot"], None)
            cursor += 1
        canvas = style.background().copy()
        top = style.HEADER + 16
        height = quarantine_card(canvas, console, t, 24, top, W - 48)
        row_y = top + height + 16
        # the item staff hold for /illegalitems inspect: the one the report names, in their hotbar
        held = None
        if console.inspect:
            target = stack_of(console.inspect["what"])[1]
            held = next((i for i, item in enumerate(console.inventory[:9]) if item["name"] == target), None)
        elif console.inventory and console.inventory_t > max(console.restored.values(), key=lambda v: v[0], default=(0, ""))[0] + 200:
            held = len(console.inventory[:9]) - 1
        hotbar_card(canvas, console, t, 24, row_y, held)
        restore_card(canvas, console, t, 24, row_y + 104, 388)
        held_item = console.inventory[held] if held is not None and held < len(console.inventory) else None
        inspect_card(canvas, console, t, 428, row_y, W - 428 - 24, held_item)
        if held_item and held_item.get("enchantments") and not console.inspect:
            mc.draw_tooltip(canvas, 428 - 12, row_y + 30, tooltip_lines(held_item), 2)
        style.chat_panel(canvas, chats, t, typing, names={"NyrOp": "Staff"}, width_px=W - 32, lines=4)
        caption = next((c for c in reversed(captions) if c["t"] <= t), None)
        style.header(canvas, caption, t)
        path = os.path.join(frames_dir, f"t{int(t):06d}.png" if only_frame is not None else f"f{n:04d}.png")
        canvas.convert("RGB").save(path)
    if only_frame is not None:
        return path
    return style.encode(frames_dir, os.path.join(RUN, style.output_name(scene)), FPS, total)


if __name__ == "__main__":
    name = sys.argv[1]
    if len(sys.argv) > 3 and sys.argv[2] == "--frame":
        print(render(name, float(sys.argv[3])))
    else:
        print(render(name))
