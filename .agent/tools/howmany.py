"""How many glyphs are alive in one column at once, counted rather than argued.

Matching pursuit over a bank of all ten settled digits, each free in (offset, scale, blur), added
one at a time. After each addition the amplitudes are re-solved jointly and the residual printed.
The count that matters is where the residual stops falling: if two glyphs reach the floor and the
third buys nothing, the column holds a pair.

The bank is built from settled frames of seven captures — every digit is a real rasterisation by the
same engine at the same size, never a font-rendered approximation — and each template is validated
by reconstructing a settled instance of its own digit before it is used.
"""

import argparse
import glob
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


from fitroll import Glyph, edge_mask, solve_amplitudes  # noqa: E402
from ground_truth import load, ink_box, columns_of  # noqa: E402

DY = np.arange(-0.80, 0.81, 0.04)
SS = np.array([0.35, 0.45, 0.55, 0.65, 0.75, 0.85, 0.95, 1.00])
BB = np.array([0.0, 0.03, 0.06, 0.09, 0.12])

# Android's glyphs are a different typeface from iOS's, so counting on an Android capture needs an
# Android bank. Same rule as the iOS one: every template is a real settled rasterisation by the same
# engine at the same size, cut from a run that ends on a known number.
ANDROID_BANK_SOURCES = [
    ("artifacts/and_bank/run-1785845775987", "877"),
    ("artifacts/and_bank/run-1785845781951", "999"),
    ("artifacts/and_bank/run-1785845789202", "10,123"),
    ("artifacts/and_stack2/run-1785836975552", "1,160"),
    ("artifacts/stackfit2/run-1785838072585", "1,242"),
    ("artifacts/roll_best/run-1785844839319", "2,722"),
]

BANK_SOURCES = [
    ("artifacts/gt_ios_bank/run-1785826461160", "1,123"),
    ("artifacts/gt_ios_bank/run-1785826465217", "1,246"),
    ("artifacts/gt_ios_bank/run-1785826469265", "1,369"),
    ("artifacts/gt_ios_bank/run-1785826473321", "1,492"),
    ("artifacts/gt_ios_bank/run-1785826477377", "1,615"),
    ("artifacts/gt_ios_bank/run-1785826481421", "1,738"),
    ("artifacts/gt_ios_struct/run-1785792563614", "999"),
    ("artifacts/gt_ios_bursts/run-1785744968851", "1,000"),
]


def digit_patches(sources=None):
    """{digit: raster} taken from the settled last frame of each source capture."""
    out = {}
    for prefix, label in (sources or BANK_SOURCES):
        meta, frames = load(prefix)
        y0, y1, x0, x1 = ink_box(frames)
        settled = frames[-1, y0:y1, x0:x1].astype(np.float64)
        groups = columns_of(settled)
        chars = [c for c in label]
        if len(groups) != len(chars):
            print(f"  ! {label}: {len(groups)} columns for {len(chars)} characters, skipped")
            continue
        for (a, b), ch in zip(groups, chars):
            if ch.isdigit() and ch not in out:
                out[ch] = settled[:, a:b]
    return out


