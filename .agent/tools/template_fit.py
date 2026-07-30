#!/usr/bin/env python3
"""Separate the OLD and NEW glyph of a transition, per frame.

Every probe used so far (ink timelines, moments) reads one window that holds both glyphs at once,
so a centroid shift has at least two readings — old ink lingering on one side, or new ink arriving
from the other — and no experiment driven by that number can tell them apart. Two attempts at the
roll's settle tail died on exactly this (METHODOLOGY §6).

This is §8.3 of the methodology: model each frame as

    D(x, y, t) ≈ a_old · T_old(y − dy_old, σ_old) + a_new · T_new(y − dy_new, σ_new)

where T_c is the glyph's own settled ink taken from the SAME recording (same font, same size, same
renderer), shifted along the roll axis and blurred. Fitting gives, per frame and per glyph, its
opacity `a`, its position `dy` and its softness `σ` — the three quantities the single-window
probes could only see summed.

Method: the amplitudes are linear given the shifts and blurs, so only (dy, σ) are searched. The
templates for every (dy, σ) are precomputed once, their Gram matrix with them, and each frame is
then a closed-form 2×2 solve over the whole grid at once — exact over the grid, no local minima.

Usage:
    python3 template_fit.py --video ref.mov --platform ios --onset 606 --column -1
"""
import argparse
import subprocess
import numpy as np

W, H = 600, 213          # scaled crop; 1.6 line-heights tall
FONT = H / 1.6           # px per line-height after scaling (~133)
FPS = 60

CROP = {                 # w, h, x, y in source pixels — a box 4.5 x 1.6 line-heights on the number
    # Re-derived 2026-07-30 for the sync-marker recordings: iOS 1206x2622 (simctl), Android
    # 1080x2400. The old values were for earlier captures and silently cropped the wrong region.
    # Kept only as the fallback and the sanity check for [locate], which finds the number itself.
    'ios':     (1134, 403, 36, 958),
    'android': (1017, 362, 31, 881),
}

# Where in the frame to look for the number, as a fraction of screen height. Wide enough to cover
# either platform's layout — the number does move when the Showcase changes — and to hold the whole
# glyph on both. It no longer has to EXCLUDE the +/− buttons: the band having to be tight enough to
# do that is what broke when the layout moved again, so the row grouping below separates them
# instead. The band's only remaining job is to keep the status bar and the preset pills out.
SEARCH_BAND = (0.18, 0.50)


def locate(path, platform):
    """
    Find the crop box by finding the DIGITS, instead of trusting a constant.

    A constant crop is measured against one screen layout, and the Showcase's layout is not frozen:
    adding one row of buttons on 2026-07-30 moved the number up 67 px on Android and 72 px on iOS,
    which put half the digits outside the window and the +/− buttons inside it. Every number taken
    that afternoon looked like a large, clean regression — the arrivals appeared to land 45 ms early
    and the crossing pair to lose all of its separation — and none of it was real.

    So: decode one settled frame, take the rows that carry real text ink inside [SEARCH_BAND], and
    build the same box the constants describe relative to them (it starts 0.12 of a digit height
    below the digits' top and is 1.82 of one tall — that is what the hand-fitted constants measure).

    The rows are GROUPED before the box is built, and the group carrying the most ink wins. Taking
    plain min..max instead assumes the band holds nothing but the number, which stopped being true
    the moment the layout moved again: the +/− buttons landed inside it and the box grew to 901 px
    against the 403 it should be, so every column was normalised against a digit height that
    included two buttons. It read as a 30 px tall digit and produced confident nonsense. A number is
    one contiguous block of rows; a button is another, with a gap between them.
    """
    probe = subprocess.run(
        ['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries',
         'stream=width,height,nb_frames', '-of', 'csv=p=0:s=,', path],
        capture_output=True, text=True).stdout.strip().split(',')
    sw, sh = int(probe[0]), int(probe[1])
    # A frame late enough that the number is settled, whatever the preset was.
    raw = subprocess.run(
        ['ffmpeg', '-v', 'error', '-sseof', '-1', '-i', path, '-vf', 'format=gray',
         '-frames:v', '1', '-f', 'rawvideo', '-pix_fmt', 'gray', '-'],
        capture_output=True).stdout
    if len(raw) < sw * sh:
        return CROP[platform]
    frame = np.frombuffer(raw[:sw * sh], dtype=np.uint8).reshape(sh, sw).astype(np.float32)
    lo, hi = int(sh * SEARCH_BAND[0]), int(sh * SEARCH_BAND[1])
    bg = float(np.median(frame[lo:hi, :20]))
    ink_ = np.clip(bg - frame[lo:hi], 0, None)
    ink_[ink_ < 80] = 0                       # real text ink, not the pale button fills
    rows = ink_.sum(axis=1)
    if rows.max() <= 0:
        return CROP[platform]
    on = rows > rows.max() * 0.03
    # Group the inked rows, tolerating gaps up to 1% of the screen (the dot of a comma, antialias
    # noise) but not the ~5% of blank page that separates the number from the buttons below it.
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
    if not runs:
        return CROP[platform]
    s, e = max(runs, key=lambda r: rows[r[0]:r[1] + 1].sum())
    top, bot = lo + int(s), lo + int(e)
    dh = bot - top
    if dh < sh * 0.04:                        # implausible: fall back rather than measure noise
        return CROP[platform]
    return (int(sw * 0.94), int(round(dh * 1.82)), int(sw * 0.03), int(round(top + dh * 0.12)))

