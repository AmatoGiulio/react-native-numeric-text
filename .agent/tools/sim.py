#!/usr/bin/env python3
"""Render a candidate model to the recorder's own format, with no device in the loop.

    python3 .agent/tools/sim.py artifacts/sim_stack --preset=single
    python3 .agent/tools/sim.py artifacts/sim_stack_alt --preset=alt60
    python3 .agent/tools/sim.py artifacts/sim_stack_roll --preset=roll

Then analyse it with EXACTLY the tools a device capture goes through — that is the whole design:

    python3 .agent/tools/compare.py artifacts/sim_stack
    python3 .agent/tools/balance.py artifacts/sim_stack artifacts/sim_stack_alt artifacts/sim_stack_roll

## Why this exists

A round costs a Gradle build, an install, four drives and a pull — about twenty minutes. At that
price the search runs over CONSTANTS, because trying a different MODEL costs a day. That is
visible in the git log: the headline went 0.031 → 0.010 in two hours on two structural changes,
and the four hours after that moved knobs by hundredths against differences smaller than the
measurement's own scatter.

Here a candidate costs two seconds. The glyphs are cut out of the iOS recordings themselves, so a
model is drawn in the reference's own typeface at the reference's own scale and lands in the same
8-bit alpha planes the recorder writes. Nothing downstream can tell the difference.

## What it is NOT

It does not simulate Android. It renders a MODEL. A model that wins here still has to be ported to
Kotlin and re-measured on a device, because the renderer, the blur path and the frame clock are all
real things this does not have. What it buys is the right to throw away nine models out of ten
before paying for any of them.

Digits 3, 5, 8 and 9 never settle in any recording on disk, so they fall back to the nearest
available width and any WIDTH number off the roll preset should be distrusted for that reason.
The single crossing and the alternation use only digits the atlas really holds.
"""

import argparse
import glob
import json
import math
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ground_truth import load, ink_box, columns_of  # noqa: E402

HERE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# Captures whose SETTLED frame is a known number. The recorder's `label` is the value a run STARTS
# from, so the settled text has to be supplied here rather than read.
ATLAS_SOURCES = [
    ("artifacts/gt_ios_ref/run-*.json", "1,160", None),
    ("artifacts/gt_ios_up5/run-*.json", "1,242", None),
    ("artifacts/gt_ios_bursts/*.json", "2,722", "1,000"),
]

# The geometry every render is laid out in: canvas size, column boxes and glyph height all come
# from one reference capture, so a simulated run and a recorded one are directly comparable.
LAYOUT_SOURCE = "artifacts/gt_ios_ref/run-1785683986267"

FPS = 60.0


# ── Apple's constants, read out of SwiftUICore. See IOS_GROUND_TRUTH.md. ─────────────────────────

# relativeOffset — a per-transition ENTRY amplitude, not a stop spacing. Apple's stored value is
# 0.59375 and it does NOT transfer at face value here: swept against the single crossing the
# extent error is 0.196 at 0.59375 and 0.033 at 0.44. The likely reason is a parameterisation
# rather than a wrong constant — `p` here runs 1 -> 0 -> -1, so an entry starting at OFFSET and a
# departure ending at -OFFSET are 2*OFFSET apart at full spread, and Apple's number may be the
# transition's TOTAL travel rather than its one-sided amplitude. Half of 0.59375 is 0.297, the fit
# says 0.44, and neither reading is confirmed. Test it before treating 0.44 as meaningful.
OFFSET = 0.44
SCALE_AMOUNT = 0.3984   # scale — how much a glyph shrinks at full distance
WAVE_TOTAL = 0.150      # delay — the wave's TOTAL spread, divided by the gaps

# Measured on this engine and carried over unchanged; see NumericTextTimeline.kt.
RESPONSE = 0.30
DAMPING = 0.90
ENTER_ALPHA_EXPONENT = 0.52
# Swept in the simulator, not carried over: at the device engine's 1.20 the crossing's ink floor
# reads 0.663 against the reference's 0.515, because a stack fades its pair more slowly than a
# strip does. 2.4 puts the floor within 0.024 and does not cost the travel.
EXIT_ALPHA_EXPONENT = 2.40

