#!/usr/bin/env python3
"""Find every transition in a recording and measure it, without being told where they are.

The parity work so far has measured one named transition at a time, which means every comparison
started by hand-locating the same event in two videos — the step that quietly went wrong most
often (a −ss seek landing on a different frame, a label contaminating the band, a run compared
against a differently-paced one). This finds the events itself: it reads one band of the frame,
scores per-frame motion against the previous frame, and reports each contiguous run of motion as
an event with its onset, its duration, and the shape of its settle.

What it gives per event, all in units that survive a font change (fractions of the band's own
settled ink, and of the glyph height):

    onset       frame index of the first moving frame
    dur_ms      onset until motion falls under the threshold and stays under it
    peak_ms     onset until the frame of maximum motion (how front-loaded the roll is)
    cols        which horizontal columns moved, so a cascade can be seen as a left→right order
    lag_ms      per-column onset relative to the event's first column (the cascade)
    excursion   how far the band's ink centroid travelled vertically, over glyph height
    overshoot   ink centroid past its settled position at the end, over glyph height
    wobble      high-frequency component of the centroid after the peak (trembling)
    dark_min    lowest total ink during the event, over settled ink (how faint it gets)

Usage:
    python3 event_timeline.py --video ios60.mp4 --band 0.34 0.56 --out ios.json
    python3 event_timeline.py --video ios60.mp4 --band 0.34 0.56 --out ios.json --columns 7
"""
import argparse
import json
import subprocess
import sys

import numpy as np

# A frame counts as moving when its mean absolute difference from the previous frame exceeds this
# fraction of the settled band's own mean ink. Low enough to catch a fade's tail, high enough that
# video compression noise on a still frame does not register.
MOTION_THRESHOLD = 0.012

# Motion must stay under the threshold for this long before an event is called finished — a roll's
# settle passes briefly through zero between its overshoot and its return.
QUIET_FRAMES = 6


def decode_band(video, y0, y1, width=None):
    """Every frame's band as a float array [t, h, w], 0 = paper, 1 = ink."""
    probe = subprocess.run(
        [
            "ffprobe", "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=width,height", "-of", "csv=p=0", video,
        ],
        capture_output=True, text=True, check=True,
    )
    vw, vh = (int(v) for v in probe.stdout.strip().split(","))

    top = int(round(y0 * vh))
    height = int(round((y1 - y0) * vh))

    # The output size is computed here rather than left to scale=-1, so the raw byte stream can be
    # reshaped without guessing: a wrong guess silently shears every frame.
    bw, bh = (vw, height) if not width else (width, max(2, round(height * width / vw)))
    scale = "" if not width else f",scale={bw}:{bh}"

    proc = subprocess.run(
        [
            "ffmpeg", "-v", "error", "-i", video,
            "-vf", f"crop={vw}:{height}:0:{top},format=gray{scale}",
            "-f", "rawvideo", "-pix_fmt", "gray", "-",
        ],
        capture_output=True, check=True,
    )
    frames = len(proc.stdout) // (bw * bh)
    if frames == 0:
        sys.exit("no frames decoded — check --band")
    data = np.frombuffer(proc.stdout[: frames * bw * bh], dtype=np.uint8)
    band = data.reshape(frames, bh, bw).astype(np.float32) / 255.0
    return 1.0 - band  # ink, not light


def settled_reference(ink, moving):
    """Mean ink of the frames that are not moving — the yardstick every ratio below uses."""
    still = ink[~moving]
    if len(still) == 0:
        return float(ink.mean())
    return float(still.mean())


def centroid_y(frame_ink):
    """Vertical ink centroid of one frame, in rows. NaN when the band is empty."""
    total = frame_ink.sum()
    if total <= 0:
        return np.nan
    rows = np.arange(frame_ink.shape[0], dtype=np.float32)
    return float((frame_ink.sum(axis=1) * rows).sum() / total)


def find_events(motion, threshold, quiet):
    """Contiguous runs of motion, each extended until it has been quiet for `quiet` frames."""
    events = []
    i = 0
    n = len(motion)
    while i < n:
        if motion[i] <= threshold:
            i += 1
            continue
        start = i
        last_moving = i
        j = i
        while j < n and (j - last_moving) <= quiet:
            if motion[j] > threshold:
                last_moving = j
            j += 1
        events.append((start, last_moving))
        i = j
    return events


