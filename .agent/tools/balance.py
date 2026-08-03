#!/usr/bin/env python3
"""Does the ink sit still, and is it shared evenly between the two glyphs?

    python3 .agent/tools/balance.py artifacts/<single> artifacts/<alt> artifacts/<roll>

Two numbers per regime, both about the pair rather than about either glyph:

  travel    peak-to-peak excursion of the column's ink centroid, in glyph heights. How far the
            visible mass slides.
  balance   the ink's share of the UPPER half of the pair, as a STANDARD DEVIATION over the run.
            Small means the pair holds together; large means one glyph at a time.
  width     the ink's horizontal 2nd-98th percentile span, in settled glyph widths — how big the
            glyphs are, which is the one thing here that vertical separation cannot confound.

Balance is a standard deviation and not a peak-to-peak, and that correction mattered: on the same
captures the peak-to-peak read 0.19 against 0.39 and the standard deviation reads 0.059 against
0.075. The peak-to-peak is set by a handful of extreme frames, so it said this engine was twice the
reference's when the distributions are a quarter apart. Frame by frame the reference sits at 0.37,
not at a balanced 0.50, and oscillates continuously — which is what the eye sees and what a
peak-to-peak throws away.

The reference does not vary the second one at all — 0.19 in a single crossing, 0.19 under a 60 ms
alternation, 0.19 through a continuous roll — and barely varies the first, 0.163 / 0.103 / 0.119.
It was matching the single crossing and missing both of the others by three to four times that put
this file here: a metric fitted on one regime says nothing about the two it was not fitted on.

Reported by eye first — "our movement is wider, ours looks like a whole step and theirs like half
of one" — and then measured. The eye was right and the headline metric had nothing to say about it.
"""

import glob
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ground_truth import load, ink_box, columns_of, profile_stats  # noqa: E402
from grid import burst_mark  # noqa: E402

HERE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# regime -> (iOS glob, iOS label filter, column, mark, window ms)
REGIMES = {
    "cambio singolo": ("artifacts/gt_ios_ref/run-*.json", None, 2, 1, (-30, 700)),
    "alternanza 60ms": ("artifacts/gt_ios_alt3/run-1785749998789.json", None, -1, 1, (0, 1200)),
    "rullo continuo": ("artifacts/gt_ios_bursts/*.json", "1,000", -1, "burst", (-30, 600)),
}


def measure(prefix, col, mark, window):
    meta, frames = load(prefix)
    # A capture that caught only the preset's reset has one mark and no transition in it; that is a
    # lost run, not a reason to take the whole round's analysis down with an IndexError.
    if len(meta.get("marks", [])) < 2:
        return None
    y0, y1, x0, x1 = ink_box(frames)
    w = frames[:, y0:y1, x0:x1].astype(np.float64)
    groups = columns_of(w[-1])
    if not groups:
        return None
    a, b = groups[col]
    settled = w[-1][:, a:b]
    rows = settled.sum(axis=1)
    lit = np.nonzero(rows > rows.max() * 0.05)[0]
    if len(lit) < 2:
        return None
    glyph = float(lit[-1] - lit[0])
    base = profile_stats(settled, glyph)[0]
    cols = settled.sum(axis=0)
    wlit = np.nonzero(cols > cols.max() * 0.05)[0]
    base_width = float(wlit[-1] - wlit[0]) if len(wlit) > 1 else 1.0

    marks = meta["marks"]
    origin = marks[burst_mark(meta) if mark == "burst" else mark]["t"]
    times = np.array(meta["times"]) - origin
    selected = (times >= window[0]) & (times <= window[1])

    centres, shares, widths = [], [], []
    for i in np.nonzero(selected)[0]:
        patch = w[i, :, a:b]
        rows = patch.sum(axis=1)
        total = rows.sum()
        if total <= 0:
            continue
        centres.append(profile_stats(patch, glyph)[0] - base)
        cumulative = np.cumsum(rows) / total
        span = rows[np.searchsorted(cumulative, 0.02): np.searchsorted(cumulative, 0.98) + 1]
        if span.sum() > 0:
            shares.append(span[: len(span) // 2].sum() / span.sum())
        cols = patch.sum(axis=0)
        if cols.sum() > 0:
            c = np.cumsum(cols) / cols.sum()
            widths.append(
                (np.searchsorted(c, 0.98) - np.searchsorted(c, 0.02)) / base_width
            )
    if len(centres) < 4 or len(shares) < 4:
        return None
    return float(np.ptp(centres)), float(np.std(shares)), float(np.median(widths))


def rows_of(pattern, col, mark, window, label=None):
    out = []
    for path in sorted(glob.glob(pattern)):
        if path.endswith("reference.json"):
            continue
        if label and json.load(open(path)).get("label") != label:
            continue
        got = measure(path[:-5], col, mark, window)
        if got:
            out.append(got)
    return out


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != 3:
        print(__doc__)
        return 1

    print("   regime            escursione        squilibrio (dev.st)   larghezza")
    for (name, (ios, label, col, mark, window)), android in zip(REGIMES.items(), args):
        # The Android capture always aligns on the burst mark: every preset resets the value first
        # and that reset takes a mark of its own.
        theirs = rows_of(os.path.join(HERE, ios), col, mark, window, label)
        ours = rows_of(os.path.join(android, "*.json"), col, "burst" if mark == 1 and "alt" in android else mark, window)
        if not theirs or not ours:
            print(f"   {name:17} nessun run confrontabile")
            continue
        t = [float(np.median([r[i] for r in theirs])) for i in (0, 1, 2)]
        o = [float(np.median([r[i] for r in ours])) for i in (0, 1, 2)]
        print(
            f"   {name:17} {t[0]:.3f} / {o[0]:.3f}      "
            f"{t[1]:.3f} / {o[1]:.3f}        {t[2]:.3f} / {o[2]:.3f}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
