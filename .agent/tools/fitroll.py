"""Fit one column of a roll into its two glyphs, frame by frame.

Differences from .agent/tools/decompose.py, each of which was a reason its residual blew up
mid-crossing:

  - Templates are cut from THIS capture's own settled frames, not rendered from a font atlas, so
    the only thing that differs between template and frame is what the transition did.
  - The settled "old" frame is the one just before the change mark, not frame 0 — frame 0 of a
    preset run is mid-transition into the parked value, and using it puts a smeared glyph in the
    template bank.
  - The host's edge-fade mask is in the forward model. Glyph ink reaches capture row 476 during
    the transition and the bottom fade starts at 453, so a departing glyph is attenuated by the
    mask; without it the fit pays for the missing ink by inventing scale and amplitude.
  - Amplitudes are solved by non-negative least squares and CLAMPED to [0, 1.05]. Unbounded, the
    old fit returned a=1.49 mid-crossing, which is not a thing a crossfade can do.
  - Each frame starts from the previous frame's solution and refines locally, so the trajectory is
    continuous instead of hopping between degenerate corners of the grid.
"""

import argparse
import json
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ground_truth import load, ink_box, columns_of  # noqa: E402


def gaussian_blur(patch, sigma_px):
    """Isotropic blur that GROWS the tile instead of clipping it.

    Trimming back to the input size discards the kernel's tails, which leaves a heavily blurred
    glyph as a hard-edged rectangle — the blur radius here reaches 22 px on a 128 px tile, so the
    truncation was most of the glyph. The tile is padded symmetrically and kept padded, so its
    centre is unchanged and its edges are soft.
    """
    if sigma_px < 0.4:
        return patch
    radius = max(1, int(np.ceil(sigma_px * 3)))
    x = np.arange(-radius, radius + 1, dtype=np.float64)
    k = np.exp(-(x * x) / (2 * sigma_px * sigma_px))
    k /= k.sum()
    out = np.pad(patch, ((radius, radius), (radius, radius)))
    out = np.apply_along_axis(lambda r: np.convolve(r, k, mode="same"), 0, out)
    out = np.apply_along_axis(lambda r: np.convolve(r, k, mode="same"), 1, out)
    return out


class Glyph:
    """One settled digit, scalable/blurrable/shiftable inside the column window."""

    def __init__(self, patch, shape, glyph_px):
        self.shape = shape
        self.glyph_px = glyph_px
        rows = patch.sum(axis=1)
        cols = patch.sum(axis=0)
        ys = np.nonzero(rows > rows.max() * 0.02)[0]
        xs = np.nonzero(cols > cols.max() * 0.02)[0]
        self.box = (ys[0], ys[-1] + 1, xs[0], xs[-1] + 1)
        self.core = patch[ys[0]:ys[-1] + 1, xs[0]:xs[-1] + 1]
        self.cy = (ys[0] + ys[-1] + 1) / 2.0
        self.cx = (xs[0] + xs[-1] + 1) / 2.0
        self._cache = {}

    def tile(self, sx, sy, sigma):
        key = (round(sx, 3), round(sy, 3), round(sigma, 3))
        hit = self._cache.get(key)
        if hit is None:
            h, w = self.core.shape
            nh, nw = max(2, int(round(h * sy))), max(2, int(round(w * sx)))
            t = np.asarray(Image.fromarray(self.core.astype(np.uint8)).resize((nw, nh), Image.LANCZOS),
                           dtype=np.float64)
            hit = gaussian_blur(t, sigma * self.glyph_px)
            if len(self._cache) < 4000:
                self._cache[key] = hit
        return hit

    def render(self, dy, dx, sx, sy, sigma):
        """Unit-amplitude model, placed at the glyph's rest centre plus (dy, dx) glyph heights."""
        tile = self.tile(sx, sy, sigma)
        cy = self.cy + dy * self.glyph_px
        cx = self.cx + dx * self.glyph_px
        canvas = np.zeros(self.shape, dtype=np.float64)
        # Subpixel placement by splitting between the two neighbouring integer rows/cols.
        top = cy - tile.shape[0] / 2.0
        left = cx - tile.shape[1] / 2.0
        t0, fy = int(np.floor(top)), top - np.floor(top)
        l0, fx = int(np.floor(left)), left - np.floor(left)
        for dyi, wy in ((0, 1 - fy), (1, fy)):
            if wy <= 0:
                continue
            for dxi, wx in ((0, 1 - fx), (1, fx)):
                if wx <= 0:
                    continue
                y0, x0 = t0 + dyi, l0 + dxi
                y1, x1 = y0 + tile.shape[0], x0 + tile.shape[1]
                cy0, cx0 = max(0, y0), max(0, x0)
                cy1, cx1 = min(self.shape[0], y1), min(self.shape[1], x1)
                if cy1 <= cy0 or cx1 <= cx0:
                    continue
                canvas[cy0:cy1, cx0:cx1] += (wy * wx) * tile[cy0 - y0:cy1 - y0, cx0 - x0:cx1 - x0]
        return canvas


