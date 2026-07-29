# Reverse-Engineering SwiftUI's `numericText`: Methodology

How the Android renderer of this library reached perceptual parity with SwiftUI's
`.contentTransition(.numericText())` — the measurement techniques, the iteration protocol,
the reverse-engineered animation model, and the assumptions the data falsified along the way.

This document exists so the process can be reproduced (for future parity work against any
closed-source animation) and so the final parameter set is traceable to a measurement rather
than to taste.

---

## 1. Problem

Replicate a closed-source system animation (Apple's `numericText` content transition, iOS 17+)
on Android, with **no access to its implementation** — only to its rendered output. The target
is not "similar": it is frame-level behavioural parity — same ordering, same directions, same
timing envelopes, same blur character.

Two things make this hard:

1. The animation is **compound**: per-glyph vertical rolls, glyph births/deaths, horizontal
   layout reflow, blur, scale and opacity all overlap in time. Eyeballing a recording cannot
   attribute a perceived difference to the right property.
2. Perception is **holistic**: a single wrong curve (e.g. opacity resolving 3 frames early)
   reads as "feels mechanical" without revealing *what* is wrong.

The answer was to progressively replace judgement with measurement.

## 2. Apparatus

### 2.1 Reference implementation

The gold standard runs on the iOS Simulator via Expo UI, which exposes the real SwiftUI
modifier (no reimplementation):

```tsx
<Text modifiers={[
  font({ size: 84, weight: 'bold', design: 'rounded' }),
  contentTransition('numericText', { countsDown }),
  animation(Animation.spring(), value),
  monospacedDigit(),
]}>
```

(`example/src/reference/AnimatedNumber.ios.tsx`, kept verbatim; on Android the same screen
renders this library instead.)

### 2.2 Deterministic scripted sequence

Both platforms are driven by the **same timed script** (`example/src/sequence.ts`): a single
timeline covering every stressing case — isolated ±1 rolls, multi-digit carries, length growth
and shrink (9→10, 99→100, 999→1,000), big jumps (1↔9,999), decimals (1→1.5), sign changes
crossing zero (0→−1 is a *critical* probe, see §5.1), a rapid hold burst, and mixed rapid jumps.
One button press produces an identical value-vs-time curve on both platforms, so two screen
recordings are directly comparable.

Each step also renders a `phase` label on screen, which lets any frame of a recording be mapped
back to what it was testing.

### 2.3 Recordings

- iOS Simulator screen recording; Android device screen recording (60 fps requested).
- **Pitfall — variable frame rate (VFR):** both recorders emit VFR streams. Two consequences:
  - `ffmpeg -ss` *before* `-i` seeks to keyframes and is not frame-accurate; all precise cuts
    must use `-vf trim=` (decode from zero) or, better, normalise first with `fps=60` **before**
    `trim` so timestamps live on a constant grid.
  - Wall-clock drift accumulates between the two recordings (~0.2 s per 10 s is typical), so a
    single global alignment offset is only valid near its anchor. **Every analysed event must be
    aligned on its own onset.**

## 3. Measurement techniques (in order of adoption)

Each technique earned its place by answering a question the previous one could not.

### 3.1 Contact-sheet grids (filmstrips)

Tile consecutive frames side by side, reference row above, implementation row below, columns
time-aligned:

```bash
ffmpeg -i ref.mov -vf "fps=60,trim=START:END,setpts=PTS-STARTPTS,\
crop=W:H:X:Y,scale=150:-2,tile=24x1" -frames:v 1 row_ios.png
# same for the implementation, then vstack the two rows
```

**Answers:** ordering, direction, gross timing, "what does it look like".
**Cannot answer:** anything at low alpha (ghost content below ~10% opacity vanishes in a
150 px-wide tile), sub-frame timing, or *which property* caused a difference.

### 3.2 Automated onset detection

Manual alignment fails (VFR + human error), and one mis-anchored grid produced a false finding
("our carry starts late") that measurement later disproved. Onsets are detected per event, per
video, from the frame-difference energy inside the number's bounding crop:

```bash
ffmpeg -i video -vf "fps=60,trim=A:B,setpts=PTS-STARTPTS,crop=...,\
tblend=all_mode=difference,signalstats,metadata=print:file=out.txt" -f null -
# onset = first frame with YAVG(diff) above a threshold, ignoring the first frames
```

**Rule learned the hard way:** never align one side with a measured onset and the other with a
predicted/stale one; measure **both** sides with the same probe.

### 3.3 Scalar ink timelines

For a fixed per-glyph window (ROI), one number per frame:

```
ink(t) = background_luma − YAVG(ROI, t)
```

Emitted by `signalstats` + `awk`, printed as a table (frame index × one column per glyph).

**Answers:** per-glyph onset order, cascade spacing, fade slopes, settle times — this is what
established that exits cascade contiguously (~2.25 frames apart) while rolls cascade slower
(~4.5–5 frames), and that the incoming glyph must start while old ink is still present.
**Blind spots (formalised after use):** a drop in ink cannot distinguish
*became transparent* / *moved out of the ROI* / *shrank* / *blurred past the ROI's edges*.
It also sums every contributor inside the window (neighbour spill, both old and new glyphs).

Two mitigations used throughout:

- **Spy windows**: an ROI placed where nothing sits at rest (e.g. above-right of a glyph's
  final cell). If it lights up during a transition, content genuinely travelled through there.
- **Interpretation discipline**: a window's curve is a *mixture*; conclusions must survive a
  second window with a different mixture.

### 3.4 Moment analysis (the v2 upgrade)

For each ROI, per frame, over a darkness map calibrated against a per-pixel background:

```
D(x,y,t) = max(0, B(x,y) − I(x,y,t))     B = per-pixel median of pre-transition frames

M(t)  = Σ D                     mass        (how much content)
Cx(t) = Σ x·D / M               centroid    (where it is → trajectory)
Cy(t) = Σ y·D / M
σx(t) = sqrt(Σ (x−Cx)²·D / M)   dispersion  (how spread → blur, and its anisotropy)
σy(t) = sqrt(Σ (y−Cy)²·D / M)
```

(Implemented as a ~30-line Python/numpy script over grayscale frame dumps.)

This decomposes the scalar ink into *quantity*, *position* and *spread*, and it is what
produced the highest-value findings:

- the born glyph's **Cx is constant to ±0.5 px** → its motion is purely vertical (falsifying
  our horizontal spawn displacement, which perception had suggested);
