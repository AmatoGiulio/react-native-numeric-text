# START HERE

This library reimplements SwiftUI's `.numericText()` content transition on Android, and measures
itself against the real thing frame by frame. On iOS it hosts the genuine SwiftUI view, so **iOS is
the reference**; the Android renderer is the thing being fitted.

Read this, then `IOS_GROUND_TRUTH.md`. Everything in both either states a measurement or says it is
a guess. A claim without a number behind it should be distrusted.

> **2026-08-04 — read `TRANSITION_MODEL.md` before acting on the "Next, in order" section below.**
> The reference's frames now reconstruct per glyph, and three things that section rests on turned
> out to be wrong: the transition ignores the transaction's animation entirely (so nothing has to be
> deconvolved), a column holds exactly two crossfading glyphs with a fixed ±0.59375 entry amplitude
> whatever the digit distance (so it is neither a strip nor a drum, and no intermediate digit is
> ever drawn), and under a fast cadence it stalls into a half-formed pair rather than stacking.
> Item 4 below — driving the reference under an explicit `.linear` — has been run, and it is what
> established the first of those.
>
> **The measured model is now PORTED and measured on device** (`stackMode`, round `stackfit`):
> the single crossing is 0.056 down / 0.047 up with the wave inside 1 ms and the close inside 2 ms,
> and **the continuous roll is essentially exact** — sharpness 0.603 against 0.600, tail 611 ms
> against 615, final floor 0.426 against 0.409. Two constants had unit bugs and both are fixed:
> Apple's `scale` 0.3984 is the FINAL scale and was being applied as the shrink amount, and
> `relativeOffset` 0.59375 is in CAP heights and was being applied to the line height — which is the
> whole "0.59375 does not transfer, 0.44 fits" story, a unit mismatch and not a wrong constant.
>
> **RETRACTED 2026-08-04 evening — "the stack loses the alternation" was never measured.**
> This paragraph used to read: the simulator predicted travel 0.190, the device gave 0.549–0.853 and
> a 60 ms band of 1.489, so the stack buys the roll and loses the alternation. Two separate faults
> produced those numbers and **both sides of the comparison were wrong**.
>
> 1. **`round.sh` never switched the engine on.** `stackMode` is enabled by a marker file on the
>    device, `numerictext-stack.on`, and nothing in the script touched it — so which engine a round
>    measured depended on whether an earlier session had left the flag behind, and no number in any
>    output said which. A fresh round of this exact source, driven before this was found, reproduced
>    the DRUM's table to three decimals under a name meant for the stack. `ENGINE=stack|drum` now
>    sets it explicitly and every group carries an `engine.txt` with the engine, the commit and
>    whether the tree was dirty.
> 2. **The simulator was not running this model.** Four divergences, all in `sim.py`: opacity
>    derived from the position clock and therefore NOT monotone (a discarded glyph relit to full
>    black crossing rest), no notion of direction at all (so an alternation drew both ways the
>    same), blur fed in as a sigma where the device feeds it as a length — **3.5x** — and the final
>    scale at 0.60 where the engine has 0.3984. `--model=kotlin` now ports the engine line for line
>    and parses its constants out of the source.
>
> Re-measured with both fixed, `artifacts/align3` (stack) and `artifacts/align1` (drum), same source,
> same session, plus the aligned simulator:
>
> | | drum, device | stack, device | stack, simulator | reference |
> |---|---|---|---|---|
> | headline down / up | **0.010** / 0.010 | 0.022 / 0.023 | 0.036 / 0.034 | — |
> | band 60 / 120 / 240 ms | 0.846 / 1.333 / 1.305 | 0.957 / 1.544 / 1.308 | 1.034 / 1.534 / 1.328 | 0.760 / 1.460 / 1.292 |
> | roll sharpness / tail | 0.603 / 635 ms | 0.603 / 552 ms | 0.600 / 593 ms | 0.600 / 615 ms |
> | travel single | 0.165 | **0.156** | 0.139 | 0.163 |
> | travel alt 60 / 120 / 240 | 0.285 / 0.259 / 0.281 | 0.440 / 0.251 / 0.177 | 0.407 / — / — | 0.103 / 0.139 / 0.161 |
> | travel roll | 0.511 | **0.184** | 0.229 | 0.119 |
>
> So the trade is real but it is not the one recorded: the stack **fixes the roll** — travel 0.511 →
> 0.184, which nothing else on this branch has managed — costs 0.012 on the single crossing, and is
> within scatter of the drum on the 60 ms band rather than twice it. The alternation's travel is the
> one place it is clearly worse, and only at 60 ms.
>
> **And the simulator can now be believed.** Aligned, it lands the device's single crossing column by
> column (ink floor within 0.014, extent within 0.02, onsets within 2 ms) and its 120 and 240 ms
> bands to 0.01–0.02. A candidate costs a second again, and this time the second is worth something.

---

## CHECKPOINT 2026-08-05 — the best simulated GEOMETRY candidate, and the ONE thing to port next

**Best simulated GEOMETRY candidate** — best on the geometry, NOT on every metric: the centroid's
excursion goes 0.313 -> 0.416, worse:
`stack engine + rest-test fix + STACK_FLIP_BORN 1.5 + SOFT 2.0 + LIFT 1.5 + STRETCH 1.35 + LANES`.

> **Corrected within the session — the first version of this line read "+ LANES. Nothing else."**
> That was wrong. `sim.py` PARSES the four `STACK_FLIP_*` constants out of the Kotlin source and
> applies them on every render, so every number in this checkpoint was measured with them active.
> The manifest of the lanes render says so in one line — `STACK_FLIP_BORN = 1.5, SOFT = 2.0,
> LIFT = 1.5, STRETCH = 1.35` — and `canon.py` exists precisely so this cannot be got wrong. It was
> got wrong anyway, by writing the summary from memory instead of from the manifest. **Read the
> manifest.**

