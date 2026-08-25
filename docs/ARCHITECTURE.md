# Architecture

How `react-native-numeric-text` renders and animates a number on each platform, and the measured transition model the Android engine reproduces.

This is the "how it actually works" companion to the [README](../README.md). For *how the model was reverse-engineered* from a closed-source animation — the measurement techniques, the iteration protocol, and the assumptions the data falsified — see [METHODOLOGY.md](./METHODOLOGY.md).

---

## 1. Two platforms, one contract

The platform strategy is intentionally asymmetric.

- **iOS 17+** uses the system implementation that already exists: SwiftUI `.contentTransition(.numericText())`, formatted by `NumberFormatter`. It *is* the reference; there is nothing to reimplement.
- **Android** uses a custom renderer built specifically for numeric transitions, tuned against the iOS output rather than against taste.

The goal is not pixel-identical output across two different text engines, rasterizers, and fonts. It is **behavioural and perceptual parity**: same ordering, same directions, same timing envelopes, same blur character, same handling of structural changes and interruption. On iOS 17+, SwiftUI itself is the yardstick; Android is measured against it while staying native to Android's rendering stack.

## 2. The measured transition model

The iOS transition was recorded frame by frame and decomposed, per glyph, into four channels — vertical offset, scale, blur, and opacity — by fitting each frame as a crossfade of the column's own settled digits (`a_old·blur(T_old, σ_old, y_old) + a_new·blur(T_new, σ_new, y_new)`). The templates are lifted from the same recording's settled frames, so the only thing the fit has to explain is what the transition did; a settled frame reconstructs to within 0.1%, a mid-crossing frame to 3–9%. Everything below is a measurement, not a guess.

### 2.1 A column holds exactly two glyphs

A rolling column is **one departing glyph and one arriving glyph, crossfading** — not a position on a ten-stop drum. The proof is already in a reference recording: a column going `4 → 6` *while counting down* sweeps eight intermediate digits (4→3→2→1→0→9→8→7→6), yet every frame reconstructs from just the settled "4" and settled "6". The arriving glyph enters at a **fixed offset amplitude however many digits were skipped**, so a jump of 8 looks like a jump of 1.

Direction is **global per transition**, set by whether the *number* grew or shrank (`countsDown`), never per digit:

| | departing glyph | arriving glyph |
|---|---|---|
| counting **down** | exits upward | enters from below |
| counting **up** | exits downward | enters from above |

In `1,242 → 1,160` the tens digit goes `4 → 6` (up) while the number goes down, and it still enters from the same side as every other column.

### 2.2 Four channels on two clocks

Pooled over six columns (three per direction), each aligned on its own onset, the channels collapse onto one curve — which is itself the finding that **every column runs the identical transition, only delayed**. Each channel is a step response `p(t)` of a second-order system:

| channel | mapping | ζ (damping) | response |
|---|---|---|---|
| **offset** | `dy = 0.59375 · (1 − p)` | **0.55** (overshoots) | ~353 ms |
| **scale** | `s = 0.3984 + 0.6016 · p` | **1.00** (critically damped) | ~278 ms |
| **alpha** | `a = p` | **1.00** | ~276 ms |
| **blur** | `σ = 0.125 · (1 − p)` glyph heights | ~0.91 | ~398 ms |

The departing glyph runs the same curves in reverse: `dy = −0.59375 · p`, `s = 1 − 0.6016 · p`, `a = 1 − p`.

Three things matter here:

- **Apple's constants land exactly.** `relativeOffset` `0.59375` is the arriving glyph's one-sided entry amplitude (measured 0.55–0.64 at first detection) — an entry offset, not a spacing between stops, which is why fitting it as spacing never worked. `scale` `0.3984` is the size a glyph is born at and dies at; the Android engine carries it verbatim as `STACK_FINAL_SCALE = 0.3984375` in [`NumericTextTimeline.kt`](../android/src/main/java/com/numerictext/NumericTextTimeline.kt).
- **Position needs its own spring.** Offset overshoots (crosses rest ~135 ms after onset, reaches −0.072 ≈ 12% of the entry amplitude, returns by ~390 ms); scale and alpha never overshoot. Driving all four from one spring costs 2–3× the error — so at least two clocks are required, a bouncy one for position and a critically damped one for size and opacity.
- **The crossfade is convex.** `α_departing + α_arriving ≈ 1.00` at every instant. It is not two independent opacities; the sum staying at one is a consequence of the curves, not a normalisation bolted on afterward.

