#!/usr/bin/env python3
"""Phase-dependence study: extract consecutive trigger pairs from alt3 and behav
recordings, measure achieved delay, and test whether post-second-trigger behaviour
depends on that delay.

This is the "FREE FIRST ANALYSIS" from HANDOFF.md — purely observational, no
device interaction, no Kotlin modifications.
"""

import glob
import json
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ground_truth import load, ink_box, columns_of, profile_stats

# ── constants ──────────────────────────────────────────────────────────────
MAX_FRAME_GAP_MS = 25.0       # reject a pair if any frame gap in its window exceeds this
PRE_WINDOW_MS = 100.0         # look this far before the first trigger
POST_WINDOW_MS = 200.0        # look this far after the second trigger

# ── helper: get mark times relative to marks[0] ────────────────────────────
def mark_times(meta):
    marks = meta.get("marks", [])
    if not marks:
        return [], []
    t0 = marks[0]["t"]
    return [(m["t"] - t0, m.get("label", "?"), m.get("value", "?")) for m in marks]


def frame_gaps_in_window(times, lo, hi):
    """Return max gap (ms) between consecutive frames in [lo, hi], or 0 if none."""
    in_win = [i for i, t in enumerate(times) if lo <= t <= hi]
    if len(in_win) < 2:
        return 999.0  # not enough frames — reject
    gaps = [times[in_win[j + 1]] - times[in_win[j]] for j in range(len(in_win) - 1)]
    return max(gaps)


def column_centroid_trajectory(prefix, col_index=-1):
    """Return (times_ms, centroids_in_glyph_heights) for one column."""
    meta, frames = load(prefix)
    marks = meta.get("marks", [])
    if len(marks) < 2:
        return np.array([]), np.array([])
    zero = marks[0]["t"]
    y0, y1, x0, x1 = ink_box(frames)
    win = frames[:, y0:y1, x0:x1].astype(np.float64)
    groups = columns_of(win[-1])
    if not groups:
        return np.array([]), np.array([])
    idx = col_index if col_index >= 0 else len(groups) + col_index
    a, b = groups[idx]
    settled = profile_stats(win[-1][:, a:b], 1.0)[0]
    height = float(np.count_nonzero(
        win[-1].sum(axis=1) > win[-1].sum(axis=1).max() * 0.02
    )) or 1.0
    times = np.array(meta["times"]) - zero
    cents = np.array([
        profile_stats(win[i][:, a:b], 1.0)[0] for i in range(len(win))
    ])
    return times, (cents - settled) / height


def extract_pairs(prefix, label):
    """Yield (delay_ms, pre_state, post_times, post_centroids, flags) for each
    consecutive pair of marks, with dropped-frame filtering."""
    meta, frames = load(prefix)
    marks_rel = mark_times(meta)
    if len(marks_rel) < 3:
        return []  # need at least marks[0], marks[1], marks[2]

    times_raw = np.array(meta["times"])
    t0 = meta["marks"][0]["t"]
    times = times_raw - t0

    # centroid for the last (units) column
    y0, y1, x0, x1 = ink_box(frames)
    win = frames[:, y0:y1, x0:x1].astype(np.float64)
    groups = columns_of(win[-1])
    if not groups:
        return []
    col_idx = -1
    idx = len(groups) + col_idx
    a, b = groups[idx]
    settled = profile_stats(win[-1][:, a:b], 1.0)[0]
    height = float(np.count_nonzero(
        win[-1].sum(axis=1) > win[-1].sum(axis=1).max() * 0.02
    )) or 1.0

    # Precompute centroids per frame
    cents = np.array([
        profile_stats(win[i][:, a:b], 1.0)[0] for i in range(len(win))
    ])
    cents_norm = (cents - settled) / height

    pairs = []
    for i in range(1, len(marks_rel) - 1):
        t_first, lbl_first, val_first = marks_rel[i]
        t_second, lbl_second, val_second = marks_rel[i + 1]
        delay = t_second - t_first
        t_prev, lbl_prev, val_prev = marks_rel[i - 1]

        # Filter: dropped frames in [t_first - PRE_WINDOW, t_second + POST_WINDOW]
        lo = t_first - PRE_WINDOW_MS
        hi = t_second + POST_WINDOW_MS
        max_gap = frame_gaps_in_window(times, max(lo, times[0]), min(hi, times[-1]))
        rejected = max_gap > MAX_FRAME_GAP_MS

        # Pre-trigger state: centroid right before second trigger
        before_second = times <= t_second
        if np.any(before_second):
            pre_idx = np.argmax(times[before_second])  # last frame before trigger
            pre_state = cents_norm[before_second][pre_idx]
        else:
            pre_state = np.nan

        # Post-trigger trajectory frames within 200ms after second trigger
        post_mask = (times > t_second) & (times <= t_second + POST_WINDOW_MS)
        post_ts = times[post_mask] - t_second
        post_cs = cents_norm[post_mask]

        direction = _direction(lbl_first, lbl_second, lbl_prev)
        pair_info = {
            "run": os.path.basename(prefix),
            "label": label,
            "delay_ms": round(delay, 1),
            "trigger_first_ms": round(t_first, 1),
            "trigger_second_ms": round(t_second, 1),
            "direction": direction,  # "toggle", "continue", or "unknown"
            "rejected": rejected,
            "max_gap_ms": round(max_gap, 1),
            "pre_state": float(pre_state),
            "post_times_ms": post_ts.tolist(),
            "post_centroids": post_cs.tolist(),
            "n_post_frames": int(np.sum(post_mask)),
        }
        pairs.append(pair_info)

    return pairs


