#!/usr/bin/env python3
"""Create a frame-by-frame iOS/Android comparison grid for NumericText.

This version detects the number band independently in each recording, so different
screen sizes/layouts do not make one crop capture the +/- buttons instead of the number.

Typical usage (automatic number detection):
    python3 frame_grid_fixed.py \
      --a captures/ios_human.mov \
      --b captures/android_human_1.mp4 \
      --name-a iOS --name-b Android \
      --onset-a 140 --onset-b 106 \
      --count 24 --stride 1 \
      --out artifacts/human-roll-grid.png

Manual overrides remain available with --band-a/--band-b, expressed as fractions of
frame height.
"""
import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw


def probe_size(video):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=width,height", "-of", "csv=p=0", video],
        capture_output=True, text=True, check=True,
    ).stdout.strip().split(",")
    return int(out[0]), int(out[1])


def extract_full_gray(video, frame_index):
    """Decode one source-resolution grayscale frame."""
    vw, vh = probe_size(video)
    proc = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", video,
         "-vf", f"select='eq(n\\,{frame_index})',format=gray",
         "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "gray", "-"],
        capture_output=True, check=True,
    )
    if len(proc.stdout) < vw * vh:
        raise RuntimeError(f"cannot decode frame {frame_index} from {video}")
    return np.frombuffer(proc.stdout[:vw * vh], dtype=np.uint8).reshape(vh, vw)


def contiguous_runs(mask, max_blank):
    runs = []
    start = None
    blank = 0
    for i, value in enumerate(mask):
        if value:
            if start is None:
                start = i
            blank = 0
        elif start is not None:
            blank += 1
            if blank > max_blank:
                runs.append((start, i - blank + 1))
                start = None
                blank = 0
    if start is not None:
        runs.append((start, len(mask)))
    return runs


def detect_number_band(video, settled_frame, search=(0.16, 0.58), pad=0.46):
    """Find the main number block and return a padded vertical band as fractions.

    Detection uses dark text ink, groups rows into separate UI blocks, then scores blocks
    by ink mass, width and glyph-like height. The +/- controls form a separate lower block
    and are rejected even when they are inside the broad search area.
    """
    frame = extract_full_gray(video, settled_frame).astype(np.float32)
    vh, vw = frame.shape
    lo = max(0, int(round(search[0] * vh)))
    hi = min(vh, int(round(search[1] * vh)))
    region = frame[lo:hi]

    # Estimate page background from side strips and convert to dark-ink strength.
    side = max(4, int(vw * 0.025))
    bg = float(np.median(np.concatenate([region[:, :side].ravel(), region[:, -side:].ravel()])))
    ink = np.clip(bg - region, 0, None)
    ink[ink < 42] = 0

    row_mass = ink.sum(axis=1)
    if row_mass.max() <= 0:
        raise RuntimeError(f"number band not found in {video}")

    active = row_mass > row_mass.max() * 0.018
    runs = contiguous_runs(active, max_blank=max(3, int(vh * 0.008)))
    candidates = []
    for y0, y1 in runs:
        if y1 <= y0:
            continue
        block = ink[y0:y1]
        col_mass = block.sum(axis=0)
        cols = np.nonzero(col_mass > max(col_mass.max() * 0.025, 1.0))[0]
        if not len(cols):
            continue
        x0, x1 = int(cols[0]), int(cols[-1] + 1)
        h = y1 - y0
        w = x1 - x0
        mass = float(block[:, x0:x1].sum())
        # Number is wide, dark and roughly 5-16% of screen height. Penalise tiny labels
        # and very tall blocks that combine number + buttons.
        height_frac = h / vh
        if not (0.035 <= height_frac <= 0.18):
            continue
        width_frac = w / vw
        score = mass * (0.6 + min(width_frac, 0.8))
        candidates.append((score, y0, y1, x0, x1, mass, width_frac, height_frac))

    if not candidates:
        raise RuntimeError(f"number band not found reliably in {video}")

    _, y0, y1, *_ = max(candidates, key=lambda c: c[0])
    top = lo + y0
    bottom = lo + y1
    glyph_h = max(1, bottom - top)
    top = max(0, int(round(top - glyph_h * pad)))
    bottom = min(vh, int(round(bottom + glyph_h * pad)))
    return top / vh, bottom / vh


