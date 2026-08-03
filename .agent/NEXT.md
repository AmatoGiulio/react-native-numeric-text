# START HERE

This library reimplements SwiftUI's `.numericText()` content transition on Android, and measures
itself against the real thing frame by frame. On iOS it hosts the genuine SwiftUI view, so **iOS is
the reference**; the Android renderer is the thing being fitted.

Read this, then `IOS_GROUND_TRUTH.md`. Everything in both either states a measurement or says it is
a guess. A claim without a number behind it should be distrusted.

---

## Where it stands

Rebuilt from HEAD and re-measured on 2026-08-03 evening (`artifacts/verify1*`), because the engine
file had been edited after the last capture and no round had been driven since. Everything below is
that round, not a carried-over number.

Single value change:

| | decrement 1,242→1,160 | increment 1,160→1,242 |
|---|---|---|
| ink floor error | 0.005 | 0.009 |
| crossing extent error | 0.015 | 0.011 |
| **headline** | **0.010** | **0.010** |
| wave start | ±2 ms | ±1 ms |
| the two unchanging columns | 1.000 | 1.000 |

The continuous roll, 14 changes 30 ms apart: sharpness 0.603 against 0.600, tail 635 ms against 615.

The alternation, **cadence against the same cadence** — which is new, and it is most of the story:

| | band iOS / and | travel iOS / and | balance iOS / and | width iOS / and |
|---|---|---|---|---|
| 60 ms | 0.760 / 0.814 | 0.103 / **0.259** | 0.058 / 0.061 | 0.779 / 0.786 |
| 120 ms | 1.460 / 1.335 | 0.139 / **0.261** | 0.115 / 0.122 | 0.786 / 0.823 |
| 240 ms | 1.292 / 1.275 | 0.161 / **0.279** | 0.118 / 0.116 | 0.814 / 0.815 |
| roll | — | 0.119 / **0.517** | 0.052 / 0.118 | 0.969 / 0.856 |

**The balance and the width are at parity at every cadence, and only the travel is not.** They read
as the largest defect left until `balance.py` was fixed: it took the iOS side from one file and the
Android side from a directory holding two runs at 60 ms and two at 120, so every Android
"alternation" figure it printed was the mean of two cadences. Separated, the balance at 60 ms is
0.061 against 0.058 and the width 0.786 against 0.779. The claim this file used to carry —
"the width is the real gap, 1.18x too wide, and it is what a viewer actually reports seeing" — was
that artefact plus a stale number, and it is retracted.

So there is **one** defect left, and it is the travel: this engine's ink centroid moves 1.7x to 4.3x
further than the reference's under a crowd, while matching it exactly (0.165 against 0.163) on a
single change. The reference moves its ink LESS through a crowd than through one change; this engine
moves it monotonically more, the more the changes pile up. That is a shape no constant reaches — see
the last section.

## The measuring rig — the part that makes progress possible

Both platforms record themselves. `NumericTextFrameRecorder` renders the host view's presentation
layer into an 8-bit alpha buffer every display-link tick and streams it to disk with exact
timestamps and a mark at every value change. No screen recording, no resampling, no hunting for a
sync flash.

```bash
python3 .agent/tools/sim.py artifacts/<dir> --preset=single|up|alt60|alt120|alt240|roll
                                # a MODEL, rendered off-line into the recorder's format. ~1 s.
                                # Every tool below works on its output unchanged.
.agent/tools/round.sh <name>    # one build: both crossings and the alternation, all three compared
.agent/tools/fit.sh <name>      # build, install, drive the decrement preset, pull, compare
python3 .agent/tools/compare.py artifacts/<dir> [--up]
python3 .agent/tools/band.py artifacts/<dir>    # the alternation's middle band, iOS beside it
python3 .agent/tools/burst.py artifacts/<dir>   # the continuous roll: sharpness and tail
python3 .agent/tools/balance.py artifacts/<single> <alt> <roll>   # does the ink sit still, is the pair even
python3 .agent/tools/grid.py artifacts/<dir> out.png --title="..." --verdict=kept|rejected|open \
        --col=2 --step=33 [--mark=burst] [--ios=<prefix>]
python3 .agent/tools/gif.py  artifacts/<dir> out.gif --title="..." --verdict=... --slow=5
```

