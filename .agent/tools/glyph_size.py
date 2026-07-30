#!/usr/bin/env python3
"""Measure the SIZE of the arriving and departing glyph in a single-column roll, per frame.

`template_fit.py` fits opacity, vertical position and blur per glyph but searches no scale — its
templates are the settled glyph at fixed size, so it cannot see a glyph that is drawn too big or too
small. This is for exactly that question, raised by inspecting a frame grid: does the arriving digit
already look like a full-size digit a couple of frames after onset, when the reference's still looks
small?

Method: for a short travel, the two glyphs mostly separate vertically before they blend, so most
frames show two ink bands with a gap between them (or a single band before/after crossing). Row-sum
profile is split at gaps into bands; each band's own bounding box gives a height and width, which is
normalised against that band's OWN settled size (measured from a still frame). No shared calibration
between old and new glyph is needed since each is compared only to its own rest state.

Usage:
    python3 glyph_size.py --video ios60.mp4 --band 0.335 0.545 --onset 874 --settled 862
"""
import argparse
import subprocess

import numpy as np


def probe_size(video):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=width,height", "-of", "csv=p=0", video],
        capture_output=True, text=True, check=True,
    ).stdout.strip().split(",")
    return int(out[0]), int(out[1])


def frames(video, band, xband, first, count):
    vw, vh = probe_size(video)
    top = int(round(band[0] * vh)); h = int(round((band[1] - band[0]) * vh))
    left = int(round(xband[0] * vw)); w = int(round((xband[1] - xband[0]) * vw))
    p = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", video,
         "-vf", f"select='between(n\\,{first}\\,{first + count})',crop={w}:{h}:{left}:{top},format=gray",
         "-vsync", "0", "-f", "rawvideo", "-pix_fmt", "gray", "-"],
        capture_output=True, check=True,
    )
    n = len(p.stdout) // (w * h)
    assert n > 0, "no frames decoded"
    return 1.0 - np.frombuffer(p.stdout[: n * w * h], dtype=np.uint8).reshape(n, h, w).astype(np.float32) / 255.0


def units_column(still, min_frac=0.25):
    prof = still.sum(axis=0)
    on = prof > prof.max() * min_frac
    runs, i = [], 0
    while i < len(on):
        if on[i]:
            j = i
            while j < len(on) and on[j]:
                j += 1
            runs.append((i, j)); i = j
        else:
            i += 1
    runs = [r for r in runs if r[1] - r[0] > 4]
    return runs[-1] if runs else (0, still.shape[1])


def row_bands(frame_row_ink, gap_frac=0.05):
    """Contiguous runs of ink rows, split at gaps — one run per glyph, usually."""
    peak = frame_row_ink.max()
    if peak <= 1e-6:
        return []
    on = frame_row_ink > peak * gap_frac
    runs, i = [], 0
    while i < len(on):
        if on[i]:
            j = i
            while j < len(on) and on[j]:
                j += 1
            runs.append((i, j)); i = j
        else:
            i += 1
    return runs


def bbox(frame, top, bot, thr):
    seg = frame[top:bot]
    mask = seg > thr
    cols = np.nonzero(mask.any(axis=0))[0]
    if not len(cols):
        return None
    return bot - top, int(cols[-1] - cols[0] + 1)  # height (rows given), width


def connected_components(mask):
    """8-connected components of a boolean array. No scipy in this environment — small crops, so a
    plain BFS is fast enough and avoids the dependency."""
    h, w = mask.shape
    labels = np.zeros((h, w), dtype=np.int32)
    comps = []
    cur = 0
    ys, xs = np.nonzero(mask)
    seen = np.zeros_like(mask)
    for y0, x0 in zip(ys, xs):
        if seen[y0, x0]:
            continue
        cur += 1
        stack = [(y0, x0)]
        seen[y0, x0] = True
        pixels = []
        while stack:
            y, x = stack.pop()
            pixels.append((y, x))
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    ny, nx = y + dy, x + dx
                    if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                        seen[ny, nx] = True
                        stack.append((ny, nx))
        pixels = np.array(pixels)
        comps.append({
            "size": len(pixels),
            "y0": int(pixels[:, 0].min()), "y1": int(pixels[:, 0].max()) + 1,
            "x0": int(pixels[:, 1].min()), "x1": int(pixels[:, 1].max()) + 1,
        })
    return comps