### 2.3 iOS runs on its own clock

`.numericText()` is **not** driven by the animation in the transaction. Driven by an explicit `.linear(duration:)` from 0.12 s to 1.4 s — a clock 3.6× shorter and one 3.2× longer than the transition — the per-column onsets (70 / 137 / 220 ms) and durations (433 / 517 / 583 ms) are identical to the frame. The transaction animation decides only **whether** the transition runs; its curve, duration, and damping are discarded. This is why `animationDuration` cannot be honoured on iOS and is Android-only.

### 2.4 The cascade

The columns start left-to-right on a fixed budget: **`delay_i = 0.15 s · i/(n−1)`**, most-significant first, quantised to the 60 Hz display (the 67 ms / 83 ms gaps seen in captures are one 75 ms delay landing on alternate ticks). With one changing column the delay is zero — the total 0.15 s is divided among the columns that actually change, leader at zero. A **structural change does not stagger**: on `9,950 → 10,123` every column starts within ~29 ms of the commit, together, because the horizontal re-layout is not part of the cascade.

### 2.5 Structural changes

When the integer digit count changes (`999 → 1,000`, `10,000 → 1,000`), affected columns split into independent **enter/exit lifecycles** instead of a roll: a new digit appears in place, small and blurred, and grows; a removed digit shrinks and blurs away in place. Neither shows the large vertical entry a roll has. The classifier keys on **numeric structure, never pixel width** — fonts and locales change width without changing structure.

### 2.6 Interruption: a stack of independent transitions

When a value changes mid-flight, the transition is **never cancelled, retargeted, or dropped**. It runs its own curves to completion; the "discarded" glyph simply keeps fading on the same law every departing glyph runs (`α = 1 − p(t − its own onset)`). What makes ordinary cadences look like a clean pair is only that older transitions have already faded to near-zero by the time the next one lands.

The number of glyphs meaningfully alive in a column therefore depends on cadence:

| cadence | glyphs alive |
|---|---|
| isolated change | 2 |
| ~220 ms tap | 2 (the third is at α ≈ 0.1) |
| ~30 ms burst | 3, sometimes 4 |

Under a fast sustained roll a column shows only the real committed values — never an intermediate digit — and degrades progressively into a soft pair, recovering once the changes stop. This is a first-class case, not a stress test bolted on after the fact.

## 3. The Android renderer

The Android implementation is deliberately **not** a grid of independent digit `Text` views. It is built around a small set of invariants:

1. **Typeset the complete formatted line first.** Layout and glyph positions come from the full value before it is partitioned into transition slots.
2. **Keep immutable value rasters.** Outgoing content keeps the pixels of its original formatted value; incoming content references the new target.
3. **Use structural identity.** Integer digits are anchored from the left, fraction digits from the decimal boundary, and each affix (currency symbol, percent sign, accounting bracket) is keyed by its **distance from the digits** — so `$999 → $1,000` slides one `$` left instead of destroying and recreating it, and a trailing `1.234,50 €` keeps its symbol through the same change.
4. **Preserve history during retriggers.** A new target does not require the previous transition to finish.
5. **Keep frame work bounded.** Bitmap extraction and per-slot bitmap creation stay out of the normal render/update hot path.
6. **Evaluate motion analytically.** Native transition state is computed directly for the current frame rather than integrated as a frame-rate-dependent simulation.

Those invariants let ordinary ±1 changes, `999 → 1,000`, reversals, and sustained press-and-hold updates all use the same underlying model.

### Alignment

