# The transition itself — read out of the reference, per glyph, per frame

Measured 2026-08-04 against `artifacts/gt_ios_ref` (1,242→1,160), `artifacts/gt_ios_up5`
(1,160→1,242), `artifacts/gt_ios_alt3` and `artifacts/gt_ios_bursts`, plus three recordings made
for this pass: `lin14_single`, `lin012_single`, `gt_ios_struct`.

This file answers a different question from `IOS_GROUND_TRUTH.md`. That one records what the
reference's *summed ink* does. This one records what each individual glyph does, because the frames
now reconstruct: every claim below comes from a fit whose residual is printed beside it, and the
model rebuilds the reference frame to within 3–9% through the middle of a crossing and 0.1% at rest.

Every number is a measurement. Where something is inferred rather than measured it says so.

---

## 1. The transition has its OWN clock — the transaction animation is a boolean

`IOS_GROUND_TRUTH.md` and `NumericTextSwiftUIHost.swift` both assume the opposite: that
`.numericText()` is driven by whatever animation is in the transaction, so everything measured is
the transition's curves multiplied by an unknown spring. **It is not.** Driven by an explicit
`.linear(duration:)`, with the override confirmed in the app's own log each time:

| transaction animation | column onsets | ink floor, col 2 | duration |
|---|---|---|---|
| `.spring()` (default) | 70 / 137 / 220 ms | 0.517 | 433 / 517 / 583 ms |
| `.linear(duration: 1.4)` | 70 / 137 / 220 ms | 0.520 | 433 / 517 / 583 ms |
| `.linear(duration: 1.0)` | 70 / 137 / 220 ms | 0.520 | 433 / 517 / 583 ms |
| `.linear(duration: 0.8)` | 70 / 137 / 220 ms | 0.520 | 433 / 517 / 583 ms |
| `.linear(duration: 0.12)` | 70 / 137 / 220 ms | 0.517 | 433 / 517 / 583 ms |
| `nil` | — | — | snaps, no transition |

A clock 3.6x shorter than the transition and one 3.2x longer both leave it identical to the frame.
So the transaction animation only decides **whether** the transition runs. Its curve, its duration
and its damping are discarded.

Consequences: the timings below are constants of the transition and not of the spring, so nothing
has to be deconvolved; `animationDuration` cannot be honoured on iOS at all; and Apple's
`maxDurationMultiple = 1.25` does not clamp the transaction's duration into it (0.12 × 1.25 is
0.15 s, and the transition still ran 433 ms).

Reproduce with `SIMCTL_CHILD_NUMERICTEXT_LINEAR=<seconds|none>` alongside `NUMERICTEXT_RECORD=1`.

## 2. A column holds exactly TWO glyphs, and the offset does not scale with digit distance

The strongest case is already in the reference recording. Column 3 of 1,242→1,160 goes **4 → 6 while
counting down** — eight stops on a ten-stop drum (4→3→2→1→0→9→8→7→6). Fitted with two templates
only, the settled "4" and the settled "6", every frame reconstructs at **residual 0.028–0.074**.
Seven intermediate digits sweeping through the window cannot hide inside that.

Column 4 goes 2 → 0, two stops. Adding a third template — a settled "1" lifted from another column,
validated by rebuilding a real settled "1" at amplitude 1.000, residual 0.001 — improves the fit
by nothing outside one noisy frame, and where it does take amplitude it sits **on top of the "0"**
(dy +0.29 against the "0" at +0.20) rather than between the two, which is where a strip sweeping
through would have to put it. The arriving "0" starts at **dy ≈ +0.55…0.64**, one offset amplitude,
not the two a two-stop move would need.

So the roll is **not a position on a strip or a drum**. It is one departing glyph and one arriving
glyph, crossfading, with a fixed offset amplitude however many digits were skipped.

**All columns roll the same way, set by `countsDown`, regardless of what the individual digit did.**
In 1,242→1,160 the tens digit goes 4→6 — up — while the number goes down, and its glyph still
enters from the same side as every other column.

Direction, both confirmed on five-run captures:

