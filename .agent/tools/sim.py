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

## Two models, and the difference matters

`--model=stack` is this file's own model: swept here, never ported, useful for asking "would this
shape work at all".

`--model=kotlin` is `NumericTextTimeline.kt` in `stackMode`, ported line for line, with its
constants PARSED OUT OF THE SOURCE at run time rather than copied. Use it to ask "what will the
device do", and to check a device round against something.

They were once believed to be the same model, and the belief cost a fortnight: the simulator
reported the stack's ink centroid at 0.190 through an alternation and the device reported 0.549, and
that gap was read as "the port has a bug" and then as "the model does not survive a real renderer".
Neither. Four divergences, all in this file:

| | this file did | the engine does |
|---|---|---|
| opacity | `(1 - abs(q)) ** k` off the position clock — NOT monotone, so a discarded glyph relights to full black as it crosses rest | its own monotone clock |
| direction | none: every glyph entered from `+1` and left towards `-1` | born at `-lastDirection`, leaves to `+lastDirection` — which SWAP on every change of an alternation |
| blur | the constant used as a gaussian sigma | used as a LENGTH: halved, quantised to 1/8 px, then Skia's `sigma = 0.577r + 0.5`. **3.5x** apart |
| scale | `1 - 0.3984 * d`, a floor of 0.60 | `1 - 0.6016 * d`, a floor of 0.3984 — 0.3984 is the FINAL scale |

Aligned, the two agree: on the single crossing, ink floor within 0.014 and extent within 0.02 on
every column, onsets within 2 ms; on the 60 ms alternation, band 1.023 against the device's 0.995
± 0.028, i.e. inside the device's own run-to-run scatter.

It still does not simulate Android — same renderer in outline, but not the same rasteriser, and the
frame clock here is a fixed 60 Hz where a device's is not. A model that wins here is still worth a
round. What it buys is the right to throw away nine models out of ten before paying for any of them.

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
RESPONSE = 0.353        # offset spring, wn 17.8 rad/s (measured)
DAMPING = 0.55          # measured overshoot 12.1%
SLOW_RESPONSE = 0.277   # scale/alpha/blur, wn 22.7 rad/s (measured)
SLOW_DAMPING = 1.00     # critically damped: these three never overshoot
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


# ── The Kotlin engine's own constants, read out of the source at run time ────────────────────────
#
# `--model=kotlin` renders what `NumericTextTimeline.kt` renders in `stackMode`, and not a model
# that once resembled it. The constants above are the SIMULATOR's, swept here and never ported; the
# ones below are the ENGINE's, and copying them by hand is how the two drifted far enough apart that
# the simulator said travel 0.190 and the device said 0.549 for what was called the same model.
# Parsed rather than duplicated, so that particular lie cannot be told again.
ENGINE_SOURCE = "android/src/main/java/com/numerictext/NumericTextTimeline.kt"

# ONE unit bridge, and it is the only fitted number in the Kotlin model here.
#
# The engine's lengths are relative to `lineHeightPx`, the text layout height in device pixels;
# this file draws in `Layout.glyph_height`, the settled number's ink band in the iOS capture's
# pixels. Neither capture records the other's unit, so the ratio has to be calibrated — but it is a
# GEOMETRIC constant, not a knob: it is fixed by one measurement and then has to land every other.
#
# Calibrated on the single crossing's extent in column 2 alone (device 1.224). It then reproduces,
# with nothing further touched: columns 3 and 4's extent to 0.02, all three ink floors to 0.015,
# every column's onset to 2 ms and its trough to 2 ms. A fudge does not do that.
#
# 1.384 sits between the 1.351 first assumed for this face and the 1.504 that `STACK_OFFSET`'s own
# comment implies. Getting it wrong is invisible on its own: at 1.0 the simulator ALSO reproduced
# the device's extent — because it was over-blurring by 3.5x at the same time, and the two errors
# cancelled in exactly the quantity used to check them. See `blur_sigma`.
CAP_PER_LINE = float(os.environ.get("SIM_CAP_PER_LINE", 1.0 / 1.384))