def edge_mask(meta, row_offset, shape):
    """The host's `.mask(edgeFadeMask)`: clear above the view, ramping to opaque over its top 15%,
    and the mirror at the bottom. Returned in the analysis window's own row space."""
    # The fade is the iOS host's own `.mask(edgeFadeMask)`. The Android recorder writes neither
    # viewBounds nor captureRect and its renderer has no such mask, so there is nothing to model —
    # returning ones keeps every caller working on both platforms.
    if "viewBounds" not in meta or "captureRect" not in meta:
        return np.ones(shape)
    vb, cr, sc = meta["viewBounds"], meta["captureRect"], meta["scale"]
    vy0 = (0 - cr[1]) * sc
    vy1 = (vb[1] - cr[1]) * sc
    fade = 0.15 * (vy1 - vy0)
    y = np.arange(shape[0], dtype=np.float64) + row_offset
    m = np.clip((y - vy0) / fade, 0, 1) * np.clip((vy1 - y) / fade, 0, 1)
    return np.repeat(m[:, None], shape[1], axis=1)


def solve_amplitudes(models, target, hi=1.05):
    """Least squares over both glyphs at once, clamped to [0, hi].

    Greedy per-glyph fitting gives the first glyph credit for ink that belongs to the second, and
    through the middle of a crossing — where they overlap most — that is exactly where it matters.
    """
    gram = np.array([[float((m * n).sum()) for n in models] for m in models])
    rhs = np.array([float((m * target).sum()) for m in models])
    try:
        amps = np.linalg.solve(gram + np.eye(len(models)) * 1e-9, rhs)
    except np.linalg.LinAlgError:
        amps = np.zeros(len(models))
    amps = np.clip(amps, 0.0, hi)
    # One pass of coordinate refinement under the clamp, so a clipped glyph does not leave the
    # other one carrying its error.
    for _ in range(3):
        for i in range(len(models)):
            others = sum(a * m for j, (a, m) in enumerate(zip(amps, models)) if j != i)
            denom = gram[i, i]
            if denom <= 1e-12:
                continue
            amps[i] = np.clip((rhs[i] - float((models[i] * others).sum())) / denom, 0.0, hi)
    return amps


AXES = ((0, -1.30, 1.30), (1, -0.35, 0.35), (2, 0.18, 1.12), (3, 0.18, 1.12), (4, 0.0, 0.34))

COARSE_DY = np.arange(-1.20, 1.21, 0.06)
COARSE_S = np.arange(0.20, 1.11, 0.10)
COARSE_B = np.arange(0.0, 0.31, 0.06)


def _fit(glyphs, ps, target, mask):
    models = [mask * g.render(*p[:5]) for g, p in zip(glyphs, ps)]
    amps = solve_amplitudes(models, target)
    resid = target - sum(a * m for a, m in zip(amps, models))
    return float(np.abs(resid).sum()), amps


def coarse_search(glyphs, params, target, mask, gi):
    """Sweep one glyph over a coarse (dy, s, blur) grid with the other held fixed.

    A purely local descent cannot start a glyph that is currently at amplitude zero: with a=0 its
    shape parameters have no gradient, so the arriving digit stayed at rest and the fit paid for it
    with the departing one. This is what gives each frame a real starting point.
    """
    best = _fit(glyphs, params, target, mask)[0]
    keep = [list(p) for p in params]
    trial = [list(p) for p in params]
    for s in COARSE_S:
        for b in COARSE_B:
            trial[gi][2] = trial[gi][3] = float(s)
            trial[gi][4] = float(b)
            for dy in COARSE_DY:
                trial[gi][0] = float(dy)
                err, _ = _fit(glyphs, trial, target, mask)
                if err < best - 1e-9:
                    best = err
                    keep = [list(p) for p in trial]
    return keep, best