| | departing glyph | arriving glyph |
|---|---|---|
| counting **down** | exits upward | enters from **below** |
| counting **up** | exits downward | enters from **above** |

## 3. The four channels, and they are on different clocks

Pooled over six columns (three per direction), each aligned on its own onset. The six collapse onto
one curve — the spread column-to-column is the `±` below, and it is small — which is itself the
finding that **every column runs the identical transition, only delayed**.

| t since onset | offset dy | scale | blur σ | alpha | departing alpha | α sum |
|---|---|---|---|---|---|---|
| 0 ms | +0.576 | 0.61 | 0.088 | 0.05 | 0.933 | 0.98 |
| 17 | +0.588±0.075 | 0.449 | 0.110 | 0.211 | 0.838 | 1.06 |
| 34 | +0.474±0.033 | 0.532 | 0.101 | 0.318 | 0.726 | 1.01 |
| 51 | +0.372±0.025 | 0.627 | 0.101 | 0.384 | 0.589 | 1.00 |
| 68 | +0.288±0.018 | 0.690 | 0.082 | 0.481 | 0.475 | 0.96 |
| 85 | +0.198±0.024 | 0.762 | 0.079 | 0.567 | 0.421 | 0.99 |
| 102 | +0.144±0.018 | 0.805 | 0.060 | 0.629 | 0.345 | 0.97 |
| 119 | +0.084±0.018 | 0.838 | 0.060 | 0.746 | 0.273 | 1.03 |
| 136 | +0.036±0.014 | 0.876 | 0.041 | 0.752 | 0.267 | 1.04 |
| 153 | −0.012±0.009 | 0.900 | 0.041 | 0.845 | 0.255 | 1.11 |
| 187 | −0.060 | 0.938 | 0.022 | 0.886 | 0.139 | 1.03 |
| 204 | **−0.072** | 0.953 | 0.022 | 0.923 | 0.077 | 1.00 |
| 238 | **−0.072** | 0.967 | 0.022 | 0.977 | 0.079 | 1.04 |
| 272 | −0.060 | 0.977 | 0.002 | 0.961 | 0.025 | 0.99 |
| 323 | −0.036 | 0.996 | 0.002 | 0.985 | 0.006 | 0.99 |
| 391 | 0.000 | 1.000 | 0.000 | 0.990 | 0.000 | 0.99 |
| 612 | 0.000 | 1.000 | 0.000 | 1.000 | 0.000 | 1.00 |

Offsets are in glyph heights (cap height), blur is a Gaussian σ in the same unit.

**α_departing + α_arriving ≈ 1.00 at every instant.** It is a convex crossfade, not two independent
opacities — which is exactly the normalisation `f981795` was missing.

Fitted analytic forms, each a step response `p(t)` of a second-order system:

| channel | mapping | ζ | ωn | response 2π/ωn | mean err |
|---|---|---|---|---|---|
| **offset** | `dy = 0.59375 · (1 − p)` | **0.55** | 17.8 rad/s | **353 ms** | 0.016 gh |
| **scale** | `s = 0.3984 + 0.6016 · p` | **1.00** | 22.6 | 278 ms | 0.011 |
| **alpha** | `a = p` | **1.00** | 22.8 | 276 ms | 0.026 |
| **blur** | `σ = 0.125 · (1 − p)` gh | 0.91 | 15.8 | 398 ms | 0.003 |

The departing glyph is the same curves run the other way: `dy = −0.59375 · p`,
`s = 1 − 0.6016 · p`, `a = 1 − p`.

**Apple's constants land exactly.** `relativeOffset` 0.59375 is the arriving glyph's one-sided entry
amplitude — measured 0.55–0.64 at first detection — and not the spacing between two stops, which is
why fitting it as spacing never worked. `scale` 0.3984 is the size a glyph is born at and dies at —
measured 0.45–0.53 at the first frame it is bright enough to fit. Blur peaks at σ ≈ 0.10–0.125 glyph
heights; taking SwiftUI's `.blur(radius:)` as σ ≈ radius/2 that is a radius of 0.20–0.25 glyph
heights against Apple's stored 0.25 relative. *Which length it is relative to is still not pinned —
cap height fits, font size would give 0.35.*

