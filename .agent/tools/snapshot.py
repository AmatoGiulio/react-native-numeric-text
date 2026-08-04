"""Is the outgoing content a settled digit, or the appearance that was already on screen?

The crowded regime is the one place the two-glyph model fails: through a burst no pair of settled
digits reproduces the column (residual ~0.20 against 0.03-0.09 isolated), no pair is preferred over
any other, and adding a third and fourth glyph stalls at ~0.18. The fit also keeps asking for a
horizontal squeeze that an isolated transition never shows.

All three of those are what you would see if the thing being crossfaded OUT is not a glyph at all
but the composite already on screen — SwiftUI rasterises the text once per value and animates the
raster, so an interruption may well snapshot the current (already blended, scaled, blurred) raster
and crossfade from that. A dictionary of settled digits cannot express such a thing at any scale.

So: take the outgoing template from the capture itself, at the frame the column started reacting to
the commit, and fit

    frame(t) = a_out * T[snapshot](dy, sx, sy, blur) + a_in * T[settled new digit](dy, sx, sy, blur)

against the same fit with a settled OLD digit in place of the snapshot. Same search, same freedom,
same number of parameters — the only difference is where the outgoing template comes from.
"""

import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from fitroll import Glyph, edge_mask, solve_amplitudes  # noqa: E402
from ground_truth import load, ink_box, columns_of  # noqa: E402
from howmany import digit_patches  # noqa: E402

DY = np.arange(-0.80, 0.81, 0.04)
SS = np.array([0.35, 0.45, 0.55, 0.65, 0.75, 0.85, 0.95, 1.00])
BB = np.array([0.0, 0.03, 0.06, 0.09, 0.12])


def lift(patch, shape, glyph_px, cy, cx):
    rows, cols = patch.sum(axis=1), patch.sum(axis=0)
    if rows.max() <= 0:
        return None
    ys = np.nonzero(rows > rows.max() * 0.02)[0]
    xs = np.nonzero(cols > cols.max() * 0.02)[0]
    core = patch[ys[0]:ys[-1] + 1, xs[0]:xs[-1] + 1]
    canvas = np.zeros(shape)
    top, left = int(round(cy - core.shape[0] / 2)), int(round(cx - core.shape[1] / 2))
    y0, x0 = max(0, top), max(0, left)
    y1, x1 = min(shape[0], top + core.shape[0]), min(shape[1], left + core.shape[1])
    canvas[y0:y1, x0:x1] = core[y0 - top:y1 - top, x0 - left:x1 - left]
    g = Glyph(canvas, shape, glyph_px)
    g.cy, g.cx = cy, cx
    return g


def fit_pair(glyphs, target, mask, rounds=2):
    def err(ps):
        models = [mask * g.render(*p[:5]) for g, p in zip(glyphs, ps)]
        amps = solve_amplitudes(models, target, hi=1.05)
        r = target - sum(a * m for a, m in zip(amps, models))
        return float(np.abs(r).sum()), amps

    params = [[0.0, 0.0, 1.0, 1.0, 0.0] for _ in glyphs]
    best, amps = err(params)
    for _ in range(rounds):
        for gi in range(len(glyphs)):
            trial = [list(p) for p in params]
            for s in SS:
                for b in BB:
                    trial[gi][2] = trial[gi][3] = float(s)
                    trial[gi][4] = float(b)
                    for dy in DY:
                        trial[gi][0] = float(dy)
                        e, a = err(trial)
                        if e < best - 1e-9:
                            best, amps, params = e, a, [list(p) for p in trial]
                    trial = [list(p) for p in params]
        for gi in range(len(glyphs)):
            for axis, lo, hi in ((0, -1.3, 1.3), (2, 0.18, 1.15), (3, 0.18, 1.15), (4, 0.0, 0.34)):
                for d in (1, -1):
                    while True:
                        trial = [list(p) for p in params]
                        v = trial[gi][axis] + d * 0.02
                        if v < lo or v > hi:
                            break
                        trial[gi][axis] = v
                        e, a = err(trial)
                        if e < best - 1e-9:
                            best, amps, params = e, a, trial
                        else:
                            break
    return params, amps, best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("prefix", nargs="?", default="artifacts/gt_ios_unit/run-1785827126422")
    ap.add_argument("--col", type=int, default=-1)
    ap.add_argument("--latency", type=float, default=60.0,
                    help="ms from a commit to the column starting to react")
    ap.add_argument("--at", type=float, nargs="+",
                    default=[150, 190, 230, 270, 310, 400])
    args = ap.parse_args()

    meta, frames = load(args.prefix)
    y0, y1, x0, x1 = ink_box(frames)
    w = frames[:, y0:y1, x0:x1].astype(np.float64)
    times = np.array(meta["times"])
    marks = meta["marks"]
    t0 = marks[0]["t"]

    settled = w[-1]
    groups = columns_of(settled)
    c = args.col if args.col >= 0 else len(groups) + args.col
    lo_x = int((groups[c - 1][1] + groups[c][0]) / 2)
    hi_x = w.shape[2] if c == len(groups) - 1 else int((groups[c][1] + groups[c + 1][0]) / 2)
    shape = (w.shape[1], hi_x - lo_x)
    rows = settled[:, groups[c][0]:groups[c][1]].sum(axis=1)
    lit = np.nonzero(rows > rows.max() * 0.05)[0]
    gpx = float(lit[-1] - lit[0])
    mask = edge_mask(meta, y0, shape)
    own = Glyph(settled[:, lo_x:hi_x], shape, gpx)

    bank_raw = digit_patches()
    bank = {d: lift(p, shape, gpx, own.cy, own.cx) for d, p in bank_raw.items()}
    print(f"  bank digits: {''.join(sorted(bank))}   column {c}   glyph {gpx:.0f}px")

    seq = [(m["t"] - t0, m["label"].replace(",", "")[-1]) for m in marks]
    print(f"  commits (ms, units digit): {[(round(t), d) for t, d in seq]}\n")

    print("     t     | settled(old)+settled(new) | snapshot+settled(new) |  snapshot params")
    for want in args.at:
        i = int(np.argmin(np.abs(times - t0 - want)))
        target = w[i, :, lo_x:hi_x]
        # the commit this column is currently reacting to, and the one before it
        active = [k for k, (tm, _) in enumerate(seq) if tm + args.latency <= want]
        if not active:
            continue
        k = active[-1]
        new_d, old_d = seq[k][1], seq[k - 1][1] if k > 0 else seq[0][1]
        onset = seq[k][0] + args.latency
        j = int(np.argmin(np.abs(times - t0 - (onset - 17))))
        snap = lift(w[j, :, lo_x:hi_x], shape, gpx, own.cy, own.cx)
        if bank.get(new_d) is None or bank.get(old_d) is None or snap is None:
            continue

        _, _, e_settled = fit_pair([bank[old_d], bank[new_d]], target, mask)
        ps, amps, e_snap = fit_pair([snap, bank[new_d]], target, mask)
        n = max(1.0, float(np.abs(target).sum()))
        p = ps[0]
        print(f"  {want:6.0f}   | {old_d}->{new_d}   {e_settled/n:.3f}            "
              f"| snap@{times[j]-t0:5.0f}ms {e_snap/n:.3f}   | "
              f"dy{p[0]:+.2f} sx{p[2]:.2f} sy{p[3]:.2f} bl{p[4]:.2f} a{amps[0]:.2f} / a_new {amps[1]:.2f}")


if __name__ == "__main__":
    main()
