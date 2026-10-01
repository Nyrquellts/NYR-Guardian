"""Listing GIFs that fit BuiltByBit pages: under 2.5 MB (aim for 1.0 to 1.8 MB), 800 to 900 px wide, and the finished state
held at least 3 s before the loop starts again.

    python bbb_gifs.py audit a.gif b.gif ...
    python bbb_gifs.py encode FRAMES_DIR OUT.gif [--fps 20] [--end-at SECONDS]
    python bbb_gifs.py all [SCENE ...]

`all` re-encodes this repository's store GIFs from run/gifs/<scene>-frames, naming each one from style.OUTPUTS, and audits
what it wrote.

`encode` reads a renderer's PNG frames (f0000.png, f0001.png, ...). It cuts a chat readout that fades out at the very end,
clones the last frame until the final state has been on screen for HOLD_S, and then lowers frame rate and colours only as
far as the size aim needs.

The final state starts at the last new caption (the top HEADER px), new chat text (the bottom of the frame) or change to a
steady row in between, such as a menu. Rows that change in most frames are scenery (walking mobs, flowing lava), and so
are changes under 400 px (particles, a blinking caret).
"""
import os
import shutil
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image

FFMPEG = "ffmpeg"
JAVA = os.environ.get("NYR_JAVA21") or shutil.which("java") or os.path.expanduser(
    "~/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin/java.exe")
LOSSY_GIF = os.path.join(os.path.dirname(os.path.abspath(__file__)), "LossyGif.java")
HEADER = 96           # both renderers draw captions in the top 96 px
CHAT_FROM = 0.77      # the chat band starts at 77% of the height (y 539 of 700, y 569 of 740)
HOLD_S = 3.2          # the rule asks for 2.5 to 3 s; captions fade in for 250 ms
AIM_MB, LIMIT_MB = 1.8, 2.5
WIDTH = (800, 900)
# Frame rate and lossy level, best first: the first step that meets the size aim wins, and a GIF that meets no aim takes
# the first step under the limit. Level 0 is ffmpeg's exact encode; above it LossyGif.java keeps all 256 colours and lets
# each shown pixel sit up to that many levels from its palette colour, which crowds of moving mobs need (64 colours
# visibly washed them out). Lower loss comes before lower frame rate. The width never changes: shrinking blurs the pixel
# font.
LADDER = [(20, 0), (15, 0), (15, 16), (12.5, 16), (12.5, 24), (10, 24), (12.5, 32), (10, 32), (10, 40)]


# Scenes whose chat readout starts expiring before the recording stops (Minecraft hides a chat line ten seconds after it
# arrives), with the second the readout is last complete. Everything else ends where faded_tail puts it.
END_AT = {  # NYR Guardian scenes: add "scene": seconds here once measured, or pass --end-at to encode
}


def png_frames(frames_dir):
    names = sorted(n for n in os.listdir(frames_dir) if n.startswith("f") and n.endswith(".png"))
    return [np.asarray(Image.open(os.path.join(frames_dir, n)).convert("L"), dtype=np.int16) for n in names]


def gif_frames(path):
    frames, durations = [], []
    with Image.open(path) as im:
        loop = im.info.get("loop")
        try:
            while True:
                frames.append(np.asarray(im.convert("L"), dtype=np.int16))
                durations.append(im.info.get("duration", 50))
                im.seek(im.tell() + 1)
        except EOFError:
            pass
    return frames, durations, loop


def text_bands(frame):
    h = frame.shape[0]
    return frame[:HEADER], frame[int(h * CHAT_FROM):]


def chat_ink(frame):
    return int((text_bands(frame)[1] > 110).sum())


def middle(frame):
    return frame[HEADER:int(frame.shape[0] * CHAT_FROM)]


def header_change(a, b):
    return int((np.abs(text_bands(b)[0] - text_bands(a)[0]) > 24).sum())


def steady_rows(frames):
    """Rows of the middle of the frame that change in under a quarter of the frames: menus and counters, not mobs or lava."""
    if len(frames) < 2:
        return np.ones(middle(frames[0]).shape[0], dtype=bool)
    activity = np.mean([(np.abs(middle(b) - middle(a)) > 24).any(axis=1) for a, b in zip(frames, frames[1:])], axis=0)
    return activity < 0.25


def state_changed(a, b, steady):
    """A new caption, new chat text (pixels turning to ink; lines fading out are not a new state) or a change in the steady
    rows between them, such as items landing in a menu."""
    (_, ca), (_, cb) = text_bands(a), text_bands(b)
    new_ink = int(((cb - ca > 24) & (cb > 110)).sum())
    menu = int((np.abs(middle(b) - middle(a))[steady] > 24).sum())
    return header_change(a, b) > 400 or new_ink > 400 or menu > 400


def final_state_start(frames):
    """The frame where the finished state begins. Steady rows are judged within the last caption's stretch only, so mobs
    that roam there count as scenery while clicks in a menu still count as a new state."""
    caption = 0
    for i in range(len(frames) - 1, 0, -1):
        if header_change(frames[i - 1], frames[i]) > 400:
            caption = i
            break
    steady = steady_rows(frames[max(0, caption - 1):])
    for i in range(len(frames) - 1, caption, -1):
        if state_changed(frames[i - 1], frames[i], steady):
            return i
    return caption


