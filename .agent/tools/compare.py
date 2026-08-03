#!/usr/bin/env python3
"""Compare an Android recorder run against the iOS reference, column by column.

The reference is `artifacts/gt_ios_ref/reference.json`, extracted from a real iOS run of the
DECREMENT 1,242 -> 1,160. Drive Android through the same decrement, or the numbers are not
comparable: every metric is normalised against the column's own settled glyph, and a crossing of
2 -> 1 measured against a settled "1" is not the same quantity as 1 -> 2 measured against a "2".

    python3 .agent/tools/compare.py artifacts/<run-dir>

Prints per-column deltas and one headline number: the mean absolute error on the metrics that the
engine's constants actually control.
"""

import glob
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ground_truth import load, ink_box, columns_of, edge_energy, profile_stats  # noqa: E402

# Which mark in the run is the transition under test. Android captures are driven by a preset that
# fires several changes; the second mark is the one that matches the reference.
MARK = 1

# The columns that change in 1,242 -> 1,160. The other two must stay at 1.000, and they are the
# control: a constant that moves them is wrong however good it makes the rest look.
CHANGING = (2, 3, 4)


def measure(prefix, mark=MARK):
    meta, frames = load(prefix)
    if len(meta["marks"]) <= mark:
        return None
    y0, y1, x0, x1 = ink_box(frames)
    w = frames[:, y0:y1, x0:x1].astype(np.float64)
    rel = np.array(meta["times"]) - meta["marks"][mark]["t"]
    settled = w[-1]
    groups = columns_of(settled)
    if len(groups) < 5:
        return None

    out = {}
    for c, (a, b) in enumerate(groups):
        ref = settled[:, a:b]
        rows = ref.sum(axis=1)
        lit = np.nonzero(rows > rows.max() * 0.05)[0]
        if len(lit) < 2:
            continue
        glyph_height = float(lit[-1] - lit[0])
        base_ink = float(ref.sum())
        base_edge = edge_energy(ref)
        if base_ink <= 0 or base_edge <= 0:
            continue

        ink = w[:, :, a:b].sum(axis=(1, 2)) / base_ink
        window = (rel >= -30) & (rel <= 1000)
        x, k = rel[window], ink[window]
        j = int(np.argmin(k))
        plane = w[window][j, :, a:b]

        back = np.where((x > x[j]) & (k > 0.985))[0]
        zero = int(np.argmin(np.abs(x)))
        moved = np.where((x > 0) & (np.abs(k - k[zero]) > 0.02))[0]

        out[c] = dict(
            v=float(k[j]),
            t=float(x[j]),
            e=float(edge_energy(plane) / base_edge),
            x=float(profile_stats(plane, glyph_height)[1]),
            b=float(x[back[0]]) if len(back) else float("nan"),
            s=float(x[moved[0]]) if len(moved) else float("nan"),
        )
    return out


def median_of(pattern):
    runs = [measure(f[:-5]) for f in sorted(glob.glob(pattern))]
    runs = [r for r in runs if r]
    if not runs:
        return None
    keys = set.intersection(*(set(r) for r in runs))
    return {
        c: {m: float(np.nanmedian([r[c][m] for r in runs])) for m in runs[0][c]} for c in keys
    }


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        print(__doc__)
        return 1
    here = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    # --up compares against the increment 1,160 -> 1,242; the default is the decrement. They are
    # not interchangeable: every metric is normalised against the column's own settled glyph, so a
    # crossing of 2 -> 1 measured against a settled "1" is a different quantity from 1 -> 2.
    which = "gt_ios_up5" if "--up" in sys.argv else "gt_ios_ref"
    reference = json.load(open(os.path.join(here, f"artifacts/{which}/reference.json")))
    reference = {int(k): v for k, v in reference.items()}

    got = median_of(os.path.join(args[0], "*.json"))
    if not got:
        print("no usable runs in", sys.argv[1])
        return 1

    print("  col        fondo    t fondo   estensione    pieno    parte a")
    errors = []
    for c in CHANGING:
        if c not in got or c not in reference:
            continue
        a, b = got[c], reference[c]
        print(
            f"   {c} android  {a['v']:.3f}    {a['t']:6.1f}     {a['x']:.3f}    {a['b']:6.1f}   {a['s']:6.1f}"
        )
        print(
            f"   {c} iOS      {b['v']:.3f}    {b['t']:6.1f}     {b['x']:.3f}    {b['b']:6.1f}   {b['s']:6.1f}"
        )
        print()
        errors.append(
            (abs(a["v"] - b["v"]), abs(a["x"] - b["x"]), abs(a["t"] - b["t"]), abs(a["s"] - b["s"]))
        )

    # The control: columns that do not change must read 1.000 on both sides.
    for c in (0, 1):
        if c in got:
            flag = "" if abs(got[c]["v"] - 1.0) < 0.01 else "   <- SI MUOVE, non dovrebbe"
            print(f"   {c} ferma    {got[c]['v']:.3f}{flag}")

    e = np.array(errors)
    print()
    print(
        "scarto medio   fondo %.3f   estensione %.3f   t fondo %.0f ms   partenza %.0f ms"
        % (e[:, 0].mean(), e[:, 1].mean(), e[:, 2].mean(), e[:, 3].mean())
    )
    print("headline (fondo + estensione)/2 = %.3f" % ((e[:, 0].mean() + e[:, 1].mean()) / 2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
