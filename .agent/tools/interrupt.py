"""One interruption at a time: does a column hold two glyphs or three?

The 30 ms burst is unreadable — every digit overlaps every other and no fit converges. A 220 ms tap
cadence is the regime that separates the models: a transition lasts ~430 ms, so exactly two are
alive, and the eight digits involved are all different.

At a moment `u` ms into transition k, a **retargeted pair** holds exactly two glyphs — the digit that
was arriving (now leaving) and the one arriving now. A **stack** holds three, because transition
k-1's own departing glyph is still running its curve.

So fit the same frame twice, with {d(k-1), d(k)} and with {d(k-2), d(k-1), d(k)}, and read off both
the residual and what amplitude the third glyph takes. Templates are real settled rasterisations
from `howmany.digit_patches`.
"""

import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from fitroll import Glyph, edge_mask, solve_amplitudes  # noqa: E402
from ground_truth import load, ink_box, columns_of  # noqa: E402
from howmany import digit_patches  # noqa: E402
from snapshot import lift, DY, SS, BB  # noqa: E402


def fit(glyphs, target, mask, rounds=2):
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
            # dx (axis 1) is searched too: templates are centred on ONE digit's ink box, and digits
            # have different ink widths inside the same monospaced advance, so a template for another
            # digit starts off horizontally misplaced. Leaving it fixed put a floor of ~0.09 on the
            # residual of every digit except the one the centre was taken from.
            for axis, lo, hi in ((0, -1.3, 1.3), (1, -0.35, 0.35),
                                 (2, 0.18, 1.15), (3, 0.18, 1.15), (4, 0.0, 0.34)):
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
    ap.add_argument("prefix", nargs="?", default="artifacts/gt_ios_taps/run-1785830945530")
    ap.add_argument("--col", type=int, default=-1)
    ap.add_argument("--cascade", type=float, default=220.0,
                    help="ms from a commit to THIS column's onset (150 wave + ~70 latency)")
    ap.add_argument("--every", type=float, default=55.0)
    ap.add_argument("--start", type=float, default=300.0)
    ap.add_argument("--stop", type=float, default=1500.0)
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

    bank = {d: lift(p, shape, gpx, own.cy, own.cx) for d, p in digit_patches().items()}

    # digit in this column for each committed value, plus the value it started from
    def digit_of(label):
        s = label.replace(",", "")
        idx = len(s) - (len(groups) - c)
        # groups includes the separator; map by counting digits from the right instead
        return s[-(len(groups) - c)] if (len(groups) - c) <= len(s) else s[0]

    seq = [(m["t"] - t0, digit_of(m["label"])) for m in marks]
    print(f"  column {c}  glyph {gpx:.0f}px  cascade {args.cascade:.0f} ms")
    print(f"  commits: {[(round(t), d) for t, d in seq]}\n")
    print("      t   u(ms)  pair        resid  |  triple            resid   a(k-2)  |  verdict")

    for want in np.arange(args.start, args.stop, args.every):
        active = [k for k, (tm, _) in enumerate(seq) if tm + args.cascade <= want]
        if len(active) < 3:
            continue
        k = active[-1]
        u = want - (seq[k][0] + args.cascade)
        d0, d1, d2 = seq[k - 2][1], seq[k - 1][1], seq[k][1]
        if any(bank.get(d) is None for d in (d0, d1, d2)):
            continue
        i = int(np.argmin(np.abs(times - t0 - want)))
        target = w[i, :, lo_x:hi_x]
        n = max(1.0, float(np.abs(target).sum()))
        _, a2, e2 = fit([bank[d1], bank[d2]], target, mask)
        _, a3, e3 = fit([bank[d0], bank[d1], bank[d2]], target, mask)
        verdict = "third glyph MATTERS" if (e2 - e3) / n > 0.02 and a3[0] > 0.12 else "pair is enough"
        print(f"  {want:5.0f}  {u:5.0f}   {d1}+{d2}  a {a2[0]:.2f}/{a2[1]:.2f}  {e2/n:.3f}  |  "
              f"{d0}+{d1}+{d2}  a {a3[0]:.2f}/{a3[1]:.2f}/{a3[2]:.2f}  {e3/n:.3f}   {a3[0]:.2f}  |  {verdict}")


if __name__ == "__main__":
    main()
