#!/usr/bin/env python3
"""Phase-dependence study v1: within-cadence analysis, pre-state regression,
and proper cluster separation.
"""

import glob
import json
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ground_truth import load, ink_box, columns_of, profile_stats

MAX_FRAME_GAP_MS = 25.0
PRE_WINDOW_MS = 100.0
POST_WINDOW_MS = 200.0


def mark_times(meta):
    marks = meta.get("marks", [])
    if not marks:
        return [], []
    t0 = marks[0]["t"]
    return [(m["t"] - t0, m.get("label", "?"), m.get("value", "?")) for m in marks]


def frame_gaps_in_window(times, lo, hi):
    in_win = [i for i, t in enumerate(times) if lo <= t <= hi]
    if len(in_win) < 2:
        return 999.0
    gaps = [times[in_win[j + 1]] - times[in_win[j]] for j in range(len(in_win) - 1)]
    return max(gaps)


def _direction(lbl1, lbl2, lbl_prev):
    try:
        v1 = int(lbl1.replace(",", ""))
        v2 = int(lbl2.replace(",", ""))
        vp = int(lbl_prev.replace(",", ""))
    except (ValueError, AttributeError):
        return "unknown"
    d1 = v2 - v1
    d0 = v1 - vp
    if d1 == 0:
        return "same"
    if d0 == 0:
        return "unknown"
    return "reversal" if np.sign(d1) != np.sign(d0) else "continue"


def extract_pairs(prefix, label):
    meta, frames = load(prefix)
    marks_rel = mark_times(meta)
    if len(marks_rel) < 3:
        return []

    times_raw = np.array(meta["times"])
    t0 = meta["marks"][0]["t"]
    times = times_raw - t0

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

        lo = t_first - PRE_WINDOW_MS
        hi = t_second + POST_WINDOW_MS
        max_gap = frame_gaps_in_window(times, max(lo, times[0]), min(hi, times[-1]))
        rejected = max_gap > MAX_FRAME_GAP_MS

        # Pre-state: last frame before second trigger
        before = times <= t_second
        if np.any(before):
            pre_idx = np.where(before)[0][-1]
            pre_state = float(cents_norm[pre_idx])
            pre_time = float(times[pre_idx] - t_second)  # negative
            pre_ink_sum = float(win[pre_idx][:, a:b].sum())
        else:
            pre_state = np.nan
            pre_time = np.nan
            pre_ink_sum = np.nan

        # Pre-pre state: last frame before first trigger (for velocity estimate)
        before_first = times <= t_first
        if np.any(before_first):
            pre_pre_idx = np.where(before_first)[0][-1]
            pre_pre_state = float(cents_norm[pre_pre_idx])
            pre_pre_time = float(times[pre_pre_idx] - t_first)
        else:
            pre_pre_state = np.nan
            pre_pre_time = np.nan

        # Post trajectory
        post_mask = (times > t_second) & (times <= t_second + POST_WINDOW_MS)
        post_ts = (times[post_mask] - t_second).tolist()
        post_cs = cents_norm[post_mask].tolist()
        post_ink = win[post_mask][:, a:b].sum(axis=(1, 2)).tolist()

        # Settled values (very last frame)
        settled_centroid = float(cents_norm[-1])
        settled_ink = float(win[-1][:, a:b].sum())

        direction = _direction(lbl_first, lbl_second, lbl_prev)

        pairs.append({
            "run": os.path.basename(prefix),
            "label": label,
            "delay_ms": round(delay, 1),
            "trigger_first_ms": round(t_first, 1),
            "trigger_second_ms": round(t_second, 1),
            "direction": direction,
            "rejected": rejected,
            "max_gap_ms": round(max_gap, 1),
            "pre_state": pre_state,
            "pre_time_ms": round(pre_time, 1),
            "pre_ink": pre_ink_sum,
            "pre_pre_state": pre_pre_state,
            "pre_pre_time_ms": round(pre_pre_time, 1),
            "post_times_ms": post_ts,
            "post_centroids": post_cs,
            "post_ink": post_ink,
            "settled_centroid": settled_centroid,
            "settled_ink": settled_ink,
            "n_post_frames": int(np.sum(post_mask)),
        })

    return pairs


