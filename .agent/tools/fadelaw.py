"""What happens to the glyph a change discards.

At a change the pair is re-formed: the incoming glyph becomes the outgoing one and the new digit
enters. The glyph that was ALREADY outgoing is dropped from the pair — but it does not vanish, and
at a 30 ms cadence it is still carrying amplitude 0.4-0.6 when the change after that lands. This
measures what it actually does.

The capture is an isolated TRIPLE: a settled value, one change, a controlled gap, a second change,
then silence. Three distinct digits in the column, exactly one interruption, so the fit is well
conditioned — unlike a burst, where everything overlaps everything.

Two hypotheses, and they differ by a lot:

  continue  — the discarded glyph is not cancelled at all; it keeps running its own transition's
              alpha curve, 1 - p(t - t_A), and simply stops being anyone's partner. This is a STACK
              in which old layers are merely faint.
  cancel    — being dropped starts a separate, faster fade from wherever it was.
"""

import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from fitroll import Glyph, edge_mask  # noqa: E402
from ground_truth import load, ink_box, columns_of  # noqa: E402
from howmany import digit_patches  # noqa: E402
from snapshot import lift  # noqa: E402
from interrupt import fit  # noqa: E402

# alpha of an arriving glyph, critically damped, wn = 22.8 rad/s (TRANSITION_MODEL section 3)
WN_ALPHA = 22.8


def p_alpha(t_ms):
    t = np.maximum(np.asarray(t_ms, dtype=float) / 1000.0, 0.0)
    return 1 - np.exp(-WN_ALPHA * t) * (1 + WN_ALPHA * t)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("prefix")
    ap.add_argument("--digits", required=True, help="A,B,C — the column's digits in order")
    ap.add_argument("--col", type=int, default=-1)
    ap.add_argument("--cascade", type=float, default=220.0,
                    help="ms from a commit to THIS column's onset")
    ap.add_argument("--to", type=float, default=700.0)
    ap.add_argument("--every", type=int, default=2)
    ap.add_argument("--margin", type=float, default=28.0)
    args = ap.parse_args()

    A, B, C = [d.strip() for d in args.digits.split(",")]
    meta, frames = load(args.prefix)
    y0, y1, x0, x1 = ink_box(frames)
    w = frames[:, y0:y1, x0:x1].astype(np.float64)
    times = np.array(meta["times"])
    marks = meta["marks"]
    t0 = marks[0]["t"]
    delta = marks[1]["t"] - t0

    settled = w[-1]
    groups = columns_of(settled)
    c = args.col if args.col >= 0 else len(groups) + args.col
    lo_x = int((groups[c - 1][1] + groups[c][0]) / 2)
    hi_x = w.shape[2] if c == len(groups) - 1 else int((groups[c][1] + groups[c + 1][0]) / 2)
    shape = (w.shape[1], hi_x - lo_x)
    rows = settled[:, groups[c][0]:groups[c][1]].sum(axis=1)
    lit = np.nonzero(rows > rows.max() * 0.05)[0]
    gpx = float(lit[-1] - lit[0])
    cy = (lit[0] + lit[-1]) / 2.0
    cx = (groups[c][0] + groups[c][1]) / 2.0 - lo_x
    mask = edge_mask(meta, y0, shape)
    # Cut the comparison off to the left of this column's own settled ink box. Both neighbours are
    # animating in a triple, so a window drawn from one settled frame lets a wider neighbouring digit
    # spill in — the artefact that produced two retracted findings in TRANSITION_MODEL section 5.
    # Everything left of the cut is excluded from target AND model alike, so it cannot bias the fit.
    cut = int(groups[c][0] - lo_x - args.margin)
    if cut > 0:
        mask[:, :cut] = 0.0
    print(f"  window x[{lo_x},{hi_x}]  comparison starts {cut} px in "
          f"(column ink box at {groups[c][0]-lo_x}, margin {args.margin:.0f})")
    bank = {d: lift(p, shape, gpx, cy, cx) for d, p in digit_patches().items()}

    # validation: the settled first and last frames must rebuild from one template each
    for tag, frame_idx, digit in (("first (settled A)", 0, A), ("last (settled C)", -1, C)):
        tgt = w[frame_idx, :, lo_x:hi_x] * (mask > 0)
        ps, amps, e = fit([bank[digit]], tgt, mask)
        print(f"  validation {tag}: '{digit}' amplitude {amps[0]:.3f} dx {ps[0][1]:+.2f} residual "
              f"{e / max(1.0, float(np.abs(tgt).sum())):.3f}")

    onA = args.cascade
    onB = delta + args.cascade
    print(f"\n  gap between commits {delta:.0f} ms   column {c}   digits {A}->{B}->{C}")
    print(f"  this column's onsets: A->B at {onA:.0f} ms, B->C at {onB:.0f} ms")
    print(f"\n   t    since-drop |  a({A}) discarded  a({B})   a({C})  | pred if UNCANCELLED | resid")

    for i in range(len(times)):
        rel = times[i] - t0
        if rel < onB - 20 or rel > args.to or i % args.every:
            continue
        tgt = w[i, :, lo_x:hi_x] * (mask > 0)
        n = max(1.0, float(np.abs(tgt).sum()))
        _, amps, e = fit([bank[A], bank[B], bank[C]], tgt, mask)
        uncancelled = 1.0 - p_alpha(rel - onA)
        print(f"  {rel:5.0f}   {rel-onB:7.0f}   |     {amps[0]:.3f}         {amps[1]:.3f}   "
              f"{amps[2]:.3f}  |       {uncancelled:.3f}         | {e/n:.3f}")


if __name__ == "__main__":
    main()
