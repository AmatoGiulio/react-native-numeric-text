package com.numerictext

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.BlurMaskFilter
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.text.TextPaint
import android.view.View
import android.view.animation.LinearInterpolator
import com.facebook.react.common.assets.ReactFontManager
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor

class NumericTextView(context: Context) : View(context) {
  // ── Public prop backing fields ──
  private var settledValue: Double = 0.0
  private var settledText: String = "0"
  private var hasSettledOnce: Boolean = false
  private var numericValue: Double = 0.0

  var numericLocale: String = "en-US"; private set
  var numericDirection: String = "automatic"; private set
  var animationDurationMs: Long = 500L; private set
  var numericUseGrouping: Boolean = true; private set
  var numericMinFractionDigits: Int = 0; private set
  var numericMaxFractionDigits: Int = 3; private set
  var numericReduceMotion: String = "system"; private set
  var numericFontSize: Float = 48f; private set
  var numericFontWeight: String = "normal"; private set
  var numericFontFamily: String = NumericTextFonts.BUNDLED; private set
  var numericTextColor: Int = Color.BLACK; private set

  // Debug-only
  var debugTransitionStrategy: String = ""; private set
  var debugManualProgress: Float = -1f; private set

  // ── Global driver state ──
  // The global spring stays the animation *clock* and settle authority (and drives the block
  // debug strategies + the horizontal-origin interpolation). The per-slot springs below carry
  // the faithful per-digit motion for the default PER_GLYPH renderer.
  private var currentDirection: Int = 1
  private var animationProgress: Float = 1f
  private var animator: ValueAnimator? = null

  private var springValue: Float = 1f
  private var springVelocity: Float = 0f
  private var lastTickNanos: Long = 0L
  private var lastValueChangeNanos: Long = 0L

  // SwiftUI 26 resolves `.spring()` to duration 0.5, bounce 0. Its own SDK helper converts that
  // response to (2π / 0.5)² ≈ 157.914 and bounce 0 to critical damping. Keep this pair explicit:
  // it is the common motion clock for the tape, structural births and horizontal reflow.
  private val swiftDefaultSpringStiffness: Float = 157.914f
  private val swiftDefaultSpringDampingRatio: Float = 1f

  // Knobs — tune against iOS. dampingRatio < 1 gives the snappy overshoot; stiffness sets
  // how fast it settles (~4/(ratio·√stiffness) seconds).
  // Soft and flowing, NOT snappy. A stiff spring makes every digit a crisp discrete event, which
  // reads as mechanical/robotic; the reference feels docile because each roll is soft and the
  // rolls blend into one another. Keep the roll gentle and let the blur carry the motion.
  // Measured: the iOS roll's settle tail is ~0.45s (centroid still moving 24+ frames after
  // onset) with a gentle mass overshoot — that's a soft spring ≈150-170, not 320.
  // MEASURED against the reference: an isolated single-digit roll (2,576 → 2,577) goes from onset
  // back to full darkness in 267 ms. A soft spring (150) took ~450-570 ms, which is why a preset
  // whose two values are 400 ms apart never showed its intermediate value as sharp, and why the
  // last arrival of a growth looked like it turned up long after the rest.
  //
  // A fast transition does NOT make a spam re-sharpen: presses land well inside 267 ms, so the
  // reversal catches the glyph mid-flight — which is exactly how the reference behaves.
  private val springStiffness: Float = 250f
  // Just under-damped: keeps a slight settle in the roll's direction without a long ringing tail.
  private val springDampingRatio: Float = 0.9f
  // Damping of the ROLL AXIS spring for an ARRIVING glyph only — this is the settle bounce. The
  // reference overshoots its final position by ~10% and eases back over ~9 frames (METHODOLOGY
  // §5.5); at 0.9 the glyph crept in monotonically and the landing read as a stop, not a settle.
  // A second-order step overshoots by exp(-piZ/sqrt(1-Z^2)); 0.62 (~9%) measured a +0.02-0.03
  // line-height rebound against the reference's +0.05-0.07, so the ring was tripled to 0.45 (~20%).
  // Lowered again once the template fit could follow the ARRIVING glyph alone: on 2,577 -> 2,576
  // the reference's new glyph sits at -0.05 line-heights from +233 to +283 ms and is still at -0.02
  // at +333, where 0.45 gave -0.02 and was back at zero by +283.
  //
  // The spring's overshoot is NOT the drawn overshoot: rollOffsetShape raises it to the power 1.43,
  // so a 31% ring draws as 0.31^1.43 = 0.19 of the travel. Landing the reference's 0.05 line-heights
  // needs the raw ring at ~45% (damping 0.24) — but at that value the settle reads as too much on
  // the device. 0.35 turned out to be a full retreat (the drawn ring measured back at 0.02, the
  // value from before any of this), so this sits at 0.28: ~0.04 line-heights drawn, between the two.
  // The bounce should be felt, not seen. The numbers say what differs; only the eye says whether it
  // is right.
  //
  // Note the grid in template_fit resolves 0.023 line-heights, so a target of 0.04 reads as either
  // 0.02 or 0.05 there — this one is set by eye between two measurements, not by a measurement.
  // Presence keeps 0.9: the bounce is positional, an opacity that overshoots just flickers.
  //
  // Raised from 0.32 when travelFactor went from 0.15 to 0.45. The drawn bounce is a FRACTION of the
  // travel, so tripling the travel tripled it without anyone changing a damping ratio, and it read
  // as a spring that had become too pronounced. A second-order system's overshoot is
  // exp(−πζ/√(1−ζ²)), which rollOffsetShape then raises to 1.43: at ζ = 0.32 that is 0.35 ringing
  // and 0.22 of the travel drawn, so 0.10 line-heights at the new travel against the 0.04 this was
  // tuned to at the old one. 0.42 rings 0.23 and draws 0.13, landing at 0.058 — deliberately a
  // little above the old value, because the old travel was itself too short for the bounce to have
  // been judged at the right size.
  // 2026-08-02: tried at 0.90 against the exact recorder and reverted — it moved the settle tail by
  // 1.5 ms and cost 0.04-0.07 of extent. Neither this nor presenceOffsetFraction's exponent is on
  // the path a plain roll actually takes. See .agent/IOS_GROUND_TRUTH.md.
  private val arriveDampingRatio: Float = 0.42f
  // Velocity that maps to full roll blur (position+velocity blend below). Scales with the spring:
  // peak presence velocity is ~5.0 at stiffness 150 but ~7.4 at 340, so keeping the old 9 here made
  // the velocity term 45% stronger than it was tuned to be. It then pulsed on every digit change —
  // read as a shimmer on a continuous roll — and could even re-blur a glyph that had arrived.
  private val blurVelocityRef: Float = 13f
  // Displacement, in roll-span units, at which the velocity term above reaches full strength.
  // 0.06 of the span is about 3 ms of a released spring — enough to keep the first frame sharp
  // without visibly delaying the smear on a fast roll.
  private val BLUR_VELOCITY_GATE: Float = 0.06f
  // Opacity drop at full blur. A Gaussian blur alone doesn't lighten a large glyph enough — the
  // reference's out-of-focus bolla is LIGHT grey, so opacity is coupled to the blur amount.
  // 0.35 washed the mid-transition out too much (user-confirmed: ghosts too pale + an "empty
  // breath" at the crossing that iOS never has — its grey mass stays present throughout).
  // 0.22 -> 0.13. Two changes landed together that each take ink out of a crossing: the blur curve
  // gained a dead zone at the top and then rises HARDER over the middle (so this coupling now bites
  // where it used to be mild), and rollDepthMin shrank the mid-crossing glyph. Measured, the
  // column's ink floor fell from 0.44/0.47/0.54 to 0.36/0.43/0.46 against the reference's
  // 0.52/0.48/0.52 — the reference's departing ghost is SMALL AND DENSE, and we had made ours small
  // and pale. Same direction as the 0.35 -> 0.22 move before it, for the same reason.
  private val blurAlphaDrop: Float = 0.13f
  // Depth floor of a rolling glyph. 0.9 kept an arriving digit at essentially full size the whole
  // way, so nothing read as approaching from depth; the reference's barely-present glyphs measure
  // ~0.72 of their settled height, and presenceScale's convex falloff keeps the visible middle of
  // the roll near full size anyway.
  // Left at 0.75 alongside the lengthened travel. 0.55 was tried and measured wrong for the same
  // reason the opacity gate was (see TransitionLogic.presenceAlpha): the reference's glyph a full
  // line-height up is a small but solid digit, not a speck, so shrinking it further only removed
  // ink the reference has.
  // Then 0.75 -> 0.66. Not a return to the 0.55 that failed: that one removed so much ink the
  // glyph stopped being a digit, and the reading behind it — a departing glyph a line-height up is
  // SMALL BUT SOLID — still holds. What changed is that the pair is now genuinely separated in
  // depth-plus-position (arriveOffsetBaseline), so the size difference has something to read
  // against; at 0.75 both glyphs of a crossing were near enough full size that they read as two
  // same-sized digits fighting for one slot. 0.66 puts the mid-crossing glyph at ~0.77 of settled
  // height against 0.83 before, keeping it a legible digit.
  private val rollDepthMin: Float = 0.50f
  // presenceScale's curve exponent for a plain roll, against the reference-fitted 2.2 a structural
  // birth/death uses. Measured 2026-07-30 by comparing a frame grid of an isolated roll column by
  // column: at 2.2 the arriving/departing glyph is already within 80-90% of settled height a tenth
  // to a third of the way through its presence, which read exactly as "the new digit is already too
  // big and grows too fast, and the old one looks big too" — both glyphs share this exponent, so
  // the same curve was responsible for each. 0.55 keeps a glyph much closer to rollDepthMin through
  // most of its presence and only rises to full size as p approaches 1, matching a digit that
  // arrives small from depth and grows into focus right at the end of the roll rather than at the
  // start. Not fitted to isolated reference ink: at this travel the arriving and departing glyphs
  // overlap too much for either one's height to be measured in isolation, so this is set by eye
  // against the reference frame grid, the same way arriveDampingRatio's bounce size is.
  private val rollScaleExponent: Float = 0.55f

  // Spacing between successive arrivals. Measured on the reference's 1 → 9,999 growth: consecutive
  // columns differ by ~0.2 of presence, which over a 267 ms transition is ~45 ms per arrival.
  //
  // Raising this to 0.068 was tried on 2026-07-30 to widen the left→right staircase and measured
  // BACKWARDS — the visible stagger went from 100 ms to 50 ms. Holding an arrival back longer leaves
  // the column carried by its DEPARTING glyph for longer, and the departures are staggered by
  // staggerSeconds, not by this. So the staircase a viewer sees on a multi-column change is set by
  // the exit cascade; this constant only spaces the arrivals inside it.
  // 0.045 -> 0.07 on 2026-07-30, moved in step with staggerSeconds. The note above stands on its
  // own terms — raising this ALONE shortens the visible staircase, because it strands each arrival
  // behind a departure cascade that has not widened with it. Once the exits are spaced ~70 ms the
  // arrivals have to follow, or a column's incoming digit lands while the column to its left is
  // still mid-swap. Per glyph, the reference's incoming ink crosses half at ~133 / 265 / 300 ms.
  // 0.07 -> 0.076, absorbing arriveCrossSlow's speed-up so that only the first column moves: at
  // 0.58 every arrival comes ~12 ms sooner, and the spacing gives that back at ~6 ms per column.
  private val enterSpacingSeconds: Float = 0.105f
  // Spacing between the ARRIVALS of a structural change, left→right.
  //
  // This was 0 on the reading that "a structural change is one transaction — SwiftUI does not
  // enqueue a new clock for every column". Measured against the reference on 2026-07-31, that is
  // wrong, and it is the single largest defect in the renderer. Per final column, ink as a fraction
  // of that column settled, on `1,000 -> 999`:
  //
  //     t (ms)      0    66   132   198   264   330   396   462
  //     iOS c0   0.34  0.26  0.45  0.72  0.89  0.96  0.99  1.00
  //     iOS c1   0.78  0.76  0.70  0.45  0.68  0.87  0.95  0.99
  //     iOS c2   0.78  0.76  0.94  0.35  0.30  0.60  0.83  0.94
  //     ours     0.29  0.11  0.79  0.97  0.99  0.99  1.00  1.00   (all three columns, identically)
  //
  // The reference's columns cross 0.9 at 270 / 350 / 429 ms and ours cross it together at 190. Its
  // TOTAL ink never falls below 0.65 and takes 260 ms to get there; ours falls to 0.24 inside 66 ms,
  // because every glyph in the composition fades on the same frame. That frame is a number that has
  // almost vanished, and it is the blink the reports describe.
  //
  // 0.045 rather than the 0.07 the shrink alone suggests: the shrink has three columns and heavy
  // horizontal reflow through the measurement windows, so its ~70 ms per column is the noisiest of
  // the three reads available. The growth `9,950 -> 10,123` puts six arrivals across the same 139 ms
  // of spread (~28 ms each), and the older fit on `1 -> 9,999` measured ~45. Two of the three land
  // near this value.
  private val birthSpacingSeconds: Float = 0.045f
  // 0.04 -> 0 on 2026-07-30. With the exit cascade widened to 0.07 this lag was pure delay: the
  // per-glyph fit had every incoming column landing ~40 ms behind the reference with the SAME slope
  // (units crossing 0.1 -> 0.9 in 150 ms on both), i.e. a translation, not a shape error. It cost
  // the column's summed ink — a floor of 0.33 against the reference's 0.55 — because the outgoing
  // glyph had already gone by the time the incoming one had any weight. The arrivals are still
  // sequenced, by enterSpacingSeconds; this constant only pushed the whole run back.
  private val enterLag: Float = 0f

