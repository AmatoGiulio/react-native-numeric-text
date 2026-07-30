#!/usr/bin/env python3
"""Pair the events of two recordings and tabulate every difference between them.

`event_timeline.py` finds and measures the transitions in one recording. This pairs them across
platforms and prints the comparison, which is the step that used to be done by eye.

Pairing is by *time*, not by similarity: both recordings are driven by the same scripted sequence,
so once one shared anchor event is identified on each side, every later event has a known scheduled
time and the pairing is arithmetic. Matching events by how alike they are would beg the question —
the whole point is to measure how unalike they are.

An event with no partner within `--tolerance` is reported as a miss rather than paired with its
neighbour, because a swallowed or split transition is a finding, not noise.

Usage:
    python3 compare_events.py --a ios.json --b and.json --anchor-a 4 --anchor-b 4 \
        --labels sequence.json --out REPORT_TABLE.md
"""
import argparse
import json

# Columns of the comparison table: (key, heading, format, "lower is better"-ness is not implied —
# these are differences from the reference, not scores).
METRICS = [
    ("dur_ms", "settle (ms)", "{:.0f}"),
    ("peak_ms", "to peak (ms)", "{:.0f}"),
    ("cascade_ms", "cascade (ms)", "{:.0f}"),
    ("ncols", "cols", "{:.0f}"),
    ("excursion", "travel (band)", "{:.4f}"),
    ("dark_min", "min ink", "{:.3f}"),
    ("wobble", "wobble", "{:.5f}"),
    ("overshoot", "overshoot", "{:.4f}"),
]


def derived(event):
    """The event's own fields plus the two that are functions of its per-column onsets."""
    lags = [v for v in event.get("lag_ms", {}).values()]
    out = dict(event)
    out["cascade_ms"] = max(lags) if lags else 0.0
    out["ncols"] = len(event.get("cols", []))
    return out


def pair(a_events, b_events, anchor_a, anchor_b, tolerance_ms, fps):
    """Events of A and B on one timeline, zeroed at their shared anchor."""
    a0 = a_events[anchor_a]["onset"]
    b0 = b_events[anchor_b]["onset"]

    a_rel = [(e["onset"] - a0) * 1000 / fps for e in a_events]
    b_rel = [(e["onset"] - b0) * 1000 / fps for e in b_events]

    pairs = []
    used = set()
    for i, ta in enumerate(a_rel):
        best, best_d = None, tolerance_ms
        for j, tb in enumerate(b_rel):
            if j in used:
                continue
            d = abs(tb - ta)
            if d <= best_d:
                best, best_d = j, d
        if best is None:
            pairs.append((ta, derived(a_events[i]), None, None))
        else:
            used.add(best)
            pairs.append((ta, derived(a_events[i]), b_rel[best], derived(b_events[best])))

    for j, tb in enumerate(b_rel):
        if j not in used:
            pairs.append((tb, None, tb, derived(b_events[j])))

    pairs.sort(key=lambda p: p[0])
    return pairs


def label_for(t_ms, labels):
    """The scripted step whose scheduled time is the latest one at or before `t_ms`."""
    if not labels:
        return ""
    best = ""
    for step in labels:
        if step["at"] <= t_ms + 40:
            best = f'{step["phase"]} → {step["value"]}'
        else:
            break
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--a", required=True, help="reference timeline json (iOS)")
    ap.add_argument("--b", required=True, help="candidate timeline json (Android)")
    ap.add_argument("--name-a", default="iOS")
    ap.add_argument("--name-b", default="Android")
    ap.add_argument("--anchor-a", type=int, required=True)
    ap.add_argument("--anchor-b", type=int, required=True)
    ap.add_argument("--labels", default=None, help="json list of {at, value, phase}")
    ap.add_argument("--tolerance", type=float, default=180.0)
    ap.add_argument("--fps", type=float, default=60.0)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    a = json.load(open(args.a))
    b = json.load(open(args.b))
    labels = json.load(open(args.labels)) if args.labels else None

    pairs = pair(
        a["events"], b["events"], args.anchor_a, args.anchor_b, args.tolerance, args.fps
    )

    lines = []
    head = ["t (ms)", "event"]
    for _, heading, _ in METRICS:
        head += [f"{heading} {args.name_a}", f"{heading} {args.name_b}", "Δ"]
    lines.append("| " + " | ".join(head) + " |")
    lines.append("|" + "|".join(["---"] * len(head)) + "|")

    sums = {key: [] for key, _, _ in METRICS}
    misses = {args.name_a: 0, args.name_b: 0}

    for t, ea, _tb, eb in pairs:
        row = [f"{t:.0f}", label_for(t, labels) or ""]
        for key, _, fmt in METRICS:
            va = ea[key] if ea else None
            vb = eb[key] if eb else None
            row.append(fmt.format(va) if va is not None else "—")
            row.append(fmt.format(vb) if vb is not None else "—")
            if va is None or vb is None:
                row.append("—")
            else:
                row.append(fmt.format(vb - va))
                sums[key].append(vb - va)
        if ea is None:
            misses[args.name_a] += 1
        if eb is None:
            misses[args.name_b] += 1
        lines.append("| " + " | ".join(row) + " |")

    lines.append("")
    lines.append(f"### Aggregate ({args.name_b} − {args.name_a})")
    lines.append("")
    lines.append("| metric | mean Δ | median Δ | worst Δ | paired |")
    lines.append("|---|---|---|---|---|")
    for key, heading, fmt in METRICS:
        vals = sorted(sums[key])
        if not vals:
            lines.append(f"| {heading} | — | — | — | 0 |")
            continue
        mean = sum(vals) / len(vals)
        median = vals[len(vals) // 2]
        worst = max(vals, key=abs)
        lines.append(
            f"| {heading} | {fmt.format(mean)} | {fmt.format(median)} "
            f"| {fmt.format(worst)} | {len(vals)} |"
        )
    lines.append("")
    lines.append(
        f"Unpaired: {misses[args.name_a]} event(s) only {args.name_b} produced, "
        f"{misses[args.name_b]} only {args.name_a} produced "
        f"(tolerance ±{args.tolerance:.0f} ms)."
    )

    text = "\n".join(lines)
    if args.out:
        with open(args.out, "w") as fh:
            fh.write(text + "\n")
        print(f"{len(pairs)} rows -> {args.out}")
    else:
        print(text)


if __name__ == "__main__":
    main()