**The offset overshoots and the other three do not.** dy crosses rest 133–145 ms after onset, reaches
−0.072 (12.1% of the entry amplitude, against 12.6% predicted for ζ = 0.55) at ~205–240 ms, and
returns to rest at ~390 ms. Confirmed independently by fitting the arriving glyph ALONE inside a band
around rest, where the departing glyph cannot reach: same −0.074 peak, same return.

Scale and alpha share one critically damped curve (ζ = 1.00, ωn ≈ 22.7) and never overshoot. Driving
all four from the offset's spring costs 2–3x the error (scale 0.011 → 0.027, alpha 0.026 → 0.049),
so **one scalar per transition is not enough** — it needs at least two, a bouncy one for position and
a critically damped one for size-and-opacity.

Wall-clock: geometry is done at ~390 ms, blur at ~255 ms, alpha's last 1% trails to ~600 ms.

## 4. The wave — `delay_i = 0.15 s · i/(n−1)`, and the 67/83 asymmetry is display quantisation

Six fresh isolated three-column changes (`artifacts/gt_ios_bank`, six taps of +123 spaced 4 s):

| run | onsets | gaps | span |
|---|---|---|---|
| 1,123 | 79 / 145 / 229 | 67, 83 | 150 |
| 1,246 | 72 / 139 / 222 | 67, 83 | 150 |
| 1,369 | 74 / 141 / 224 | 67, 83 | 150 |
| 1,492 | 51 / 134 / 218 | 83, 83 | 167 |
| 1,615 | 78 / 145 / 228 | 67, 83 | 150 |
| 1,738 | 85 / 151 / 218 | 67, 67 | 133 |

Span is 150 ms every time, to within one display frame. **The 67/83 asymmetry is not a law — it is
75 ms landing between two 60 Hz ticks**: 67 = 8/120 s and 83 = 10/120 s bracket it, and the run that
came out 83/83 and the one that came out 67/67 are the same delay caught on the other side of the
tick. `delay` being stored in 120ths is consistent with that.

**With one changing column the delay is zero.** `1,000 → 1,001` (`artifacts/gt_ios_unit`, the first
step of the controlled roll) starts the units column at **51–68 ms**, the same presentation latency
as the leader of a three-column change — not 150 ms later. Same again for the tens column of
`1,009 → 1,010`, which is the leader of that two-column change: onset 72 ms after its commit.

So the total is fixed at Apple's 0.15 s and divided among the columns that actually change, leader
at zero, most significant first, quantised to the display.

**A structural change does not stagger its reflow.** On `9,950 → 10,123` every column starts moving
within 29 ms of the commit, together — the horizontal re-layout is not part of the cascade. (That
same reflow is why the per-column onsets of a structural change cannot be read this way at all; the
windows are fixed and the glyphs slide through them.)

## 5. Under crowding it does not stack — it stalls

This is the behaviour the alternation and roll tables have been circling, and it is visible directly.

At **60 ms** the column never resolves. It sits in a steady state: two half-formed glyphs, one above
and one below, both at roughly half scale and half opacity, essentially unchanging for the whole
burst. Nothing ever reaches full size.

At **240 ms** each digit fully resolves — big, sharp, legible — before the next change.

Through a **continuous roll** (+123 every ~35 ms) the units column shows the real values and only the
real values — 0, 3, 6, 2, 5, 8, 1 — never an intermediate digit, and degrades progressively from
crisp digits into the same soft pair, recovering once the changes stop.

A stack of independent transitions is ruled out by the 60 ms case: each transition would run its own
scale curve to completion regardless of what arrives later, so glyphs at full size would be present
throughout. There are none.

**The intermediate values are skipped, and that is now seen directly.** `1,000 → 1,010` one step
every 30 ms, one changing column, captured clean (`artifacts/gt_ios_unit`): the units column shows a
crisp "0", collapses into an unreadable soft pair from ~100 ms to ~330 ms while nine commits land,
and re-emerges on **9** and then **0**. It never shows 1, 2, 3, 4, 5, 6, 7 or 8 legibly at all. Ink
falls to 0.37 of a settled glyph and sits on that plateau for the whole burst, then recovers with a
visible ring rather than monotonically.

