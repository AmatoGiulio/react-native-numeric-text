"""Does a two-stop change pass THROUGH the digit in between?

Column 4 of 1,242 -> 1,160 goes 2 -> 0. Two models that agree on every one-stop change disagree here:

  strip/drum  — the column is a position on a ribbon of stops, so moving two stops must sweep the
                "1" across the window, and the arriving "0" must start two stops away (~1.19 glyph
                heights) rather than one.
  crossfade   — the column holds exactly one departing and one arriving glyph whatever the digit
                distance, and the "1" never exists.

So: fit the frames with THREE templates, the extra one being a settled "1" lifted from a column that
holds one all run long, and see whether it takes any amplitude.

The lift is validated first by reconstructing a column that really does hold a settled "1": if the
lifted template cannot do that, nothing it says about column 4 means anything.
"""

import os
import sys

import numpy as np

S = os.environ.get("FITS", ".agent/fits")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from fitroll import Glyph, edge_mask, solve_amplitudes, COARSE_DY, COARSE_S, COARSE_B  # noqa: E402
from ground_truth import load, ink_box, columns_of  # noqa: E402


def lift(source_patch, shape, glyph_px, cy, cx):
    """Move a settled glyph into another column's window, centred on that column's rest point."""
    rows = source_patch.sum(axis=1)
    cols = source_patch.sum(axis=0)
    ys = np.nonzero(rows > rows.max() * 0.02)[0]
    xs = np.nonzero(cols > cols.max() * 0.02)[0]
    core = source_patch[ys[0]:ys[-1] + 1, xs[0]:xs[-1] + 1]
    canvas = np.zeros(shape)
    top = int(round(cy - core.shape[0] / 2))
    left = int(round(cx - core.shape[1] / 2))
    y0, x0 = max(0, top), max(0, left)
    y1 = min(shape[0], top + core.shape[0])
    x1 = min(shape[1], left + core.shape[1])
    canvas[y0:y1, x0:x1] = core[y0 - top:y1 - top, x0 - left:x1 - left]
    g = Glyph(canvas, shape, glyph_px)
    g.cy, g.cx = cy, cx
    return g


def fit(glyphs, target, mask, params, sweeps=2):
    def err(ps):
        models = [mask * g.render(*p[:5]) for g, p in zip(glyphs, ps)]
        amps = solve_amplitudes(models, target, hi=1.05)
        r = target - sum(a * m for a, m in zip(amps, models))
        return float(np.abs(r).sum()), amps

    best, amps = err(params)
    for _ in range(sweeps):
        for gi in range(len(glyphs)):
            trial = [list(p) for p in params]
            for s in COARSE_S:
                for b in COARSE_B:
                    trial[gi][2] = trial[gi][3] = float(s)
                    trial[gi][4] = float(b)
                    for dy in COARSE_DY:
                        trial[gi][0] = float(dy)
                        e, a = err(trial)
                        if e < best - 1e-9:
                            best, amps, params = e, a, [list(p) for p in trial]
                    trial = [list(p) for p in params]
        for gi in range(len(glyphs)):
            for axis, lo, hi in ((0, -1.3, 1.3), (2, 0.18, 1.12), (3, 0.18, 1.12), (4, 0.0, 0.34)):
                for d in (1, -1):
                    while True:
                        trial = [list(p) for p in params]
                        v = trial[gi][axis] + d * 0.012
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
    meta, frames = load("artifacts/gt_ios_ref/run-1785683986267")
    y0, y1, x0, x1 = ink_box(frames)
    w = frames[:, y0:y1, x0:x1].astype(np.float64)
    times = np.array(meta["times"]) - meta["marks"][1]["t"]
    old_idx = int(np.nonzero(times <= -60)[0][-1])
    so, sn = w[old_idx], w[-1]
    go, gn = columns_of(so), columns_of(sn)

    def window(c):
        lo = int((max(go[c - 1][1], gn[c - 1][1]) + min(go[c][0], gn[c][0])) / 2)
        hi = (w.shape[2] if c == len(gn) - 1
              else int((max(go[c][1], gn[c][1]) + min(go[c + 1][0], gn[c + 1][0])) / 2))
        return lo, hi

    # --- validation: rebuild column 2's settled "1" from column 0's settled "1"
    lo2, hi2 = window(2)
    shape2 = (w.shape[1], hi2 - lo2)
    own2 = Glyph(sn[:, lo2:hi2], shape2, 182.0)
    src0 = sn[:, gn[0][0]:gn[0][1]]
    lifted = lift(src0, shape2, 182.0, own2.cy, own2.cx)
    mask2 = edge_mask(meta, y0, shape2)
    tgt = sn[:, lo2:hi2]
    for name, g in (("own settled template", own2), ("lifted from column 0", lifted)):
        m = mask2 * g.render(0, 0, 1, 1, 0)
        a = float((m * tgt).sum()) / max(1e-9, float((m * m).sum()))
        rel = float(np.abs(tgt - a * m).sum()) / float(np.abs(tgt).sum())
        print(f"  validation — {name}: amplitude {a:.3f}, residual {rel:.3f}")

    # --- the test: column 4, which goes 2 -> 0
    c = 4
    lo, hi = window(c)
    shape = (w.shape[1], hi - lo)
    rows = sn[:, gn[c][0]:gn[c][1]].sum(axis=1)
    lit = np.nonzero(rows > rows.max() * 0.05)[0]
    gpx = float(lit[-1] - lit[0])
    two = Glyph(so[:, lo:hi], shape, gpx)      # settled "2"
    zero = Glyph(sn[:, lo:hi], shape, gpx)     # settled "0"
    one = lift(sn[:, gn[2][0]:gn[2][1]], shape, gpx, zero.cy, zero.cx)   # settled "1"
    mask = edge_mask(meta, y0, shape)

    print(f"\n  column 4: 2 -> 0 (two stops).  glyph {gpx:.0f}px")
    print("     t     2-glyph resid | 3-glyph resid |  '2' dy/a      '0' dy/a      '1' dy/a")
    onset = 220.0
    for want in np.arange(onset - 20, onset + 220, 16.7):
        i = int(np.argmin(np.abs(times - want)))
        target = w[i, :, lo:hi]
        p2, a2, e2 = fit([two, zero], target, mask,
                         [[0, 0, 1, 1, 0], [0.59, 0, 0.4, 0.4, 0.1]])
        p3, a3, e3 = fit([two, zero, one], target, mask,
                         [list(p2[0]), list(p2[1]), [0.3, 0, 0.5, 0.5, 0.1]])
        n = float(np.abs(target).sum())
        print(f"  {times[i]-onset:6.0f}     {e2/n:.3f}       |     {e3/n:.3f}     | "
              f"{p3[0][0]:+.2f}/{a3[0]:.2f}  {p3[1][0]:+.2f}/{a3[1]:.2f}  {p3[2][0]:+.2f}/{a3[2]:.2f}")


if __name__ == "__main__":
    main()
