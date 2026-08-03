#!/usr/bin/env python3
"""The continuous roll: how sharp it stays and how long it takes to stop.

    python3 .agent/tools/burst.py artifacts/<android-run-dir>

A burst is the case a single crossing cannot stand in for — fourteen changes 30 ms apart, so the
column never resolves and every constant that depends on how far apart two glyphs are is being asked
a different question. Two numbers, both against the same reference runs in `artifacts/gt_ios_bursts`:

  sharpness   the median edge energy through the roll, per unit ink, against a settled glyph. It
              measures how blurred the moving digits are. The reference is SHARPER here than at the
              floor of a single crossing, 0.600 against ~0.45, because each crossing in a burst is
              short — which is the measurement that killed a velocity-proportional shutter blur.
  tail        milliseconds from the LAST change to the column reading full again, taken the way
              compare.py takes it: find the ink floor after the last change first, then the return.
              Measured from the last mark alone it catches a glyph crossing 0.985 on its way past
              and reads tens of milliseconds.

Only the runs whose label matches are compared, because the burst set holds three different starting
values and their columns are not the same quantity.
"""

import glob
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ground_truth import load, ink_box, columns_of, edge_energy  # noqa: E402
from grid import burst_mark  # noqa: E402

HERE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
IOS = os.path.join(HERE, "artifacts/gt_ios_bursts/*.json")
LABEL = "1,000"


def measure(prefix, col=-1):
    meta, frames = load(prefix)
    y0, y1, x0, x1 = ink_box(frames)
    w = frames[:, y0:y1, x0:x1].astype(np.float64)
    groups = columns_of(w[-1])
    if not groups:
        return None
    a, b = groups[col]
    settled = w[-1][:, a:b]
    base_ink, base_edge = settled.sum(), edge_energy(settled)
    if base_ink <= 0 or base_edge <= 0:
        return None

    marks = meta["marks"]
    times = np.array(meta["times"])
    ink = w[:, :, a:b].sum(axis=(1, 2)) / base_ink

    rolling = (times >= marks[burst_mark(meta)]["t"]) & (times <= marks[-1]["t"])
    sharpness = np.median([edge_energy(w[i, :, a:b]) / base_edge for i in np.nonzero(rolling)[0]])

    after = np.nonzero(times >= marks[-1]["t"])[0]
    floor = after[np.argmin(ink[after])]
    back = np.nonzero((times > times[floor]) & (ink > 0.985))[0]
    tail = times[back[0]] - marks[-1]["t"] if len(back) else float("nan")
    return float(sharpness), float(tail), float(ink[floor])


def report(pattern, label):
    rows = []
    for path in sorted(glob.glob(pattern)):
        if path.endswith("reference.json"):
            continue
        if json.load(open(path)).get("label") != LABEL:
            continue
        got = measure(path[:-5])
        if got:
            rows.append(got)
    if not rows:
        print(f"   {label:9} nessun run con etichetta {LABEL}")
        return None
    out = tuple(float(np.median([r[i] for r in rows])) for i in range(3))
    print(
        f"   {label:9} nitidezza {out[0]:.3f}   coda {out[1]:5.0f} ms   "
        f"fondo finale {out[2]:.3f}   ({len(rows)} run)"
    )
    return out


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        print(__doc__)
        return 1
    report(IOS, "iOS")
    report(os.path.join(args[0], "*.json"), "android")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
