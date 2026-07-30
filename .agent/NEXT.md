# START HERE — 2026-07-30

Read this before touching the Android renderer. It is the state of play, the one experiment that is
queued, and the traps that already cost a day.

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
