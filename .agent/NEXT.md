# START HERE — 2026-07-31 (branch `feat/timeline-based-rewrite`)

## The persistent-column engine, first measurement — added 2026-07-31, evening

Everything below this section was measured on the OLD spring engine (branch
`feat/per-slot-springs-ios-parity`). This branch replaced it wholesale with
`NumericRollEngine` (`NumericTextTimeline.kt`): one persistent column per key, a continuous
`position` on a strip of glyph stops, ONE spring per column, alpha/scale from distance-to-stop,
blur from velocity. ~850 lines total against ~3,035.

**New tooling:** `.agent/tools/analyze_pair.py` — one command from raw captures to
`artifacts/<name>/{REPORT.md, grid.png, shape.txt, pre/end frames, onsets}`. Used for everything
below. Captures: `captures/ios_human.mov` + `captures/android_human_1.mp4` (humanised ×12,
`1,000 → 2,476`, onsets 255/106) and the iOS-only `1,242 → 1,160` (onset 191).

Measured on the humanised cadence, whole run, hundreds/tens/units medians (ours / **iOS**):

| | ink | edge | crisp | up | dn |
|---|---|---|---|---|---|
| hundreds | 0.94 / **0.75** | 1.38 / **1.15** | 0.93 / **0.75** | +0.10 / **−0.04** | +0.07 / **+0.04** |
| tens | 1.37 / **1.17** | 0.96 / **0.88** | 0.72 / **0.51** | +0.10 / **−0.02** | +0.07 / **+0.04** |
| units | 0.86 / **0.71** | 0.99 / **0.79** | 0.64 / **0.45** | +0.08 / **−0.04** | +0.06 / **+0.03** |

Ranked defects, all confirmed in the zoomed frames (`artifacts/human_x12/`):