The lanes hold each glyph in one of two bands during an alternation instead of letting it travel its
own path — half-separation 0.369 glyph heights, taken from the reference's own lobes at −0.390 and
+0.348, mapped through a `tanh` so a glyph crossing rest passes smoothly and **a commit never
recreates a position**: the pair in flight carries on. Geometry only.

| 60 ms alternation | reference | before lanes | **with lanes** |
|---|---|---|---|
| midpoint wobble | 0.049 | 0.156 | **0.065** |
| lobe separation | 0.738 | 0.696 | **0.746** |
| central ink | 0.097 | 0.132 | **0.095** |
| band | 0.760 | 1.056 | **0.790** |
| total-ink pulsation | 0.057 | 0.158 | 0.158 (untouched) |
| centroid excursion | 0.103 | 0.313 | 0.416 (worse) |

Single crossing and continuous roll render **bit-identical** to the baseline, verified through
`canon.py` manifests rather than asserted.

### Port ONLY the lanes, behind an A/B flag

The next session ports the lanes to Kotlin and nothing else, to answer three questions on the
device: do they stabilise the midpoint, do they hold the separation, and do they leave the single
crossing and the roll alone. **Do not commit the behaviour as final before that device comparison.**

**And "nothing else" means: add the lanes ON TOP of the four `STACK_FLIP_*` constants that are
already in the tree — do not remove them.** The measured configuration includes them. Porting the
lanes onto a tree with those reverted would produce something this session never rendered, and the
device numbers would not be comparable to anything above. The A/B flag toggles the LANES only.

**Explicitly NOT to port** — none of these earned it:

- the ink-pairing (`FLIP_PAIR`): stabilised the total against a reference fed by its own output, so
  it drifted the column from 0.354 to 0.610;
- the brightness filters (`FLIP_HP`): the tested ratio-based corrections, of the form
  `reference / current`, raised the mean — the mean of that ratio exceeds 1 when the denominator
  oscillates. This rules out THAT implementation family, not every possible multiplicative
  correction;
- the fade slowdown (`FLIP_FADE_SLOW`): correctly implemented and near-useless, 0.158 → 0.160. A
  departing glyph is also SHRINKING, and ink goes as the square of size, so keeping it opaque for
  longer keeps something small on screen and fills almost nothing;
- `FLIP_SMOOTH`: reduced the flicker by a quarter and moved the trajectory, which it was not
  supposed to touch;
- `FLIP_MOMENTUM`, and every other lever in `flip_knobs()` — they default to 0 and stay there.

### What the Kotlin tree already carries, uncommitted

`STACK_FLIP_BORN` 1.5, `STACK_FLIP_SOFT` 2.0, `STACK_FLIP_LIFT` 1.5, `STACK_FLIP_STRETCH` 1.35, plus
the rest-test fix and the engine selector. Measured on device as `artifacts/align6` and `align7`;
the lanes are NOT among them. Whoever ports the lanes should know these are already there and that
`align7`'s numbers include them.

### The defect the lanes do NOT fix, measured

The centroid still moves ~3x the reference, and the total ink pulses ~3x. Traced per lobe on the
pixels of both platforms: the mean imbalance between the two lobes is nearly the reference's (0.363
against 0.324) and the exchange correlation is close (−0.645 against −0.784), so it is not that one
lobe outweighs the other. The measured mismatch is in the TIMING of the ink exchange, and its underlying
cause is not yet isolated: an opacity slowdown alone was ruled out, while size and opacity remain
coupled. Measured by glyph identity across frames, the departing glyph loses 0.056 of ink per frame
while the arriving one gains 0.036, and the loss exceeds the gain in 27 frames out of 34. The column briefly runs short of ink, and that is the flicker. Slowing the
fade does not fill it, because the departing glyph is shrinking at the same time.

## Where it stands

Rebuilt from HEAD and re-measured on 2026-08-03 evening (`artifacts/verify1*`), because the engine
file had been edited after the last capture and no round had been driven since. Everything below is
that round, not a carried-over number.

Single value change:

| | decrement 1,242→1,160 | increment 1,160→1,242 |
|---|---|---|
| ink floor error | 0.005 | 0.009 |
| crossing extent error | 0.015 | 0.011 |
| **headline** | **0.010** | **0.010** |
| wave start | ±2 ms | ±1 ms |
| the two unchanging columns | 1.000 | 1.000 |

The continuous roll, 14 changes 30 ms apart: sharpness 0.603 against 0.600, tail 635 ms against 615.

The alternation, **cadence against the same cadence** — which is new, and it is most of the story:

| | band iOS / and | travel iOS / and | balance iOS / and | width iOS / and |
|---|---|---|---|---|
| 60 ms | 0.760 / 0.814 | 0.103 / **0.259** | 0.058 / 0.061 | 0.779 / 0.786 |
| 120 ms | 1.460 / 1.335 | 0.139 / **0.261** | 0.115 / 0.122 | 0.786 / 0.823 |
| 240 ms | 1.292 / 1.275 | 0.161 / **0.279** | 0.118 / 0.116 | 0.814 / 0.815 |
| roll | — | 0.119 / **0.517** | 0.052 / 0.118 | 0.969 / 0.856 |

**The balance and the width are at parity at every cadence, and only the travel is not.** They read
as the largest defect left until `balance.py` was fixed: it took the iOS side from one file and the
Android side from a directory holding two runs at 60 ms and two at 120, so every Android
"alternation" figure it printed was the mean of two cadences. Separated, the balance at 60 ms is
0.061 against 0.058 and the width 0.786 against 0.779. The claim this file used to carry —
"the width is the real gap, 1.18x too wide, and it is what a viewer actually reports seeing" — was
that artefact plus a stale number, and it is retracted.

