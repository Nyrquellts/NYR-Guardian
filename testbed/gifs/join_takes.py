#!/usr/bin/env python3
"""Joins two or more capture.mjs recordings into one, so a before/after GIF can be made of a plugin that cannot be
switched off while a server runs.

NYR Anvil Fix has no config and no command on purpose, so its "Too Expensive!" half is recorded on a server with no
plugin and its capped half on a server with only its jar. Both halves are real takes; this only shifts the second one's
clock so the two play one after the other.

    python join_takes.py anvilfix-too-expensive anvilfix-vanilla anvilfix-fixed
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RUN = os.path.join(HERE, "..", "run", "gifs")

# A moment between takes, so the last state of one is still on screen when the next begins.
GAP_MS = 250


def join(out_scene, takes):
    joined, at = [], 0
    first = None
    for name in takes:
        with open(os.path.join(RUN, f"{name}.json"), encoding="utf-8") as handle:
            take = json.load(handle)
        if first is None:
            first = take
        for event in take["events"]:
            joined.append({**event, "t": event["t"] + at})
        at += take["duration"] + GAP_MS
    data = {k: v for k, v in first.items() if k not in ("scene", "duration", "events")}
    data.update({"scene": out_scene, "duration": at - GAP_MS, "events": joined})
    path = os.path.join(RUN, f"{out_scene}.json")
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(data, handle)
    print(f"joined {len(takes)} takes into {path} ({len(joined)} events, {data['duration'] / 1000:.1f} s)")
    return path


if __name__ == "__main__":
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    join(sys.argv[1], sys.argv[2:])
