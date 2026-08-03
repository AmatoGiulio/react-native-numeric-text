# Working in this repository

A React Native library that reimplements SwiftUI's `.numericText()` content transition. iOS hosts
the genuine SwiftUI view; Android reimplements it and is measured against iOS frame by frame.

**Read `NEXT.md` first, then `IOS_GROUND_TRUTH.md`.** They are the whole state of the work.

## The rules that were learned the hard way

**Measure, then claim.** Every number in the documents traces to a recording in `artifacts/`. If
you assert something without one, say so in the same sentence.

**Produce a grid for anything visual, and SHOW IT.** `.agent/tools/grid.py` for a frame,
`.agent/tools/gif.py` for the motion. Every defect found in this project came from looking at frames
side by side, not from the headline metric — the metric ranks candidates, it does not find defects.
That cuts both ways: a round reported as a table of error terms is not evidence a reader can check.
Attach the sheets every round, not a description of them.

**Title every sheet, and say whether it was kept.** `--title` is required on both tools and
`--verdict=kept|rejected|open` marks which of a round's sheets is the engine as it stands. They look
alike; an untitled pair once got a discarded experiment read as the current state, which makes a
fixed defect look live.

**One knob at a time, and keep the control.** The two unchanging columns must read 1.000. A
constant that moves them is wrong however good it makes the rest look.

**Replicate before chasing.** The recorder repeats to 0.001 on amplitudes and 13 ms on timings, so
a single run is enough to see an effect and not enough to locate it. Two burst defects were
reported from one run each and both turned out to be in the other direction.

**Record what failed, with its numbers.** The closed brackets at the end of the ground truth are
worth as much as the constants; they are what stops a dead end being walked twice.

## Layout

```
android/src/main/java/com/numerictext/
  NumericTextTimeline.kt    the engine — every constant is here and says where it came from
  NumericTextView.kt        the renderer, hardware and software paths
  NumericTextFrameRecorder  ground-truth capture, DEBUG only, off unless armed
ios/NumericTextSwiftUIHost.swift   the real SwiftUI view, plus the same recorder
example/src/Showcase.tsx    the scripted presets both platforms are driven through
.agent/tools/               ground_truth.py, compare.py, band.py, grid.py, gif.py, round.sh, fit.sh
```

The example's presets are the test harness, not decoration. Anything with a cadence goes through
`scheduleOnFrames` so both platforms receive the same script — the JS timer queue coalesces
differently per platform and once had the same preset firing every 31 ms on iOS and every 113 ms on
Android.

## Commits

Conventional commits, enforced by commitlint. Subject in lower case. Say what was measured and what
it cost; a commit that changes a constant without a number in its message is not finished.