So there is **one** defect left, and it is the travel: this engine's ink centroid moves 1.7x to 4.3x
further than the reference's under a crowd, while matching it exactly (0.165 against 0.163) on a
single change. The reference moves its ink LESS through a crowd than through one change; this engine
moves it monotonically more, the more the changes pile up. That is a shape no constant reaches — see
the last section.

## The 60 ms alternation — six levers swept, and what the trade turned out to be

Simulator only, `--model=kotlin`, nothing ported. Every lever is gated on ONE signal, `flipRaw`,
which rises only when the value REVERSES onto a column still in flight — so it is identically zero
for a single change and identically zero through a roll, where the direction never reverses.
**Verified rather than asserted**: with all six levers at 0.9 the single crossing and the roll come
out bit-identical to the ungated render (`cmp` on the raw frame planes). The single crossing and the
roll cannot be damaged by anything in this section, by construction.

`.agent/tools/alttrace.py` prints the engine's own per-glyph state — position, velocity, alpha,
blur, which way it was born, time since the last reversal — and it is what made the mechanism
visible. At 60 ms, steady state:

```
t=216ms   0 +1.093 α0.071 | 1 -0.909 α0.107 | 0 +0.550 α0.269 | 1 -0.536 α0.410 | 0 +0.912 α0.143
```

Seven glyphs alive, the two bright ones at ±0.55 with unequal alpha, and they swap every 60 ms.
**Nothing is ever near rest**, and the centroid swings with whichever of the pair is brighter.

| lever | what it does on a reversal | travel 0.407 → | band 1.034 → |
|---|---|---|---|
| `FLIP_TRAVEL` | birth the newcomer closer in | **0.216** at 1.0 | 1.483 |
| `FLIP_SHORTEN` | compress the drawn offset | 0.358 at 0.75 | — |
| `FLIP_MIRROR` | birth it opposite the glyph it supersedes | 0.305 at 1.0 | 1.304 |
| `FLIP_DAMP` | kill the live glyphs' velocity | 0.388 at 0.9 | worse imbalance |
| `FLIP_FADE` | speed the superseded glyphs' fade | 0.393 at 0.75 | worse imbalance |
| `FLIP_DEPART` | shorten the departure too | **0.425**, worse | worse |
| `FLIP_LEVEL` | even the pair's opacity, moving nothing | 0.414, no effect | 1.014 |

Damping, fading and shortening the departure do nothing for the travel, and two of them make the
pair's imbalance worse. Levelling the opacity does not move the centroid at all — which kills the
reading that the swing is photometric.

Best on the stated objective, `FLIP_TRAVEL=1.0` with `FLIP_SHORTEN=0.75`:

| | 60 ms | 120 ms | 240 ms |
|---|---|---|---|
| travel, this vs reference | **0.187** / 0.103 | 0.121 / 0.139 | 0.141 / 0.161 |
| imbalance, this vs reference | **0.056** / 0.058 | 0.122 / 0.115 | 0.119 / 0.118 |
| band, this vs reference | **1.517** / 0.760 | 1.450 / 1.460 | 1.192 / 1.292 |

Travel 0.407 → 0.187 against the reference's 0.103, the pair's imbalance lands the reference at all
three cadences, and 120 / 240 ms improve on the band as well. **One thing regresses and it is the
60 ms band, 1.034 → 1.517 against 0.760** — and that is not a badly chosen constant, it is the
mechanism. The band measures whether the two forms stay SEPARATED (below 1) or merge (above 1).
Every lever that pulls ink towards rest to still the centroid also fills the middle. Travel and band
are coupled through the same quantity.

**The reference is not on that curve at all, and that is the finding.** Its band goes 0.760 at 60 ms,
1.460 at 120, 1.292 at 240 — it separates MORE as the cadence gets faster, while this engine merges
more. `TRANSITION_MODEL.md` §5 already describes it in words: under crowding the reference stalls
into two half-formed glyphs held apart, one above and one below, at half scale and half opacity,
essentially unchanging. It does not converge them to rest and it does not swap them; it PARKS them.
This engine's transitions each keep converging on rest, and no reversal-gated adjustment to where
they start or how fast they get there turns converging into parking.

### The park was tried, and it does exactly one of the two things

`FLIP_PARK` / `FLIP_PARK_AT`: under a reversal in flight, aim the pair at ±`at` — one above, one
below, held — instead of at rest and at the exit, blended every frame off the live signal so the
column still settles normally once the burst ends. `target` untouched, so scale, blur and alpha go
on crossfading underneath, which is what "half scale, half opacity, essentially unchanging" needs.

**It moves the band, and it is the only thing that does**: 1.034 → 0.852 at `at` 0.80, → 0.691 with
the rest-test fix and the opacity levelling, against the reference's 0.760. Every other lever moves
it the wrong way. **It does not move the travel** — 0.407 → 0.453, slightly worse.

The trace says why, and it retracts the reading that the swing is about where glyphs END UP. Under a
park the newest glyph is still born at full amplitude and still travels inward from ±0.95 to ±0.4
*while it is the brightest thing in the column*, alternating sides. The old glyphs the park holds
are already at alpha 0.005–0.17. **The centroid follows the newcomer's journey, not the pair's
resting geometry.** Which is also why `FLIP_LEVEL` does nothing for travel even with the positions
made symmetric — tried, 0.441 against 0.437 without it.

### A real bug, and it is in the engine rather than in a constant

