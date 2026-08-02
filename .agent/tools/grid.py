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

IOS = "artifacts/gt_ios_ref/run-1785683986267"
MARK = 1
ROW_HEIGHT = 150


def strip(prefix, times, column=None):
    meta, frames = load(prefix)
    y0, y1, x0, x1 = ink_box(frames)
    w = frames[:, y0:y1, x0:x1]
    rel = np.array(meta["times"]) - meta["marks"][MARK]["t"]

    if column is not None:
        groups = columns_of(w[-1].astype(np.float64))
        if column < len(groups):
            a, b = groups[column]
            pad = (b - a) // 2
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
    times = list(range(0, 701, step))

    runs = sorted(glob.glob(os.path.join(android_dir, "*.json")))
    if not runs:
        print("no runs in", android_dir)
        return 1

    top = strip(IOS, times, column)
    bottom = strip(runs[-1][:-5], times, column)

    cell_w = max(max(t.width for t in top), max(t.width for t in bottom)) + 8
    sheet = Image.new("L", (cell_w * len(times), ROW_HEIGHT * 2 + 34), 255)
    draw = ImageDraw.Draw(sheet)

    for row, (tiles, label) in enumerate(((top, "iOS"), (bottom, "android"))):
        y = 20 + row * ROW_HEIGHT
        for n, tile in enumerate(tiles):
            x = n * cell_w + (cell_w - tile.width) // 2
            sheet.paste(tile, (x, y + (ROW_HEIGHT - 30 - tile.height) // 2))
        draw.text((4, y + ROW_HEIGHT - 26), label, fill=90)

    for n, t in enumerate(times):
        draw.text((n * cell_w + 4, 4), f"{t}", fill=140)
        draw.line([(n * cell_w, 18), (n * cell_w, ROW_HEIGHT * 2 + 20)], fill=225)

    sheet.save(out)
    print("scritto", out, sheet.size)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