# Not yet fitted. The device engine's blur is a RenderEffect with its own radius quantisation, and
# the number here only has to make `burst.py`'s sharpness land near the reference's 0.600 — fit it
# in the simulator before touching the Kotlin.
BLUR_RELATIVE = 0.16

# There is ~35 ms between a value change and the first frame that shows it, on both platforms, and
# every measured start time in the ground truth includes it.
LATENCY = 0.035

# Place each entry on the DRUM rather than on a flat line — ten faces of a decagon, offset
# `APOTHEM * sin(angle)` and a `cos` foreshortening applied to height only. Carried over from
# NumericTextTimeline.kt, where it is what took the single crossing from 0.031 to 0.010.
DRUM = False
FACES = 10
FACE_ANGLE = 2.0 * math.pi / FACES
APOTHEM = 0.555

# The chase: how hard a column is being pressed. Steps on every change that lands while the column
# is not at rest and bleeds at a constant RATE, so it saturates below a critical cadence and sits
# at zero above one. Exactly zero from rest, so nothing it drives can move the single crossing.
# Carried over from NumericTextTimeline.kt, where it is what produces the alternation's band.
CROWD_SPREAD = 0.0
CROWD_STEP = 0.60
CROWD_RELAX = 0.15


class Atlas:
    """Settled digit rasters, cut out of the iOS recordings.

    Each digit is stored cropped to its own ink, plus where its ink sat relative to the column's
    settled baseline, so it can be put back exactly where the reference draws it.
    """

    def __init__(self):
        self.glyphs = {}
        self.height = None

    @staticmethod
    def _columns(prefix):
        meta, frames = load(prefix)
        y0, y1, x0, x1 = ink_box(frames)
        w = frames[:, y0:y1, x0:x1]
        return w, columns_of(w[-1].astype(np.float64))

    def load_from(self, sources):
        for pattern, settled, label in sources:
            for path in sorted(glob.glob(os.path.join(HERE, pattern))):
                if path.endswith("reference.json"):
                    continue
                if label and json.load(open(path)).get("label") != label:
                    continue
                try:
                    w, groups = self._columns(path[:-5])
                except Exception:
                    continue
                if len(groups) != len(settled):
                    continue
                for ch, (a, b) in zip(settled, groups):
                    # The separator is cut out too. It has no strip to roll along, but it is a
                    # column of its own in `columns_of`, and drawing it as a digit merges it with
                    # its neighbour and loses a column from every downstream tool.
                    if ch in self.glyphs:
                        continue
                    patch = w[-1][:, a:b]
                    rows = np.nonzero(patch.sum(axis=1) > patch.sum(axis=1).max() * 0.02)[0]
                    cols = np.nonzero(patch.sum(axis=0) > patch.sum(axis=0).max() * 0.02)[0]
                    if len(rows) < 2 or len(cols) < 2:
                        continue
                    self.glyphs[ch] = patch[rows[0]:rows[-1] + 1, cols[0]:cols[-1] + 1]
                    self.height = max(self.height or 0, int(rows[-1] - rows[0] + 1))
        if not self.glyphs:
            raise SystemExit("no settled glyphs could be cut out — are the iOS captures on disk?")
        return self

    def get(self, ch):
        """The digit, or the nearest one by width when the atlas never saw it settle."""
        if ch in self.glyphs:
            return self.glyphs[ch]
        widths = {k: v.shape[1] for k, v in self.glyphs.items()}
        target = float(np.median(list(widths.values())))
        return self.glyphs[min(widths, key=lambda k: abs(widths[k] - target))]

    @property
    def missing(self):
        return sorted(set("0123456789") - set(self.glyphs))


class Layout:
    """Canvas and column boxes, taken from a reference capture so the two are comparable."""

    def __init__(self, prefix):
        meta, frames = load(prefix)
        y0, y1, x0, x1 = ink_box(frames)
        settled = frames[-1, y0:y1, x0:x1].astype(np.float64)
        self.groups = columns_of(settled)
        self.height = frames.shape[1]
        self.width = frames.shape[2]
        self.ox, self.oy = int(x0), int(y0)
        rows = settled.sum(axis=1)
        lit = np.nonzero(rows > rows.max() * 0.02)[0]
        self.glyph_height = float(lit[-1] - lit[0])
        self.baseline = float((lit[0] + lit[-1]) / 2.0)