def extract(video, band, first, count, stride, outdir, tag, xband=(0.0, 1.0)):
    """Extract selected frames of one crop as PNGs."""
    vw, vh = probe_size(video)
    top = int(round(band[0] * vh))
    height = max(2, int(round((band[1] - band[0]) * vh)))
    left = int(round(xband[0] * vw))
    width = max(2, int(round((xband[1] - xband[0]) * vw)))
    last = first + count * stride
    sel = f"between(n\\,{first}\\,{last})"
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", video,
         "-vf", f"select='{sel}',crop={width}:{height}:{left}:{top}",
         "-fps_mode", "vfr", "-start_number", "0",
         str(outdir / f"{tag}_%04d.png")],
        check=True,
    )
    files = sorted(outdir.glob(f"{tag}_*.png"))
    if not files:
        sys.exit(f"no frames extracted from {video} at {first}")
    return [files[i] for i in range(0, min(len(files), count * stride), stride)][:count]


def digit_height(image):
    ink = 255 - np.asarray(image.convert("L"), dtype=np.int16)
    # Strong threshold avoids pale button backgrounds and motion halos.
    rows = np.nonzero((ink > 70).any(axis=1))[0]
    return int(rows[-1] - rows[0] + 1) if len(rows) else image.height


def ink_bounds(image, threshold=70):
    ink = 255 - np.asarray(image.convert("L"), dtype=np.int16)
    mask = ink > threshold
    rows = np.nonzero(mask.any(axis=1))[0]
    cols = np.nonzero(mask.any(axis=0))[0]
    if not len(rows) or not len(cols):
        return None
    return int(cols[0]), int(rows[0]), int(cols[-1]) + 1, int(rows[-1]) + 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--a", required=True, help="reference video, drawn on top")
    ap.add_argument("--b", required=True, help="candidate video, drawn below")
    ap.add_argument("--name-a", default="iOS")
    ap.add_argument("--name-b", default="Android")
    ap.add_argument("--band", nargs=2, type=float, default=None, metavar=("Y0", "Y1"),
                    help="shared manual vertical band; automatic detection is preferred")
    ap.add_argument("--band-a", nargs=2, type=float, default=None, metavar=("Y0", "Y1"))
    ap.add_argument("--band-b", nargs=2, type=float, default=None, metavar=("Y0", "Y1"))
    ap.add_argument("--search-band", nargs=2, type=float, default=[0.16, 0.58],
                    metavar=("Y0", "Y1"), help="area searched by automatic number detection")
    ap.add_argument("--xband", nargs=2, type=float, default=[0.03, 0.80], metavar=("X0", "X1"),
                    help="horizontal crop; default excludes the floating Tools button")
    ap.add_argument("--onset-a", type=int, required=True)
    ap.add_argument("--onset-b", type=int, required=True)
    ap.add_argument("--lead", type=int, default=2)
    ap.add_argument("--count", type=int, default=14)
    ap.add_argument("--stride", type=int, default=2)
    ap.add_argument("--settled-a", type=int, default=None)
    ap.add_argument("--settled-b", type=int, default=None)
    ap.add_argument("--cell-height", type=int, default=150)
    ap.add_argument("--cell-span", type=float, default=2.2)
    ap.add_argument("--out", default=None)
    ap.add_argument("--video", default=None)
    ap.add_argument("--slow", type=float, default=1.0)
    ap.add_argument("--title", default="")
    args = ap.parse_args()

    set_a = args.settled_a if args.settled_a is not None else max(0, args.onset_a - 8)
    set_b = args.settled_b if args.settled_b is not None else max(0, args.onset_b - 8)

    shared = tuple(args.band) if args.band else None
    band_a = tuple(args.band_a) if args.band_a else shared
    band_b = tuple(args.band_b) if args.band_b else shared
    if band_a is None:
        band_a = detect_number_band(args.a, set_a, tuple(args.search_band))
    if band_b is None:
        band_b = detect_number_band(args.b, set_b, tuple(args.search_band))

    print(f"detected bands: {args.name_a} {band_a[0]:.4f}-{band_a[1]:.4f}  "
          f"{args.name_b} {band_b[0]:.4f}-{band_b[1]:.4f}")

    with tempfile.TemporaryDirectory() as tmp_name:
        tmp = Path(tmp_name)
        still_a = extract(args.a, band_a, set_a, 1, 1, tmp, "sa", args.xband)[0]
        still_b = extract(args.b, band_b, set_b, 1, 1, tmp, "sb", args.xband)[0]
        ha = digit_height(Image.open(still_a))
        hb = digit_height(Image.open(still_b))
        print(f"settled digit height: {args.name_a} {ha}px  {args.name_b} {hb}px")

        first_a = max(0, args.onset_a - args.lead)
        first_b = max(0, args.onset_b - args.lead)
        frames_a = extract(args.a, band_a, first_a, args.count, args.stride, tmp, "a", args.xband)
        frames_b = extract(args.b, band_b, first_b, args.count, args.stride, tmp, "b", args.xband)
        n = min(len(frames_a), len(frames_b))
        if n == 0:
            sys.exit("no paired frames")

        cell = args.cell_height
        scale_a = cell / max(ha, 1)
        scale_b = cell / max(hb, 1)

        def load(path, scale):
            im = Image.open(path).convert("L")
            return im.resize((max(1, round(im.width * scale)),
                              max(1, round(im.height * scale))), Image.LANCZOS)

        rows_a = [load(p, scale_a) for p in frames_a[:n]]
        rows_b = [load(p, scale_b) for p in frames_b[:n]]

        def window(still, scale, sample):
            b = ink_bounds(load(still, scale))
            if b is None:
                return 0, sample.width
            pad = cell // 2
            return max(0, b[0] - pad), min(sample.width, b[2] + pad)

        xa0, xa1 = window(still_a, scale_a, rows_a[0])
        xb0, xb1 = window(still_b, scale_b, rows_b[0])
        cw = max(xa1 - xa0, xb1 - xb0)
        ch = round(cell * args.cell_span)
        centre = ch // 2

        def baseline(still, scale):
            b = ink_bounds(load(still, scale))
            return (b[1] + b[3]) / 2 if b else load(still, scale).height / 2

        base_a = baseline(still_a, scale_a)
        base_b = baseline(still_b, scale_b)

        def cell_image(im, x0, x1, base):
            crop = im.crop((x0, 0, x1, im.height))
            out = Image.new("L", (cw, ch), 255)
            out.paste(crop, ((cw - crop.width) // 2, round(centre - base)))
            return out

        cells_a = [cell_image(im, xa0, xa1, base_a) for im in rows_a]
        cells_b = [cell_image(im, xb0, xb1, base_b) for im in rows_b]

        if args.video:
            frames_dir = tmp / "stack"
            frames_dir.mkdir()
            for i, (ca, cb) in enumerate(zip(cells_a, cells_b)):
                canvas = Image.new("L", (cw, ch * 2 + 4), 255)
                canvas.paste(ca, (0, 0))
                canvas.paste(cb, (0, ch + 4))
                d = ImageDraw.Draw(canvas)
                d.line([(0, ch + 2), (cw, ch + 2)], fill=180, width=1)
                canvas.save(frames_dir / f"{i:04d}.png")
            fps = 60 / args.stride / args.slow
            subprocess.run(
                ["ffmpeg", "-v", "error", "-y", "-framerate", f"{fps:.4f}",
                 "-i", str(frames_dir / "%04d.png"), "-c:v", "libx264", "-crf", "12",
                 "-pix_fmt", "yuv420p", "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2:0:0:white",
                 args.video], check=True,
            )
            print(f"{n} frames -> {args.video} ({fps:.1f} fps, {args.slow}x slow)")
            return

        gap = 6
        label_h = 26
        grid = Image.new("L", (cw * n + gap * (n - 1), (ch + label_h) * 2 + gap), 255)
        draw = ImageDraw.Draw(grid)
        for i, (ca, cb) in enumerate(zip(cells_a, cells_b)):
            x = i * (cw + gap)
            ms = (i * args.stride - args.lead) * 1000 / 60
            draw.text((x + 4, 6), f"{ms:+.0f}ms", fill=90)
            grid.paste(ca, (x, label_h))
            grid.paste(cb, (x, label_h + ch + label_h + gap))
            draw.text((x + 4, ch + label_h + gap + 6), f"{ms:+.0f}ms", fill=90)
        draw.text((4, label_h - 14), args.name_a, fill=40)
        draw.text((4, label_h + ch + gap + label_h - 14), args.name_b, fill=40)
        out = args.out or "grid.png"
        Path(out).parent.mkdir(parents=True, exist_ok=True)
        grid.save(out)
        print(f"{n} frames -> {out}  ({grid.width}x{grid.height})")


if __name__ == "__main__":
    main()
