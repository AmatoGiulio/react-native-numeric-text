"""Reduce the six fitted columns to ONE transition, and fit an analytic form to each channel.

Every column of every direction runs the same transition, delayed. So all six are aligned on their
own onset and pooled; a channel that does not collapse under that alignment would show up as scatter
here rather than as a clean curve.
"""

import json
import os
import sys

import numpy as np

S = os.environ.get("FITS", ".agent/fits")
OFFSET = 0.59375
SCALE = 0.3984


def load_all():
    rows = []
    for direction in ("down", "up"):
        sign = 1.0 if direction == "down" else -1.0   # arriving glyph enters from +dy when counting down
        for c in (2, 3, 4):
            try:
                doc = json.load(open(f"{S}/{direction}_c{c}.json"))
            except FileNotFoundError:
                continue
            F = doc["frames"]
            t = np.array([f["t"] for f in F])
            a = np.array([f["new"]["a"] for f in F])
            dy = np.array([f["new"]["dy"] for f in F]) * sign
            s = np.array([(f["new"]["sx"] + f["new"]["sy"]) / 2 for f in F])
            bl = np.array([f["new"]["blur"] for f in F])
            oa = np.array([f["old"]["a"] for f in F])
            ody = np.array([f["old"]["dy"] for f in F]) * sign
            live = np.nonzero(a > 0.03)[0]
            if not len(live):
                continue
            # true onset: the DEPARTING glyph starts one or two frames before the arriving one is
            # bright enough to detect, so take the first frame it has left rest.
            moved = np.nonzero((np.abs(ody) > 0.015) & (t > t[live[0]] - 60))[0]
            t0 = t[moved[0]] if len(moved) and moved[0] < live[0] else t[live[0]]
            for i in range(len(t)):
                if t[i] < t0 - 5 or t[i] > t0 + 700:
                    continue
                rows.append((direction, c, t[i] - t0, dy[i], s[i], bl[i], a[i], ody[i], oa[i]))
    return rows


def step_response(t, zeta, wn):
    """Unit step of a second-order system, at rest with zero velocity at t=0."""
    t = np.maximum(t, 0.0)
    if zeta < 1.0:
        wd = wn * np.sqrt(1 - zeta ** 2)
        return 1 - np.exp(-zeta * wn * t) * (np.cos(wd * t) + (zeta * wn / wd) * np.sin(wd * t))
    return 1 - np.exp(-wn * t) * (1 + wn * t)


def fit_spring(ts, ys, target_of_p):
    """Grid search (zeta, wn) minimising |model - measured| for a channel that is a map of p."""
    best = None
    for zeta in np.arange(0.30, 1.51, 0.01):
        for wn in np.arange(6.0, 40.0, 0.2):
            p = step_response(ts / 1000.0, zeta, wn)
            err = np.abs(target_of_p(p) - ys).mean()
            if best is None or err < best[0]:
                best = (err, zeta, wn)
    return best


def main():
    rows = load_all()
    print(f"pooled {len(rows)} frames from {len(set((r[0], r[1]) for r in rows))} columns\n")
    T = np.array([r[2] for r in rows])
    DY = np.array([r[3] for r in rows])
    SC = np.array([r[4] for r in rows])
    BL = np.array([r[5] for r in rows])
    AL = np.array([r[6] for r in rows])
    ODY = np.array([r[7] for r in rows])
    OAL = np.array([r[8] for r in rows])

    print("Pooled curve, arriving glyph (median across the six columns, ± spread):")
    print("  t(ms)    dy        scale      blur       alpha     |  departing dy    alpha   sum a")
    for lo in range(0, 620, 17):
        m = (T >= lo - 8) & (T < lo + 9)
        if m.sum() < 2:
            continue
        def q(v):
            return f"{np.median(v[m]):+.3f}±{(np.percentile(v[m],75)-np.percentile(v[m],25))/2:.3f}"
        print(f"  {lo:5d}  {q(DY)}  {q(SC)}  {q(BL)}  {q(AL)}  |  {q(ODY)}  {q(OAL)}  "
              f"{np.median((AL+OAL)[m]):.3f}")

    # Offset: the one channel with an overshoot, so it pins the spring.
    keep = (T >= 0) & (T <= 620) & (AL > 0.12)
    err, zeta, wn = fit_spring(T[keep], DY[keep], lambda p: OFFSET * (1 - p))
    print(f"\nOFFSET  dy = {OFFSET} * (1 - p),  p = step response")
    print(f"  best zeta {zeta:.2f}   wn {wn:.1f} rad/s   response (2pi/wn) {2*np.pi/wn*1000:.0f} ms"
          f"   mean |err| {err:.4f} glyph heights")
    print(f"  overshoot predicted {np.exp(-np.pi*zeta/np.sqrt(1-zeta**2))*100:.1f}%  "
          f"measured {-DY[keep].min()/OFFSET*100:.1f}%")

    # Scale and alpha: monotone, so fit them their own spring (which will come back overdamped).
    err2, z2, w2 = fit_spring(T[keep], SC[keep], lambda p: SCALE + (1 - SCALE) * np.clip(p, 0, 1))
    print(f"\nSCALE   s = {SCALE} + {1-SCALE:.4f} * p2")
    print(f"  best zeta {z2:.2f}   wn {w2:.1f}   response {2*np.pi/w2*1000:.0f} ms   mean |err| {err2:.4f}")

    err3, z3, w3 = fit_spring(T[keep], AL[keep], lambda p: np.clip(p, 0, 1))
    print(f"\nALPHA   a = p3")
    print(f"  best zeta {z3:.2f}   wn {w3:.1f}   response {2*np.pi/w3*1000:.0f} ms   mean |err| {err3:.4f}")

    err4, z4, w4 = fit_spring(T[keep], BL[keep], lambda p: 0.125 * (1 - np.clip(p, 0, 1)))
    print(f"\nBLUR    sigma = 0.125 * (1 - p4)   [glyph heights]")
    print(f"  best zeta {z4:.2f}   wn {w4:.1f}   response {2*np.pi/w4*1000:.0f} ms   mean |err| {err4:.4f}")

    print("\nDoes one spring drive them all? residual of each channel under the OFFSET spring:")
    p = step_response(T[keep] / 1000.0, zeta, wn)
    for name, ys, f in (("scale", SC[keep], lambda p: SCALE + (1 - SCALE) * np.clip(p, 0, 1)),
                        ("alpha", AL[keep], lambda p: np.clip(p, 0, 1)),
                        ("blur", BL[keep], lambda p: 0.125 * (1 - np.clip(p, 0, 1)))):
        print(f"  {name:6s} mean |err| {np.abs(f(p) - ys).mean():.4f}")


if __name__ == "__main__":
    sys.exit(main())