  // Horizontal reflow keeps per-glyph state so retargets remain continuous, but every state uses
  // the same critical SwiftUI clock.
  private val xStiffness: Float = swiftDefaultSpringStiffness
  private val xDampingRatio: Float = swiftDefaultSpringDampingRatio
  // LEFT→RIGHT cascade (reverse-engineered from the iOS reference at 60fps: on a multi-digit
  // change the leftmost changed column leads). Kept SUBTLE — a large delay turns the cascade into
  // a visibly sequential, machine-like wave.
  //
  // 0.04 -> 0.07 on 2026-07-30, on the per-glyph fit. An earlier try at 0.05 was rejected on the
  // reading below — "the reference barely staggers its starts" — and that reading came from a probe
  // that could not separate the two glyphs of a column, so a column whose OLD digit was still
  // sitting there read as "not started". Per-glyph, the reference's outgoing ink crosses its half
  // point at 65 / 135 / 223 ms across three columns: ~80 ms apart, twice what 0.04 produces even
  // before the spam gate scales it down. The visible staircase IS the exit cascade.
  // Only valid alongside a quick per-column collapse (see rollExitFadeRate) — widening the cascade
  // under a slow collapse just overlaps every column with the next one.
  // Then 0.07 -> 0.09, on medians over repeated runs (.agent/tools/parity_report.py): at 0.07 the
  // half-gone times stepped 67 ms per column against the reference's 86, leaving the units 38 ms
  // early. This also lifts the column's ink floor, because what empties a column is the GAP between
  // its outgoing glyph going and its incoming one arriving, and the arrivals already land on the
  // reference — so the gap has to close from the exit side.
  // Then BACK to 0.05 once exitSlowPerColumn landed. The two are alternative ways to make the same
  // column look late, and with the reference's per-column slowness in place, 0.09 of delay on top
  // overshot every half-gone time by 45-76 ms. The vindication of the old note below is exact: most
  // of the reference's apparent lateness is duration, and only a little of it is delay.
  //
  // Measured on 2,000 → 1,999 (four changing columns), per column, over the middle 50% of the glyph
  // so a neighbour's blur halo cannot reach it, normalised at +283 ms (the next scripted step lands
  // at +320 ms, so a later reference measures the wrong target). Both the START (10% of the column's
  // total change) and the HALFWAY point (50%):
  //
  //     iOS      start  17 / 33 / 33 /  50 ms   (span only 33 ms — near simultaneous)
  //              half   17 / 83 / 117 / 167 ms
  //              so DURATION 17 / 67 / 100 / 133 ms
  //
  //     ours     start   0 /  0 / 33 /  33 ms
  //              half    0 / 17 /  67 / 117 ms
  //              so DURATION 0 / 33 /  50 / 100 ms
  //
  // The reference's columns all begin within 33 ms of each other; what sweeps left→right is that
  // each column further right takes LONGER to get through its change — 17 ms for the leftmost
  // against 133 ms for the units. The wave is made of increasing slowness, not increasing delay.
  // Ours has the same shape, compressed, and its leftmost column has no duration at all (0 ms: it
  // simply snaps), which is the "we start the blur too early" that was reported.
  //
  // So the open work is a per-column DURATION, not a per-column delay. It cannot be a spring-rate
  // multiplier as first sketched: making the rightmost column 8x slower means dividing its
  // stiffness by ~60, and our settle tail is already LONGER than the reference's (767 ms against
  // 550 ms on this same transition). Whatever lands here has to separate how fast a glyph crosses
  // from how long it takes to come to rest — today one spring does both.
  private val staggerSeconds: Float = 0.090f
  // The DEPARTURE half of the same wave, and the delay in front of all of it. See
  // [birthSpacingSeconds] for the measurement that put these back; these three are the other side
  // of it and were zeroed by the same reading.
  //
  // `structuralExitLead` is what makes the old composition stand WHOLE before anything moves. It is
  // the most directly measured number here: on `9,950 -> 10,123` the reference's right-hand columns
  // are still at 1.02-1.10 of their settled ink at +132 ms and the leftmost has only just begun at
  // +99, and on `1,000 -> 999` the whole number is crisp and untouched through +67. Ours starts
  // dissolving on the first frame.
  //
  // `substitutionExitLead` is the extra a glyph waits when its slot is being TAKEN rather than
  // deleted. A deletion may go as soon as its turn comes; a substitution has to be there until its
  // replacement carries ink, or the column shows a hole.
  //
  // BOTH LEADS ARE 0, and the first attempt at this proves why. At 0.06 / 0.025 they delay a
  // column's departure by 85 ms past its OWN arrival — exits carry the lead, arrivals do not — and
  // the blink inverted into a pile-up: total ink on `1,000 -> 999` went 1.18 / 1.28 / 1.43 / 1.40
  // and never dipped at all, where the reference dips to 0.71. Two whole numbers on screen at once
  // is not better than none.
  //
  // The per-column depth was never the defect. Measured on the rightmost column, the reference dips
  // to 0.24 and ours dipped to 0.22 — the same hole, in the same column. What was wrong is that all
  // three columns dug it on the SAME FRAME, and that is a stagger, not a lead. So the wave carries
  // the whole handover — a column's exit and its arrival share the ordinal, as the code below
  // already arranges — and neither side of it is pushed past the other.
  private val structuralStaggerSeconds: Float = 0.045f
  private val structuralExitLead: Float = 0f
  private val substitutionExitLead: Float = 0f
  // Per-column SLOWNESS of a roll's departure — the other half of the reference's wave, and the
  // one the old comment below correctly identified as the open work.
  //
  // Measured as how long each outgoing glyph takes to go from 0.9 to 0.1 of its ink, on
  // 1,242 -> 1,160, medians over three runs:
  //
  //     iOS    123 / 281 / 188 ms   (hundreds / tens / units)
  //     before  84 / 249 /  96
  //
  // Our columns all collapsed at one rate and were merely released at different times, so the half
  // -gone instants matched while the character did not: the reference UNROLLS the right-hand
  // columns, we snapped them in sequence. This divides the exit presence stiffness by
  // (1 + slow·colIndex)², so column n takes (1 + slow·n) times as long — duration goes as 1/sqrt(K).
  // 0.5 makes the units (index 2) twice as slow as the leftmost, which is the ratio measured above.
  // It is deliberately NOT applied to offK: that owns the settle tail, which is already longer than
  // the reference's, and the two must stay separable — one spring doing both is what made the
  // earlier sketch of this need a 60x stiffness collapse.
  // 0.5 -> 0.265, refitted together with rollExitFadeRate once the base rate carried part of the
  // slowness: what the reference asks for is the RATIO between the leftmost column's fall and the
  // rightmost's (1.53), not the absolute slowdown, and 0.5 was solving for the ratio alone.
  // 2026-08-02, two-point fit against the exact recorder. Splitting the capture into outgoing-only
  // and incoming-only runs showed the floor is carried by the DEPARTURE — at the units' floor ours
  // holds 0.725 of the outgoing glyph against 0.074 of the arriving one — and that the departure
  // gets progressively slower rightwards: the outgoing ink reaches zero at ~200 / ~400 / ~600 ms on
  // the three columns. This constant is what makes it progressive.
  //
  // Recorded at 0.265 and at 0. The leftmost column did not move at all either time, which is the
  // signature this constant must have, and the other two are linear in it — solving each for the
  // reference's own floor gives 0.092 and 0.126 independently. 0.265 -> 0.11.
  private val exitSlowPerColumn: Float = 0.11f
  // Structural roles may differ in blur/travel, not in their physical duration. The previous 0.65
  // made the third arrival 2.3× slower than the first and created the reported visible wave.
  //
  // Left at 0 on 2026-07-31, deliberately, while [birthSpacingSeconds] came back. The roll's wave
  // IS slowness and this one is not: measured on `1,000 -> 999`, each of the reference's columns
  // takes a comparable time to recover — c0 goes 0.45 -> 0.93 in 165 ms, c1 0.55 -> 0.92 in 132,
  // c2 0.30 -> 0.90 in 165 — and only their START differs. Equal ramps at different offsets is a
  // delay, not a slowness, and giving this one both would double-count the wave.
  private val birthSlowPerColumn: Float = 0f
  private val structuralExitSlowPerColumn: Float = 0.04f
  // Below this gap between two changes every rate is at its CROWDED value, and each blends linearly
  // back to its isolated one up to twice it. A hold on +/- repeats every 30 ms and the scripted
  // burst every 45 ms — both must land at zero — while a change from rest must get the full cascade
  // the reference shows there.
  //
  // Widening this to 200 was tried on 2026-07-30 and fixed the wrong thing well: the ink stopped
  // climbing out of the box, and went pale doing it (the crowded blend also speeds every departure
  // and every arrival's PRESENCE, so at a tap cadence the column never darkens). Position and ink
  // are separate axes here — see [offsetCrowdMs], which is where the widening belongs.
  private val cascadeSpamMs: Float = 90f
  // The same gate, for the arrival's POSITION only, and much wider.
  //
  // Someone tapping the button as fast as a thumb comfortably goes lands changes 200-250 ms apart.
  // At `cascadeSpamMs` that is fully isolated, and the isolated rates are deliberately slow — the
  // arrival's offset spring sits at [arriveOffsetBaseline], 0.42 of the base stiffness, so that a
  // crossing pair stays separated in SPACE. At 220 ms that glyph is still in the air when the next
  // tap lands, and the next one starts a fresh roll from above it. Measured over a scripted 220 ms
  // tap run (the Showcase's third cadence), how far the ink reaches past the settled digit box, in
  // glyph heights, over 1.6 s:
  //
  //     iOS       up  median +0.00  worst +0.00      down  median +0.02  worst +0.04
  //     before    up  median +0.07  worst +0.20      down  median +0.01  worst +0.03
  //
  // The reference never leaves its box at this cadence: the digits change inside the line and the
  // only thing outside it is a soft ghost hanging just below. Ours climbed out of the top — the
  // "the last digit keeps going higher and higher" of the report, and why a run of taps reads as
  // popping rather than rolling.
  //
  // Only the offset is widened. Presence, blur and the exits keep `cascadeSpamMs`, so a tapped
  // change still carries an isolated change's ink and softness; all that changes is that its digit
  // is on the line by the time the next tap arrives.
  private val offsetCrowdMs: Float = 260f
  // How much faster than the presence spring a ROLL's departure fades, when the change stands
  // alone. See pK below.
  //
  // Was 1.3 — departures shedding FASTER than arrivals gain — until the per-glyph fit put a number
  // on what that costs. Summed ink in the units column of 1,242 -> 1,160 (outgoing + incoming, as a
  // fraction of one settled glyph): the reference never drops below 0.55 and is back over 0.8 within
  // 100 ms, while at 1.3 we fell to 0.18 and stayed under 0.7 for 200 ms. That hollow is visible in
  // the grid as a stretch where NO digit in the changing columns is fully inked — two grey ghosts
  // instead of a handover, which is what "it dissolves" meant.
  // 0.49 was that first correction — the departure's rate set equal to the arrival's. It fixed the
  // hollow but bought it with a limp start, because it treated as ONE defect what the per-column fit
  // then showed to be two. Measured per column on 1,242 -> 1,160, the time each outgoing glyph
  // crosses half its ink:
  //
  //     iOS    65 / 135 / 223 ms   (hundreds / tens / units) — steps of ~80 ms
  //     at 0.49  117 / 132 / 170                             — steps of ~25 ms
  //
  // The reference does NOT hold a glyph with a slow spring. Every column of it collapses fast —
  // ~130 ms from full to a tenth, its leftmost already down to 0.76 by +33 ms — and what makes the
  // units look "held" is that their column is released much later. Slowness and lateness produce
  // the same still frame and are not the same thing: slowing the spring flattened OUR leftmost
  // column too, which is why it now starts 50 ms late and reads limp.
  // So the collapse goes back to being quick and the waiting is moved where the reference puts it,
  // into staggerSeconds. The two must move together — a wide cascade with a slow collapse smears
  // every column into its neighbour, and a quick collapse on a narrow cascade is the hollow again.
  // The spam path is deliberately NOT moved with it: rollExitFadeFast (2.9) still owns the
  // press-and-hold, so the pile-up this constant used to guard against is still guarded.
  // Then 1.15 -> 0.71 with exitSlowPerColumn dropped alongside it. With the wave's slowness in
  // place, the leftmost column — which gets none of it — was the one left too quick: 90 ms to fall
  // against the reference's 123. Solving both ends at once, the fall spans want 123 (index 0) and
  // 188 (index 2), a ratio of 1.53 rather than the 2.36 we had; duration goes as 1/sqrt(K), so the
  // base rate drops to (96.5·sqrt(1.15)/123)² ≈ 0.71 and the per-column term to (1.53-1)/2 ≈ 0.265.
  // Then 0.71 -> 0.95 with staggerSeconds 0.05 -> 0.065. Two residuals were left, and they are one
  // adjustment: our columns stepped 60 ms apart against the reference's 86 (too narrow), and every
  // column began ~40 ms after the reference's did. Widening the step alone would have pushed the
  // units — the one column already on the reference — 40 ms late, so the collapse gets quicker by
  // the same amount the cascade widens, and the whole run slides back onto it.
  // Part of that 40 ms is structural and cannot be tuned away: the value reaches the native view in
  // the React commit that paints the sync marker, but the view acts on it on the NEXT frame, so ~17
  // ms of it is the pipeline, not the animation.
  private val rollExitFadeRate: Float = 0.95f
  // …and when changes are arriving faster than a transition can play. Both readings are right and
  // they conflict: an isolated roll should show its old digit roll out (fitted: the reference is at
  // 0.71 / 0.33 of its opacity at +33 / +83 ms), while a continuous roll should not stack four
  // half-faded glyphs below the line (fitted: the reference's column median sits +0.06 during a
  // 30 ms hold, ours +0.17). Same signal as the cascade gate decides which case this is.
  //
  // 4 emptied the column instead: during a hold the reference carries 0.60 of a settled glyph's
  // ink at any moment and we carried 0.19, which reads as a roll that is too pale, too quick and
  // sitting high — with the departures gone, all that is left is the arriving glyph, which is
  // always above its final place. 2.5 then overshot the other way, to +0.10 with 0.42 of the ink;
  // 2.9 splits them onto the reference's +0.06.
  private val rollExitFadeFast: Float = 2.9f
  // How much faster an ARRIVING glyph resolves when changes crowd. The two are SEPARATE because
  // presence carries three things — opacity, depth scale and blur — while the offset carries only
  // position. Speeding both by 2.6 landed the digit centred but at full size and sharp the moment
  // it appeared, which is the depth cue gone. The reference keeps them apart: during a hold its
  // rolling digit is aligned with its still neighbours to within 0.005 of a line height, yet its
  // darkest pixel is only 0.43 of a settled glyph's — in place, but far from resolved.
  private val arrivePresenceFast: Float = 1.5f
  private val arriveOffsetFast: Float = 2.6f
  // Baseline multiplier on the arrival's offset stiffness for an ISOLATED change (changeSpacing =
  // 1); arriveOffsetFast above is what it ramps toward as changes crowd. Was implicitly 1 (raw
  // springStiffness), which — with the low-slope scale curve fixed elsewhere — read on a frame grid
  // as the arriving glyph reaching its resting position by ~117 ms, well before the reference's
  // roll has visibly finished settling. 0.75 lowers the isolated case's natural frequency, which
  // delays the raw spring's first crossing of its target by roughly 30 ms without touching the spam
  // path (arriveOffsetFast is unchanged, so a crowded roll's arrival still snaps in as before). Set
  // by eye against the frame grid, not measured — an isolated roll's two glyphs overlap too much at
  // this travel to isolate one glyph's position in the recording directly.
  // 0.75 -> 0.42 on 2026-07-30. The per-glyph fit measures how far APART the two glyphs of a
  // crossing are while both are on screen, and ours were half the reference's: 0.23 line-heights on
  // the units against 0.32, and 0.20 against 0.38 on the hundreds. Both platforms' OUTGOING glyph
  // travels the same distance (-0.14 to -0.18) — the whole difference is the incoming one, which at
  // +233 ms sits at +0.07 for us and +0.23 for the reference. Ours is essentially in place by the
  // time it lights up, so the pair reads as one smudge in a single slot instead of one digit above
  // another. A slower arrival spring keeps it up in the air while it gains its ink.
  private val arriveOffsetBaseline: Float = 0.42f
  // How much SLOWER an arriving glyph gains presence, on top of everything above. Applied to the
  // presence spring only — not to offK, which owns the settle tail that is already longer than the
  // reference's (767 ms against 550 ms on 2,000 -> 1,999), so slowing it would make a worse problem
  // worse. Fitted on the column durations of 1,242 -> 1,160 (10%->90% of each column's own change):
  // the reference runs 50 / 83 / 133 ms on hundreds / tens / units against our 33 / 67 / 83 — the
  // same shape, uniformly at ~0.70 of the reference's duration. A spring's duration goes as
  // 1/sqrt(K), so one uniform 1/1.43² ≈ 0.49 on the stiffness stretches all three onto it.
  // Only lands together with presenceAlpha's smoothstep: each was tried alone and each alone
  // measured worse, because the curve's shape and the spring's speed are independent defects and
  // fixing one unmasks the other.
  // 0.49 -> 0.58. The arrivals of the tens and units land on the reference to within 5 ms, but the
  // FIRST column's — the only one with no spacing in front of it — was 33 ms late, and that is what
  // held its ink floor at 0.41 against the reference's 0.52. With no delay to remove, the only thing
  // making it late is the spring itself, so the spring gets slightly quicker and enterSpacingSeconds
  // absorbs the difference for every column after the first.
  // Then 0.58 -> 0.68, absorbing TOP_SLOPE. Giving the alpha curve a live top lowers it everywhere
  // below p = 1, so an arrival needs ~15 ms more to reach any given ink — the exits wanted that,
  // the arrivals did not, and they were already on the reference.
  // Then 0.68 -> 0.55. The two glyphs of a crossing share the ink differently than the reference
  // does: at +233 ms iOS carries 0.41 of the OUTGOING glyph and 0.14 of the incoming, we carried
  // 0.22 and 0.34 — the same total (the floors match) split the wrong way round, which is why the
  // outgoing digit reads as vanishing rather than as riding out of the slot. The arrival crosses
  // half its ink 28 ms before the reference's, so it is slowed by that much; the exits are already
  // on the reference and are left alone.
  // …and then back to 0.66. Slowing the arrival's OPACITY as well as its position overshot: the
  // first column — the only one with no spacing in front of it, so the spring alone sets it — lit up
  // 39 ms after the reference's and left its ink floor at 0.37 against 0.52. The two axes are
  // separable and want opposite things here: the POSITION stays slow (arriveOffsetBaseline, which is
  // what holds the arriving glyph up in the air and separates the pair), the OPACITY goes back to
  // quick. Keeping them apart is the same distinction the pair of constants was created for.
  private val arriveCrossSlow: Float = 0.66f
  private var changeSpacing: Float = 1f   // 1 = isolated change, 0 = spam; see cascadeSpamMs
  /** The same, on the wider window the arrival's POSITION uses. See [offsetCrowdMs]. */
  private var offsetSpacing: Float = 1f
  // ── Continuous roll tape ──
  //
  // A plain roll is one persistent physical coordinate per column. Every changed character is
  // placed on that column's tape and advances the target by one lane; the live phase and velocity
  // are never reset. This is the same merge invariant as SwiftUI's persistent spring: a later value
  // replaces the target of the motion already on screen instead of starting another 0 -> 1
  // lifecycle beside it.
  //
  // The phase uses SwiftUI's actual default spring. Retargeting preserves phase and velocity, so a
  // 220 ms tap joins the motion already on screen without needing an artificial slow column.
  private val rollTapeStiffness: Float = 120f
  private val rollTapeDampingRatio: Float = 0.95f
  private val rollTapeSlowPerColumn: Float = 0f
  // The roll's left→right wave, as a per-column HOLD on the tape's target.
  //
  // Measured on the reference's `1,242 -> 1,160`, per column: it bottoms out at 100 / 167 / 250 ms
  // and its recoveries take 167 / 183 / 167 ms — identical within a frame. Equal durations at
  // shifted starts is a DELAY, and it is why [rollTapeSlowPerColumn] stays 0: dividing the phase
  // spring's stiffness would move the floors apart correctly and stretch the right-hand columns'
  // recovery to 2.2× the reference's while doing it.
  //
  // (The older note under `staggerSeconds` reads the opposite — "the wave is made of increasing
  // slowness". That was measured through the two-template per-glyph fit, whose own documentation
  // says the tens column wanders and whose residual is ~0.18. Column ink, which needs no model,
  // gives three recovery ramps within 16 ms of each other. On the duration question, trust the
  // direct one; the two are not strictly in conflict, since the outgoing glyph's fall can lengthen
  // while the column's whole handover keeps its length.)
  //
  // 0.075 is the mean of the reference's two steps, 67 and 83 ms. It is scaled by the crowding gate
  // like every other stagger, so a press-and-hold does not accumulate holds.
  private val rollTapeStaggerSeconds: Float = 0.110f
  // Presence, depth and softness are functions of tape distance, with one curve for both sides of
  // a handover. Changing a glyph from "incoming" to "outgoing" therefore cannot change its shape on
  // the retarget frame.
  private val rollTapeScaleExponent: Float = 1f
  // Velocity continuously moves the blur from isolated to crowded. Unlike the old elapsed-time
  // gates, this cannot switch the stiffness or the look of every live glyph on a tap boundary.
  private val rollTapeSoftVelocity: Float = 2.5f
  // Once a lane has passed this far behind the live phase it cannot contribute visible ink or be a
  // useful immediate reversal target.
  private val ROLL_TAPE_CULL_DISTANCE: Float = 1.35f
  private val ROLL_TAPE_REUSE_DISTANCE: Float = 1.35f
  // The visible front is deliberately less than one full lane ahead. A full-lane lead would put
  // an arriving glyph at presence 0 for the entire fast section of the roll.
  private val ROLL_FRONT_LEAD: Float = 0.35f
  // The simple phase roll gets its mass from overlapping glyphs; this defocus merges their edges
  // at peak speed while the complementary alpha below keeps the strip from becoming over-dense.
  private val SIMPLE_ROLL_BLUR_SCALE: Float = 1.0f
  // The source stays readable at launch and the crowded strip keeps enough mass at speed. The
  // former 55% cruise drop made every integer crossing collapse into a nearly empty frame; the
  // reference instead keeps a continuous two-glyph stain throughout the fast section.
  private val SIMPLE_ROLL_SPEED_ALPHA_DROP_EARLY: Float = 0.30f
  private val SIMPLE_ROLL_SPEED_ALPHA_DROP_FAST: Float = 0.30f
  // Blur must follow the speed of the simple phase, not the much slower legacy tape. With the old
  // 2.5 lanes/s reference even the final 9 and the carry were fully blurred; the reference starts
  // resolving them as soon as the fast middle of the roll is over.
  private val SIMPLE_ROLL_BLUR_VELOCITY: Float = 12f
  // At speed SwiftUI does not expose one full intermediate digit at a time. The two neighbouring
  // lanes carry almost equal visual weight, producing the persistent two-glyph column visible in
  // the 16.7 ms reference grid. Their combined opacity is about one 70%-dark glyph after the speed
  // attenuation above, matching the measured reference ink floor.
  private val SIMPLE_ROLL_FAST_PAIR_PRESENCE: Float = 0.68f
  // A fast camera-integrated roll occasionally exposes three adjacent lanes. At +117 ms the iOS
  // reference contains 2/1/0, while a strict two-lane sample can only contain 2/1. Keep one faint
  // lane behind the main pair once the tape has crossed its first full lane; it fades with speed.
  private val SIMPLE_ROLL_FAST_TRAIL_PRESENCE: Float = 0.28f
  private val SIMPLE_ROLL_TRAIL_START: Float = 0.95f
  private val SIMPLE_ROLL_TRAIL_FULL: Float = 1.10f
  // At an exact integer lane the shared glyph must remain dominant. Forcing a second lane there
  // made the centre jump by half the travel every time the pair advanced; the velocity blur itself
  // supplies the soft trail at that instant. The pair becomes balanced only between lanes.
  // Velocity owns the fast section, but a slow crossing is still a two-glyph hand-off and remains
  // soft and dense around its midpoint. Without this bell the final 9/0 pair sharpened into two
  // outlined digits while iOS still showed one fused mass. It is zero at either settled lane, so
  // the final glyph still resolves completely sharp.
  private val SIMPLE_ROLL_SINGLE_HANDOFF_ALPHA_LIFT: Float = 0.50f
  private val SIMPLE_ROLL_BURST_HANDOFF_ALPHA_LIFT: Float = 0.15f
  private val SIMPLE_ROLL_BRAKE_ALPHA_LIFT_PER_BLEND: Float = 0.28f
  // The measured final 9→0 crossing reaches the same softness as the fast smear at its midpoint.
  private val SIMPLE_ROLL_HANDOFF_BLUR_SCALE: Float = 1.0f
  // Once the fast pair was made continuous, its measured envelope grew to 1.34-1.48 glyph heights
  // against iOS's 1.25-1.40. A 0.58 lane keeps the same two-glyph stain without overextending it.
  private val SIMPLE_ROLL_TRAVEL_FACTOR: Float = 0.58f
  // During cruise the reference's centre of ink stays slightly on the departure side instead of
  // alternating above/below the baseline at every integer lane.
  private val SIMPLE_ROLL_CENTER_BIAS_FACTOR: Float = 0.06f
  // Two targets belong to the same deterministic burst only when they arrive before a normal
  // one-lane spring can reach its first target. This includes a genuine fast double tap while an
  // ordinary later tap remains an isolated roll.
  private val SIMPLE_ROLL_BURST_GAP_SECONDS: Float = 0.160f
  // A higher-order carry joins a tape that is already moving. Starting all presentation ramps at
  // zero delayed the new tens glyph by roughly two frames against iOS.
  private val SIMPLE_ROLL_CARRY_WARM_AGE_SECONDS: Float = 0.100f
  // Fabric may coalesce several 30 ms React updates into one native prop commit. Keep a small
  // skipped run on the same tape instead of mistaking 1 -> 6 for an arbitrary direct replacement.
  private val SIMPLE_ROLL_MAX_COALESCED_STEPS: Int = 10
  // One continuously retargeted spring creates the whole speed envelope by itself. Every 30 ms
  // target adds energy while the counter is running; when updates stop, the same spring naturally
  // brakes through 9 and resolves into 0. Switching to a 1,200 "cruise" spring made Android peak
  // 50 ms early, finish 100 ms early and leave sharply outlined ghosts during the settle.
  private val SIMPLE_ROLL_STIFFNESS: Float = 340f
  // Where a roll's departure asymptotes, in travel units, drawn through rollOffsetShape's 1.43
  // power. Measured on a press-and-hold in the example app — the same 30 ms repeat on both sides —
  // as how far the rolling column's ink sits below where it settles: the reference holds a median
  // of +0.06 line-heights and never passes +0.12, while 2.0 units and then 1.15 (0.40 and 0.18
  // drawn) parked ours at +0.17, which is the digit visibly sinking during a continuous roll.
  // Ours parks AT the asymptote because departures linger there. Pulling the asymptote in helped
  // (0.8 drew 0.11 and measured +0.12) but could not reach the reference, which does not park at
  // all: on an ISOLATED roll its outgoing glyph is still visible 0.14 away, yet during a hold the
  // column's median sits at +0.06 — it passes through and is gone. See rollExitFadeFast.
  private val rollExitOff: Float = 1.0f
  // How far a revived glyph's off must already be from the baseline before scheduleSlots resets it
  // to a clean arrival start. Below this it is presumed to have only just been marked to retire —
  // still effectively at rest — and resetting it would be the jump that caused a regression, not a
  // fix. See the guard where this is used.
  private val REVIVE_OFF_RESET_THRESHOLD: Float = 0.15f
  // How much faster a structural DEATH runs than the presence spring — BOTH its springs, so the
  // trajectory keeps its shape and is simply played faster. Fitted per glyph on 1,000 -> 1, where
  // the composition loses four glyphs in sequence (.agent/tools/template_fit.py, single-template
  // mode): each of the reference's dying digits goes from full to half opacity in 50 ms, ours took
  // 83. That 1.6x also stretched the gap between consecutive deaths — a slower fade reaches the
  // 90%-gone mark later — so the two glyphs left together as a pair rather than one at a time.
  // Time in a second-order system scales as 1/sqrt(stiffness), hence the square below.
  private val deathRate: Float = 1.4f
  // How far out of place a fading glyph may be and still be revived, as a fraction of line-height.
  private val reviveMaxDriftFactor: Float = 0.12f
  private var lastChangeUptimeMs: Long = 0L

