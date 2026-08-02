#!/usr/bin/env python3
"""Read a run captured by the iOS frame recorder and report it column by column.

The recorder (``NumericTextFrameRecorder`` in ``ios/NumericTextSwiftUIHost.swift``) writes the
SwiftUI reference straight out of its layer: one 8-bit alpha plane per display frame, stamped with
the display link's own clock, plus a JSON describing them. Nothing is compressed, nothing is
resampled onto a frame rate it never had, and the instant each value change landed is recorded
rather than found by hunting for a flash.

That is the whole point of this file: the metrics below are the same ones ``roll_shape_fixed.py``
computes from a screen recording, so the numbers are directly comparable to every REPORT.md already
in ``artifacts/`` — but here they carry no capture error.

Usage:
    python3 .agent/tools/ground_truth.py --run artifacts/<dir>/run-<stamp>
    python3 .agent/tools/ground_truth.py --run <prefix> --contact sheet.png --json out.json
"""

import argparse
import json

import numpy as np


def load(prefix):
    """Return (meta, frames) with frames as a (n, h, w) uint8 array of ink coverage."""
    with open(f"{prefix}.json") as handle:
        meta = json.load(handle)
    count, height, width = meta["frames"], meta["height"], meta["width"]
    planes = np.fromfile(f"{prefix}.bin", dtype=np.uint8)
    expected = count * height * width
    if planes.size != expected:
        raise SystemExit(f"{prefix}.bin holds {planes.size} bytes, expected {expected}")
    return meta, planes.reshape(count, height, width)


def ink_box(frames, threshold=8):
    """The rows and columns any frame of the run puts ink in."""
    columns = np.where(frames.max(axis=(0, 1)) > threshold)[0]
    rows = np.where(frames.max(axis=(0, 2)) > threshold)[0]
    if not len(columns) or not len(rows):
        raise SystemExit("no ink in this run")
    return rows[0], rows[-1] + 1, columns[0], columns[-1] + 1


def columns_of(settled, gap=6, floor=0.02):
    """Split a settled frame into glyph columns on its empty vertical gutters.

    The gutters are real here: this is the reference's own rendering, not a photograph of it, so a
    column boundary is a column of exactly zero ink rather than a threshold judgement call.
    """
    profile = settled.sum(axis=0).astype(np.float64)
    if profile.max() <= 0:
        return []
    inked = profile > profile.max() * floor
    groups, start, run = [], None, 0
    for index, value in enumerate(inked):
        if value:
            if start is None:
                start = index
            run = 0
        elif start is not None:
            run += 1
            if run >= gap:
                groups.append((start, index - run + 1))
                start = None
    if start is not None:
        groups.append((start, len(inked)))
    return groups


def profile_stats(patch, height):
    """Vertical moments of one column's ink, in units of a settled glyph height.

    Deliberately identical to ``roll_shape_fixed.profile_stats`` so the two pipelines can be read
    against each other.
    """
    rows = patch.sum(axis=1).astype(np.float64)
    total = rows.sum()
    if total <= 0:
        return 0.0, 0.0, 0.0, 0.0
    y = np.arange(len(rows), dtype=np.float64)
    centre = float((rows * y).sum() / total)
    cumulative = np.cumsum(rows) / total
    low = float(np.searchsorted(cumulative, 0.05))
    high = float(np.searchsorted(cumulative, 0.95))
    top = float(np.searchsorted(cumulative, 0.02))
    bottom = float(np.searchsorted(cumulative, 0.98))
    return centre / height, (high - low) / height, top / height, bottom / height


def edge_energy(patch):
    """Vertical ink boundary per unit ink. Collapses under a smear, survives a small blur."""
    total = patch.sum()
    if total <= 0:
        return 0.0
    return float(np.abs(np.diff(patch.astype(np.int32), axis=0)).sum() / total)


