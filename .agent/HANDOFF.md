# HANDOFF — read this before touching anything

You are continuing work on an Android reimplementation of SwiftUI's `.numericText()`. **iOS hosts
the real SwiftUI view, so iOS is the reference and Android is the thing being fitted.** Every claim
in this repo either carries a number or says it is a guess. Do not add claims without numbers.

## YOUR ONE TASK

**Port the LANES to Kotlin, behind an A/B flag. Nothing else.**

Then measure on the device whether they (1) stabilise the midpoint, (2) hold the lobe separation,
(3) leave the single crossing and the continuous roll alone.

**Do not commit the behaviour as final before that device comparison.** If the device disagrees with
the simulator, the device wins and you report it — that is the whole point of the exercise.

### What the lanes are

During an alternation each glyph is held in one of two bands instead of travelling its own path.
Half-separation **0.369 glyph heights**, taken from the reference's own lobes at −0.390 and +0.348.
The mapping is a `tanh`, not a sign, so a glyph crossing rest passes through smoothly and **a commit
never recreates a position** — the pair already in flight carries on.

The reference implementation is in `.agent/tools/sim.py`, knob `FLIP_LANE` in `flip_knobs()`. Read
it and port that, including the soft gate (`flipGate`) that keeps it inert outside an alternation.

### Measured in the simulator (this is what you are trying to reproduce on device)

| 60 ms alternation | reference | before lanes | with lanes |
|---|---|---|---|
| midpoint wobble | 0.049 | 0.156 | **0.065** |
| lobe separation | 0.738 | 0.696 | **0.746** |
| central ink | 0.097 | 0.132 | **0.095** |
| band | 0.760 | 1.056 | **0.790** |
| total-ink pulsation | 0.057 | 0.158 | 0.158 (unchanged) |
| centroid excursion | 0.103 | 0.313 | **0.416 — WORSE** |

The lanes are the best candidate **on the geometry**, not on every metric. The centroid gets worse.
Say so in whatever you report; do not quietly drop that column.

## FIVE TRAPS THAT HAVE ALREADY COST DAYS

**1. The baseline already contains four levers. Do not remove them.**
`STACK_FLIP_BORN 1.5`, `SOFT 2.0`, `LIFT 1.5`, `STRETCH 1.35`, plus the rest-test fix, are committed
in `NumericTextTimeline.kt`. Every number above was measured WITH them active, because `sim.py`
parses them straight out of the Kotlin source. Port the lanes ON TOP. **The A/B flag toggles the
lanes only.** A tree with those reverted renders something nobody has ever measured.

**2. Read the manifest, never your memory.**
`canon.py` writes a `manifest.json` beside every render recording the source hashes, the engine
constants, the environment, and the atlas. It exists because a `.bin` carries nothing about what
produced it. This trap has been walked into three times — twice by comparing different experiments
in silence, once by writing a summary from memory when the manifest said otherwise. **When you state
what a render contained, read it out of the manifest.**

**3. The engine selector used to lie, and might again.**
"No marker file" once meant the drum, so the app could never start on the stack whatever its own
selector said — a whole round reproduced the DRUM's table under a name meaning the stack. Both
engines now get an explicit marker, `round.sh` clears the other, and a `debugEngine` prop pins the
choice. **Every round writes an `engine.txt`. Read it before believing any device number.**

**4. A full disk silently truncates a render.**
`sim.py` writes the `.bin` before the `.json`, so a full disk leaves a mutilated file that `cmp`
just calls "different". `canon.py` now checks the length against `frames x width x height` and fails
loudly. **Do not wrap renders in `>/dev/null 2>&1` and then trust the output.** Check free space
before a long batch.

**5. Emulator capture has its own gotchas.** See the "Android emulator capture" notes — a recording
that fills the disk leaves a `.bin` with no `.json`, and every glob here keys on the `.json`, so it
is skipped in silence.

## THE PIPELINE — run it exactly like this

Everything lives in `.agent/tools/`. Never render by calling `sim.py` directly; go through
`canon.py` so you get a manifest.

```bash
# render (writes manifest.json, verifies the .bin length, fails loudly)
python3 .agent/tools/canon.py render <outdir> --preset=alt60 --env FLIP_LANE=0.369

# are two renders even the same experiment? refuses to answer if not
python3 .agent/tools/canon.py verify <dirA> <dirB>

# first diverging frame
python3 .agent/tools/canon.py why <dirA> <dirB>
```

Presets: `single` (one crossing), `roll` (continuous), `alt60` / `alt120` / `alt240` (alternation).