def gaussian(patch, sigma):
    """Separable gaussian. No scipy on this machine, and a box blur reads as square at these radii."""
    if sigma < 0.35:
        return patch
    radius = max(1, int(math.ceil(sigma * 3)))
    x = np.arange(-radius, radius + 1, dtype=np.float64)
    k = np.exp(-(x * x) / (2 * sigma * sigma))
    k /= k.sum()
    pad = ((radius, radius), (radius, radius))
    out = np.pad(patch, pad, mode="constant")
    out = np.apply_along_axis(lambda r: np.convolve(r, k, mode="same"), 1, out)
    out = np.apply_along_axis(lambda c: np.convolve(c, k, mode="same"), 0, out)
    return out


class Spring:
    """Semi-implicit Euler, the same integrator `stepPosition` uses, so results carry across."""

    __slots__ = ("x", "v", "target")

    def __init__(self, x, target=None):
        self.x = float(x)
        self.v = 0.0
        self.target = float(x if target is None else target)

    def step(self, dt, response=None, damping=None):
        # Read the module globals at CALL time, never as default arguments. A default is bound when
        # the function is defined, so a sweep that sets `sim.RESPONSE` between renders silently ran
        # five identical models and printed five identical rows — the same class of no-op as an
        # `adb install` that fails and leaves the previous binary in place.
        response = RESPONSE if response is None else response
        damping = DAMPING if damping is None else damping
        omega = 2.0 * math.pi / max(0.05, response)
        self.v += ((omega * omega * (self.target - self.x)) - (2.0 * damping * omega * self.v)) * dt
        self.x += self.v * dt


class Entry:
    """One transition, with its own clock. NOT a stop on a shared strip.

    `p` runs 1 → 0 → -1: it enters from `+OFFSET`, rests at 0, and leaves towards `-OFFSET`. A
    superseded entry's target becomes -1 and STAYS there however many further changes arrive — a
    removal in SwiftUI runs to its removal state and is then gone, it does not keep travelling.
    That bound is what keeps the ink centroid still.
    """

    __slots__ = ("ch", "spring")

    def __init__(self, ch, start):
        self.ch = ch
        self.spring = Spring(start, 0.0)

    @property
    def p(self):
        return self.spring.x