- the arrival **overshoots** its final Cy by ~10% and settles back over ~9 frames;
- the roll's settle tail (~0.45 s of continuing centroid motion) and its **mass overshoot
  (~1.05)** → the reference spring is much softer than assumed.

Note when B contains the pre-transition glyph, D measures *new* content (arrivals and
displaced ink), which is often exactly what is wanted; with a glyph-free B it measures raw ink.
Choose deliberately.

### 3.5 Pixel zooms for blur character

Full-resolution crops of one glyph at matched phases (+30/+70/+110 ms into a roll), upscaled.
This is qualitative but decisive for *texture*: it showed the reference's blur is a **light,
round, out-of-focus blob** while ours was a **dark vertical streak** — three separate causes
(anisotropy ratio, peak radius, and opacity NOT being coupled to blur). A Gaussian blur
preserves total ink, so a large glyph keeps a dark core no matter the radius; the reference's
lightness proves it also drops opacity as blur rises.

### 3.6 What was deliberately not used

Web re-implementations of `numericText` (e.g. LCP/three-section diffing approaches) were
inspected and **rejected as evidence**: they are approximations of the same target, not the
target. Only Apple's rendered output counts.

## 4. Iteration protocol

```
implement → unit-test (pure logic) → rebuild on device → record the scripted sequence
→ align per event (measured onsets, both sides) → grids for perception, timelines/moments
  for attribution → falsify or confirm → adjust ONLY what a measurement indicts → repeat
```

Rules that kept the loop honest:

- **Perception picks the battle, measurement picks the fix.** Every user-perceived defect
  ("too rigid", "the old value lingers", "feels like a black smear") was translated into a
  measurable signature before touching a knob.
- **One causal change per hypothesis.** When a change worsened feel (e.g. stiffening the
  spring to fix "rigid" — the actual cause was elsewhere), it was rolled back, not layered over.
- **Keep falsified ideas in the log.** §6 lists them; half of this document's value is there.
- Visual verification happens **on device** by a human; the numbers only say *what* differs,
  never whether it feels right.

## 5. The reverse-engineered model (findings)

The behavioural spec that emerged, each item tied to its evidence:

### 5.1 Direction is global per transition

One vertical direction for the whole transition, decided by whether the *number* increased or
decreased (`countsDown`) — never per digit. **Proof:** in `0→−1` the units digit goes 0→1
(digit increases) yet rolls in the *decrement* direction; in `99→100` every glyph, including
the ones whose digit value decreases, arrives from the top. Increment: content arrives from
above and leaves downward; decrement: mirrored.

### 5.2 Two glyph behaviours, chosen by numeric structure

- **Same logical slot, stable structure** (2,576→2,577; 2,599→2,600): an **overlapped roll** —
  old and new glyph coexist, crossfading while translating on one soft spring. The glyphs never
  separate into two distinct events.
- **Structural change** (the integer digit count changes: 10→9, 999→1,000, 9,999→1): affected
  columns split into **independent exit and enter lifecycles** with their own clocks. The
  classifier must use *numeric structure*, never pixel width (fonts and locales change width
  without changing structure).
- Births/deaths of separators and leading digits are always lifecycles.

### 5.3 Layout: two centred compositions, exits frozen

Old glyphs live in the old centred layout, new glyphs in the new one. Surviving glyphs glide
between their two positions (anchor reflow: **early-start, slow ease, ~220 ms** — not a delayed
start). **Dying glyphs fade essentially in place** (frozen at their old position with only a
slight outward drift): letting them ride the layout contraction makes every corpse slide to the
centre and pile into an unreadable jumble on big shrinks.

### 5.4 The cascade

Left→right, but with three different clocks:

