"""Read the reference's animation directly: per glyph, per frame, what it did.

    python3 .agent/tools/decompose.py artifacts/gt_ios_ref/run-1785683986267 --col=2 --digits=2,1

Every measurement in this project so far has been of SUMMED ink — an ink floor, a band, a centroid.
Summed ink cannot say which glyph did what, so every model has been inferred from aggregates and
then argued about. This fits the frame instead.

Each frame is modelled as a sum of the settled glyph rasters, each with its own

    dy      offset from the column's rest line, in glyph heights
    sx, sy  horizontal and vertical scale, SEPARATELY — so a vertical-only squash, which is what a
            drum predicts and a flat strip does not, shows up as sy < sx rather than being assumed
    sigma   isotropic blur radius, in glyph heights
    a       amplitude

fitted by matching pursuit over a parameter grid and then refined. The templates are the settled
digits cut out of the SAME capture, so the only thing that differs between template and frame is
what the transition did to it.

**The residual is the whole point and it is printed on every row.** A decomposition that does not
reconstruct the frame is a story, not a measurement — if `resid` is not small, nothing else on the
line means anything, and a rising residual through a burst is itself the finding (it says more
glyphs are alive than were fitted).

`--digits` is the set that may appear, in the order they arrive. Give it from the run's own marks:
for 1,242 -> 1,160 the units column goes 2 -> 0, the tens 4 -> 6, the hundreds 2 -> 1.
"""

import argparse
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ground_truth import load, ink_box, columns_of  # noqa: E402

# Deliberately wide on scale: the point is to MEASURE the shrink, so a grid centred on Apple's
# 0.3984 could only ever confirm it.
SX = np.array([0.25, 0.34, 0.42, 0.50, 0.62, 0.74, 0.86, 0.94, 1.00, 1.06])
SY = np.array([0.25, 0.34, 0.42, 0.50, 0.62, 0.74, 0.86, 0.94, 1.00, 1.06])
SIGMA = np.array([0.0, 0.03, 0.06, 0.10, 0.15, 0.21])
DOWN = 2       # the search runs at half resolution; the amplitude is re-solved at full
PASSES = 3     # matching-pursuit sweeps, so a pass refines a glyph rather than fitting its leftovers


def blur1d(patch, sigma, axis):
    if sigma < 0.35:
        return patch
    radius = max(1, int(np.ceil(sigma * 3)))
    x = np.arange(-radius, radius + 1, dtype=np.float64)
    k = np.exp(-(x * x) / (2 * sigma * sigma))
    k /= k.sum()
    return np.apply_along_axis(lambda r: np.convolve(r, k, mode="same"), axis,
                               np.pad(patch, [(radius, radius) if i == axis else (0, 0)
                                              for i in range(2)], mode="constant"))


def gaussian(patch, sigma):
    return blur1d(blur1d(patch, sigma, 0), sigma, 1)


def tile_of(base, sx, sy, sigma_px):
    h, w = base.shape
    nh, nw = max(1, int(round(h * sy))), max(1, int(round(w * sx)))
    tile = np.asarray(
        Image.fromarray(base.astype(np.uint8)).resize((nw, nh), Image.LANCZOS), dtype=np.float64
    )
    return gaussian(tile, sigma_px) if sigma_px > 0 else tile


def place(tile, shape, cy, cx):
    canvas = np.zeros(shape, dtype=np.float64)
    top, left = int(round(cy - tile.shape[0] / 2)), int(round(cx - tile.shape[1] / 2))
    y0, x0 = max(0, top), max(0, left)
    y1, x1 = min(shape[0], top + tile.shape[0]), min(shape[1], left + tile.shape[1])
    if y1 > y0 and x1 > x0:
        canvas[y0:y1, x0:x1] = tile[y0 - top:y1 - top, x0 - left:x1 - left]
    return canvas