By default the line is composed about its centre, so a number grows and shrinks around its middle. `textAlign: 'left' | 'right'` shifts the whole reflow onto that edge instead (`alignMode` in the roll engine): every line — the one arriving and the one leaving — is measured from the same edge, so an aligned number keeps that edge fixed for the entire transition, with nothing drifting or jumping as the width changes. `center` is byte-identical to the original path.

### Blur

- **API 31+** uses cached native rendering primitives (`RenderNode`) and a `RenderEffect` blur — a property of the draw, applied at composite time.
- **API 24–30** temporarily switches only the numeric view to a software-layer blur path while it is animating, then restores the normal layer state.

### Two-colour amounts

`fractionColor` is honoured without disturbing the transition on either platform, but by different means:

- **Android** draws the line into one raster and tints it at composite time, so a colour is a property of the draw, not of the bitmap. The second colour is a second `PorterDuffColorFilter`, chosen per keyed slice; settled and transitioning frames go through the same draw path, so both pick it up.
- **iOS** carries the colour inside a single `AttributedString` with no outer `foregroundStyle`. This matters: `.numericText()` rolls the glyphs of *one* `Text`, so keeping the text a single run — colour as an attribute rather than an outer style over concatenated pieces — is what lets the coloured fraction roll with the rest instead of cross-fading on its own. Two sibling views would each rasterise and transition independently and drift apart on any change that moves the decimal point.

## 4. Typography

- **iOS** defaults to the rounded system design. `fontFamily: 'system'` opts into the plain system design; a font registered by the host app can be supplied through `style`.
- **Android** bundles a subset of [Sunghyun Sans](https://github.com/anaclumos/sunghyun-sans) (OFL-licensed), a redistributable rounded counterpart to Apple's rounded-system presentation — nine real weights, ~33 KB each. The subset covers Latin-script numeric-formatting glyphs (digits, separators, signs, currency symbols, and the Latin letters in ISO currency codes). Coverage is checked against the glyphs the current format will actually draw; if any required glyph is missing, the renderer falls back to the platform font rather than drawing missing-glyph boxes. The full licence is at `android/src/main/assets/fonts/OFL.txt`.

## 5. Performance

The Android renderer is designed for continuously changing values, not only isolated showcase transitions. The normal animation path avoids per-frame bitmap extraction and per-slot bitmap creation; API 31+ uses cached native primitives and `RenderEffect`, API 24–30 switches only the numeric view to the software blur path while animating and restores it afterward. This is what makes press-and-hold counters, live balances, scores, timers, and rapidly updating dashboards viable rather than only single showcase transitions.

## 6. Formatting: intentionally unsupported `Intl` options

Every supported `format` option has both a formatting meaning **and** a stable transition model on both platforms. These are left out on purpose:

| Option | Reason |
|---|---|
| `currencyDisplay: 'name'` | Localized names change spelling with the value (`dollar`/`dollars`); mutable affixes are deferred until they have an explicit transition contract. |
| `notation: 'compact'` (`1.2K`) | Both platforms can produce it, but from different CLDR vintages, so they would disagree on the string for the same input. |
| `signDisplay` | Android's `NumberFormatter` is API 30; iOS's `NumberFormatter` has no equivalent. |
| `currencyDisplay: 'narrowSymbol'` | Same version constraint. |
| `unit`, `unitDisplay`, `roundingIncrement`, `roundingMode` | Same, except `roundingMode`, which is fixed at half-away-from-zero on purpose — two renderers disagreeing on the number they draw is a bug, not a preference. |

## 7. Going deeper

- **[METHODOLOGY.md](./METHODOLOGY.md)** — how the model above was reverse-engineered without access to Apple's implementation: measurement apparatus, the techniques in order of adoption, the iteration protocol, the reverse-engineered findings, and a table of plausible assumptions the data falsified.
- **[`research/engine-derivation`](https://github.com/AmatoGiulio/react-native-numeric-text/tree/research/engine-derivation)** branch — the full raw derivation: every ground-truth recording (`artifacts/`), the measurement and fitting scripts (`.agent/tools/`), the iteration-by-iteration log, and the per-glyph measurement notes.