# Search grid. 3 px is 0.023 of a line height; the reference's residual tail is ~0.05, so the grid
# resolves it four times over. ±72 px covers ±0.54 line-heights, past the 0.48 a birth spawns from.
DY = np.arange(-72, 73, 3)
SIGMA = np.array([0.01, 2, 4, 6, 9, 12, 16, 21, 27])


def decode(path, platform):
    w, h, x, y = locate(path, platform)
    vf = f"fps={FPS},crop={w}:{h}:{x}:{y},scale={W}:{H},format=gray"
    cmd = ['ffmpeg', '-v', 'error', '-i', path, '-vf', vf, '-f', 'rawvideo', '-pix_fmt', 'gray', '-']
    raw = subprocess.run(cmd, capture_output=True).stdout
    n = len(raw) // (W * H)
    return np.frombuffer(raw[:n * W * H], dtype=np.uint8).reshape(n, H, W)


def background(frames):
    """The page's own level, measured per video rather than assumed (iOS 244, Android 242)."""
    return float(np.median(frames[::37, :5, :]))


def ink(frames, bg):
    return np.clip(bg - 2.0 - frames.astype(np.float32), 0, None)


def columns(frame_ink, min_frac=0.06):
    prof = frame_ink.sum(axis=0)
    on = prof > prof.max() * min_frac
    groups, s = [], None
    for i, v in enumerate(on):
        if v and s is None:
            s = i
        elif not v and s is not None:
            if i - s > 4:
                groups.append((s, i))
            s = None
    if s is not None:
        groups.append((s, len(on)))
    return groups


def gaussian1d(sigma):
    r = max(1, int(3 * sigma))
    x = np.arange(-r, r + 1, dtype=np.float32)
    k = np.exp(-0.5 * (x / sigma) ** 2)
    return k / k.sum()


def blur(img, sigma):
    if sigma <= 0.05:
        return img.copy()
    k = gaussian1d(sigma)
    pad = len(k) // 2
    a = np.pad(img, ((pad, pad), (pad, pad)), mode='constant')
    a = np.apply_along_axis(lambda m: np.convolve(m, k, mode='valid'), 1, a)
    a = np.apply_along_axis(lambda m: np.convolve(m, k, mode='valid'), 0, a)
    return a


def shift_y(img, dy):
    out = np.zeros_like(img)
    if dy == 0:
        out[:] = img
    elif dy > 0:
        out[dy:] = img[:-dy]
    else:
        out[:dy] = img[-dy:]
    return out


def template_stack(tpl):
    """Every (dy, σ) variant of one glyph, flattened, plus the index table."""
    rows, idx = [], []
    for s_i, s in enumerate(SIGMA):
        b = blur(tpl, s)
        for d_i, d in enumerate(DY):
            rows.append(shift_y(b, int(d)).ravel())
            idx.append((d_i, s_i))
    return np.asarray(rows, dtype=np.float32), np.asarray(idx)