# ── EXPERIMENTS, and they are NOT in the engine ──────────────────────────────────────────────────
#
# Everything below is a candidate that exists only in this file. `--model=kotlin` without them is
# the engine; with any of them set it is the engine PLUS a proposal. Read at call time, never bound
# as a default argument, and never written into `engine_constants` — the whole value of that
# function is that it cannot be edited from here.
#
# All four are gated on ONE signal, `flipRaw`, which rises only when the value REVERSES onto a
# column that is still in flight. It is therefore identically zero for a single change (nothing is
# in flight) and identically zero through a roll (+123 every time, the direction never reverses).
# That is not a convenience — it is what makes the single crossing and the roll untouchable by
# construction rather than by a promise, and each sweep re-checks that both came out bit-identical.
def flip_knobs():
    e = os.environ.get
    return {
        "step":     float(e("FLIP_STEP", 0.60)),      # impulse per reversal, as CROWD_STEP
        "relax":    float(e("FLIP_RELAX", 0.15)),     # seconds to bleed back to zero
        "shorten":  float(e("FLIP_SHORTEN", 0.0)),    # compress the drawn offset
        "damp":     float(e("FLIP_DAMP", 0.0)),       # kill velocity of live entries on the flip
        "travel":   float(e("FLIP_TRAVEL", 0.0)),     # birth the newcomer closer in
        "fade":     float(e("FLIP_FADE", 0.0)),       # speed the superseded glyphs' fade
        "depart":   float(e("FLIP_DEPART", 0.0)),     # shorten the DEPARTURE, the mirror of travel
        "mirror":   float(e("FLIP_MIRROR", 0.0)),     # birth the newcomer OPPOSITE the glyph it supersedes
        "level":    float(e("FLIP_LEVEL", 0.0)),      # even the live glyphs' OPACITY, moving nothing
        # PARK, and it is not a seventh lever — it changes what a transition is.
        #
        # Every lever above leaves each transition converging: the newcomer heads for rest and the
        # superseded one heads for the exit, and all a lever can do is change where they start or
        # how fast they get there. That is why travel and band are coupled — stilling the centroid
        # means pulling ink to the middle, and pulling ink to the middle merges the pair.
        #
        # The reference does not converge under crowding. TRANSITION_MODEL.md §5, measured: "two
        # half-formed glyphs, one above and one below, both at roughly half scale and half opacity,
        # essentially unchanging for the whole burst". It HOLDS them apart. So: under a reversal in
        # flight, aim the pair at ±`at` instead of at rest and at the exit, and let opacity and
        # scale go on crossfading underneath. Separated and symmetric at once, which is the
        # combination no amount of travel-shortening reaches.
        "park":     float(e("FLIP_PARK", 0.0)),       # how much of the way to the held pair
        "at":       float(e("FLIP_PARK_AT", 0.5)),    # where the held pair sits, in entry amplitudes
        # Drive the engine's OWN arrival gate off the reversal signal as well as off `crowdRaw`.
        #
        # `STACK_ARRIVAL_GATE` already exists to stop a glyph being bright before it has arrived,
        # and the trace says that is precisely the remaining defect: under an alternation the
        # brightest thing in the column is the newcomer travelling inward from ±0.95, alternating
        # sides, and the centroid follows it. The gate is driven by `crowdRaw`, which even with the
        # rest test fixed stays lower here than through a roll.
        "gate":     float(e("FLIP_GATE", 0.0)),       # extra gate drive under a reversal
        "gatehard": float(e("FLIP_GATEHARD", 0.0)),   # raise the gate's own ceiling towards 1
        # ONE defect, ONE hypothesis: the newcomer is too visible while it is still far from rest,
        # and its journey inward — bright, alternating sides — is what swings the centroid.
        #
        # The engine already has a mechanism for "bright means arrived", `STACK_ARRIVAL_GATE`, and
        # it CANNOT fix this: it redistributes rather than attenuates, handing the far glyph's share
        # of the opacity to the nearest one, and under a 60 ms alternation nothing has arrived, so
        # the share lands on a glyph at ±0.4 and the centroid moves further. Measured: 0.406 → 0.454.
        #
        # So: attenuate the far glyph and give its brightness to NOBODY. Applied after the
        # redistribution and after the ceiling, so nothing hands it back. The column's total ink
        # therefore dips while a reversal is in progress, which is the cost this has to be judged on
        # — the reference's own ink does dip through a crossing (to 0.515), but that is measured for
        # an isolated change, not for an alternation.
        "dim":      float(e("FLIP_DIM", 0.0)),        # attenuate by distance from rest, no giveback
        # Iteration 1. Measured structure of the two lobes, reference against this engine:
        # width 0.168 vs 0.141, separation 0.738 vs 0.599, total ink 0.321 vs 0.277 — the
        # reference's whole pair is 16-23% BIGGER, not differently arranged. And inside the engine
        # no glyph is ever within 0.12 of rest under a 60 ms alternation, so the central ink is the
        # TAILS of glyphs at ±0.3-0.45, not glyphs sitting in the middle.
        "big":      float(e("FLIP_BIG", 0.0)),        # shrink less under a reversal
        # Iteration 2, a DIAGNOSTIC rather than a candidate. `big` moved the excursion (0.406 ->
        # 0.385) and the imbalance (0.121 -> 0.095), which no photometric or amplitude lever did.
        # But enlarging a glyph changes two things at once: the area it draws, and its weight in the
        # ink, which goes as alpha * scale^2. This applies the WEIGHT change without the size
        # change, so the two can be told apart.
        "area":     float(e("FLIP_AREA", 0.0)),       # same ink weight as `big`, same size as base
        # Iteration 4. `area` at 0.5 improved excursion, band and imbalance together but left the
        # column 28% brighter than the reference; calibrated to the reference's own ink (0.16) most
        # of the gain went back. What it does is brighten in PROPORTION TO DISTANCE, i.e. it moves
        # weight towards the far glyph. Disproved hypothesis 1 moved weight the other way, far to
        # near, and failed — so the sign was wrong, not the idea. This is that redistribution with
        # the total held constant, so it cannot win by simply adding ink.
        "tilt":     float(e("FLIP_TILT", 0.0)),       # weight towards the FAR glyph, total held
        # Iteration 5. Logical lifecycle vs VISUAL lifecycle: the transitions go on running and
        # fading exactly as measured, but only the strongest few are composited. Measured inside the
        # engine, a 60 ms alternation draws 5 glyphs of which the top two carry 87% of the ink — the
        # other three are the ones accused of filling the middle. Faded out rather than cut, so
        # nothing pops when the burst ends and the signal decays.
        "keep":     float(e("FLIP_KEEP", 0.0)),       # how many contributors survive compositing
        # Stretch each glyph vertically about its OWN centre, under a reversal only.
        #
        # Measured on the area-normalised mean profiles: the reference's lobes are 30-45% wider in
        # FWHM (0.585/0.535 against 0.405/0.515), it carries 2.3x our mass beyond ±0.55, and we
        # carry 2.0x its ink inside ±0.05. Our distribution is not heavy-tailed — the tail ratio is
        # 1.33 against 1.27, both far from a Gaussian's 1.82 — it is simply NARROWER. Reducing blur
        # would narrow it further, which is why that test was not run.
        #
        # This changes only the height a glyph is drawn at. The raster is centred on its own offset,
        # so stretching cannot move it: no offset, no horizontal scale, no alpha, no blur, no
        # lifecycle, no change to how many glyphs are live.
        "stretch":  float(e("FLIP_STRETCH", 1.0)),    # vertical extent multiplier at full signal
        # The same stretch, on the DEPARTING glyph alone.
        #
        # Stretching everything moved the lower lobe onto the reference (FWHM 0.575 against 0.535,
        # width-at-a-tenth 0.720 against 0.715) and left the upper one behind at 0.425 against
        # 0.585. The upper lobe is the departing glyph, which by then is small and faint, so a
        # multiplier has little mass to act on. The reference holds its two lobes nearly equal in
        # width. Factor derived from that gap: 0.585 / 0.405 = 1.44.
        "stretchout": float(e("FLIP_STRETCH_OUT", 1.0)),
        #
        # NOTE: what was `FLIP_RESTFIX` here is now unconditional, in this port and in the Kotlin —
        # see `_step`'s `was_at_rest`. It is a fix, not a lever, so it is no longer switchable.
        #
        # The crowd impulse fires when a change lands on a column that was "not at rest", and the
        # engine tests that with `abs(target - position)`. In stack mode `position` is NEVER stepped
        # — `stepEntries` replaces `stepPosition` — so it sits at whatever `snapToTarget` last left,
        # while `target` moves with every commit. Through a ROLL that is harmless: the target walks
        # away and never comes back, so the test reads "in flight" every time and the signal is
        # right. Under an ALTERNATION the target oscillates between two stops and lands back ON the
        # stale position every other commit, so the impulse fires half as often as it should.
        #
        # Measured: crowdRaw averages 0.190 through a 60 ms alternation against 0.614 through a
        # 30 ms roll. So `STACK_ARRIVAL_GATE` — the thing whose whole job is to pair "bright" with
        # "arrived" — is barely engaged in the one regime where the brightest glyph is the one still
        # in transit. Which is the defect.

    }