  private var formatter: NumberFormat? = null
  private var currentFormatterLocale: Locale? = null
  private var currentGroupSep: Char = ','
  private var currentDecimalSep: Char = '.'
  private var currentMinusSign: Char = '-'

  private var activePlan: LayerPlan? = null
  private var debugPlan: LayerPlan? = null
  private var completionFired: Boolean = false
  private var textLayersNeedRebuild: Boolean = true
  private var maskPaintNeedsUpdate: Boolean = true

  // Full-text layers cached as RenderNode (API 29+) — used by the block debug renderer.
  private var oldTextNode: RenderNode? = null
  private var newTextNode: RenderNode? = null
  private var cachedOldFormatted: String? = null
  private var cachedNewFormatted: String? = null

  // ── Continuous presence model (default PER_GLYPH renderer) ──
  //
  // Each logical column (units, tens, …, separators) is keyed by a stable id (see
  // TransitionLogic.KeyedSlot) and owns a small set of GLYPHS. A glyph is not a phase timer — it
  // is a persistent little physical object with a PRESENCE p ∈ [0,1] sprung toward 0 or 1, and
  // every visual property is a pure function of its current p (see TransitionLogic's presence
  // helpers). Nothing ever restarts: a retarget only moves each glyph's goal.
  //
  // That is the whole difference from the previous phase-timer model, and it is what the reference
  // does. Spam a preset and the old model replayed a full-amplitude 0→1 lifecycle per press, so
  // the number snapped crisp between presses and every change read flat and direct. Here a glyph
  // interrupted at p = 0.4 on its way out simply springs back from 0.4 with its velocity intact —
  // the "back" — and, because presses arrive faster than the spring settles, presence hovers
  // mid-range and the composition stays the soft grey mass the reference shows.
  private class GlyphState(var ch: String) {
    var p: Float = 0f              // presence; overshoots past 1 on the settle bounce
    var v: Float = 0f              // presence velocity, carried across every retarget
    var target: Float = 1f         // 0 = should be gone, 1 = should be settled
    // Position along the roll axis, in units of the travel distance: −1 = waiting above (for an
    // increment), 0 = at the baseline, +1 = gone below. It is its OWN sprung state, not a function
    // of presence.
    //
    // Deriving it from presence meant a glyph could only ever retrace the way it came, because
    // reversing the axis mid-flight would have teleported it across the baseline — so the sign was
    // pinned unless presence had reached ~1. In a continuous roll (hold "+") no glyph ever gets
    // that far before the next digit arrives, so every one of them bounced back out of the top
    // instead of continuing through and out of the bottom: the number trembled rather than rolled.
    // With its own coordinate, "keep going" is just a new target and stays perfectly continuous.
    var off: Float = 0f
    var offV: Float = 0f
    var offTarget: Float = 0f
    /**
     * Where this glyph heads when it leaves. The offset target itself is DERIVED each tick from the
     * live presence target, so movement and fading are released together by the cascade.
     *
     * Setting the offset target directly at schedule time let a glyph travel while its presence
     * target was still queued behind an exit stagger: on 9,999 -> 1,000 the outgoing nines slid
     * upward at full presence, perfectly sharp, before they began to fade.
     */
    var exitOff: Float = 0f
    /**
     * This departure is a structural DEATH (a column disappearing, or a glyph displaced by one on a
     * structural change) rather than the outgoing half of a roll. The two are separate lifecycles in
     * the reference (METHODOLOGY §5.2), and only the death is a mirror of a structural birth: same
     * distance, same spring rates, opposite sign. A roll's departure keeps the fade-fast/travel-slow
     * bias that stops a continuous roll piling up crisp ink above the baseline.
     */
    var structuralExit: Boolean = false
    /**
     * This glyph is leaving a column that is RECEIVING another one, in a structural change — a
     * handover, not a death. It keeps a roll's softness (the reference's substitutions are soft
     * where its deaths stay crisp) but a death's PACE, because in a structural change the reference
     * holds the old composition still and then drops it quickly. The two properties are separate:
     * one is the blur curve, the other is the spring, and this used to force them together.
     */
    var substitutionExit: Boolean = false
    /**
     * This glyph was created as a structural BIRTH, so its presence and position use the common
     * SwiftUI spring instead of the legacy independent roll springs.
     */
    var structuralBirth: Boolean = false
    /**
     * Ordinal of this glyph's COLUMN among the columns that receive a glyph, left→right (0 =
     * leftmost, −1 = this column receives nothing and is simply going away).
     *
     * This is what both halves of the reference's wave are indexed by — the exit's slowness and the
     * birth's — because what the wave sweeps across is the columns that are changing CONTENT. It is
     * kept on the glyph, not derived at schedule time, because the integrator needs it every tick.
     *
     * It is deliberately not the cascade's own column ordinal, which also counts the columns being
     * deleted: on 1,000 -> 877 that put the units at index 4 and slowed it by (1 + 0.265·4)², where
     * the reference only ever spreads its wave over the three columns a viewer sees change. In a
     * plain roll every changing column receives a glyph, so the two are the same and nothing moves.
     */
    var waveIndex: Int = 0
    var travelMul: Float = 1f      // roll = 1; a structural birth spawns much further out
    var minScale: Float = 0.9f
    // Exponent of presenceScale's convex curve. Birth/exit keep the reference-fitted 2.2; a plain
    // roll's glyphs get rollScaleExponent instead (see presenceScale's doc for why the two differ).
    var scaleExponent: Float = 2.2f
    var driftMul: Float = 0f       // outward X displacement while absent (births/deaths only)
    // X is centre-relative (glyph centre minus its layout's half width) so it is independent of
    // the view's measured width, which changes underneath us on requestLayout. It springs to its
    // target instead of riding a shared reflow clock that a retarget would restart.
    var xRel: Float = 0f
    var xv: Float = 0f
    var xRelTarget: Float = 0f
    var w: Float = 0f              // glyph advance, for the drift/spawn displacement
    var delay: Float = 0f          // cascade stagger before `pendingTarget` is applied
    var pendingTarget: Float = -1f
    var node: RenderNode? = null
    // Lane on a continuous plain-roll tape. NaN means the glyph is on the structural lifecycle
    // path and its independent p/off springs remain authoritative.
    var tapeLane: Float = Float.NaN
    // The visible lane may follow the front of a fast tape. Keeping this separate prevents the
    // latest digit from being left behind while the physical phase continues through intermediate
    // values; it converges to this target lane when the roll decelerates.
    var tapeLaneTarget: Float = Float.NaN
    var tapeSoftness: Float = 1f

    /**
     * Where this glyph is headed once its stagger elapses. Scheduling MUST read this rather than
     * `target`: during the stagger window a column's outgoing glyph is already aimed at 0 while
     * the incoming one has not been released yet, so `target` alone briefly reports that the
     * column wants nothing — and a retarget landing in that window used to mistake the column for
     * empty, add a second arriving glyph, and leave two glyphs settling on top of each other
     * permanently.
     */
    val effectiveTarget: Float get() = if (pendingTarget >= 0f) pendingTarget else target

    /** Aim at [t] now if this glyph is unstaggered, or when its stagger elapses if it is. */
    fun aimAt(t: Float) { if (delay > 0f) pendingTarget = t else { target = t; pendingTarget = -1f } }

    /**
     * Arm a cascade stagger — but never restart one that has not fired yet.
     *
     * A glyph waiting out its stagger sits at full presence, perfectly sharp and motionless. Every
     * retarget used to reset that wait, so under repeated changes the columns with the longest
     * delay (the rightmost ones) had their exit pushed back before it ever began: measured on a
     * 1 ↔ 9,999 spam, the two leading columns were sharp in 1-2% of frames while the last three
     * were sharp in 71-81% — the number visibly split into a churning left half and a frozen right
     * half. Letting the original countdown run means a queued change always eventually fires.
     */
    fun armDelay(d: Float) { if (pendingTarget < 0f) delay = d }
  }

  private class Column {
    val glyphs = ArrayList<GlyphState>(3)
    // Simple continuous roll state. Unlike the legacy per-glyph tape, phase is an absolute digit
    // position and the visible glyphs are derived from it every frame.
    var simpleRollActive: Boolean = false
    var simplePhase: Float = 0f
    var simpleVelocity: Float = 0f
    // The spring remains the interruption-safe phase clock, while this is the phase actually
    // painted after the measured brake/hold/final-lane choreography has been applied.
    var simpleVisualPhase: Float = 0f
    var simpleVisualVelocity: Float = 0f
    var simpleTarget: Float = 0f
    var simpleDirection: Int = 1
    var simpleXRel: Float = 0f
    var simpleWidth: Float = 0f
    var simpleTargetChar: String = ""
    var simpleAgeSeconds: Float = 0f
    var simpleIdleSeconds: Float = 0f
    var simpleBurstSteps: Int = 0
    // One persistent position/velocity pair for every glyph on a normal roll. A new value changes
    // only [tapeTarget]; every lane derives its position from phase - lane.
    var tapeActive: Boolean = false
    var tapePhase: Float = 0f
    var tapeVelocity: Float = 0f
    var tapeTarget: Float = 0f
    /**
     * The lane the tape is OWED but has not been released to yet, and the time left on that hold.
     *
     * This is the roll's left→right wave, and it is the only thing in the tape that is not
     * immediate. It holds the TARGET, never the phase: the spring keeps integrating toward whatever
     * target it already has, so a glyph on screen is neither restarted nor teleported and the merge
     * invariant the tape exists for is untouched. A change arriving while a hold is running flushes
     * it first (see the flush in `scheduleRollTape`), so a stream of changes can never park a column
     * — each one releases the last one's owed lane before arming its own, and the crowding gate
     * takes the hold to zero long before a press-and-hold gets near that.
     */
    var tapePendingTarget: Float = 0f
    var tapeDelay: Float = 0f
    var tapeDirection: Int = 1
    var tapeWaveIndex: Int = 0
    /** The glyph this column is resolving toward, counting one still waiting out its stagger. */
    fun incoming(): GlyphState? = glyphs.lastOrNull { it.effectiveTarget >= 0.5f }
  }

  /**
   * A roll is a handoff between two glyphs, not a stack of every value crossed by the tape.
   * Keeping this bound at two is important: on a fast hold the target is retargeted in place,
   * otherwise Android paints the intermediate 1, 2, 3... values that SwiftUI presents as one
   * compact two-glyph mass.
   */
  private val MAX_GLYPHS_PER_COLUMN = 2

  private val columns = LinkedHashMap<String, Column>()
  private var slotTargetText: String = "0"   // last scheduled target