The tail is the cascade again, and it checks out: the last commit `1,009 → 1,010` changes two
columns, so the units is the follower and waits 150 ms — its 9→0 roll starts at ~412 ms, which is
why "9" is on screen crisply between ~385 and ~412.

### One interruption at a time: a retargeted PAIR, and the arriving glyph restarts

The 30 ms burst is too crowded to read. A **220 ms tap cadence** is the regime that separates the
models — a transition lasts ~430 ms so exactly two are alive, and the eight digits involved are all
different (`artifacts/gt_ios_taps/run-1785830945530`, +123 x8, gaps 217–233 ms; units column sees
0→3→6→9→2→5→8→1→4).

Fitting each frame with the known pair `{d(k-1), d(k)}` and then again with `{d(k-2), d(k-1), d(k)}`:

| u into transition k | pair α old/new | pair resid | triple resid | α of d(k-2) |
|---|---|---|---|---|
| 20 ms | 0.81 / 0.19 | 0.110 | 0.110 | 0.13 |
| 75 | 0.60 / 0.41 | 0.078 | 0.079 | 0.10 |
| 130 | 0.24 / 0.76 | 0.127 | 0.097 | 0.11 |
| 185 | 0.12 / 0.92 | 0.113 | 0.110 | 0.03 |

**The third glyph buys nothing.** The pair reproduces the frame at 0.065–0.143, α_old + α_new = 1.00
throughout, and the digit from the previous transition takes 0.01–0.13 with no residual improvement.
So an interruption does not stack — the old departing glyph is gone.

What the pair DOES, against the isolated curve of §3 (magnitudes; this capture counts up so the signs
are mirrored):

| u | isolated dy / s / α | interrupted dy / s / α |
|---|---|---|
| ~18 ms | 0.588 / 0.449 / 0.211 | **0.580 / 0.440 / 0.205** |
| ~51 | 0.372 / 0.627 / 0.384 | 0.300 / 0.690 / 0.498 |
| ~135 | 0.036 / 0.876 / 0.752 | **0.040 / 0.870 / 0.756** |
| ~186 | 0.060 / 0.938 / 0.886 | **0.040 / 0.940 / 0.913** |

**The arriving glyph restarts from the full ±0.59375 entry amplitude, at scale 0.3984 and α 0**, on
the same curve as an isolated change — it does not resume from wherever the previous arrival had got
to. The departing glyph, by contrast, is *continuous*: it is the previous transition's arriving glyph
and it leaves from where it currently sits (measured +0.16 at u=21, i.e. its own overshoot position
+0.06 plus one step of travel, not from 0).

So the rule is: **on a change, the pair is re-formed — the incoming glyph of the old pair becomes the
outgoing glyph of the new one, continuing its motion, the new digit enters at full amplitude, and the
old outgoing glyph is dropped.** At 220 ms the dropped glyph was already at α≈0.1, so dropping it is
invisible. That is also the prediction for where this model must break: at a cadence fast enough that
the dropped glyph is still bright.

### …and the deep-crowding anomaly was mostly the analysis window — partially retracted

Fitting the stalled column with two settled-digit templates, jointly refined, free in offset, in
horizontal and vertical scale separately, and in blur, over all ten consecutive digit pairs:

| moment | best pair | residual |
|---|---|---|
| t = 151 ms (stalled) | (9,0) | **0.200** — and (8,9) 0.217, (2,3) 0.234, i.e. no pair wins |
| t = 218 ms (stalled) | (6,7) | **0.216** — (8,9) 0.218, (5,6) 0.223 |
| t = 301 ms (stalled) | (8,9) | **0.213** — (4,5) 0.225, (5,6) 0.225 |
| t = 401 ms (burst over) | (9,0) | **0.056**, and it is decisively the best pair |

Against 0.03–0.09 for an isolated crossing. Once the burst ends the model snaps back to being right,
identifies the correct pair, and puts it in sensible geometry. **While the burst runs, no pair of
settled digits reproduces the column at all**, and no pair is preferred over the others — the
signature of the true content not being in the dictionary. Adding a third and a fourth glyph gets to
~0.18 and stops.

