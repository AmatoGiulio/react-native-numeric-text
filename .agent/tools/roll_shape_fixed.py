#!/usr/bin/env python3
"""What SHAPE a rolling column is, frame by frame — smear or crossfade.

Every probe before this one measures how much ink a column carries and where that ink sits.
Neither can tell apart the two things a continuous roll can look like, because both can carry the
same ink at the same height:

  * a MOTION SMEAR — one tall grey band with no glyph in it, which is what SwiftUI draws while the
    value is running, and
  * a CROSSFADE — two individually legible digits superimposed, which is what a per-glyph renderer
    draws when it blurs each glyph a little and dissolves between them.

The discriminator is EDGE ENERGY along the roll axis. A settled digit has a fixed amount of
vertical ink boundary per unit ink; smearing it along y destroys that boundary while conserving the
ink, so `Σ|∂I/∂y| / ΣI` collapses. Blur it isotropically by a little and the ratio barely moves.
Normalised against the column's own settled value it reads directly as "how much of a real digit is
on screen": 1.0 is a settled digit, and the reference's roll runs far below it.

Reported per column, over a window of frames:

    ink    total ink, in settled glyphs
    edge   edge energy per unit ink, against the same column settled (1.0 = as crisp as at rest)
    ext    vertical 5-95% span of the ink, in settled glyph heights (1.0 = a digit's own height)
    cen    centroid offset from the settled centroid, in glyph heights (+ = LOWER on screen)
    skew   third standardised moment of the vertical profile (+ = the tail hangs BELOW)

and one summary the eye actually reads: `crisp`, the fraction of frames in which the column holds
something as sharp as a settled digit (edge >= 0.85). A flip-book scores high, a roll scores zero.

Usage:
    python3 roll_shape.py --video roll_ios60.mp4 --platform ios --from 300 --to 340 --label iOS
"""
import argparse
import subprocess
import sys

import numpy as np

sys.path.insert(0, __file__.rsplit('/', 1)[0])
from template_fit import background, ink, columns, locate


def digit_rows(path):
    """Rows of the settled number in SOURCE pixels: the largest contiguous run of text ink."""
    probe = subprocess.run(
        ['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries',
         'stream=width,height', '-of', 'csv=p=0:s=,', path],
        capture_output=True, text=True).stdout.strip().split(',')
    sw, sh = int(probe[0]), int(probe[1])
    # Read a settled frame near the end. Some Android recordings do not support `-sseof`
    # reliably (notably MP4s with sparse/odd timestamps), so try several strategies instead of
    # assuming a reverse seek always returns bytes.
    def grab_last_frame():
        attempts = [
            ['ffmpeg', '-v', 'error', '-sseof', '-1', '-i', path,
             '-vf', 'format=gray', '-frames:v', '1', '-f', 'rawvideo',
             '-pix_fmt', 'gray', '-'],
        ]

        # Fallback 1: seek from an absolute timestamp derived from ffprobe duration.
        duration_probe = subprocess.run(
            ['ffprobe', '-v', 'error', '-show_entries', 'format=duration',
             '-of', 'default=noprint_wrappers=1:nokey=1', path],
            capture_output=True, text=True
        )
        try:
            duration = float(duration_probe.stdout.strip())
        except (TypeError, ValueError):
            duration = 0.0
        if duration > 0.0:
            attempts.append(
                ['ffmpeg', '-v', 'error', '-ss', f'{max(0.0, duration - 1.0):.6f}',
                 '-i', path, '-vf', 'format=gray', '-frames:v', '1',
                 '-f', 'rawvideo', '-pix_fmt', 'gray', '-']
            )

        for cmd in attempts:
            r = subprocess.run(cmd, capture_output=True)
            if r.returncode == 0 and len(r.stdout) >= sw * sh:
                return r.stdout[:sw * sh]

        # Final fallback: decode all frames and keep the last complete one. This avoids depending
        # on container timestamps entirely.
        r = subprocess.run(
            ['ffmpeg', '-v', 'error', '-i', path, '-vf', 'format=gray',
             '-f', 'rawvideo', '-pix_fmt', 'gray', '-'],
            capture_output=True
        )
        frame_bytes = sw * sh
        n = len(r.stdout) // frame_bytes
        if r.returncode == 0 and n > 0:
            start = (n - 1) * frame_bytes
            return r.stdout[start:start + frame_bytes]
        raise RuntimeError(
            f'cannot decode a settled frame from {path}; ffmpeg returned no complete frame'
        )

    raw = grab_last_frame()
    frame = np.frombuffer(raw, dtype=np.uint8).reshape(sh, sw).astype(np.float32)
    lo, hi = int(sh * 0.18), int(sh * 0.50)
    bgv = float(np.median(frame[lo:hi, :20]))
    ink_ = np.clip(bgv - frame[lo:hi], 0, None)
    ink_[ink_ < 80] = 0
    rows = ink_.sum(axis=1)
    on = rows > rows.max() * 0.03
    gap = max(2, int(sh * 0.01))
    runs, s, blank = [], None, 0
    for i, v in enumerate(on):
        if v:
            if s is None:
                s = i
            blank = 0
        elif s is not None:
            blank += 1
            if blank > gap:
                runs.append((s, i - blank))
                s = None
    if s is not None:
        runs.append((s, len(on) - 1))
    s, e = max(runs, key=lambda r: rows[r[0]:r[1] + 1].sum())
    return sw, sh, lo + s, lo + e