def dominant_blob(frame, thr):
    """The connected component of darkest ink, thresholded — whichever glyph is currently sharpest
    and most opaque, regardless of where the fainter ghost of the other one sits."""
    mask = frame > thr
    comps = connected_components(mask)
    if not comps:
        return None
    # "Dominant" = highest total ink mass, not just pixel count, so a large pale smear does not
    # outrank a small sharp digit.
    best, best_mass = None, -1.0
    for c in comps:
        mass = float(frame[c["y0"]:c["y1"], c["x0"]:c["x1"]].sum())
        if mass > best_mass:
            best_mass, best = mass, c
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video", required=True)
    ap.add_argument("--band", nargs=2, type=float, required=True)
    ap.add_argument("--xband", nargs=2, type=float, default=[0.03, 0.80])
    ap.add_argument("--onset", type=int, required=True)
    ap.add_argument("--settled", type=int, default=None, help="frame index of a settled number")
    ap.add_argument("--count", type=int, default=40)
    ap.add_argument("--thr", type=float, default=0.15)
    ap.add_argument("--rel-thr", type=float, default=0.5,
                    help="peak-row width threshold, as a fraction of that row's own max value")
    ap.add_argument("--fps", type=float, default=60.0)
    args = ap.parse_args()

    settled_at = args.settled if args.settled is not None else max(0, args.onset - 12)
    still = frames(args.video, args.band, args.xband, settled_at, 1)[0]
    a, b = units_column(still)
    pad = int((b - a) * 0.6)
    sl = slice(max(0, a - pad), b + pad)
    still = still[:, sl]

    ink = frames(args.video, args.band, args.xband, args.onset, args.count)[:, :, sl]

    # The settled glyph's own height/width, at the SAME threshold used per-frame below.
    rows = np.nonzero((still > args.thr).any(axis=1))[0]
    rest_h = float(rows[-1] - rows[0] + 1) if len(rows) else still.shape[0]
    rest_w_box = bbox(still, 0, still.shape[0], args.thr)
    rest_w = rest_w_box[1] if rest_w_box else still.shape[1]

    # The settled glyph's own peak-row width, at a threshold relative to THAT row's own peak — so
    # it is comparable to the same self-relative measurement taken per-frame below.
    peak_row_rest = int(np.argmax(still.sum(axis=1)))
    row = still[peak_row_rest]
    rest_peak_w = _row_width(row, args.rel_thr)

    print(f"# {args.video}: settled glyph peak-row width {rest_peak_w}px at rel_thr {args.rel_thr}")
    print(f"{'ms':>6} {'peak_row_w':>10}  {'peak_row_y':>10}  (whichever glyph is currently "
          f"darkest, width / settled width, position / settled height)")
    rest_cy = peak_row_rest
    for i, f in enumerate(ink):
        ms = i * 1000 / args.fps
        row_sums = f.sum(axis=1)
        py = int(np.argmax(row_sums))
        w = _row_width(f[py], args.rel_thr)
        print(f"{ms:6.0f} {w / rest_peak_w:10.3f}  {(py - rest_cy) / rest_h:10.3f}")


def _row_width(row, rel_thr):
    """Width of the run around the row's own peak, at a fraction of THAT row's peak value — robust
    to blur softening the edges differently across frames, unlike a fixed absolute threshold."""
    if row.max() <= 1e-6:
        return 0
    on = row > row.max() * rel_thr
    idx = np.nonzero(on)[0]
    return int(idx[-1] - idx[0] + 1) if len(idx) else 0


if __name__ == "__main__":
    main()
