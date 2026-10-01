#!/usr/bin/env python3
"""Draws the capture.mjs zero-setup recording (anvilfix-zero-setup) as a listing GIF: what the server printed when the
plugin started, what its plugins folder holds afterwards, and what the server says when an admin tries a command.

Every line on the picture comes out of the recording, which read them off the running server; nothing here is written by
this script except the card headings.

    python render_setup.py anvilfix-zero-setup [--frame SECONDS]
"""
import json
import os
import re
import shutil
import sys
from functools import lru_cache

from PIL import Image, ImageDraw, ImageFont

import style

HERE = os.path.dirname(os.path.abspath(__file__))
RUN = os.path.join(HERE, "..", "run", "gifs")
FPS = 20
W, H = style.W, style.H
MONO = r"C:\Windows\Fonts\consola.ttf"
CARD_FILL, CARD_EDGE = (12, 14, 18, 225), (44, 48, 56)
WHITE, GREY, DIM = (255, 255, 255), (176, 182, 192), (120, 126, 136)
GREEN, RED = (46, 204, 113), (235, 100, 90)
# a console line is revealed this long after the one before it
LINE_MS = 900
# Minecraft colour codes: the section sign, or the replacement character a client decoded it to
COLOUR = re.compile("[\u00a7\ufffd].")


def clean(text):
    """One chat line with its colour codes taken out; the server puts the error itself on the first line."""
    return COLOUR.sub("", text).split("\n")[0].strip()


@lru_cache(maxsize=None)
def mono(size):
    return ImageFont.truetype(MONO, size)


def card(canvas, x, y, width, height, title):
    """A titled panel. Returns the y the first row of content sits on."""
    panel = Image.new("RGBA", (width, height))
    d = ImageDraw.Draw(panel)
    d.rounded_rectangle([0, 0, width - 1, height - 1], radius=12, fill=CARD_FILL, outline=CARD_EDGE + (255,), width=2)
    d.text((20, 16), title, font=style.fonts()["name"], fill=WHITE + (255,))
    canvas.alpha_composite(panel, (x, y))
    return y + 52


def revealed(events, card_name, t):
    """When a card was shown, or None while it is still to come."""
    for e in events:
        if e["type"] == "show" and e.get("card") == card_name and e["t"] <= t:
            return e["t"]
    return None


def fitted(lines, width, draw):
    """The largest console size whose longest line still fits the card, so no line is cut off."""
    for size in range(15, 8, -1):
        if all(draw.textlength(line, font=mono(size)) <= width for line in lines):
            return mono(size)
    return mono(9)


def console_card(canvas, x, y, width, lines, since, t, draw):
    """The plugin's own start-up lines, one appearing after the other."""
    height = 52 + 30 * max(len(lines), 1) + 12
    row = card(canvas, x, y, width, height, "Server console, first start")
    d = ImageDraw.Draw(canvas)
    font = fitted([line.replace("] ", "]  ", 1) for line in lines], width - 40, draw)
    for i, line in enumerate(lines):
        if t < since + 300 + i * LINE_MS:
            break
        level, _, rest = line.partition("] ")
        d.text((x + 20, row + i * 30), level + "]", font=font, fill=DIM + (255,))
        d.text((x + 20 + d.textlength(level + "]  ", font=font), row + i * 30), rest, font=font, fill=GREY + (255,))
    return y + height


def zeroes_row(canvas, x, y, width, height):
    """The three zeroes, as the product promises them: no config, no command, no bundled library."""
    gap = 18
    each = (width - 2 * gap) // 3
    f = style.fonts()
    for i, (what, detail) in enumerate([("config files", "nothing to edit"), ("commands", "nothing to learn"),
                                        ("dependencies", "nothing else to install")]):
        panel = Image.new("RGBA", (each, height))
        d = ImageDraw.Draw(panel)
        d.rounded_rectangle([0, 0, each - 1, height - 1], radius=12, fill=CARD_FILL, outline=GREEN + (120,), width=2)
        d.text((20, 14), "0", font=f["hero"], fill=GREEN + (255,))
        d.text((20 + d.textlength("0", font=f["hero"]) + 12, 32), what, font=f["name"], fill=WHITE + (255,))
        d.text((20, height - 34), detail, font=f["state"], fill=DIM + (255,))
        canvas.alpha_composite(panel, (x + i * (each + gap), y))


