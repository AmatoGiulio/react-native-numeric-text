#!/usr/bin/env python3
"""How much ink a CONTINUOUS roll carries — the press-and-hold case.

The per-glyph template fit does not work here. It needs a settled "before" and a settled "after" to
build its two templates from, and during a hold there is neither: the column never stops changing.

What can be measured is cruder and is the thing a viewer actually complains about — how dark the
rolling column is, moment to moment, as a fraction of a settled digit. A roll that reads as "the
digit disappeared" is one where this number collapses. The reference sits around 0.6.

Method: take the rightmost column's box from a settled frame at the END of the recording (the roll
has stopped by then), measure that frame's ink inside the box as the 1.0 reference, then report the
distribution of the same box's ink over the frames of the hold itself.

Usage:
    python3 hold_ink.py --video hold.mp4 --platform android --from 60 --to 300
"""
import argparse
import numpy as np
import sys

sys.path.insert(0, __file__.rsplit('/', 1)[0])
from template_fit import decode, background, ink, columns


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--video', required=True)
    ap.add_argument('--platform', required=True, choices=['ios', 'android'])
    ap.add_argument('--from', dest='f0', type=int, required=True)
    ap.add_argument('--to', dest='f1', type=int, required=True)
    ap.add_argument('--column', type=int, default=-1)
    ap.add_argument('--settled', type=int, default=-12,
                    help='frame to take the settled reference from (negative = from the end)')
    ap.add_argument('--label', default=None)
    a = ap.parse_args()

    frames = decode(a.video, a.platform)
    bg = background(frames)
    settled = frames[a.settled] if a.settled < 0 else frames[a.settled]
    si = ink(settled[None], bg)[0]
    cols = columns(si)
    if not cols:
        raise SystemExit('no columns found in the settled frame')
    x0, x1 = cols[a.column]
    ref = float(si[:, x0:x1].sum())

    vals = []
    for f in range(a.f0, min(a.f1, len(frames))):
        fi = ink(frames[f][None], bg)[0]
        vals.append(float(fi[:, x0:x1].sum()) / ref)
    v = np.array(vals)
    label = a.label or a.video.rsplit('/', 1)[-1]
    print(f'{label:>28}  median {np.median(v):.2f}  p10 {np.percentile(v, 10):.2f}  '
          f'p90 {np.percentile(v, 90):.2f}  min {v.min():.2f}  ({len(v)} frames)')


if __name__ == '__main__':
    main()
