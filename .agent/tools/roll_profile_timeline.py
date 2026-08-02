#!/usr/bin/env python3
"""Measure one numeric column at every 60 fps frame and draw its time/space trace.

This complements ``roll_shape_fixed.py``.  That tool is deliberately a shape report; this one
keeps the full timeline so input pulses, the braking point, a ghostless crossing, and one-frame
rendering glitches cannot disappear inside medians.
"""

import argparse
import json
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from roll_shape_fixed import decode_band, edge_energy, profile_stats
from template_fit import background, columns, ink


def percentile_row(rows, fraction):
    total = float(rows.sum())
    if total <= 0:
        return 0.0
    return float(np.searchsorted(np.cumsum(rows) / total, fraction))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video", required=True)
    ap.add_argument("--from", dest="f0", type=int, required=True)
    ap.add_argument("--to", dest="f1", type=int, required=True)
    ap.add_argument("--column", type=int, default=-1)
    ap.add_argument("--settled", type=int, default=-10)
    ap.add_argument("--label", default="")
    ap.add_argument("--json")
    ap.add_argument("--heatmap")
    args = ap.parse_args()

    frames = decode_band(args.video)
    bg = background(frames)
    all_ink = ink(frames, bg)
    settled = all_ink[args.settled]
    groups = columns(settled)
    if not groups:
        raise SystemExit("no settled numeric columns found")
    x0, x1 = groups[args.column]
    ref_patch = settled[:, x0:x1]
    ref_rows = ref_patch.sum(axis=1)
    nonzero = np.nonzero(ref_rows > ref_rows.max() * 0.05)[0]
    ref_height = float(nonzero[-1] - nonzero[0]) if len(nonzero) > 1 else 1.0
    ref_center, _, _ = profile_stats(ref_patch, ref_height)
    ref_ink = float(ref_patch.sum())
    ref_edge = edge_energy(ref_patch)

    lo = max(0, args.f0)
    hi = min(args.f1, len(frames))
    samples = []
    profiles = []
    previous = all_ink[max(0, lo - 1), :, x0:x1]
    for frame in range(lo, hi):
        patch = all_ink[frame, :, x0:x1]
        rows = patch.sum(axis=1)
        center, extent, _ = profile_stats(patch, ref_height)
        samples.append(
            {
                "frame": frame,
                "t": round((frame - args.f0) * 1000.0 / 60.0, 1),
                "ink": round(float(patch.sum()) / ref_ink, 4),
                "edge": round(edge_energy(patch) / ref_edge, 4),
                "extent": round(extent, 4),
                "center": round(center - ref_center, 4),
                "top02": round((percentile_row(rows, 0.02) / ref_height) - ref_center, 4),
                "bottom98": round((percentile_row(rows, 0.98) / ref_height) - ref_center, 4),
                "motion": round(float(np.abs(patch - previous).sum()) / ref_ink, 4),
            }
        )
        profiles.append(rows)
        previous = patch

    motion = np.asarray([sample["motion"] for sample in samples])
    floor = max(0.015, float(np.percentile(motion, 55))) if len(motion) else 0.015
    peaks = []
    for i in range(1, max(1, len(motion) - 1)):
        if motion[i] >= floor and motion[i] >= motion[i - 1] and motion[i] > motion[i + 1]:
            peaks.append(samples[i]["t"])

    result = {
        "video": args.video,
        "label": args.label or args.video.rsplit("/", 1)[-1],
        "onset": args.f0,
        "column": args.column,
        "x": [x0, x1],
        "ref_height": ref_height,
        "motion_peaks_ms": peaks,
        "samples": samples,
    }
    if args.json:
        with open(args.json, "w") as fh:
            json.dump(result, fh, separators=(",", ":"))

    print(f'{result["label"]}: column {args.column}, frames {lo}-{hi - 1}')
    print("motion peaks:", " ".join(f"{v:.0f}" for v in peaks))
    print(" t(ms) motion  ink edge  ext   cen    top    bottom")
    for sample in samples:
        print(
            f'{sample["t"]:6.1f} {sample["motion"]:6.3f} {sample["ink"]:4.2f} '
            f'{sample["edge"]:4.2f} {sample["extent"]:4.2f} {sample["center"]:+6.2f} '
            f'{sample["top02"]:+6.2f} {sample["bottom98"]:+7.2f}'
        )

    if args.heatmap and profiles:
        profile = np.asarray(profiles, dtype=np.float32).T
        scale = np.percentile(profile, 99.5)
        if scale > 0:
            profile = np.clip(profile / scale, 0, 1)
        # Pale paper to dark ink, with each source frame widened enough to inspect at a glance.
        raster = (247.0 - 232.0 * np.power(profile, 0.65)).astype(np.uint8)
        raster = np.repeat(raster, 10, axis=1)
        top = 34
        image = Image.new("L", (raster.shape[1], raster.shape[0] + top), 247)
        image.paste(Image.fromarray(raster), (0, top))
        draw = ImageDraw.Draw(image)
        font = ImageFont.load_default()
        for i, sample in enumerate(samples):
            if i % 6 == 0:
                x = i * 10
                draw.line((x, top - 5, x, top - 1), fill=80)
                draw.text((x + 2, 4), f'{sample["t"]:.0f}', fill=45, font=font)
        image.save(args.heatmap)


if __name__ == "__main__":
    main()
