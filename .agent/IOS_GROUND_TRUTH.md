# Ground truth — what is measured about the reference

Everything here is a measurement or is labelled as a guess. Rewritten from scratch on 2026-08-03 so
that no superseded claim survives; the working-out is in `git log`.

## How the reference is read

Two ways of reading SwiftUI's algorithm were tried and both answered no, so do not repeat them:

- **The CALayer tree.** Under a `Text` with `.contentTransition(.numericText())` there are three
  layers, the innermost a `CGDrawingLayer` with no sublayers, no CAAnimation on any of them, and
  transforms at identity for the whole transition.
- **Interposing a `TextRenderer`.** It is called ONCE, at the start, with the destination text laid
  out at rest, and then SwiftUI animates without calling it again. The transition survives being
  interposed unchanged, so the renderer is upstream of it.

SwiftUI rasterises the text once per value and animates the raster itself. **Pixels are the only
channel** — but the recorder makes them exact ones.

## Apple's own constants, read out of SwiftUICore

`ContentTransition.NumericTextConfiguration` is a real type in the shipped framework and its
accessors decode a packed byte layout.

```
/Library/Developer/CoreSimulator/Volumes/iOS_23B86/.../iOS 26.1.simruntime/.../SwiftUICore
nm -gU <bin> | swift demangle          # the members
objdump -d --start-address=... <bin>   # the getters decode the packing
```

Builders: `axis(Axis)`, `blur(radius: CGFloat)`, `delay(Double)`, `relativeOffset(CGFloat)`,
`reversed(Bool)`, `scale(CGFloat)`, and the statics `automatic(value: Double)` and
`fixed(downwards: Bool)`. The public `.numericText(value:)` and `.numericText(countsDown:)` are
those two statics.

**Read the defaults from the statics, not from `init(direction:axis:options:)`.** The init's
`options` argument defaults to 0, which reads `relativeBlur` as OFF; the statics write `options = 2`
from a different constant at `0xbf5780`, which turns it ON. Getting that wrong made this file claim
an 8.0-point absolute blur for a day when the real one is 0.25 relative.

| stored | packing | default | value |
|---|---|---|---|
| `delay` | byte 7 / 120 | 18 | **0.15 s** |
| `scale` | byte 8 / 128 | 51 | **0.3984** |
| `blur` | byte 9 / 4, or /128 when `relativeBlur` | 32 | **0.25 relative** |
| `offset` | signed byte 10 / 32 | 19 | **0.59375** |
| `maxDurationMultiple` | immediate in the getter | — | 1.25 |
| `options` | byte 6 | 2 | `relativeBlur` set, `reversed` clear |

`relativeOffset` is a builder, not a second field: it writes byte 10, the same byte `offset` reads,
with the same divisor. One field, two names — the name is what tells you the stored offset is
relative. `blur(radius:)` writes byte 9 and clears the `relativeBlur` bit: one byte, one flag, two
readings.

`delay` being stored in 120ths is itself informative — the wave is quantised to half a 60 Hz frame,
which is why measured column steps are always 8/120 or 10/120.

### Which transfer

| | transfers? | why |
|---|---|---|
| `delay` 0.15 s | **exactly** | the wave's TOTAL spread, divided by the gaps. Measured 70/137/220 ms on three columns: 150 ms end to end. |
| `scale` 0.3984 | **yes** | but only once the crossing's two glyphs had separate opacity curves |
| `offset` 0.59375 | no | the separation of two visible stops fits at ~0.32 |
| `blur` 0.25 | roughly | 0.42 is preferred by eye with the current 2D blur |
| `maxDurationMultiple` | untestable | it bounds `animationDuration`, which SwiftUI does not have — `.numericText()` is a spring with no duration, so the prop scales Android's springs alone |

## The single crossing

Reference, decrement 1,242 → 1,160, columns 2/3/4:

| | ink floor | at | extent | back to full | starts |
|---|---|---|---|---|---|
| | 0.515 / 0.435 / 0.428 | 137/204/287 | 1.181 / 1.147 / 1.196 | 420/504/587 | 70/137/220 |

Increment 1,160 → 1,242, five runs, spread in brackets:

| | ink floor | extent | starts |
|---|---|---|---|
| | 0.379 / 0.524 / 0.511 (±0.000) | 1.182 / 1.166 / 1.215 (±0.000) | 68.9/135.6/218.9 (±13 ms) |

The two unchanging columns read 1.000 on both platforms. That is the control: a constant that moves
them is wrong however good it makes the rest look.

