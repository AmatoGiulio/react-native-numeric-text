#!/usr/bin/env python3
"""Play the iOS reference and an Android run side by side, as an animation.

    python3 .agent/tools/gif.py artifacts/<android-dir> out.gif --title="..."
                                [--verdict=kept|rejected] [--ios=<prefix>] [--mark=burst]
                                [--slow=4] [--from=-100] [--to=800] [--col=N]

The grids answer "what does frame 132 look like"; this answers "does it MOVE the same", which is
the question actually being asked of a transition. Both rows are re-zeroed on their own value-change
mark, cropped to their own ink box and scaled to a common settled glyph height, so what is being
compared is the motion and not the pixel size.

`--title` is REQUIRED and `--verdict` says whether this is the engine as it stands or an attempt
that was thrown away, for the same reason as in `grid.py`: an untitled sheet handed over got a
discarded experiment read as the current state.

`--slow` is how many times slower than life it plays; 1 is real time. GIF frame durations are
quantised to 10 ms, so real time is approximate and anything under 3x is hard to read.
"""

import glob
import json
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ground_truth import load, ink_box, columns_of  # noqa: E402
from grid import IOS, burst_mark, header  # noqa: E402

ROW = 190


def direction_of(meta):
    """+1 if the run's value rises, -1 if it falls, 0 if it cannot be read.

    From the MARK LABELS, not from the capture's `countsDown` field: that field reads True on runs
    that plainly increment — 1,000 to 2,722 is stored with countsDown=True — so a check built on it
    would never fire and would be a guard that only looks like one.
    """
    marks = meta.get("marks", [])
    if len(marks) < 2:
        return 0
    try:
        first = float(marks[0]["label"].replace(",", ""))
        last = float(marks[-1]["label"].replace(",", ""))
    except (KeyError, ValueError, AttributeError):
        return 0
    if last == first:
        return 0
    return 1 if last > first else -1


def frames_at(prefix, stamps, column=None, mark=1):
    meta, planes = load(prefix)
    if mark == "burst":
        mark = burst_mark(meta)
    y0, y1, x0, x1 = ink_box(planes)
    w = planes[:, y0:y1, x0:x1]
    rel = np.array(meta["times"]) - meta["marks"][mark]["t"]

    if column is not None:
        groups = columns_of(w[-1].astype(np.float64))
        if column < len(groups):
            a, b = groups[column]
            pad = (b - a) // 5
            w = w[:, :, max(0, a - pad): min(w.shape[2], b + pad)]

    settled = w[-1].astype(np.float64)
    rows = settled.sum(axis=1)
    lit = np.nonzero(rows > rows.max() * 0.05)[0]
    glyph = max(1, int(lit[-1] - lit[0]))
    scale = (ROW * 0.42) / glyph

    out = []
    for t in stamps:
        tile = Image.fromarray(255 - w[int(np.argmin(np.abs(rel - t)))])
        out.append(tile.resize(
            (max(1, int(tile.width * scale)), max(1, int(tile.height * scale))), Image.LANCZOS
        ))
    return out


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = {a.split("=")[0]: a.split("=")[1] for a in sys.argv[1:] if "=" in a and a.startswith("--")}
    if not args:
        print(__doc__)
        return 1

    android_dir = args[0]
    out = args[1] if len(args) > 1 else "out.gif"
    title = flags.get("--title")
    if not title:
        print("--title=\"...\" is required. Say what the animation shows; see the module docstring.")
        return 1
    verdict = flags.get("--verdict")
    column = int(flags["--col"]) if "--col" in flags else None
    slow = float(flags.get("--slow", 4))
    step = float(flags.get("--step", 16.7))
    start, end = float(flags.get("--from", -100)), float(flags.get("--to", 800))
    ios = flags.get("--ios", IOS)
    ios_mark = flags.get("--ios-mark", flags.get("--mark", 1))
    and_mark = flags.get("--mark", 1)
    ios_mark = ios_mark if ios_mark == "burst" else int(ios_mark)
    and_mark = and_mark if and_mark == "burst" else int(and_mark)

    runs = sorted(glob.glob(os.path.join(android_dir, "*.json")))
    if not runs:
        print("no runs in", android_dir)
        return 1

    # The two rows must be the SAME transition, and nothing else here checks it.
    #
    # `--ios` defaults to the single crossing, so `--mark=burst` on a roll silently put the
    # reference's ONE change beside fourteen of ours and the sheet read as a comparison. Two of
    # those were handed over before anyone noticed. A change count that disagrees by more than a
    # couple is not a defect in the engine, it is two different presets.
    ios_meta = json.load(open(f"{ios}.json"))
    and_meta = json.load(open(runs[-1]))
    ios_marks, and_marks = len(ios_meta.get("marks", [])), len(and_meta.get("marks", []))
    why = []
    ios_dir, and_dir = direction_of(ios_meta), direction_of(and_meta)
    if ios_dir and and_dir and ios_dir != and_dir:
        name = {1: "incrementa", -1: "decrementa"}
        why.append(f"vanno in DIREZIONI OPPOSTE — iOS {name[ios_dir]}, android {name[and_dir]}")
    if abs(ios_marks - and_marks) > 2:
        why.append(f"hanno un numero di cambi diverso — iOS {ios_marks}, android {and_marks}")
    if why:
        origin = "il default" if "--ios" not in flags else "quello che hai passato"
        print("i due lati non sono la stessa transizione: " + "; ".join(why) + ".")
        print(f"   il lato iOS e' {ios} ({origin}).")
        print("   Passa --ios=<prefisso> della corsa iOS che fa la STESSA cosa dell'android.")
        return 1

    stamps = list(np.arange(start, end, step))
    top = frames_at(ios, stamps, column, ios_mark)
    bottom = frames_at(runs[-1][:-5], stamps, column, and_mark)

    gutter = 84
    width = max(
        gutter + max(max(t.width for t in top), max(t.width for t in bottom)) + 24,
        len(title) * 13 + 24,
    )
    band = header(width, title, verdict)
    sheet = []
    for n, (a, b) in enumerate(zip(top, bottom)):
        canvas = Image.new("L", (width, band.height + ROW * 2 + 26), 255)
        canvas.paste(band, (0, 0))
        top_y = band.height
        for row, tile in enumerate((a, b)):
            canvas.paste(tile, (gutter + 12, top_y + 20 + row * ROW + (ROW - tile.height) // 2))
        draw = ImageDraw.Draw(canvas)
        draw.text((8, top_y + 20 + ROW // 2), "iOS", fill=60)
        draw.text((8, top_y + 20 + ROW + ROW // 2), "android", fill=60)
        draw.text((8, top_y + 4), f"{stamps[n]:+.0f} ms   ({slow:g}x piu' lento del vero)", fill=150)
        draw.line([(gutter, top_y + 18), (gutter, top_y + ROW * 2 + 22)], fill=225)
        draw.line([(gutter, top_y + 20 + ROW), (width, top_y + 20 + ROW)], fill=232)
        sheet.append(canvas)

    sheet[0].save(
        out, save_all=True, append_images=sheet[1:],
        duration=int(round(step * slow)), loop=0, optimize=True,
    )
    print("scritto", out, f"{len(sheet)} fotogrammi, {sheet[0].size}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