The crowd impulse fires when a change lands on a column "not at rest", tested as
`abs(target - position)`. **In stack mode `position` is never stepped** — `stepEntries` replaces
`stepPosition` — so it holds whatever `snapToTarget` last left while `target` moves with every
commit. Through a roll the target walks away and never returns, so the test is right by accident.
Under an alternation the target oscillates between two stops and lands back ON the stale position
every other commit, so **the impulse fires half as often as it should**: `crowdRaw` averages 0.190
through a 60 ms alternation against 0.614 through a 30 ms roll. `STACK_ARRIVAL_GATE`, whose whole
job is to pair "bright" with "arrived", is therefore barely engaged in the one regime where the
brightest glyph is the one still in transit.

Asking the entries themselves — any live glyph away from its own rest or still carrying velocity —
leaves the single crossing and the roll **bit-identical** (both already answer correctly) and
changes only the alternation. It is a fix, not a knob, and it should go into the Kotlin on its own.

### Where the frontier stands

| | travel | band | imbalance |
|---|---|---|---|
| reference | **0.103** | **0.760** | **0.058** |
| base | 0.407 | 1.034 | 0.131 |
| restfix + park 0.80 + level | 0.425 | **0.691** | 0.097 |
| restfix + park + level + mirror | 0.351 | 0.941 | 0.085 |
| restfix + travel 0.75 | 0.237 | 1.254 | 0.053 |
| travel 1.0 + shorten 0.75 | **0.187** | 1.517 | 0.056 |

The frontier moved — `restfix + park + level + mirror` beats the base on all three at once, which
nothing before it did — and **the reference still dominates every point on it**. The goal of 0.103
is not reached and should not be claimed.

### The rest-test fix is PORTED, and it costs the band — read this before judging it

`NumericTextTimeline.step` now asks the entries in stack mode. It is the right test and the old one
was measuring a scalar that stack mode never updates. But "correct" is not the same as "better", and
here it is not better on every count. Re-measured in the simulator, alternation only:

| | travel | band | imbalance |
|---|---|---|---|
| before the fix | 0.407 | **1.034** | 0.131 |
| after the fix | 0.406 | **1.251** | 0.121 |

Travel does not move at all, the pair's imbalance improves slightly, and **the band gets worse**.

Confirmed on device, `artifacts/align5` against `artifacts/align3` — same source otherwise, same
session, `engine.txt` in both:

| | align3, no fix | align5, fix | reference |
|---|---|---|---|
| headline down / up | 0.022 / 0.023 | **0.022 / 0.023** | — |
| travel single | 0.156 | **0.156** | 0.163 |
| travel roll | 0.184 | **0.185** | 0.119 |
| roll sharpness / tail | 0.603 / 552 ms | 0.603 / 553 ms | 0.600 / 615 ms |
| band 60 ms | 0.957 **±0.156** | 1.136 **±0.010** | 0.760 |
| band 120 / 240 ms | 1.544 / 1.308 | 1.516 / 1.281 | 1.460 / 1.292 |
| travel alt 60 | 0.440 ±0.061 | 0.417 ±0.029 | 0.103 |

The single crossing and the roll are untouched on the device too, which is what the simulator said
bit-identically. The band cost is real and the two agree on its size: +0.22 in the simulator,
+0.18 on the device.

**And there is a benefit the simulator could not show: the 60 ms band stopped being noise.** Its
run-to-run spread goes from ±0.156 to ±0.010, and 120/240 ms tighten to ±0.012/±0.009. This file has
carried a warning for weeks that the 60 ms band's own scatter (0.041–0.172) was larger than the
differences rounds were being decided on, and that "that round carried no information". The cause
was in the engine, not in the rig: the crowd impulse was firing on alternate commits, so which
commits it caught depended on where the run happened to start. It is now a measurement worth
fitting against.
The reason is not subtle: `STACK_ARRIVAL_GATE` and everything around it was fitted WITH the broken
signal, so restoring the signal changes the gate's effective strength and the constants fitted
against it no longer hold. The fix should stay — a signal that fires half the time is not a thing to
build on — but the constants it drives are now unfitted and the 60 ms band is where that shows.

### Driving the arrival gate off the reversal signal — tried, and it FAILS

The obvious follow-up, and the trace's own suggestion: if the brightest glyph is one still in
transit, lean harder on the mechanism that exists to prevent exactly that. Measured, with the gate
given extra drive under a reversal and its depth raised towards 1:

| | travel | band |
|---|---|---|
| base (fix in) | 0.406 | 1.251 |
| `FLIP_GATE=1` | **0.438** | 1.297 |
| `FLIP_GATE=1, FLIP_GATEHARD=1` | **0.454** | 1.333 |

Worse, monotonically. The reason is in the engine's own comment: the gate **redistributes** rather
than attenuates — it moves the far glyph's share of the opacity onto the arrived one, keeping the
column's total. Under a 60 ms alternation **nothing ever arrives**, so "the arrived one" is merely
whichever glyph is least far out, sitting at ±0.4, and concentrating the column's brightness there
moves the centroid further rather than less.

So the remaining defect is not a weighting problem. No redistribution of brightness among glyphs
that are all off-centre can centre the ink. The only lever that touches it is how far the bright
newcomer has to travel — which is `FLIP_TRAVEL`, and which is a statement about the entry amplitude
under a reversal, not about opacity.

Best combined point measured so far, with the fix in:
`FLIP_GATE=1, FLIP_GATEHARD=1, FLIP_PARK=1, FLIP_PARK_AT=0.80, FLIP_LEVEL=1` — travel 0.424,
**band 0.698** against the reference's 0.760, imbalance 0.093. The band is solved; the travel is not.

### "Darker and more legible" is the pair handing over too completely — not sharpness

Reported from the device by eye, and the first two explanations for it were both wrong.

**Not sharpness.** Measured on the 60 ms alternation, per unit ink: this engine's changing column
reads 0.561 against the reference's 0.645, and its darkest pixel 0.298 against 0.314. Ours is
BLURRIER and LIGHTER, so "born already formed" is refuted.

