#!/usr/bin/env python3
"""Trace every live glyph of the changing column, frame by frame, inside the engine.

    python3 .agent/tools/alttrace.py --preset=alt60
    python3 .agent/tools/alttrace.py --preset=alt60 --from=300 --to=700

This reads the ENGINE's own state — not pixels. `compare.py` and `balance.py` can only see the ink
a model leaves behind, which is enough to rank two models and not enough to say why one of them
sloshes. Here every live transition prints its position, its velocity, its opacity, its blur, which
way it was born, and how long ago the value last reversed.

Only the simulator, and only `--model=kotlin`. Nothing here can change what a device does.
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim  # noqa: E402


def trace(preset, lo, hi, column=-1):
    start, schedule, tail = sim.PRESETS[preset]
    layout = sim.Layout(os.path.join(sim.HERE, sim.LAYOUT_SOURCE))
    model = sim.KotlinModel(sim.fmt(start), layout, sim.engine_constants())

    dt = 1.0 / sim.FPS
    step_ms = dt * 1000.0
    schedule = [(sim.LEAD_IN + d, v) for d, v in schedule]
    pending = list(schedule)
    end = schedule[-1][0] + tail

    last_flip = None          # ms of the last time the direction reversed
    prev_dir = None
    t = 0.0
    print(f"  t(ms)  dir  dal ribaltamento |  per glifo vivo:  cifra  p       v        alpha  blur   nato-a")
    while t <= end:
        while pending and pending[0][0] <= t:
            model.change(sim.fmt(pending.pop(0)[1]))
            if prev_dir is not None and model.direction != prev_dir:
                last_flip = t
            prev_dir = model.direction
        model.step(dt)
        rel = t - sim.LEAD_IN
        if lo <= rel <= hi:
            col = model.columns[column]
            since = "-" if last_flip is None else f"{t - last_flip:5.0f}"
            head = f"{rel:7.1f}  {model.direction:+d}   {since:>16} |"
            parts = []
            for e in col.entries:
                parts.append(f"  {e.ch}  {e.p:+.3f}  {e.velocity:+7.3f}  {e.alpha:.3f}  {e.b:+.2f}"
                             f"  {'sup' if e.superseded else 'viv'}")
            print(head + "".join(parts))
        t += step_ms


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--preset", default="alt60", choices=sorted(sim.PRESETS))
    ap.add_argument("--from", dest="lo", type=float, default=0.0)
    ap.add_argument("--to", dest="hi", type=float, default=400.0)
    args = ap.parse_args()
    trace(args.preset, args.lo, args.hi)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