def place_template(core_patch, shape, glyph_px, cy, cx):
    rows = core_patch.sum(axis=1)
    cols = core_patch.sum(axis=0)
    ys = np.nonzero(rows > rows.max() * 0.02)[0]
    xs = np.nonzero(cols > cols.max() * 0.02)[0]
    core = core_patch[ys[0]:ys[-1] + 1, xs[0]:xs[-1] + 1]
    canvas = np.zeros(shape)
    top, left = int(round(cy - core.shape[0] / 2)), int(round(cx - core.shape[1] / 2))
    y0, x0 = max(0, top), max(0, left)
    y1, x1 = min(shape[0], top + core.shape[0]), min(shape[1], left + core.shape[1])
    canvas[y0:y1, x0:x1] = core[y0 - top:y1 - top, x0 - left:x1 - left]
    g = Glyph(canvas, shape, glyph_px)
    g.cy, g.cx = cy, cx
    return g


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("prefix")
    ap.add_argument("--col", type=int, default=4)
    ap.add_argument("--at", type=float, nargs="+", required=True, help="times in ms from mark 1")
    ap.add_argument("--max", type=int, default=4, help="most glyphs to add")
    ap.add_argument("--mark", type=int, default=1)
    ap.add_argument("--bank", choices=("ios", "android"), default="ios")
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
    print(f"  bank: digits {''.join(sorted(patches))}")
    bank = {d: place_template(p, shape, glyph_px, own.cy, own.cx) for d, p in patches.items()}

    # validation — each template must rebuild a settled instance of its own digit
    tgt = settled[:, lo_x:hi_x]
    final_digit = meta["marks"][-1]["label"].replace(",", "")[-(len(groups) - c)]
    m = mask * bank[final_digit].render(0, 0, 1, 1, 0)
    a = float((m * tgt).sum()) / float((m * m).sum())
    print(f"  validation: lifted '{final_digit}' rebuilds this column's settled glyph — "
          f"amplitude {a:.3f}, residual {float(np.abs(tgt - a*m).sum())/float(np.abs(tgt).sum()):.3f}")

    # pre-render the whole candidate dictionary once
    cand = []
    for d, g in sorted(bank.items()):
        for s in SS:
            for b in BB:
                for dy in DY:
                    cand.append((d, dy, s, b))
    print(f"  {len(cand)} candidates ({len(bank)} digits x {len(SS)} scales x {len(BB)} blurs "
          f"x {len(DY)} offsets)\n")

    for want in args.at:
        i = int(np.argmin(np.abs(times - want)))
        target = w[i, :, lo_x:hi_x]
        norm = float(np.abs(target).sum())
        chosen, params = [], []
        resid = target.copy()
        line = []

        def build(ps):
            return [mask * bank[d].render(p[0], p[1], p[2], p[2], p[3])
                    for d, p in zip(chosen, ps)]

        def score(ps):
            models = build(ps)
            amps = solve_amplitudes(models, target, hi=1.05)
            r = target - sum(a * m for a, m in zip(amps, models))
            return float(np.abs(r).sum()), amps, r

        for step in range(args.max):
            best = None
            for (d, dy, s, b) in cand:
                m = mask * bank[d].render(dy, 0, s, s, b)
                den = float((m * m).sum())
                if den <= 1e-6:
                    continue
                num = float((m * resid).sum())
                if num <= 0:
                    continue
                gain = num * num / den
                if best is None or gain > best[0]:
                    best = (gain, d, dy, s, b)
            if best is None:
                break
            chosen.append(best[1])
            params.append([best[2], 0.0, best[3], best[4]])

            # Polish every chosen glyph — the scan grid is 7 px in dy and has no dx at all, which on
            # a sharp glyph is worth more residual than a whole extra glyph. Without this the count
            # measures the grid, not the animation.
            cur, amps, resid = score(params)
            for scale_step in (0.03, 0.012, 0.006):
                for _ in range(3):
                    moved = False
                    for gi in range(len(params)):
                        for axis, lo, hi in ((0, -1.0, 1.0), (1, -0.25, 0.25),
                                             (2, 0.25, 1.05), (3, 0.0, 0.20)):
                            for direction in (1, -1):
                                stepped = False
                                while True:
                                    trial = [list(p) for p in params]
                                    v = trial[gi][axis] + direction * scale_step
                                    if v < lo or v > hi:
                                        break
                                    trial[gi][axis] = v
                                    e, a, r = score(trial)
                                    if e < cur - 1e-9:
                                        cur, amps, resid, params = e, a, r, trial
                                        moved = stepped = True
                                    else:
                                        break
                                if stepped:
                                    break
                    if not moved:
                        break
            line.append((step + 1, cur / norm, list(zip(chosen, params)), amps.copy()))
        print(f"  t={times[i]:7.1f} ms")
        for n, rel, ch, amps in line:
            desc = "  ".join(f"{d}@{p[0]:+.2f} s{p[2]:.2f} a{a:.2f}"
                             for (d, p), a in zip(ch, amps))
            print(f"     {n} glyph(s): residual {rel:.3f}   {desc}")
        print()


if __name__ == "__main__":
    main()
