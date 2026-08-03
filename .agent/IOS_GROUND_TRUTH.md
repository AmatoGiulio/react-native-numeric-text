# Ground truth from iOS — what was tried, what works, how to use it

Added 2026-08-02. Replaces screen recording as the way to measure the reference.

## Why

Every number in `PARITY_ROADMAP.md` and in the `artifacts/*/REPORT.md` files was inferred from a
screen recording: how much ink is on screen, from which the algorithm had to be guessed. That route
had reached its floor. The iOS captures are variable-rate — measured at ~48.6 fps through the
transition window, with 13–20 ms gaps and occasional 33 ms holes — and were resampled onto a fixed
60 Hz grid, so roughly a fifth of the "iOS frames" in those tables are duplicates. Meanwhile three
runs of the same Android APK on the same preset spread by ±0.05 on the headline metrics. Several
conclusions in `NEXT.md` are differences smaller than that.

## What was tried and did not work

Both were cheap, both were conclusive, and both are worth not repeating.

**1. Reading the CALayer tree.** Under the `Text` with `.contentTransition(.numericText())` there
are three layers, the innermost a `CGDrawingLayer` with no sublayers. No CAAnimation on any of them,
no filters, transforms at identity for the whole transition. SwiftUI does not drive this through
CoreAnimation — it redraws one opaque layer itself, every frame.

**2. Interposing a custom `TextRenderer`.** iOS 18's `Text.LayoutOptions.disablesAnimations` implies
the layout handed to a renderer carries animation state, so this looked promising. It does not.
The renderer is called **once**, at the start, with the destination text laid out at rest — and then
SwiftUI animates without calling it again. The transition itself survives being interposed (the
layer trajectory is identical), so the renderer is upstream of the transition, not inside it.

Together these say what the reference is: SwiftUI rasterises the text once per value and animates
the raster with its own engine. There is no per-digit object to read. **The algorithm is closed and
the pixels are the only channel.**

Two things came out of the failures and are worth keeping:

- The one `TextRenderer` call yields exact typography — each glyph's width, x position, ascent and
  descent. If column boundaries ever need to be exact rather than found in the ink, they are there.
- The reference animates *images of glyphs*, so its blur is an image blur and its scale an image
  scale. The Android renderer already works this way; on that point the model is not wrong.

## What works: the exact-frame recorder

`NumericTextFrameRecorder` in `ios/NumericTextSwiftUIHost.swift`, `#if DEBUG`, off unless
`NUMERICTEXT_RECORD=1`. It renders the host view's presentation layer into an 8-bit alpha buffer on
every display-link tick and writes the run as a raw `.bin` of concatenated frames plus a `.json`.

What it removes, relative to a screen recording:

| | screen recording | recorder |
|---|---|---|
| frame rate | variable, ~48.6 fps, resampled to 60 | the display link's own, median 16.67 ms |
| timestamps | inferred | exact, from `CACurrentMediaTime` |
| t = 0 | found by hunting for a sync flash | recorded as a mark when the value changes |
| pixels | H.264, then a colour→ink threshold | the layer's alpha plane, unmodified |
| resolution | whatever the device records | device scale (×3), any region |

Alpha rather than colour on purpose: the text is one solid colour, so the alpha plane *is* the ink
coverage the analysis measures — opacity, blur and the edge-fade mask all multiply into it.

It is not free. Rendering and copying a 1382×643 buffer per frame on the main thread cost one
dropped frame out of 96 in the first run (median interval 16.67 ms, max 33.11 ms). That is small,
but it is not zero, and it is why the recorder is off by default.

### Recording a run

```bash
xcrun simctl install <udid> example/ios/build/Build/Products/Debug-iphonesimulator/NumericTextExample.app
SIMCTL_CHILD_NUMERICTEXT_RECORD=1 xcrun simctl launch --terminate-running-process <udid> numerictext.example
# drive the Showcase, then:
C=$(xcrun simctl get_app_container <udid> numerictext.example data)
cp "$C/Documents/numerictext-record/"run-*.{bin,json} artifacts/<name>/
```

A run is armed by the first value change and keeps recording for 1.6 s after the last one, so a
whole burst lands in one file with every change stamped as a mark. **The cadence does not have to be
precise** — JS timer jitter no longer matters, because what each change did and exactly when it
landed are both recorded.

### Reading a run

```bash
python3 .agent/tools/ground_truth.py --run artifacts/<name>/run-<stamp> \
  --contact sheet.png --json report.json
```

The metrics (`ink`, `edge`, `extent`, `centre`, `top`, `bottom`) are deliberately the same
definitions as `roll_shape_fixed.py`, so they read against the existing REPORTs — with the caveat
that a column is normalised against *its own settled glyph*, and on a transition that glyph differs
from the one it started as. A ratio above 1.0 at t=0 means the starting glyph simply carries more
ink than the one replacing it (a `0` against a `1`), not that anything moved.

## First measurement, `1,000 → 1,123`

One run, 96 frames, five columns. The two unchanging columns measure flat at 1.00 for the whole
window, which is the sanity check the video pipeline could never quite give.

| column | first motion | ink floor | back to full |
|---|---|---|---|
| hundreds | 33 ms | 0.55 @ 133 ms | 400 ms |
| tens | 133 ms | 0.51 @ 216 ms | 483 ms |
| units | 216 ms | 0.50 @ 283 ms | 566 ms |

The staircase is regular in a way the recordings never resolved: first motion 100 and 83 ms apart,
floors 83 and 67 apart, and the recoveries **exactly 83 ms apart, twice**. 83.3 ms is five frames at
60 Hz.

**This is one run of one transition and nothing should be fitted to it yet.** The regularity is a
hypothesis worth testing, not a constant. Before it becomes one: replicate this transition several
times to establish the run-to-run spread of the recorder itself, then measure the decrement, then a
structural change.

## The noise floor — measured 2026-08-02, six runs

`1,160 → 1,242` driven from the Showcase preset six times, `artifacts/gt_noise/`. Spread is
max−min across the six runs at matched times, on the three columns that change.