class Bank:
    """Every (scale, scale, blur) of one digit, rendered ONCE and kept as a vertical-shift FFT.

    The shift search is the expensive part done naively — re-rendering a template for every
    candidate offset is ~30k renders per glyph per frame, which is minutes a frame. Shifting is an
    index operation, so the correlation over ALL offsets is one FFT product: with the horizontal
    axis contracted first, each template costs a single 1-D inverse transform.
    """

    def __init__(self, base, shape, cy, cx, glyph_px):
        self.shape, self.cy, self.cx = shape, cy, cx
        self.glyph_px = glyph_px
        small = (shape[0] // DOWN, shape[1] // DOWN)
        self.ny = int(2 ** np.ceil(np.log2(small[0] * 2)))
        self.nx = int(2 ** np.ceil(np.log2(small[1] * 2)))
        self.params, spectra, energies = [], [], []
        for sx in SX:
            for sy in SY:
                for sig in SIGMA:
                    tile = tile_of(base, sx, sy, sig * glyph_px)
                    canvas = place(tile, shape, cy, cx)[::DOWN, ::DOWN]
                    self.params.append((sx, sy, sig))
                    spectra.append(
                        np.fft.rfft2(canvas, (self.ny, self.nx)).astype(np.complex64))
                    energies.append(float((canvas * canvas).sum()))
        self.spectra = np.stack(spectra)
        self.energy = np.maximum(np.array(energies), 1e-9)
        self.base = base
        # How far the glyph is allowed to be from the column's rest centre, horizontally. The
        # window is wider than one column so a wide digit is not clipped, which means a neighbour's
        # glyph is inside it too — unconstrained, the fit happily latches onto the neighbour.
        self.dx_limit = max(2, int(round(glyph_px * 0.30 / DOWN)))

    def best(self, target):
        """(gain, dy_px, dx_px, sx, sy, sigma, amplitude) maximising the explained energy.

        The search is 2-D. A 1-D one, over the vertical offset only, is what the first version did
        and it cannot work: "1,242" and "1,160" have different digit widths, so the number
        re-lays-out and every column MOVES horizontally during a transition. The fit was then
        matching a template against a glyph that had slid out from under it, and the residual came
        back at 1.66 — larger than the target itself.
        """
        small = target[::DOWN, ::DOWN]
        ft = np.fft.rfft2(small, (self.ny, self.nx)).astype(np.complex64)
        best = None
        # Chunked: the full correlation stack is (templates, ny, nx) and does not need to exist.
        for lo in range(0, len(self.params), 48):
            block = self.spectra[lo:lo + 48]
            corr = np.fft.irfft2(ft[None] * np.conj(block), (self.ny, self.nx))
            gains = (corr * corr) / self.energy[lo:lo + 48, None, None]
            # Only shifts within the allowed horizontal band; wrap-around puts negative dx at the
            # far end of the axis, so both ends are kept.
            mask = np.zeros(self.nx, dtype=bool)
            mask[:self.dx_limit + 1] = True
            mask[-self.dx_limit:] = True
            gains[:, :, ~mask] = -1.0
            idx = int(np.argmax(gains))
            t, dy, dx = np.unravel_index(idx, gains.shape)
            if best is None or gains[t, dy, dx] > best[0]:
                best = (float(gains[t, dy, dx]), lo + t, dy, dx,
                        float(corr[t, dy, dx] / self.energy[lo + t]))
        gain, t, dy, dx, amp = best
        if dy > self.ny // 2:
            dy -= self.ny
        if dx > self.nx // 2:
            dx -= self.nx
        sx, sy, sig = self.params[t]
        return gain, dy * DOWN, dx * DOWN, sx, sy, sig, amp

    def model(self, dy_px, dx_px, sx, sy, sigma, amp, glyph_px):
        tile = tile_of(self.base, sx, sy, sigma * glyph_px)
        return amp * place(tile, self.shape, self.cy + dy_px, self.cx + dx_px)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("prefix")
    ap.add_argument("--col", type=int, default=2)
    ap.add_argument("--digits", required=True, help="e.g. 2,1 — the glyphs that may appear")
    ap.add_argument("--from", dest="lo", type=float, default=-30)
    ap.add_argument("--to", dest="hi", type=float, default=750)
    ap.add_argument("--every", type=int, default=2)
    ap.add_argument("--mark", type=int, default=1)
    args = ap.parse_args()

    meta, frames = load(args.prefix)
    y0, y1, x0, x1 = ink_box(frames)
    w = frames[:, y0:y1, x0:x1].astype(np.float64)
    groups = columns_of(w[-1])
    a, b = groups[args.col]
    col = args.col if args.col >= 0 else len(groups) + args.col

    # The window is bounded by the MIDPOINTS to the neighbouring columns, not by this column's own
    # settled box and not by a fixed margin. Two failures got it here, and only the residual showed
    # either:
    #
    #   - the settled box is too NARROW. A column ending on a "1" gets 86 px while the "2" passing
    #     through it is 130 px wide, so the template was clipped at the sides. Residual 1.66.
    #   - a fixed generous margin is too WIDE. It swallows the separator and part of the next
    #     digit, and the fit spends a glyph on the neighbour's ink: a phantom "1" at amplitude 0.46
    #     sitting a third of a glyph off centre. Residual 0.18 on a frame that is one settled digit.
    #
    # Halfway to each neighbour is the boundary that is both wide enough for any glyph and owned by
    # exactly one column.
    lo_x = 0 if col == 0 else int((groups[col - 1][1] + a) / 2)
    hi_x = w.shape[2] if col == len(groups) - 1 else int((b + groups[col + 1][0]) / 2)
    centre = (a + b) / 2.0
    settled_rows = w[-1][:, a:b].sum(axis=1)
    lit = np.nonzero(settled_rows > settled_rows.max() * 0.05)[0]
    glyph_px = float(lit[-1] - lit[0])
    patches = w[:, :, lo_x:hi_x]
    rest_y = (lit[0] + lit[-1]) / 2.0
    cx = centre - lo_x
    shape = patches.shape[1:]

    from sim import Atlas, ATLAS_SOURCES
    atlas = Atlas().load_from(ATLAS_SOURCES)
    digits = [d.strip() for d in args.digits.split(",")]
    banks = {}
    for d in digits:
        raster = atlas.get(d).astype(np.float64)
        k = glyph_px / raster.shape[0]
        base = np.asarray(
            Image.fromarray(raster.astype(np.uint8)).resize(
                (max(1, int(round(raster.shape[1] * k))), max(1, int(round(raster.shape[0] * k)))),
                Image.LANCZOS),
            dtype=np.float64)
        banks[d] = Bank(base, shape, rest_y, cx, glyph_px)

    times = np.array(meta["times"]) - meta["marks"][args.mark]["t"]
    picked = list(np.nonzero((times >= args.lo) & (times <= args.hi))[0])[:: args.every]

    print(f"   glifo {glyph_px:.0f}px  colonna {args.col}  cifre {digits}  "
          f"({len(banks[digits[0]].params)} template per cifra)")
    print("      t(ms)   " + "  ".join(f"{d}:  dy     dx     sx    sy   blur    a" for d in digits)
          + "    resid")
    for i in picked:
        target = patches[i]
        if target.sum() <= 0:
            continue
        fits = {d: None for d in digits}
        residual = target.copy()
        for _ in range(PASSES):
            for d in digits:
                if fits[d] is not None:
                    residual = residual + fits[d][1]
                _, dy, dx, sx, sy, sig, _ = banks[d].best(residual)
                unit = banks[d].model(dy, dx, sx, sy, sig, 1.0, glyph_px)
                fits[d] = ([dy / glyph_px, dx / glyph_px, sx, sy, sig, 0.0], unit)
                residual = residual - unit * 0.0

            # Solve the amplitudes JOINTLY, not one after the other.
            #
            # Greedy pursuit gives the first glyph credit for ink that belongs to the second, and
            # through the middle of a crossing — where they overlap most and where the answer
            # matters most — it collapses outright: the departing "2" came back at amplitude 1.18
            # with the arriving "1" at 0.00, residual 0.556. Least squares over both at once, with
            # the amplitudes clamped at zero, is the whole fix.
            models = [fits[d][1] for d in digits]
            gram = np.array([[float((m * n).sum()) for n in models] for m in models])
            rhs = np.array([float((m * target).sum()) for m in models])
            try:
                amps = np.linalg.solve(gram + np.eye(len(models)) * 1e-6, rhs)
            except np.linalg.LinAlgError:
                amps = np.zeros(len(models))
            amps = np.clip(amps, 0.0, None)
            residual = target.copy()
            for d, amp, unit in zip(digits, amps, models):
                fits[d][0][5] = float(amp)
                fits[d] = (fits[d][0], unit * float(amp))
                residual = residual - fits[d][1]
        err = float(np.abs(residual).sum() / max(1.0, np.abs(target).sum()))
        cells = []
        for d in digits:
            dy, dx, sx, sy, sig, amp = fits[d][0]
            cells.append(f"{d}:{dy:+.2f}  {dx:+.2f}  {sx:.2f}  {sy:.2f}  {sig:.2f}  {amp:.2f}")
        print(f"   {times[i]:8.1f}   " + "   ".join(cells) + f"    {err:.3f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
