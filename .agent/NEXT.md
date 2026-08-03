# START HERE

This library reimplements SwiftUI's `.numericText()` content transition on Android, and measures
itself against the real thing frame by frame. On iOS it hosts the genuine SwiftUI view, so **iOS is
the reference**; the Android renderer is the thing being fitted.

Read this, then `IOS_GROUND_TRUTH.md`. Everything in both either states a measurement or says it is
a guess. A claim without a number behind it should be distrusted.

---

## Where it stands

Android against the iOS reference, single value change:

| | decrement 1,242→1,160 | increment 1,160→1,242 |
|---|---|---|
| ink floor error | 0.005 | 0.009 |
| crossing extent error | 0.015 | 0.011 |
| edge at the floor | 0.885/0.389/0.733 vs 0.873/0.400/0.728 | — |
| **headline** | **0.010** | **0.010** |
| wave start | ±2 ms | ±0 ms |
| back to full | 419/502/585 vs 420/504/587 | — |

The timings match to a frame and the single crossing now matches to within three times the noise
floor. What is left is the alternation, and it is no longer a shape problem: the drum reproduces the
reference's overlapped profile exactly at an apothem the single crossing forbids. See the drum
section of the ground truth.

## The measuring rig — the part that makes progress possible

Both platforms record themselves. `NumericTextFrameRecorder` renders the host view's presentation
layer into an 8-bit alpha buffer every display-link tick and streams it to disk with exact
timestamps and a mark at every value change. No screen recording, no resampling, no hunting for a
sync flash.

```bash
.agent/tools/round.sh <name>    # one build: both crossings and the alternation, all three compared
.agent/tools/fit.sh <name>      # build, install, drive the decrement preset, pull, compare
python3 .agent/tools/compare.py artifacts/<dir> [--up]
python3 .agent/tools/band.py artifacts/<dir>    # the alternation's middle band, iOS beside it
python3 .agent/tools/grid.py artifacts/<dir> out.png --col=2 --step=33 [--mark=burst] [--ios=<prefix>]
```

`round.sh` is the one to reach for — a round costs one build instead of three, and it prints the two
directions and the alternation together, which is what stops a knob being called good on the
evidence of the one measurement it was fitted against.

Three traps, each of which has already cost real time:

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

The drum is **done and kept**, and it did not do what it was expected to do. It halved the single
crossing — headline 0.031 → 0.010 in both directions, with the extent error that had survived five
fits of everything else going 0.037 → 0.015 — and it left the alternation band at 1.409 against the
reference's 0.760. What it bought instead is a much sharper question, which is (1) below.

1. **What sets the spacing.** The drum's shape is the reference's: at an apothem of 1.15 the
   alternation's mean profile lands on it bin for bin, band 0.715 against 0.760, closer than
   anything else tried on the overlapped regime. The crossing forbids that apothem — it pins it at
   0.555 — and the reason is now a measurement rather than a guess: the reference spans 1.527 glyph
   heights under a 60 ms alternation and 1.181 at the widest frame of a single crossing, at the
   same angular separation. **No function of the separation alone can produce both.** So look for
   the thing with memory that widens the pair when changes pile up: an apothem that grows while the
   column is being chased and relaxes when it is not. It is the same direction as the reference's
   own cadence sweep, where it WIDENS towards fast and we narrow. Whatever the mechanism, the test
   is `round.sh`: the apothem may not move the single crossing.
2. **The overlap opacity**, unchanged by the drum and still open. Under a fast alternation the
   reference goes *fainter* than it ever goes in one crossing — the grid shows it holding both
   glyphs dim and apart where we swap between two solid ones. A crowd-normalised composition
   reproduced the faintness almost exactly, peak 0.333 against 0.317, but not the gap. If (1) finds
   the spacing, this is what has to land with it; the closed brackets are in the ground truth so
   neither dead end is walked again.
3. **The glyph-size gap**, 12% at the bottom of a crossing and flat across cadence. The standing
   guess was that the drum's foreshortening was it; that can now be bounded rather than guessed —
   `cos` at the crossing's separation is worth 5%, so at most half of it — but the gap has NOT been
   re-measured since the drum went in. Measure it before deciding whether anything is left to
   explain.
