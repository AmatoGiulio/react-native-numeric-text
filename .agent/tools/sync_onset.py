#!/usr/bin/env python3
"""Find the frame a transition actually started on, from the app's own sync marker.

Every onset in this project used to be inferred from motion, and inside a run of changes that
reliably finds the tail of the PREVIOUS transition rather than the start of this one. It invalidated
several measurements before it was caught, twice by producing numbers that looked plausible enough
to act on.

The Showcase's preset button removes the inference: it sets the new value and turns a black bar on
in the same React commit, so the first frame where the bar is dark is the frame the native view
received the value. This reads that bar back.

The bar is NOT looked for at a fixed place. It sits at `top: 96` in density-independent points, and
96 dp lands at a different fraction of the frame on every device — 0.104 on a 1080x2400 Android
screen, 0.110 on a 1206x2622 iPhone. Assuming a fraction is how the first version of this silently
found nothing. Instead every horizontal strip of the upper part of the frame is scanned, and the
marker is identified by behaviour: a contiguous run of rows that goes dark together and stays dark
for the marker's own duration. A dark UI element that is merely present, or one that lingers for the
wrong length of time, is rejected rather than mistaken for the marker.

Usage:
    python3 sync_onset.py --video ios.mp4
    python3 sync_onset.py --video and.mp4 --quiet        # prints only the frame number
"""
import argparse
import subprocess
import sys

import numpy as np

ROWS = 160          # row bins over the searched region; ~15 px each on a 2400 px screen
COLS = 32


def probe(video):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=width,height", "-of", "csv=p=0", video],
        capture_output=True, text=True, check=True,
    ).stdout.strip().split(",")
    return int(out[0]), int(out[1])


def row_series(video, search_to):
    """Mean luminance per (frame, row-bin) over the top `search_to` of the frame. 0 = black."""
    vw, vh = probe(video)
    h = int(round(search_to * vh))
    p = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", video,
         "-vf", f"crop={vw}:{h}:0:0,format=gray,scale={COLS}:{ROWS}",
         "-f", "rawvideo", "-pix_fmt", "gray", "-"],
        capture_output=True, check=True,
    )
    n = len(p.stdout) // (COLS * ROWS)
    if n == 0:
        sys.exit("no frames decoded")
    a = np.frombuffer(p.stdout[: n * COLS * ROWS], dtype=np.uint8).reshape(n, ROWS, COLS)
    return a.astype(np.float32).mean(axis=2) / 255.0, vh, h


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video", required=True)
    ap.add_argument("--fps", type=float, default=60.0)
    ap.add_argument("--search-to", type=float, default=0.45,
                    help="fraction of frame height to search for the bar")
    ap.add_argument("--dark", type=float, default=0.35)
    ap.add_argument("--min-rows", type=int, default=3,
                    help="a real bar spans at least this many row bins")
    ap.add_argument("--expect-dark-ms", type=float, default=400.0)
    ap.add_argument("--tolerance", type=float, default=0.4,
                    help="fractional slack on the expected dark duration")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    rows, vh, searched = row_series(args.video, args.search_to)
    dark = rows < args.dark                     # [frames, ROWS]
    n_dark = dark.sum(axis=1)

    expected = args.expect_dark_ms * args.fps / 1000.0
    lo, hi = expected * (1 - args.tolerance), expected * (1 + args.tolerance)

    onset = None
    i = 1
    while i < len(n_dark):
        if n_dark[i] >= args.min_rows and n_dark[i - 1] < args.min_rows:
            run = 0
            while i + run < len(n_dark) and n_dark[i + run] >= args.min_rows:
                run += 1
            ok = lo <= run <= hi
            if not args.quiet:
                band = np.nonzero(dark[i])[0]
                y0 = band.min() / ROWS * searched / vh
                y1 = (band.max() + 1) / ROWS * searched / vh
                print(
                    f"  candidate frame {i:5d} ({i / args.fps:6.3f}s)  "
                    f"rows y={y0:.3f}-{y1:.3f}  dark {run:3d} frames "
                    f"({run * 1000 / args.fps:5.0f} ms)  "
                    f"{'ok' if ok else 'REJECTED (wrong duration for the marker)'}"
                )
            if ok and onset is None:
                onset = i
            i += max(run, 1)
        else:
            i += 1

    if onset is None:
        sys.exit(
            "no sync marker found. Either the recording has no preset press, or the marker's "
            "duration no longer matches --expect-dark-ms (SYNC_FLASH_MS in Showcase.tsx)."
        )
    if not args.quiet:
        print()
    print(onset)


if __name__ == "__main__":
    main()