class StackColumn:
    """A column is a STACK of transitions, not a position on a strip.

    The difference is the whole point of this file. A strip has one position, so its two visible
    glyphs are a function of that position and its ink centroid IS that position — when the column
    slides, the ink slides, which is exactly the one defect left on the device engine (travel 0.517
    against the reference's 0.119 through a roll).

    A stack has no position. Entries enter from `+OFFSET` and leave towards `-OFFSET`, so their
    masses sit either side of rest and the centroid stays put however many are alive. Travel is not
    fitted here; it falls out.
    """

    def __init__(self, ch, x0, x1):
        self.x0, self.x1 = x0, x1
        self.entries = [Entry(ch, 0.0)]
        self.pending = []
        self.crowd = 0.0

    def change(self, ch, hold):
        """Queue the change behind its share of the wave.

        The hold belongs to the CHANGE, not to the arriving glyph: holding only the newcomer lets
        the outgoing one start leaving immediately, and the column goes dark in the gap between
        them — measured here as an ink floor of 0.000 against the reference's 0.515. Each queued
        change also keeps its own countdown, because a single pending slot per column makes a
        burst pile every change that arrives during one hold into one commit.
        """
        self.pending.append([hold, ch])

    def step(self, dt):
        for slot in self.pending:
            slot[0] -= dt
        self.crowd = max(0.0, self.crowd - dt / CROWD_RELAX)
        while self.pending and self.pending[0][0] <= 0.0:
            _, ch = self.pending.pop(0)
            # The step is taken only when the column was NOT at rest — that is what keeps the
            # signal identically zero for a single change, so the crossing cannot move.
            if any(abs(e.p) > 0.01 for e in self.entries):
                self.crowd = min(1.0, self.crowd + CROWD_STEP)
            for e in self.entries:
                e.spring.target = -1.0
            self.entries.append(Entry(ch, 1.0))
        for e in self.entries:
            e.spring.step(dt)
        # Cull what has left. Without this a press-and-hold grows an entry per change forever.
        self.entries = [e for e in self.entries if e.p > -1.6 and self._alpha_raw(e) > 0.004]
        if not self.entries:
            self.entries = [Entry("0", 0.0)]

    @staticmethod
    def _alpha_raw(e):
        presence = max(0.0, 1.0 - abs(e.p))
        if presence <= 0.0:
            return 0.0
        # Weighted by how close a glyph is to ITS OWN rest, not by which entry is newest — a binary
        # role makes two glyphs swap curves the instant a change lands, and under an alternation
        # that was the whole behaviour (swing 0.307 against 0.103).
        exponent = EXIT_ALPHA_EXPONENT + (ENTER_ALPHA_EXPONENT - EXIT_ALPHA_EXPONENT) * presence
        return presence ** exponent

    def samples(self):
        """(ch, offset in line heights, scale, alpha, blur in line heights) per live entry.

        The alphas are CAPPED to sum to one, not normalised to it. A crossfade is a convex
        combination, which is why summing N independent alphas is wrong — that is what brightened
        the ink 0.581 → 0.816 when overlapping transitions were tried on the device and got them
        reverted eleven minutes later. But the reference's total ink DIPS through a crossing, to
        0.515 of a settled glyph, so forcing the sum UP to one would destroy the primary metric.
        Cap, never boost.
        """
        raw = [self._alpha_raw(e) for e in self.entries]
        total = sum(raw)
        scale = 1.0 / total if total > 1.0 else 1.0
        out = []
        for e, a in zip(self.entries, raw):
            if a * scale <= 0.004:
                continue
            distance = min(1.0, abs(e.p))
            shrink = 1.0 - SCALE_AMOUNT * distance
            if DRUM:
                # The synthesis: keep the stack's per-transition identity, which is what produces
                # the travel and the balance, and place each entry on the DRUM instead of on a flat
                # line. The drum is what halved the single crossing on the device (0.031 -> 0.010)
                # and what produces the alternation's gap when it is wide; a stack does not have to
                # pay for that width the way a strip does, because its crossing is set by one
                # transition's own curve rather than by the spacing of two stops.
                angle = e.p * FACE_ANGLE
                offset = APOTHEM * (1.0 + CROWD_SPREAD * self.crowd) * math.sin(angle)
                squash = math.cos(angle)
            else:
                offset = OFFSET * (1.0 + CROWD_SPREAD * self.crowd) * e.p
                squash = 1.0
            out.append((e.ch, offset, shrink, a * scale, BLUR_RELATIVE * distance, squash))
        return out


class StackModel:
    def __init__(self, text, layout):
        self.layout = layout
        self.columns = []
        for ch, (a, b) in zip(text, layout.groups):
            self.columns.append(StackColumn(ch, a, b))
        self.text = text

    def change(self, text):
        """Apply a new value, holding each changed column by its share of the wave.

        The wave is a fixed TOTAL divided by the gaps, and the leader waits HALF a gap: at zero
        this engine read 35/102/186 and at a full gap 101/176/243, bracketing the reference's
        70/137/220.
        """
        changed = [i for i, (old, new) in enumerate(zip(self.text, text)) if old != new]
        gap = WAVE_TOTAL / max(1, len(changed) - 1) if len(changed) > 1 else 0.0
        for order, i in enumerate(changed):
            self.columns[i].change(text[i], LATENCY + gap * (order + 0.5))
        self.text = text

    def step(self, dt):
        for c in self.columns:
            c.step(dt)

    def draw(self, atlas):
        canvas = np.zeros((self.layout.height, self.layout.width), dtype=np.float64)
        lh = self.layout.glyph_height
        for col in self.columns:
            cx = self.layout.ox + (col.x0 + col.x1) / 2.0
            for ch, offset, scale, alpha, blur, squash in col.samples():
                raster = atlas.get(ch).astype(np.float64)
                h, w = raster.shape
                # The drum turns about a horizontal axis: a face's WIDTH does not change as it
                # rolls, its height does. Squash is 1.0 on the flat model.
                sh = max(1, int(round(h * scale * squash)))
                sw = max(1, int(round(w * scale)))
                if (sh, sw) != (h, w):
                    raster = np.asarray(
                        Image.fromarray(raster.astype(np.uint8)).resize((sw, sh), Image.LANCZOS),
                        dtype=np.float64,
                    )
                if blur > 0:
                    raster = gaussian(raster, blur * lh)
                raster = raster * alpha
                top = int(round(self.layout.oy + self.layout.baseline + offset * lh - raster.shape[0] / 2.0))
                left = int(round(cx - raster.shape[1] / 2.0))
                y0, x0 = max(0, top), max(0, left)
                y1 = min(canvas.shape[0], top + raster.shape[0])
                x1 = min(canvas.shape[1], left + raster.shape[1])
                if y1 <= y0 or x1 <= x0:
                    continue
                canvas[y0:y1, x0:x1] += raster[y0 - top:y1 - top, x0 - left:x1 - left]
        return np.clip(canvas, 0, 255).astype(np.uint8)