1. **Direction is INVERTED.** On an increment iOS's new digit arrives from ABOVE and the old one
   leaves below (re-confirmed this session on both `1,000→1,123` and the decrement mirror
   `1,242→1,160`; also §6 of METHODOLOGY). Ours arrives from below. In `setTarget`,
   `next = column.target + directionSign` puts the new stop under the old for direction=+1;
   the sign must flip (or `offsetY`'s).
2. **No per-column stagger.** All changed columns move in phase — the mid-transition frame shows
   the WHOLE old number above and the WHOLE new number below, two legible compositions swapping.
   iOS moves one column at a time, left→right, ~75 ms apart. The engine has no per-column
   delay/slowness knob at all yet.
3. **Both stops far too legible through the crossing.** At mid-crossing each stop has
   alpha ≈ 0.5 and mild blur, so the frame reads as two numbers. iOS's moving column is an
   unreadable smudge: alpha should dip harder (summed-ink floor ~0.5, ours ~0.7-0.9) and blur
   must be driven by crossing progress, not velocity alone — at the reference's crossing the
   glyph is heavily blurred even when the spring is at peak velocity or near rest.
4. **The ink leaves the box upward** (+0.10 vs iOS −0.04): STEP_FRACTION 0.40 with fully
   visible stops pushes legible glyphs a whole step outside the line. iOS confines the visible
   mass inside ±0.05 of the box at this cadence.

What the model already gets RIGHT (do not regress): retarget continuity — repeated taps move
the target without restarting anything, no pile-up, no flip-book pumping between changes;
completion detection is clean; the code is small enough to reason about.

Not yet measured on this branch: structural changes (birth/death/x-glide — the human cadence
never changes digit count), press-and-hold 30 ms, isolated single roll on Android.

Everything below this section was measured before the roll-tape rewrite that is currently in the
working tree. This section measures THAT, at 60 fps, one frame every 16.7 ms, against a same-session
iOS capture. Five presets, all sync-marked; the two new ones and the humanised cadence were added
today because no preset covered the cases the reports are actually about.

Recordings, grids and per-frame tables are local only. Tools: `roll_shape.py` (new — below),
`frame_grid.py --stride 1 --count 50`, `template_fit.py`.

### 1. The roll's per-column stagger is gone — but only the stagger

**This table replaces a wrong one.** The first Android capture labelled `1,242 → 1,160` actually hit
the `1,000 → 877` button one row above it, so a structural shrink was compared against the
reference's plain roll. The conclusion drawn from it — "ours is twice as fast and its floor is much
deeper" — was an artefact of that. Verify which preset a capture hit by dumping its settled last
frame; it costs one ffmpeg call.

Corrected, per-column ink as a fraction of that column settled:

| t (ms) | 0 | 50 | 100 | 150 | 200 | 250 | 300 | 350 | 400 |
|---|---|---|---|---|---|---|---|---|---|
| iOS hundreds | 1.09 | 0.72 | **0.50** | 0.61 | 0.77 | 0.89 | 0.94 | 0.98 | 0.99 |
| iOS tens | 0.83 | 0.83 | 0.76 | **0.44** | 0.49 | 0.68 | 0.83 | 0.92 | 0.96 |
| iOS units | 0.83 | 0.83 | 0.83 | 0.83 | 0.59 | **0.41** | 0.56 | 0.74 | 0.87 |
| ours hundreds | 1.03 | 0.80 | **0.57** | 0.56 | 0.67 | 0.78 | 0.85 | 0.90 | 0.92 |
| ours tens | 0.84 | 0.65 | **0.52** | 0.54 | 0.65 | 0.76 | 0.84 | 0.90 | 0.92 |
| ours units | 0.82 | 0.63 | **0.50** | 0.52 | 0.65 | 0.76 | 0.84 | 0.90 | 0.92 |

The depth is right (0.50-0.57 against 0.41-0.50) and so is the pace — both take ~450 ms. **What is
missing is the offset**: the reference's columns bottom out at 100 / 150 / 250 ms and ours bottom
out together, at 100. Three curves that are individually well fitted and perfectly in phase.

The mechanism is not a mistuned constant. `scheduleSlots` returns early for a plain roll once
`scheduleRollTape` accepts it, so `staggerSeconds` and `exitSlowPerColumn` are never reached at all;
the tape's own per-column knob, `rollTapeSlowPerColumn`, is 0. That is where the roll's staircase has
to be rebuilt, and the target is 50-100 ms between column floors.

### 2. The structural path is a whole-composition crossfade

Total ink of the whole number, as a fraction of the settled composition:

| t (ms) | 0 | 66 | 99 | 132 | 165 | 198 | 231 | 264 | 297 | 330 | 396 | 462 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `1,000 → 999` iOS | 1.18 | 1.10 | 1.09 | 1.03 | 0.92 | 0.77 | 0.66 | **0.65** | 0.71 | 0.78 | 0.87 | 0.95 |
| `1,000 → 999` ours | 1.02 | **0.24** | 0.50 | 0.89 | 0.89 | 1.09 | 1.07 | 1.05 | 1.05 | 1.02 | 1.01 | 1.00 |
| `9,950 → 10,123` iOS | 1.06 | 0.97 | 0.89 | 0.82 | 0.77 | 0.70 | **0.66** | 0.69 | 0.79 | 0.89 | 1.01 | 1.02 |
| `9,950 → 10,123` ours | 0.98 | **0.40** | 0.36 | 0.58 | 0.58 | 0.79 | 0.88 | 0.92 | 0.92 | 0.95 | 0.99 | 0.99 |

The reference never removes more than a third of the ink and takes 230-260 ms to get there. **Ours
removes three quarters of it inside 66-100 ms** — every glyph at once, including the ones whose own
column has not started. In the grid that frame is a number that has almost vanished, and it is what
reads as a blink rather than a transition.

The frames say what the reference does instead. On `1,000 → 999` it runs
`1,000 → ▮,000 → ▮9000 → 9▮00 → 99▮0 → 99▮ → 999` over ~320 ms: each new 9 arrives in its own
column, left to right, and **the old zeros stay fully dark until their own column's turn**. On
`9,950 → 10,123` it holds `9,950` whole and crisp for 67 ms before anything moves at all.

The cause is in the file and is not subtle: `birthSpacingSeconds`, `structuralStaggerSeconds`,
`structuralExitLead`, `substitutionExitLead` and `birthSlowPerColumn` are **all 0** in the working
tree, zeroed on the reading that "a structural change is one transaction" and that the previous
0.65 "created the reported visible wave". The reference's own frames show a 320 ms left-to-right
wave on a structural shrink. That reading was wrong, and the section further down in this file
("The structural path", 2026-07-30 evening) had already fitted the constants that produce it.

### 3. The continuous roll: ours is too dense, too sharp, too short, and leans the wrong way

`roll_shape.py`, 38 frames of a 30 ms press-and-hold, per column (hundreds / tens / units).
`ink` is in settled glyphs, `edge` is edge energy per unit ink against the same column at rest
(1.0 = as sharp as a settled digit), `ext` is the ink's 5-95% vertical span in glyph heights,
`up`/`dn` are how far it reaches past the settled glyph's own box, `crisp` the fraction of frames
holding something as sharp as a settled digit.

**Increment** (+123 every 30 ms):

| | ink | edge | ext | up | dn | crisp |
|---|---|---|---|---|---|---|
| iOS | 0.78 / 0.58 / 0.62 | 0.42 / 0.33 / 0.60 | 1.28 / 1.22 / 1.10 | +0.15 / +0.14 / +0.12 | **+0.36 / +0.28 / +0.10** | 0.11 / 0.00 / 0.05 |
| ours | 0.95 / 0.82 / 0.73 | 0.44 / 0.43 / 0.43 | 1.01 / 1.13 / 1.08 | +0.08 / +0.12 / +0.07 | +0.12 / +0.21 / +0.12 | 0.08 / 0.05 / 0.00 |

**Decrement** (−123 every 30 ms):

| | ink | edge | ext | up | dn | crisp |
|---|---|---|---|---|---|---|
| iOS | 0.38 / 0.40 / 0.48 | 0.51 / 0.49 / 0.93 | 1.26 / 1.24 / 1.08 | **+0.35 / +0.27 / +0.08** | +0.12 / +0.15 / +0.12 | 0.18 / 0.21 / 0.55 |
| ours | 0.60 / 0.69 / 0.65 | 0.72 / 0.70 / 0.75 | 1.10 / 1.08 / 1.09 | +0.04 / +0.06 / +0.04 | **+0.21 / +0.29 / +0.17** | 0.39 / 0.39 / 0.37 |

Read together:

- **The reference's roll is DIRECTIONAL and ours is not.** Its mass leans the way the value is
  going — 0.36 below the box on an increment, 0.35 ABOVE it on a decrement, with the centroid
  crossing from +0.19 to −0.09. Ours leans **down in both directions** (+0.14 and +0.03), so a
  decrement and an increment draw the same shape. This is the whole of "the mass is pulled up when
  we go down". The vertical stabilisation that fixed the tap-cadence excursion took the roll's
  direction with it.
- **Ours carries too much ink**: 0.60-0.69 against 0.38-0.48 on a decrement, 0.73-0.95 against
  0.58-0.78 on an increment. Half again as much, and it is why a hold reads as legible digits
  stepping rather than a mass running.
- **Ours stays too sharp**: edge 0.70-0.75 against 0.49-0.51, and a settled-sharp digit is on
  screen twice as often (0.39 against 0.20). The reference's rolling column has no glyph in it.
- **Ours is too short**: 1.08-1.10 glyph heights against 1.24-1.28.

And it pumps. Total ink through the hold, ours: 1.17 → 0.68 → 0.97 → 0.83 → 0.87 → 0.76. The
reference: 1.20 → 0.89 → 0.66 → 0.62 → 0.64 → 0.77 — it decays into a plateau and stays there. Ours
keeps re-forming a whole number between changes, which is the flip-book the reports describe.

### 4. The humanised tap cadence is the closest thing we have

A new `human ×12` preset: gaps 220 / 400 / 210 / 650 / 240 / 380 / 200 / 640 / 230 / 410 / 200 ms,
a literal table so both platforms replay it identically. Over the whole run every metric matches to
within noise — ink 0.74/1.17/0.71 against 0.71/1.07/0.66, edge 1.16/0.90/0.79 against
1.08/0.82/0.75, centroid to 0.01 — **except the reach below the line: the reference's is +0.04 /
+0.04 / +0.03 and ours is +0.20 / +0.22 / +0.17.**

That is the departing ghost the 2026-07-30 note left open, now with a number on it. In the grid it
is visible as a grey pair hanging under the line for 300 ms after ours has already delivered the new
digits, while the reference is still handing its columns over one at a time.

### 5. Two tooling traps, both of which produced confident wrong numbers today

- **`locate` took min..max of the inked rows** inside its search band, which assumes the band holds
  nothing but the number. The Showcase's layout moved again and the +/− buttons landed inside it:
  the box grew to 901 px against the 403 it should be, every column was normalised against a "digit
  height" that included two buttons, and the roll measured as reaching 1.4 glyph heights below the
  line — identically on both platforms, which is exactly what makes it look like a measurement.
  It now groups the rows and takes the group carrying the most ink. **The Showcase's button area is
  also pinned to a fixed height now**, so adding presets cannot move the number again.
- **An odd crop width on yuv420p.** `crop` rounds it down to even while a `reshape` on the raw
  output still assumes the odd one, so every row lands one pixel further left than the last. The
  crop decodes, finds columns, and returns numbers — from a sheared image. Force even x/y/w/h.

`template_fit`'s crop starts flush with the digits' top, so it has **no headroom above the glyph**
and cannot see upward excursion at all. Every "reach up +0.00" in this file below was measured
through that window. `roll_shape.decode_band` pads both sides (capped at 0.35 of the block, because
the +/− buttons sit 0.44 below).

### The structural wave, put back — DONE 2026-07-31, measured

`structuralStaggerSeconds` and `birthSpacingSeconds` at **0.045**, both leads at **0**,
`birthSlowPerColumn` still 0. Total ink of the whole number, medians of three runs:

| t (ms) | 0 | 66 | 99 | 132 | 165 | 198 | 231 | 264 | 330 | 396 |
|---|---|---|---|---|---|---|---|---|---|---|
| `9,950→10,123` iOS | 1.06 | 0.95 | 0.89 | 0.82 | 0.74 | 0.66 | **0.62** | 0.65 | 0.82 | 0.92 |
| before | 0.95 | **0.34** | 0.50 | 0.81 | 0.81 | 0.97 | 0.99 | 0.99 | 1.00 | 1.00 |
| **now** | 1.05 | 0.92 | 0.82 | 0.79 | 0.79 | **0.69** | 0.77 | 0.77 | 0.98 | 0.99 |
| `1,000→999` iOS | 1.18 | 1.11 | 1.09 | 1.03 | 0.93 | 0.81 | **0.71** | 0.71 | 0.83 | 0.93 |
| before | 1.04 | **0.30** | 0.44 | 0.80 | 0.80 | 0.97 | 0.99 | 0.99 | 0.99 | 1.00 |
| **now** | 1.13 | 1.03 | **0.83** | 1.01 | 1.01 | 1.04 | 1.00 | 0.99 | 0.99 | 1.00 |

The growth now tracks the reference's whole descent within 0.03-0.07 and bottoms at 0.69 against its
0.66 on the same frame. The shrink's blink is gone — 0.30 to 0.83 — and it is now a little too
SHALLOW against 0.71, which is the safe side of the error. The plain roll is unchanged, checked
column by column against a capture of the same APK before the change.

**What the first attempt got wrong, kept because it is the instructive half.** Adding the leads
(`structuralExitLead = 0.06`, `substitutionExitLead = 0.025`) alongside the stagger inverted the
defect instead of fixing it: exits carry the lead and arrivals do not, so every column's departure
ran 85 ms behind its own arrival and the total ink went 1.18 / 1.28 / **1.43** / 1.40 without ever
dipping. Two whole numbers on screen at once. The per-column depth had never been the problem — the
reference's rightmost column dips to 0.24 and ours dipped to 0.22 — so the wave belongs to the
handover as a whole, not to one side of it.

**Still short, and the hypothesis for it.** Both structural changes still finish ~150 ms before the
reference, and the shrink's wave is too narrow: three arrivals × 45 ms is 90 ms of spread against
the reference's ~139. But the growth's six arrivals also spread over ~139 ms in the reference — the
SAME total, over twice the columns. If that holds on a third transition, the reference normalises
its structural wave to a fixed duration and divides it by the column count, which no single constant
can express and which would explain why 0.045 fits the growth and leaves the shrink shallow. Two
data points is not enough to write code around: measure `1,000 → 877` (3 columns) and
`1,000 → 10,000` (5) first.

### The roll's wave, put back — DONE 2026-07-31, measured

`rollTapeStaggerSeconds = 0.075`, as a HOLD on the tape's target — not on its phase, so a glyph on
screen is never restarted and the tape's merge invariant is untouched. Per column on
`1,242 → 1,160`, floor and how long the column takes to recover from it (medians of three runs):

| | iOS | before | with the wave |
|---|---|---|---|
| hundreds | 0.50 @ **100** ms, 167 ms | 0.56 @ 150, 233 | 0.56 @ **150**, 233 |
| tens | 0.42 @ **167** ms, 183 ms | 0.52 @ 117, 266 | 0.52 @ **217**, 250 |
| units | 0.41 @ **250** ms, 167 ms | 0.50 @ 100, 283 | 0.51 @ **317**, 200 |

Before, the three floors were at 150 / 117 / 100 — the units bottomed out FIRST, the staircase
running backwards. Now they step 150 / 217 / 317: 67 and 100 ms apart against the reference's 67 and
83. The step is right; the whole staircase is uniformly ~50-67 ms late, and that is the tape spring
itself, not the wave — column 0 has no hold at all and is still 50 ms behind.

**It is a delay, not a slowness, and the measurement is what says so.** The reference's three
recoveries take 167 / 183 / 167 ms — equal within a frame. Dividing the phase spring's stiffness
(`rollTapeSlowPerColumn`, which already existed for this) would have moved the floors apart AND
stretched the units' recovery to 2.2× the reference's. That constant stays 0.

**Two regimes checked for regression, neither regressed.** At the humanised tap cadence every metric
is within noise of before and two moved slightly toward the reference. In the 30 ms hold the
HUNDREDS column moved onto the reference on four metrics at once — ink 0.95 → 0.77 against its 0.78,
extent 1.01 → 1.23 against 1.28, centroid +0.14 → +0.19 against +0.19, upward reach +0.08 → +0.15
against +0.15 — because one stagger at the start of a run leaves the columns' phases permanently
offset, which is what the reference's hold looks like. The tens lost a little (extent 1.13 → 1.00
against 1.22) and the units are unchanged.

