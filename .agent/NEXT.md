# START HERE — 2026-07-30

Read this before touching the Android renderer. It is the state of play, the one experiment that is
queued, and the traps that already cost a day.

## Where things stand

Branch `feat/per-slot-springs-ios-parity`, HEAD `b669556`, working tree clean.

The APK on the emulator **matches this commit**. If you rebuild anything, reinstall — a build on the
device that no longer matches the source is how a whole afternoon got confusing:

```bash
cd example/android && ANDROID_HOME=~/Library/Android/sdk ./gradlew assembleDebug -PreactNativeArchitectures=arm64-v8a
~/Library/Android/sdk/platform-tools/adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
```

What landed today that changes behaviour: the iOS SwiftUI renderer, blur gated by displacement, the
roll's travel pulled back inside the reference's box, a per-role scale curve, and the parked-glyph
revival fix. Everything else is tooling, docs, or a revert.

## The one experiment that is queued

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

The iOS reference capture for this preset is `q_ios60.mp4`, onset **929** (scratchpad; re-record with
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