def decode_band(path, pad=0.35):
    """
    Decode a window centred on the digits, with headroom on BOTH sides.

    `template_fit.locate` deliberately reproduces the hand-fitted crop, which starts at the digits'
    top: it has room below a glyph and none above it, which is fine for a fit that searches ±0.54
    line-heights but useless for asking how far a smear reaches upward. Its lower edge also reaches
    far enough down to catch the top of the +/− buttons, and their ink reads as a roll extending 1.4
    glyph heights below the line — on both platforms equally, which is what makes it look like a
    measurement rather than a bug.

    So this takes the digit block itself and pads it symmetrically. The pad is capped at 0.35 of the
    block for a reason: on both layouts the +/− buttons sit 0.44 of one below the number, and any
    wider window puts their ink back in. That caps the excursion this can report at ~0.5 glyph
    heights, which is well past anything either platform draws.

    No scaling: every quantity here is normalised against the same column at rest, so source pixels
    are the natural unit and the two platforms' different glyph sizes cancel.
    """
    sw, sh, top, bot = digit_rows(path)
    dh = float(bot - top)
    # EVEN geometry, all four numbers. The source is yuv420p, so `crop` silently rounds an odd
    # width down to the next even one while the reshape here still assumes the odd one — every row
    # then lands one pixel further left than the last and the crop reads as a skewed, torn raster
    # that still decodes, still produces columns, and still returns numbers.
    even = lambda v: int(v) - (int(v) % 2)
    y0 = even(max(0, top - dh * pad))
    h = even(min(sh - y0, dh * (1 + 2 * pad)))
    x0, w = even(sw * 0.03), even(sw * 0.94)
    vf = f'fps=60,crop={w}:{h}:{x0}:{y0},format=gray'
    raw = subprocess.run(
        ['ffmpeg', '-v', 'error', '-i', path, '-vf', vf, '-f', 'rawvideo', '-pix_fmt', 'gray', '-'],
        capture_output=True).stdout
    n = len(raw) // (w * h)
    return np.frombuffer(raw[:n * w * h], dtype=np.uint8).reshape(n, h, w)


def profile_stats(patch, height):
    """Vertical moments of one column's ink patch, in units of a settled glyph height."""
    rows = patch.sum(axis=1)
    tot = rows.sum()
    if tot <= 0:
        return 0.0, 0.0, 0.0
    y = np.arange(len(rows), dtype=np.float32)
    cen = float((rows * y).sum() / tot)
    var = float((rows * (y - cen) ** 2).sum() / tot)
    sd = np.sqrt(max(var, 1e-6))
    skew = float((rows * (y - cen) ** 3).sum() / tot / sd ** 3)
    # 5-95% span of the cumulative profile: robust to the halo a neighbour's blur leaves behind.
    c = np.cumsum(rows) / tot
    lo = float(np.searchsorted(c, 0.05))
    hi = float(np.searchsorted(c, 0.95))
    return cen / height, (hi - lo) / height, skew