**Show the grids. Every round, as images, not as a description of them.** This is an animation; a
table of error terms is not evidence anyone can check, and the numbers rank candidates rather than
finding defects — every defect fixed on this branch was found by looking. `gif.py` is the stronger
of the two for anything about motion, `grid.py` for anything about a particular frame.

`--title` is required on both and `--verdict` says which of the round's sheets is the engine as it
stands and which is an experiment that was thrown away. A round makes several and they look alike:
handing over an untitled pair got a discarded attempt read as the current state, which makes a fixed
defect look live.

**`sim.py` first, `round.sh` second.** A model that cannot reach the reference off-line will not
reach it on a device either, and finding that out costs a second rather than twenty minutes. When
it is time for the device, `round.sh` is the one to reach for: a round costs one build instead of
three, and it prints both directions and all three cadences together, which is what stops a knob
being called good on the evidence of the one measurement it was fitted against.

**Know the scatter before believing a difference.** `band.py` and `balance.py` now print a `±`
spread beside every median, and `band.py` flags a cadence whose runs disagree by more than 0.03.
Measured, two runs of one unchanged build:

| | 60 ms band | 120 / 240 ms band | roll travel |
|---|---|---|---|
| spread within one build | 0.041–0.172 | ~0.006 | up to 0.244 |

The four-round square that chose the current `EVEN_SHRINK_AT`/`CROWD_SPREAD` corner was decided on
differences of 0.03–0.04 at 60 ms, and HEAD re-measured moves 0.038 on that same number. **That
round carried no information.** 60 ms now gets five runs; 120 and 240 are the cadences to fit on.

Traps, each of which has already cost real time:

- **A recording that ran out of disk leaves a `.bin` with no `.json`, and every glob here keys off
  the `.json`** — so it is skipped in silence and the analysis measures what survived. A run is
  90–240 MB and the alternation group is six of them, ~940 MB, against 611 MB free on a 6 G data
  partition. That ate the **240 ms alternation on three consecutive rounds** while these notes
  blamed the dev-client scrim. `round.sh` now drains after every run rather than every group,
  refuses to drive below 400 MB free, and says so when a group holds an orphaned `.bin`. Give the
  AVD more room as well: `disk.dataPartition.size` in `~/.android/avd/<AVD>.avd/config.ini`.

- **`fit.sh` taps a fixed screen coordinate, so adding a preset button moves it.** That once put
  two rounds of measurements on the wrong transition, and only a "restored" baseline reading 0.242
  instead of 0.031 gave it away. If a run reads far worse than the last for no reason, check the
  preset in the capture's marks first.
- **Use `grid.py --mark=burst` for anything with more than one change.** A preset resets the value
  before it runs and that reset takes a mark of its own, so aligning on mark 0 compares one
  platform's roll against the other's idle second — which produced a confidently reported defect
  that did not exist.
- **The recorder draws through a SOFTWARE canvas.** It sees `drawGlyphSoftware`, not the
  RenderEffect a device runs. Keep the two in step or you are measuring a renderer nobody sees;
  that hid an entirely absent blur for a day.
- **`adb install` fails with an EMPTY error message when the emulator's `/data` fills**, and a round
  driven after it measures the previous build under a new name. That produced a burst regression
  that did not exist — two "different" builds, one binary. `round.sh` reclaims space on failure;
  when driving by hand, grep the install for `Success` before believing anything after it.
- **The dev client's menu opens by itself and its scrim swallows every tap underneath.** It ate the
  240 ms alternation on two consecutive rounds in silence. Dismiss it with the Continue button and
  then the panel's X — never with `keyevent 4`, which backs out of the app when no menu is up and
  leaves the round driving the launcher.

Reference recordings, ~2.5 GB on disk, never committed:

| set | what | used by |
|---|---|---|
| `artifacts/gt_ios_ref/` | decrement, 1 run | `compare.py` default |
| `artifacts/gt_ios_up5/` | increment, 5 runs | `compare.py --up` |
| `artifacts/gt_ios_bursts/` | 3 bursts each direction | the burst tables |
| `artifacts/gt_ios_alt3/` | alternation at 60/120/240 ms | the overlap tables |
| `artifacts/gt_ios_behav/` | reversal and alternation | the interruption tables |