def engine_constants(path=None):
    import re
    src = open(os.path.join(HERE, path or ENGINE_SOURCE)).read()
    found = dict(re.findall(r"(?:const )?val ([A-Z][A-Z0-9_]*)\s*=\s*(-?[0-9.]+)f", src))
    need = [
        "STACK_OFFSET", "STACK_FINAL_SCALE", "STACK_RESPONSE_SECONDS", "STACK_DAMPING",
        "STACK_SLOW_RESPONSE_SECONDS", "STACK_SLOW_DAMPING", "STACK_BLUR_RESPONSE_SECONDS",
        "STACK_BLUR_DAMPING", "STACK_BLUR_FRACTION", "STACK_EXIT_ALPHA_SPEEDUP",
        "STACK_CROWD_SHORTEN", "STACK_ARRIVAL_SHARPNESS", "STACK_ARRIVAL_GATE",
        "STACK_ALPHA_FLOOR", "STACK_ALPHA_CEILING", "STACK_CROWD_SPEEDUP", "STACK_DEPART_RELATIVE",
        "WAVE_TOTAL_SECONDS", "RESPONSE_SECONDS", "CROWD_STEP", "CROWD_RELAX",
        "POSITION_EPSILON", "VELOCITY_EPSILON",
    ]
    missing = [k for k in need if k not in found]
    if missing:
        raise SystemExit(f"{path or ENGINE_SOURCE} no longer defines: {', '.join(missing)}")
    return {k: float(found[k]) for k in need}