**A latent bug came out with it.** The crowding clock was read AFTER the tape's early return, so a
tape roll never updated `lastChangeUptimeMs`: through a whole press-and-hold `changeSpacing` and
`offsetSpacing` stayed at their isolated values, and the first structural change after a burst saw a
long quiet gap and treated itself as isolated. The clock is now read at the top of `scheduleSlots`,
before the tape gets its chance.

### What to try next, in order

1. **The roll's staircase is ~50 ms late as a whole.** Column 0 carries no hold, so this is the tape
   phase spring — `rollTapeStiffness`, currently SwiftUI's own default. It is also what won the tap
   cadence, so measure all three regimes for anything tried here.
3. **Make the roll's mass directional.** Its asymptote and its blur should sit on the side the value
   is travelling FROM, and today both sit below regardless of direction.
4. Only then the density and sharpness (items 3 above): less ink, softer, longer. Note these three
   move together — the 2026-07-30 note "a departing ghost is small AND dense" was fitted on an
   isolated change and does not describe the hold.

---

# Earlier — 2026-07-30

Read this before touching the Android renderer. It is the state of play, the one experiment that is
queued, and the traps that already cost a day.

## The regime nobody had measured: TAPS — added 2026-07-30, late

Every number in this file until now came from one of two cadences: a single change from rest, or a
30 ms press-and-hold. The complaint that would not go away — *"if I press + fast the last digit
keeps going higher and higher; the glyph should never leave a given area, the way iOS keeps it;
SwiftUI rolls where ours pops"* — is about neither. It is about someone tapping the button as fast
as a thumb comfortably goes, 200-250 ms apart. The Showcase now scripts that too (`taps ×8 · 220ms`).