| | ink | edge | extent | centre |
|---|---|---|---|---|
| on the recorder's own clock, median | 0.008 | 0.001 | 0.006 | 0.006 |
| on the recorder's own clock, worst | 0.184 | 0.305 | 0.144 | 0.044 |
| **re-zeroed on first motion, median** | **0.000** | **0.000** | **0.000** | **0.000** |
| **re-zeroed on first motion, worst** | **0.001** | **0.006** | **0.006** | **0.001** |

The ink floor of every column was identical to three decimals in all six runs.

The whole of the first row's worst case is one thing: the value change lands at an arbitrary phase
against the 60 Hz refresh, so runs differ by up to **12.1 ms — under one frame — and by exactly that
amount on every event of every column**. Where the curve is steep, half a frame of offset reads as a
large amplitude difference. It is not measurement error, and it disappears completely once each run
is re-zeroed on the frame its first column starts to move.

**So: align on first motion, then treat anything above 0.01 as real.** Against the video pipeline's
±0.05 that is five times finer, and unlike ±0.05 it is a hard floor rather than an estimate.

Per-column events for this transition, medians of six (spread 12.1 ms on every one of them):

| column | first motion | ink floor | back to full |
|---|---|---|---|
| hundreds | 73 ms | 0.379 @ 123 ms | 406 ms |
| tens | 139 ms | 0.524 @ 223 ms | 489 ms |
| units | 223 ms | 0.511 @ 289 ms | 556 ms |

Read against `1,000 → 1,123` above, the staircase spacing is 67–100 ms and **not obviously one
constant across the two transitions** — the recoveries were 83/83 there and 83/67 here. Two
transitions is not enough to decide whether that is structure or glyph-dependent. Do not fit a
constant to it yet.

## The decrement — measured 2026-08-02. It is the increment, mirrored.

No new capture was needed: the `1,160 → 1,242` preset sets its start value first, so each of the six
noise-floor runs contains `1,242 → 1,160` **and** `1,160 → 1,242` — same glyphs, same session, same
file. Five runs of each (the first run starts from a different value and is discarded).

Timing, medians, decrement against increment:

| column | first motion | ink floor at | back to full |
|---|---|---|---|
| hundreds | 71 / **75** ms | 148 / **125** ms | 405 / **408** ms |
| tens | 138 / **141** ms | 205 / **225** ms | 488 / **491** ms |
| units | 221 / **225** ms | 288 / **291** ms | 547 / **558** ms |

Every pair is within 3.4 ms — well under the 12.1 ms sub-frame phase spread. The two directions run
on the same schedule.

Depth needs care. Normalised the obvious way, against each direction's own settled glyph, the floors
look different by up to 0.2 — but that is an artefact: the two directions end on *different glyphs*,
and a `1` carries far less ink than a `2`. Normalised against the mean of the two settled states, so
the denominator is the same both ways:

| column | decrement | increment | difference |
|---|---|---|---|
| hundreds | 0.457 | 0.458 | +0.000 |
| tens | 0.474 | 0.478 | +0.004 |
| units | 0.478 | 0.481 | +0.003 |

All below the 0.01 threshold. **On a plain roll the reference's decrement is the increment mirrored,
in timing and in depth.** Run-to-run spread was 0.000–0.003.

The one real directional difference is small: on the tens and units the increment reaches about
0.05 glyph heights further *down* and the decrement about the same further *up* — the expected lean,
but a twentieth of a glyph, not the third of one that the video pipeline reported. The hundreds
column is confounded here because `1` and `2` differ too much in shape for a percentile measure.

**What this settles.** `artifacts/decremento_ios_android` showed the reference barely disturbing its
ink where Android was in motion for most of the window, and that was read as iOS treating the
decrement differently. It does not. Whatever that capture compared, it was not this. So the Android
decrement gap is entirely Android's, and its target is not a separate behaviour to model — it is the
increment's own curve, mirrored. If the Android model cannot produce that by symmetry, the asymmetry
is a defect in the model rather than a missing feature.

## The structural path — measured 2026-08-02. There isn't one.

Four presets, two runs each, `artifacts/gt_struct/`. Total ink of the whole composition as a
fraction of the settled one, and the drawn width relative to just before the change:

| t (ms) | 1,000→999 | 1,000→10,000 | 1,000→877 | 9,950→10,123 |
|---|---|---|---|---|
| 0 | 1.18 / 1.00 | 0.86 / 1.00 | 1.59 / 1.00 | 1.19 / 1.00 |
| 100 | 1.11 / 0.98 | 0.89 / 0.99 | 1.51 / 0.98 | 1.04 / 1.00 |
| 200 | 0.88 / 0.96 | 0.96 / 1.10 | 1.22 / 0.96 | 0.82 / 1.11 |
| 300 | **0.72** / 0.98 | 1.00 / 1.17 | **0.80** / 0.98 | **0.73** / 1.20 |
| 400 | 0.90 / 0.95 | 1.01 / 1.18 | 0.91 / 0.95 | 0.91 / 1.19 |
| 500 | 0.98 / 0.93 | 1.00 / 1.19 | 0.98 / 0.93 | 0.98 / 1.18 |

**`1,000 → 10,000` never dips, because nothing rolls.** Every digit keeps its place value —
1,0,0,0 becomes 1,0,0,0,0 — so no column changes content. The frames (`growth.png`) show the glyphs
sliding sideways, the separator relocating, and one new zero arriving at the end. There is no
vertical motion at all. Ink rises monotonically because the composition simply gains a glyph.

The other three do dip, and all three bottom at **267–294 ms** and are full again at **483–513 ms**.
A plain roll's units column bottoms at 289 ms and is full at 556 ms. **It is the same schedule.**

So the conclusion is not a new set of constants — it is that the category is wrong:

> The reference has **one vertical behaviour and one horizontal behaviour, and they are
> independent**. A column rolls when *its own digit changes value*, on the roll's own left-to-right
> schedule. The composition reflows horizontally when *the width changes*, over roughly the same
> 350 ms. A "structural change" is simply a transition where both happen at once —
> `1,000 → 10,000` isolates the reflow, a plain roll isolates the roll, and `1,000 → 877` runs both.

