#!/usr/bin/env python3
"""Count how many distinct glyph forms the reference has on screen at once.

    python3 .agent/tools/forms.py artifacts/<dir> [--col=-1] [--from=0] [--to=4000]

This is the measurement that settles what `.numericText()` IS, and it only works under a LINEAR
animation. `.numericText()` has no clock of its own — it runs on whatever animation is in the
transaction — so every previous reading of the reference has been the transition's curves
multiplied by a spring, solved together. Launch with `NUMERICTEXT_LINEAR=2` (see
`NumericTextSwiftUIHost.swift`) and the clock becomes known and slow.

Then, under a fast alternation:

  - a STACK of overlapping transitions has many transitions alive at once, each at its own
    progress, so their glyphs sit at many different offsets: several separated lobes, and an ink
    extent far wider than one step;
  - a single POSITION on a strip can only ever show the two stops bracketing it, however the
    target moves: two lobes, and an extent bounded by one step.

The two answers differ by a factor, not by a few percent, which is why counting is enough.

Reported per frame and summarised: the number of local maxima in the column's vertical ink
profile, and the profile's 2nd-98th percentile span in settled glyph heights.
"""

import argparse
import glob
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ground_truth import load, ink_box, columns_of  # noqa: E402


def lobes(profile, floor=0.18, min_gap=3):
    """Local maxima above `floor` of the profile's peak, separated by at least `min_gap` bins.

    The floor is what stops a blurred shoulder counting as a form; the gap is what stops one lobe
    with a noisy top counting twice.
    """
    peak = profile.max()
    if peak <= 0:
        return 0
    found = []
    for i in range(1, len(profile) - 1):
        if profile[i] < floor * peak:
            continue
        if profile[i] >= profile[i - 1] and profile[i] > profile[i + 1]:
            if not found or i - found[-1] >= min_gap:
                found.append(i)
    return len(found)


def measure(prefix, col, window):
    meta, frames = load(prefix)
    y0, y1, x0, x1 = ink_box(frames)
    w = frames[:, y0:y1, x0:x1].astype(np.float64)
    groups = columns_of(w[-1])
    a, b = groups[col]
    settled = w[-1][:, a:b]
    rows = settled.sum(axis=1)
    lit = np.nonzero(rows > rows.max() * 0.05)[0]
    glyph = float(lit[-1] - lit[0])

    marks = meta.get("marks", [])
    origin = marks[1]["t"] if len(marks) > 1 else 0.0
    times = np.array(meta["times"]) - origin

    out = []
    for i in np.nonzero((times >= window[0]) & (times <= window[1]))[0]:
        rows = w[i, :, a:b].sum(axis=1)
        total = rows.sum()
        if total <= 0:
            continue
        # Smooth just enough that the raster's own texture is not counted as structure.
        k = np.ones(5) / 5.0
        smooth = np.convolve(rows, k, mode="same")
        cumulative = np.cumsum(smooth) / smooth.sum()
        lo = int(np.searchsorted(cumulative, 0.02))
        hi = int(np.searchsorted(cumulative, 0.98))
        span = smooth[lo:hi + 1]
        out.append((float(times[i]), lobes(span), (hi - lo) / glyph))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("path")
    ap.add_argument("--col", type=int, default=-1)
    ap.add_argument("--from", dest="lo", type=float, default=0.0)
    ap.add_argument("--to", dest="hi", type=float, default=4000.0)
    args = ap.parse_args()

    paths = sorted(glob.glob(os.path.join(args.path, "*.json")))
    for path in paths:
        if path.endswith("reference.json"):
            continue
        try:
            rows = measure(path[:-5], args.col, (args.lo, args.hi))
        except Exception as exc:
            print(f"   {os.path.basename(path)}: {exc}")
            continue
        if not rows:
            continue
        counts = [r[1] for r in rows]
        spans = [r[2] for r in rows]
        hist = {}
        for c in counts:
            hist[c] = hist.get(c, 0) + 1
        shape = " ".join(f"{k}:{v}" for k, v in sorted(hist.items()))
        print(f"   {os.path.basename(path)[:28]:30} n={len(rows):4d}  "
              f"forme mediana {int(np.median(counts))}  max {max(counts)}   "
              f"estensione mediana {np.median(spans):.2f}  max {max(spans):.2f}")
        print(f"      distribuzione forme   {shape}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