Measured over 1.6 s of it, how far the ink reaches past the settled digit box, in glyph heights:

| | reach up (median / worst) | reach down | ink centre |
|---|---|---|---|
| iOS | **+0.00 / +0.00** | +0.02 / +0.04 | +0.004 |
| ours, before | +0.07 / +0.20 | +0.01 / +0.03 | +0.012 |
| ours, now | **−0.01 / +0.17** | +0.01 / +0.10 | +0.052 |

The reference never leaves its box at this cadence: the digits change *inside* the line and the only
thing outside it is a soft ghost hanging just below. Ours climbed out of the top of it — which is
exactly the report, and why a run of taps read as popping rather than rolling.

**The cause** was the crowding gate. Every rate blends from a crowded value to an isolated one over
`cascadeSpamMs`, and at 90 ms that blend was finished by 180 — so a 220 ms tap got the isolated
rates, which are deliberately slow (the arrival's offset spring sits at 0.42 of the base stiffness,
to keep a crossing pair separated in space). At 220 ms that glyph is still in the air when the next
tap lands, and the next one starts a fresh roll above it.

**What did not work:** widening `cascadeSpamMs` itself to 200. It fixed the excursion and went pale
doing it — the same blend also speeds every departure and every arrival's presence, so the column
never darkens. Ink on screen during the run fell to 0.44 of a whole number against the reference's
0.69, with only 0.24 of it at full darkness against 0.39.

**What did:** a second, wider gate for the arrival's POSITION alone (`offsetCrowdMs = 260`).
Presence, blur and the exits keep the old window, so a tapped change still carries an isolated
change's ink and softness — measured 0.65 / 0.40 against the reference's 0.69 / 0.39 — and all that
changes is that its digit is on the line by the time the next tap arrives.

Still open here: the worst-case excursion is +0.17 where the reference's is 0.00, and the ink now
sits 0.05 of a glyph low where the reference sits at 0.004. Both are the departing ghost, which
hangs below on an increment.

## A trap that invalidated an afternoon's numbers

`template_fit.py` cropped a FIXED box out of the frame, measured against one screen layout. Adding a
row of buttons to the Showcase moved the number up 67 px on Android and 72 px on iOS, which put half
the digits outside the window and the +/− glyphs inside it. Every number taken after that looked
like a large, clean regression on the isolated roll — arrivals landing 45 ms early, the crossing
pair losing all its separation — and none of it was real.

`decode` now calls `locate`, which finds the digits in the frame and builds the box around them. It
reproduces the hand-fitted constants to within 4 px on the recordings they were fitted to, and it
follows the layout. **The constants remain only as the fallback.** If a measurement ever looks
suddenly, uniformly better or worse, dump the crop before believing it.

## The structural path — added 2026-07-30, evening

Everything below this section is about the ROLL. The report that opened this session was about the
other path, and it was right:

> the decrement does not look like the increment, it does not look like the digits are rolling …
> ours is much snappier in everything, faster, shorter, less soft in some scenarios