**The two directions are not interchangeable.** Every metric is normalised against the column's own
settled glyph, so a crossing of 2→1 measured against a settled "1" is a different quantity from
1→2 measured against a "2".

## The wave

Columns start 67 and 83 ms apart — 8/120 and 10/120 — spanning 150 ms end to end, which is Apple's
`delay` to the millisecond. It is a fixed TOTAL divided by the gaps, so a wider number does not take
proportionally longer.

The leader waits **half a gap**, not zero and not a full one: at zero this engine read 35/102/186
and at a full gap 101/176/243, bracketing the reference's 70/137/220. There is ~35 ms of latency
before the first frame either way.

Each change carries its own countdown from its own arrival. A single pending slot per column made a
burst pile every change that arrived during one hold into one commit — traced on a 33 ms roll, the
rightmost column stood still for 187 ms then jumped eight stops at once, then six.

## The blur

**Isotropic — a defocus, not a smear along the roll.** A directional blur preserves a glyph's
structure across its axis, so the digit stays readable and merely looks streaked; the reference's
mid-crossing glyphs are unreadable clouds. `NumericTextConfiguration` storing its blur as a single
number with no axis fits that.

It follows the crossing's DISTANCE, not the glyph's speed. A shutter term proportional to velocity
was carried for a while on the reasoning that a fast roll should smear more, and it is simply wrong:
the reference is SHARPER through a fast burst (0.60 of a settled glyph's edge) than at the floor of
a single crossing (~0.45), because each crossing in a burst is short.

| shutter | roll sharpness against 0.600 |
|---|---|
| 0.026 | 0.36 |
| 0.012 | 0.40 |
| **removed** | **0.603** |

The radius must be quantised finely — at half-pixel steps a decaying blur walks down its buckets one
visible notch at a time and the glyph reads as vibrating. Eight steps per pixel is enough.

## The crossing's two glyphs

They do not share an opacity curve. With one curve the arriving digit reads weak and the old one
hangs around long after the reference has cleared it; brightening it lifts the ghost, dimming it
starves the arrival.

The exponent is weighted by how close a glyph is to **its own stop**, not by which stop is the
target. A binary role makes the two glyphs exchange exponents the instant the target moves — on a
single change that is invisible, but under an alternation it is the whole behaviour: three times the
reference's swing, 0.307 against 0.103, collapsing to 0.082 when both exponents are forced equal.

## Under interruption — the open ground

A reversal mid-roll shows no defect: both bend the motion rather than restart it.

A fast alternation does. Flipping one digit 0↔1 at three cadences, each platform's peak opacity read
against **its own** single crossing (iOS 0.478, Android 0.525):

| cadence | iOS | android |
|---|---|---|
| ~63 ms | **0.66** | **1.11** |
| ~117 ms | 1.02 | 1.26 |
| ~240 ms | **1.52** | **1.50** |

**Where transitions do not overlap the two agree exactly. Where they pile up the reference goes
below a level we never reach.** Three signatures separate them in the overlapped regime and vanish
outside it: peak opacity, the middle band between the two digits (0.756 against 1.513 at 60 ms,
1.362 against 1.328 at 240), and the total ink swing (0.046 against 0.194).

The geometry of it: the reference holds its pair 0.876 glyph heights apart under a fast alternation
and we hold ours 0.612, and the directions are opposite — against its own slow cadence it WIDENS
and we narrow. A two-glyph strip cannot span more than one step however the target moves.

A fourth difference is **not** part of that group: the reference's glyphs are 12% shorter than ours
at the bottom of a crossing, and that gap is flat at 1.14 across every cadence. Its shape — agreeing
at the start and end of a crossing and diverging only at the bottom — says it is the depth of the
shrink, not a constant factor.

### Closed brackets — do not re-walk these

| attempt | result |
|---|---|
| `SETTLE_KNOCK`, capping opacity when chased | saturates at 0.498 against 0.317, and does nothing at all to the gap |
| travelling superseded glyphs | spreads the ink but BRIGHTENS it, 0.581 → 0.816 |
| ...plus crowd normalisation | opacity almost exact, 0.333 against 0.317, gap barely moves |
| critical damping | single crossing 0.031 → 0.020, back-to-full 65-80 ms late, burst tail 685 ms |
| skipping the wave hold for a moving column | burst tail overshoots to 403 ms against 545 |
| deeper `SCALE_AMOUNT` alone | width ratio 1.125 → 1.062, headline 0.024 → 0.114 |
| the drum, rigid | produces the gap (1.513 → 0.849) and costs the crossing (0.031 → 0.181) |