Their `reference.json` files **are** committed, so the numbers survive even if the pixels are lost.
To re-record: install the iOS app, launch with `SIMCTL_CHILD_NUMERICTEXT_RECORD=1`, drive a preset,
copy from the app container's `Documents/numerictext-record`.

**Noise floor**, five runs of one preset: amplitudes identical to three decimals, timings spread by
13 ms. Anything above 0.001 on an amplitude or 13 ms on a time is real.

## The engine

`android/src/main/java/com/numerictext/NumericTextTimeline.kt`, ~430 lines. One persistent column
per logical position, each a continuous position on a strip of stops. A change moves a column's
`target` and never restarts anything — that single property is what makes one tap and a
press-and-hold the same code path.

The strip is a **drum**, not a flat ribbon: ten digits on the ten faces of a decagon, so a stop of
travel is a tenth of a turn and a glyph's offset is `APOTHEM * sin(angle)` with a `cos(angle)`
foreshortening applied to its height only. `SCALE_AMOUNT` stays uniform and independent of it —
tying the whole scale to the angle is what the first, rigid attempt did, and it cost the crossing.

Two scalars per column, deliberately not one: `position` owns geometry, `settle` owns opacity. The
reference finishes its geometry at ~400 ms and then spends another ~350 ms bringing opacity to full
with nothing moving, which one scalar cannot express.

Every constant lives in the companion object and says whether it is Apple's or measured. There is
nothing to tune that is not there.

## What to know before touching anything

**The metric ranks candidates; it does not find defects.** Every defect fixed in this branch came
from looking at the app or at a frame grid: the roll running backwards in both directions, the blur
being absent rather than small, the blur being along a line instead of a defocus, the number
appearing to vibrate, the arrival reading pale, the wave starving far columns during a burst. Two
were artefacts of the measurement path rather than of the engine. **Produce a grid for anything you
claim.**

**A constant that does not transfer may be right with the model around it wrong.** Apple's `scale`
looked wrong until the crossing's two glyphs were given separate opacity curves — with one curve,
shrinking a glyph and dimming it are the same act.

**Measure before hypothesising, replicate before chasing.** Two burst defects were reported from
one run each and both turned out to be in the *other* direction once replicated three times.

## Next, in order

The drum is **done and kept** and did not do what it was expected to do: it halved the single
crossing, headline 0.031 → 0.010 in both directions, and left the alternation band untouched. The
band was then solved by a separate mechanism — an apothem that widens while the column is chased —
and the pair's ink imbalance halved by the same signal levelling its opacity and its shrink together.
All three are zero at rest, which is why the single crossing has not moved since.

**Stop turning constants.** The four-round square that set the current corner decided differences
smaller than the 60 ms band's own run-to-run scatter, and this file's own table says no corner
satisfies both the alternation and the roll. Both of those are reasons the same shape of round
cannot converge, and a fifth one will not either.

1. **The travel, and it is structural rather than a constant.** `samples()` emits exactly two
   glyphs, `floor(position)` and the stop above it, and every one of offset, alpha and shrink is a
   function of `distance = |stop - position|`. The ink centroid therefore IS the column's position:
   if the column slides, the ink slides. The only memory in the system is one scalar per column,
   `evenness = CROWD_EVEN * crowdRaw`.

   The reference does the opposite of sliding — 0.163 on a single change and 0.103 / 0.139 / 0.161
   / 0.119 under every crowd, i.e. it moves its ink LESS when changes pile up. This engine matches
   the single change exactly (0.165) and then grows monotonically with crowding, to 0.517. No
   setting of a signal that scales one shared position produces "moves less when busier".

   What the reference's behaviour looks like instead is a STACK: one transition per change, each
   with its own clock, entering from `+offset` and leaving towards `-offset`. Their masses sit
   either side of the rest position, so the centroid stays put however many are alive — travel
   falls out for free rather than being fitted. It also explains why Apple's `offset` 0.59375 never
   transferred: it is a per-transition entry amplitude, not the spacing between two stops, and it
   was fitted here as spacing at ~0.32.

   `f981795` tried this and was reverted eleven minutes later for brightening the ink, 0.581 → 0.816
   — which is what summing N independent alphas does. A crossfade is a CONVEX combination, so the
   stack's alphas have to be normalised to sum to one, not dimmed individually. The one round that
   did normalise got the opacity nearly exact (0.333 against 0.317) and was then judged on the gap,
   which the drum and the chase have since solved by other means. **It was closed on a criterion
   that no longer applies.**