| Phase kind | Spacing (measured) |
|---|---|
| exits | contiguous, ~2.25 frames (~37 ms) apart, own ordinal |
| rolls | ~4.5–5 frames (~75–83 ms) apart |
| enters | positional index on a compressed stagger (≈0.55×) + small lag |

plus a **handoff rule**: an exit that sits after an arrival in the cascade waits ~half a step
extra (in 10→9 the "0" stays firm until the "9" is perceptible, then leaves). The arrival must
begin **while the outgoing ink is still present** — the reference never shows an empty gap at
the centre of a transition (the "ONE-window" curve dips only ~15%, not to zero).

### 5.5 Enters and exits, per property

- Enter: spawns ~0.5 line-height away along the roll axis (purely vertical approach), heavily
  blurred and slightly small, resolves by coming into focus; ~10% positional overshoot with a
  ~9-frame settle-back; long soft alpha resolve (~0.3 s), with a brief initial dead zone.
- Exit: softness leads (blur near-full within the first quarter), alpha drops fast then tails
  out; shrinks toward ~0.74 scale; drifts slightly outward and along the roll axis.

### 5.6 Roll physics and blur

- Spring: soft — settle tail ~0.45 s, gentle bounce; a stiff spring makes each digit a crisp
  discrete event that reads as mechanical.
- Crossfade: generous old/new overlap; total opacity dips slightly at the crossing (two
  overlapping blurred layers otherwise read darker than one solid glyph).
- Blur: near-isotropic (light bolla, not a streak), radius ~0.16 of line-height at peak, with
  an asymmetric envelope (peaks early, decays slowly), and **opacity coupled to blur**
  (α × (1 − k·blur), k≈0.22 — 0.35 proved too washed-out at the crossing).

## 6. Assumptions falsified by measurement

Kept on purpose — this is the part that saves time next time.

| Assumption (plausible, wrong) | What the data showed |
|---|---|
| The cascade runs right→left from the units | Left→right; the leftmost changed glyph leads |
| Increment rolls upward (new from below) | New enters from the top on increments |
| "Rigid" feel ⇒ spring too slow ⇒ stiffen it | The defect was elsewhere (timing/curves); stiffening made it worse |
| Anchor slides late (delay, then move) | Slides from the very first frames, slowly (ease, not delay) |
| The born "5" arrives diagonally from top-right | Cx constant: purely vertical descent; "top-right" was its *position*, not its motion |
| Dying glyphs follow the contracting layout | They fade in place; following the reflow causes a centre pile-up |
| Enters wait their positional turn in the cascade | They start early (compressed stagger); waiting creates an empty hole the reference never shows |
| One shared spring can drive all properties per slot | Onset/termination differ per property; per-property curves are required |
| A stronger Gaussian blur alone reproduces the soft look | Blur preserves ink; the lightness comes from blur-coupled opacity |
| The roll's long settle tail is a second, softer positional mode | Built it; no effect on a roll — `rollOffsetShape`'s `\|x\|^1.43` crushes a small residual to 0.008 line-heights |
| …then it must be the roll's departure fading too soon | Halved its fade rate; the residual and the duration both stayed put |

## 7. Reproducibility notes

- Normalise VFR first: `fps=60` **before** `trim` in every filter chain.
- Never trust `-ss` before `-i` for analysis cuts.
- Detect onsets per event, per video, same probe both sides.
- Exclude UI labels/diagnostics from detection crops (they change with the value and fire
  false onsets).
- Normalise ink per ROI (background and settled-glyph references differ between videos,
  fonts, and glyphs — a settled "1" holds far less ink than a "9").
- Cross-font caveat: absolute dip depths and masses are not comparable across fonts
  (SF Rounded vs Roboto); compare *shapes, onsets and durations*, or normalised occupancy.
- The full knob set lives at the top of `NumericTextView.kt`; each value traces back to a
  measurement in `.agent/PARITY_ROADMAP.md` (iteration log).

## 8. Future work (next rungs of the ladder)

1. **Occupancy normalisation** everywhere: `occ = (B_roi − YAVG) / (B_roi − F_roi)` with
   per-ROI settled references; robust onsets (>2% change sustained ≥2 frames).
2. **3×3 sub-ROI grids per slot** — automates the spy-window trick; the onset order of the
   nine cells yields the entry vector (top-before-bottom = from above, etc.).
3. **Template fitting**: model each frame as
   `a_old·blur(T_old, σ_old, x_old, y_old) + a_new·blur(T_new, σ_new, x_new, y_new)` and grid-search
   the parameters — fully separates the old/new contributions that single-window curves mix.
4. **Loss-based tuning**: define `L = Σ wᵢ·errorᵢ` over onset/occupancy/centroid/dispersion/settle
   against the reference curves, and let the knob set be optimised instead of hand-tuned.

---

*Companion files: `.agent/PARITY_ROADMAP.md` (iteration-by-iteration log with the measured
numbers), `.agent/NUMERIC_TEXT_ALGORITHM.md` (algorithm spec), `example/src/sequence.ts`
(the deterministic test sequence).*