def edge_energy(patch):
    """Vertical ink boundary per unit ink. Collapses under a smear, survives a small blur."""
    tot = patch.sum()
    if tot <= 0:
        return 0.0
    return float(np.abs(np.diff(patch, axis=0)).sum() / tot)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--video', required=True)
    ap.add_argument('--platform', required=True, choices=['ios', 'android'])
    ap.add_argument('--from', dest='f0', type=int, required=True)
    ap.add_argument('--to', dest='f1', type=int, required=True)
    ap.add_argument('--settled', type=int, default=-10,
                    help='frame the settled reference comes from (negative = from the end)')
    ap.add_argument('--label', default=None)
    ap.add_argument('--per-frame', action='store_true')
    a = ap.parse_args()

    frames = decode_band(a.video)
    bg = background(frames)
    si = ink(frames[a.settled][None], bg)[0]
    cols = columns(si)
    if not cols:
        raise SystemExit('no columns in the settled frame')

    # Each column is normalised against ITSELF at rest, so nothing depends on the two platforms
    # drawing the same size, the same weight, or the same page grey.
    ref = []
    for x0, x1 in cols:
        p = si[:, x0:x1]
        rows = p.sum(axis=1)
        nz = np.nonzero(rows > rows.max() * 0.05)[0]
        h = float(nz[-1] - nz[0]) if len(nz) > 1 else 1.0
        cen, ext, _ = profile_stats(p, h)
        ref.append({'ink': float(p.sum()), 'h': h, 'top': float(nz[0]), 'bot': float(nz[-1]),
                    'cen': cen, 'ext': ext, 'edge': edge_energy(p)})

    label = a.label or a.video.rsplit('/', 1)[-1]
    print(f'\n{label}   frames {a.f0}-{a.f1}   {len(cols)} columns '
          f'(left to right, settled digit {ref[0]["h"]:.0f}px tall)')
    print(f'{"col":>4} {"ink":>16} {"edge":>16} {"ext":>16} {"cen":>16} '
          f'{"up":>7} {"dn":>7} {"crisp":>7}')

    for ci, (x0, x1) in enumerate(cols):
        vals = {k: [] for k in ('ink', 'edge', 'ext', 'cen', 'skew', 'up', 'dn')}
        for f in range(a.f0, min(a.f1, len(frames))):
            p = ink(frames[f][None], bg)[0][:, x0:x1]
            cen, ext, skew = profile_stats(p, ref[ci]['h'])
            vals['ink'].append(p.sum() / ref[ci]['ink'])
            vals['edge'].append(edge_energy(p) / ref[ci]['edge'])
            vals['ext'].append(ext)
            vals['cen'].append(cen - ref[ci]['cen'])
            vals['skew'].append(skew)
            # How far the ink reaches past the settled glyph's own box, up and down, in glyph
            # heights. This is the "the digit climbs out of the line" the reports are about, and
            # its sign is the direction the mass is being pulled.
            rows = p.sum(axis=1)
            tot = rows.sum()
            if tot > 0:
                c = np.cumsum(rows) / tot
                vals['up'].append((ref[ci]['top'] - float(np.searchsorted(c, 0.02)))
                                  / ref[ci]['h'])
                vals['dn'].append((float(np.searchsorted(c, 0.98)) - ref[ci]['bot'])
                                  / ref[ci]['h'])
            else:
                vals['up'].append(0.0)
                vals['dn'].append(0.0)
        v = {k: np.array(x) for k, x in vals.items()}
        crisp = float((v['edge'] >= 0.85).mean())

        def band(k, fmt='{:.2f}'):
            return (f'{fmt.format(np.median(v[k]))} '
                    f'[{fmt.format(np.percentile(v[k], 10))},'
                    f'{fmt.format(np.percentile(v[k], 90))}]')

        print(f'{ci:>4} {band("ink"):>16} {band("edge"):>16} {band("ext"):>16} '
              f'{band("cen", "{:+.2f}"):>16} {np.median(v["up"]):>+7.2f} '
              f'{np.median(v["dn"]):>+7.2f} {crisp:>7.2f}')

        if a.per_frame:
            for i, f in enumerate(range(a.f0, min(a.f1, len(frames)))):
                print(f'      f{f:<5} t{(f - a.f0) * 1000 // 60:>5}ms  ink {v["ink"][i]:.2f}  '
                      f'edge {v["edge"][i]:.2f}  ext {v["ext"][i]:.2f}  cen {v["cen"][i]:+.2f}')


if __name__ == '__main__':
    main()