`shrink.png` confirms the shape the video pipeline had already read correctly for `1,000 → 999`:
`1,000 → ▮,000 → ▮9000 → 9▮00 → 99▮0 → 999`, each nine arriving in its own column left to right,
the old zeros staying dark until their own turn. That earlier reading was right; what was wrong was
treating it as a separate mechanism with its own constants.

One caveat on the width column: for a *shrink* it barely moves (1.00 → 0.93) because the drawn box
still contains the departing glyphs and their blur out to the left, so it measures the ghost's reach
rather than the composition's width. Only the growth figures are a clean reflow measurement. Also
note the drawn width keeps changing after the transition settles — that is React Native re-laying
out the host view, not SwiftUI.

## Where this leaves the plan

1. ~~Establish the recorder's noise floor.~~ Done.
2. ~~The decrement.~~ Done — the increment mirrored.
3. ~~The structural path.~~ Done — it is not a separate path.

## Android records itself too — 2026-08-02

`NumericTextFrameRecorder` in `android/src/main/java/com/numerictext/NumericTextFrameRecorder.kt`
writes the same `.bin` + `.json`, so `ground_truth.py` reads either platform without being told
which. It captures inside `onDraw` — exactly what was drawn, on the frame it was drawn, which is
tighter than the iOS side, where the frame has to be re-rendered from the layer on a display tick.

```bash
adb shell touch /sdcard/Android/data/numerictext.example/files/numerictext-record.on
adb pull /sdcard/Android/data/numerictext.example/files/numerictext-record
```

Two things learned building it, both worth not rediscovering: frames must stream to disk (holding a
run in memory is ~90 MB, and `ByteArrayOutputStream` doubling on top of that is an immediate
`OutOfMemoryError`), and the emulator has no room for the universal APK — build
`-PreactNativeArchitectures=arm64-v8a`.

Android's own noise floor, five runs of `1,160 → 1,242`, re-zeroed on first motion: ink 0.000
median / 0.040 worst, edge 0.000 / 0.059, extent 0.000 / 0.051. Slightly looser than iOS's
0.000 / 0.006 — its frame cadence is driven by the spring ticker rather than a display link — so
**treat 0.02 as the actionable threshold on Android, 0.01 on iOS.**

### The first exact comparison, `1,160 → 1,242`

Medians, Android against iOS, on the same preset:

| column | first motion | ink floor | floor at | back to full |
|---|---|---|---|---|
| hundreds | 35 / **75** ms | 0.429 / **0.379** | 102 / **125** ms | 385 / **408** ms |
| tens | 119 / **141** ms | 0.649 / **0.524** | 219 / **225** ms | 484 / **491** ms |
| units | 202 / **225** ms | 0.669 / **0.511** | 319 / **291** ms | 584 / **558** ms |

Two defects, both well above threshold:

1. **The first column starts 40 ms too early**, and the whole cascade is shifted early with it. The
   spacing between columns is nearly right (84/83 ms against 67/83) — it is the lead-in that is
   wrong, not the stagger.
2. **The dip gets shallower the further right it goes, and the reference's does not.** iOS floors
   at 0.38 / 0.52 / 0.51; Android at 0.43 / **0.65** / **0.67**. By the units column Android is
   holding two thirds of its ink where the reference holds half. That is the "reads as two legible
   numbers" complaint, with a number on it at last — and it is a *rendering* defect, not a timing
   one, because the timing of that same column is within 30 ms.

The units column also finishes 26 ms late, which is consistent with 2 rather than a separate fault.

**The font caveat is settled: the faces match.** Compared glyph by glyph on the two settled frames,
scaled to a common height (`artifacts/gt_android/faces.png`), the digits' width-to-height ratios
agree within 1% (0.716/0.711 and 0.805/0.803) and the outlines overlap. Android's face is slightly
heavier — 1.5–7% more ink per unit area — and that works *against* the observed defect, because a
heavier settled glyph is a larger denominator and pushes the measured floor down. The depth gap is
real and if anything slightly understated.

### And it is not a blur problem — it is the travel distance

At each column's floor frame:

| column | ink | edge (1 = as sharp as at rest) | extent, glyph heights |
|---|---|---|---|
| hundreds | 0.429 / **0.379** | 0.387 / **0.401** | 1.611 / **1.182** |
| tens | 0.649 / **0.524** | 0.508 / **0.541** | 1.587 / **1.166** |
| units | 0.669 / **0.511** | 0.441 / **0.436** | 1.675 / **1.215** |

**Edge energy matches on every column.** Android's blur is right; the moving glyph is exactly as
soft as the reference's. What differs is the extent: Android spreads its ink over about 1.6 glyph
heights where the reference keeps it inside 1.2 — the crossing pair is too far apart vertically.

### travelFactor, measured — 0.65 → 0.29, and what it did and did not fix

`travelFactor` was recorded at 0.65 and again at 0.47. Extent is linear in it,
`extent = glyphHeight + 1.21 x travel`, and solving that line for the reference's extent gave
0.302 / 0.294 / 0.274 on the three columns *independently* — so 0.29. Recorded a third time to
check the prediction:

| column | 0.65 | 0.47 | **0.29** | iOS |
|---|---|---|---|---|
| hundreds, extent | 1.611 | 1.389 | **1.172** | 1.182 |
| tens, extent | 1.587 | 1.374 | **1.161** | 1.166 |
| units, extent | 1.675 | 1.455 | **1.226** | 1.215 |

All three land within 0.01 of the reference. **The extent defect is closed**, and this undoes
85de2d2, which had raised the constant 0.25 → 0.65 on the screen-recording pipeline — inside that
pipeline's own noise, and in the wrong direction.

Two things it did *not* do, and the first is a correction to what this file said a paragraph ago:

- **The ink floor did not move at all**: 0.429 → 0.422 → 0.419, 0.649 → 0.635 → 0.622,
  0.669 → 0.659 → 0.656, against the reference's 0.379 / 0.524 / 0.511. So "too much ink at the
  floor and too much spread are one defect seen twice" was **wrong**. They are independent, and the
  depth gap — still the largest, ~0.14 on the tens and units — has a separate cause that is not the
  travel distance and not the blur.