def plugins_card(canvas, x, y, width, height, entries):
    """What the plugins folder holds after that start. bStats and spark are the server's own, not this plugin's."""
    row = card(canvas, x, y, width, height, "plugins/ after the first start")
    d = ImageDraw.Draw(canvas)
    for i, name in enumerate(entries):
        folder = "." not in name
        ours = name.startswith("NYR-AnvilFix")
        d.text((x + 20, row + i * 26), ("[dir] " if folder else "      ") + name, font=mono(14),
               fill=(DIM if folder else (GREEN if ours else WHITE)) + (255,))
    written = [n for n in entries if n.startswith("NYR-AnvilFix") and "." not in n]
    note = "NYR-AnvilFix wrote no folder of its own" if not written else "it wrote " + ", ".join(written)
    d.text((x + 20, y + height - 32), note, font=style.fonts()["state"], fill=(GREEN if not written else RED) + (255,))


def command_card(canvas, x, y, width, height, typed, reply):
    """An admin trying a command the plugin does not register, and what the server answers."""
    row = card(canvas, x, y, width, height, "An admin tries a command")
    d = ImageDraw.Draw(canvas)
    d.text((x + 20, row), typed, font=mono(16), fill=WHITE + (255,))
    for i, line in enumerate(reply):
        d.text((x + 20, row + 32 + i * 22), line, font=mono(14), fill=RED + (255,))
    d.text((x + 20, y + height - 32), "nothing to learn, nothing to permission", font=style.fonts()["state"], fill=GREEN + (255,))


def wrap(text, font, width, draw):
    lines, line = [], ""
    for word in text.split():
        trial = (line + " " + word).strip()
        if draw.textlength(trial, font=font) <= width:
            line = trial
        else:
            lines.append(line)
            line = word
    if line:
        lines.append(line)
    return lines


def render(scene, only_frame=None):
    style.PRODUCT["name"] = style.product_of(scene)
    with open(os.path.join(RUN, f"{scene}.json"), encoding="utf-8") as handle:
        data = json.load(handle)
    events, duration = style.warp(data["events"], tail=2600)
    captions = [e for e in events if e["type"] == "caption"]
    console_lines = data.get("console", [])
    entries = data.get("plugins", [])
    chats = [e for e in events if e["type"] == "chat"]

    frames_dir = os.path.join(RUN, f"{scene}-frames")
    if only_frame is None:
        shutil.rmtree(frames_dir, ignore_errors=True)
    os.makedirs(frames_dir, exist_ok=True)
    total = int(duration / 1000 * FPS)
    times = [only_frame * 1000] if only_frame is not None else [i * 1000 / FPS for i in range(total)]

    measure = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    for n, t in enumerate(times):
        canvas = style.background().copy()
        y = style.HEADER + 30
        shown = revealed(events, "console", t)
        if shown is not None:
            y = console_card(canvas, 24, y, W - 48, console_lines, shown, t, measure) + 22
        if revealed(events, "plugins", t) is not None:
            plugins_card(canvas, 24, y, 424, 152, entries)
        if revealed(events, "command", t) is not None:
            said = [clean(c["text"]) for c in chats if c["t"] <= t]
            said = [s for s in said if s and "anvilfix" not in s.lower()]
            reply = wrap(said[-1], mono(14), 360, measure)[:3] if said else []
            command_card(canvas, 452, y, W - 476, 152, "/anvilfix", reply)
        if revealed(events, "zeroes", t) is not None:
            zeroes_row(canvas, 24, y + 174, W - 48, 104)
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
