#!/usr/bin/env python3
"""The middle band of an alternation: how full the space between the two digits is.

    python3 .agent/tools/band.py artifacts/<run-dir> [--col=-1] [--ios]

Under a fast alternation the reference holds its two glyphs apart and we merge them into one mass.
The band is the measurement that separates the two: average every frame's normalised vertical ink
profile over the alternating stretch, then take the middle third of that mean profile over its
outer thirds. Two separated forms read BELOW 1 — the space between them is emptier than the glyphs.
One merged mass reads well above it.

The window is mark 1 to the last mark: mark 0 is the preset's own reset to the starting value, and
aligning on it compares one platform's roll against the other's idle second. The thirds are taken
over the profile's 2nd-98th percentile span, not over the recorder's ink box, which is as tall as
the whole run's travel and would put both glyphs inside the middle third at every cadence.

Reconstructed on 2026-08-03 — the original script was not committed. It reproduces the recorded
reference reading at the cadence that matters (0.760 here against 0.756 recorded at 60 ms) and runs
0.07 low at 240 ms (1.292 against 1.362), so compare Android against the iOS numbers THIS script
prints, never against the ones in the ground truth.
"""

import glob
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ground_truth import load, ink_box, columns_of  # noqa: E402

HERE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def band(prefix, col=-1):
    """Return (band, cadence_ms) for one capture, or None if it holds no alternation."""
    meta, frames = load(prefix)
    marks = meta.get("marks", [])
    if len(marks) < 4:
        return None
    y0, y1, x0, x1 = ink_box(frames)
    window = frames[:, y0:y1, x0:x1].astype(np.float64)
    groups = columns_of(window[-1])
    if not groups:
        return None
    a, b = groups[col]

    times = np.array(meta["times"])
    selected = (times >= marks[1]["t"]) & (times <= marks[-1]["t"])
    profiles = []
    for index in np.nonzero(selected)[0]:
        rows = window[index, :, a:b].sum(axis=1)
        total = rows.sum()
        if total > 0:
            profiles.append(rows / total)
    if len(profiles) < 8:
        return None

    mean = np.mean(profiles, axis=0)
    cumulative = np.cumsum(mean) / mean.sum()
    span = mean[np.searchsorted(cumulative, 0.02): np.searchsorted(cumulative, 0.98) + 1]
    n = len(span)
    third = n // 3
    if third < 1:
        return None
    middle = span[third: n - third].mean()
    outer = np.concatenate([span[:third], span[n - third:]]).mean()
    cadence = (marks[-1]["t"] - marks[1]["t"]) / (len(marks) - 2)
    return float(middle / outer), float(cadence)


def report(pattern, label):
    rows = []
    for path in sorted(glob.glob(pattern)):
        if not path.endswith(".json") or path.endswith("reference.json"):
            continue
        got = band(path[:-5])
        if got:
            rows.append(got)
    if not rows:
        return None
    by_cadence = {}
    for value, cadence in rows:
        by_cadence.setdefault(round(cadence / 30) * 30, []).append(value)
    for cadence in sorted(by_cadence):
        values = by_cadence[cadence]
        # The spread, not just the median. At 60 ms two runs of ONE build read 0.926 and 0.754,
        # and rounds were being decided on differences of 0.03 — a median printed alone is what
        # made that look like a result. 120 and 240 ms repeat to ~0.005 and are safe to fit on;
        # 60 ms needs five runs before a difference under 0.05 means anything.
        spread = float(np.ptp(values)) if len(values) > 1 else float("nan")
        flag = "  ⚠ sotto il rumore" if len(values) > 1 and spread > 0.03 else ""
        print(
            f"   {label:9} ~{cadence:3.0f} ms   banda {np.median(values):.3f}   "
            f"±{spread:.3f}   ({len(values)} run){flag}"
        )
    return by_cadence


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        print(__doc__)
        return 1
    report(os.path.join(HERE, "artifacts/gt_ios_alt3/*.json"), "iOS")
    report(os.path.join(args[0], "*.json"), "android")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