- **Edge energy regressed on two columns**: hundreds 0.387 → 0.329 and units 0.441 → 0.345, against
  0.401 and 0.436. With the pair closer together the two glyphs overlap more, so there is a mild
  coupling after all, appearing only at the short end. It is ~0.08, against an extent gap of ~0.44
  that was closed, so 0.29 stays — but the blur now wants a small reduction it did not want before.

### The settle tail: the reference finishes its geometry, then fades in

Reported from the eye first — *"a strange bounce at the end of travel, then it re-enlarges; on iOS
it is barely readable and much more natural"* — and the recorder shows exactly it. Units column,
ink / edge / ink-box height in glyph heights, Android at travel 0.29 against iOS:

| t (ms) | Android | iOS |
|---|---|---|
| 399 | 0.835 / 0.866 / **1.287** | 0.808 / **1.004** / **1.006** |
| 465 | 0.926 / 0.959 / 1.019 | 0.924 / 1.008 / 0.994 |
| 564 | 0.976 / 1.000 / 1.000 | 0.984 / 1.005 / 1.000 |

**At 400 ms the reference's glyph is already finished** — final size, final sharpness — and only its
opacity is still moving, from 0.81 to 1.00 over the following 350 ms with nothing else changing.
Ours is still spread over 1.29 glyph heights and still soft at that moment, and does not finish its
geometry until 565 ms, by which time it is dark enough to read. That is what the eye sees: a digit
settling *after* it has become legible. The reference's is motionless before it is legible.

So it is not a scale bounce. `presenceScale` already clamps its input, so scale cannot exceed 1;
the 1.02–1.03 measured earlier in the tail was residual blur and the departing glyph's fringe
inflating the ink box, not the glyph growing.

**Why the obvious fix is wrong.** `arriveOffsetBaseline` (0.42) deliberately makes the arrival's
POSITION spring slow, and its comment block records the reasoning: the reference's arriving glyph
sits higher at +233 ms than ours, so the pair stays separated. That measurement is not contradicted
here — the reference *is* higher early *and* finished sooner. Both together mean its motion is not a
slower spring but a different curve: hang high, then drop in and stop. A slower spring is slow at
both ends, which is precisely the tail we have.

#### Two knobs tried against it, both measured, both null — and what they eliminate

Recorded six runs each, same preset, compared at matched times:

| | t_geom, hundreds / tens / units | extent at the floor |
|---|---|---|
| baseline | 334.7 / 400.6 / 534.4 | 1.172 / 1.161 / 1.226 |
| `presenceOffsetFraction` exponent 1.15 → 2.0 | 336.0 / 404.7 / 535.8 | 1.172 / 1.165 / 1.229 |
| `arriveDampingRatio` 0.42 → 0.90 | 336.2 / 401.5 / 534.4 | 1.210 / 1.219 / 1.293 |
| iOS | **241.2 / 307.8 / 407.8** | 1.182 / 1.166 / 1.215 |

Both reverted. The exponent is genuinely dead on this path — `GlyphState.off` is its own sprung
state, not a function of presence, so `presenceOffsetFraction` never runs for a plain roll. The
damping moved nothing and cost 0.04–0.07 of extent.

**What the tracing found while chasing this** (temporary, since removed): a plain roll goes
`scheduleSlots` → `scheduleRollTape` → `scheduleSimpleRoll`, which *rejects* it — `1,160 → 1,242`
needs 8 lane steps on the tens against a coalesce budget of 1 — so it falls through to the per-glyph
scheduler. `scheduleRollTape` is a one-line wrapper that delegates and nothing else, and
**`scheduleLegacyRollTape` is never called from anywhere**: ~170 lines of dead code marked "retained
temporarily for comparison".

#### Why both guesses missed, and what would actually settle it

`t_geom` requires the ink box to be back to one glyph's height *and* the edge to be sharp — so it is
gated by whichever glyph leaves last, not by the arrival. At 400 ms ours still spans 1.29 glyph
heights: the outgoing digit is still on screen. Both knobs tried were arrival knobs.

That also joins the two open defects: if the departure lingers, the column carries two glyphs'
ink for longer, which is exactly the too-shallow floor. One cause, not two — the same claim this
file made once already and had to withdraw, so treat it as a hypothesis until measured.

### Splitting the crossing — and the fix it found

`NumericTextFrameRecorder.drawFilter` records a run with only the outgoing glyphs, or only the
incoming ones, by having `drawSlots` skip the other half **while the recorder is taking its own
pass** (`excludes(target)`; the on-screen draw is untouched). A separate run rather than a second
plane per frame, because the recorder's run-to-run spread is 0.000 once re-zeroed, so two runs of
the same preset are directly comparable and it costs no memory, disk or draw time.

Switch it on alongside the recorder:

```bash
adb shell touch /sdcard/Android/data/<pkg>/files/numerictext-record.outgoing   # or .incoming
```

Validation: outgoing + incoming reproduces the unfiltered total to within the small excess expected
where the two overlap under alpha compositing.

It answered the question immediately. Units column, ours:

| t (ms) | outgoing | incoming | total | iOS total |
|---|---|---|---|---|
| 200 | 1.068 | 0.000 | 1.068 | 1.119 |
| 267 | **0.725** | 0.074 | 0.800 | 0.601 |
| 333 | 0.313 | 0.392 | 0.705 | 0.576 |

At the floor the ink is almost all the glyph that is *leaving*, and the departure gets slower the
further right it goes — outgoing ink reaches zero at ~200 / ~400 / ~600 ms on the three columns.
`exitSlowPerColumn` is precisely the constant that makes it progressive, and it divides the exit
stiffness by `(1 + slow·n)²`, so the units were departing more than twice as slowly as the hundreds.

Recorded at 0.265 and at 0. **The leftmost column did not move either time** — 0.419 both — which is
the signature this constant must have if it is the cause, and is what makes this a fit rather than a
coincidence. The other two are linear in it and each solves to the reference's own floor
independently: 0.092 and 0.126. Set to **0.11** and re-recorded:

