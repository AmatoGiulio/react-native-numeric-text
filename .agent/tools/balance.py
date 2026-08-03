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

**Compare a cadence against the SAME cadence, and never a width across two regimes.** Both were
got wrong here and both changed what the numbers said:

  - This script used to take the iOS side from ONE file and the Android side from every json in
    the directory — which holds two runs at 60 ms and two at 120. Every Android "alternation"
    number it printed was the mean of a 60 and a 120. Separated, the balance reads 0.050 against
    the reference's 0.058 and the width 0.782 against 0.779, where mixed they read 0.096 and 0.806
    and were being called the largest defect left.
  - `width` has a REGIME-DEPENDENT ZERO. A settled glyph's ink runs from 85 px for a "1" to 147 px
    for a "4", 1.73x, and the divisor is whichever digit the run happened to end on. Two settled
    digits blended with NO shrink at all measure 0.91 on this metric, not 1.00. So the alternation
    (digits 0 and 1) and the roll (all ten) do not share a scale, and the 0.779-against-0.969 that
    once read as "it shrinks when it oscillates and not when it travels" is mostly the digits.
    iOS against Android at one cadence is sound; anything across regimes is not.

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

# regime -> (iOS glob, iOS label filter, column, mark, window ms, split by cadence?)
REGIMES = {
    "cambio singolo": ("artifacts/gt_ios_ref/run-*.json", None, 2, 1, (-30, 700), False),
    "alternanza": ("artifacts/gt_ios_alt3/run-*.json", None, -1, 1, (0, 1200), True),
    "rullo continuo": ("artifacts/gt_ios_bursts/*.json", "1,000", -1, "burst", (-30, 600), False),
}


def cadence_of(meta):
    """The alternation's step, in ms, read out of the capture's own marks — rounded to 30 ms.

    Mark 0 is the preset's reset to the starting value and is not part of the cadence, so the gaps
    are taken from mark 1 onwards. Returns None for a capture with too few marks to hold one.
    """
    marks = meta.get("marks", [])
    if len(marks) < 4:
        return None
    times = [m["t"] for m in marks]
    gaps = sorted(times[i + 1] - times[i] for i in range(1, len(times) - 1))
    return int(round(gaps[len(gaps) // 2] / 30.0) * 30)


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
    """Measure every run the pattern matches, keyed by its cadence.

    Returns {cadence_or_None: [(travel, balance, width), ...]}. Keying rather than pooling is the
    whole point: pooled, a directory of two 60 ms runs and two 120 ms ones reported their mean as
    "60 ms" and it read as a defect that was not there.
    """
    out = {}
    for path in sorted(glob.glob(pattern)):
        if path.endswith("reference.json"):
            continue
        meta = json.load(open(path))
        if label and meta.get("label") != label:
            continue
        got = measure(path[:-5], col, mark, window)
        if got:
            out.setdefault(cadence_of(meta), []).append(got)
    return out


def summarise(rows):
    """Median of each metric, and the peak-to-peak SPREAD across the runs behind it.

    The spread is printed because the 60 ms band and the roll's travel are not repeatable to the
    precision they were being fitted at: two runs of one build read 0.926 and 0.754, and rounds
    were being chosen on differences of 0.03. A median with no scatter beside it invites exactly
    that. Anything smaller than the spread is not a result.
    """
    med = [float(np.median([r[i] for r in rows])) for i in (0, 1, 2)]
    spread = [float(np.ptp([r[i] for r in rows])) for i in (0, 1, 2)]
    return med, spread, len(rows)


def line(name, theirs, ours):
    t, _, tn = summarise(theirs)
    o, os_, on = summarise(ours)
    print(
        f"   {name:17} {t[0]:.3f} / {o[0]:.3f} ±{os_[0]:.3f}   "
        f"{t[1]:.3f} / {o[1]:.3f} ±{os_[1]:.3f}   "
        f"{t[2]:.3f} / {o[2]:.3f} ±{os_[2]:.3f}   ({tn} iOS / {on} and)"
    )


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != 3:
        print(__doc__)
        return 1

    print("   regime            escursione            squilibrio (dev.st)   larghezza")
    for (name, (ios, label, col, mark, window, split)), android in zip(REGIMES.items(), args):
        # The Android capture always aligns on the burst mark: every preset resets the value first
        # and that reset takes a mark of its own.
        amark = "burst" if mark == 1 and "alt" in android else mark
        theirs = rows_of(os.path.join(HERE, ios), col, mark, window, label)
        ours = rows_of(os.path.join(android, "*.json"), col, amark, window)
        if not theirs or not ours:
            print(f"   {name:17} nessun run confrontabile")
            continue
        if not split:
            line(name, [r for v in theirs.values() for r in v], [r for v in ours.values() for r in v])
            continue
        # One line per cadence, and a cadence with no run on one of the two sides is SAID so rather
        # than quietly dropped — the 240 ms alternation went missing for three rounds because the
        # emulator's /data filled, and nothing in the output mentioned it.
        for cadence in sorted(c for c in set(theirs) | set(ours) if c):
            if cadence not in theirs or cadence not in ours:
                side = "android" if cadence in theirs else "iOS"
                print(f"   {name} {cadence:3d}ms   nessun run {side}")
                continue
            line(f"{name} {cadence:3d}ms", theirs[cadence], ours[cadence])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