def faded_tail(frames):
    """How many frames at the end come after the chat readout began fading (Minecraft hides chat lines after ten seconds).
    Measured from the start of the finished state: if the chat ends with under 60% of its ink, the clip is cut back to the
    last frame that still had 90% of it, and that frame is the one held."""
    start = final_state_start(frames)
    ink = [chat_ink(f) for f in frames[start:]]
    peak = max(ink)
    if not peak or ink[-1] >= 0.6 * peak:
        return 0
    last_full = max(i for i, v in enumerate(ink) if v >= 0.9 * peak)
    return len(ink) - 1 - last_full


def final_state_ms(frames, durations):
    """How long the finished state stays on screen before the loop."""
    return sum(durations[final_state_start(frames):])


def audit(paths):
    ok = True
    for path in paths:
        frames, durations, loop = gif_frames(path)
        mb = os.path.getsize(path) / 1_000_000
        h, w = frames[0].shape
        held = final_state_ms(frames, durations) / 1000
        faded = faded_tail(frames)
        problems = []
        if mb >= LIMIT_MB:
            problems.append(f"{mb:.2f} MB, over {LIMIT_MB}")
        if not WIDTH[0] <= w <= WIDTH[1]:
            problems.append(f"{w} px wide")
        if held < 2.5:
            problems.append(f"final state {held:.2f} s")
        if faded:
            problems.append(f"chat fades in the last {faded} frames")
        if loop not in (0, None):
            problems.append(f"loops {loop} times")
        ok &= not problems
        print(f"{'OK ' if not problems else 'FIX'} {os.path.basename(path):48} {mb:5.2f} MB {w}x{h} {len(frames):4} frames "
              f"{sum(durations) / 1000:5.1f} s, final state {held:4.2f} s  {'; '.join(problems)}")
    return ok


def encode(frames_dir, out, fps_in=20, end_at=None):
    """end_at (seconds into the frames) ends the clip at a chosen frame instead of the detected one, for scenes where roaming
    mobs hide when the chat readout was last complete."""
    frames = png_frames(frames_dir)
    if end_at is None:
        cut = faded_tail(frames)
    else:
        cut = max(0, len(frames) - 1 - round(end_at * fps_in))
    kept = len(frames) - cut
    height = frames[0].shape[0]
    step = 1000 / fps_in
    held = final_state_ms(frames[:kept], [step] * kept)
    pad = max(0.0, HOLD_S - held / 1000)
    del frames
    source = os.path.join(frames_dir, "f%04d.png")
    chosen = None
    with tempfile.TemporaryDirectory() as tmp:
        for i, (fps, lossy) in enumerate(LADDER):
            trial = os.path.join(tmp, f"trial{i}.gif")
            if lossy == 0:
                chain = (f"trim=end_frame={kept},tpad=stop_mode=clone:stop_duration={pad:.3f},fps={fps},split[a][b];"
                         "[a]palettegen=max_colors=256:stats_mode=diff[p];[b][p]paletteuse=dither=none:diff_mode=rectangle")
                subprocess.run([FFMPEG, "-y", "-loglevel", "error", "-framerate", str(fps_in), "-i", source, "-vf", chain,
                                "-loop", "0", trial], check=True)
            else:
                palette = os.path.join(tmp, f"palette-{fps}.png")
                if not os.path.exists(palette):
                    subprocess.run([FFMPEG, "-y", "-loglevel", "error", "-framerate", str(fps_in), "-i", source, "-vf",
                                    f"trim=end_frame={kept},fps={fps},palettegen=max_colors=256:stats_mode=full", palette],
                                   check=True)
                subprocess.run([JAVA, "-Xmx1g", LOSSY_GIF, f"frames={frames_dir}", f"count={kept}", f"palette={palette}",
                                f"out={trial}", f"fps-in={fps_in}", f"fps={fps}", f"hold-ms={round(pad * 1000)}",
                                f"lossy={lossy}", f"exact-above={HEADER}", f"exact-from={int(height * CHAT_FROM)}"],
                               check=True, stdout=subprocess.DEVNULL)
            mb = os.path.getsize(trial) / 1_000_000
            if chosen is None and mb < LIMIT_MB:
                chosen = (trial, fps, lossy, mb)
            if mb <= AIM_MB:
                chosen = (trial, fps, lossy, mb)
                break
        if chosen is None:
            raise RuntimeError(f"{out}: no step got under {LIMIT_MB} MB")
        trial, fps, lossy, mb = chosen
        os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
        os.replace(trial, out)
    print(f"{os.path.basename(out)}: {fps} fps, lossy {lossy}, {mb:.2f} MB; cut {cut} faded frames, final state was "
          f"{held / 1000:.2f} s, held {pad:.2f} s longer", flush=True)
    return out


if __name__ == "__main__":
    if sys.argv[1:2] == ["audit"]:
        sys.exit(0 if audit(sys.argv[2:]) else 1)
    if sys.argv[1:2] == ["encode"]:
        def flag(name, default):
            return float(sys.argv[sys.argv.index(name) + 1]) if name in sys.argv else default
        encode(sys.argv[2], sys.argv[3], flag("--fps", 20), flag("--end-at", None))
        sys.exit(0)
    if sys.argv[1:2] == ["all"]:
        import style
        run = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "run", "gifs")
        want = sys.argv[2:] or list(style.OUTPUTS)
        store = os.path.join(run, "..", "store")

        def target(scene):
            folder = os.path.join(store, style.STORE.get(scene.split("-")[0], "other"))
            os.makedirs(folder, exist_ok=True)
            return os.path.join(folder, style.output_name(scene))
        outs = [encode(os.path.join(run, scene + "-frames"), target(scene), end_at=END_AT.get(scene)) for scene in want]
        sys.exit(0 if audit(outs) else 1)
    print(__doc__)
    sys.exit(2)