def _direction(lbl1, lbl2, lbl_prev):
    """Infer direction: 'toggle' if alternating (e.g. 1→0 or 0→1), 'continue' if
    same direction as previous (e.g. 1→0→1→0 are all toggle), 'same' if repeating."""
    try:
        v1 = int(lbl1.replace(",", ""))
        v2 = int(lbl2.replace(",", ""))
        vp = int(lbl_prev.replace(",", ""))
    except (ValueError, AttributeError):
        return "unknown"
    d1 = v2 - v1  # current step
    d0 = v1 - vp  # previous step
    if d1 == 0:
        return "same"
    if d0 == 0:
        return "unknown"  # can't compare direction
    if np.sign(d1) == np.sign(d0):
        return "continue"  # same direction
    else:
        return "reversal"  # opposite direction


# ── analysis ───────────────────────────────────────────────────────────────
def interpolate_post(ts, cs, target_times):
    """Interpolate centroid at given ms after trigger."""
    if len(ts) < 2:
        return np.full(len(target_times), np.nan)
    return np.interp(target_times, ts, cs, left=np.nan, right=np.nan)


def main():
    sources = {
        "gt_ios_alt3": glob.glob("artifacts/gt_ios_alt3/run-*.json"),
        "gt_ios_behav": glob.glob("artifacts/gt_ios_behav/run-*.json"),
    }

    all_pairs = []
    for family, files in sources.items():
        for f in sorted(files):
            prefix = f[:-5]  # strip .json
            try:
                pairs = extract_pairs(prefix, family)
                all_pairs.extend(pairs)
            except Exception as e:
                print(f"  ERROR reading {prefix}: {e}", file=sys.stderr)

    # ── print summary ──────────────────────────────────────────────────────
    n_total = len(all_pairs)
    n_clean = sum(1 for p in all_pairs if not p["rejected"])
    n_rejected = sum(1 for p in all_pairs if p["rejected"])
    print(f"\n{'='*70}")
    print(f"  Total pairs: {n_total}  clean: {n_clean}  rejected (gap > {MAX_FRAME_GAP_MS}ms): {n_rejected}")
    print(f"{'='*70}\n")

    # Print table
    print(f"  {'run':<30s} {'delay':>7s} {'dir':>9s} {'max_gap':>8s} {'status':>8s}  {'pre_state':>9s}")
    print(f"  {'-'*30} {'-'*7} {'-'*9} {'-'*8} {'-'*8}  {'-'*9}")
    for p in sorted(all_pairs, key=lambda x: (x["label"], x["delay_ms"])):
        status = "REJECTED" if p["rejected"] else "ok"
        print(f"  {p['run']:<30s} {p['delay_ms']:6.1f}ms {p['direction']:>9s} {p['max_gap_ms']:7.1f}ms {status:>8s}  {p['pre_state']:+8.4f}")

    # ── phase-dependence plot ──────────────────────────────────────────────
    clean = [p for p in all_pairs if not p["rejected"]]

    # Prepare plot: delay vs centroid at various lags after second trigger
    LAGS = [33, 50, 67, 83, 100, 133, 167, 200]  # ms after second trigger
    delays = np.array([p["delay_ms"] for p in clean])
    pre_states = np.array([p["pre_state"] for p in clean])
    directions = [p["direction"] for p in clean]

    # Interpolate each pair's post trajectory at the lag points
    lag_matrix = np.full((len(clean), len(LAGS)), np.nan)
    for i, p in enumerate(clean):
        ts = np.array(p["post_times_ms"])
        cs = np.array(p["post_centroids"])
        if len(ts) >= 2:
            lag_matrix[i, :] = interpolate_post(ts, cs, LAGS)

    # ── Correlazione delay vs centroid a ciascun lag ───────────────────────
    print(f"\n--- Phase dependence: centroid position at lag vs achieved delay ---")
    header = f"  {'lag (ms)':>9s}"
    header += f"  {'pearson_r':>10s} {'slope (h/ms)':>14s} {'delta_total':>12s}"
    print(header)
    print(f"  {'-'*9}  {'-'*10} {'-'*14} {'-'*12}")
    corr_results = []
    for k, lag in enumerate(LAGS):
        vals = lag_matrix[:, k]
        mask = ~np.isnan(vals)
        if mask.sum() < 3:
            continue
        d = delays[mask]
        v = vals[mask]
        r = np.corrcoef(d, v)[0, 1] if len(d) >= 3 else np.nan
        # linear slope
        if len(d) >= 3:
            A = np.vstack([d, np.ones_like(d)]).T
            slope, intercept = np.linalg.lstsq(A, v, rcond=None)[0]
            delta = slope * (d.max() - d.min())
        else:
            slope, delta = np.nan, np.nan
        print(f"  {lag:6.0f}ms   {r:+9.3f}     {slope:+13.6f}   {delta:+11.3f}")
        corr_results.append((lag, r, slope, delta, len(d)))

    # ── Scatter plot: delay vs centroid at 67ms (one mid-range lag) ────────
    IMG_W, IMG_H, PAD = 1200, 500, 80
    img = Image.new("RGB", (IMG_W, IMG_H), "white")
    d = ImageDraw.Draw(img)
    d.text((12, 10), "Phase-dependence probe — free first analysis (gt_ios_alt3 + gt_ios_behav)", fill="black")
    d.text((12, 28), f"Centroid position {LAGS[2]}ms after second trigger vs achieved inter-trigger delay. "
             f"{n_clean} clean pairs. Slope {corr_results[2][2]:+.5f} h/ms (r={corr_results[2][1]:+.3f}).",
             fill=(100, 100, 100))

    plot_lag = 67
    lag_idx = LAGS.index(plot_lag)
    x_vals = delays
    y_vals = lag_matrix[:, lag_idx]
    mask = ~np.isnan(y_vals)
    x_vals, y_vals = x_vals[mask], y_vals[mask]
    dirs = np.array(directions)[mask]

    x_min, x_max = x_vals.min() - 5, x_vals.max() + 5
    y_min = np.nanmin(lag_matrix[:, lag_idx]) * 1.3
    y_max = np.nanmax(lag_matrix[:, lag_idx]) * 1.3
    y_min = min(y_min, -1.0)
    y_max = max(y_max, 1.0)

    def px2(x, y):
        return (PAD + (x - x_min) / (x_max - x_min) * (IMG_W - 2 * PAD),
                IMG_H - PAD - (y - y_min) / (y_max - y_min) * (IMG_H - 2 * PAD - 30))

    # Grid
    for lvl in np.linspace(y_min, y_max, 6):
        d.line([px2(x_min, lvl), px2(x_max, lvl)], fill=(230, 230, 230))
        d.text((4, px2(x_min, lvl)[1] - 6), f"{lvl:.2f}", fill=(160, 160, 160))
    d.line([px2(x_min, 0), px2(x_max, 0)], fill=(170, 170, 170), width=1)

    colors = {"reversal": (200, 60, 60), "continue": (60, 120, 200), "unknown": (130, 130, 130), "toggle": (200, 60, 60)}

    for i in range(len(x_vals)):
        col = colors.get(dirs[i], (100, 100, 100))
        r = 5
        cx, cy = px2(x_vals[i], y_vals[i])
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col, outline=(0, 0, 0))

    # Trend line
    if len(x_vals) >= 3:
        A_plot = np.vstack([x_vals, np.ones_like(x_vals)]).T
        slope_plot, intercept_plot = np.linalg.lstsq(A_plot, y_vals, rcond=None)[0]
        tx = np.array([x_min, x_max])
        ty = slope_plot * tx + intercept_plot
        d.line([px2(tx[0], ty[0]), px2(tx[1], ty[1])], fill=(0, 0, 0), width=1)

    # Legend
    d.ellipse([PAD + 10 - 4, IMG_H - 40 - 4, PAD + 10 + 4, IMG_H - 40 + 4], fill=(200, 60, 60))
    d.text((PAD + 24, IMG_H - 46), "reversal", fill=(200, 60, 60))
    d.ellipse([PAD + 120 - 4, IMG_H - 40 - 4, PAD + 120 + 4, IMG_H - 40 + 4], fill=(60, 120, 200))
    d.text((PAD + 134, IMG_H - 46), "continue", fill=(60, 120, 200))

    d.text((PAD, IMG_H - 18), "inter-trigger delay (ms)", fill=(100, 100, 100))
    # rotated Y label
    d.text((10, IMG_H // 2 - 30), "centroid", fill=(100, 100, 100))
    d.text((10, IMG_H // 2 - 10), "(glyph heights)", fill=(100, 100, 100))

    out_path = "artifacts/phase_study_v0.png"
    img.save(out_path)
    print(f"\n  Scatter plot written to {out_path}")

    # ── Multi-lag summary plot ─────────────────────────────────────────────
    # One panel per lag, showing delay vs centroid
    cols = 4
    rows = (len(LAGS) + cols - 1) // cols
    PW, PH, PP = 280, 220, 40
    MW = cols * PW + (cols + 1) * PP
    MH = rows * PH + (rows + 1) * PP + 40
    multi = Image.new("RGB", (MW, MH), "white")
    dm = ImageDraw.Draw(multi)
    dm.text((12, 8), f"Phase dependence across lags — {n_clean} clean pairs (gt_ios_alt3 + gt_ios_behav)", fill="black")

    for ki, lag in enumerate(LAGS):
        row = ki // cols
        col = ki % cols
        ox = PP + col * (PW + PP)
        oy = PP + row * (PH + PP) + 24

        yi = lag_matrix[:, ki]
        m = ~np.isnan(yi)
        if m.sum() < 2:
            continue
        xp = delays[m]
        yp = yi[m]
        dp = np.array(directions)[m]

        xmi, xma = xp.min() - 3, xp.max() + 3
        ymi = min(np.nanmin(yi) * 1.2, -0.8)
        yma = max(np.nanmax(yi) * 1.2, 0.8)

        def px3(x, y):
            return (ox + (x - xmi) / max(xma - xmi, 1) * PW,
                    oy + PH - (y - ymi) / max(yma - ymi, 0.01) * PH)

        dm.rectangle([ox, oy, ox + PW, oy + PH], outline=(220, 220, 220))
        dm.line([px3(xmi, 0), px3(xma, 0)], fill=(200, 200, 200))

        for j in range(len(xp)):
            cc = colors.get(dp[j], (100, 100, 100))
            cx, cy = px3(xp[j], yp[j])
            dm.ellipse([cx - 3, cy - 3, cx + 3, cy + 3], fill=cc)

        if len(xp) >= 3:
            A3 = np.vstack([xp, np.ones_like(xp)]).T
            s3, i3 = np.linalg.lstsq(A3, yp, rcond=None)[0]
            tx3 = np.array([xmi, xma])
            ty3 = s3 * tx3 + i3
            dm.line([px3(tx3[0], ty3[0]), px3(tx3[1], ty3[1])], fill=(0, 0, 0), width=1)

        r3 = np.corrcoef(xp, yp)[0, 1] if len(xp) >= 3 else 0
        dm.text((ox + 4, oy + 2), f"lag={lag}ms  r={r3:+.2f}  n={m.sum()}", fill=(80, 80, 80))
        dm.text((ox + 4, oy + PH - 14), "delay (ms)", fill=(140, 140, 140))

    multi_path = "artifacts/phase_study_v0_lags.png"
    multi.save(multi_path)
    print(f"  Multi-lag panel written to {multi_path}")

    # ── Summary stats ──────────────────────────────────────────────────────
    print(f"\n--- Direction breakdown ---")
    for d in ["reversal", "continue", "unknown"]:
        count = sum(1 for p in clean if p["direction"] == d)
        print(f"  {d}: {count}")

    print(f"\n--- Delay distribution (clean pairs) ---")
    clean_delays = [p["delay_ms"] for p in clean]
    print(f"  range: {min(clean_delays):.1f} – {max(clean_delays):.1f} ms")
    print(f"  median: {np.median(clean_delays):.1f} ms")
    print(f"  count: {len(clean_delays)}")

    # ── JSON dump ──────────────────────────────────────────────────────────
    out_json = "artifacts/phase_study_v0.json"
    with open(out_json, "w") as f:
        json.dump({
            "description": "Phase-dependence probe — free first analysis",
            "n_total_pairs": n_total,
            "n_clean": n_clean,
            "n_rejected": n_rejected,
            "max_frame_gap_ms": MAX_FRAME_GAP_MS,
            "lags_ms": LAGS,
            "correlations": [{"lag": l, "r": r, "slope": s, "delta": d, "n": n}
                             for l, r, s, d, n in corr_results],
            "pairs": all_pairs,
        }, f, indent=2, default=str)
    print(f"\n  Full data written to {out_json}")


if __name__ == "__main__":
    main()