def column_onsets(ink, start, end, ncols, threshold):
    """First moving frame of each horizontal column inside one event, relative to the event."""
    band = ink[max(0, start - 1) : end + 2]
    if len(band) < 2:
        return {}
    edges = np.linspace(0, band.shape[2], ncols + 1).astype(int)
    onsets = {}
    for c in range(ncols):
        col = band[:, :, edges[c] : edges[c + 1]]
        if col.shape[2] == 0:
            continue
        diff = np.abs(np.diff(col, axis=0)).mean(axis=(1, 2))
        # Each column against its own settled ink, so a pale column is not called still.
        ref = max(col.mean(), 1e-6)
        hits = np.nonzero(diff > threshold * ref)[0]
        if len(hits):
            onsets[c] = int(hits[0])
    return onsets


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video", required=True)
    ap.add_argument(
        "--band", nargs=2, type=float, required=True, metavar=("Y0", "Y1"),
        help="vertical slice of the frame holding the number, as fractions of frame height",
    )
    ap.add_argument("--fps", type=float, default=60.0)
    ap.add_argument("--columns", type=int, default=7)
    ap.add_argument("--width", type=int, default=None, help="downscale the band to this width")
    ap.add_argument("--threshold", type=float, default=MOTION_THRESHOLD)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    ink = decode_band(args.video, args.band[0], args.band[1], args.width)
    n, h, w = ink.shape

    diff = np.abs(np.diff(ink, axis=0)).mean(axis=(1, 2))
    motion = np.concatenate([[0.0], diff])

    total = ink.sum(axis=(1, 2))
    cy = np.array([centroid_y(f) for f in ink])

    # Two passes: a provisional threshold to find the still frames, then the real yardstick.
    rough = motion > args.threshold * max(ink.mean(), 1e-6)
    ref_ink = settled_reference(ink.mean(axis=(1, 2)), rough)
    thr = args.threshold * ref_ink

    events = []
    for start, end in find_events(motion, thr, QUIET_FRAMES):
        span = slice(start, end + 1)
        peak = int(start + np.argmax(motion[span]))
        settled_cy = cy[min(end + QUIET_FRAMES, n - 1)]
        travel = cy[span]
        travel = travel[~np.isnan(travel)]
        excursion = float(np.nanmax(np.abs(travel - settled_cy))) if len(travel) else 0.0
        after = cy[peak : min(end + QUIET_FRAMES, n - 1)]
        after = after[~np.isnan(after)]
        wobble = float(np.abs(np.diff(np.diff(after))).mean()) if len(after) > 2 else 0.0
        onsets = column_onsets(ink, start, end, args.columns, args.threshold)
        first = min(onsets.values()) if onsets else 0
        events.append(
            {
                "onset": start,
                "onset_s": round(start / args.fps, 3),
                "end": end,
                "dur_ms": round((end - start) * 1000 / args.fps, 1),
                "peak_ms": round((peak - start) * 1000 / args.fps, 1),
                "cols": sorted(onsets),
                "lag_ms": {
                    str(c): round((onsets[c] - first) * 1000 / args.fps, 1)
                    for c in sorted(onsets)
                },
                "excursion": round(excursion / h, 4),
                "overshoot": round(float(abs(cy[end] - settled_cy)) / h, 4),
                "wobble": round(wobble / h, 5),
                "dark_min": round(float(total[span].min() / (ref_ink * h * w)), 4),
                "peak_motion": round(float(motion[span].max() / ref_ink), 4),
            }
        )

    result = {
        "video": args.video,
        "frames": n,
        "band_px": [h, w],
        "fps": args.fps,
        "settled_ink": round(ref_ink, 6),
        "threshold": round(thr, 6),
        "events": events,
    }
    text = json.dumps(result, indent=2)
    if args.out:
        with open(args.out, "w") as fh:
            fh.write(text)
    print(text if not args.out else f"{len(events)} events -> {args.out}")


if __name__ == "__main__":
    main()