def fit(frames, onset, column=-1, span=60, bg=None, verbose=True):
    """Fit both glyphs over [onset−4, onset+span). Returns a dict of per-frame parameter arrays."""
    bg = background(frames) if bg is None else bg
    end = onset + span
    settled_new = ink(frames[end - 10:end + 1], bg).mean(axis=0)
    settled_old = ink(frames[onset - 10:onset - 2], bg).mean(axis=0)

    x0, x1 = columns(settled_new)[column]
    # Widen so a displaced or blurred glyph is not clipped differently from its template.
    xa, xb = max(0, x0 - 24), min(W, x1 + 24)

    t_old = settled_old[:, xa:xb]
    t_new = settled_new[:, xa:xb]
    if verbose:
        print(f'# column x={x0}-{x1} (roi {xa}-{xb}), background {bg:.0f}, '
              f'{len(DY)}x{len(SIGMA)} grid per glyph')

    To, idx = template_stack(t_old)
    Tn, _ = template_stack(t_new)

    goo = (To * To).sum(axis=1)                    # (P,)
    gnn = (Tn * Tn).sum(axis=1)                    # (P,)
    gon = To @ Tn.T                                # (P, P) — the expensive part, done once
    goo64, gnn64, gon64 = goo.astype(np.float64), gnn.astype(np.float64), gon.astype(np.float64)

    out = {k: [] for k in ('t', 'a_old', 'dy_old', 's_old', 'a_new', 'dy_new', 's_new', 'resid')}
    for f in range(onset - 4, end):
        d = ink(frames[f:f + 1], bg)[0, :, xa:xb].ravel()
        bo = (To @ d).astype(np.float64)
        bn = (Tn @ d).astype(np.float64)
        # Closed-form 2x2 least squares for every (old, new) pair at once.
        # float64: the Gram entries run to ~1e8, and a near-singular pair (two templates that are
        # nearly the same picture) squares that when the amplitude blows up before clipping.
        dd = float(d.astype(np.float64) @ d.astype(np.float64))
        # Single-template fits, needed as a fallback and as the answer whenever one of the two
        # glyphs does not exist: a BIRTH has no old glyph in its column, so that template is all
        # zeros. Testing degeneracy against goo*gnn alone compares 0 < 0, misses it, and the solve
        # divides by zero — which is what filled the reference's newly-created columns with NaN.
        ao_solo = np.clip(np.where(goo64 > 1e-6, bo / np.where(goo64 > 1e-6, goo64, 1.0), 0.0), 0, None)
        an_solo = np.clip(np.where(gnn64 > 1e-6, bn / np.where(gnn64 > 1e-6, gnn64, 1.0), 0.0), 0, None)
        r_o = dd - 2 * ao_solo * bo + ao_solo ** 2 * goo64
        r_n = dd - 2 * an_solo * bn + an_solo ** 2 * gnn64

        det = goo64[:, None] * gnn64[None, :] - gon64 ** 2
        scale = np.maximum(goo64[:, None] * gnn64[None, :], 1e-9)
        bad = (np.abs(det) < 1e-3 * scale) | (goo64[:, None] < 1e-6) | (gnn64[None, :] < 1e-6)
        safe_det = np.where(bad, 1.0, det)
        ao = np.clip((gnn64[None, :] * bo[:, None] - gon64 * bn[None, :]) / safe_det, 0, None)
        an = np.clip((goo64[:, None] * bn[None, :] - gon64 * bo[:, None]) / safe_det, 0, None)
        # Where the pair is degenerate, keep whichever single template explains the frame better.
        keep_old = r_o[:, None] <= r_n[None, :]
        ao = np.where(bad, np.where(keep_old, ao_solo[:, None], 0.0), ao)
        an = np.where(bad, np.where(keep_old, 0.0, an_solo[None, :]), an)
        r = (dd
             - 2 * (ao * bo[:, None] + an * bn[None, :])
             + ao ** 2 * goo64[:, None] + an ** 2 * gnn64[None, :] + 2 * ao * an * gon64)
        i, j = np.unravel_index(np.argmin(r), r.shape)
        out['t'].append((f - onset) / FPS * 1000)
        out['a_old'].append(float(ao[i, j])); out['dy_old'].append(float(DY[idx[i][0]]) / FONT)
        out['s_old'].append(float(SIGMA[idx[i][1]]) / FONT)
        out['a_new'].append(float(an[i, j])); out['dy_new'].append(float(DY[idx[j][0]]) / FONT)
        out['s_new'].append(float(SIGMA[idx[j][1]]) / FONT)
        out['resid'].append(float(np.sqrt(max(r[i, j], 0)) / max(np.linalg.norm(d), 1e-6)))
    return {k: np.asarray(v) for k, v in out.items()}