Two readings were offered and **both were wrong, because the measurement was**. Three tests killed
them and found the real cause:

- **Not the snapshot.** "On interruption SwiftUI crossfades from the composite already on screen"
  predicts that using the captured frame at the commit as the outgoing template should fit. It fits
  *worse* — 0.26–0.35 against 0.245–0.267 for a settled pair, and 0.223 against 0.073 once the burst
  ends (`.agent/tools/snapshot.py`). Rejected.
- **Not more glyphs.** Matching pursuit over all ten settled digits, each free in offset, scale and
  blur, saturates: 1 glyph 0.48, 2 glyphs 0.236, 3 glyphs 0.181, and glyphs 4–7 take **amplitude
  0.00** and move the residual by 0.002. Nothing in the dictionary explains the rest.
- **It was the neighbouring column.** Column windows were cut from the run's *settled* frame. In
  `1,000 → 1,010` the settled tens digit is a narrow "1" (87 px of ink) but the tens digit *during*
  the burst is a wide "0" (147 px), which spills across the boundary into the units window. The
  unmodelled ink is visible as a black crescent on the left edge of every crowded target, and it is
  absent from every model. Re-cut the window from the *pre-burst* layout and add the static tens "0"
  as a third template and the residual collapses:

| t | pair only | pair + static neighbour |
|---|---|---|
| 110 ms | 0.808 | 0.136 |
| 151 | 0.779 | **0.048** |
| 190 | 0.766 | **0.043** |
| 234 | 0.753 | **0.040** |
| 270 | 0.766 | **0.044** |

  The neighbour comes out at amplitude 1.00 and dy 0.00 — exactly a static settled glyph, which is
  what it should be. Validation on the settled pre-burst frame: 0.061.

**What survives of the anomaly.** Those residuals are over the whole two-column window, and the
neighbour is a large sharp glyph that flatters the normalisation. Restricted to the units half alone
the residual is still **0.14–0.41**, the fitted amplitudes hit the 1.05 clamp and their sum runs
1.15–1.81 instead of 1.00, and the fitted offsets flip sign between frames. So at a 30 ms cadence
something is still not a pair of settled digits — but it is a much smaller anomaly than the 0.20
figure this file used to carry, and the "no pair is preferred / horizontal squeeze / rendered
differently" reasoning that rested on it is **retracted**: that was the neighbour's ink pulling every
fit sideways.

**And with the window fixed, the third glyph finally shows up** — which is what the 220 ms section
predicted. Re-running the glyph count on the corrected window, units-restricted residual:

| moment | 2 units glyphs | 3 units glyphs | 4 units glyphs | α of 3rd / 4th |
|---|---|---|---|---|
| t = 168 ms | 0.148 | **0.107** | **0.095** | 0.58 / 0.09 |
| t = 234 ms | 0.120 | 0.133 | **0.081** | 0.09 / **0.38** |
| t = 301 ms | 0.181 | **0.158** | — | 0.13 / — |

Extra glyphs now take real amplitude — 0.58 for the third at t=168, 0.38 for the fourth at t=234 —
and the residual falls with them. **That also retracts the "saturates at 0.18, extra glyphs take
amplitude 0.00" result**: with the neighbour's ink unmodelled the pursuit was spending its capacity
trying to reach ink no units-column template can reach, and stalled. Given a correct window it does
not stall.

So the discarded glyph does **not** vanish on the next change — it keeps fading, and at a cadence
fast enough it is still bright when the one after that arrives. That is exactly the failure mode the
220 ms measurement predicted, and it reconciles all three regimes:

| cadence | glyphs meaningfully alive |
|---|---|
| isolated | 2 |
| 220 ms | 2 (the third is at α ≈ 0.1) |
| 30 ms | 3, sometimes 4 |

The honest state: the pair model is measured and holds for isolated changes, for a 220 ms
interruption, and through the recovery after a burst. At 30 ms it needs a third and occasionally a
fourth glyph.