**Not the press-and-hold either.** Lab's hold is `setInterval(30)` and the perceived cycle looked
like timer jitter. Sampled inside the JS runtime during a real hold: median 33 ms, one sample over
50 ms in 120. The timer is healthy. A first measurement said 68.6 ms with commits coalescing to +2
— **that was the frame recorder's own load**, which renders the layer into an alpha buffer every
tick and streams it to disk. Anyone measuring cadence with the rig armed is looking at a cadence no
user ever sees. What IS periodic is the data: Lab steps by ±1, so every ten ticks the units wrap
9 → 0 and the value carries into the tens, which makes it a two-column change and puts the units
column at the back of the wave. The reference does the same — `TRANSITION_MODEL.md` §5 measures its
"9" sitting crisp for 150 ms at exactly that carry.

**What it actually is:** how often ONE glyph owns the column. Ink either side of rest, 60 ms
alternation:

| | median imbalance | frames with one glyph over 70% | max |
|---|---|---|---|
| reference | 0.294 | **15.4%** | **0.446** |
| this engine | 0.351 | **40.6%** | 0.647 |

A digit becomes readable when one glyph carries two thirds of the column, and the reference never
lets that happen — it has a hard ceiling around 0.45 that this engine passes routinely.

**A ceiling on it was tried and it misses.** `FLIP_CAP=0.45` improves the excursion 0.406 → 0.342,
the imbalance 0.121 → 0.102 and the band 1.251 → 1.230, with the single crossing and the roll
bit-identical — but the rendered dominance does not move at all (0.351, 40.6%, max 0.647 → 0.633).
The cap divides glyphs by which side of rest they sit on; the profile does not, because a glyph just
above rest lays most of its ink below it. So this is a real gain arriving for a reason other than
the one it was built for, which is the same trap `AREA` fell into — do not port it as a fix for
legibility.