**Why it shows on the decrement and not the increment.** The Showcase starts at 1,000 and steps by
±123. `+` gives 1,123 — four digits, a plain roll. `−` gives 877 — three digits, so the digit count
changes and the whole transition goes down the STRUCTURAL path, which none of the roll tuning
touches. The direction convention itself is fine: on both platforms a decrement rolls upward (the
outgoing glyph leaves at the top, the incoming one arrives from below), confirmed frame by frame.

**What was wrong with it.** Measured on a new sync-marked preset, `1,000 → 877` (the button is in
the Showcase next to the other two), per column, arriving ink as a fraction of the settled glyph:

| t (ms) | 33 | 83 | 133 | 183 | 233 | 283 | 333 | 383 |
|---|---|---|---|---|---|---|---|---|
| iOS tens | 0.05 | 0.31 | 0.19 | 0.43 | 0.63 | 0.83 | 0.93 | 0.99 |
| ours tens, before | 0.00 | 0.08 | 0.11 | 0.14 | 0.64 | 0.89 | 0.98 | 1.01 |
| iOS units | 0.06 | 0.37 | 0.23 | 0.35 | 0.18 | 0.51 | 0.72 | 0.89 |
| ours units, before | 0.00 | 0.00 | 0.00 | 0.06 | 0.12 | 0.21 | 0.79 | 0.89 |

Every reference column is already lighting up at +33 ms and then ramps for 150-345 ms; ours waited
out a stagger and flipped in about 100. That is a *snap*, and it is what "the digits are not
rolling" means. Fitted as durations (0.1 → 0.9 of the arriving ink): iOS 136 / 256 / 345 ms across
the three columns, ours 148 / 174 / 173 — the reference's wave, ours flat.

**What landed.** The same lesson the roll path already learned, applied here: *the wave is made of
increasing slowness, not increasing delay.*

1. `birthSlowPerColumn = 0.65` — a structural arrival's springs are divided by `(1 + 0.65·n)²`, so
   column n takes `(1 + 0.65·n)` times as long. Both of its springs, so the trajectory keeps its
   shape.
2. `birthSpacingSeconds = 0.025` and `structuralStaggerSeconds = 0.037` — with the wave carried by
   duration, the delays come down to what the reference's first-motion frames measure.
3. A retired glyph in a column that is RECEIVING a new one is a substitution, not a death. It used
   to run at `deathRate²` — the fastest spring in the file, fitted on `1,000 → 1` where nothing
   replaces what leaves — which evacuated a slot that still had to be occupied.
4. `waveIndex` replaces the cascade's column ordinal wherever the wave is indexed. In a shrink the
   old ordinal also counted the columns being deleted, so the units came out at index 4.
5. `structuralExitLead = 0.06` + `substitutionExitLead = 0.025` + `structuralStaggerSeconds = 0.018`
   — the old composition stands whole, then goes quickly, and the glyph holding a slot waits longer
   than the ones simply being deleted. Held with a roll's pace instead the whole shrink ran ~70 ms
   late; released with no lead it emptied the slot before its replacement existed. Both were tried.

Arrival ramps after, against the reference above: tens `0.03 / 0.09 / 0.17 / 0.53 / 0.68 / 0.85`,
units `0.00 / 0.01 / 0.05 / 0.15 / 0.29 / 0.56`. The shape is the reference's; the start is ~80 ms
behind it. The roll preset is unchanged within run-to-run noise (`waveIndex` equals the old ordinal
there), re-measured over three runs.

### Is the decrement different from the increment? Yes — but not in the roll

Asked directly, and measured directly, because it is the report that keeps coming back.

**On a plain roll the two directions are the same.** The Showcase has a mirror preset for exactly
this: `1,242 → 1,160` and `1,160 → 1,242`, the same three columns and the same digits, run three
times each. Total ink through the change, in whole numbers, and the ink still at full darkness:

| t (ms) | 0 | 50 | 100 | 150 | 200 | 250 | 300 | 350 | 400 |
|---|---|---|---|---|---|---|---|---|---|
| total, down | 1.00 | 0.88 | 0.74 | 0.66 | 0.64 | 0.70 | 0.76 | 0.85 | 0.91 |
| total, up | 0.98 | 0.95 | 0.84 | 0.78 | 0.72 | 0.73 | 0.77 | 0.84 | 0.90 |
| crisp, down | 0.99 | 0.74 | 0.47 | 0.41 | 0.33 | 0.54 | 0.59 | 0.83 | 0.91 |
| crisp, up | 0.99 | 0.83 | 0.63 | 0.46 | 0.36 | 0.58 | 0.62 | 0.83 | 0.90 |

The down runs ~40 ms ahead and one twentieth of a glyph deeper. That is the whole difference, and it
is inside the run-to-run spread. Ink leaving the digit box measures 0.01 of a number in BOTH
directions — the roll is a crossfade inside the box either way, which is its own open question, but
not a directional one.

**On a structural change they are not, and cannot be.** A growth only ADDS: on 877 → 1,000 the "1"
and its separator are born into space nothing had to leave, so the composition reads
`877 → 1877 → 1,877 → 1,077 → 1,000` and is a legible number in every frame. A shrink has to DELETE
and then close up: `1,000 → ,000 → 8 00`. The middle of that is not a number, and *that* is what a
viewer means by "the decrement suffers something". The reference has the same asymmetry of task and
does not pay it: its own shrink runs `1,000 → 1 8000 → 8 900 → 87 00 → 877 0`, always contiguous,
because its outgoing digits stay put and stay inked while the new ones arrive beside them.

So the work is not to mirror the increment. It is to stop the shrink from opening a hole.

### Still open on this path