| column | floor, session start → now → iOS | extent, session start → now → iOS |
|---|---|---|
| hundreds | 0.429 → 0.419 → **0.379** | 1.611 → **1.172** → 1.182 |
| tens | 0.649 → **0.537** → 0.524 | 1.587 → **1.161** → 1.166 |
| units | 0.669 → **0.494** → 0.511 | 1.675 → **1.255** → 1.215 |

Mean distance from the reference: floor **0.111 → 0.024**, extent **0.437 → 0.018**.

Still open, all now small and each with a known character:

- **The hundreds' floor, 0.040.** Untouched by `exitSlowPerColumn` by construction (it is index 0),
  so it is a separate cause — and the same column is the one that starts ~24 ms early.
- **Edge, 0.07–0.11 too soft on the hundreds and units.** This came in with the travel change and
  the departure change has not helped it; the blur wants a small reduction it did not want before.
- **The lead-in, ~24 ms.** The stagger between columns is right; only the first column's start is.

Do not fit any of these from one transition. The method that worked three times here is the same
one each time: record at two values of one constant, check that the columns it should not touch do
not move, then solve.

Open, in order of size: the ink floor (~0.14), the settle tail's shape, the 40 ms early lead-in, the
edge regression (~0.08).

## What is left

The Android renderer carries `birthSpacingSeconds`,
`structuralStaggerSeconds`, `structuralExitLead`, `substitutionExitLead`, `birthSlowPerColumn`,
`structuralExitSlowPerColumn` and a whole second scheduler for a category the reference does not
have. If the measurements above hold, most of that can go — replaced by the roll's own schedule plus
an independent horizontal glide. That is a simplification, and it should be measured as one:
re-record these four presets against the Android build before and after.
4. Only then, tuning — against exact numbers, and with the video comparison demoted to what it
   should always have been: a regression check, not a design tool.

The Android side has no equivalent recorder. It could have one — the same trick works on a
`View`'s canvas — and until it does, the comparison is exact on one side only.

---

# Apple's own parameters, read out of SwiftUICore — 2026-08-02

The two probes established that the *behaviour* cannot be read from outside. The *parameters* can:
`ContentTransition.NumericTextConfiguration` is a real type in the shipped framework, and its
accessors are small enough to read directly.

```
/Library/Developer/CoreSimulator/Volumes/iOS_23B86/.../iOS 26.1.simruntime/Contents/Resources/
  RuntimeRoot/System/Library/Frameworks/SwiftUICore.framework/SwiftUICore
```

`nm -gU … | swift demangle` lists the members; the getters decode the packing; `init` copies four
default bytes from a constant in `__TEXT,__const`.

| member | storage | default | value |
|---|---|---|---|
| `delay` | `byte / 120` | 18 | **0.15 s** |
| `offset` | `signed byte / 32` | 19 | **0.59375** |
| `scale` | `byte / 128` | 51 | **0.3984** |
| `blur` | `byte / 4` (or `/128` as `relativeBlur`) | 32 | **8.0** (or 0.25) |
| `maxDurationMultiple` | immediate in the getter | — | **1.25** |
| `options` | `OptionSet` | 0 | neither `relativeBlur` nor `reversed` |
| `axis`, `direction` | enums | — | — |

Two of these are independently confirmed by the recorder, which is what makes the rest credible:

- **`offset` = 0.59375.** The old renderer's `travelFactor` is the amplitude of ONE glyph and was
  fitted against the recorder at 0.29 (0.302 / 0.294 / 0.274 on the three columns). The pair's
  separation is twice that — 0.58 — against Apple's 0.59375.
- **`delay` = 0.15 s, and it is the wave's TOTAL, not the gap.** Three changing columns give
  0.075 s per gap; the recorder measured the three columns starting 67 and 83 ms apart, mean 75.
  A fixed total divided by the column count is exactly the thing `.agent/NEXT.md` could not express
  with one constant after seeing a 3-column and a 6-column change spread over the same ~139 ms.

`delay` being stored in 120ths is itself a finding: the wave is quantised to half a 60 Hz frame,
which is why the recorder kept measuring steps of 67 and 83 ms (8/120 and 10/120) rather than one
number.

**What this does not give.** The spring, the alpha curve and the blur curve are compiled code, not
stored parameters — `scale = 0.3984` and `blur = 8.0` are amplitudes, and how they are applied
across a crossing is still only measurable from the pixels. So the recorder is not replaced; it now
has four of its answers checked against the source of truth.

**Do not treat these as portable API.** They are internal defaults of one OS version, read for
interoperability. They should be written into the Android renderer as measured constants with this
note, never depended on at runtime.

---

# Which of Apple's constants transfer — 2026-08-03

Five constants were read out of `NumericTextConfiguration`. After a day of fitting each against
the recorder, they split cleanly:

| constant | stored | transfers? | what it is here |
|---|---|---|---|
| `delay` | 18/120 = 0.15 s | **yes, exactly** | total spread of the wave, divided by the gaps |
| `scale` | 51/128 = 0.3984 | **yes** | how far a glyph shrinks at full separation |
| `offset` | 19/32 = 0.59375 | **no** | separation of two stops fits at ~0.32 |
| `blur` | 32/4 = 8.0 pt | **no** | as a fraction of the line height, fits at ~0.42 |
| `maxDurationMultiple` | 1.25 | untested | caps how far the spring stretches |

The timing parameter transfers to the millisecond; the geometric ones do not, which is what one
would expect if SwiftUI measures them against a glyph box defined differently from ours.

`scale` only transferred once the crossing's two glyphs were given separate opacity curves. With a
shared curve, shrinking a glyph and dimming it are the same act — a smaller glyph carries less ink
— so Apple's shrink read as "too pale" and got cut. The lesson generalises: a constant that does
not transfer may be right, with the model around it wrong.

## What the eye caught that the metric could not

Four defects were found by looking at the app or at a frame grid, none of which moved the headline
number in the direction that would have found them:

