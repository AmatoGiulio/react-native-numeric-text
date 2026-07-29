# Handoff — where the parity work stands

Written at `11f6c74` on `feat/per-slot-springs-ios-parity`. Read this, then
`docs/METHODOLOGY.md` (how to measure) and `git log` (every change carries its measurement in the
message — that is the real record, this file is only the index).

## What this session did

Seventeen commits, `e8fb79a`..`11f6c74`, all Android. Each fixes one measured defect:

| commit | defect it fixes |
|---|---|
| `e8fb79a` | a structural death faded in place instead of rolling out |
| `88bd7ec` | an arrival landed with no settle bounce |
| `b78cf93` | the arrival's opacity resolved ~50 ms early |
| `592d6a7` | the exit cascade was keyed to a dense ordinal, and gated all-or-nothing on "at rest" |
| `1b1ec3f` | a revived glyph slid in sideways when two presets were chained |
| `5a94030` | dying glyphs blurred as a group instead of leaving one at a time |
| `ec7cf79` | (docs) two falsified attempts at the roll's settle tail |
| `93ffe84` | the roll's crossfade and settle, measured per glyph — adds `template_fit.py` |
| `bdf735b`, `68f5654` | the above, backed off by eye |
| `a0f7782` | deaths half-lived 83 ms against the reference's 50 |
| `2d083a8` | the last digits of a growth arrived unblurred; the settle ring was 2× too wide on births |
| `1b0a468`, `44cb58c`, `7f6f37d`, `d4809cb`, `11f6c74` | the continuous roll: centring, pace, crispness, density, depth |

## The knobs, and what each is answerable to

All in `NumericTextView.kt`. Anything with a number in it was fitted against a measurement — the
commit that set it says which.

| knob | value | governs |
|---|---|---|
| `springStiffness` / `springDampingRatio` | 340 / 0.9 | the base rate of everything |
| `arriveDampingRatio` | 0.32 | the settle bounce of a roll's arrival (set by eye, see `68f5654`) |
| `birthDampingRatio` | 0.44 | the same for a structural birth, which travels 3.2× further |
| `staggerSeconds` / `cascadeSpamMs` | 0.04 / 90 ms | the left→right cascade, and when it switches off |
| `rollExitFadeRate` / `rollExitFadeFast` | 1.3 / 2.9 | how fast a roll's departure fades, isolated vs crowded |
| `arrivePresenceFast` / `arriveOffsetFast` | 1.5 / 2.6 | how fast an arrival resolves when crowded — **kept apart on purpose**: presence carries opacity, depth scale and blur; the offset carries only position |
| `rollExitOff` / `exitTravelOfBirth` | 1.0 / 0.8 | how far a departure travels, roll vs structural death |
| `deathRate` | 1.4 | a structural death runs this much faster, both springs |
| `reviveMaxDriftFactor` | 0.12 | how far out of place a fading glyph may be and still be reused |

Four of these switch on `changeSpacing` — how long since the last change, 1 when isolated and 0
during a spam. An isolated transition is untouched by all of them by construction.

## Open, in order

1. **The iOS side of the library is a placeholder.** `ios/NumericTextView.mm` stores props and draws
   an empty `UIView`. Everything above is Android; the example's "Reference (SwiftUI)" is Expo UI,
   not this library. So the library is 1:1 with SwiftUI *on Android* and renders nothing on iOS.
   This is the largest remaining gap and is a different kind of work — calling the native modifier,
   not reproducing it.
2. **A fast roll is still slightly under the reference's density** — full-darkness frames 5.3 %
   against 6.8 %.
3. **Deaths overlap a little more than the reference's** — peak/mass 1.2–1.3 against 1.6.
4. Nothing is merged to `main`.

## Reproducing a measurement

```bash
python3 .agent/tools/template_fit.py --video docs/tune/ref.mov --platform ios --onset 606
```

`docs/tune/` is gitignored, so the recordings themselves are local only. To make new ones: drive
both platforms with the same example app, tap the same preset, and align on a measured onset per
event — never a predicted one. `docs/METHODOLOGY.md` §3–4 is the protocol; §6 is the list of
assumptions that measurement has already falsified, which is the part worth reading first.