- **The arrivals start ~80 ms late.** The reference has a *toe* — every column is at 0.05 by +33 ms
  — and `presenceAlpha`'s flat bottom (`h'(0) = 0`) cannot produce one. Changing that curve moves
  the roll path too, which is fitted, so it needs its own measurement.
- **The hole, which is the report itself.** At +150-200 ms the shrink shows `8 · · 00` where the
  reference shows `8 9 00` — one old zero has gone and its slot is empty. Four rounds of timing got
  the per-column numbers onto the reference (outgoing ink crosses half at 132 / 182 / 228 ms against
  its 128 / 163 / 189, floors 0.90 / 0.62 / 0.46 against 0.84 / 0.77 / 0.38) and the hole only moved
  later. What is left is a RATE difference, and it is legible in the frames: our two deaths run at
  `deathRate²`, the fastest spring in the file, and are finished long before the arrivals — which
  now ramp for 150-345 ms — have any ink. The reference's deaths and arrivals overlap. The next
  thing to try is a death that does not outrun the arrival beside it; `deathRate` itself cannot just
  be lowered, because it is fitted on 1,000 → 1, where nothing arrives at all.
- **The departures may be far too sharp here.** The fit puts the reference's outgoing σ at 0.16-0.20
  line-heights where ours reads 0.02-0.05 — but 0.20 is the top of the search grid, and in a shrink
  the glyphs reflow horizontally through the measurement window, so treat this as a lead, not a
  number. Widen `SIGMA` in `template_fit.py` before acting on it.
- Everything here is one transition, `1,000 → 877`, and a growth (`877 → 1,000`) has not been looked
  at at all.

## Where things stand

Branch `feat/per-slot-springs-ios-parity`. HEAD is still `b669556` — the parity work below is
UNCOMMITTED in the working tree.

The APK on the emulator **matches this commit**. If you rebuild anything, reinstall — a build on the
device that no longer matches the source is how a whole afternoon got confusing:

```bash
cd example/android && ANDROID_HOME=~/Library/Android/sdk ./gradlew assembleDebug -PreactNativeArchitectures=arm64-v8a
~/Library/Android/sdk/platform-tools/adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
```

What landed today that changes behaviour: the iOS SwiftUI renderer, blur gated by displacement, the
roll's travel pulled back inside the reference's box, a per-role scale curve, and the parked-glyph
revival fix. Everything else is tooling, docs, or a revert.

## Where parity stands — 2026-07-30, end of session

The queued experiment ran, and then eight more iterations on top of it. Measured with
`.agent/tools/parity_report.py`, medians over three runs, against `docs/tune/q_ios60.mp4` (onset 445):

| column | half_out | half_in | floor | sep | blur@0.8 | blur@0.5 |
|---|---|---|---|---|---|---|
| hundreds | 77 / **52** | 162 / **134** | 0.44 / **0.52** | — / 0.38 | 0.03 / **0.03** | 0.09 / **0.07** |
| tens | 132 / **136** | 262 / **257** | 0.44 / **0.48** | 0.32 / **0.05** | 0.02 / **0.03** | 0.05 / **0.07** |
| units | 216 / **225** | 298 / **292** | 0.53 / **0.52** | 0.32 / **0.32** | 0.03 / **0.03** | 0.05 / **0.07** |

(ours / **reference**. Times in ms from the sync marker; floor in glyphs of ink, sep in
line-heights, blur in line-heights of σ sampled where the outgoing glyph still holds 0.8 and 0.5 of
its ink. Run-to-run spread is ±5-35 ms, so anything inside ~35 ms is a tie.)

The UNITS column — the one a viewer watches — matches on every metric. What is left is the
HUNDREDS: it goes ~25 ms late and arrives ~28 ms late, and its floor is 0.08 low. It is the only
column with no stagger and no per-column slowness in front of it, so it is driven by the springs
alone and every global change lands on it hardest.

### What actually moved the needle, in order

1. **`presenceAlpha`** — `p^e` → smoothstep → a cubic Hermite with a live top (`TOP_SLOPE`). A zero
   tangent at full presence makes a departure loiter ~30 ms before it goes anywhere.
2. **The cascade is made of DURATION, not delay.** `exitSlowPerColumn` divides the exit presence
   stiffness by `(1 + slow·colIndex)²`, so each column further right unrolls more slowly. This is
   the "increasing slowness" the old note called open work. Once it existed, `staggerSeconds` came
   back DOWN (0.09 → 0.065): the two are alternative ways to make a column look late.
3. **The two glyphs must be separated in SPACE, not just in time.** `arriveOffsetBaseline`
   0.75 → 0.42 keeps the arriving digit up in the air while it gains ink. Ours were 0.20-0.23
   line-heights apart against the reference's 0.32-0.38, which is why a crossing read as one smudge
   in a slot instead of one digit above another.
4. **The arrival's opacity and its position want opposite things.** Slowing both (arriveCrossSlow
   0.55) overshot and emptied the first column. Position slow, opacity quick (0.66) is the split.
5. **Blur is a matter of ORDER, not amount.** `presenceBlur` was linear in the presence deficit, so
   a glyph softened the instant it began to fade. The reference is crisp while it is whole and soft
   once it is going — σ 0.03 at 0.8 of its ink, 0.07 at 0.5, where ours read 0.05 / 0.05. A
   smoothstep with a dead zone at the top (`BLUR_ONSET`) reverses that order. Comparing PEAK σ hid
   this completely: ours was at or below the reference's the whole time.
6. **A departing ghost is small AND dense.** `rollDepthMin` 0.75 → 0.66 shrinks the mid-crossing
   glyph, which alone made it pale (floor 0.54 → 0.46), so `blurAlphaDrop` 0.22 → 0.13 puts the ink
   back. Size and density had to move together — either one alone measured worse.

### Method notes that cost time to learn

- **One recording proves nothing.** Two captures of the same APK disagree by 30-40 ms on when a
  column crosses half. Use `parity_report.py` with three runs and read the spread; three separate
  conclusions in this session were drawn from single runs and had to be thrown away.
- **Check the pre-onset frame of every run.** `run.sh` now saves it. Two runs were invalid: one
  landed on an app still playing the scripted 41 s sequence (it measured a transition nobody asked
  for), another started from a composition that had not settled. Both produced plausible numbers.
- **`am force-stop` before every capture**, or the previous run's state leaks into this one.
- **The blur reads worse than it measures.** Ours is at or below the reference's σ on every column;
  the grid exaggerates it because Android's smaller glyph is upscaled to match iOS. Same trap as the
  darkness one already noted below.
- **The tens column's fit is the least trustworthy** — 4 → 6 are similar enough shapes that the
  two-template solve wanders, on BOTH platforms (its `fall` reads longer than the units' in the
  reference too). Weight the units and hundreds when they disagree.

### Still open

- The hundreds column, above.
- The settle tail: ours 767 ms against the reference's 550 on `2,000 → 1,999`. Owned by `offK`,
  untouched all session.
- The press-and-hold pile-up guard was only spot-checked (a burst of taps looks clean, no stacking),
  never with the real 30 ms hold `rollExitFadeFast` was tuned against.
- Everything here is fitted to ONE transition, `1,242 → 1,160`. It has not been re-measured on a
  growth (`1 → 9,999`), a shrink, or a sign change.

## How it got there — the first two steps, kept for the reasoning

### Step 1: the queued experiment (smoothstep + arriveCrossSlow)

Both edits are applied (`presenceAlpha` → `smoothstep`, `arriveCrossSlow = 0.49f` on `pK`'s arrival
branch only). Measured on a fresh capture of the preset, onset 204, per-glyph fit on the units
column. The ARRIVING glyph is now essentially on the reference; the OUTGOING one got worse.

| t (ms) | 83 | 133 | 183 | 233 | 283 | 333 |
|---|---|---|---|---|---|---|
| iOS old (out) | 1.00 | 0.89 | 0.87 | 0.44 | 0.18 | 0.07 |
| ours out, before | 0.92 | 0.55 | 0.29 | 0.16 | 0.12 | 0.09 |
| **ours out, now** | **0.98** | **0.45** | **0.17** | **0.06** | **0.05** | **0.09** |
| iOS new (in) | 0.00 | 0.07 | 0.01 | 0.12 | 0.41 | 0.74 |
| ours in, before | 0.02 | 0.03 | 0.14 | 0.32 | 0.48 | 0.64 |
| **ours in, now** | **0.01** | **0.01** | **0.01** | **0.19** | **0.39** | **0.61** |

The arrival's early-lighting — the thing that read as "it appears before it has travelled" — is gone:
0.01 at +183 against the reference's 0.01, where it used to be 0.14. From +233 on it tracks within
0.07. That half is done.

The departure now sheds faster than before, because smoothstep is steeper than `p^0.6` through the
middle and the exit spring's rate was never touched. It holds whole to +83 (0.98 vs 1.00, better than
the old 0.92) and then falls off a cliff, where the reference is still at 0.89 by +133 and 0.87 at
+183 — the reference barely moves for 180 ms and *then* drops.

**That lever was then pulled, in the same session:** `rollExitFadeRate` 1.3 → **0.49**, i.e. the
departure now sheds at exactly the arrival's rate (`arriveCrossSlow`). `rollExitFadeFast` (2.9, the
spam path) deliberately did not move, so the press-and-hold pile-up is still guarded.

Outgoing glyph, units column, same preset — onset 213:

| t (ms) | 83 | 133 | 183 | 233 | 283 | 333 |
|---|---|---|---|---|---|---|
| iOS | 1.00 | 0.89 | 0.84 | 0.41 | 0.17 | 0.07 |
| at 1.3 | 0.98 | 0.45 | 0.17 | 0.06 | 0.05 | 0.09 |
| **at 0.49** | **0.99** | **0.74** | **0.42** | **0.19** | **0.13** | **0.07** |

The metric that actually decides this is the column's SUMMED ink (outgoing + incoming), because what
the eye reads is whether the number stays legible, not what either glyph does alone:

| t (ms) | 33 | 83 | 133 | 183 | 233 | 283 | 333 |
|---|---|---|---|---|---|---|---|
| iOS | 1.00 | 1.00 | 0.96 | 0.85 | 0.55 | 0.60 | 0.83 |
| at 1.3 | 1.00 | 0.99 | 0.46 | 0.18 | 0.25 | 0.44 | 0.70 |
| **at 0.49** | 1.00 | 0.99 | **0.78** | **0.46** | **0.46** | **0.58** | **0.78** |

The floor went from 0.18 to 0.46 against the reference's 0.55, and the stretch spent under 0.7 from
200 ms to about 100. In the grid the changing columns now keep a fully inked digit through the
middle of the transition instead of showing two grey ghosts.

**The pile-up guard is only partly re-checked.** A burst of rapid taps shows no stacking of dark
half-faded glyphs — if anything that path still reads pale — but it was a burst of `input tap`, not
the 30 ms press-and-hold the constant was originally tuned against, and the crop was off-centre.
By construction the spam end is untouched (`changeSpacing = 0` still yields `rollExitFadeFast`
= 2.9); only intermediate spacings see the lower rate. Do the real hold before trusting this.

**What is still short:** the reference's outgoing curve is flatter still through +133/+183 (0.89 /
0.84 against our 0.74 / 0.42) — it holds a whole glyph and then drops in one move, where ours starts
easing off immediately. A single spring rate cannot make that corner; it wants either a hold before
the exit spring is released, or a shaped exit envelope. Measure before assuming which.

## The one experiment that WAS queued (for reference)

**Two changes, applied together.** Each was tried alone today and each alone measured worse — the
curve's shape and the spring's speed are independent defects and fixing one unmasks the other.

1. `TransitionLogic.presenceAlpha` — replace the `p^e` body with `smoothstep(0f, 1f, c)`.
   Its test `presence_crossingKeepsBothGlyphsSubstantial` asserts the OLD belief (a crossing pair
   summing over 1.05 glyphs of ink) and will fail. That belief is superseded: the per-glyph fit
   shows the reference's summed ink dips to **0.56** at the swap. Replace the assertions with
   `alpha(0.5) ≈ 0.5`, `alpha(0.2) < 0.15`, `alpha(0.8) > 0.85`.

2. `NumericTextView.arriveCrossSlow = 0.49f` — a new constant multiplying **only** `pK` in the
   arrival branch of the spring integration. Not `offK`: that owns the settle tail, which is already
   longer than the reference's (767 ms vs 550 ms), so slowing it makes a worse problem worse.

Both edits exist verbatim in the session history if you want the exact patch.

## What you are aiming at

Per-glyph opacity on the units column of `1,242 -> 1,160`, from `template_fit.py`:

| t (ms) | 83 | 133 | 183 | 233 | 283 | 333 |
|---|---|---|---|---|---|---|
| **iOS old** | 1.00 | 0.89 | 0.87 | 0.44 | 0.18 | 0.07 |
| **iOS new** | 0.00 | 0.07 | 0.01 | 0.12 | 0.41 | 0.74 |
| ours old | 0.92 | 0.55 | 0.29 | 0.16 | 0.12 | 0.09 |
| ours new | 0.02 | 0.03 | 0.14 | 0.32 | 0.48 | 0.64 |

The reference holds the outgoing glyph **whole** until +83 ms and only then drops it; ours is already
half gone at +133 where the reference is at 0.89. The two glyphs barely coexist on iOS — that clean
handover is what "it rolls" means, and its absence is what reads as "it dissolves in place".

Column durations (10%→90% of each column's own change) on the same transition:

| | hundreds | tens | units |
|---|---|---|---|
| iOS | 50 ms | 83 ms | 133 ms |
| ours | 33 ms | 67 ms | 83 ms |

Shape already matches; ours is uniformly ~0.70 of the reference, hence one uniform factor, and
duration goes as 1/sqrt(K), so 1/1.43² ≈ 0.49.

## How to measure — do not improvise this

The Showcase has a **preset button** (`1,242 → 1,160`) that sets the value and turns a black bar on
in the same React commit. The first dark frame IS the frame the native view got the value.

```bash
# 1. record (Android): emulator console recorder, NOT adb screenrecord
TOKEN=$(cat ~/.emulator_console_auth_token)
printf "auth %s\nscreenrecord start --time-limit 12 /path/out.webm\nquit\n" "$TOKEN" | nc -w 3 localhost 5554
sleep 2 && adb -s emulator-5554 shell input tap 540 2035        # the preset button