- **the roll ran backwards, both ways.** Confirmed by tracking each column's ink centroid on a
  matched pair of reference runs from the same value: `2,599 -> 2,722` moves the ink DOWN by 0.070
  glyph heights, `2,599 -> 2,476` moves it UP by 0.054. The reference brings an incrementing digit
  in from ABOVE.
- **the blur was absent, not small.** `drawRolling` picks its path on `canvas.isHardwareAccelerated`
  and the software branch drew plain text. The recorder draws into a Bitmap, so it always took that
  branch: setting the blur amplitude to 8 and to 0 produced identical numbers.
- **the blur was along a line, not a defocus.** A directional blur preserves a glyph's structure
  across its axis, so the digit stays readable and merely looks streaked. The reference's
  mid-crossing glyphs are unreadable clouds. SwiftUI's `.blur(radius:)` is isotropic and the stored
  constant has no axis.
- **the radius was quantised at half a pixel**, so a decaying blur walked down its buckets one
  visible notch at a time and the glyph read as vibrating. Eight steps per pixel fixed it, which
  also ruled out the more expensive hypothesis — that the RenderEffect's layer was being resampled
  by `node.scaleX`.

**So: the metric ranks candidates, it does not find defects.** Every one of these was invisible to
it, and two of them were artefacts of the measurement path itself rather than of the engine.

## Where it stands

Decrement `1,242 -> 1,160`, `.agent/tools/fit.sh` then `compare.py`:

| | android | iOS | error |
|---|---|---|---|
| wave starts | 68.4 / 135.3 / 218.5 | 70.2 / 136.8 / 220.1 | 2 ms |
| back to full | 418 / 502 / 585 | 420 / 504 / 587 | |
| ink floor | 0.472 / 0.453 / 0.439 | 0.515 / 0.435 / 0.428 | 0.024 |
| extent | 1.186 / 1.113 / 1.245 | 1.181 / 1.147 / 1.196 | 0.029 |
| headline | | | **0.027** |

Open: the increment has no numeric reference — `compare.py` knows only the decrement, so the
increment is still judged by eye. And no continuous roll has been compared at all, because every
multi-change recording on the simulator has a zero-byte plane file: the recorder silently writes
nothing above some run length, and that needs fixing before a burst can be measured.

---

# The burst, replicated — 2026-08-03

Both burst defects had been seen in one run per direction and flagged as needing replication.
Three runs each side, driven at 33/34 ms through `scheduleOnFrames`:

| | iOS | android | difference | real? |
|---|---|---|---|---|
| sharpness, up | 0.600 ±0.000 | 0.603 ±0.000 | +0.003 | no |
| **sharpness, down** | 1.661 ±0.000 | 1.524 ±0.021 | **−0.137** | **yes** |
| **tail, up** | 545 ±11 ms | 586 ±1 ms | **+41 ms** | **yes** |
| tail, down | 581 ±21 ms | 585 ±0 ms | +4 ms | no |

Sharpness is the median edge energy of the rightmost column over the burst, normalised on its own
settled glyph; the tail is how long that column keeps moving after the last value change. Only
compare within a direction — the two settle on different glyphs, so the normalisation differs.

**Neither defect was where the single runs put them.** They were reported as "11% less sharp and a
37 ms tail" in the decrement. The sharpness deficit is the decrement's alone and the long tail is
the increment's alone, and each direction is clean on the other measure. One run per direction
mixed them; two would have shown it.

The spreads are the useful part: on a burst this recorder repeats to 0.000–0.021 on sharpness and
0–21 ms on the tail, so both surviving differences clear the floor by a wide margin.

Consistent with the single transition, where the increment measures 0.014 against the decrement's
0.024 — the decrement is the weaker direction in both regimes.

## Reference sets on disk

| set | what | use |
|---|---|---|
| `artifacts/gt_ios_ref/` | decrement 1,242 → 1,160, one run | `compare.py <dir>` |
| `artifacts/gt_ios_up5/` | increment 1,160 → 1,242, five runs | `compare.py <dir> --up` |
| `artifacts/gt_ios_bursts/` | three bursts each direction | the table above |

The increment reference carries its own noise floor: amplitudes identical to three decimals across
five runs, timings spread by 13 ms. Anything above 0.001 on an amplitude, or 13 ms on a time, is
real.

## Chasing the two burst targets — what did not work, 2026-08-03

Both were replicated and real. Neither is fixed, and the attempts are worth not repeating.

### The decrement's sharpness deficit is largely a phase artefact

The metric was the MEDIAN edge energy over the burst window, and the signal oscillates once per
crossing — so it reads the phase as much as the amount. Frame by frame the two platforms sit on top
of each other at 0, 60, 120, 180 and 300 ms and diverge by 0.59 at 240, where ours is at the bottom
of a crossing and the reference has not entered one.

Separated into phase-independent statistics:

| | iOS min | android min | iOS max | android max |
|---|---|---|---|---|
| down | 0.831 | 0.635 | 1.661 | 1.649 |
| up | 0.472 | 0.407 | 1.001 | 0.992 |

The maxima match — at rest we are the same. The minima do not: our deepest point in a burst is
deeper in BOTH directions, not just the decrement.

But on a SINGLE crossing the deepest point measures the other way:

| | iOS | android | |
|---|---|---|---|
| increment | 0.459 | 0.555 | we are 1.21x SHARPER |
| decrement | 0.667 | 0.642 | 0.96x, matched |

So there is no blur amplitude that is wrong. What differs is how the blur responds to a continuous
roll, where crossings run into each other — a modelling question, and one that risks the single
transition, which is the primary target and is at 0.014 / 0.024.

### The increment's long tail is not the hold rule, the damping, or the follower

Our tail is 586 ms after the last change and the reference's 545. The useful framing is not that
ours is long: our single transition finishes in 585 and theirs in 587, so **the reference is faster
after a burst than after one change and we take the same time either way.** It has a shortcut we do
not.

Four attempts, all measured, none kept:

