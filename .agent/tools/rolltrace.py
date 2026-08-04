"""Per-glyph trajectory through a continuous roll, for one platform, frame by frame.

`howmany.py` answers "how many glyphs are alive" at a handful of instants. This answers what those
glyphs DO across the whole burst: for every frame it fits the column with two glyphs drawn from the
digits that have actually been committed recently, and records each one's offset, scale and opacity.

Restricting the dictionary to the last few committed digits is what makes this affordable — the full
ten-digit sweep costs minutes a frame, and a digit that has not been committed cannot be on screen.

Output is a JSON of per-frame rows, so the two platforms can be plotted against each other.
"""

import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from fitroll import Glyph, edge_mask, solve_amplitudes  # noqa: E402
from ground_truth import load, ink_box, columns_of  # noqa: E402
from howmany import digit_patches, ANDROID_BANK_SOURCES, place_template  # noqa: E402

DY = np.arange(-0.80, 0.81, 0.08)
SS = np.array([0.35, 0.45, 0.55, 0.65, 0.75, 0.85, 0.95, 1.00])
BB = np.array([0.0, 0.06, 0.12])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("prefix")
    ap.add_argument("--col", type=int, default=4)
    ap.add_argument("--mark", type=int, default=1)
    ap.add_argument("--bank", choices=("ios", "android"), default="ios")
    ap.add_argument("--from", dest="lo", type=float, default=120)
    ap.add_argument("--to", dest="hi", type=float, default=400)
    ap.add_argument("--every", type=int, default=2)
    ap.add_argument("--recent", type=int, default=4, help="how many recent digits may appear")
    ap.add_argument("--cascade", type=float, default=220.0,
                    help="ms from a commit to THIS column reacting; the units column is the wave's "
                         "last and lags by the full 0.15 s spread plus latency, so without it the "
                         "dictionary offered to the fit is the wrong set of digits entirely")
    ap.add_argument("--json", required=True)
    args = ap.parse_args()

    meta, frames = load(args.prefix)
    y0, y1, x0, x1 = ink_box(frames)
    w = frames[:, y0:y1, x0:x1].astype(np.float64)
    times = np.array(meta["times"]) - meta["marks"][args.mark]["t"]
    settled = w[-1]
    groups = columns_of(settled)
    c = args.col
    lo_x = int((groups[c - 1][1] + groups[c][0]) / 2)
    hi_x = w.shape[2] if c == len(groups) - 1 else int((groups[c][1] + groups[c + 1][0]) / 2)
    shape = (w.shape[1], hi_x - lo_x)
    rows = settled[:, groups[c][0]:groups[c][1]].sum(axis=1)
    lit = np.nonzero(rows > rows.max() * 0.05)[0]
    glyph_px = float(lit[-1] - lit[0])
    own = Glyph(settled[:, lo_x:hi_x], shape, glyph_px)
    mask = edge_mask(meta, y0, shape)

    patches = digit_patches(ANDROID_BANK_SOURCES if args.bank == "android" else None)
    bank = {d: place_template(p, shape, glyph_px, own.cy, own.cx) for d, p in patches.items()}

    nd = len(groups) - c
    seq = [(m["t"] - meta["marks"][args.mark]["t"], m["label"].replace(",", "")[-nd])
           for m in meta["marks"]]

    out = []
    for i in range(len(times)):
        t = times[i]
        if t < args.lo or t > args.hi or i % args.every:
            continue
        recent = [d for tm, d in seq if tm <= t - args.cascade][-args.recent:]
        recent = [d for d in dict.fromkeys(reversed(recent)) if d in bank]
        if len(recent) < 2:
            continue
        target = w[i, :, lo_x:hi_x]
        norm = max(1.0, float(np.abs(target).sum()))

        chosen = []
        for _ in range(2):
            best = None
            for d in recent:
                g = bank[d]
                for s in SS:
                    for b in BB:
                        tile = g.tile(s, s, b)
                        for dy in DY:
                            m = mask * g.render(dy, 0.0, s, s, b)
                            models = [x[1] for x in chosen] + [m]
                            amps = solve_amplitudes(models, target, hi=1.05)
                            r = target - sum(a * mm for a, mm in zip(amps, models))
                            e = float(np.abs(r).sum())
                            if best is None or e < best[0]:
                                best = (e, d, float(dy), float(s), float(b), m, amps)
            chosen.append((best[1:5], best[5]))
            last = best
        amps = last[6]
        order = np.argsort(-np.asarray(amps))
        row = {"t": float(t), "resid": last[0] / norm, "glyphs": []}
        for k in order:
            (d, dy, s, b), _ = chosen[k]
            row["glyphs"].append({"d": d, "dy": dy, "s": s, "blur": b, "a": float(amps[k])})
        out.append(row)
        print(f"  t={t:6.0f}  " + "   ".join(
            f"{g['d']}: dy{g['dy']:+.2f} s{g['s']:.2f} a{g['a']:.2f}" for g in row["glyphs"])
            + f"   resid {row['resid']:.3f}", flush=True)

    with open(args.json, "w") as h:
        json.dump({"prefix": args.prefix, "bank": args.bank, "rows": out}, h, indent=1)
    print(f"\nwrote {args.json}  ({len(out)} frames)")


if __name__ == "__main__":
    main()