2. **The off-line simulator exists — `.agent/tools/sim.py`, and it has already run.** It cuts the
   settled glyphs out of the iOS recordings and renders a model into the recorder's own format, so
   `compare.py`, `band.py`, `burst.py`, `balance.py`, `grid.py` and `gif.py` all work on its output
   unchanged. A candidate costs **one second** instead of twenty minutes; sixteen model variants
   were swept in ten.

   **The stack model was built and measured there, and the result is split.** Apple's constants,
   no chase, no crowd signal, nothing fitted except two knobs swept against the single crossing:

   | | headline | travel single / alt60 / roll | band 60 ms | roll sharpness |
   |---|---|---|---|---|
   | reference | — | 0.163 / 0.103 / 0.119 | 0.760 | 0.600 |
   | device, drum + chase | **0.010** | 0.165 / 0.259 / **0.517** | **0.814** | 0.603 |
   | simulator, stack | 0.018 | 0.166 / **0.190** / **0.180** | **1.429** | 0.600 |

   The travel is fixed structurally, which nothing on the device has managed: the roll goes 0.517 →
   0.180 and the single crossing stays put, so for the first time in this branch a crowded regime
   and the isolated one improved TOGETHER. Balance follows it — 0.032 against the reference's 0.052
   through a roll, against the device's 0.118.

   **And the stack loses the band badly**, 1.429 against 0.760 where the chased drum reaches 0.814.
   A faster spring was the obvious suspect and it is not the cause: swept 0.30 → 0.09 the band
   never gets below 1.065, and by then the crossing has broken (headline 0.246). So the split is no
   longer two corners of one knob square — it is **two models, each winning a different regime**,
   which is a better problem to have and a different one to solve.

   Do NOT port the stack to Kotlin yet. That is the simulator earning its keep: it says this model
   is not ready, in ten seconds, instead of a day of Kotlin and a round.

3. **The synthesis worth trying next, and it is cheap now.** Keep the stack's per-transition
   identity — which is what produces the travel and the balance — and give each entry the DRUM's
   placement, `APOTHEM * sin(angle)` with the `cos` foreshortening, plus the chase's widening. The
   ground truth already records that the drum's SIZE is what produces the band (apothem 1.15 gives
   0.715 against 0.760) and that it cost the crossing, which is precisely what a stack does not
   have to pay: its crossing is set by one transition's own curve, not by the spacing of two stops.
   Both halves are already written, in `sim.py` and in `NumericTextTimeline.kt`.

   Second, unrelated and also cheap: the entry amplitude fits at 0.44 where Apple's `relativeOffset`
   is 0.59375. In this parameterisation `p` runs 1 → 0 → -1, so an arrival at `+OFFSET` and a
   departure at `-OFFSET` span `2*OFFSET`; Apple's number may be the transition's total travel
   rather than its one-sided amplitude. Half of it is 0.297 and the fit says 0.44, so neither
   reading is confirmed — one sweep settles it.

3. **Then re-measure, and only then.** The single crossing at 0.010 with the controls at 1.000 is
   the thing not to break; the roll's tail at 635 ms against 615 is the measurement that caught
   every previous attempt at this (all three balance experiments broke it, and `compare.py` never
   sees it).

4. **What is genuinely unknown, and cheap to find out.** `.numericText()` is animated by whatever
   animation is in the transaction. Driving the reference under an explicit
   `withAnimation(.linear(duration: 1))` would read every curve — offset, scale, alpha, blur —
   directly against a known clock, instead of fitting them through a spring that is being solved at
   the same time. Nothing in these notes has tried it, and it separates two unknowns that have so
   far only been measured multiplied together.