def refine(glyphs, params, target, mask):
    """Coarse sweep per glyph, then annealed line search on every axis of both."""
    for gi in range(len(glyphs)):
        params, _ = coarse_search(glyphs, params, target, mask, gi)

    best, amps = _fit(glyphs, params, target, mask)
    for step_scale in (0.048, 0.024, 0.012):
        for _ in range(4):
            improved = False
            for gi in range(len(glyphs)):
                for axis, lo, hi in AXES:
                    step = step_scale if axis in (0, 1) else step_scale * 1.6
                    for direction in (1, -1):
                        moved = False
                        while True:
                            trial = [list(p) for p in params]
                            v = trial[gi][axis] + direction * step
                            if v < lo or v > hi:
                                break
                            trial[gi][axis] = v
                            err, a = _fit(glyphs, trial, target, mask)
                            if err < best - 1e-9:
                                best, amps, params = err, a, trial
                                improved = moved = True
                            else:
                                break
                        if moved:
                            break
            if not improved:
                break
    return params, amps, best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("prefix")
    ap.add_argument("--col", type=int, default=2)
    ap.add_argument("--mark", type=int, default=1)
    ap.add_argument("--from", dest="lo", type=float, default=-60)
    ap.add_argument("--to", dest="hi", type=float, default=760)
    ap.add_argument("--every", type=int, default=1)
    ap.add_argument("--json", help="write the fitted curves here")
    ap.add_argument("--no-mask", action="store_true", help="drop the edge fade from the model")
    args = ap.parse_args()

    meta, frames = load(args.prefix)
    y0, y1, x0, x1 = ink_box(frames)
    w = frames[:, y0:y1, x0:x1].astype(np.float64)
    times = np.array(meta["times"]) - meta["marks"][args.mark]["t"]

    # The settled OLD frame: the last one at least 60 ms before the change. Frame 0 is mid-flight.
    old_idx = int(np.nonzero(times <= -60)[0][-1])
    settled_old, settled_new = w[old_idx], w[-1]
    g_old = columns_of(settled_old)
    g_new = columns_of(settled_new)
    if len(g_old) != len(g_new):
        raise SystemExit(f"columns differ: {len(g_old)} settled-old vs {len(g_new)} settled-new — "
                         f"this is a structural change, not a roll")
    c = args.col
    lo_x = 0 if c == 0 else int((max(g_old[c - 1][1], g_new[c - 1][1]) + min(g_old[c][0], g_new[c][0])) / 2)
    hi_x = (w.shape[2] if c == len(g_new) - 1
            else int((max(g_old[c][1], g_new[c][1]) + min(g_old[c + 1][0], g_new[c + 1][0])) / 2))

    rows = settled_new[:, g_new[c][0]:g_new[c][1]].sum(axis=1)
    lit = np.nonzero(rows > rows.max() * 0.05)[0]
    glyph_px = float(lit[-1] - lit[0])

    shape = (w.shape[1], hi_x - lo_x)
    old_g = Glyph(settled_old[:, lo_x:hi_x], shape, glyph_px)
    new_g = Glyph(settled_new[:, lo_x:hi_x], shape, glyph_px)
    mask = np.ones(shape) if args.no_mask else edge_mask(meta, y0, shape)

    picked = list(np.nonzero((times >= args.lo) & (times <= args.hi))[0])[::args.every]
    params = [[0.0, 0.0, 1.0, 1.0, 0.0], [0.0, 0.0, 1.0, 1.0, 0.0]]
    print(f"  column {c}  glyph {glyph_px:.0f}px  window x[{lo_x},{hi_x}]  "
          f"mask={'off' if args.no_mask else 'on'}")
    print("     t(ms)  |  OLD  dy     sx    sy   blur    a  |  NEW  dy     sx    sy   blur    a  | resid")
    out = []
    for i in picked:
        target = w[i, :, lo_x:hi_x]
        params, amps, err = refine([old_g, new_g], params, target, mask)
        rel = err / max(1.0, float(np.abs(target).sum()))
        o, n = params
        print(f"  {times[i]:8.1f}  |  {o[0]:+.3f} {o[2]:6.3f} {o[3]:6.3f} {o[4]:6.3f} {amps[0]:6.3f}"
              f"  |  {n[0]:+.3f} {n[2]:6.3f} {n[3]:6.3f} {n[4]:6.3f} {amps[1]:6.3f}  | {rel:.3f}")
        out.append({"t": float(times[i]),
                    "old": {"dy": o[0], "dx": o[1], "sx": o[2], "sy": o[3], "blur": o[4],
                            "a": float(amps[0])},
                    "new": {"dy": n[0], "dx": n[1], "sx": n[2], "sy": n[3], "blur": n[4],
                            "a": float(amps[1])},
                    "resid": rel})
    if args.json:
        with open(args.json, "w") as h:
            json.dump({"prefix": args.prefix, "col": c, "glyph_px": glyph_px, "frames": out}, h, indent=1)
        print(f"\nwrote {args.json}")


if __name__ == "__main__":
    main()