# ── The presets, matching example/src/Showcase.tsx ───────────────────────────────────────────────

def fmt(value):
    return f"{value:,}"


# Every device preset parks the value at its starting point, waits, and only then runs — so mark 0
# is the reset and mark 1 is the first real change. Without the same quiet lead-in here both marks
# land at t=0 and `compare.py` has no transition to align on.
LEAD_IN = 500.0

PRESETS = {
    # (starting value, [(delay ms after the lead-in, new value)], settle ms after the last)
    "single": (1242, [(0, 1160)], 1000),
    "up": (1160, [(0, 1242)], 1000),
    "alt60": (1000, [(60 * i, 1000 + (i + 1) % 2) for i in range(20)], 1200),
    "alt120": (1000, [(120 * i, 1000 + (i + 1) % 2) for i in range(20)], 1200),
    "alt240": (1000, [(240 * i, 1000 + (i + 1) % 2) for i in range(20)], 1200),
    "roll": (1000, [(30 * i, 1000 + 123 * (i + 1)) for i in range(14)], 1200),
}


def render(preset, atlas, layout):
    start, schedule, tail = PRESETS[preset]
    schedule = [(LEAD_IN + d, v) for d, v in schedule]
    model = StackModel(fmt(start), layout)

    # Mark 0 is the preset's own reset to the starting value, exactly as the device presets record
    # it — `grid.py --mark=burst` and `balance.py` both rely on that being there.
    marks = [{"t": 0.0, "label": fmt(start)}]
    for delay, value in schedule:
        marks.append({"t": float(delay), "label": fmt(value)})
    end = schedule[-1][0] + tail

    frames, times = [], []
    dt = 1.0 / FPS          # seconds, for the springs
    step_ms = dt * 1000.0   # milliseconds, for the recorder's clock
    t = 0.0
    pending = list(schedule)
    while t <= end:
        while pending and pending[0][0] <= t:
            model.change(fmt(pending.pop(0)[1]))
        model.step(dt)
        frames.append(model.draw(atlas))
        times.append(t)
        t += step_ms

    meta = {
        "frames": len(frames),
        "width": layout.width,
        "height": layout.height,
        "times": times,
        "marks": marks,
        "label": fmt(start),
        "format": "gray8-alpha",
        "simulated": True,
        "model": "stack",
    }
    return meta, np.stack(frames)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("outdir")
    ap.add_argument("--preset", default="single", choices=sorted(PRESETS))
    ap.add_argument("--runs", type=int, default=1)
    args = ap.parse_args()

    atlas = Atlas().load_from(ATLAS_SOURCES)
    layout = Layout(os.path.join(HERE, LAYOUT_SOURCE))
    print(f"   atlante: {''.join(sorted(atlas.glyphs))}"
          + (f"   mancanti (sostituite): {''.join(atlas.missing)}" if atlas.missing else ""))
    print(f"   tela: {layout.width}x{layout.height}, glifo {layout.glyph_height:.0f}px, "
          f"{len(layout.groups)} colonne")

    os.makedirs(args.outdir, exist_ok=True)
    for i in range(args.runs):
        meta, frames = render(args.preset, atlas, layout)
        prefix = os.path.join(args.outdir, f"run-sim{i}-{args.preset}")
        frames.tofile(prefix + ".bin")
        with open(prefix + ".json", "w") as fh:
            json.dump(meta, fh)
        print(f"   {prefix}: {meta['frames']} fotogrammi")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