### The fade law — SOLVED: there is no special fade, and nothing is ever discarded

`runPair(gapMs)` in `example/src/Showcase.tsx` produces the shape that settles it: settle, one
change, a frame-clock gap, a second change, silence. At a **28 ms** gap the glyph the second change
"discards" is still at α ≈ 0.83 when it happens, which is what makes it readable
(`artifacts/gt_ios_triple`, units column 0 → 3 → 6).

The decisive test needs no fitting at all — a forward model of the column's ink from the analytic
curves and the capture's own settled glyphs, against the measured ink:

| since the drop | measured ink | if the glyph is DISCARDED | if it KEEPS RUNNING |
|---|---|---|---|
| 5 ms | **0.595** | 0.189 | 0.823 |
| 39 | **0.431** | 0.199 | 0.484 |
| 72 | **0.432** | 0.316 | 0.434 |
| 105 | **0.536** | 0.501 | 0.548 |

And the free three-glyph fit, now well conditioned because the glyph is bright, returns amplitudes
that track its own uncancelled curve: 0.800 against 0.825 predicted at +5 ms, 0.573 against 0.554 at
+39, 0.094 against 0.109 at +139, with residuals 0.042–0.081.

**So a transition is never cancelled, never retargeted, and never dropped. It runs its own curves to
completion whatever arrives afterwards.** The fade law of the "discarded" glyph is simply
`α = 1 − p(t − its own onset)`, the same curve every departing glyph runs.

That **corrects the reading of the 220 ms result above.** The third glyph was measured there at
α 0.01–0.13 and read as "the old outgoing glyph is dropped". It was not dropped — by 220 ms it had
faded to 0.01–0.13 *on this very law*, which is what the same law predicts. The 220 ms data never
distinguished the two; the 28 ms data does.

So the architecture is a **stack of independent transitions**, exactly as `.agent/NEXT.md` item 1
hypothesised. What makes it look like a pair at ordinary cadences is only that older transitions
have already faded. And the ~1.0 alpha sum is a consequence of the curves, not a normalisation rule
that has to be implemented.

A replication at a **79 ms** gap does not discriminate, and should not be expected to: by then the
leftover is worth α 0.34 falling to 0.02, and the two models differ by less than the measurement's
own systematic (~3–5%, the forward model running slightly rich at late times).

### (superseded) the bounded version of this, kept for the argument

Measured on `artifacts/gt_ios_pairs`: isolated triples — a settled value, one change, a controlled
gap, a second change, then silence. Three distinct digits in the column and exactly one interruption,
which is the best-conditioned form of the question available. Achieved gaps 103 / 154 / 197 ms
(`.agent/tools/fadelaw.py`, `fadelaw2.py`, `inktest.py`).

Four things are established:

- **It does not keep running its own curve.** At the drop + 8 ms with a 103 ms gap the discarded
  glyph measures α = 0.101 free / 0.059 with its geometry constrained, against **0.280** predicted if
  its own transition simply continued. Being dropped accelerates the fade.
- **It never adds ink.** A forward model with no fitting — analytic curves, the capture's own settled
  glyphs — puts measured ink at or *below* a two-glyph model at every frame after the interruption
  (0.465 against 0.636 at the drop, 0.762 against 0.827 later). Adding the leftover glyph's ink moves
  the model further from the measurement, never closer. So nothing accumulates.
- **The live alphas sum to ≈1.** 0.96 immediately after the interruption; 1.00 throughout the 220 ms
  cadence; 1.00 for an isolated change.
- **It is not instantaneous either.** At a 30 ms cadence the third and fourth glyphs carry 0.58 and
  0.38.
- **And it is not plain renormalisation of unchanged curves.** That predicts α_A/α_B = 0.41 at the
  drop + 8 ms; measured 0.18.

What is *not* measured is the functional form, and the reason is the experiment rather than the
analysis: **at every gap the app can produce the glyph is already faint when it is dropped** — 0.28
at a 103 ms gap, 0.12 at 154 ms, 0.07 at 197 ms — which is at the fit's own noise floor (residual
0.05–0.10, and the free three-glyph amplitudes visibly jump, e.g. 0.700 → 0.371 → 0.409 on the 197 ms
run where the constrained fit says 0.04 → 0.06 → 0.03).

