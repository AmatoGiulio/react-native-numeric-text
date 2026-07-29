#!/usr/bin/env python3
"""Per-phase frame cost during a press-and-hold, from `dumpsys gfxinfo framestats`.

Total frame time on an emulator is dominated by vsync scheduling and host load — three identical
runs of the same build spread from 21 ms to 28 ms at the median, which is wider than most changes
worth making. The two phases below are the ones a renderer change actually moves, and they are
stable across runs because they measure work rather than waiting:

  record  DRAW_START -> SYNC_START
          the UI thread building the display list: our onDraw, its measureText, its RenderNode
          recordings.

  gpu     ISSUE_DRAW_COMMANDS_START -> FRAME_COMPLETED
          the render thread rasterising it: fill rate, layer compositing, blur.

Usage:
    python3 .agent/tools/frame_cost.py --serial emulator-5554 --runs 3
    python3 .agent/tools/frame_cost.py --label after --runs 5 > after.json
"""
import argparse
import json
import statistics
import subprocess
import sys
import time

PKG = "numerictext.example"
# The '+' button, in device pixels on the Pixel_8 AVD.
HOLD_X, HOLD_Y = 646, 1098

# Columns are looked up by name, not position: the CSV has gained fields over Android versions
# (FrameTimelineVsyncId, WorkloadTarget, …), so fixed indices silently read the wrong clock.
NEEDED = ("Flags", "DrawStart", "SyncStart", "IssueDrawCommandsStart", "FrameCompleted")


def adb(serial, *args, binary=False):
    cmd = ["adb", "-s", serial, *args]
    out = subprocess.run(cmd, capture_output=True)
    if out.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)}: {out.stderr.decode(errors='replace')}")
    return out.stdout if binary else out.stdout.decode(errors="replace")


def collect(serial, hold_ms):
    adb(serial, "shell", "dumpsys", "gfxinfo", PKG, "reset")
    adb(serial, "shell", "input", "swipe",
        str(HOLD_X), str(HOLD_Y), str(HOLD_X), str(HOLD_Y), str(hold_ms))
    time.sleep(1)
    return adb(serial, "shell", "dumpsys", "gfxinfo", PKG, "framestats")


def parse(dump):
    """Returns (record_ms, gpu_ms). Skips frames the platform itself discarded (Flags != 0)."""
    record, gpu = [], []
    idx = None
    for line in dump.splitlines():
        line = line.strip()
        if line.startswith("Flags,"):
            names = line.split(",")
            missing = [n for n in NEEDED if n not in names]
            if missing:
                raise RuntimeError(f"framestats is missing {missing}; got {names}")
            idx = {n: names.index(n) for n in NEEDED}
            continue
        if idx is None or not line or not line[0].isdigit():
            continue
        # Rows carry a trailing comma, so a whole-row int() parse throws and would silently drop
        # every frame. Only the columns actually used are converted.
        c = line.split(",")
        if len(c) <= max(idx.values()):
            continue
        try:
            draw, sync = int(c[idx["DrawStart"]]), int(c[idx["SyncStart"]])
            issue, done = int(c[idx["IssueDrawCommandsStart"]]), int(c[idx["FrameCompleted"]])
            if int(c[idx["Flags"]]) != 0:
                continue
        except ValueError:
            continue
        if sync <= draw or done <= issue:
            continue
        record.append((sync - draw) / 1e6)
        gpu.append((done - issue) / 1e6)
    return record, gpu


def summarise(name, values):
    if not values:
        return {"phase": name, "n": 0}
    s = sorted(values)
    return {
        "phase": name,
        "n": len(s),
        "median": round(statistics.median(s), 3),
        "mean": round(statistics.fmean(s), 3),
        "p90": round(s[int(len(s) * 0.90)], 3),
        "p99": round(s[min(int(len(s) * 0.99), len(s) - 1)], 3),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--hold-ms", type=int, default=6000)
    ap.add_argument("--label", default="")
    args = ap.parse_args()

    record, gpu = [], []
    for i in range(args.runs):
        r, g = parse(collect(args.serial, args.hold_ms))
        print(f"run {i + 1}: {len(r)} frames", file=sys.stderr)
        record += r
        gpu += g

    result = {
        "label": args.label,
        "runs": args.runs,
        "phases": [summarise("record", record), summarise("gpu", gpu)],
    }
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
