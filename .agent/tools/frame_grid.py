#!/usr/bin/env python3
"""Put the same transition from two recordings one above the other, frame by frame.

The numeric tables answer questions you already know to ask. This answers the other kind — "the
rotation is the same but something is off" — by showing the two rolls stacked at matched glyph
height, one column per frame, so a digit sitting too high or arriving too early is visible rather
than inferred.

Matching the two 1:1 is the whole difficulty, and it is not a matter of resizing to the same pixel
width: the recordings differ in screen resolution AND in font (SF Rounded vs the bundled Sunghyun
Sans). So the scale is taken from the thing being compared — the **settled digit height**, measured
from a still frame's ink bounding box on each side — and both crops are resampled until those match.
After that a vertical offset in the grid means a vertical offset in the animation.

Usage:
    # one transition, every 2nd frame, 14 columns
    python3 frame_grid.py --a ios60.mp4 --b and.mp4 --band 0.36 0.53 \
        --onset-a 869 --onset-b 936 --count 14 --stride 2 --out roll.png

    # a stacked video of the number only, slowed 4x
    python3 frame_grid.py --a ios60.mp4 --b and.mp4 --band 0.36 0.53 \
        --onset-a 869 --onset-b 936 --count 120 --video roll.mp4 --slow 4
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


def extract(video, band, first, count, stride, outdir, tag, xband=(0.0, 1.0)):
    """Frames [first, first+count*stride) of the band, as PNGs. Returns them in order.

    `xband` matters more than it looks: the dev-client's floating Tools button sits inside the
    number's vertical band on both platforms, and leaving it in makes the measured digit height the
    whole band's height, which silently destroys the 1:1 scaling.
    """
    vw, vh = probe_size(video)
    top = int(round(band[0] * vh))
    height = int(round((band[1] - band[0]) * vh))
    left = int(round(xband[0] * vw))
    width = int(round((xband[1] - xband[0]) * vw))
    last = first + count * stride
    sel = f"between(n\\,{first}\\,{last})"
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", video,
         "-vf", f"select='{sel}',crop={width}:{height}:{left}:{top}",
         "-vsync", "0", "-start_number", "0", str(outdir / f"{tag}_%04d.png")],
        check=True,
    )
    files = sorted(outdir.glob(f"{tag}_*.png"))
    if not files:
        sys.exit(f"no frames extracted from {video} at {first}")
    return [files[i] for i in range(0, min(len(files), count * stride), stride)][:count]


def digit_height(image):
    """Height of the settled ink in one frame, in pixels — the yardstick for matching scale."""
    ink = 255 - np.asarray(image.convert("L"), dtype=np.int16)
    rows = np.nonzero((ink > 60).any(axis=1))[0]
    return int(rows[-1] - rows[0] + 1) if len(rows) else image.height


def ink_bounds(image, threshold=60):
    """Bounding box of the ink, so both sides can be cropped to their own number."""
    ink = 255 - np.asarray(image.convert("L"), dtype=np.int16)
    mask = ink > threshold
    rows = np.nonzero(mask.any(axis=1))[0]
    cols = np.nonzero(mask.any(axis=0))[0]
    if not len(rows) or not len(cols):
        return None
    return int(cols[0]), int(rows[0]), int(cols[-1]) + 1, int(rows[-1]) + 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--a", required=True, help="reference video (drawn on top)")
    ap.add_argument("--b", required=True, help="candidate video (drawn below)")
    ap.add_argument("--name-a", default="iOS")
    ap.add_argument("--name-b", default="Android")
    ap.add_argument("--band", nargs=2, type=float, required=True, metavar=("Y0", "Y1"))
    ap.add_argument("--xband", nargs=2, type=float, default=[0.0, 0.80], metavar=("X0", "X1"),
                    help="horizontal slice to keep; the default excludes the dev Tools button")
    ap.add_argument("--onset-a", type=int, required=True)
    ap.add_argument("--onset-b", type=int, required=True)
    ap.add_argument("--lead", type=int, default=2, help="frames of stillness before the onset")
    ap.add_argument("--count", type=int, default=14)
    ap.add_argument("--stride", type=int, default=2)
    ap.add_argument("--settled-a", type=int, default=None,
                    help="frame index of a settled number on A (default: onset-a − 8)")
    ap.add_argument("--settled-b", type=int, default=None)
    ap.add_argument("--cell-height", type=int, default=150, help="glyph box height per row, px")
    ap.add_argument("--cell-span", type=float, default=2.2,
                    help="cell height in glyph heights; both rows use this, registered on the "
                         "settled baseline, so a vertical offset in the grid is a real one")
    ap.add_argument("--out", default=None, help="grid PNG")
    ap.add_argument("--video", default=None, help="stacked MP4 instead of a grid")
    ap.add_argument("--slow", type=float, default=1.0, help="video slowdown factor")
    ap.add_argument("--title", default="")
    args = ap.parse_args()

    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)

        # A settled frame from each side, purely to measure the digit height.
        set_a = args.settled_a if args.settled_a is not None else max(0, args.onset_a - 8)
        set_b = args.settled_b if args.settled_b is not None else max(0, args.onset_b - 8)
        still_a = extract(args.a, args.band, set_a, 1, 1, tmp, "sa", args.xband)[0]
        still_b = extract(args.b, args.band, set_b, 1, 1, tmp, "sb", args.xband)[0]
        ha = digit_height(Image.open(still_a))
        hb = digit_height(Image.open(still_b))
        print(f"settled digit height: {args.name_a} {ha}px  {args.name_b} {hb}px")

        first_a = max(0, args.onset_a - args.lead)
        first_b = max(0, args.onset_b - args.lead)
        frames_a = extract(args.a, args.band, first_a, args.count, args.stride, tmp, "a", args.xband)
        frames_b = extract(args.b, args.band, first_b, args.count, args.stride, tmp, "b", args.xband)
        n = min(len(frames_a), len(frames_b))

        # Both rows are scaled so their settled digit is `cell-height` tall: after this the rows are
        # in the same units and a difference in the grid is a difference in the animation.
        cell = args.cell_height
        scale_a = cell / ha
        scale_b = cell / hb

        def load(path, scale):
            im = Image.open(path).convert("L")
            return im.resize(
                (max(1, round(im.width * scale)), max(1, round(im.height * scale))),
                Image.LANCZOS,
            )

        rows_a = [load(p, scale_a) for p in frames_a[:n]]
        rows_b = [load(p, scale_b) for p in frames_b[:n]]

        # Crop both to the horizontal extent of their own settled number, plus room for drift, so
        # the cells are the number rather than the whole screen width.
        def window(still, scale, sample):
            b = ink_bounds(load(still, scale))
            if b is None:
                return 0, sample.width
            pad = cell // 2
            return max(0, b[0] - pad), min(sample.width, b[2] + pad)

        xa0, xa1 = window(still_a, scale_a, rows_a[0])
        xb0, xb1 = window(still_b, scale_b, rows_b[0])
        wa, wb = xa1 - xa0, xb1 - xb0
        cw = max(wa, wb)

        # Vertical registration. Without this the two rows cannot be read against each other at all:
        # the crop is a fraction of each screen's height, so after scaling to matched glyph height
        # the two bands cover a different number of glyph heights and sit at a different offset
        # inside their cell. A digit that looked high was an artefact of the framing, not the
        # animation. Both rows are therefore cut to the same box, measured in glyph heights, with
        # the SETTLED digit's centre at the same place in it.
        ch = round(cell * args.cell_span)
        centre = ch // 2

        def baseline(still, scale):
            b = ink_bounds(load(still, scale))
            return (b[1] + b[3]) / 2 if b else load(still, scale).height / 2

        base_a = baseline(still_a, scale_a)
        base_b = baseline(still_b, scale_b)

        def cell_image(im, x0, x1, base):
            """The number's window, centred horizontally, registered vertically on the baseline."""
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
                 "-i", str(frames_dir / "%04d.png"),
                 "-c:v", "libx264", "-crf", "12", "-pix_fmt", "yuv420p",
                 "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2:0:0:white", args.video],
                check=True,
            )
            print(f"{n} frames -> {args.video} ({fps:.1f} fps, {args.slow}x slow)")
            return

        gap = 6
        label = 26
        grid = Image.new("L", (cw * n + gap * (n - 1), (ch + label) * 2 + gap), 255)
        draw = ImageDraw.Draw(grid)
        for i, (ca, cb) in enumerate(zip(cells_a, cells_b)):
            x = i * (cw + gap)
            ms = (i * args.stride - args.lead) * 1000 / 60
            draw.text((x + 4, 6), f"{ms:+.0f}ms", fill=90)
            grid.paste(ca, (x, label))
            grid.paste(cb, (x, label + ch + label + gap))
            draw.text((x + 4, ch + label + gap + 6), f"{ms:+.0f}ms", fill=90)
        draw.text((4, label - 14), args.name_a, fill=40)
        draw.text((4, label + ch + gap + label - 14), args.name_b, fill=40)
        out = args.out or "grid.png"
        grid.save(out)
        print(f"{n} frames -> {out}  ({grid.width}x{grid.height})")


if __name__ == "__main__":
    main()