def fit_dying(frames, onset, span=60, bg=None, min_gap=6, settle_at=None):
    """One template per DYING column: a column that holds ink before the change and none after.

    A death is a single glyph, so the two-template solve degenerates. Here each pre-transition
    column gets its own single-template fit — opacity, position and blur per frame — which is what
    tells apart "the glyphs leave one at a time" from "they all fade together", something a summed
    window can only see as a drop in ink.
    """
    bg = background(frames) if bg is None else bg
    end = onset + span
    # settle_at: where to read the layout the change is heading for. It defaults to the end of the
    # window, but a preset that sets a second value 400 ms later never settles inside it, and then
    # every column looks alive and none is classed as dying.
    sa = (end - 10) if settle_at is None else settle_at
    settled = ink(frames[sa:sa + 8], bg).mean(axis=0)
    pre = ink(frames[onset - 10:onset - 2], bg).mean(axis=0)
    live_after = np.zeros(W, bool)
    for a, b in columns(settled):
        live_after[max(0, a - min_gap):min(W, b + min_gap)] = True

    out = []
    for x0, x1 in columns(pre):
        if live_after[x0:x1].any():
            continue                                   # something settles here; not a clean death
        xa, xb = max(0, x0 - 20), min(W, x1 + 20)
        T, idx = template_stack(pre[:, xa:xb])
        g = (T * T).sum(axis=1).astype(np.float64)
        g = np.where(g < 1e-6, 1e-6, g)
        rows = {'x': (x0, x1), 't': [], 'a': [], 'dy': [], 's': []}
        for f in range(onset - 4, end):
            d = ink(frames[f:f + 1], bg)[0, :, xa:xb].ravel()
            b_ = (T @ d).astype(np.float64)
            a_ = np.clip(b_ / g, 0, None)
            r = float(d @ d) - 2 * a_ * b_ + a_ ** 2 * g
            i = int(np.argmin(r))
            rows['t'].append((f - onset) / FPS * 1000)
            rows['a'].append(float(a_[i]))
            rows['dy'].append(float(DY[idx[i][0]]) / FONT)
            rows['s'].append(float(SIGMA[idx[i][1]]) / FONT)
        out.append({k: (np.asarray(v) if k != 'x' else v) for k, v in rows.items()})
    return out


def report_dying(res, label, step=3):
    print(f'-- {label}: {len(res)} dying columns (opacity as a fraction of the settled glyph)')
    if not res:
        return
    k = range(0, len(res[0]['t']), step)
    print('   t(ms)  :', ' '.join(f'{res[0]["t"][i]:6.0f}' for i in k))
    for j, c in enumerate(res):
        print(f'   col{j} a :', ' '.join(f'{c["a"][i]:6.2f}' for i in k))
    for j, c in enumerate(res):
        a = c['a']; a0 = a[:4].mean()
        rel = np.nonzero(a < 0.5 * a0)[0]; rel = rel[rel >= 4]
        start = np.nonzero(a < 0.9 * a0)[0]; start = start[start >= 4]
        half = (rel[0] - 4) / FPS * 1000 if len(rel) else float('nan')
        t0 = (start[0] - 4) / FPS * 1000 if len(start) else float('nan')
        live = a > 0.06 * a0
        trav = np.abs(c['dy'][live]).max() if live.any() else 0.0
        print(f'   col{j}: starts to go at {t0:4.0f} ms, half gone at {half:4.0f} ms, '
              f'travels {trav:.2f} line-heights while visible')


def report(res, label, step=3):
    print(f'-- {label}   (dy and σ in line-heights, a as a fraction of the settled glyph)')
    k = range(0, len(res['t']), step)
    print('   t(ms) :', ' '.join(f'{res["t"][i]:6.0f}' for i in k))
    print('   a_old :', ' '.join(f'{res["a_old"][i]:6.2f}' for i in k))
    print('   dy_old:', ' '.join(f'{res["dy_old"][i]:+6.2f}' if res['a_old'][i] > 0.05 else '     .' for i in k))
    print('   σ_old :', ' '.join(f'{res["s_old"][i]:6.2f}' if res['a_old'][i] > 0.05 else '     .' for i in k))
    print('   a_new :', ' '.join(f'{res["a_new"][i]:6.2f}' for i in k))
    print('   dy_new:', ' '.join(f'{res["dy_new"][i]:+6.2f}' if res['a_new'][i] > 0.05 else '     .' for i in k))
    print('   σ_new :', ' '.join(f'{res["s_new"][i]:6.2f}' if res['a_new'][i] > 0.05 else '     .' for i in k))
    print(f'   fit residual: median {np.median(res["resid"]):.3f}, worst {res["resid"].max():.3f} '
          f'(fraction of the frame\'s own ink norm)')


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--video', required=True)
    ap.add_argument('--platform', required=True, choices=['ios', 'android'])
    ap.add_argument('--onset', type=int, required=True)
    ap.add_argument('--column', type=int, default=-1)
    ap.add_argument('--span', type=int, default=60)
    ap.add_argument('--label', default='')
    a = ap.parse_args()
    frames = decode(a.video, a.platform)
    report(fit(frames, a.onset, a.column, a.span), a.label or a.video)