def measure(meta, frames, settled_index=-1):
    y0, y1, x0, x1 = ink_box(frames)
    window = frames[:, y0:y1, x0:x1].astype(np.float64)
    settled = window[settled_index]
    groups = columns_of(settled)
    if not groups:
        raise SystemExit("no columns found in the settled frame")

    report = {
        "label": meta.get("label"),
        "countsDown": meta.get("countsDown"),
        "scale": meta.get("scale"),
        "marks": meta.get("marks", []),
        "box": [int(x0), int(y0), int(x1 - x0), int(y1 - y0)],
        "columns": [],
    }
    for index, (cx0, cx1) in enumerate(groups):
        reference = settled[:, cx0:cx1]
        rows = reference.sum(axis=1)
        lit = np.nonzero(rows > rows.max() * 0.05)[0]
        glyph_height = float(lit[-1] - lit[0]) if len(lit) > 1 else 1.0
        base_centre, _, _, _ = profile_stats(reference, glyph_height)
        base_ink = float(reference.sum())
        base_edge = edge_energy(reference)

        samples = []
        for frame in range(len(window)):
            patch = window[frame, :, cx0:cx1]
            centre, extent, top, bottom = profile_stats(patch, glyph_height)
            samples.append(
                {
                    "t": round(meta["times"][frame], 2),
                    "ink": round(float(patch.sum()) / base_ink, 4) if base_ink else 0.0,
                    "edge": round(edge_energy(patch) / base_edge, 4) if base_edge else 0.0,
                    "extent": round(extent, 4),
                    "centre": round(centre - base_centre, 4),
                    "top": round(top - base_centre, 4),
                    "bottom": round(bottom - base_centre, 4),
                }
            )
        report["columns"].append(
            {
                "index": index,
                "x": [int(cx0), int(cx1)],
                "glyphHeight": round(glyph_height, 2),
                "samples": samples,
            }
        )
    return report


def contact_sheet(frames, path, stride=2, count=14, threshold=8):
    from PIL import Image

    y0, y1, x0, x1 = ink_box(frames, threshold)
    picks = list(range(0, min(len(frames), stride * count), stride))
    tiles = [255 - frames[i, y0:y1, x0:x1] for i in picks]
    height, width = tiles[0].shape
    sheet = Image.new("L", (width * len(tiles), height), 255)
    for position, tile in enumerate(tiles):
        sheet.paste(Image.fromarray(tile), (position * width, 0))
    sheet = sheet.resize((sheet.width // 3, sheet.height // 3), Image.LANCZOS)
    sheet.save(path)
    return picks


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", required=True, help="path prefix, without .json/.bin")
    parser.add_argument("--json", help="write the full per-frame report here")
    parser.add_argument("--contact", help="write a contact sheet here")
    parser.add_argument("--stride", type=int, default=2)
    parser.add_argument("--rows", type=int, default=30, help="frames to print")
    args = parser.parse_args()

    meta, frames = load(args.run)
    report = measure(meta, frames)

    print(
        f"{meta['label']}  countsDown={meta['countsDown']}  "
        f"{meta['frames']} frames  {meta['width']}x{meta['height']} @{meta['scale']}x  "
        f"{len(report['columns'])} columns"
    )
    marks = ", ".join(f"{m['label']}@{m['t']:.0f}ms" for m in report["marks"])
    print(f"changes: {marks}")
    gaps = np.diff(meta["times"])
    print(f"frame interval: median {np.median(gaps):.2f} ms, max {gaps.max():.2f} ms")
    print()

    header = "   t(ms) " + " ".join(
        f"| c{c['index']}: ink  edge   ext   cen " for c in report["columns"]
    )
    print(header)
    for row in range(0, min(args.rows * args.stride, meta["frames"]), args.stride):
        cells = []
        for column in report["columns"]:
            s = column["samples"][row]
            cells.append(
                f"| {s['ink']:5.2f} {s['edge']:5.2f} {s['extent']:5.2f} {s['centre']:+5.2f}"
            )
        print(f"{report['columns'][0]['samples'][row]['t']:8.1f} " + " ".join(cells))

    if args.json:
        with open(args.json, "w") as handle:
            json.dump(report, handle, indent=1)
        print(f"\nwrote {args.json}")
    if args.contact:
        picks = contact_sheet(frames, args.contact, stride=args.stride)
        stamps = ", ".join(f"{meta['times'][i]:.0f}" for i in picks)
        print(f"wrote {args.contact}  frames at {stamps} ms")


if __name__ == "__main__":
    main()