class Atlas:
    """Settled digit rasters, cut out of the iOS recordings.

    Each digit is stored cropped to its own ink, plus where its ink sat relative to the column's
    settled baseline, so it can be put back exactly where the reference draws it.
    """

    def __init__(self):
        self.glyphs = {}
        # Where each glyph's own ink centre sits relative to the NUMBER's ink centre, in capture
        # pixels. The docstring above always claimed this was stored; it was not, and every glyph
        # was drawn centred on the number's band instead of on its own place in it. A digit is
        # nearly centred so it hid, but the comma sat half a glyph too high and every simulated
        # frame showed `1·242` with a middle dot. Anything measured over the whole frame rather
        # than per column was reading that.
        self.centres = {}
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
                band = w[-1].astype(np.float64).sum(axis=1)
                band_lit = np.nonzero(band > band.max() * 0.02)[0]
                band_centre = (band_lit[0] + band_lit[-1]) / 2.0
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
                    self.centres[ch] = (rows[0] + rows[-1]) / 2.0 - band_centre
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

    TWO clocks, not one. Measured per glyph (TRANSITION_MODEL section 3): the OFFSET runs an
    underdamped spring that overshoots rest by 12% (zeta 0.55, response 353 ms), while scale, alpha
    and blur run a CRITICALLY damped one that never overshoots (zeta 1.00, response ~277 ms).
    Driving all four from one scalar is what made this file finish the transition at 183 ms against
    the reference's 420 — the ink floor and the wave were already exact, only the closing was wrong.
    """

    __slots__ = ("ch", "spring", "slow")

    def __init__(self, ch, start):
        self.ch = ch
        self.spring = Spring(start, 0.0)   # offset
        self.slow = Spring(start, 0.0)     # scale, alpha, blur

    @property
    def p(self):
        return self.spring.x

    @property
    def q(self):
        return self.slow.x


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
                e.slow.target = -1.0
            self.entries.append(Entry(ch, 1.0))
        for e in self.entries:
            e.spring.step(dt)
            e.slow.step(dt, SLOW_RESPONSE, SLOW_DAMPING)
        # Cull what has left. Without this a press-and-hold grows an entry per change forever.
        self.entries = [e for e in self.entries if e.q > -1.6 and self._alpha_raw(e) > 0.004]
        if not self.entries:
            self.entries = [Entry("0", 0.0)]

    @staticmethod
    def _alpha_raw(e):
        presence = max(0.0, 1.0 - abs(e.q))
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
            distance = min(1.0, abs(e.q))
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
        return draw_columns(self.layout, atlas,
                            [(( c.x0 + c.x1) / 2.0, c.samples()) for c in self.columns])


def draw_columns(layout, atlas, columns):
    """Rasterise `(centre_x, [(ch, offset, scale, alpha, blur, squash)])` into a recorder frame.

    Shared by both models on purpose: the moment the two rasterise differently, a difference
    between them stops being a difference between the models.
    """
    canvas = np.zeros((layout.height, layout.width), dtype=np.float64)
    lh = layout.glyph_height
    for centre, samples in columns:
        cx = layout.ox + centre
        for ch, offset, scale, alpha, blur, squash in samples:
            raster = atlas.get(ch).astype(np.float64)
            h, w = raster.shape
            # The drum turns about a horizontal axis: a face's WIDTH does not change as it rolls,
            # its height does. Squash is 1.0 on the flat model and on the Kotlin stack.
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
            # Each glyph on ITS OWN place in the band, scaled with it — a shrinking comma has to
            # shrink towards where a comma sits, not towards the middle of the digits.
            top = int(round(layout.oy + layout.baseline + atlas.centres.get(ch, 0.0) * scale
                            + offset * lh - raster.shape[0] / 2.0))
            left = int(round(cx - raster.shape[1] / 2.0))
            y0, x0 = max(0, top), max(0, left)
            y1 = min(canvas.shape[0], top + raster.shape[0])
            x1 = min(canvas.shape[1], left + raster.shape[1])
            if y1 <= y0 or x1 <= x0:
                continue
            canvas[y0:y1, x0:x1] += raster[y0 - top:y1 - top, x0 - left:x1 - left]
    return np.clip(canvas, 0, 255).astype(np.uint8)


# ── The Kotlin engine, ported line for line ──────────────────────────────────────────────────────
#
# Not "a stack model" — THIS engine, `NumericTextTimeline.kt` with `stackMode = true`, so that a
# disagreement between the simulator and the device is a disagreement about the RENDERER and the
# frame clock rather than about which model was being measured.
#
# Everything the model above does differently is a divergence that was found by writing this, and
# each one is marked `WAS:` at the line where it lives. They are not small: the simulator drove
# alpha off the position clock, had no notion of which way the value moved, and shrank a glyph to
# 0.60 where the engine shrinks it to 0.3984.


class KEntry:
    """`NumericTextTimeline.Entry` — FOUR clocks, not two.

    WAS: the simulator ran two, and derived alpha from the slow clock as `(1 - |q|) ** exponent`.
    That expression is not monotone. A superseded glyph travels from one side of rest to the other,
    so it passes through `q = 0`, where it returns FULL opacity — every glyph a fast roll throws
    away lights back up to solid black on its way out. The engine gave alpha its own monotone clock
    for exactly this reason, and the simulator never got the fix. It is the single largest
    divergence between the two, and it lives in the crowded regime where they disagree.
    """

    __slots__ = ("ch", "p", "velocity", "target", "posTarget", "q", "qVelocity",
                 "b", "bVelocity", "alpha", "alphaVelocity", "alphaTarget", "superseded",
                 "parkTarget")

    def __init__(self, ch, p):
        self.ch = ch
        self.p = float(p)
        self.velocity = 0.0
        self.target = 0.0
        self.posTarget = 0.0
        self.q = float(p)
        self.qVelocity = 0.0
        self.b = float(p)
        self.bVelocity = 0.0
        self.alpha = 0.0
        self.alphaVelocity = 0.0
        self.alphaTarget = 1.0
        self.superseded = False
        self.parkTarget = 0.0    # experiment; see flip_knobs()["park"]


class KColumn:
    def __init__(self, ch):
        self.entries = [KEntry(ch, 0.0)]
        self.entries[0].alpha = 1.0
        self.pending = []        # [remaining seconds, char, stop] — the engine's PendingStop
        self.position = 0.0      # the roll model's scalar. NEVER stepped in stack mode — but the
        self.target = 0          # crowd impulse still reads it, exactly as the engine does.
        self.crowdRaw = 0.0
        self.crowd = 0.0
        self.flipRaw = 0.0       # experiment only; see flip_knobs()
        self.lastDir = None      # the direction of this column's previous commit


class KotlinModel:
    """`stackMode = true`, including the parts that only look like bookkeeping."""

    def __init__(self, text, layout, K):
        self.K = K
        self.layout = layout
        self.text = text
        self.columns = [KColumn(ch) for ch in text]
        self.boxes = list(layout.groups)
        self.direction = 1

    def change(self, text):
        # WAS: the simulator had no direction at all — every glyph entered from `+1` and left
        # towards `-1` whatever the value did. The engine births at `-lastDirection` and departs to
        # `+lastDirection`, so on an ALTERNATION the two sides swap every single change. That is the
        # one regime where the simulator and the device disagree, and it is direction-blind there.
        try:
            self.direction = 1 if int(text.replace(",", "")) >= int(self.text.replace(",", "")) else -1
        except ValueError:
            self.direction = 1
        changed = [i for i, (old, new) in enumerate(zip(self.text, text)) if old != new]
        gap = self.K["WAVE_TOTAL_SECONDS"] / max(1, len(changed) - 1) if len(changed) > 1 else 0.0
        for order, i in enumerate(changed):
            col = self.columns[i]
            # The STOP is resolved when the change is QUEUED, not when it commits — `stopFor` runs
            # inside `setTarget`, off `goalStop()`, with the direction of THAT change. Resolving it
            # at commit time instead uses whatever direction arrived in the meantime, which under an
            # alternation is the opposite one every single time.
            goal = col.pending[-1][2] if col.pending else col.target
            col.pending.append([LATENCY + gap * (order + 0.5), text[i], goal - self.direction])
        self.text = text

    def step(self, dt):
        """`NumericTextTimeline.step`, in ITS order — the order is part of the model.

        Per column, per frame, exactly as the engine runs it:

          1. count the pending holds down, reading `wasAtRest` BEFORE any of them commits;
          2. commit those that have expired — supersede, then push the newcomer;
          3. raise `crowdRaw` if a change landed on a column that was not at rest;
          4. `stepCrowd` — decay the impulse raised in 3, in the SAME frame it was raised;
          5. `stepEntries` — the four springs, then the cull;
          6. `snapToTarget` if nothing anywhere is still moving.

        Swapping 3 and 4 makes a burst's first commit decay before it is read; moving the cull ahead
        of the springs drops a glyph one frame early. Neither shows up in a still frame.
        """
        K = self.K
        F = flip_knobs()
        dt = min(dt, 0.04)
        active = False
        for col in self.columns:
            if col.pending:
                # Read BEFORE the commits, off the roll model's stale scalar — which in stack mode
                # is only ever reset by `snapToTarget`. Replicated rather than corrected: it is
                # what decides whether a change counts as crowding on the device.
                # Ask the transitions themselves — the engine's own test since the stale-scalar bug
                # was found. A column is in flight when any live glyph is away from its own rest or
                # still carrying velocity, which is what `abs(target - position)` was standing in
                # for and got wrong under an alternation. See NumericTextTimeline.step.
                was_at_rest = all(abs(e.p) < K["POSITION_EPSILON"]
                                  and abs(e.velocity) < K["VELOCITY_EPSILON"]
                                  for e in col.entries)
                arrived = False
                for slot in col.pending:
                    slot[0] -= dt
                while col.pending and col.pending[0][0] <= 0.0:
                    _, ch, stop = col.pending.pop(0)
                    col.target = stop
                    # EXPERIMENT. A reversal that lands on a column still in flight — and nothing
                    # else. `was_at_rest` is the same test the crowd impulse uses.
                    reversed_in_flight = (col.lastDir is not None
                                          and self.direction != col.lastDir
                                          and not was_at_rest)
                    if reversed_in_flight:
                        col.flipRaw = min(1.0, col.flipRaw + F["step"])
                        if F["damp"] > 0.0:
                            # The velocities in the trace run to ±9.8 at the moment of a reversal,
                            # and they are what carries a glyph past rest and out the far side.
                            for live in col.entries:
                                live.velocity *= (1.0 - F["damp"] * col.flipRaw)
                        if F["fade"] > 0.0:
                            for live in col.entries:
                                if live.superseded:
                                    live.alpha *= (1.0 - F["fade"] * col.flipRaw)
                    col.lastDir = self.direction
                    # `lastDirection` IS read at commit time for the birth and the departure — it is
                    # a field on the engine, not a property of the queued change. So a glyph can be
                    # born on the side the LATEST change dictates while its stop came from an
                    # earlier one. Replicated, not tidied.
                    for live in col.entries:
                        if not live.superseded:
                            live.superseded = True
                            live.target = float(self.direction)
                            live.posTarget = float(self.direction) + K["STACK_DEPART_RELATIVE"] * live.p
                            # EXPERIMENT: shorten the DEPARTURE by the same signal that shortens
                            # the entry. Only `posTarget` — `target` still drives scale, blur and
                            # alpha, and those must run their full travel however far the glyph
                            # moves, which is the separation `posTarget` exists for.
                            live.posTarget *= (1.0 - F["depart"] * col.flipRaw)
                            # Where this glyph would be HELD if the column is being reversed. Only
                            # recorded here; the blend happens every frame in `_step_entries`, off
                            # the live `flipRaw`. Baking it in at commit time was the first attempt
                            # and it is wrong in a way the alternation preset cannot show: once the
                            # burst ends the signal decays but the target stays, so the last glyph
                            # parks off-centre and never settles. `target` is untouched either way,
                            # so scale, blur and alpha still run their full course — the glyph goes
                            # on fading where it stands, which is what "half opacity, essentially
                            # unchanging" needs.
                            live.parkTarget = float(self.direction) * F["at"]
                            live.alphaTarget = 0.0
                    # EXPERIMENT: birth the newcomer closer in when the value just reversed. Its
                    # own curve is untouched — it still runs to rest on the measured spring; it
                    # simply has less distance to cover, which is what a reversal at 60 ms leaves
                    # room for.
                    born = -float(self.direction) * (1.0 - F["travel"] * col.flipRaw)
                    # EXPERIMENT: birth the newcomer at the MIRROR of the glyph it supersedes,
                    # rather than at a fixed fraction of the entry amplitude.
                    #
                    # The band and the travel pull opposite ways under a `travel`-style lever:
                    # bringing everything towards rest empties the excursion and MERGES the pair,
                    # and the band is exactly the measurement that catches merging (0.760 on the
                    # reference means two separated forms; above 1 means one mass). The reference
                    # gets both at once because its pair is separated AND symmetric. Ours is
                    # separated and LOPSIDED — traced at 60 ms, the newest sits at -0.91 with alpha
                    # 0.14 while the one it replaced sits at +0.53 with alpha 0.34, so the centroid
                    # swings with the alphas instead of cancelling.
                    #
                    # Mirroring keeps the separation and takes away the lopsidedness.
                    if F["mirror"] > 0.0 and col.flipRaw > 0.0 and len(col.entries) > 1:
                        outgoing = abs(col.entries[-1].p)
                        blend = F["mirror"] * col.flipRaw
                        born = -float(self.direction) * (1.0 + blend * (outgoing - 1.0))
                    fresh = KEntry(ch, born)
                    # The arriving glyph stops PART WAY IN instead of running to rest, so the pair
                    # straddles the rest line rather than piling onto it.
                    fresh.parkTarget = -float(self.direction) * F["at"]
                    col.entries.append(fresh)
                    arrived = True
                if arrived and not was_at_rest:
                    col.crowdRaw = min(1.0, col.crowdRaw + K["CROWD_STEP"])
                active = True
            if self._step_crowd(col, dt):
                active = True
            if self._step_entries(col, dt):
                active = True
        if not active:
            self._snap()
        return active

    def _step_crowd(self, col, dt):
        K = self.K
        col.flipRaw = max(0.0, col.flipRaw - dt / flip_knobs()["relax"])   # experiment
        if col.crowdRaw <= 0.0 and col.crowd <= 0.001:
            col.crowd = 0.0
            return False
        col.crowdRaw = max(0.0, col.crowdRaw - dt / K["CROWD_RELAX"])
        col.crowd += (col.crowdRaw - col.crowd) * min(1.0, dt / K["CROWD_RELAX"])
        return True

    def _step_entries(self, col, dt):
        K = self.K
        moving = False
        eps_p, eps_v = K["POSITION_EPSILON"], K["VELOCITY_EPSILON"]
        rush = 1.0 + K["STACK_CROWD_SPEEDUP"] * col.crowdRaw
        base = 1.0                      # `response / RESPONSE_SECONDS`; durationScale is 1 here
        quick = base / rush
        fast = 2.0 * math.pi / max(0.05, K["STACK_RESPONSE_SECONDS"] * base)
        slow = 2.0 * math.pi / max(0.05, K["STACK_SLOW_RESPONSE_SECONDS"] * quick)
        blur = 2.0 * math.pi / max(0.05, K["STACK_BLUR_RESPONSE_SECONDS"] * quick)
        # EXPERIMENT: blend the position target towards the HELD pair, every frame, off the live
        # signal — so as `flipRaw` bleeds away after the last reversal the target slides back to the
        # real one and the column settles normally. Nothing else reads `park`.
        park = flip_knobs()
        hold = park["park"] * col.flipRaw
        for e in col.entries:
            aim = e.posTarget + (e.parkTarget - e.posTarget) * hold if hold > 0.0 else e.posTarget
            err = aim - e.p
            if abs(err) > eps_p or abs(e.velocity) > eps_v:
                e.velocity += ((fast * fast * err) - (2.0 * K["STACK_DAMPING"] * fast * e.velocity)) * dt
                e.p += e.velocity * dt
                moving = True
            else:
                # `aim`, not `target`. In the engine the two are the same number (posTarget is
                # target plus DEPART_RELATIVE, which is 0), so this is the engine's own line; with
                # a park in force they differ and snapping to target would teleport the glyph.
                e.p, e.velocity = aim, 0.0
            err = e.target - e.b
            if abs(err) > eps_p or abs(e.bVelocity) > eps_v:
                e.bVelocity += ((blur * blur * err) - (2.0 * K["STACK_BLUR_DAMPING"] * blur * e.bVelocity)) * dt
                e.b += e.bVelocity * dt
                moving = True
            else:
                e.b, e.bVelocity = e.target, 0.0
            err = e.alphaTarget - e.alpha
            if abs(err) > eps_p or abs(e.alphaVelocity) > eps_v:
                aw = slow * K["STACK_EXIT_ALPHA_SPEEDUP"] if e.superseded else slow
                e.alphaVelocity += ((aw * aw * err) - (2.0 * K["STACK_SLOW_DAMPING"] * aw * e.alphaVelocity)) * dt
                e.alpha += e.alphaVelocity * dt
                moving = True
            else:
                e.alpha, e.alphaVelocity = e.alphaTarget, 0.0
            err = e.target - e.q
            if abs(err) > eps_p or abs(e.qVelocity) > eps_v:
                e.qVelocity += ((slow * slow * err) - (2.0 * K["STACK_SLOW_DAMPING"] * slow * e.qVelocity)) * dt
                e.q += e.qVelocity * dt
                moving = True
            else:
                e.q, e.qVelocity = e.target, 0.0
        if len(col.entries) > 1:
            # WAS: the simulator culled on `q > -1.6 and alpha_raw > 0.004`, which can empty a
            # column. The engine keeps the newest TWO whatever their opacity.
            keep = col.entries[-2:]
            col.entries = [e for e in col.entries if e in keep or e.alpha > 0.004]
        return moving

    def _snap(self):
        # `pending` is always empty here: a column with work queued reports itself active, so the
        # engine never snaps one. The clear is the engine's, kept for the same reason.
        for col in self.columns:
            col.pending.clear()
            col.position = float(col.target)
            col.crowdRaw = col.crowd = 0.0
            col.flipRaw = 0.0
            col.lastDir = None
            last = col.entries[-1]
            col.entries = [KEntry(last.ch, 0.0)]
            col.entries[0].alpha = 1.0

    def blur_sigma(self, length):
        """`blurLengthPx` -> the sigma the device's blur actually produces, in glyph heights.

        WAS: the simulator fed `BLUR_FRACTION * |b|` straight in as a GAUSSIAN SIGMA. The device
        feeds the same number to `drawGlyphSoftware` as a blur LENGTH, and everything after that
        divides it down — `BLUR_RADIUS_FACTOR` halves it, the result is quantised to eighths of a
        pixel, and BlurMaskFilter's `radius` is a Skia radius whose sigma is `0.57735 * r + 0.5`.
        End to end that is 0.289 of the length, so the simulator was blurring **3.5x** as hard as
        the device for the identical constant.

        That is the ink floor: the simulator's crossing reads 0.346 against the device's 0.532 with
        every other quantity now matching, and over-blur is exactly what thins a crossing without
        moving its geometry. Both blur paths on the device — the RenderEffect and the software one
        the recorder draws through — run this same chain, so there is one answer and not two.
        """
        lh = self.layout.glyph_height
        length_px = length * lh
        if length_px < 0.75:                      # BLUR_MIN_PX
            return 0.0
        bucket = min(480, max(1, round(length_px * 0.5 * 8.0)))   # RADIUS_FACTOR, STEPS_PER_PX
        radius = bucket / 8.0
        return (0.57735 * radius + 0.5) / lh      # Skia's radius -> sigma, back into glyph heights

    def samples(self, col):
        """`emitStack` — the gate, the redistribution, the ceiling and the floor, in that order."""
        K = self.K
        F = flip_knobs()
        n = len(col.entries)
        raw = [0.0] * n
        total = 0.0
        plain = 0.0
        for i, e in enumerate(col.entries):
            far = min(1.0, abs(e.p))
            near = max(0.0, 1.0 - far) ** K["STACK_ARRIVAL_SHARPNESS"]
            # EXPERIMENT: the same gate, driven harder when the value is reversing.
            drive = min(1.0, col.crowdRaw + F["gate"] * col.flipRaw)
            depth = K["STACK_ARRIVAL_GATE"]
            depth += (1.0 - depth) * F["gatehard"] * col.flipRaw
            gate = 1.0 - depth * drive * (1.0 - near)
            presence = max(0.0, e.alpha * gate)
            if presence <= 0.0:
                continue
            raw[i] = presence
            total += presence
            plain += max(0.0, e.alpha)
        # EXPERIMENT: even the live glyphs' opacity towards their own mean, under a reversal only.
        # This moves NOTHING — every glyph stays exactly where it was — and it preserves the sum, so
        # the column's total ink is untouched and the band's separation survives. The trace says the
        # pair's asymmetry is half positional and half photometric: at 60 ms the two bright glyphs
        # sit at +0.53 and -0.91 carrying 0.34 and 0.14, so even mirroring them leaves the centroid
        # weighted towards the brighter one.
        if F["keep"] > 0.0 and col.flipRaw > 0.0 and total > 1e-4:
            # Iteration 5: rank by the ink each glyph actually lays down — alpha times the AREA it
            # covers, not alpha alone, because a glyph at 0.4 scale draws a sixth of what its alpha
            # suggests. Everything past the cut is faded out in proportion to the signal rather than
            # switched off, so the end of a burst is continuous.
            weight = [r * (1.0 - (1.0 - K["STACK_FINAL_SCALE"]) * min(1.0, abs(e.q))) ** 2
                      for r, e in zip(raw, col.entries)]
            order = sorted(range(len(raw)), key=lambda i: -weight[i])
            for rank, i in enumerate(order):
                if rank >= int(F["keep"]):
                    raw[i] *= max(0.0, 1.0 - col.flipRaw)
            total = sum(raw)
        if F["tilt"] > 0.0 and col.flipRaw > 0.0 and total > 1e-4:
            # Iteration 4: tilt the column's opacity towards whatever is furthest from rest, then
            # rescale so the column carries exactly the ink it carried before. A pure change of
            # SHARE, in the direction the failed hypothesis 1 went the wrong way round.
            before = sum(raw)
            k = F["tilt"] * col.flipRaw
            raw = [r * (1.0 + k * min(1.0, abs(e.p)))
                   for r, e in zip(raw, col.entries)]
            after = sum(raw)
            if after > 1e-9:
                raw = [r * before / after for r in raw]
            total = before
        if F["level"] > 0.0 and col.flipRaw > 0.0:
            live = [i for i, r in enumerate(raw) if r > 0.0]
            if len(live) > 1:
                mean = sum(raw[i] for i in live) / len(live)
                k = F["level"] * col.flipRaw
                for i in live:
                    raw[i] += (mean - raw[i]) * k
        if total > 1e-4 and plain > 0.0:
            give = plain / total
            raw = [r * give for r in raw]
            total = plain
        floor = K["STACK_ALPHA_FLOOR"] * col.crowdRaw
        if total > K["STACK_ALPHA_CEILING"]:
            norm = K["STACK_ALPHA_CEILING"] / total
        elif 1e-4 <= total <= floor:
            norm = floor / total
        else:
            norm = 1.0
        out = []
        for e, r in zip(col.entries, raw):
            alpha = r * norm
            # EXPERIMENT: dim what is far from rest, and hand its brightness to nobody. Same
            # `near` term the engine's own gate uses, so the shape of the attenuation is the
            # engine's; the difference is only that this survives the redistribution above.
            if F["dim"] > 0.0 and col.flipRaw > 0.0:
                far = min(1.0, abs(e.p))
                near = max(0.0, 1.0 - far) ** K["STACK_ARRIVAL_SHARPNESS"]
                alpha *= 1.0 - F["dim"] * col.flipRaw * (1.0 - near)
            if alpha <= 0.01:
                continue
            distance = min(1.0, abs(e.q))
            # WAS: the simulator used `1 - 0.3984 * distance`, a floor of 0.60. The engine uses
            # 0.3984 as the FINAL scale, a floor of 0.3984 — the unit bug NEXT.md records as fixed
            # in the Kotlin and never fixed here. A glyph is 50% smaller at birth than the
            # simulator thought, which moves every ink number it prints.
            shrink = 1.0 - (1.0 - K["STACK_FINAL_SCALE"]) * distance * \
                (1.0 - F["big"] * col.flipRaw)   # experiment, iteration 1
            if F["area"] > 0.0 and col.flipRaw > 0.0:
                # Iteration 2: what `big` would have done to this glyph's AREA, applied to its
                # opacity instead, leaving the drawn size at the base engine's.
                plainer = 1.0 - (1.0 - K["STACK_FINAL_SCALE"]) * distance
                bigger = 1.0 - (1.0 - K["STACK_FINAL_SCALE"]) * distance * \
                    (1.0 - F["area"] * col.flipRaw)
                shrink = plainer
                alpha = min(1.0, alpha * (bigger / plainer) ** 2)
            # WAS: the simulator multiplied by the glyph height and the engine multiplies by the
            # LINE height, so the same constant meant two lengths 1.5x apart. This is the same unit
            # mismatch `STACK_OFFSET`'s own comment records — caught in the Kotlin, never here, and
            # it is worth more than every constant in this file: 0.3950 line heights is 0.594 cap
            # heights, which is Apple's 0.59375 back again.
            offset = (K["STACK_OFFSET"] / CAP_PER_LINE) * \
                (1.0 - K["STACK_CROWD_SHORTEN"] * col.crowdRaw) * e.p
            offset *= (1.0 - flip_knobs()["shorten"] * col.flipRaw)   # experiment
            # WAS: blur rode the slow clock at 0.16. The engine gives it its own clock (zeta 0.91,
            # response 0.398) and 0.42 of a line height — nearly 3x, and it lingers.
            blurred = self.blur_sigma(K["STACK_BLUR_FRACTION"] * min(1.0, abs(e.b)))
            # The last field is the height-only multiplier the rasteriser already takes (the drum
            # model uses it to foreshorten). 1.0 is the engine; above 1.0 stretches, about the
            # glyph's own centre, because the raster is placed centred on its offset.
            tall = 1.0 + (F["stretch"] - 1.0) * col.flipRaw
            if e.superseded:
                tall *= 1.0 + (F["stretchout"] - 1.0) * col.flipRaw
            out.append((e.ch, offset, shrink, min(1.0, alpha), blurred, tall))
        return out

    def draw(self, atlas):
        return draw_columns(self.layout, atlas, [
            ((a + b) / 2.0, self.samples(col)) for col, (a, b) in zip(self.columns, self.boxes)
        ])


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


def render(preset, atlas, layout, which="stack"):
    start, schedule, tail = PRESETS[preset]
    schedule = [(LEAD_IN + d, v) for d, v in schedule]
    model = (KotlinModel(fmt(start), layout, engine_constants()) if which == "kotlin"
             else StackModel(fmt(start), layout))

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
        "model": which,
    }
    return meta, np.stack(frames)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("outdir")
    ap.add_argument("--preset", default="single", choices=sorted(PRESETS))
    ap.add_argument("--runs", type=int, default=1)
    ap.add_argument("--model", default="stack", choices=("stack", "kotlin"),
                    help="stack = this file's own swept model; kotlin = NumericTextTimeline.kt "
                         "in stackMode, constants parsed from the source")
    args = ap.parse_args()

    atlas = Atlas().load_from(ATLAS_SOURCES)
    layout = Layout(os.path.join(HERE, LAYOUT_SOURCE))
    print(f"   atlante: {''.join(sorted(atlas.glyphs))}"
          + (f"   mancanti (sostituite): {''.join(atlas.missing)}" if atlas.missing else ""))
    print(f"   tela: {layout.width}x{layout.height}, glifo {layout.glyph_height:.0f}px, "
          f"{len(layout.groups)} colonne")

    os.makedirs(args.outdir, exist_ok=True)
    for i in range(args.runs):
        meta, frames = render(args.preset, atlas, layout, args.model)
        prefix = os.path.join(args.outdir, f"run-sim{i}-{args.preset}")
        frames.tofile(prefix + ".bin")
        with open(prefix + ".json", "w") as fh:
            json.dump(meta, fh)
        print(f"   {prefix}: {meta['frames']} fotogrammi")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