**What would settle it:** an isolated triple with a gap of 30–60 ms, where the discarded glyph is
still at α ≈ 0.7 when it is dropped. Two taps closer than ~90 ms collapse into a single commit in the
example app's tap path (measured: a 55 ms pair produced one mark, a 94 ms pair produced two), so this
needs a scripted preset beside the existing alternation ones — a `runPair(gapMs)` in
`example/src/Showcase.tsx` driving A → B → C on a frame clock. That is a small JS change to the
harness and it is the single highest-value thing left to record.

What is safe to carry forward: one persistent pair per column, retargeted rather than restarted,
with a fixed ±0.59375 amplitude — that is measured, and it holds for isolated changes and for the
recovery after a burst. The deep-crowding regime is not yet modelled.

## 6. Structural changes — measured only qualitatively

`1,000 → 10,000` inserts a digit: it appears in place, small and blurred, and grows. `10,000 → 1,000`
removes one: it shrinks and blurs away in place. Neither shows the large vertical entry a roll has.
**This is from the contact sheets, not from a fit** — the number re-lays out horizontally on a
structural change, so the per-column windows used everywhere above are contaminated by neighbours
sliding through them, and the numbers that pipeline produced for the birth are not trustworthy.
Measuring these properly needs the fit to solve horizontal position jointly across all columns.

## 6b. The burst captures drop frames — the alternation and single-change ones do not

Frame intervals inside the changing window:

| capture | median dt | intervals > 25 ms |
|---|---|---|
| `gt_ios_bursts` (+123 every 30 ms) | 16.7 ms | **12 of 27**, up to 50 ms |
| `gt_ios_unit` (+1 every 30 ms) | 16.7 ms | 1 of 32 |
| `gt_ios_alt3` (60 ms alternation) | 16.7 ms | 1 of 85 |
| `gt_ios_ref` (single change) | 16.7 ms | 0 of 21 |

The +123 burst moves three columns every 30 ms and the recorder's display link misses nearly half
its ticks doing it. Anything read off `gt_ios_bursts` at frame resolution is unreliable, and a strip
of "every 16.7 ms" built from it silently repeats frames — which is how this pass first misread the
roll as showing each digit crisply. **Use `gt_ios_unit` for burst behaviour**; it stresses one column
instead of three and records clean.

## 7. What this leaves open

- Which length the 0.25 relative blur is relative to.
- Whether the two clocks in §3 are genuinely two springs or one spring feeding two different
  easings; the data separates them but does not explain them.
- Structural insert/remove, per §6.
- Whether the offset's ζ = 0.55 / 353 ms is exactly some named SwiftUI spring. It is not
  `.spring()`'s default (0.55 response, 0.825 damping), and it does not have to be one.

## 8. The instruments

`.agent/tools/decompose.py` was the starting point and its residual reaches 0.32 mid-crossing, which
by its own docstring invalidates those rows. Four things were wrong, and the scratch fitter used here
fixes all four — worth folding back in if this line of measurement continues:

1. **The blur clipped its own tails.** Blurring a tile and trimming back to the input size turns a
   heavily blurred glyph into a hard-edged rectangle; at σ = 22 px on a 128 px tile that is most of
   the glyph. This alone took the mid-crossing residual from 0.60 to 0.09.
2. **Templates should come from the capture, not from a font atlas** — then the only difference
   between template and frame is what the transition did, and a settled frame reconstructs at 0.001.
3. **The settled "old" frame is the one just before the change mark, not frame 0.** Frame 0 of a
   preset run is mid-transition into the parked value.
4. **Amplitudes must be clamped to [0, 1.05] and each frame started from the previous one's
   solution.** Unbounded and independent, the fit returned amplitude 1.49 mid-crossing and hopped
   between degenerate corners of the grid.

The host's `.mask(edgeFadeMask)` is also in the forward model: glyph ink reaches capture row 476 and
the bottom fade starts at 453, so a departing glyph really is attenuated by it.