(Also worth keeping: the first version of that lever blended the CEILING by the signal rather than
the result, so a nominal 0.45 behaved as 0.603 at the signal's own median of 0.722 and never bit.)

### The hand test says the STACK wins, and it outranks the band

Driven on the device by a human, both engines side by side through the example app's own switch:
**the stack is the better one to look at.** That is worth recording precisely because the metric
disagreed — the 60 ms band reads 1.313 for the stack against 1.251 for the drum, i.e. the number
that has driven most of this branch's decisions said the opposite.

It is the same lesson this file already carries in "what to know before touching anything": the
metric ranks candidates, it does not find defects, and every defect fixed here was found by looking.
The band measures whether two forms stay separated in a time-averaged profile; it says nothing about
whether the motion reads as one continuous thing. Do not re-decide this on the band alone.

### Nine reversal-gated levers, and the three roads they closed

All in the simulator, `--model=kotlin`, nothing ported. Every lever is gated on `flipRaw`, so the
single crossing and the roll render **bit-identical** to the ungated model — checked with `cmp` on
the raw frame planes on every one of the nine, not asserted.

| lever | what it does | travel | band | imbalance |
|---|---|---|---|---|
| — | base, with the rest-test fix | 0.406 | 1.251 | 0.121 |
| `BIG=0.5` | glyphs shrink less | 0.385 | 1.389 | 0.095 |
| `AREA=0.5` | same ink weight, base size | **0.376** | 1.225 | 0.104 |
| `AREA=0.16` | calibrated to the reference's ink | 0.395 | 1.242 | 0.115 |
| `TILT=1.0` | weight towards the far glyph, total held | 0.401 | **1.222** | 0.115 |
| `KEEP=3` | only 3 contributors composited | 0.415 | 1.270 | 0.123 |
| `KEEP=2` | only 2 | 0.391 | 1.301 | 0.127 |
| `STRETCH=1.22` | every glyph taller | 0.411 | 1.333 | 0.101 |
| `STRETCH=1.40` | more so | 0.413 | 1.391 | **0.088** |
| `STRETCH_OUT=1.44` | only the DEPARTING glyph taller | **0.382** | 1.313 | 0.100 |
| reference | | 0.103 | 0.760 | 0.058 |

**The contributor count is REFUTED, and decisively.** With `KEEP=2` two glyphs carry **96.8%** of
the ink — the configuration this was supposed to need — and the central ink reads **0.155 against
the base's 0.157**, where the reference sits at 0.097. The middle is not filled by the weak
contributors; it is filled by the two dominant glyphs' own tails. Measured inside the engine, no
glyph is ever within 0.12 of rest at any cadence, so nothing is sitting in the middle at all.

**`AREA` wins for the wrong reason.** It improved all three at once, but it left the column 28%
brighter than the reference, and `TILT` — the same redistribution with the total held fixed — is
worth nothing (0.406 → 0.401). The gain is the alpha ceiling: brightening saturates the dominant
glyph at 1.0 and only the far ones grow. An artefact of the clamp, not a mechanism.

### The profiles, and what they actually say

Mean vertical profiles of the changing column, resampled onto glyph heights about rest and scaled
to unit AREA, so only shape differs (`profile.py` in the scratchpad):

| | FWHM up | FWHM down | W10 up | W10 down | W10/FWHM |
|---|---|---|---|---|---|
| reference | 0.585 | 0.535 | 0.705 | 0.715 | 1.271 |
| this engine | 0.405 | 0.515 | 0.560 | 0.660 | 1.332 |

And in ABSOLUTE windows about rest, at equal total area:

| window | reference | engine | ratio |
|---|---|---|---|
| \|y\| < 0.05 | 0.047 | 0.096 | **2.04** |
| \|y\| < 0.10 | 0.103 | 0.194 | 1.88 |
| \|y\| > 0.55 | **0.854** | 0.372 | **0.44** |

**Our lobes are not heavy-tailed — they are NARROW.** The tail ratio is 1.33 against 1.27 with a
Gaussian at 1.82, i.e. both profiles are flat-topped and ours is barely different. The real gap is
that the reference's mass is pushed OUT: it carries 2.3x ours beyond ±0.55 and we carry 2.0x its
ink inside ±0.05. A blur reduction was therefore **not run** — it would narrow the profile further
and empty the far mass, which is the axis we are already furthest from.

This also retracts the framing of the earlier "central ink 0.097 vs 0.157": that measure takes the
middle third *of the distance between the lobes*, and the reference's lobes are further apart, so
the two platforms were being compared over windows of different absolute width. The sign survives —
our middle really is twice as full — but the cause is not tail weight.

### What every road so far has converged on

Nine levers across three families — photometric share, entry/park geometry, and per-glyph shape —
and the same fact under each: **the reference holds two FULL glyphs, and this engine holds one full
glyph and a trail of faint residues.** Stretching the departing glyph by 44% moved the upper lobe by
6%, which says the upper lobe is not the departing glyph at all; it is the sum of the residues.

The untested thing that all three roads point at is the departing glyph's own ALPHA CURVE under a
reversal — it has to stay large and bright far longer than the measured isolated curve allows. That
is not a knob on top of the model; it is the fade law itself, and `TRANSITION_MODEL.md` §5 measured
that law on an isolated triple, never under a reversal. **There is no capture of a reversal at a
30-60 ms gap in the ground truth**, which is why this has never been checked.

## The measuring rig — the part that makes progress possible

Both platforms record themselves. `NumericTextFrameRecorder` renders the host view's presentation
layer into an 8-bit alpha buffer every display-link tick and streams it to disk with exact
timestamps and a mark at every value change. No screen recording, no resampling, no hunting for a
sync flash.

```bash
python3 .agent/tools/sim.py artifacts/<dir> --preset=single|up|alt60|alt120|alt240|roll
                                # a MODEL, rendered off-line into the recorder's format. ~1 s.
                                # Every tool below works on its output unchanged.
.agent/tools/round.sh <name>    # one build: both crossings and the alternation, all three compared
.agent/tools/fit.sh <name>      # build, install, drive the decrement preset, pull, compare
python3 .agent/tools/compare.py artifacts/<dir> [--up]
python3 .agent/tools/band.py artifacts/<dir>    # the alternation's middle band, iOS beside it
python3 .agent/tools/burst.py artifacts/<dir>   # the continuous roll: sharpness and tail
python3 .agent/tools/balance.py artifacts/<single> <alt> <roll>   # does the ink sit still, is the pair even
python3 .agent/tools/grid.py artifacts/<dir> out.png --title="..." --verdict=kept|rejected|open \
        --col=2 --step=33 [--mark=burst] [--ios=<prefix>]
python3 .agent/tools/gif.py  artifacts/<dir> out.gif --title="..." --verdict=... --slow=5
```

**Show the grids. Every round, as images, not as a description of them.** This is an animation; a
table of error terms is not evidence anyone can check, and the numbers rank candidates rather than
finding defects — every defect fixed on this branch was found by looking. `gif.py` is the stronger
of the two for anything about motion, `grid.py` for anything about a particular frame.

`--title` is required on both and `--verdict` says which of the round's sheets is the engine as it
stands and which is an experiment that was thrown away. A round makes several and they look alike:
handing over an untitled pair got a discarded attempt read as the current state, which makes a fixed
defect look live.

**`sim.py` first, `round.sh` second.** A model that cannot reach the reference off-line will not
reach it on a device either, and finding that out costs a second rather than twenty minutes. When
it is time for the device, `round.sh` is the one to reach for: a round costs one build instead of
three, and it prints both directions and all three cadences together, which is what stops a knob
being called good on the evidence of the one measurement it was fitted against.

**Know the scatter before believing a difference.** `band.py` and `balance.py` now print a `±`
spread beside every median, and `band.py` flags a cadence whose runs disagree by more than 0.03.
Measured, two runs of one unchanged build:

| | 60 ms band | 120 / 240 ms band | roll travel |
|---|---|---|---|
| spread within one build | 0.041–0.172 | ~0.006 | up to 0.244 |

The four-round square that chose the current `EVEN_SHRINK_AT`/`CROWD_SPREAD` corner was decided on
differences of 0.03–0.04 at 60 ms, and HEAD re-measured moves 0.038 on that same number. **That
round carried no information.** 60 ms now gets five runs; 120 and 240 are the cadences to fit on.

Traps, each of which has already cost real time:

- **Which ENGINE a round measured used to be invisible.** `stackMode` is switched by a marker file
  on the device and `round.sh` did not touch it, so a round measured whichever engine the last
  session had left armed — and the output said nothing. That is how "the stack" acquired a table of
  the drum's numbers. Drive with `ENGINE=stack .agent/tools/round.sh <name>`; read
  `artifacts/<name>/engine.txt` before believing any number in a directory.
- **The HOST's disk fills too, not just the emulator's.** A round writes ~3 GB locally and `sim.py`
  ~90–320 MB per preset. At 100% full the round simply stops part way — `align2` came back with two
  alternation runs out of nine and no roll at all, and its single-crossing table looked perfectly
  normal. Check `df -h .` before a round, not after.

- **A recording that ran out of disk leaves a `.bin` with no `.json`, and every glob here keys off
  the `.json`** — so it is skipped in silence and the analysis measures what survived. A run is
  90–240 MB and the alternation group is six of them, ~940 MB, against 611 MB free on a 6 G data
  partition. That ate the **240 ms alternation on three consecutive rounds** while these notes
  blamed the dev-client scrim. `round.sh` now drains after every run rather than every group,
  refuses to drive below 400 MB free, and says so when a group holds an orphaned `.bin`. Give the
  AVD more room as well: `disk.dataPartition.size` in `~/.android/avd/<AVD>.avd/config.ini`.

- **`fit.sh` taps a fixed screen coordinate, so adding a preset button moves it.** That once put
  two rounds of measurements on the wrong transition, and only a "restored" baseline reading 0.242
  instead of 0.031 gave it away. If a run reads far worse than the last for no reason, check the
  preset in the capture's marks first.
- **Use `grid.py --mark=burst` for anything with more than one change.** A preset resets the value
  before it runs and that reset takes a mark of its own, so aligning on mark 0 compares one
  platform's roll against the other's idle second — which produced a confidently reported defect
  that did not exist.
- **The recorder draws through a SOFTWARE canvas.** It sees `drawGlyphSoftware`, not the
  RenderEffect a device runs. Keep the two in step or you are measuring a renderer nobody sees;
  that hid an entirely absent blur for a day.
- **`adb install` fails with an EMPTY error message when the emulator's `/data` fills**, and a round
  driven after it measures the previous build under a new name. That produced a burst regression
  that did not exist — two "different" builds, one binary. `round.sh` reclaims space on failure;
  when driving by hand, grep the install for `Success` before believing anything after it.
- **The dev client's menu opens by itself and its scrim swallows every tap underneath.** It ate the
  240 ms alternation on two consecutive rounds in silence. Dismiss it with the Continue button and
  then the panel's X — never with `keyevent 4`, which backs out of the app when no menu is up and
  leaves the round driving the launcher.

Reference recordings, ~2.5 GB on disk, never committed:

| set | what | used by |
|---|---|---|
| `artifacts/gt_ios_ref/` | decrement, 1 run | `compare.py` default |
| `artifacts/gt_ios_up5/` | increment, 5 runs | `compare.py --up` |
| `artifacts/gt_ios_bursts/` | 3 bursts each direction | the burst tables |
| `artifacts/gt_ios_alt3/` | alternation at 60/120/240 ms | the overlap tables |
| `artifacts/gt_ios_behav/` | reversal and alternation | the interruption tables |

Their `reference.json` files **are** committed, so the numbers survive even if the pixels are lost.
To re-record: install the iOS app, launch with `SIMCTL_CHILD_NUMERICTEXT_RECORD=1`, drive a preset,
copy from the app container's `Documents/numerictext-record`.

**Noise floor**, five runs of one preset: amplitudes identical to three decimals, timings spread by
13 ms. Anything above 0.001 on an amplitude or 13 ms on a time is real.

## The engine

`android/src/main/java/com/numerictext/NumericTextTimeline.kt`, ~430 lines. One persistent column
per logical position, each a continuous position on a strip of stops. A change moves a column's
`target` and never restarts anything — that single property is what makes one tap and a
press-and-hold the same code path.

The strip is a **drum**, not a flat ribbon: ten digits on the ten faces of a decagon, so a stop of
travel is a tenth of a turn and a glyph's offset is `APOTHEM * sin(angle)` with a `cos(angle)`
foreshortening applied to its height only. `SCALE_AMOUNT` stays uniform and independent of it —
tying the whole scale to the angle is what the first, rigid attempt did, and it cost the crossing.

Two scalars per column, deliberately not one: `position` owns geometry, `settle` owns opacity. The
reference finishes its geometry at ~400 ms and then spends another ~350 ms bringing opacity to full
with nothing moving, which one scalar cannot express.

Every constant lives in the companion object and says whether it is Apple's or measured. There is
nothing to tune that is not there.

## What to know before touching anything

**The metric ranks candidates; it does not find defects.** Every defect fixed in this branch came
from looking at the app or at a frame grid: the roll running backwards in both directions, the blur
being absent rather than small, the blur being along a line instead of a defocus, the number
appearing to vibrate, the arrival reading pale, the wave starving far columns during a burst. Two
were artefacts of the measurement path rather than of the engine. **Produce a grid for anything you
claim.**

**A constant that does not transfer may be right with the model around it wrong.** Apple's `scale`
looked wrong until the crossing's two glyphs were given separate opacity curves — with one curve,
shrinking a glyph and dimming it are the same act.

**Measure before hypothesising, replicate before chasing.** Two burst defects were reported from
one run each and both turned out to be in the *other* direction once replicated three times.

**Formatting is now three implementations of one rule, and they have to stay in step.**
`src/numberFormat.ts` resolves the `format` prop; `NumericTextFormatter.kt` and the `FormatSpec`
half of `NumericTextSwiftUIHost.swift` each reproduce it natively. All three implement ECMA-402's
digit-bound rule and round half-away-from-zero, which is `Intl`'s default and neither platform's.
Changing one without the other two makes the two renderers draw different numbers, and the JS
width estimate size a box for a third.

The transition side of that is the affix key in `TransitionLogic`: a currency symbol, a percent
sign and an accounting bracket are keyed by distance from the digits (`P0` inward from the left,
`X0` inward from the right), so they survive the number gaining or losing a digit. Keyed by string
offset, which is what `O$i` did, a `$` dies and is reborn on every carry.

Verified so far: 39 JS unit tests, 25 Kotlin unit tests, `compileDebugKotlin`, `swiftc -typecheck`
in both configurations, a full `xcodebuild` of the example, and both renderers driven by hand on a
simulator through every format. **Not** verified against a recording: no ground-truth run has been
taken with a currency format, so the affix's motion during a carry is reasoned-about rather than
measured. That is the first thing to do if the affix ever looks wrong.

## Next, in order

The drum is **done and kept** and did not do what it was expected to do: it halved the single
crossing, headline 0.031 → 0.010 in both directions, and left the alternation band untouched. The
band was then solved by a separate mechanism — an apothem that widens while the column is chased —
and the pair's ink imbalance halved by the same signal levelling its opacity and its shrink together.
All three are zero at rest, which is why the single crossing has not moved since.

**Stop turning constants.** The four-round square that set the current corner decided differences
smaller than the 60 ms band's own run-to-run scatter, and this file's own table says no corner
satisfies both the alternation and the roll. Both of those are reasons the same shape of round
cannot converge, and a fifth one will not either.

1. **The travel, and it is structural rather than a constant.** `samples()` emits exactly two
   glyphs, `floor(position)` and the stop above it, and every one of offset, alpha and shrink is a
   function of `distance = |stop - position|`. The ink centroid therefore IS the column's position:
   if the column slides, the ink slides. The only memory in the system is one scalar per column,
   `evenness = CROWD_EVEN * crowdRaw`.

   The reference does the opposite of sliding — 0.163 on a single change and 0.103 / 0.139 / 0.161
   / 0.119 under every crowd, i.e. it moves its ink LESS when changes pile up. This engine matches
   the single change exactly (0.165) and then grows monotonically with crowding, to 0.517. No
   setting of a signal that scales one shared position produces "moves less when busier".

   What the reference's behaviour looks like instead is a STACK: one transition per change, each
   with its own clock, entering from `+offset` and leaving towards `-offset`. Their masses sit
   either side of the rest position, so the centroid stays put however many are alive — travel
   falls out for free rather than being fitted. It also explains why Apple's `offset` 0.59375 never
   transferred: it is a per-transition entry amplitude, not the spacing between two stops, and it
   was fitted here as spacing at ~0.32.

   `f981795` tried this and was reverted eleven minutes later for brightening the ink, 0.581 → 0.816
   — which is what summing N independent alphas does. A crossfade is a CONVEX combination, so the
   stack's alphas have to be normalised to sum to one, not dimmed individually. The one round that
   did normalise got the opacity nearly exact (0.333 against 0.317) and was then judged on the gap,
   which the drum and the chase have since solved by other means. **It was closed on a criterion
   that no longer applies.**

2. **The off-line simulator exists — `.agent/tools/sim.py`, and it has already run.** It cuts the
   settled glyphs out of the iOS recordings and renders a model into the recorder's own format, so
   `compare.py`, `band.py`, `burst.py`, `balance.py`, `grid.py` and `gif.py` all work on its output
   unchanged. A candidate costs **one second** instead of twenty minutes; sixteen model variants
   were swept in ten.

   **The stack model was built and measured there, and the result is split.** Apple's constants,
   no chase, no crowd signal, nothing fitted except two knobs swept against the single crossing:

   | | headline | travel single / alt60 / roll | band 60 ms | roll sharpness |
   |---|---|---|---|---|
   | reference | — | 0.163 / 0.103 / 0.119 | 0.760 | 0.600 |
   | device, drum + chase | **0.010** | 0.165 / 0.259 / **0.517** | **0.814** | 0.603 |
   | simulator, stack | 0.018 | 0.166 / **0.190** / **0.180** | **1.429** | 0.600 |

   The travel is fixed structurally, which nothing on the device has managed: the roll goes 0.517 →
   0.180 and the single crossing stays put, so for the first time in this branch a crowded regime
   and the isolated one improved TOGETHER. Balance follows it — 0.032 against the reference's 0.052
   through a roll, against the device's 0.118.

   **And the stack loses the band badly**, 1.429 against 0.760 where the chased drum reaches 0.814.
   A faster spring was the obvious suspect and it is not the cause: swept 0.30 → 0.09 the band
   never gets below 1.065, and by then the crossing has broken (headline 0.246). So the split is no
   longer two corners of one knob square — it is **two models, each winning a different regime**,
   which is a better problem to have and a different one to solve.

   Do NOT port the stack to Kotlin yet. That is the simulator earning its keep: it says this model
   is not ready, in ten seconds, instead of a day of Kotlin and a round.

3. **The synthesis worth trying next, and it is cheap now.** Keep the stack's per-transition
   identity — which is what produces the travel and the balance — and give each entry the DRUM's
   placement, `APOTHEM * sin(angle)` with the `cos` foreshortening, plus the chase's widening. The
   ground truth already records that the drum's SIZE is what produces the band (apothem 1.15 gives
   0.715 against 0.760) and that it cost the crossing, which is precisely what a stack does not
   have to pay: its crossing is set by one transition's own curve, not by the spacing of two stops.
   Both halves are already written, in `sim.py` and in `NumericTextTimeline.kt`.

   Second, unrelated and also cheap: the entry amplitude fits at 0.44 where Apple's `relativeOffset`
   is 0.59375. In this parameterisation `p` runs 1 → 0 → -1, so an arrival at `+OFFSET` and a
   departure at `-OFFSET` span `2*OFFSET`; Apple's number may be the transition's total travel
   rather than its one-sided amplitude. Half of it is 0.297 and the fit says 0.44, so neither
   reading is confirmed — one sweep settles it.

3. **Then re-measure, and only then.** The single crossing at 0.010 with the controls at 1.000 is
   the thing not to break; the roll's tail at 635 ms against 615 is the measurement that caught
   every previous attempt at this (all three balance experiments broke it, and `compare.py` never
   sees it).

4. **What is genuinely unknown, and cheap to find out.** `.numericText()` is animated by whatever
   animation is in the transaction. Driving the reference under an explicit
   `withAnimation(.linear(duration: 1))` would read every curve — offset, scale, alpha, blur —
   directly against a known clock, instead of fitting them through a spring that is being solved at
   the same time. Nothing in these notes has tried it, and it separates two unknowns that have so
   far only been measured multiplied together.