| change | tail | note |
|---|---|---|
| DAMPING_RATIO 0.90 → 1.00 | 685 | worse; the tail is asymptotic approach, not ringing |
| reset the opacity follower only from rest | 586 | no effect at all |
| skip the hold for a column already moving | 403 | overshoots by 142 |
| skip the hold for a column with work queued | 469 | overshoots by 76 |
| **unchanged** | **586** | **+41, the smallest error of the five** |

Critical damping is worth noting separately: it improved the single transition to 0.020 from 0.024
while pushing back-to-full 65–80 ms late and the burst tail to 685. It buys the crossing's shape at
the cost of every timing, which is not a trade worth making.

---

# Two behaviours under interruption — 2026-08-03

Three scripted presets, added so both platforms receive the same sequence: `roll ⇄` reverses a
continuous roll halfway, and `alterna ×20` flips one digit between 0 and 1 at two cadences.

## Alternation: the reference converges, this engine keeps chasing

Units column, steady state (300 ms in, up to the last change). `centroid` is where the ink sits
between the two stops, `swing` is how far it moves over the run, `ink` is total coverage.

| | iOS centroid | android centroid | iOS swing | android swing | iOS ink | android ink |
|---|---|---|---|---|---|---|
| 60 ms | +0.613 | +0.629 | **0.103** | **0.307** | 0.322 | 0.478 |
| 120 ms | +0.551 | +0.599 | **0.139** | **0.324** | 0.382 | 0.500 |

Three things, in order of size:

- **The swing is 3x ours.** The reference settles into a nearly static superposition — a faint
  half-1 over a half-0, held almost still from 135 ms to the end of the run — while this engine
  keeps visibly flipping between a solid 0 and a solid 1. It is the clearest behavioural gap left.
- **Its blend is fainter.** 0.322 against our 0.478: the reference holds both glyphs at low opacity
  rather than showing one of them properly.
- **The parking point does move with the cadence, on both, the same way.** Faster pressing parks
  nearer the incoming digit: iOS 0.613 → 0.551, ours 0.629 → 0.599. So the effect reproduces, at
  about half the sensitivity.

All three point one way: the reference has more inertia against a target that keeps moving than we
do. The same shape as two earlier findings — it is faster than us after a burst, and it steps more
evenly through one.

## Reversal: no defect found

A roll up reversed into a roll down at the halfway mark. Both platforms bend the motion rather
than restart it, and the ghosting through the turn is comparable. The digit sequences differ in
phase, which the burst work already explained.

---

# The inertia against a moving target — found, 2026-08-03

Three observations pointed at one missing mechanism: the reference is faster than us after a burst,
steps more evenly through one, and refuses to chase an alternation. It is none of the things that
sound like inertia.

**It is a discontinuity in our opacity, not a difference in our spring.**

The arithmetic gives it away. With RESPONSE 0.30 driven by a square wave at 67 ms, a second-order
system transmits about 18% of the target's swing — a position ripple of ~0.056 glyph heights. We
measured a centroid swing of 0.307, five times that. So what oscillates is not the position.

`GlyphRole` was binary and tied to the current target: `stop == column.target`. When the target
flips, the two glyphs exchange ENTER (0.52) and EXIT (1.60) **instantly**. Forcing both exponents
to 1.0 collapsed the swing from 0.307 to 0.082, which identifies the swap as the whole cause.

It is invisible on a single change because the arriving glyph is a full stop away and its opacity
is clamped near zero by `min(presence, settle)` — which is why it survived a day of fitting.

## The fix, and what it costs

Weight the exponent by how close a glyph is to its own stop rather than by which stop is the
target. At rest the arrival has presence 1 and gets ENTER, the departure has 0 and gets EXIT — the
fitted values recovered where they were fitted — and a strip parked between two stops gives both
the same blend, with nothing left to swap.

| | swing | centroid | ink |
|---|---|---|---|
| iOS | 0.103 | +0.613 | 0.322 |
| binary role | 0.307 | +0.629 | 0.478 |
| **weighted** | **0.137** | +0.645 | 0.468 |

EXIT_ALPHA_EXPONENT re-fitted 1.60 → 1.40, because the blend darkened the crossing: the mid-point
exponent is the mean of the two, so the pair has to move to keep the same ink there.

Single transition 0.024 → 0.031. Alternation swing error 0.204 → 0.034. Reverting is one line.

**Still open:** the reference's blend is fainter than ours under alternation, 0.322 against 0.468,
and the weighting does not touch it. That is a separate mechanism.

## A tooling trap that cost two rounds

`fit.sh` taps a fixed coordinate, and adding three preset buttons moved the layout so it hit
"9,950 → 10,123" instead. Two rounds were measured against the wrong transition — including a
"restored" baseline that read 0.242 instead of 0.024, which is what gave it away. If a run reads
far worse than the previous one for no reason, check the preset in the capture's marks first.

## The alternation ink gap, measured — 2026-08-03

Units column, 0↔1 every ~60 ms, steady state. No interpretation, just what the pixels say.

| | total ink mean | min | max | swing | peak opacity |
|---|---|---|---|---|---|
| iOS | 0.322 | 0.298 | 0.344 | **0.046** | **0.317** |
| android | 0.468 | 0.386 | 0.580 | **0.194** | **0.582** |

Upper lobe / lower lobe: iOS 0.112 / 0.210, android 0.156 / 0.312. The RATIO matches, 0.53 against
0.50 — the two glyphs are balanced the same way and sit in the same places. What differs is level.

The comparison that makes it legible is against each platform's own single crossing:

| peak opacity | single crossing floor | alternation |
|---|---|---|
| iOS | 0.478 | **0.317** |
| android | 0.525 | **0.582** |

**The two move in opposite directions.** Chased with a target it cannot reach, the reference goes
fainter than it ever goes in one crossing — 0.478 down to 0.317, a third quieter. This engine goes
brighter, 0.525 up to 0.582. At a single crossing the two are close.

And the reference's total ink is nearly constant across the whole alternation, swinging 0.046,
while ours still pulses by 0.194 after the exponent fix.

No hypothesis recorded here on purpose. What is established: it is not the balance between the two
glyphs, and it is not a small offset in one direction — the sign of the effect differs.

