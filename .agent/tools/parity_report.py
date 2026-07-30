#!/usr/bin/env python3
"""Average several recordings of the SAME build and compare them to the iOS reference.

Why this exists: two captures of one unchanged APK disagree by 30-40 ms on when a column's ink
crosses half — the preset's tap lands anywhere inside a frame, the emulator jitters, and the sync
marker resolves the onset only to the frame the React commit was painted in. A single recording
therefore cannot tell a 40 ms improvement from noise, and three separate tuning conclusions were
drawn from single runs before that was noticed.

So: fit every run, then report the MEDIAN across runs of the two numbers that decide parity, per
column —

  half_out  when the outgoing glyph crosses half its settled ink
  half_in   when the incoming glyph crosses half of its own
  floor     the lowest summed ink (outgoing + incoming) the column ever carries
  fall      how long the outgoing glyph takes to go from 0.9 to 0.1 of its ink
  rise      the same for the incoming glyph, 0.1 to 0.9

`floor` is the one that matches what a viewer reports: it is how close the column comes to holding
no whole digit at all. The reference never goes under ~0.55.

Usage:
    python3 parity_report.py --ios ios60.mp4 --ios-onset 445 \
        --runs a_60.mp4:203 b_60.mp4:199 c_60.mp4:212
"""
import argparse
import sys
import numpy as np

sys.path.insert(0, __file__.rsplit('/', 1)[0])
from template_fit import decode, background, fit, FPS


def cross(t, a, level, rising):
    """Linear interpolation of the first crossing of `level`, in ms. NaN if it never happens."""
    for i in range(1, len(a)):
        lo, hi = (a[i - 1], a[i]) if rising else (a[i], a[i - 1])
        if (a[i] >= level > a[i - 1]) if rising else (a[i] <= level < a[i - 1]):
            span = a[i] - a[i - 1]
            f = 0.0 if span == 0 else (level - a[i - 1]) / span
            return t[i - 1] + f * (t[i] - t[i - 1])
    return float('nan')


def measure(video, onset, platform, columns, span):
    frames = decode(video, platform)
    bg = background(frames)
    out = {}
    for c in columns:
        r = fit(frames, onset, c, span, bg=bg, verbose=False)
        t, ao, an = r['t'], r['a_old'], r['a_new']
        # Settled ink is 1.0 by construction of the fit, so the sum is directly "glyphs of ink".
        # Only look for the floor after the outgoing glyph has actually begun to go, or the
        # untouched frames before the change would always win.
        started = np.nonzero(ao < 0.9)[0]
        lo = started[0] if len(started) else 0
        hi = min(len(t), lo + int(0.45 * FPS))
        # Peak softness, but only over frames where the glyph carries enough ink to be read as a
        # digit. Below that the fit's σ is describing a smear nobody can see, and it saturates.
        def peak_sigma(a, s):
            m = a > 0.3
            return float(s[m].max()) if m.any() else float('nan')

        # How far APART the two glyphs are while both are actually on screen. This is what decides
        # whether a crossing reads as one digit above another or as a single smudge: two glyphs at
        # the same height with half the ink each is a blur, the same two 0.37 line-heights apart is
        # a roll. Measured over frames where both carry enough ink to be seen.
        both = (ao > 0.15) & (an > 0.15)
        sep = float((r['dy_new'] - r['dy_old'])[both].max()) if both.any() else float('nan')

        # Softness while the glyph is still nearly whole. The peak σ is not the complaint — ours
        # measures at or below the reference's — the TIMING is: a departure that goes soft while it
        # still has most of its ink reads as smeared, where the reference stays crisp until it has
        # actually left. Sampled where the outgoing glyph crosses 0.8 and 0.5 of its ink.
        def sigma_at(a, sg, level):
            for i in range(1, len(a)):
                if a[i] <= level < a[i - 1]:
                    return float(sg[i])
            return float('nan')

        out[c] = {
            'sep': sep,
            'blur@0.8': sigma_at(ao, r['s_old'], 0.8),
            'blur@0.5': sigma_at(ao, r['s_old'], 0.5),
            'half_out': cross(t, ao, 0.5, rising=False),
            'half_in': cross(t, an, 0.5, rising=True),
            'floor': float((ao + an)[lo:hi].min()) if hi > lo else float('nan'),
            'blur_out': peak_sigma(ao, r['s_old']),
            'blur_in': peak_sigma(an, r['s_new']),
            # How long the column takes to get THROUGH its change, as opposed to when it starts.
            # The reference's wave is partly made of this: each column further right is slower.
            'fall': cross(t, ao, 0.1, rising=False) - cross(t, ao, 0.9, rising=False),
            'rise': cross(t, an, 0.9, rising=True) - cross(t, an, 0.1, rising=True),
        }
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--ios')
    ap.add_argument('--ios-onset', type=int)
    ap.add_argument('--runs', nargs='+', required=True, help='video:onset, repeated')
    ap.add_argument('--columns', type=int, nargs='+', default=[-3, -2, -1])
    ap.add_argument('--span', type=int, default=32)
    ap.add_argument('--label', default='candidate')
    a = ap.parse_args()

    ref = measure(a.ios, a.ios_onset, 'ios', a.columns, a.span) if a.ios else None

    runs = []
    for spec in a.runs:
        v, _, o = spec.rpartition(':')
        runs.append(measure(v, int(o), 'android', a.columns, a.span))

    names = {-1: 'units', -2: 'tens', -3: 'hundreds', -4: 'thousands'}
    print(f'{len(runs)} run(s), median per column. Times in ms from the sync marker; '
          f'floor in glyphs of ink.')
    print(f'{"column":>10}  {"half_out":>18}  {"half_in":>18}  {"floor":>14}  '
          f'{"sep":>14}  {"blur@0.8":>14}  {"blur@0.5":>14}')
    for c in a.columns:
        cells = []
        for key in ('half_out', 'half_in', 'floor', 'sep', 'blur@0.8', 'blur@0.5'):
            vals = np.array([r[c][key] for r in runs], dtype=float)
            med = np.nanmedian(vals)
            spread = np.nanmax(vals) - np.nanmin(vals) if len(vals) > 1 else 0.0
            small = key not in ('half_out', 'half_in')
            fmt = f'{med:6.2f}' if small else f'{med:6.0f}'
            sp = f'{spread:5.2f}' if small else f'{spread:4.0f}'
            cell = f'{fmt} ±{sp}'
            if ref:
                rv = ref[c][key]
                cell += f' / {rv:6.2f}' if small else f' / {rv:4.0f}'
            cells.append(cell)
        print(f'{names.get(c, c):>10}  {cells[0]:>18}  {cells[1]:>18}  {cells[2]:>14}  '
              f'{cells[3]:>14}  {cells[4]:>14}  {cells[5]:>14}')
    if ref:
        print('   (value ± spread across runs / iOS reference)')


if __name__ == '__main__':
    main()
