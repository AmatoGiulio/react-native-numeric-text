#!/usr/bin/env python3
"""Side-by-side frame grid: the iOS reference above, an Android run below, same clock.

    python3 .agent/tools/grid.py artifacts/<android-run-dir> [out.png] [--col N] [--step MS]

Both rows are re-zeroed on their own transition mark and cropped to their own ink box, then
scaled to a common glyph height so the shapes are comparable rather than the pixel sizes. With
``--col`` the grid zooms into one column, which is the only way to see what a single crossing
actually does.
"""

import glob
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ground_truth import load, ink_box, columns_of  # noqa: E402

# Decrement 1,242 -> 1,160 by default; --ios= and --mark= point at another reference run.
IOS = "artifacts/gt_ios_ref/run-1785683986267"
MARK = 1
ROW_HEIGHT = 150


def burst_mark(meta, gap=250.0):
    """Index of the mark the burst starts on.

    A preset resets the value before it runs, and that reset is itself a change — so it gets a mark
    of its own on whichever platform was not already sitting on the starting value. Aligning two
    recordings on mark 0 then compares one platform's roll against the other's idle second, which
    is exactly the mistake that had me report a defect the engine did not have. The burst is the
    first mark whose successor follows within `gap`.
    """
    marks = meta["marks"]
    for i in range(len(marks) - 1):
        if marks[i + 1]["t"] - marks[i]["t"] <= gap:
            return i
    return 0


def strip(prefix, times, column=None, mark=MARK):
    meta, frames = load(prefix)
    if mark == "burst":
        mark = burst_mark(meta)
    y0, y1, x0, x1 = ink_box(frames)
    w = frames[:, y0:y1, x0:x1]
    rel = np.array(meta["times"]) - meta["marks"][mark]["t"]

    if column is not None:
        groups = columns_of(w[-1].astype(np.float64))
        if column < len(groups):
            a, b = groups[column]
            pad = (b - a) // 6
            w = w[:, :, max(0, a - pad) : min(w.shape[2], b + pad)]

    # Normalise the vertical scale on the settled glyph so both platforms are drawn at one size.
    settled = w[-1].astype(np.float64)
    rows = settled.sum(axis=1)
    lit = np.nonzero(rows > rows.max() * 0.05)[0]
    glyph = max(1, int(lit[-1] - lit[0]))

    tiles = []
    for t in times:
        i = int(np.argmin(np.abs(rel - t)))
        tile = Image.fromarray(255 - w[i])
        scale = (ROW_HEIGHT * 0.55) / glyph
        tile = tile.resize((max(1, int(tile.width * scale)), max(1, int(tile.height * scale))), Image.LANCZOS)
        tiles.append(tile)
    return tiles


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = {a.split("=")[0]: a.split("=")[1] for a in sys.argv[1:] if "=" in a and a.startswith("--")}
    if not args:
        print(__doc__)
        return 1

    android_dir = args[0]
    out = args[1] if len(args) > 1 else os.path.join(android_dir, "grid.png")
    column = int(flags["--col"]) if "--col" in flags else None
    step = int(flags.get("--step", 50))
    ios = flags.get("--ios", IOS)
    ios_mark = flags.get("--ios-mark", MARK)
    and_mark = flags.get("--mark", MARK)
    ios_mark = ios_mark if ios_mark == "burst" else int(ios_mark)
    and_mark = and_mark if and_mark == "burst" else int(and_mark)
    times = list(range(0, 701, step))

    runs = sorted(glob.glob(os.path.join(android_dir, "*.json")))
    if not runs:
        print("no runs in", android_dir)
        return 1

    top = strip(ios, times, column, ios_mark)
    bottom = strip(runs[-1][:-5], times, column, and_mark)

    # A left gutter for the row labels. Drawing them inside the band puts each one under the next
    # row's tiles, and every label but the last is lost — which makes a multi-row sheet unreadable.
    gutter = 74
    cell_w = max(max(t.width for t in top), max(t.width for t in bottom)) + 8
    # The row height comes from the TALLEST tile, never a fixed constant. PIL crops a paste that
    # runs past the sheet without saying so, and the blurriest row is the tallest — so a fixed
    # height silently cut the top and bottom off exactly the row being examined.
    row_h = max(t.height for t in top + bottom) + 26
    sheet = Image.new("L", (gutter + cell_w * len(times), row_h * 2 + 24), 255)
    draw = ImageDraw.Draw(sheet)

    for row, (tiles, label) in enumerate(((top, "iOS"), (bottom, "android"))):
        y = 18 + row * row_h
        for n, tile in enumerate(tiles):
            x = gutter + n * cell_w + (cell_w - tile.width) // 2
            sheet.paste(tile, (x, y + (row_h - 26 - tile.height) // 2))
        draw.text((6, y + row_h // 2 - 6), label, fill=60)

    for n, t in enumerate(times):
        draw.text((gutter + n * cell_w + 4, 4), f"{t}", fill=140)
        draw.line([(gutter + n * cell_w, 16), (gutter + n * cell_w, row_h * 2 + 18)], fill=225)

    sheet.save(out)
    print("scritto", out, sheet.size)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
