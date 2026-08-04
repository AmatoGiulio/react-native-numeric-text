#!/usr/bin/env python3
"""Plot the changing column's ink centroid against time — the reference and a run, one clock.

    python3 .agent/tools/centroid.py out.png --title="..." \
            --ios=artifacts/gt_ios_alt3/run-... --and=artifacts/<dir> [--and2=<dir>] [--label2=...]

`balance.py` reduces this curve to one number, its peak-to-peak. That number is the whole remaining
defect of the alternation (0.407 against the reference's 0.103) and it is invisible in a frame grid:
a grid samples every 33 ms while the alternation turns over every 60, the column is small, and both
rows read as "a faint smear that stays put". This draws the curve the number comes from, so the
claim "it looks the same" can be checked against the thing that says it is not.

Centroid is `ground_truth.profile_stats`, in settled glyph heights, zero at the settled position —
the same quantity `balance.py` takes the peak-to-peak of, so the two cannot disagree.
"""

import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ground_truth import load, ink_box, columns_of, profile_stats  # noqa: E402

W, H, PAD = 1400, 460, 70


def curve(prefix, column=-1, window=(0.0, 1200.0)):
    meta, frames = load(prefix)
    marks = meta.get("marks", [])
    zero = marks[1]["t"] if len(marks) > 1 else 0.0
    y0, y1, x0, x1 = ink_box(frames)
    win = frames[:, y0:y1, x0:x1].astype(np.float64)
    groups = columns_of(win[-1])
    a, b = groups[column]
    settled = profile_stats(win[-1][:, a:b], 1.0)[0]
    height = float(np.count_nonzero(win[-1].sum(axis=1) > win[-1].sum(axis=1).max() * 0.02))
    ts, cs = [], []
    for i, t in enumerate(meta["times"]):
        rel = t - zero
        if not (window[0] <= rel <= window[1]):
            continue
        centre = profile_stats(win[i][:, a:b], 1.0)[0]
        ts.append(rel)
        cs.append((centre - settled) / height)
    return np.array(ts), np.array(cs)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = {a.split("=")[0]: a.split("=")[1] for a in sys.argv[1:] if "=" in a and a.startswith("--")}
    out = args[0] if args else "centroid.png"
    title = flags.get("--title")
    if not title:
        print('--title="..." is required — say what the curve shows.')
        return 2

    series = [("iOS", flags["--ios"], (0, 0, 0)),
              (flags.get("--label", "android"), flags["--and"], (200, 60, 60))]
    if "--and2" in flags:
        series.append((flags.get("--label2", "variante"), flags["--and2"], (60, 110, 200)))

    curves = []
    for name, path, colour in series:
        if os.path.isdir(path):
            import glob
            path = sorted(p[:-5] for p in glob.glob(os.path.join(path, "*.json"))
                          if not p.endswith("reference.json"))[0]
        curves.append((name, colour) + curve(path))

    span = max(float(np.abs(c).max()) for *_, c in curves) * 1.15 or 1.0
    img = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(img)
    d.text((12, 10), title, fill="black")
    d.text((12, 26), "baricentro dell'inchiostro della colonna che cambia, in altezze di glifo. "
                     "Zero = posizione a riposo.", fill=(110, 110, 110))

    def px(t, y):
        return (PAD + t / 1200.0 * (W - 2 * PAD), H / 2 + 20 - y / span * (H / 2 - PAD))

    d.line([px(0, 0), px(1200, 0)], fill=(170, 170, 170))
    for lvl in (-span / 2, span / 2):
        d.line([px(0, lvl), px(1200, lvl)], fill=(232, 232, 232))
        d.text((8, px(0, lvl)[1] - 6), f"{lvl:+.2f}", fill=(150, 150, 150))
    for t in range(0, 1201, 200):
        d.text((px(t, 0)[0] - 8, H - 26), f"{t}", fill=(150, 150, 150))
    d.text((W // 2 - 30, H - 12), "tempo (ms)", fill=(150, 150, 150))

    for i, (name, colour, ts, cs) in enumerate(curves):
        pts = [px(t, c) for t, c in zip(ts, cs)]
        d.line(pts, fill=colour, width=3 if i == 0 else 2)
        pp = float(cs.max() - cs.min())
        d.text((PAD + 10, 48 + i * 16), f"{name}   escursione picco-picco {pp:.3f}", fill=colour)

    img.save(out)
    print(f"scritto {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