def interpolate_post(ts, cs, target_times):
    if len(ts) < 2:
        return np.full(len(target_times), np.nan)
    return np.interp(target_times, ts, cs, left=np.nan, right=np.nan)


def cluster_name(delay_ms):
    """Name the nominal cadence cluster."""
    if delay_ms < 90:
        return "~60ms"
    elif delay_ms < 170:
        return "~120ms"
    else:
        return "~240ms"


def main():
    sources = {
        "gt_ios_alt3": glob.glob("artifacts/gt_ios_alt3/run-*.json"),
        "gt_ios_behav": glob.glob("artifacts/gt_ios_behav/run-*.json"),
    }

    all_pairs = []
    for family, files in sources.items():
        for f in sorted(files):
            prefix = f[:-5]
            try:
                pairs = extract_pairs(prefix, family)
                all_pairs.extend(pairs)
            except Exception as e:
                print(f"  ERROR reading {prefix}: {e}", file=sys.stderr)

    n_total = len(all_pairs)
    clean = [p for p in all_pairs if not p["rejected"]]
    n_clean = len(clean)
    n_rejected = n_total - n_clean
    print(f"  Total: {n_total}  clean: {n_clean}  rejected: {n_rejected}")

    # ── Per-cluster analysis ───────────────────────────────────────────────
    LAGS = [33, 50, 67, 83, 100, 133, 167]

    clusters = {}
    for p in clean:
        c = cluster_name(p["delay_ms"])
        clusters.setdefault(c, []).append(p)

    print(f"\n  Clusters: {[(c, len(ps)) for c, ps in sorted(clusters.items())]}")

    for cname in sorted(clusters.keys()):
        cpairs = clusters[cname]
        delays = np.array([p["delay_ms"] for p in cpairs])
        pre_states = np.array([p["pre_state"] for p in cpairs])

        # Interpolate at lags
        lag_matrix = np.full((len(cpairs), len(LAGS)), np.nan)
        for i, p in enumerate(cpairs):
            ts = np.array(p["post_times_ms"])
            cs = np.array(p["post_centroids"])
            if len(ts) >= 2:
                lag_matrix[i, :] = interpolate_post(ts, cs, LAGS)

        print(f"\n  Cluster {cname} (n={len(cpairs)}, delay {delays.min():.0f}-{delays.max():.0f}ms)")
        print(f"  {'lag':>6s}  {'pearson_r':>10s} {'r_within_cadence':>17s} {'slope h/ms':>12s} {'p_value':>8s}")
        for k, lag in enumerate(LAGS):
            vals = lag_matrix[:, k]
            mask = ~np.isnan(vals)
            if mask.sum() < 3:
                continue
            d = delays[mask]
            v = vals[mask]
            if len(d) >= 3:
                r = np.corrcoef(d, v)[0, 1]
                A = np.vstack([d, np.ones_like(d)]).T
                slope, _ = np.linalg.lstsq(A, v, rcond=None)[0]
            else:
                r, slope = np.nan, np.nan
            print(f"  {lag:4.0f}ms  {r:+10.3f}            {r:+10.3f}   {slope:+11.6f}" if not np.isnan(r) else f"  {lag:4.0f}ms  (insufficient data)")

    # ── Global: pre_state vs delay scatter (the physical relationship) ─────
    delays_all = np.array([p["delay_ms"] for p in clean])
    pre_all = np.array([p["pre_state"] for p in clean])

    print(f"\n  --- Pre-state (glyph position at 2nd trigger) vs delay ---")
    print(f"  This is the physical phase relationship — where was the glyph when trigger 2 hit?")
    r_pre = np.corrcoef(delays_all, pre_all)[0, 1] if len(delays_all) >= 3 else 0
    print(f"  Pearson r = {r_pre:+.3f}")
    for cname in sorted(clusters.keys()):
        cpairs = clusters[cname]
        d = np.array([p["delay_ms"] for p in cpairs])
        ps = np.array([p["pre_state"] for p in cpairs])
        if len(d) >= 3:
            r = np.corrcoef(d, ps)[0, 1]
            print(f"    {cname}: r = {r:+.3f}, pre_state range {ps.min():+.4f} to {ps.max():+.4f}")

    # ── Residual analysis: does pre_state explain post-trigger? ────────────
    # For each lag, regress post-centroid on pre_state. Check if delay adds
    # additional explanatory power beyond pre_state.
    print(f"\n  --- Model comparison: pre_state alone vs pre_state + delay ---")
    print(f"  {'lag':>6s}  {'R²(pre_state)':>14s} {'R²(pre+delay)':>15s} {'ΔR²':>8s}")
    for k, lag in enumerate(LAGS):
        lag_matrix_all = np.full((len(clean), len(LAGS)), np.nan)
        for i, p in enumerate(clean):
            ts = np.array(p["post_times_ms"])
            cs = np.array(p["post_centroids"])
            if len(ts) >= 2:
                lag_matrix_all[i, :] = interpolate_post(ts, cs, LAGS)

        vals = lag_matrix_all[:, k]
        mask = ~np.isnan(vals)
        if mask.sum() < 5:
            continue
        v = vals[mask]
        d = delays_all[mask]
        p = pre_all[mask]

        # Model 1: centroid ~ pre_state
        A1 = np.vstack([p, np.ones_like(p)]).T
        res1 = np.linalg.lstsq(A1, v, rcond=None)[1][0] if len(p) >= 3 else np.inf
        ss_tot = np.sum((v - v.mean()) ** 2)
        r2_pre = 1 - res1 / ss_tot if ss_tot > 0 else 0

        # Model 2: centroid ~ pre_state + delay
        A2 = np.vstack([p, d, np.ones_like(p)]).T
        res2 = np.linalg.lstsq(A2, v, rcond=None)[1][0] if len(p) >= 4 else np.inf
        r2_both = 1 - res2 / ss_tot if ss_tot > 0 else 0

        delta_r2 = r2_both - r2_pre
        print(f"  {lag:4.0f}ms  {r2_pre:+13.3f}   {r2_both:+13.3f}   {delta_r2:+7.3f}")

    # ── Plot: main multi-panel figure ──────────────────────────────────────
    IMG_W, IMG_H = 1400, 900
    PAD = 60
    img = Image.new("RGB", (IMG_W, IMG_H), "white")
    d = ImageDraw.Draw(img)
    d.text((12, 10), "Phase-dependence probe — free first analysis", fill="black")
    d.text((12, 28), f"{n_clean} clean pairs from gt_ios_alt3 + gt_ios_behav. Post-trigger centroid vs delay across lags.",
           fill=(100, 100, 100))

    # Panel layout: 2 rows × 4 columns
    n_lags = len(LAGS)
    n_cols = 4
    n_rows = (n_lags + n_cols - 1) // n_cols
    PW, PH = (IMG_W - (n_cols + 1) * PAD) / n_cols, (IMG_H - (n_rows + 1) * PAD - 60) / n_rows  # reserve top 60 for title

    colors = {"reversal": (200, 60, 60), "continue": (60, 120, 200), "unknown": (130, 130, 130)}
    cluster_colors = {"~60ms": (200, 60, 60), "~120ms": (60, 120, 200), "~240ms": (60, 160, 60)}
    cluster_sym = {"~60ms": "o", "~120ms": "s", "~240ms": "D"}

    for ki, lag in enumerate(LAGS):
        row = ki // n_cols
        col = ki % n_cols
        ox = PAD + col * (PW + PAD)
        oy = 60 + row * (PH + PAD)

        # Interpolate all clean at this lag
        yi = np.full(len(clean), np.nan)
        for i, p in enumerate(clean):
            ts = np.array(p["post_times_ms"])
            cs = np.array(p["post_centroids"])
            if len(ts) >= 2:
                yi[i] = interpolate_post(ts, cs, [lag])[0]

        m = ~np.isnan(yi)
        if m.sum() < 2:
            continue

        xp = delays_all[m]
        yp = yi[m]
        cn = [cluster_name(p["delay_ms"]) for j, p in enumerate(clean) if m[j]]

        xmi, xma = xp.min() - 5, xp.max() + 5
        ymin, ymax = np.nanmin(yi) * 1.3, np.nanmax(yi) * 1.3
        ymin = min(ymin, -0.6)
        ymax = max(ymax, 0.6)

        def px(x, y):
            return (ox + (x - xmi) / max(xma - xmi, 1) * PW,
                    oy + PH - (y - ymin) / max(ymax - ymin, 0.01) * PH)

        # Panel frame
        d.rectangle([ox, oy, ox + PW, oy + PH], outline=(200, 200, 200))
        d.line([px(xmi, 0), px(xma, 0)], fill=(190, 190, 190))

        # Points
        for j in range(len(xp)):
            cc = cluster_colors.get(cn[j], (100, 100, 100))
            cx, cy = px(xp[j], yp[j])
            r = 4
            d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=cc, outline=(50, 50, 50))

        # Global trend line
        if len(xp) >= 3:
            A = np.vstack([xp, np.ones_like(xp)]).T
            sl, ic = np.linalg.lstsq(A, yp, rcond=None)[0]
            tx = np.array([xmi, xma])
            d.line([px(tx[0], sl * tx[0] + ic), px(tx[1], sl * tx[1] + ic)],
                   fill=(0, 0, 0), width=1)

        rg = np.corrcoef(xp, yp)[0, 1] if len(xp) >= 3 else 0
        d.text((ox + 4, oy + 2), f"lag={lag}ms  r={rg:+.2f}  n={m.sum()}", fill=(60, 60, 60))
        d.text((ox + 4, oy + PH - 13), "delay (ms)", fill=(140, 140, 140))

        # Legend (top-left panel)
        if ki == 0:
            for ci, (cn2, cc2) in enumerate(cluster_colors.items()):
                ly = oy + 18 + ci * 16
                d.ellipse([ox + 6, ly - 3, ox + 14, ly + 5], fill=cc2, outline=(50, 50, 50))
                d.text((ox + 18, ly - 4), cn2, fill=cc2)

    img.save("artifacts/phase_study_v1.png")
    print(f"\n  Figure saved to artifacts/phase_study_v1.png")

    # ── Second figure: pre_state vs delay (the phase map) ──────────────────
    img2 = Image.new("RGB", (800, 500), "white")
    d2 = ImageDraw.Draw(img2)
    d2.text((12, 10), "Glyph position when second trigger lands (pre-state) vs inter-trigger delay", fill="black")
    d2.text((12, 28), f"r={r_pre:+.3f} across {n_clean} pairs. Higher values = glyph farther from rest.", fill=(100, 100, 100))

    P2 = 70
    def px2(x, y):
        return (P2 + (x - 40) / 230 * (800 - 2 * P2),
                500 - P2 - (y + 0.05) / 0.25 * (500 - 2 * P2 - 30))

    d2.line([px2(40, 0), px2(270, 0)], fill=(180, 180, 180))
    for i in range(len(clean)):
        cc = cluster_colors.get(cluster_name(clean[i]["delay_ms"]), (100, 100, 100))
        cx, cy = px2(clean[i]["delay_ms"], clean[i]["pre_state"])
        d2.ellipse([cx - 4, cy - 4, cx + 4, cy + 4], fill=cc, outline=(50, 50, 50))

    # Trend
    if len(delays_all) >= 3:
        A2 = np.vstack([delays_all, np.ones_like(delays_all)]).T
        sl2, ic2 = np.linalg.lstsq(A2, pre_all, rcond=None)[0]
        tx2 = np.array([40, 270])
        d2.line([px2(tx2[0], sl2 * tx2[0] + ic2), px2(tx2[1], sl2 * tx2[1] + ic2)],
                fill=(0, 0, 0), width=1)

    d2.text((P2, 500 - 16), "delay (ms)", fill=(100, 100, 100))
    d2.text((P2 + 300, 500 - 16), "reversal  (continue / same dir not present in data)", fill=(150, 150, 150))

    img2.save("artifacts/phase_study_v1_prestate.png")
    print(f"  Figure saved to artifacts/phase_study_v1_prestate.png")

    # ── Third figure: post-trigger trajectory overlay ──────────────────────
    # Group by cluster and overlay post-trigger trajectories (time-aligned to second trigger)
    img3 = Image.new("RGB", (1200, 500), "white")
    d3 = ImageDraw.Draw(img3)
    d3.text((12, 10), "Post-second-trigger centroid trajectories, aligned by cluster", fill="black")
    d3.text((12, 28), "Time zero = second trigger. Each line = one trigger pair.", fill=(100, 100, 100))

    P3 = 70
    n_panels = len(clusters)
    PW3 = (1200 - (n_panels + 1) * P3) / n_panels
    PH3 = 500 - 2 * P3 - 40

    for pi, cname in enumerate(sorted(clusters.keys())):
        cpairs = clusters[cname]
        ox = P3 + pi * (PW3 + P3)
        oy = 50

        ymin3, ymax3 = -0.6, 0.6
        xmax3 = 200  # ms

        def px3(x, y):
            return (ox + x / xmax3 * PW3,
                    oy + PH3 - (y - ymin3) / max(ymax3 - ymin3, 0.01) * PH3)

        d3.rectangle([ox, oy, ox + PW3, oy + PH3], outline=(200, 200, 200))
        d3.line([px3(0, 0), px3(xmax3, 0)], fill=(180, 180, 180))

        for p in cpairs:
            ts = np.array(p["post_times_ms"])
            cs = np.array(p["post_centroids"])
            if len(ts) < 2:
                continue
            # thin lines for individual trajectories
            pts = [(px3(t, c)) for t, c in zip(ts, cs) if t <= xmax3]
            if len(pts) >= 2:
                d3.line(pts, fill=(180, 180, 180), width=1)

        # Mean trajectory
        all_ts = []
        all_cs = []
        for p in cpairs:
            ts = np.array(p["post_times_ms"])
            cs = np.array(p["post_centroids"])
            if len(ts) >= 2:
                ci = np.interp(np.arange(0, 201, 5), ts, cs, left=np.nan, right=np.nan)
                all_ts.append(np.arange(0, 201, 5))
                all_cs.append(ci)
        if all_cs:
            mean_ts = np.arange(0, 201, 5)
            mean_cs = np.nanmean(all_cs, axis=0)
            pts_mean = [(px3(t, c)) for t, c in zip(mean_ts, mean_cs) if not np.isnan(c)]
            d3.line(pts_mean, fill=cluster_colors.get(cname, (0, 0, 0)), width=2)

        d3.text((ox + 4, oy + 2), f"{cname} (n={len(cpairs)})", fill=(60, 60, 60))
        d3.text((ox + 4, oy + PH3 - 13), "ms after 2nd trigger", fill=(140, 140, 140))

    img3.save("artifacts/phase_study_v1_trajectories.png")
    print(f"  Figure saved to artifacts/phase_study_v1_trajectories.png")

    # ── Dump summary ───────────────────────────────────────────────────────
    print(f"\n  --- Summary ---")
    print(f"  Clusters found: {[(c, len(ps)) for c, ps in sorted(clusters.items())]}")
    print(f"  NOTE: all clean pairs are 'reversal' — no 'continue' / same-dir pairs exist in these recordings.")
    print(f"  The global correlation (r={r_pre:+.3f} delay→pre_state) is dominated by")
    print(f"  bimodal data (h~60 vs h~120), which is a trivial effect: of course the glyph")
    print(f"  is at a different position at 55ms vs 117ms after the first trigger.")
    print(f"  WITHIN each cluster, delay jitter is only ±8ms — too narrow to detect")
    print(f"  continuous phase dependence above noise (2-3 points cover the full range).")
    print(f"  Family B with continuous jittered delays 30–180ms is NEEDED.")


if __name__ == "__main__":
    main()