# 2. normalise, then read the onset — never infer it
ffmpeg -i out.webm -vf fps=60 -c:v libx264 -crf 14 -pix_fmt yuv420p out60.mp4
python3 .agent/tools/sync_onset.py --video out60.mp4

# 3. per-glyph curves (the measurement that matters)
python3 .agent/tools/template_fit.py --video out60.mp4 --platform android --onset <N> --column -1 --span 32

# 4. side-by-side grids
python3 .agent/tools/frame_grid.py --a ios60.mp4 --b out60.mp4 \
  --band 0.24 0.548 --xband 0.10 0.86 --onset-a 929 --onset-b <N> --stride 1 --count 24 --out g.png
```

The iOS reference capture for this preset now lives in the repo, not the scratchpad:
`docs/tune/q_ios60.mp4`, onset **445** (re-recorded 2026-07-30 on iPhone 17 Pro / iOS 26.1, and it
reproduces the reference table above to within 0.03). Its Android counterpart at the smoothstep +
arriveCrossSlow state is `docs/tune/q_android60.mp4`, onset **204**. Both are gitignored — local
only, so re-record if they are gone. The grids from that pair are `docs/tune/grid_*.png`.

Recording iOS: `xcrun simctl io <udid> recordVideo --codec h264 --force out.mov` in the background,
tap the preset through Argent's `gesture-tap` (ONE input path — on iOS use Argent, on Android use
`adb shell input tap`, never both in the same test), then `kill -INT` the recorder.

The old iOS reference for this preset was `q_ios60.mp4`, onset **929** (scratchpad; re-record with
`xcrun simctl io <udid> recordVideo` if it is gone — the app is JS-only for the preset, so a Metro
reload is enough, no iOS rebuild).

## Traps that already cost a day

- **Never infer the onset from motion.** Inside a run of changes it finds the tail of the *previous*
  transition. It produced plausible-looking numbers three times and invalidated every one. Use the
  sync marker, and check the frame before it shows the expected starting value.
- **Three levers are already excluded, with measurements.** `enterSpacingSeconds` 0.045→0.068 made
  the visible stagger *shorter*; `staggerSeconds` 0.04→0.05 delays starts, which the reference barely
  staggers; a per-column spring-rate profile needs a 60× stiffness collapse. See their comments in
  `NumericTextView.kt`.
- **`adb shell screenrecord` degrades within a session** — 43fps → 2.6fps over one afternoon, with no
  error. The emulator console recorder held 24fps throughout. And `adb shell input tap` silently does
  nothing while Argent's simulator-server owns the input path; pick one input path per test.
- **Don't judge darkness from a grid.** Upscaling Android's smaller glyph to match iOS makes it look
  far paler than it measures.
- **`frame_grid.py` mis-crops when the two glyphs differ a lot in width** (a narrow "1" arriving
  where a wide "2" set the crop) and can render a healthy transition as a fade to blank. Sanity-check
  against raw frames before believing a dramatic-looking grid.

## Still open beyond this experiment

- The settle tail: ours 767 ms vs the reference's 550 ms on `2,000 → 1,999`. Owned by `offK`.
- `docs/tune/` is gitignored, so the grids and reports there are local only.