  private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    textAlign = Paint.Align.LEFT
  }

  // ── Per-frame scratch and paint-derived caches ──
  //
  // Everything below is derived from `textPaint` and changes only when the paint does, but was
  // being recomputed inside the draw and tick loops — where the cost is paid once per glyph per
  // frame and so grows with the digit count.

  /** Reused by the spring integrator so a tick allocates nothing. */
  private val springOut = FloatArray(2)

  /** `Paint.getFontMetrics()` allocates on every call; these are refreshed by [recalcTextPaint]. */
  private var fmAscent = 0f
  private var fmDescent = 0f
  private var textHeightPx = 0f

  /** Advance width per glyph string, cleared whenever the paint changes. */
  private val advanceCache = HashMap<String, Float>(24)

  /**
   * Bumped by [recalcTextPaint]. A glyph's recorded [RenderNode] is only valid for the generation
   * it was recorded in, so this is what invalidates the recordings when size, weight, colour or
   * typeface change.
   */
  private var paintGeneration = 0

  /** The width [settleTo] last asked for, so it only asks again when the answer changes. */
  private var lastDesiredWidth = -1

  // Soft vertical mask paint (reused, updated when font metrics change) — block renderer only.
  private val verticalMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
  }

  private val overscanHorizontal = 4f

  // ── Central visual knobs — tune against on-device iOS comparison. ──
  // travelFactor: vertical roll as a fraction of line-height. Kept deliberately SHORT: a long
  //   travel separates the outgoing/incoming glyphs into two distinct dark ghosts (a tall black
  //   column); a short travel makes them overlap near the baseline so they blend into ONE soft
  //   grey mass — the compact, readable roll SwiftUI produces (verified via iOS/Android frame diff).
  // How far past the resting digit the rolling ink reaches, measured on an isolated 2,576 -> 2,577
  // over the units column alone, in glyph heights, against a verified-still reference frame:
  //
  //     reference   0.059 above   0.102 below
  //     0.15        0.019         0.057        (too tight — the column barely leaves its box)
  //     0.95        0.133         0.167        (too far, both ways)
  //
  // Interpolating between the two measured points puts both numbers on the reference at 0.45, and
  // the two agree to within 0.05 of each other, which is the check that the relationship is linear.
  //
  // 0.95 came from a measurement taken inside a band that included the "+" button. A button is a
  // hard-edged object, so it reported the same reach at every ink threshold — the signature that
  // was read at the time as "the reference's arriving glyph is a solid digit a full glyph-height up"
  // and is really just a button. The reference's roll is compact, as the original note here said.
  // Any band used for this has to stop above y = 0.55 of the screen, where the buttons start.
  // 2026-08-02: 0.65 -> 0.29, measured rather than guessed (.agent/IOS_GROUND_TRUTH.md).
  //
  // At each column's floor the reference keeps its crossing pair inside 1.18-1.22 glyph heights;
  // at 0.65 ours spread over 1.59-1.68, at matching edge energy — the blur was already right, the
  // pair was simply too far apart. Recording both platforms at 0.65 and at 0.47 gives the line
  // `extent = glyphHeight + 1.21 x travel`, and solving it for the reference's extent lands on
  // 0.302 / 0.294 / 0.274 for the three columns independently.
  //
  // This undoes 85de2d2, which raised it 0.25 -> 0.65 on the screen-recording pipeline, inside that
  // pipeline's own noise. Note what it does NOT fix: the ink floor barely moved between 0.65 and
  // 0.47, so the depth gap against the reference is a separate defect with a separate cause.
  private val travelFactor = 0.29f
  // Peak radius as a fraction of line-height. A small increase merges adjacent tape lanes into one
  // mass without using blur to conceal a timing mismatch.
  private val blurFactor = 0.18f
  // Headroom around a glyph's own RenderNode, in multiples of the peak blur radius. A DECAL blur
  // treats everything outside the node as transparent, so the falloff has to fit inside the node
  // or the halo is sliced off square at its edge; three radii is past where the Gaussian is visible.
  private val BLUR_MARGIN_FACTOR = 3f
  // Extra height (per side, × line-height) reserved so the blur/roll can breathe. It has to cover
  // the full travel plus the blur's halo, or the glyph waiting above the line is sliced off square
  // at the view's edge — which would hide exactly the ink this travel exists to show.
  private val verticalHeadroomFactor = 0.60f
  // Depth scale: a rolling glyph shrinks toward this as it leaves and grows back on arrival,
  // so the motion reads as a digit rotating on a cylinder rather than a flat 2D guillotine.
  // Depth: the leaving glyph shrinks more (it recedes), the arriving one barely scales — it
  // resolves by coming into focus rather than by travelling or growing a lot.
  private val exitMinScale = 0.74f
  // A born glyph is a BLOB when it spawns: small + heavily blurred, displaced from its final
  // spot (outward + along the roll axis), then it slides in and comes into focus. A tame
  // fade-in-place read as "the final number at low opacity" — too recognisable.
  // Fitted to the reference growth: a structural birth bottoms out around 0.6 of full size.
  private val enterMinScale = 0.6f
  // A multiplier on travelFactor, so it moves whenever that does and a structural birth keeps
  // spawning from the 0.48 of a line height measured on the reference (0.45 × 1.07).
  private val enterTravelFactor = 1.07f
  // Horizontal spawn displacement of a born glyph, as a fraction of its width: it appears
  // displaced toward the composition's growing edge (e.g. the trailing "5" from the right) and
  // slides to its slot. Direction derived from geometry — no hardcoding.
  private val enterSpawnXFactor = 0.05f
  // Small outward drift of a leaving glyph (fraction of its width, away from the new centre) —
  // the reference's dying digits spread slightly apart as they fade, they don't converge.
  private val exitDriftOut = 0.18f
  // A structural DEATH is the structural birth mirrored, but slightly shorter: measured on the
  // reference (1,000 -> 1, 1,000 -> 999, 1.9 -> 2.0), a dying glyph's ink covers 0.31-0.37 of a
  // line height along the roll axis before it is gone, against the 0.48 a born glyph spawns from.
  // A full-length mirror (1.0) measured 0.41-0.59 and threw visibly more ink off the line than the
  // reference does; 0.8 of the birth's distance lands inside the measured band.
  private val exitTravelOfBirth = 0.8f

  private val travel: Float get() = getTextHeight() * travelFactor

  init {
    recalcTextPaint()
    recalcFormatter()
  }

  // ── Measurement ──

  /**
   * Horizontal headroom, per side: a dying glyph drifts outward and every moving glyph carries a
   * blur halo, so the ink reaches past the text's own advance and the outermost digits were being
   * clipped at the view's edge mid-transition.
   */
  private fun hHeadroom(): Float = textHeightPx * blurFactor * 2f + overscanHorizontal

  /**
   * The width this view asks for when nothing is animating.
   *
   * [settleTo] compares against this rather than deriving its own figure. It used to measure the
   * bare text with no headroom and compare that to `measuredWidth`, which includes headroom on
   * both sides — about 175 px of it at 88 sp. The two could not agree, so every settle ended with
   * a `requestLayout()`, and the relayout blanked the view for a frame: press +, watch the roll
   * finish, then see the number flash out and back. It stayed hidden only because the JS-side
   * minimum used to be wider than both figures, which collapsed them onto the same number.
   */
  private fun settledDesiredWidth(text: String): Int =
    ceil(advanceOf(text) + 2f * hHeadroom() + paddingLeft + paddingRight).toInt()

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val textHeight = textHeightPx
    val desiredWidth = if (animator != null && activePlan != null) {
      ceil(max(activePlan!!.oldWidth, activePlan!!.newWidth) + 2f * hHeadroom() + paddingLeft + paddingRight).toInt()
    } else {
      settledDesiredWidth(if (settledText.isNotEmpty()) settledText else "0")
        .also { lastDesiredWidth = it }
    }
    // Headroom so the roll + blur halo aren't hard-clipped at the view's own bounds.
    val vHeadroom = textHeight * verticalHeadroomFactor
    val desiredHeight = ceil(textHeight + 2f * vHeadroom + paddingTop + paddingBottom).toInt()
    setMeasuredDimension(
      resolveSize(maxOf(desiredWidth, suggestedMinimumWidth), widthMeasureSpec),
      resolveSize(maxOf(desiredHeight, suggestedMinimumHeight), heightMeasureSpec)
    )
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    recalcFormatter(); recalcTextPaint()
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow(); cancelAnimation()
  }

  // ── Progress resolution ──

  private fun resolveProgress(): Float =
    if (debugManualProgress >= 0f) debugManualProgress.coerceIn(0f, 1f) else animationProgress

  private fun resolveStrategy(): TransitionStrategy = when (debugTransitionStrategy.lowercase()) {
    "whole_run" -> TransitionStrategy.WHOLE_RUN
    "changed_run" -> TransitionStrategy.CHANGED_RUN
    // Default: per-glyph is the faithful SwiftUI behaviour. WHOLE_RUN / CHANGED_RUN
    // remain available as debug strategies for comparison.
    else -> TransitionStrategy.PER_GLYPH
  }

  private fun resolvePlan(): LayerPlan? {
    if (debugManualProgress >= 0f) return debugPlan ?: activePlan
    return activePlan
  }

  // ── Drawing ──

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (width <= 0 || height <= 0) return

    val progress = resolveProgress()
    val scrubbing = debugManualProgress >= 0f
    // Key off the animator, not the progress value — the spring overshoots past 1 mid-bounce.
    val settledLike = animator == null

    // Per-glyph is the default, faithful path.
    if (resolveStrategy() == TransitionStrategy.PER_GLYPH) {
      if (scrubbing) {
        if (columns.isNotEmpty()) drawSlots(canvas, progress.coerceIn(0f, 0.999f)) else drawSettled(canvas)
      } else if (!settledLike && columns.isNotEmpty()) {
        drawSlots(canvas, null)
      } else {
        drawSettled(canvas)
      }
    } else {
      // Debug strategies: whole-run / changed-run block renderer.
      val plan = resolvePlan()
      if (settledLike || plan == null) {
        if (scrubbing && plan != null) {
          drawTransition(canvas, plan, progress.coerceIn(0f, 0.999f))
        } else {
          drawSettled(canvas)
        }
      } else {
        drawTransition(canvas, plan, progress)
      }
    }

    // Ground-truth capture: exactly what was drawn, on the frame it was drawn. No-op unless armed.
    NumericTextFrameRecorder.capture(this)
  }

  private fun drawSettled(canvas: Canvas) {
    textPaint.maskFilter = null
    textPaint.alpha = 255
    val cx = width / 2f
    val cy = height / 2f
    val bl = baselineY(cy)
    canvas.drawText(settledText, cx - advanceOf(settledText) / 2f, bl, textPaint)
  }

  // ── Per-slot renderer (faithful SwiftUI numericText) ──
  //
  // Every glyph is drawn purely from its own presence and X springs — there is no shared
  // transition progress, so there is nothing to rewind when the value changes mid-flight. A glyph
  // that is settled and still draws through the cheap sharp path; everything else goes through a
  // RenderNode carrying blur, depth scale, roll offset and crossfade alpha.

  private fun drawSlots(canvas: Canvas, scrub: Float?) {
    val bl = baselineY(height / 2f)
    val centreX = width / 2f
    val h = getTextHeight()
    val travelPx = h * travelFactor
    val maxBlurPx = h * blurFactor
    val nodeCapable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canvas.isHardwareAccelerated

    // Scrubbing (debug) has no per-glyph clock to read, so drive every glyph from the manual
    // progress: arriving glyphs run 0→1 with it, leaving glyphs run 1→0 against it.
    fun presenceOf(g: GlyphState): Float =
      if (scrub == null) g.p else (if (g.target >= 0.5f) scrub else 1f - scrub)

    // Pass 1 — settled, still glyphs. Sharp and cheap.
    textPaint.maskFilter = null; textPaint.alpha = 255
    for (c in columns.values) for (g in c.glyphs) {
      if (c.simpleRollActive) continue
      if (NumericTextFrameRecorder.excludes(g.target)) continue
      if (isStill(g, scrub)) drawGlyph(canvas, g.ch, centreX + g.xRel, bl)
    }

    // Pass 2 — everything in motion, each glyph a pure function of its own presence.
    for (c in columns.values) for (g in c.glyphs) {
      if (c.simpleRollActive) continue
      if (NumericTextFrameRecorder.excludes(g.target)) continue
      if (isStill(g, scrub)) continue
      val p = presenceOf(g)
      val pc = p.coerceIn(0f, 1f)
      val renderPc =
        if (g.structuralBirth && g.target >= 0.5f) {
          TransitionLogic.structuralArrivalVisualPresence(pc)
        } else {
          pc
        }
      // Softness leads presence, with a velocity term so a fast roll smears more than a slow one.
      //
      // A structural DEATH is the exception: it stays SHARP while it thins and only softens near
      // the end. Measured on 1,000 -> 1, where four glyphs die in sequence, the reference's peak ink
      // holds at 100% while total mass is already down to 79% and reaches 1.8x the mass by the time
      // it is a quarter gone — one glyph at full darkness while the others have left. Ours faded and
      // blurred them as a group (peak/mass ~1.05 throughout), which is what read as a grey smear.
      // The velocity term is what did it: a departure's presence velocity is highest the moment it
      // is released, so it went soft before it had lost any ink.
      val blurAmt = if (g.structuralExit && g.target < 0.5f) {
        TransitionLogic.deathBlur(pc)
      } else {
        // The velocity term is gated by how far the glyph has actually MOVED. Ungated it made the
        // roll's first frame its blurriest: a glyph's presence velocity peaks the instant it is
        // released, so a still, settled digit went soft before it had travelled a pixel. Read
        // frame-by-frame against the reference, iOS's first frame after a value change is still
        // sharp and the first thing that changes is the glyph's HEIGHT; the blur arrives with the
        // displacement, about 33 ms later. Blur therefore follows position, not the spring's state.
        val moved = TransitionLogic.smoothstep(0f, BLUR_VELOCITY_GATE, abs(g.off))
        val presenceBlur = max(
          TransitionLogic.presenceBlur(
            renderPc,
            if (g.tapeLane.isNaN()) changeSpacing else g.tapeSoftness
          ),
          (abs(g.v) / blurVelocityRef).coerceIn(0f, 1f) * 0.6f * moved
        )
        // A continuous tape is readable at the start and at the settle, but its middle must be a
        // moving mass. The tape phase is the real roll speed; using it here avoids the old failure
        // where the latest digit was perfectly sharp even while the tape was crossing several
        // lanes. This naturally clears again as the phase slows on the final digit.
        if (g.tapeLane.isNaN()) presenceBlur else max(
          presenceBlur,
          (abs(c.tapeVelocity) / rollTapeSoftVelocity).coerceIn(0f, 1f)
        )
      }
      // Adjacent tape lanes have complementary raw presence. Preserve that invariant through the
      // handoff; applying the structural Hermite curve to both sides made their summed opacity fall
      // to ~0.8 at the midpoint and produced a visible pulse instead of one stable mass.
      val visualPresence =
        if (g.tapeLane.isNaN()) TransitionLogic.presenceAlpha(renderPc) else renderPc
      val alpha = (visualPresence * (1f - blurAlphaDrop * blurAmt) * 255f)
        .toInt().coerceIn(0, 255)
      if (alpha <= 0) continue
      // Unclamped p: the spring's overshoot carries the glyph slightly past its baseline.
      val yOff = travelPx * g.travelMul * TransitionLogic.rollOffsetShape(g.off)
      val xDrift =
        (if (g.xRelTarget >= 0f) 1f else -1f) * g.w * g.driftMul * (1f - renderPc)
      val scale = TransitionLogic.presenceScale(renderPc, g.minScale, g.scaleExponent)
      val radius = maxBlurPx * blurAmt
      if (nodeCapable) {
        drawSlotGlyphNode(canvas, g, centreX + g.xRel + xDrift, bl, yOff, scale, alpha, radius)
      } else {
        drawGlyphBlurred(canvas, g.ch, centreX + g.xRel + xDrift, bl + yOff, alpha, radius * 0.6f)
      }
    }
    for (c in columns.values) {
      if (c.simpleRollActive) {
        drawSimpleRoll(canvas, c, centreX, bl, h, maxBlurPx)
      }
    }
    textPaint.maskFilter = null; textPaint.alpha = 255
  }

  /** Draw one continuous digit tape from its absolute phase; no per-update glyph lifecycle. */
  private fun drawSimpleRoll(
    canvas: Canvas,
    col: Column,
    centreX: Float,
    baseline: Float,
    lineHeight: Float,
    maxBlurPx: Float
  ) {
    val fullTravelPx = lineHeight * SIMPLE_ROLL_TRAVEL_FACTOR
    val speed = (abs(col.simpleVelocity) / SIMPLE_ROLL_BLUR_VELOCITY).coerceIn(0f, 1f)
    val fastKeep = TransitionLogic.simpleRollFastKeep(
      col.simpleIdleSeconds,
      col.simpleBurstSteps
    )
    val pairRamp = TransitionLogic.simpleRollPairRamp(col.simpleAgeSeconds)
    val alphaRamp = TransitionLogic.simpleRollAlphaRamp(col.simpleAgeSeconds)
    val blurRamp = TransitionLogic.simpleRollBlurRamp(col.simpleAgeSeconds)
    // SwiftUI's launch trails its physical target by about half a lane, then catches up as the
    // strip accelerates. This is presentation-only: retargeting still uses the uninterrupted
    // spring phase, so repeated updates cannot accumulate the lag.
    val phase = col.simpleVisualPhase - col.simpleDirection *
      TransitionLogic.simpleRollLaunchLag(col.simpleAgeSeconds, col.simpleBurstSteps)
    // SwiftUI opens the vertical lane gradually: its first 2-3 sampled frames still read as one
    // source digit. Applying the full lane travel immediately exposed two hard, distant
    // outlines on Android before blur had time to develop.
    val travelPx = fullTravelPx * pairRamp
    val speedPair = (speed * pairRamp * fastKeep).coerceIn(0f, 1f)
    val speedBlur = (speed * blurRamp * fastKeep).coerceIn(0f, 1f)
    val speedAlphaAmount = (speed * alphaRamp * fastKeep).coerceIn(0f, 1f)
    val speedAlphaDrop = SIMPLE_ROLL_SPEED_ALPHA_DROP_EARLY +
      (SIMPLE_ROLL_SPEED_ALPHA_DROP_FAST - SIMPLE_ROLL_SPEED_ALPHA_DROP_EARLY) * blurRamp
    val speedAlpha = 1f - speedAlphaDrop * speedAlphaAmount
    val finalProgress = TransitionLogic.simpleRollFinalProgress(
      col.simpleIdleSeconds,
      col.simpleBurstSteps
    )
    val finalBlur = TransitionLogic.simpleRollFinalBlur(
      col.simpleIdleSeconds,
      col.simpleBurstSteps
    )
    val finalAlpha = TransitionLogic.simpleRollFinalAlpha(
      col.simpleIdleSeconds,
      col.simpleBurstSteps
    )
    val settlingPairBlend = TransitionLogic.simpleRollSettlingPairBlend(
      col.simpleIdleSeconds,
      col.simpleBurstSteps
    )
    val readableHold = TransitionLogic.simpleRollIsBurst(col.simpleBurstSteps) &&
      fastKeep <= 0.10f && finalProgress < 0f

    // The main sample is the outgoing lane plus the lane immediately ahead. During cruise both
    // stay populated across the whole hand-off; after the first crossing a faint third lane may
    // remain behind them to reproduce the reference's short exposure trail.
    val outgoingIndex =
      if (col.simpleDirection > 0) floor(phase).toInt() else ceil(phase).toInt()
    val laneProgress = abs(phase - outgoingIndex.toFloat()).coerceIn(0f, 1f)
    val handoffBell = 4f * laneProgress * (1f - laneProgress)
    // At cruise there is always a neighbouring glyph, including at an exact integer phase. The
    // previous handoff bell reduced this to zero every time the phase crossed an integer, causing
    // the measured one/two-glyph flicker. Pair-centre compensation below keeps this full pair from
    // reintroducing the old vertical centroid sawtooth.
    val pairBlend = max(speedPair, settlingPairBlend)
    val startPhase = col.simpleTarget - col.simpleDirection * col.simpleBurstSteps
    val travelledFromStart =
      (col.simpleDirection * (phase - startPhase)).coerceAtLeast(0f)
    val trailRamp = if (TransitionLogic.simpleRollIsBurst(col.simpleBurstSteps)) {
      TransitionLogic.smoothstep(
        SIMPLE_ROLL_TRAIL_START,
        SIMPLE_ROLL_TRAIL_FULL,
        travelledFromStart
      )
    } else {
      0f
    }
    val trailingPresence = SIMPLE_ROLL_FAST_TRAIL_PRESENCE * speedPair * trailRamp
    val handoffAlphaLift = when {
      TransitionLogic.simpleRollIsBurst(col.simpleBurstSteps) && finalProgress >= 0f ->
        1f + SIMPLE_ROLL_BURST_HANDOFF_ALPHA_LIFT * handoffBell
      TransitionLogic.simpleRollIsBurst(col.simpleBurstSteps) ->
        1f + SIMPLE_ROLL_BRAKE_ALPHA_LIFT_PER_BLEND * settlingPairBlend
      !TransitionLogic.simpleRollIsBurst(col.simpleBurstSteps) ->
        1f + SIMPLE_ROLL_SINGLE_HANDOFF_ALPHA_LIFT * handoffBell * (1f - speedPair)
      else -> 1f
    }
    // The nonlinear presence/scale curves otherwise pull the populated sample alternately toward
    // its upper and lower member. Compensate it as one unit while fast, including the faint third
    // trail, so its measured centre does not jump when the lanes advance.
    var predictedWeight = 0f
    var predictedMoment = 0f
    var predictedShiftWeight = 0f
    for (pairIndex in -1..1) {
      val index = outgoingIndex + pairIndex * col.simpleDirection
      val offset = index - phase
      val rawPresence = (1f - abs(offset)).coerceIn(0f, 1f)
      val presence = if (pairIndex < 0) {
        trailingPresence
      } else {
        rawPresence + (SIMPLE_ROLL_FAST_PAIR_PRESENCE - rawPresence) * pairBlend
      }
      if (presence <= 0f) continue
      val rawScale = TransitionLogic.presenceScale(presence, rollDepthMin, rollScaleExponent)
      val scale = 1f + (rawScale - 1f) * pairRamp
      val weight = TransitionLogic.presenceAlpha(presence) * scale * scale
      val relativeY = -col.simpleDirection * travelPx *
        TransitionLogic.rollOffsetShape(offset)
      predictedWeight += weight
      // Canvas scales both the lane translation and the glyph around the text baseline. The latter
      // matters just as much: a smaller digit's own ink centre moves down toward the baseline. The
      // old prediction omitted that term and left the fast strip 0.12-0.20 line-heights below iOS.
      val glyphCentreFromBaseline = (fmAscent + fmDescent) * 0.5f
      predictedMoment +=
        (relativeY * scale + (scale - 1f) * glyphCentreFromBaseline) * weight
      predictedShiftWeight += scale * weight
    }
    val desiredCentre = col.simpleDirection * lineHeight * SIMPLE_ROLL_CENTER_BIAS_FACTOR
    val fullCentreCorrection = if (predictedShiftWeight > 0f) {
      (desiredCentre * predictedWeight - predictedMoment) / predictedShiftWeight
    } else {
      0f
    }
    val centreCorrection = fullCentreCorrection * pairBlend
    for (pairIndex in -1..1) {
      val index = outgoingIndex + pairIndex * col.simpleDirection
      val offset = index - phase
      val rawPresence = (1f - abs(offset)).coerceIn(0f, 1f)
      val presence = if (pairIndex < 0) {
        trailingPresence
      } else {
        rawPresence + (SIMPLE_ROLL_FAST_PAIR_PRESENCE - rawPresence) * pairBlend
      }
      if (presence <= 0f) continue
      val alpha =
        (TransitionLogic.presenceAlpha(presence) * speedAlpha * handoffAlphaLift *
          finalAlpha * 255f)
        .toInt().coerceIn(0, 255)
      if (alpha <= 0) continue
      val digit = ((index % 10) + 10) % 10
      val text = digit.toString()
      val rawScale = TransitionLogic.presenceScale(presence, rollDepthMin, rollScaleExponent)
      val scale = 1f + (rawScale - 1f) * pairRamp
      val presenceBlur = TransitionLogic.presenceBlur(presence)
      val laneBlur = when {
        readableHold -> presenceBlur * 0.75f
        finalProgress >= 0f -> presenceBlur
        else -> presenceBlur * blurRamp
      }
      val handoffBlur = when {
        readableHold -> 0f
        finalProgress >= 0f -> handoffBell
        else -> handoffBell * blurRamp
      }
      val blurBlend = max(
        max(speedBlur, finalBlur),
        max(laneBlur, handoffBlur * SIMPLE_ROLL_HANDOFF_BLUR_SCALE)
      )
      val radius = maxBlurPx * blurBlend.coerceIn(0f, 1f) * SIMPLE_ROLL_BLUR_SCALE
      // For an increment the outgoing digit leaves downward and the incoming digit arrives from
      // above; decrement is the exact mirror. The previous sign sent the incoming glyph out of the
      // visible band, which looked like a spring losing the digit.
      val y = baseline - col.simpleDirection * travelPx *
        TransitionLogic.rollOffsetShape(offset) + centreCorrection
      val x = centreX + col.simpleXRel

      canvas.save()
      canvas.scale(scale, scale, x, baseline)
      drawGlyphBlurred(canvas, text, x, y, alpha, radius)
      canvas.restore()
    }
  }

  /** Settled at full presence, with nothing pending and no residual motion → draw it sharp. */
  private fun isStill(g: GlyphState, scrub: Float?): Boolean =
    scrub == null && g.target >= 1f && g.pendingTarget < 0f && g.delay <= 0f &&
      g.p >= 0.999f && abs(g.v) < 0.01f && abs(g.xRel - g.xRelTarget) < 0.3f &&
      // offV matters now that an arrival bounces: it crosses its target at full speed, and without
      // this it would be drawn sharp and unshifted for that one frame, then jump back out.
      abs(g.off) < 0.004f && abs(g.offV) < 0.02f

  private fun drawGlyph(canvas: Canvas, text: String, centerX: Float, baseline: Float) {
    if (text.isEmpty()) return
    canvas.drawText(text, centerX - advanceOf(text) / 2f, baseline, textPaint)
  }

  /**
   * Draws one moving glyph through its own [RenderNode], carrying the blur (RenderEffect, API 31+),
   * the depth scale about the glyph's baseline, the roll translation and the crossfade alpha.
   *
   * The node is glyph-sized, not view-sized. A node covering the whole view meant every moving
   * glyph composited — and blurred — a full-view layer, so the fill cost was glyphs × view area
   * and a longer number cost proportionally more of the screen. It is now glyphs × glyph area,
   * with [BLUR_MARGIN_FACTOR] of headroom so a DECAL blur still has room to fall off inside the
   * node's own bounds instead of being cut at its edge.
   *
   * The glyph IS re-recorded every frame, at its true sub-pixel position, and that is deliberate.
   * Recording once and moving the node with a fractional `translationX` is cheaper — it was worth
   * about 12 ms of display-list work per frame at the median — but it resamples a raster that was
   * rasterised for a different sub-pixel phase, so a moving glyph is filtered while a settled one,
   * drawn straight onto the canvas, is not. The two do not match, and the frame where a glyph
   * crosses from one path to the other reads as a flash. Sub-pixel text positioning has to come
   * from the text call, not from a transform applied to a cached layer.
   */
  @SuppressLint("NewApi")
  private fun drawSlotGlyphNode(
    canvas: Canvas, g: GlyphState,
    cx: Float, bl: Float, translationY: Float, scale: Float, alpha: Int, blurRadiusY: Float
  ) {
    val ch = g.ch
    if (alpha <= 0 || ch.isEmpty()) return

    val advance = advanceOf(ch)
    val margin = ceil(textHeightPx * blurFactor * BLUR_MARGIN_FACTOR)
    val nodeW = ceil(advance + 2f * margin).toInt()
    val nodeH = ceil(textHeightPx + 2f * margin).toInt()

    // The node's box is placed on whole pixels; everything fractional stays inside the recording,
    // where the text call resolves it the same way the sharp path does.
    val left = Math.round(cx - margin - advance / 2f)
    val top = Math.round(bl + translationY - (margin - fmAscent))
    val localCentreX = (cx - margin - advance / 2f) - left + margin + advance / 2f
    val localBaseline = (bl + translationY - (margin - fmAscent)) - top + (margin - fmAscent)

    var node = g.node
    if (node == null) {
      node = RenderNode("slotGlyph")
      // A node holds exactly one glyph, so nothing inside it can overlap anything else inside it.
      // Left at its default, an alpha below 1 makes the framework rasterise it into an offscreen
      // buffer first so the alpha applies to the composite — a buffer per moving glyph per frame,
      // and another way for the two paths to disagree.
      node.setHasOverlappingRendering(false)
      g.node = node
    }
    node.setPosition(left, top, left + nodeW, top + nodeH)
    val rec = node.beginRecording()
    textPaint.alpha = 255; textPaint.maskFilter = null
    rec.drawText(ch, localCentreX - advance / 2f, localBaseline, textPaint)
    node.endRecording()
    node.pivotX = localCentreX
    node.pivotY = localBaseline
    node.translationX = 0f
    node.translationY = 0f
    node.scaleX = scale
    node.scaleY = scale
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      node.setRenderEffect(
        // Near-ISOTROPIC: the reference's blurred glyph is a round, out-of-focus bolla, not a
        // directional streak. Only a slight vertical bias remains to hint at the roll axis.
        if (blurRadiusY >= 0.8f)
          RenderEffect.createBlurEffect(max(1f, blurRadiusY * 0.85f), blurRadiusY, Shader.TileMode.DECAL)
        else null
      )
    }
    node.alpha = alpha / 255f
    canvas.drawRenderNode(node)
  }

  // Isotropic-blur fallback for old devices / software canvas; allocates a BlurMaskFilter.
  private fun drawGlyphBlurred(canvas: Canvas, text: String, centerX: Float, baseline: Float, alpha: Int, radius: Float) {
    if (text.isEmpty() || alpha <= 0) return
    textPaint.alpha = alpha
    textPaint.maskFilter = if (radius >= 0.8f) BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) else null
    canvas.drawText(text, centerX - advanceOf(text) / 2f, baseline, textPaint)
    textPaint.maskFilter = null
  }

  // ── Per-slot scheduling ──

  private val slotMeasure: (String) -> Float = { advanceOf(it) }

  /** Centre-relative X of a keyed slot: independent of the view's measured width. */
  private fun xRelOf(ks: KeyedSlot): Float = ks.centerFromLeft - ks.totalWidth / 2f

  // Rebuild the column map from a settled string: one glyph per column, fully present and still.
  private fun seedSlots(committed: String) {
    columns.clear()
    val layout = TransitionLogic.layoutKeyedSlots(committed, currentGroupSep, currentDecimalSep, currentMinusSign, slotMeasure)
    for (ks in layout) {
      columns[ks.key] = Column().apply {
        glyphs.add(GlyphState(ks.char).apply {
          p = 1f; v = 0f; target = 1f
          xRel = xRelOf(ks); xRelTarget = xRel; w = ks.width
          minScale = rollDepthMin; scaleExponent = rollScaleExponent
        })
      }
    }
    slotTargetText = committed
  }

  /**
   * Schedule a stable-topology number change on one continuous tape per changed column.
   *
   * Returns false when the live composition cannot be represented safely as a tape — for example a
   * sign/grouping change or a structural lifecycle already in flight. The caller then uses the
   * existing structural scheduler without attempting to translate one model into the other.
   */
  private fun scheduleRollTape(newLayout: List<KeyedSlot>, newFormatted: String, dir: Int): Boolean {
    return scheduleSimpleRoll(newLayout, newFormatted, dir)
  }

  /**
   * Simple roll model: one absolute phase per digit column. The phase is continuous across every
   * update, and drawing derives the neighbouring digits from it. No update creates or retargets a
   * visible glyph.
   */
  private fun scheduleSimpleRoll(newLayout: List<KeyedSlot>, newFormatted: String, dir: Int): Boolean {
    val newKeys = newLayout.mapTo(LinkedHashSet(newLayout.size)) { it.key }
    val liveKeys = columns.keys.toCollection(LinkedHashSet(columns.size))
    if (newKeys != liveKeys) return false
    if (columns.values.any { it.tapeActive }) return false

    val fractionalDigits = newLayout.count { it.key.startsWith("F") }
    val displayedSteps = activePlan?.let {
      TransitionLogic.displayedStepCount(it.oldValue, it.newValue, fractionalDigits)
    } ?: 0
    val recentlyRolling = columns.values.any {
      it.simpleRollActive && it.simpleIdleSeconds <= SIMPLE_ROLL_BURST_GAP_SECONDS
    }
    val coalescedSteps = if (
      recentlyRolling && displayedSteps in 2..SIMPLE_ROLL_MAX_COALESCED_STEPS
    ) {
      displayedSteps
    } else {
      1
    }

    // This absolute tape maps lane n to digit n mod 10, so it is authoritative only for adjacent
    // numeric updates (including 9→0 carries and immediate reversals), plus a bounded number of
    // cadence updates coalesced by Fabric while that tape is already live. Preflight the whole
    // layout before mutating any column. An isolated arbitrary jump such as +123 remains on the
    // general per-glyph renderer.
    for (ks in newLayout) {
      val col = columns.getValue(ks.key)
      val committed = col.incoming()?.ch ?: return false
      val current = if (col.simpleRollActive) col.simpleTargetChar.ifEmpty { committed } else committed
      val currentIsDigit = current.length == 1 && current[0].isDigit()
      val targetIsDigit = ks.char.length == 1 && ks.char[0].isDigit()
      if (currentIsDigit && targetIsDigit) {
        if (current != ks.char) {
          val laneSteps = TransitionLogic.directedDigitSteps(
            current[0].digitToInt(),
            ks.char[0].digitToInt(),
            dir
          )
          if (laneSteps == 0 || laneSteps > coalescedSteps) return false
        }
      } else if (current != ks.char || col.simpleRollActive) {
        return false
      }
    }

    for (ks in newLayout) {
      val col = columns.getValue(ks.key)
      val current = col.incoming()?.ch ?: return false
      col.simpleXRel = xRelOf(ks)
      col.simpleWidth = ks.width

      val currentIsDigit = current.length == 1 && current[0].isDigit()
      val targetIsDigit = ks.char.length == 1 && ks.char[0].isDigit()
      if (!currentIsDigit || !targetIsDigit) {
        // Stable punctuation belongs to the layout, not to the digit tape. A changed separator is
        // structural and must use the existing structural scheduler.
        if (current != ks.char || col.simpleRollActive) return false
        col.incoming()?.let {
          it.xRelTarget = xRelOf(ks)
          it.w = ks.width
        }
        continue
      }

      val nextDirection = if (dir < 0) -1 else 1
      val currentTarget = if (col.simpleRollActive) {
        col.simpleTargetChar.ifEmpty { current }
      } else {
        current
      }
      val laneSteps = TransitionLogic.directedDigitSteps(
        currentTarget[0].digitToInt(),
        ks.char[0].digitToInt(),
        nextDirection
      )
      if (!col.simpleRollActive) {
        if (current == ks.char) continue
        val start = current[0].digitToInt().toFloat()
        col.simpleRollActive = true
        col.simplePhase = start
        col.simpleVelocity = 0f
        col.simpleVisualPhase = start
        col.simpleVisualVelocity = 0f
        col.simpleTarget = start
        col.simpleDirection = nextDirection
        col.simpleAgeSeconds = if (recentlyRolling) {
          SIMPLE_ROLL_CARRY_WARM_AGE_SECONDS
        } else {
          0f
        }
        col.simpleIdleSeconds = 0f
        col.simpleBurstSteps = laneSteps.coerceAtLeast(1)
      } else if (col.simpleTargetChar == ks.char) {
        continue
      } else {
        // A later target continues from exactly what was painted. During the measured readable
        // hold that phase intentionally differs from the hidden spring; rebasing here prevents a
        // fast double tap from jumping back to the spring when it resumes.
        col.simplePhase = col.simpleVisualPhase
        col.simpleVelocity = col.simpleVisualVelocity
        val joinsBurst = col.simpleDirection == nextDirection &&
          col.simpleIdleSeconds <= SIMPLE_ROLL_BURST_GAP_SECONDS
        col.simpleBurstSteps = if (joinsBurst) {
          col.simpleBurstSteps + laneSteps.coerceAtLeast(1)
        } else {
          laneSteps.coerceAtLeast(1)
        }
        if (!joinsBurst) col.simpleAgeSeconds = 0f
      }

      col.simpleDirection = nextDirection
      col.simpleTarget += col.simpleDirection * laneSteps.coerceAtLeast(1)
      col.simpleTargetChar = ks.char
      col.simpleIdleSeconds = 0f
    }

    slotTargetText = newFormatted
    return true
  }

  // Legacy per-glyph tape retained temporarily for comparison; simple rolls return above.
  private fun scheduleLegacyRollTape(newLayout: List<KeyedSlot>, newFormatted: String, dir: Int): Boolean {
    val newKeys = newLayout.mapTo(LinkedHashSet(newLayout.size)) { it.key }
    val liveKeys = columns.entries
      .filter { it.value.incoming() != null }
      .mapTo(LinkedHashSet(columns.size)) { it.key }
    if (newKeys != liveKeys) return false

    // A tape can start from the one settled glyph seed, or continue an existing tape. Do not enter
    // it from a structural transition whose independent springs are still resolving.
    val compatible = columns.values.all { col ->
      col.tapeActive || (
        col.glyphs.size == 1 &&
          col.glyphs[0].target >= 0.5f &&
          col.glyphs[0].pendingTarget < 0f &&
          abs(col.glyphs[0].p - 1f) < 0.004f &&
          abs(col.glyphs[0].off) < 0.01f
        )
    }
    if (!compatible) return false

    val changed = newLayout.filter { ks ->
      columns[ks.key]?.incoming()?.ch != ks.char
    }.sortedBy { xRelOf(it) }
    val waveByKey = changed.mapIndexed { index, ks -> ks.key to index }.toMap()

    for (ks in newLayout) {
      val col = columns.getValue(ks.key)
      val current = col.incoming()
      val continuedTape = col.tapeActive

      if (!col.tapeActive && current != null && current.ch != ks.char) {
        col.tapeActive = true
        col.tapePhase = 0f
        col.tapeVelocity = 0f
        col.tapeTarget = 0f
        col.tapePendingTarget = 0f
        col.tapeDelay = 0f
        current.tapeLane = 0f
        current.tapeLaneTarget = 0f
        current.tapeSoftness = 1f
      }

      if (current != null && current.ch == ks.char) {
        current.xRelTarget = xRelOf(ks)
        current.w = ks.width
        continue
      }
      if (!col.tapeActive) return false

      // A change arriving while this column is still holding FLUSHES the hold first, so everything
      // below — the reuse search, the lane arithmetic — sees a tape whose released target is current,
      // exactly as it did before holds existed. Without the flush a reversal inside the hold window
      // would look for a reusable lane against a phase that has not moved yet and create a second
      // glyph on the lane the first one already occupies.
      if (col.tapeDelay > 0f) {
        col.tapeDelay = 0f
        col.tapeTarget = col.tapePendingTarget
      }

      col.tapeDirection = dir
      col.tapeWaveIndex = waveByKey[ks.key] ?: 0

      // A quick direction reversal may reuse the glyph that is physically waiting on the new
      // arrival side. A same-direction digit cycle must not pull an old matching character
      // backwards through the strip, hence the signed-side test.
      val reusable = col.glyphs
        .filter { !it.tapeLane.isNaN() && it.ch == ks.char }
        .filter {
          TransitionLogic.rollTapeCanReuseLane(
            lane = it.tapeLane,
            phase = col.tapePhase,
            direction = dir,
            maxDistance = ROLL_TAPE_REUSE_DISTANCE
          )
        }
        .minByOrNull { abs(it.tapeLane - col.tapePhase) }

      // Lanes stack on the OWED target, not the released one, so a change landing inside another
      // column's hold still advances by exactly one lane.
      val nextLane = reusable?.tapeLane ?: (col.tapePendingTarget + dir)
      col.tapePendingTarget = nextLane
      val hold = changeSpacing * col.tapeWaveIndex * rollTapeStaggerSeconds
      if (hold > 0f) col.tapeDelay = hold else col.tapeTarget = nextLane

      // The tape has one role-independent visual curve. Target only identifies which glyph must be
      // retained and eventually settle; it no longer selects a different spring or scale formula.
      for (other in col.glyphs) {
        other.target = 0f
        other.pendingTarget = -1f
        other.delay = 0f
        other.structuralExit = false
        other.substitutionExit = false
        other.structuralBirth = false
        other.minScale = rollDepthMin
        other.scaleExponent = rollTapeScaleExponent
        other.travelMul = 1f
        other.driftMul = 0f
      }

      // If the target changes again before the incoming glyph has arrived, retarget that same
      // incoming object instead of appending another lane. Its phase and velocity remain those
      // of the live tape; only its character and destination lane change. A reversal can still
      // reuse the outgoing glyph above, which preserves the natural double-tap behaviour.
      val retargetableIncoming = current?.takeIf {
        continuedTape && it !== reusable && !it.tapeLane.isNaN() && it.effectiveTarget >= 0.5f
      }
      // The incoming glyph is the visible front of this one roll. When the counter advances
      // again, keep that glyph on its current front lane instead of throwing it one whole lane
      // farther away. The tape phase still advances to [nextLane], which gives us the acceleration
      // and the slow settle at the final digit without making the new digit disappear.
      val visualLane = if (reusable == null) {
        retargetableIncoming?.tapeLane ?: nextLane
      } else {
        nextLane
      }
      val g = reusable ?: retargetableIncoming ?: GlyphState(ks.char).apply {
        tapeLane = visualLane
        xRel = xRelOf(ks)
        xRelTarget = xRel
        p = TransitionLogic.rollTapePresence(
          offset = col.tapePhase - tapeLane,
          isTarget = true,
          direction = dir
        )
        off = col.tapePhase - tapeLane
        offV = col.tapeVelocity
      }.also { col.glyphs.add(it) }

      g.ch = ks.char
      g.target = 1f
      g.pendingTarget = -1f
      g.delay = 0f
      g.tapeLane = visualLane
      g.tapeLaneTarget = nextLane
      g.tapeSoftness = (1f - abs(col.tapeVelocity) / rollTapeSoftVelocity).coerceIn(0f, 1f)
      g.xRelTarget = xRelOf(ks)
      g.w = ks.width
      g.minScale = rollDepthMin
      g.scaleExponent = rollTapeScaleExponent
      g.travelMul = 1f
      g.driftMul = 0f
      g.structuralExit = false
      g.substitutionExit = false
      g.structuralBirth = false

      // The normal tap/hold regimes need at most the final target plus the lanes surrounding the
      // presentation phase. Prefer dropping a spent lane behind the motion; never drop the target.
      while (col.glyphs.size > MAX_GLYPHS_PER_COLUMN) {
        val spent = col.glyphs
          .filter { it !== g && !it.tapeLane.isNaN() }
          .maxByOrNull {
            (col.tapePhase - it.tapeLane) * col.tapeDirection
          } ?: break
        spent.node = null
        col.glyphs.remove(spent)
      }
    }

    slotTargetText = newFormatted
    return true
  }

  /**
   * Hand the current tape presentation back to the independent glyph springs.
   *
   * A topology change cannot stay on a per-column tape: columns may be born or disappear, so the
   * structural scheduler below must become authoritative. Keep every glyph's sampled p/off and
   * both velocities, but remove the tape marker before that scheduler retargets them. Without this
   * bridge, an old tape glyph kept being integrated from its lane forever and could never satisfy
   * the structural target (or the transition's settle condition).
   */
  private fun releaseRollTapes(dir: Int) {
    for (col in columns.values) {
      if (col.simpleRollActive) {
        val finalChar = col.simpleTargetChar.ifEmpty {
          (((col.simpleTarget % 10f) + 10f) % 10f).toInt().toString()
        }
        col.glyphs.clear()
        col.glyphs.add(GlyphState(finalChar).apply {
          p = 1f; v = 0f; target = 1f
          xRel = col.simpleXRel; xRelTarget = col.simpleXRel; w = col.simpleWidth
          minScale = rollDepthMin; scaleExponent = rollScaleExponent
        })
        col.simpleRollActive = false
      }
      if (!col.tapeActive) continue
      for (g in col.glyphs) {
        if (g.tapeLane.isNaN()) continue
        g.tapeLane = Float.NaN
        g.tapeSoftness = 1f
        g.delay = 0f
        g.pendingTarget = -1f
        g.offTarget = if (g.target >= 0.5f) 0f else dir * rollExitOff
        if (g.target < 0.5f) g.exitOff = g.offTarget
      }
      col.tapeActive = false
      col.tapePhase = 0f
      col.tapeVelocity = 0f
      col.tapeTarget = 0f
      col.tapePendingTarget = 0f
      col.tapeDelay = 0f
    }
  }

  // Diff the live columns against a new target and MOVE GOALS — never restart anything. A column
  // whose character changes retargets its current glyph to 0 and the incoming one to 1; if the
  // incoming character is still on screen as a fading glyph (the A→B→A of a preset spam), that very
  // glyph is reused and simply retargeted back to 1, so it springs back from wherever it is with
  // its velocity intact instead of being re-created at zero presence.
  private fun scheduleSlots(newFormatted: String, dir: Int) {
    val newLayout = TransitionLogic.layoutKeyedSlots(newFormatted, currentGroupSep, currentDecimalSep, currentMinusSign, slotMeasure)
    val newKeys = HashSet<String>(newLayout.size)

    // The crowding clock is read HERE, before the tape gets its chance, because a tape roll is a
    // change like any other. It used to be read after the tape's early return, so a burst of rolls
    // left `lastChangeUptimeMs` stale and the first structural change after one would see a long
    // quiet gap and treat itself as isolated.
    val now = android.os.SystemClock.uptimeMillis()
    val sinceLastChange = (now - lastChangeUptimeMs).toFloat()
    lastChangeUptimeMs = now
    val restFraction = ((sinceLastChange - cascadeSpamMs) / cascadeSpamMs).coerceIn(0f, 1f)
    changeSpacing = restFraction
    offsetSpacing = ((sinceLastChange - offsetCrowdMs) / offsetCrowdMs).coerceIn(0f, 1f)

    // STRUCTURAL classifier (numeric structure, not pixel width): when the integer digit count
    // changes (10→9, 999→1,000), a changed digit column is NOT a same-slot substitution — the
    // reference shows its old glyph leaving and the new one arriving as independent glyphs with
    // their own timing. Stable structure keeps the tight, overlapped roll.
    // Count only columns that are still resolving toward a character: during a spam the map also
    // holds columns already fading out from an earlier target, and counting those inflated the old
    // digit count and misclassified a plain roll as structural.
    val oldIntCount = columns.count { (k, c) -> k.startsWith("I") && c.incoming() != null }
    val newIntCount = newLayout.count { it.key.startsWith("I") }
    val structural = oldIntCount != newIntCount

    // A stable topology is a persistent strip, not a set of fresh enter/exit lifecycles. The
    // structural path below remains the authority for births, deaths, grouping and in-flight
    // structural transitions.
    if (!structural && scheduleRollTape(newLayout, newFormatted, dir)) return
    releaseRollTapes(dir)

    // Phases of the left→right cascade: each departure and each arrival is its own entry, ordered
    // by the X it happens at (centre-relative, so departures in the old composition and arrivals in
    // the new one share one axis). This interleaves arrivals among departures exactly like the
    // reference — 9,999→1: exit 9(-82) → exit ,(-47) → exit 9(-12) → ENTER 1(0) → exit 9(+35).
    // `stagger = false` means "release this glyph now": the cascade sequences a change starting
    // from rest, but a glyph that is already on screen must not be held back by it (see below).
    data class Phase(val g: GlyphState, val x: Float, val key: String, val isExit: Boolean, val stagger: Boolean = true)
    val phases = ArrayList<Phase>()

    // EXITS lose their cascade once the composition is already moving. A staggered exit is held at
    // full presence until its turn, so on a rapid spam the right-hand columns — which wait longest —
    // kept being reset before they ever started and stayed sharp: measured 46-64% of moving frames
    // against 1-3% on the left, a 44-point split the reference does not have (its 9,999 <-> 1 spam
    // is uniform, 13-28% across all five columns).
    //
    // ARRIVALS keep their cascade always. They wait at zero presence, so staggering them cannot pin
    // a column sharp — and it is what makes a continuous roll read as rolling rather than as every
    // column flipping in lockstep.
    // How much of the exit cascade survives, from how long ago the LAST change came in — not from
    // whether the composition happens to be at rest.
    //
    // The all-or-nothing "at rest" gate dropped the cascade for a preset that sets its value twice
    // 400 ms apart (measured on 1.9 -> 2.0: the reference holds the dying ".9" 150 ms, ours released
    // it at 0), and "at rest" could not recover in time anyway because the horizontal reflow spring
    // is deliberately slow (~0.4 s). What the gate is actually protecting against is changes
    // arriving FASTER than the cascade can play: a spam at 30-45 ms per change, where a staggered
    // exit is reset before its turn and so sits sharp — measured 46-64% of moving frames sharp on
    // the right-hand columns against 1-3% on the left, a split the reference does not have.
    // Elapsed time says that directly.
    for (ks in newLayout) {
      newKeys.add(ks.key)
      val col = columns.getOrPut(ks.key) { Column() }
      val current = col.incoming()
      if (current != null && current.ch == ks.char) {
        // Already resolving toward this character: only its destination moves.
        current.xRelTarget = xRelOf(ks); current.w = ks.width
        continue
      }
      // Reuse a still-fading glyph with the same character (the A→B→A reversal) before creating one.
      // Tried refusing to revive one that had already travelled most of the way out, on the theory
      // that hauling it back fights the roll — measured worse (47% of moving frames scrolling
      // downward against 63% for plain revival), so reuse stays unconditional.
      // …but only if it is still standing roughly WHERE the new layout wants it. A revival keeps
      // the glyph's live xRel, so one left over from a wider composition springs in sideways: on
      // 999 -> 1,000 followed by 99 -> 100, the zeros of "1,000" were revived for "100" from half a
      // line-height to the right, and the number read as digits sliding in from the sides instead
      // of rolling. Measured as ink sitting beside the settled digits, that case ran 19% at +67 ms
      // and 18% at +133 against the reference's 6% and 1.8% — while the same change from rest
      // matched it (3.8 / 1.6 vs 2.6 / 0.6). Same-layout revival, which is what the A→B→A of a
      // spam needs and what the earlier measurement defended, has a drift of zero and is untouched.
      val reviveMaxDrift = getTextHeight() * reviveMaxDriftFactor
      val revived = col.glyphs.lastOrNull {
        it.ch == ks.char && abs(it.xRel - xRelOf(ks)) <= reviveMaxDrift
      }
      val g = revived ?: GlyphState(ks.char).apply {
        p = 0f; v = 0f
        // Born on the arrival side. A structural birth spawns much further out and much softer;
        // a same-slot roll starts just off the baseline.
        off = -dir.toFloat(); offV = 0f; offTarget = off   // waiting on the arrival side
        exitOff = dir * rollExitOff
        // In a STRUCTURAL change every affected column is a lifecycle, not a roll (METHODOLOGY
        // §5.2) — including one that already existed. Requiring `current == null` here meant the
        // units column, which is the one carrying the old value, arrived as a short roll: fitted
        // per column on 1 -> 9,999 at matched opacity, its blur measured 0.03 of a line height
        // against the reference's 0.09, while every other column matched. That is the "the last
        // digits arrive unblurred" of the report.
        travelMul = if (structural) enterTravelFactor else 1f
        minScale = if (structural) enterMinScale else rollDepthMin
        scaleExponent = if (structural) 2.2f else rollScaleExponent
        driftMul = if (current == null && structural) enterSpawnXFactor else 0f
        xRel = xRelOf(ks)
      }
      g.w = ks.width
      g.xRelTarget = xRelOf(ks)
      g.exitOff = dir * rollExitOff          // where it will go if it later leaves
      g.structuralExit = false               // it is arriving; a later death re-arms this
      g.substitutionExit = false
      // Set on every arrival, not only on a fresh one: a revived glyph keeps the state of whatever
      // it was last time, and a birth's slowness must not leak into the roll that revives it.
      g.structuralBirth = structural && revived == null
      // A revived glyph keeps p/v (opacity continuity is the whole point of reviving one) and xRel
      // (the X spring retargets smoothly on its own). off/offV are reset to a clean arrival start,
      // but ONLY when the glyph has genuinely travelled — off already past REVIVE_OFF_RESET_THRESHOLD
      // from the baseline.
      //
      // Without this, a glyph that fully departed and is now parked at its exit asymptote
      // (off ≈ dir_old * rollExitOff, p ≈ 0, invisible) gets reused for a new arrival without ever
      // moving off: if the new direction happens to want the SAME side the glyph is already parked
      // on, there is nothing left to travel and the roll is invisible. Measured on two consecutive
      // taps from the same digit history: a fresh "+123" arrival's column swings 0.148 of a glyph
      // height, a second "+123" swings 0.095 — both a clearly visible roll — but the very next
      // "-123", reviving glyphs the increments had already parked, swings 0.011. Not a directional
      // bug: "10 -> 9" away from any revival matches the reference. It is this.
      //
      // The threshold exists because an EARLIER, unconditional version of this reset broke a
      // different case: a glyph only JUST marked to retire — still at p ≈ 1, off ≈ 0, fully sharp,
      // simply not the target character anymore — got yanked to the arrival's ±1 position while
      // still drawn at full opacity, which read as the settled number flashing to blank before the
      // roll cut to the new value. That glyph's off was near 0 precisely because it hadn't gone
      // anywhere yet, and near 0 is already exactly where a clean arrival start needs it.
      if (revived != null && abs(g.off) > REVIVE_OFF_RESET_THRESHOLD) {
        g.off = -dir.toFloat(); g.offV = 0f
      }
      if (revived == null) col.glyphs.add(g)

      // Retire EVERY glyph that is not the one arriving, and let each carry on down the strip on
      // its own. Nothing is merged.
      //
      // Collapsing the outgoing side into one "survivor" that absorbed the others' ink was
      // pathological on a continuous roll (hold "+" = one increment every 30 ms against a 267 ms
      // transition). The survivor was picked by highest presence, which was always the OLDEST
      // glyph; it kept being topped up and never left, while each newly arrived digit was deleted
      // at p ~ 0.05 before it could travel. Logged live: "0" pinned at off ~ +0.9 with p ~ 0.5 for
      // the whole hold — a motionless blurred blob at the bottom with nothing rolling past it.
      //
      // A fast roll is *supposed* to have several glyphs in flight at once; that strip of digits
      // sliding through is what reads as rotation, and it is what the reference shows.
      var newestRetired: GlyphState? = null
      for (other in col.glyphs) {
        if (other === g || other.effectiveTarget <= 0f) continue   // already on its way out
        // Where a ROLL's departure heads. Two units was set when departures faded four times faster
        // than they moved and so were invisible long before arriving; now that they linger (see
        // rollExitFadeRate) the same destination is visible all the way down, and a continuous roll
        // showed the outgoing digit sinking far below the line instead of staying centred.
        // rollOffsetShape's 1.43 power turns 2 units into 0.40 of a line height, where the template
        // fit puts the reference's outgoing glyph at 0.14 before it is gone.
        other.exitOff = dir * rollExitOff
        // A departing glyph always gets the STRUCTURAL exponent (2.2), never rollScaleExponent —
        // even in a plain roll. The two roles need opposite curves from the same formula: an
        // arrival wants a low slope at p = 0 (stays small early, so 0.55), but that same low
        // exponent gives presenceScale a slope that DIVERGES at p = 1 as presence starts falling,
        // which shrank the departing glyph within the first sliver of its departure — read on a
        // frame grid as "the old digit starts scaling and blurring down before the reference does".
        // 2.2 has the opposite property at that end: near-zero slope at p = 1, so the departure
        // stays full size for a while before it shrinks, matching what a departing glyph did before
        // rollScaleExponent existed. Without this, a glyph created as an arrival earlier in its life
        // (most of them) would carry 0.55 into its own departure, since scaleExponent otherwise
        // persists across a glyph's roles.
        other.scaleExponent = 2.2f
        if (structural) {
          other.minScale = exitMinScale; other.driftMul = exitDriftOut
          // A structural death is the birth run backwards: it covers the same 1 unit (× the birth's
          // travel factor) that a born glyph spawns from, at the same spring rate. Measured on the
          // reference, a dying glyph is still visible after ~0.3 line-heights of roll; ours moved
          // 0.04 and was gone, which read as fading in place rather than rolling out.
          other.travelMul = enterTravelFactor
          other.exitOff = dir * exitTravelOfBirth
          // …but this glyph is NOT dying: its column is right here, receiving a new character. It is
          // a SUBSTITUTION, so it hands over at a roll's pace and keeps `structuralExit` false.
          //
          // The rate it used to get (deathRate², the fastest spring in this file, fitted on
          // 1,000 -> 1 where four glyphs leave and nothing replaces them) evacuated a slot that
          // still had to be occupied: measured on 1,000 -> 877, the column's summed ink fell to
          // 0.40 of a glyph against the reference's 0.77, and the grid shows the middle slot empty
          // at +167 ms where the reference still has a whole digit crossing in it. Only a column
          // that is going away entirely is a death; those are re-armed in the vanishing-column loop
          // below, which is where a real death belongs.
          other.structuralExit = false
          other.substitutionExit = true
        }
        other.delay = 0f
        if (newestRetired == null || other.p > newestRetired.p) newestRetired = other
      }
      // Only the most present departure joins the cascade; the rest are already leaving.
      newestRetired?.let { phases.add(Phase(it, it.xRel, ks.key, isExit = true)) }

      // Safety cap. Spent glyphs drop out on their own at p ~ 0, which at this cadence keeps a
      // column near half a dozen; this only guards against an unbounded pile-up.
      while (col.glyphs.size > MAX_GLYPHS_PER_COLUMN) {
        val faintest = col.glyphs.filter { it !== g }.minByOrNull { it.p } ?: break
        faintest.node = null
        col.glyphs.remove(faintest)
      }
      g.delay = 0f
      g.pendingTarget = -1f
      // A FRESH glyph is parked at zero presence until the cascade releases it — it has nothing on
      // screen to preserve. A REVIVED one (the A→B→A of a spam) is already visible and heading out,
      // so it must be released immediately: parking it at 0 through a stagger would keep pushing it
      // away for the whole wait, and since the cascade gives the last column the longest stagger,
      // a rapid spam made the final digit decay to nothing and vanish outright.
      //
      // A fresh glyph may only be held back if the column is not left visibly gutted by the wait.
      // That is true when the outgoing glyph is still aimed at 1 (it stays present through its own
      // stagger and hands over), and also when there is no outgoing glyph at all — a column being
      // BORN is legitimately empty until its turn, and that is exactly what the cascade sequences.
      // Excluding births here killed the wave on a growth like 1 → 9,999: every new digit was
      // released at once and simply appeared.
      val fresh = revived == null
      val outgoingHoldsInk = newestRetired == null || newestRetired.target >= 0.5f
      if (fresh) g.target = 0f
      phases.add(Phase(g, g.xRelTarget, ks.key, isExit = false, stagger = fresh && outgoingHoldsInk))
    }

    // Columns that no longer exist: every glyph in them heads for zero presence, fading essentially
    // in place (measured against iOS, where a big shrink fades the old digits where they stand).
    for ((k, col) in columns) {
      if (newKeys.contains(k)) continue
      for (g in col.glyphs) {
        if (g.effectiveTarget <= 0f) continue
        g.minScale = exitMinScale; g.driftMul = exitDriftOut; g.scaleExponent = 2.2f
        g.travelMul = enterTravelFactor        // mirror of the structural birth (see above)
        g.exitOff = dir * exitTravelOfBirth
        g.structuralExit = true
        g.xRelTarget = g.xRel                  // frozen: dying glyphs do not ride the reflow
        g.delay = 0f
        phases.add(Phase(g, g.xRel, k, isExit = true))
      }
    }

    // LEFT→RIGHT cascade over ALL phases (ink-timeline measurements, 9,999→1 @60fps):
    //  • EXITS are CONTIGUOUS — iOS spaces them ~2.25 frames apart regardless of interleaved
    //    arrivals, so they are indexed by their own ordinal.
    //  • ENTERS keep the global positional index on a compressed stagger: the iOS "1" starts
    //    ~0.12s in, while the overlapping old ink is still ~60% present, so the centre never
    //    goes empty.
    //
    // The cascade is applied to EVERY transition, including one retargeting a still-moving
    // composition. It used to be skipped for any column already in motion — which meant a preset
    // that sets its value twice in quick succession, or any spam, released every column at once and
    // the whole number moved in lockstep instead of rippling. A stagger is safe now because it only
    // gates the target flip; the glyph keeps springing toward its current goal meanwhile.
    phases.sortBy { it.x }
    var entersSeen = 0
    // COLUMN ordinal, not phase ordinal: a changed column contributes an exit AND an arrival at the
    // same x, and the reference releases them on one clock keyed to WHERE the column is.
    // Measured per column on the reference (first frame each old column's own ink moves):
    //   1,000 -> 1     33 / 50 / 83 / 133 / 183 ms   (~45 ms per column)
    //   1,000 -> 999   17 / 50 / 100 / 133 / 167 ms  (~40 ms per column)
    // A dense exit ordinal plus the old `entersSeen * 0.5` handoff stretched ours to 17 / 33 / 117 /
    // 183 / 233 — the right-hand columns left ~65 ms apart instead of ~40. The handoff it encoded
    // (10 -> 9: the "0" holds until the "9" shows) is implicit here: a column's exit is keyed to its
    // own position, which already puts it after everything to its left.
    // Ordered by the leftmost x each column touches. Keying on the raw x does NOT work: a column's
    // departure sits at its OLD position and its arrival at its NEW one, so the two would count as
    // two separate columns and the ordinal would run at phase rate again.
    val colOrder = phases.groupBy { it.key }.entries
      .sortedBy { e -> e.value.minOf { it.x } }
      .map { it.key }
    val exitStep = if (structural) structuralStaggerSeconds else staggerSeconds
    val exitLead = if (structural) structuralExitLead else 0f
    val enterStep = if (structural) birthSpacingSeconds else enterSpacingSeconds
    // The columns that RECEIVE a glyph, left→right — what both halves of the wave are indexed by.
    // A column's departure and its arrival share the ordinal, because they are one handover.
    val waveOrder = phases.filter { !it.isExit }.map { it.key }
    for (ph in phases) {
      val columnIndex = colOrder.indexOf(ph.key)
      ph.g.waveIndex = waveOrder.indexOf(ph.key)
      when {
        ph.isExit -> {
          val lead = exitLead + if (ph.g.substitutionExit) substitutionExitLead else 0f
          ph.g.armDelay(restFraction * (lead + columnIndex * exitStep))
          ph.g.aimAt(0f)
        }
        // Already on screen: no stagger, come back now.
        !ph.stagger -> { ph.g.delay = 0f; ph.g.aimAt(1f); entersSeen++ }
        // ARRIVALS — structural births and same-slot rolls alike. `entersSeen` counts arrivals in
        // left-to-right order (phases are x-sorted), so it alone sets the ripple.
        //
        // Two things used to inflate this badly. A structural arrival also added the positional
        // index, and a roll used the GLOBAL phase index — which counts an exit *and* an arrival per
        // changed column, so it ran at roughly double rate. On 2,577 → 1,000 that put the last
        // column's start at ~0.5 s: longer than the 400 ms the example's presets leave between
        // their two values, so "1,000" never finished forming before it became 999.
        // A STRUCTURAL arrival's stagger collapses under crowding exactly as its exit's does. The
        // exits have always been scaled by `restFraction` and the arrivals never were, which was
        // harmless while the structural steps were 0 and is not now: a digit-count boundary crossed
        // during a press-and-hold would get staggered arrivals against unstaggered departures, and
        // that combination is precisely what put two whole compositions on screen at once when it
        // was tried deliberately (see structuralExitLead). Reasoned, not filmed — a hold that
        // crosses 9,999 is not scripted in the Showcase, so this guards a case no capture covers.
        // The roll's own spacing is untouched: `enterScale` is 1 whenever the change is not
        // structural.
        else -> {
          val enterScale = if (structural) restFraction else 1f
          ph.g.armDelay(enterLag + entersSeen * enterStep * enterScale)
          ph.g.aimAt(1f)
          entersSeen++
        }
      }
    }

    slotTargetText = newFormatted
  }

  private fun atRest(col: Column): Boolean =
    !col.simpleRollActive && col.glyphs.all { isSettled(it) }

  private fun isSettled(g: GlyphState): Boolean =
    g.delay <= 0f && g.pendingTarget < 0f && abs(g.p - g.target) < 0.004f && abs(g.v) < 0.03f &&
      abs(g.off - g.offTarget) < 0.01f && abs(g.offV) < 0.05f &&
      abs(g.xRel - g.xRelTarget) < 0.35f && abs(g.xv) < 2f

  private fun tickSlots(dt: Float) {
    val colIt = columns.iterator()
    while (colIt.hasNext()) {
      val col = colIt.next().value
      if (col.simpleRollActive) {
        col.simpleAgeSeconds += dt
        col.simpleIdleSeconds += dt
        TransitionLogic.springIntegrateInto(
          col.simplePhase,
          col.simpleVelocity,
          col.simpleTarget,
          SIMPLE_ROLL_STIFFNESS,
          rollTapeDampingRatio,
          dt,
          springOut
        )
        col.simplePhase = springOut[0]
        col.simpleVelocity = springOut[1]
        val previousVisualPhase = col.simpleVisualPhase
        col.simpleVisualPhase = TransitionLogic.simpleRollVisualPhase(
          col.simplePhase,
          col.simpleTarget,
          col.simpleDirection,
          col.simpleIdleSeconds,
          col.simpleBurstSteps
        )
        col.simpleVisualVelocity = if (dt > 0f) {
          (col.simpleVisualPhase - previousVisualPhase) / dt
        } else {
          col.simpleVelocity
        }
        if (
          abs(col.simplePhase - col.simpleTarget) < 0.004f &&
          abs(col.simpleVelocity) < 0.03f &&
          TransitionLogic.simpleRollCanCommit(col.simpleIdleSeconds, col.simpleBurstSteps)
        ) {
          val finalChar = col.simpleTargetChar.ifEmpty {
            (((col.simpleTarget % 10f) + 10f) % 10f).toInt().toString()
          }
          col.glyphs.clear()
          col.glyphs.add(GlyphState(finalChar).apply {
            p = 1f; v = 0f; target = 1f
            xRel = col.simpleXRel; xRelTarget = col.simpleXRel; w = col.simpleWidth
            minScale = rollDepthMin; scaleExponent = rollScaleExponent
          })
          col.simpleRollActive = false
          col.simpleTargetChar = finalChar
        }
        continue
      }
      if (col.tapeActive) {
        // Release an owed lane when its hold expires. Only the TARGET waits; the spring below runs
        // every frame regardless, so a column mid-flight keeps flying while a later column waits.
        if (col.tapeDelay > 0f) {
          col.tapeDelay -= dt
          if (col.tapeDelay <= 0f) {
            col.tapeDelay = 0f
            col.tapeTarget = col.tapePendingTarget
          }
        }
        val slow = 1f + rollTapeSlowPerColumn * col.tapeWaveIndex
        val tapeK = rollTapeStiffness / (slow * slow)
        TransitionLogic.springIntegrateInto(
          col.tapePhase,
          col.tapeVelocity,
          col.tapeTarget,
          tapeK,
          rollTapeDampingRatio,
          dt,
          springOut
        )
        col.tapePhase = springOut[0]
        col.tapeVelocity = springOut[1]
      }
      val gIt = col.glyphs.iterator()
      while (gIt.hasNext()) {
        val g = gIt.next()
        if (col.tapeActive && !g.tapeLane.isNaN()) {
          // Keep the latest incoming digit on the front of the roll while the physical phase
          // catches up. Once the phase reaches its target, the lane is no longer synthetic and
          // the glyph settles normally. Outgoing glyphs keep their original lane and provide the
          // visible trailing half of the handoff.
          if (g.target >= 0.5f && !g.tapeLaneTarget.isNaN()) {
            g.tapeLane = if (col.tapeDirection > 0) {
              min(g.tapeLaneTarget, col.tapePhase + ROLL_FRONT_LEAD)
            } else {
              max(g.tapeLaneTarget, col.tapePhase - ROLL_FRONT_LEAD)
            }
          }
          val oldPresence = g.p
          g.off = col.tapePhase - g.tapeLane
          g.offV = col.tapeVelocity
          g.offTarget = col.tapeTarget - g.tapeLane
          g.p = TransitionLogic.rollTapePresence(
            offset = g.off,
            isTarget = g.target >= 0.5f,
            direction = col.tapeDirection
          )
          g.v = if (dt > 0f) (g.p - oldPresence) / dt else 0f
          g.tapeSoftness =
            (1f - abs(col.tapeVelocity) / rollTapeSoftVelocity).coerceIn(0f, 1f)

          TransitionLogic.springIntegrateInto(
            g.xRel,
            g.xv,
            g.xRelTarget,
            xStiffness,
            xDampingRatio,
            dt,
            springOut
          )
          g.xRel = springOut[0]
          g.xv = springOut[1]

          val passedDistance = (col.tapePhase - g.tapeLane) * col.tapeDirection
          if (g.target < 0.5f && passedDistance > ROLL_TAPE_CULL_DISTANCE) {
            g.node = null
            gIt.remove()
          }
          continue
        }
        if (g.delay > 0f) {
          g.delay -= dt
          if (g.delay <= 0f) {
            g.delay = 0f
            if (g.pendingTarget >= 0f) { g.target = g.pendingTarget; g.pendingTarget = -1f }
          }
        } else if (g.pendingTarget >= 0f) {
          g.target = g.pendingTarget; g.pendingTarget = -1f
        }
        // Presence and X are independent springs; both carry velocity across every retarget. The
        // presence spring runs even during a stagger — the delay gates only the target flip. It
        // used to freeze the glyph, which is why a staggered column that was already moving had to
        // be excluded from the cascade, and why nothing rippled once a transition was interrupted.
        // A DEPARTING glyph sheds its presence faster than it travels. Its destination is two units
        // away so it always clears the frame (a fast roll needs departures to leave, not pile up),
        // but at a matched rate it was still nearly opaque by the time it had visibly moved — that
        // is what made the nines of 9,999 -> 1,000 rise while crisp.
        // A structural DEATH is exempt: it mirrors the birth, so it keeps the presence spring's own
        // rate and stays readable over the whole roll instead of dissolving before it has moved.
        // A ROLL's departure and its arrival now run at the SAME rate. x4 was there so a continuous
        // roll could not pile up crisp ink, but separating the two glyphs by template fit
        // (.agent/tools/template_fit.py) on 2,577 -> 2,576 puts the reference's outgoing glyph at
        // 0.78 / 0.30 / 0.17 / 0.10 of its settled opacity at +33 / +83 / +133 / +183 ms against our
        // 0.37 / 0.14 / 0.06 / 0.04 at x4 and 0.57 / 0.21 / 0.09 / 0.05 at x1.8 — the reference's
        // crossfade is far more weighted to the outgoing glyph early on than any of those. The
        // pile-up it guarded against is re-checked on a press-and-hold after every change here.
        // Held above the 1.0 that matched the measurement: at 1.0 the outgoing glyph lingers long
        // enough to read as a visible second digit rather than as the weight of the roll, and two
        // scenes ran past the reference's own duration (-1 -> 0 at 450 ms against 300). 1.6 was too
        // far back — it measured 0.59 / 0.22 at +33 / +83 ms, near the 0.57 / 0.21 of before any of
        // this — so 1.3 splits it against the reference's 0.78 / 0.30.
        // Structural births share one physical duration. `birthSlow` remains in the formula so the
        // lifecycle math stays explicit, but its factor is zero: glyph roles vary visually, not in
        // their clock.
        val wave = max(0, g.waveIndex)
        val birthSlow =
          if (g.structuralBirth) 1f + birthSlowPerColumn * wave * changeSpacing else 1f
        val birthSlow2 = birthSlow * birthSlow
        val pK = when {
          // An ARRIVING glyph gains presence faster when changes crowd. In a continuous roll the
          // incoming digit never gets time to darken, so the column reads as a pale smudge and the
          // number looks unbalanced toward its left — measured on a press-and-hold, the whole
          // composition's ink centre sits 0.24 line-heights left of where it settles against the
          // reference's 0.08, while the edges do not move at all: nothing is sliding, the last
          // digit is simply too faint to carry its side. Presence only — the roll's PACE comes from
          // the offset spring and stays where it was tuned.
          g.target >= 0.5f && g.structuralBirth ->
            swiftDefaultSpringStiffness
          g.target >= 0.5f ->
            springStiffness * arriveCrossSlow *
              (1f + (1f - changeSpacing) * (arrivePresenceFast - 1f)) / birthSlow2
          g.structuralExit -> springStiffness * deathRate * deathRate
          // A handover inside a structural change: a death's pace, carrying the wave's slowness.
          // At a roll's pace instead, the whole shrink ran ~70 ms late; at a death's pace with no
          // lead-in it emptied the slot before its replacement had arrived. It needs both — held,
          // then quick — which is what the lead plus this rate give it.
          g.substitutionExit -> {
            val slow = 1f + structuralExitSlowPerColumn * wave * changeSpacing
            springStiffness * deathRate * deathRate / (slow * slow)
          }
          // Isolated: linger and roll out. Spam: clear out before the next digit lands on top.
          else -> {
            val slow = 1f + exitSlowPerColumn * wave * changeSpacing
            springStiffness *
              (rollExitFadeRate + (1f - changeSpacing) * (rollExitFadeFast - rollExitFadeRate)) /
              (slow * slow)
          }
        }
        TransitionLogic.springIntegrateInto(g.p, g.v, g.target, pK, springDampingRatio, dt, springOut)
        g.p = springOut[0]; g.v = springOut[1]
        // Movement is released by the same stagger as the fade: while a change is still queued the
        // glyph holds where it is, so nothing ever slides at full presence.
        g.offTarget = when {
          g.pendingTarget >= 0f -> g.off
          g.target >= 0.5f -> 0f
          else -> g.exitOff
        }
        // A DEPARTING glyph must fade faster than it travels, or it is still nearly opaque by the
        // time it has visibly moved — that is what made the nines of 9,999 -> 1,000 rise while
        // crisp. Its destination is two units away so it always clears out, but a quarter of the
        // stiffness halves the rate, so it covers roughly one unit in the time it sheds its
        // presence and drifts the rest of the way once it is already invisible.
        // An ARRIVING glyph keeps the presence spring's own rate, so it lands as it solidifies.
        val offK = when {
          // Same speed-up as its presence, so an arrival LANDS as it lights up. Accelerating only
          // the presence left the digit bright but still in flight: measured against its own still
          // neighbours in the same frame, ours sat 0.16 line-heights above them during a hold where
          // the reference sits at -0.005. That is the "positioned toward the top" of the report,
          // and the earlier probe missed it by comparing the column to its own settled position
          // instead of to the digits beside it.
          g.target >= 0.5f && g.structuralBirth ->
            swiftDefaultSpringStiffness
          g.target >= 0.5f ->
            springStiffness * (arriveOffsetBaseline +
              (1f - offsetSpacing) * (arriveOffsetFast - arriveOffsetBaseline)) / birthSlow2
          // Same multiplier as its presence, so a death covers the same distance before it is gone.
          g.structuralExit -> springStiffness * deathRate * deathRate
          g.substitutionExit -> {
            val slow = 1f + structuralExitSlowPerColumn * wave * changeSpacing
            springStiffness * deathRate * deathRate / (slow * slow)
          }
          else -> springStiffness * 0.25f
        }
        // The settle bounce is the arrival's alone: a departure that rang would swing back toward
        // the baseline it is trying to leave.
        val offZ = when {
          g.target < 0.5f -> springDampingRatio
          g.structuralBirth -> swiftDefaultSpringDampingRatio
          else -> arriveDampingRatio
        }
        TransitionLogic.springIntegrateInto(g.off, g.offV, g.offTarget, offK, offZ, dt, springOut)
        g.off = springOut[0]; g.offV = springOut[1]
        TransitionLogic.springIntegrateInto(g.xRel, g.xv, g.xRelTarget, xStiffness, xDampingRatio, dt, springOut)
        g.xRel = springOut[0]; g.xv = springOut[1]
        // Fully extinguished and not coming back.
        if (g.target <= 0f && g.pendingTarget < 0f &&
          (g.p <= 0.004f && abs(g.v) < 0.05f || abs(g.off) > 1.7f)) {
          g.node = null; gIt.remove()
        }
      }
      if (col.glyphs.isEmpty()) colIt.remove()
    }
  }


  private fun slotsAtRest(): Boolean = columns.values.all { atRest(it) }


  // ── Block renderer (debug strategies WHOLE_RUN / CHANGED_RUN) ──

  private fun drawTransition(canvas: Canvas, plan: LayerPlan, progress: Float) {
    val bl = baselineY(height / 2f)
    val hProg = TransitionLogic.layoutInterpolation(progress)

    val oldOriginX = (width.toFloat() - plan.oldWidth) / 2f
    val newOriginX = (width.toFloat() - plan.newWidth) / 2f
    val originX = oldOriginX + (newOriginX - oldOriginX) * hProg

    val changedLeft = originX + plan.oldPrefixAdvance + (plan.newPrefixAdvance - plan.oldPrefixAdvance) * hProg
    val interpolChangedAdv = plan.oldChangedAdvance + (plan.newChangedAdvance - plan.oldChangedAdvance) * hProg

    val hOverscan = overscanHorizontal
    val changedRegionLeft = changedLeft - hOverscan
    val changedRegionRight = changedLeft + interpolChangedAdv + hOverscan

    drawStableRegions(canvas, plan, bl, originX, hProg)

    val oldOriginXFull = oldOriginX
    val newOriginXFull = newOriginX

    val oldOffset = TransitionLogic.computeOldOffset(currentDirection, travel, progress)
    val newOffset = TransitionLogic.computeNewOffset(currentDirection, travel, progress)
    val oldAlpha = (255 * TransitionLogic.oldOpacity(progress)).toInt().coerceIn(0, 255)
    val newAlpha = (255 * TransitionLogic.newOpacity(progress)).toInt().coerceIn(0, 255)

    val oldChangedViewLeft = oldOriginXFull + plan.oldPrefixAdvance
    val oldChangedViewRight = oldChangedViewLeft + plan.oldChangedAdvance
    val newChangedViewLeft = newOriginXFull + plan.newPrefixAdvance
    val newChangedViewRight = newChangedViewLeft + plan.newChangedAdvance

    updateMaskPaint(bl)

    val changedClipTop = bl + fmAscent - travel * 0.3f
    val changedClipBottom = bl + fmDescent + travel * 0.3f

    val maskTop = bl + fmAscent - travel * 0.5f
    val maskBottom = bl + fmDescent + travel * 0.5f

    canvas.save()
    canvas.clipRect(changedRegionLeft, changedClipTop, changedRegionRight, changedClipBottom)

    if (oldAlpha > 0 && plan.oldChangedUtf16Start < plan.oldChangedUtf16End) {
      val oldSave = canvas.saveLayer(
        oldChangedViewLeft - hOverscan, changedClipTop,
        oldChangedViewRight + hOverscan, changedClipBottom,
        null
      )
      textPaint.alpha = oldAlpha
      drawFullText(canvas, plan.oldFormatted, oldOriginXFull, bl + oldOffset, textPaint)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        updateMaskGradient(maskTop, maskBottom, TransitionLogic.verticalMaskEnvelope(progress))
        canvas.drawRect(
          oldChangedViewLeft - hOverscan, maskTop,
          oldChangedViewRight + hOverscan, maskBottom,
          verticalMaskPaint
        )
      }
      canvas.restoreToCount(oldSave)

      val blur = TransitionLogic.blurEnvelope(progress)
      if (blur > 0.01f) {
        applyBlurOverlay(canvas, plan.oldFormatted, oldOriginXFull, bl + oldOffset,
          oldChangedViewLeft, oldChangedViewRight, changedClipTop, changedClipBottom, blur, oldAlpha)
      }
    }

    if (newAlpha > 0 && plan.newChangedUtf16Start < plan.newChangedUtf16End) {
      val newSave = canvas.saveLayer(
        newChangedViewLeft - hOverscan, changedClipTop,
        newChangedViewRight + hOverscan, changedClipBottom,
        null
      )
      textPaint.alpha = newAlpha
      drawFullText(canvas, plan.newFormatted, newOriginXFull, bl + newOffset, textPaint)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        updateMaskGradient(maskTop, maskBottom, TransitionLogic.verticalMaskEnvelope(1f - progress))
        canvas.drawRect(
          newChangedViewLeft - hOverscan, maskTop,
          newChangedViewRight + hOverscan, maskBottom,
          verticalMaskPaint
        )
      }
      canvas.restoreToCount(newSave)

      val newBlur = TransitionLogic.newBlurEnvelope(progress)
      if (newBlur > 0.01f) {
        applyBlurOverlay(canvas, plan.newFormatted, newOriginXFull, bl + newOffset,
          newChangedViewLeft, newChangedViewRight, changedClipTop, changedClipBottom, newBlur, newAlpha)
      }
    }

    canvas.restore()
  }

  private fun drawStableRegions(canvas: Canvas, plan: LayerPlan, bl: Float, originX: Float, hProg: Float) {
    textPaint.alpha = 255

    val stableTop = bl + fmAscent - travel * 0.3f
    val stableBottom = bl + fmDescent + travel * 0.3f

    if (plan.commonPrefixUtf16End > 0) {
      val prefixAdv = plan.oldPrefixAdvance + (plan.newPrefixAdvance - plan.oldPrefixAdvance) * hProg
      canvas.save()
      canvas.clipRect(originX, stableTop, originX + prefixAdv, stableBottom)
      drawFullText(canvas, plan.newFormatted, originX, bl, textPaint)
      canvas.restore()
    }

    if (plan.oldSuffixUtf16Start < plan.oldFormatted.length || plan.newSuffixUtf16Start < plan.newFormatted.length) {
      val prefixAdv = plan.oldPrefixAdvance + (plan.newPrefixAdvance - plan.oldPrefixAdvance) * hProg
      val changedAdv = plan.oldChangedAdvance + (plan.newChangedAdvance - plan.oldChangedAdvance) * hProg
      val totalWidth = plan.oldWidth + (plan.newWidth - plan.oldWidth) * hProg
      val suffixLeft = originX + prefixAdv + changedAdv
      canvas.save()
      canvas.clipRect(suffixLeft, stableTop, originX + totalWidth, stableBottom)
      drawFullText(canvas, plan.newFormatted, originX, bl, textPaint)
      canvas.restore()
    }
  }

  private fun drawFullText(canvas: Canvas, text: String, x: Float, y: Float, paint: TextPaint) {
    if (text.isEmpty()) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val n = getOrCreateTextNode(text, paint)
      if (n != null) {
        n.setPosition((x).toInt(), (y - getTextHeight() * 2).toInt(),
          (x + textPaint.measureText(text)).toInt() + 1, (y + getTextHeight()).toInt() + 1)
        n.setTranslationY(0f)
        canvas.drawRenderNode(n)
        return
      }
    }
    canvas.drawText(text, x, y, paint)
  }

  @SuppressLint("NewApi")
  private fun getOrCreateTextNode(text: String, paint: TextPaint): RenderNode? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val isOld = text == cachedOldFormatted
    val isNew = text == cachedNewFormatted
    val node = when {
      isOld -> oldTextNode
      isNew -> newTextNode
      else -> null
    }
    if (node != null && node.hasDisplayList()) return node

    val newNode = RenderNode(text.take(12))
    val width = ceil(paint.measureText(text).toDouble()).toInt() + 1
    val height = ceil(getTextHeight().toDouble()).toInt() + 1
    val rec = newNode.beginRecording(width, height)
    rec.drawText(text, 0f, -paint.fontMetrics.ascent, paint)
    newNode.endRecording()
    newNode.setTranslationY(0f)

    when {
      isOld -> { oldTextNode = newNode; cachedOldFormatted = text }
      isNew -> { newTextNode = newNode; cachedNewFormatted = text }
      text == cachedOldFormatted -> { oldTextNode = newNode }
      text == cachedNewFormatted -> { newTextNode = newNode }
      else -> {}
    }
    return newNode
  }

  // ── Soft mask (block renderer) ──

  private fun updateMaskPaint(bl: Float) {
    val ascent = fmAscent
    val descent = fmDescent
    val fade = travel * 0.5f
    val top = bl + ascent - fade
    val bottom = bl + descent + fade
    updateMaskGradient(top, bottom, 1f)
  }

  private var lastMaskTop: Float = Float.NaN
  private var lastMaskBottom: Float = Float.NaN
  private var lastMaskStrength: Float = -1f

  private fun updateMaskGradient(top: Float, bottom: Float, strength: Float) {
    if (!maskPaintNeedsUpdate && top == lastMaskTop && bottom == lastMaskBottom && strength == lastMaskStrength) return
    lastMaskTop = top; lastMaskBottom = bottom; lastMaskStrength = strength
    maskPaintNeedsUpdate = false

    val glyphTop = lastMaskTop + travel * 0.5f
    val glyphBottom = lastMaskBottom - travel * 0.5f
    val totalFade = travel * 0.5f

    val solidStart = glyphTop + totalFade * 0.2f
    val solidEnd = glyphBottom - totalFade * 0.2f

    val colors = intArrayOf(
      Color.argb(0, 255, 255, 255),
      Color.argb((255 * strength).toInt().coerceIn(0, 255), 255, 255, 255),
      Color.argb((255 * strength).toInt().coerceIn(0, 255), 255, 255, 255),
      Color.argb(0, 255, 255, 255)
    )
    val positions = floatArrayOf(0f, (solidStart - top) / (bottom - top), (solidEnd - top) / (bottom - top), 1f)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      verticalMaskPaint.shader = LinearGradient(0f, top, 0f, bottom, colors, positions, Shader.TileMode.CLAMP)
    }
  }

  private fun applyBlurOverlay(canvas: Canvas, text: String, x: Float, y: Float,
                                changedLeft: Float, changedRight: Float,
                                clipTop: Float, clipBottom: Float,
                                blurStrength: Float, baseAlpha: Int) {
    val radius = blurStrength * 12f
    if (radius < 1f) return
    val blurAlpha = (baseAlpha * blurStrength * 0.4f).toInt().coerceIn(0, 255)
    if (blurAlpha <= 0) return

    canvas.save()
    canvas.clipRect(changedLeft, clipTop, changedRight, clipBottom)

    textPaint.alpha = blurAlpha
    textPaint.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
    canvas.drawText(text, x, y, textPaint)
    textPaint.maskFilter = null

    canvas.restore()
  }

  // ── Public setters ──

  fun setValue(newValue: Double) {
    val now = System.nanoTime()
    numericValue = newValue
    lastValueChangeNanos = now
    if (!hasSettledOnce) {
      hasSettledOnce = true; settledValue = newValue; settledText = formatNumber(newValue)
      animationProgress = 1f; activePlan = null; columns.clear()
      updateContentDescription(); invalidate(); requestLayout(); return
    }
    NumericTextFrameRecorder.arm(
      this,
      formatNumber(newValue),
      newValue < settledValue
    )
    // Mid-flight update → retarget the running transition for continuity, instead of
    // cancelling and restarting from rest (which reads as staccato on a rapid hold).
    // Guard on the animator only: the spring value overshoots past 1 during the bounce,
    // and a restart there would snap the display back to the segment origin.
    if (animator != null) { retargetTransition(); return }

    val dir = when (numericDirection) {
      "up" -> 1; "down" -> -1
      else -> newValue.compareTo(settledValue).coerceIn(-1, 1)
    }
    if (dir == 0) {
      settledText = formatNumber(newValue); updateContentDescription(); invalidate(); return
    }
    startTransition(dir)
  }

  fun setLocale(v: String) {
    if (v == numericLocale) return
    // The paint follows the locale, not just the formatter: the locale decides which numerals are
    // drawn, and so whether the bundled face can draw them at all.
    numericLocale = v; currentFormatterLocale = null; recalcFormatter(); recalcTextPaint(); requestLayout()
    if (animator == null) { settledText = formatNumber(settledValue); updateContentDescription(); invalidate() }
  }
  fun setDirection(v: String) { numericDirection = v }
  fun setAnimationDuration(v: Double) { animationDurationMs = v.toLong().coerceIn(50L, 10000L) }
  fun setUseGrouping(v: Boolean) {
    if (v == numericUseGrouping) return
    numericUseGrouping = v; recalcFormatter(); textLayersNeedRebuild = true; requestLayout()
    if (animator == null) { settledText = formatNumber(settledValue); updateContentDescription(); invalidate() }
  }
  fun setMinimumFractionDigits(v: Int) {
    if (v == numericMinFractionDigits) return
    numericMinFractionDigits = v; recalcFormatter(); textLayersNeedRebuild = true; requestLayout()
    if (animator == null) { settledText = formatNumber(settledValue); updateContentDescription(); invalidate() }
  }
  fun setMaximumFractionDigits(v: Int) {
    if (v == numericMaxFractionDigits) return
    numericMaxFractionDigits = v; recalcFormatter(); textLayersNeedRebuild = true; requestLayout()
    if (animator == null) { settledText = formatNumber(settledValue); updateContentDescription(); invalidate() }
  }
  fun setReduceMotion(v: String) { numericReduceMotion = v }
  fun setFontSize(v: Float) {
    val s = v.coerceAtLeast(4f)
    if (s == numericFontSize) return
    numericFontSize = s; recalcTextPaint(); textLayersNeedRebuild = true; maskPaintNeedsUpdate = true; requestLayout(); invalidate()
  }
  fun setFontWeight(v: String) {
    if (v == numericFontWeight) return
    numericFontWeight = v; recalcTextPaint(); textLayersNeedRebuild = true; maskPaintNeedsUpdate = true; requestLayout(); invalidate()
  }
  fun setFontFamily(v: String) {
    if (v == numericFontFamily) return
    numericFontFamily = v; recalcTextPaint(); textLayersNeedRebuild = true; maskPaintNeedsUpdate = true; requestLayout(); invalidate()
  }
  fun setTextColor(v: Int) {
    if (v == numericTextColor) return
    numericTextColor = v; recalcTextPaint(); textLayersNeedRebuild = true; invalidate()
  }
  fun setDebugTransitionStrategy(v: String) { debugTransitionStrategy = v }
  fun setDebugManualProgress(v: Float) { debugManualProgress = v; invalidate() }

  // ── Transition lifecycle ──

  private fun startTransition(dir: Int) {
    if (animator != null) cancelAnimation()

    val oldValue = settledValue
    val oldFormatted = settledText
    val newFormatted = formatNumber(numericValue)
    if (newFormatted == oldFormatted) return

    val plan = buildPlan(resolveStrategy(), oldValue, numericValue, oldFormatted, newFormatted)
    activePlan = plan
    if (debugManualProgress >= 0f) debugPlan = plan
    currentDirection = dir
    animationProgress = 0f
    springValue = 0f
    springVelocity = 0f
    completionFired = false

    seedSlots(oldFormatted)
    scheduleSlots(newFormatted, dir)

    // Freeze-frame (debug scrub): keep the slots staged, render at the manual progress, no ticker.
    if (debugManualProgress >= 0f) { invalidate(); return }

    if (!shouldAnimate()) { settleTo(newFormatted); return }

    val needsLayout = max(plan.oldWidth, plan.newWidth) > measuredWidth || plan.newWidth != plan.oldWidth
    if (needsLayout) requestLayout()

    startSpringTicker()
  }

  // A ValueAnimator used only as a per-frame clock; the springs are integrated by real dt.
  private fun startSpringTicker() {
    if (animator != null) return
    lastTickNanos = 0L
    val anim = ValueAnimator.ofFloat(0f, 1f)
    anim.duration = 100_000L
    anim.repeatCount = ValueAnimator.INFINITE
    anim.interpolator = LinearInterpolator()
    anim.addUpdateListener { tickSpring() }
    animator = anim
    anim.start()
  }

  private fun tickSpring() {
    val now = System.nanoTime()
    if (lastTickNanos == 0L) { lastTickNanos = now; return }
    val dt = ((now - lastTickNanos) / 1_000_000_000f).coerceIn(0f, 1f / 30f)
    lastTickNanos = now

    val (x, v) = TransitionLogic.springStep(
      springValue, springVelocity, 1f, springStiffness, springDampingRatio, dt
    )
    springValue = x
    springVelocity = v
    animationProgress = x

    tickSlots(dt)

    // Settled: global spring at the goal AND slow, every column at rest, AND no value change in
    // the last ~160ms. The recency guard keeps the ticker alive during a rapid hold, so each
    // press re-bases and rolls continuously instead of settling and dead-starting between presses.
    //
    // The goal test is `abs(x - 1f)`, which bounds the spring on both sides. It used to also
    // require `x >= 1f`, i.e. that the spring be at or ABOVE its goal — and at damping 0.9 the
    // global spring overshoots exactly once, early, then converges from below for good. The
    // per-glyph arrival springs ring far longer at damping 0.32, so by the time `slotsAtRest()`
    // turns true the global spring has been under 1 for a long while and the condition could no
    // longer be met. The transition never formally ended: the animator ran forever (measured: 210
    // frames rendered across 4 idle seconds), `settleTo` was never called, and the glyphs that had
    // moved sat on the threshold of `isStill`, flipping between the sharp path and the RenderNode
    // path frame to frame — which is what read as the settled number flashing.
    val quiet = (now - lastValueChangeNanos) > 160_000_000L
    if (quiet && slotsAtRest() && abs(x - 1f) < 0.002f && abs(v) < 0.02f) {
      if (!completionFired) { completionFired = true; springValue = 1f; springVelocity = 0f; settleTo(formatNumber(numericValue)) }
      return
    }
    invalidate()
  }

  // Mid-flight retarget: keep the running animator + global progress and swap the target to the
  // newest value; per-slot, only the columns whose character actually changed retarget (carrying
  // their velocity), so a rapid burst becomes one continuous per-column roll.
  private fun retargetTransition() {
    // The next hop rolls FROM the segment currently on screen — i.e. the IN-FLIGHT target — not
    // from the pre-transition settledText, which stays stale until the running segment settles.
    // Using the stale value collapsed A→B→A patterns (999→1,000→999): while the 999→1,000 grow was
    // still animating, settledText was still "999", so the incoming "999" looked like a no-op and
    // the animation was cancelled to a jump. Baselining on the in-flight target fixes it.
    val inflightTarget = activePlan?.newFormatted ?: settledText
    val inflightValue = activePlan?.newValue ?: settledValue
    if (springValue >= 1f) springValue = (springValue - 1f).coerceAtLeast(0f)
    settledValue = inflightValue; settledText = inflightTarget

    val oldFormatted = inflightTarget
    val newFormatted = formatNumber(numericValue)
    if (newFormatted == oldFormatted) {
      cancelAnimation(); settleTo(oldFormatted); return
    }
    if (activePlan?.newFormatted == newFormatted) return

    val dir = when (numericDirection) {
      "up" -> 1; "down" -> -1
      else -> numericValue.compareTo(settledValue).coerceIn(-1, 1).let { if (it == 0) currentDirection else it }
    }
    val plan = buildPlan(resolveStrategy(), settledValue, numericValue, oldFormatted, newFormatted)
    activePlan = plan
    if (debugManualProgress >= 0f) debugPlan = plan
    currentDirection = dir

    scheduleSlots(newFormatted, dir)

    val needsLayout = max(plan.oldWidth, plan.newWidth) > measuredWidth || plan.newWidth != plan.oldWidth
    if (needsLayout) requestLayout()
    invalidate()
  }

  private fun buildPlan(strategy: TransitionStrategy, oldValue: Double, newValue: Double, oldFormatted: String, newFormatted: String): LayerPlan {
    val measure = { text: String, start: Int, end: Int -> textPaint.measureText(text, start, end) }
    return when (strategy) {
      TransitionStrategy.WHOLE_RUN -> TransitionLogic.buildWholeRunPlan(oldValue, newValue, oldFormatted, newFormatted, measure)
      TransitionStrategy.CHANGED_RUN -> TransitionLogic.buildLayerPlan(oldValue, newValue, oldFormatted, newFormatted, measure)
      // PER_GLYPH uses the per-slot renderer; the LayerPlan is still built so onMeasure has the
      // old/new widths for the changed-width case.
      TransitionStrategy.PER_GLYPH -> TransitionLogic.buildLayerPlan(oldValue, newValue, oldFormatted, newFormatted, measure)
    }
  }

  private fun settleTo(text: String) {
    settledText = text; settledValue = numericValue
    animationProgress = 1f; activePlan = null; columns.clear()
    if (debugManualProgress < 0f) { debugPlan = null }
    springValue = 1f; springVelocity = 0f
    updateContentDescription(); invalidate()
    val a = animator; animator = null; a?.removeAllListeners(); a?.cancel()
    // Ask for a layout only when the width we want has actually changed. Comparing against
    // `measuredWidth` cannot work: that has been through resolveSize and the parent's constraints,
    // so it legitimately differs from what we asked for, and the mismatch made every settle
    // relayout — which blanks the view for a frame.
    val dw = settledDesiredWidth(text)
    if (dw != lastDesiredWidth) { lastDesiredWidth = dw; requestLayout() }
  }

  private fun cancelAnimation() {
    animator?.also { it.removeAllListeners(); it.cancel() }; animator = null
  }

  private fun shouldAnimate(): Boolean = when (numericReduceMotion) {
    "always" -> false; "never" -> true
    else -> {
      try { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
      catch (_: Exception) { true }
    }
  }

  // ── Formatting ──

  private fun recalcFormatter() { formatter = null; currentFormatterLocale = null }
  private fun formatNumber(value: Double): String {
    val locale = resolveLocale()
    if (formatter == null || currentFormatterLocale != locale) {
      formatter = NumberFormat.getNumberInstance(locale); currentFormatterLocale = locale
      val sym = DecimalFormatSymbols.getInstance(locale)
      currentGroupSep = sym.groupingSeparator; currentDecimalSep = sym.decimalSeparator; currentMinusSign = sym.minusSign
    }
    formatter?.let { it.isGroupingUsed = numericUseGrouping; it.minimumFractionDigits = numericMinFractionDigits; it.maximumFractionDigits = numericMaxFractionDigits }
    return formatter?.format(value) ?: value.toString()
  }
  private fun resolveLocale(): Locale = try { Locale.forLanguageTag(numericLocale.replace("_", "-")) }
  catch (_: Exception) { val p = numericLocale.split("-", "_"); when (p.size) { 1 -> Locale(p[0]); 2 -> Locale(p[0], p[1]); else -> Locale.US } }

  private fun recalcTextPaint() {
    textPaint.color = numericTextColor
    textPaint.textSize = numericFontSize * resources.displayMetrics.scaledDensity
    textPaint.isAntiAlias = true; textPaint.isSubpixelText = true
    textPaint.typeface = resolveTypeface()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) textPaint.fontFeatureSettings = "tnum"
    val fm = textPaint.fontMetrics
    fmAscent = fm.ascent; fmDescent = fm.descent; textHeightPx = fm.descent - fm.ascent
    advanceCache.clear()
    paintGeneration++
    textLayersNeedRebuild = true; maskPaintNeedsUpdate = true
  }

  /** [TextPaint.measureText], memoised — the draw loop measures the same handful of glyphs forever. */
  private fun advanceOf(text: String): Float =
    advanceCache.getOrPut(text) { textPaint.measureText(text) }

  /**
   * The bundled face by default, the system face when asked for "system", and a consumer-registered
   * family for anything else. The bundled one is only kept if it can actually draw this locale's
   * numerals — see [localeGlyphProbe].
   */
  private fun resolveTypeface(): Typeface {
    val weight = NumericTextFonts.weightOf(numericFontWeight)
    val systemStyle = if (weight >= 700) Typeface.BOLD else Typeface.NORMAL
    val system = Typeface.create(Typeface.DEFAULT, systemStyle)
    if (numericFontFamily == NumericTextFonts.SYSTEM) return system
    if (numericFontFamily != NumericTextFonts.BUNDLED) {
      return try {
        ReactFontManager.getInstance().getTypeface(numericFontFamily, systemStyle, context.assets)
      } catch (_: RuntimeException) { system }
    }
    val bundled = NumericTextFonts.bundled(context.assets, weight) ?: return system
    // Ask before committing: a locale that formats with Arabic-Indic or Devanagari digits would
    // otherwise draw tofu, since the bundled face is Latin-script only.
    val probePaint = TextPaint(textPaint); probePaint.typeface = bundled
    return if (NumericTextFonts.canRender(probePaint, localeGlyphProbe())) bundled else system
  }

  /** Every character this locale's number formatting can produce: its ten digits, and its marks. */
  private fun localeGlyphProbe(): String {
    val sym = try { DecimalFormatSymbols.getInstance(resolveLocale()) } catch (_: Exception) { return "0123456789,.-" }
    val zero = sym.zeroDigit
    val sb = StringBuilder()
    for (i in 0..9) sb.append(zero + i)
    sb.append(sym.groupingSeparator).append(sym.decimalSeparator).append(sym.minusSign)
    return sb.toString()
  }

  private fun getTextHeight(): Float = textHeightPx
  private fun baselineY(centerY: Float): Float = centerY + textHeightPx / 2f - fmDescent
  private fun updateContentDescription() { contentDescription = settledText }
}