```bash
# every metric in one shot: pulsation, mean ink, frame jumps, midpoint, separation, compensation
python3 .agent/tools/metrics.py "iOS=artifacts/gt_ios_alt3/run-1785749998789" "cand=<dir>"

# the two lobes traced over time: position, ink, midpoint, behaviour across commits
python3 .agent/tools/lobetrace.py out.png "iOS=<ios run>" "cand=<dir>"

# the ink exchange: does the total hold still, do the lobes compensate
python3 .agent/tools/pair.py out.png "iOS=<ios run>" "cand=<dir>"

# total brightness over time
python3 .agent/tools/bright.py out.png "iOS=<ios run>" "cand=<dir>"

# geometry against the reference
python3 .agent/tools/balance.py <ios ref> <dir> <ios bursts>   # travel, imbalance, band
python3 .agent/tools/band.py <dir>                              # separation under cadence
python3 .agent/tools/compare.py <dir>                           # single crossing headline
python3 .agent/tools/burst.py <dir>                             # roll sharpness, tail, floor
```

`metrics.py` and friends want a directory holding exactly one run's `.json` + `.bin`. `canon.py`
writes a `manifest.json` in there too, and the older tools choke on it — symlink the two run files
into a clean sibling directory before calling them.

### The non-regression check, every single time

The lanes MUST leave the single crossing and the roll bit-identical. Prove it, do not assert it:

```bash
python3 .agent/tools/canon.py render <dir>/single --preset=single --env FLIP_LANE=0.369
python3 .agent/tools/canon.py render <dir>/roll   --preset=roll   --env FLIP_LANE=0.369
# then compare sha_bin in the manifests against a baseline rendered without FLIP_LANE
```

Known-good hashes for the current tree, no lanes: single `43373d3d17bf0d1c`, roll `edb469dbbf58f70a`.
Lanes on `alt60`: `1a1104551c1afb5f`. **If you re-render those three and get different hashes, stop —
something in the tree changed and every number below is void.**

### Show the grids

Every round ships titled images, never a table on its own. `gif.py` stacks the reference above the
candidate:

```bash
python3 .agent/tools/gif.py <dir> out.gif --slow=6 --col 4 --from=0 --to=800 \
  --ios=artifacts/gt_ios_alt3/run-1785749998789 --ios-mark=1 --mark=1 \
  --title="what this shows" --verdict=open
```

## DO NOT PORT THESE

All of them are in `sim.py`, all default to off, all were tried and rejected today. The reason
matters more than the verdict — read `NEXT.md` before re-litigating any of them:

- `FLIP_PAIR` — ink pairing. Stabilised the total against a reference fed by its own output, so it
  drifted the column from 0.354 to 0.610.
- `FLIP_HP` — brightness filter. The tested ratio-based corrections (`reference / current`) raise the
  mean, because that ratio's mean exceeds 1 when the denominator oscillates.
- `FLIP_FADE_SLOW` — fade slowdown. Correctly implemented, near-useless (0.158 → 0.160): a departing
  glyph is also SHRINKING and ink goes as the square of size.
- `FLIP_HOLD` / `FLIP_HOLD_OUT` — gain hold and its mirror. The first targets the wrong side of the
  imbalance; the second divides by residual ink and explodes.
- `FLIP_SMOOTH` — cut the flicker by a quarter but moved the trajectory, which it must not touch.
- `FLIP_MOMENTUM` and everything else in `flip_knobs()` — zero, and they stay zero.

## THE OPEN PROBLEM (not your task, do not start on it)

The centroid still moves ~3x the reference and the total ink pulses ~3x. Traced per lobe on the
pixels of BOTH platforms: the mean imbalance between lobes is nearly the reference's (0.363 vs
0.324) and the exchange correlation is close (−0.645 vs −0.784), so it is not that one lobe
outweighs the other. The measured mismatch is in the TIMING of the exchange — the departing glyph
loses 0.056 of ink per frame while the arriving one gains 0.036, and the loss exceeds the gain in 27
frames out of 34. **Its underlying cause is not yet isolated.** An opacity-only slowdown is ruled
out; size and opacity remain coupled.

## HOUSE RULES

- Report what you measured, including the columns that got worse. A result that contradicts the
  hypothesis is the valuable one.
- One rule changed per experiment. Attribute before you tune — `SIM_CONST_<NAME>=<value>` forces a
  single engine constant so you can ask which one is responsible.
- Never claim "identical" without the hashes; never claim "improved" without the before number.
- Do not commit as final before the device comparison. Do not push unless asked.