## The overlap test — 2026-08-03

The hypothesis: the reference composites one fading transition per value change, while this engine
holds one persistent state per column. Under a single change the two are equivalent, which is why a
day of fitting worked; under interruption they diverge.

It predicts that slowing an alternation, so fewer transitions are alive at once, brings the
reference back up toward what a single crossing measures — and that an engine with one persistent
state shows no such dependence on overlap.

Three cadences, peak opacity in the alternation's steady state:

| cadence | iOS ink | iOS peak | android ink | android peak |
|---|---|---|---|---|
| ~63 ms | 0.322 | 0.317 | 0.470 | 0.581 |
| ~117 ms | 0.382 | 0.489 | 0.491 | 0.659 |
| ~240 ms | 0.520 | 0.727 | 0.586 | 0.790 |

Read each against its OWN single crossing — iOS 0.478, android 0.525:

| cadence | iOS ratio | android ratio |
|---|---|---|
| ~63 ms | **0.66** | **1.11** |
| ~117 ms | 1.02 | 1.26 |
| ~240 ms | **1.50** | **1.50** |

**At the slowest cadence the two agree exactly, 1.50 against 1.50.** As overlap increases they
separate, and at the fastest the reference has dropped to 0.66 of its own single crossing while we
have risen to 1.11 of ours. The reference goes below what one crossing ever reaches; we never do.

That is what the hypothesis predicted, and it localises the missing mechanism: it does not exist at
all without overlap, and it grows with it. Both engines brighten as the cadence slows — that part
is just having more time — but only the reference darkens when its transitions pile up.

**Supported, not proven.** An opacity capped by how long the target has been stable would fit the
same three points. What is ruled out is any explanation that does not depend on overlap, since
without overlap the two agree to two decimals.

## The glyphs are smaller in the reference — and it is not the overlap, 2026-08-03

Reported from watching a fast 0→1: the reference seems to shrink its digits further than we do
mid-crossing. It does. Width at half the frame's own peak, over the settled glyph's width:

| | iOS | android | ratio |
|---|---|---|---|
| single crossing floor | 0.842 | 0.898 | **1.066** |
| alternation ~63 ms | 0.582 | 0.665 | **1.143** |
| alternation ~117 ms | 0.604 | 0.696 | **1.152** |
| alternation ~240 ms | 0.687 | 0.783 | **1.140** |

We are wider — shrinking less — everywhere. But the gap is FLAT across cadence, 1.14 at all three,
including the 240 ms case where the opacity ratios agreed to two decimals. So this is not the
overlap mechanism; it is a second, independent difference.

The threshold works against this conclusion, which strengthens it: measuring width at half of each
frame's own peak inflates the width of a fainter render, and the reference is the fainter one. It
still measures narrower.

SCALE_AMOUNT is already Apple's own 0.3984 and was approved by eye at a single crossing, so the
residual is not the constant. Two candidates, untested: the scale is applied to a different box
than the one SwiftUI applies it to, or its scale depends on something beyond the distance to the
stop — sustained motion doubles the gap, from 6.6% to 14%.

### Which of the two candidates it is

The "different box" candidate is ruled out on first principles, not measurement: a scale applied
about a pivot multiplies a glyph's width by that scale whatever the box is. The box moves a glyph;
it does not resize it. So if we are wider at the same phase, our scale factor is simply larger.

That leaves one question, and the width-versus-time curve answers it. Units column, single crossing:

| t | iOS | android | ratio |
|---|---|---|---|
| 160 | 1.149 | 1.153 | 1.003 |
| 240 | 1.044 | 1.031 | 0.987 |
| **320** | **0.807** | **0.908** | **1.125** |
| 400 | 0.930 | 0.980 | 1.054 |
| 480 | 0.982 | 0.990 | 1.007 |

**Not a constant factor.** The two agree at the start and the end of the crossing and diverge only
at the bottom of the shrink: the reference reaches 0.807 of its settled width, we only reach 0.908.
It is the DEPTH that differs, not a uniform offset.

Deepening it costs more than it buys, measured:

| | headline | width ratio at the bottom |
|---|---|---|
| SCALE_AMOUNT 0.3984 | **0.024** | 1.125 |
| 0.58 | 0.114 | 1.062 |
| 0.58 + ENTER 0.34 | 0.097 | — |

Smaller glyphs carry less ink and cover less span, so the floor and the extent both collapse and
buying them back takes the exponent AND the step: three knobs, which is a refit and not a fix. Left
at 0.3984, which is Apple's own number and the size approved by eye at a single crossing.

The finding stands on its own: at the bottom of a crossing the reference's glyph is 12% smaller
than ours, and no single constant closes that without opening two others.

## The gap between the two digits — a third signature of the same regime, 2026-08-03

Reported from the 60 ms grid: after ~135 ms the reference shows visible SPACE between the 1 and the
0, while ours stay crossed. Measured as the mean normalised vertical profile's middle third over
its outer thirds — literally how full the space between the two glyphs is:

| | 60 ms | 240 ms |
|---|---|---|
| iOS | **0.756** | 1.362 |
| android | **1.513** | 1.328 |

At 60 ms the reference's middle is EMPTIER than its ends and ours is half again fuller: it holds
two separated forms where we hold one merged mass. At 240 ms the two agree — 1.362 against 1.328 —
which is the same crossover as the opacity.

A first attempt at this measured peak separation and the valley between the peaks, and reported the
opposite. It was peak-finding on a faint smooth profile and resolved two lobes in only 14 frames of
the iOS run against 43 of ours, so it was measuring noise. Band ink needs no peaks and uses every
frame.

So three independent signatures now separate the two engines in the overlapped regime and vanish
outside it:

| | 60 ms | 240 ms |
|---|---|---|
| peak opacity vs own single crossing | 0.66 / 1.11 | 1.52 / 1.50 |
| middle band vs ends | 0.756 / 1.513 | 1.362 / 1.328 |
| total ink swing | 0.046 / 0.194 | — |

The glyph-size finding is a fourth difference but NOT part of this group: it is flat across cadence,
1.14 at every one, so it is present with or without overlap.
