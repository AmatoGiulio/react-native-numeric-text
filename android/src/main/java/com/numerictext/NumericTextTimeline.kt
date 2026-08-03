package com.numerictext

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The roll engine: one persistent column per logical position, each a single continuous position on
 * an endless strip of digits.
 *
 * Written from scratch on 2026-08-02 against two sources that did not exist before, and it is worth
 * saying which is which, because every constant here is one or the other:
 *
 *  - **Apple's own numbers.** `ContentTransition.NumericTextConfiguration` is a real type in the
 *    shipped SwiftUICore and its defaults are readable: `offset` 19/32, `delay` 18/120,
 *    `scale` 51/128, `blur` 32/4, `maxDurationMultiple` 1.25. Constants marked APPLE are those,
 *    unchanged. See `.agent/IOS_GROUND_TRUTH.md`.
 *  - **The exact-frame recorder.** Constants marked MEASURED were fitted against it: record at two
 *    values, check the knob does not move the columns it should not touch, solve.
 *
 * There is nothing to tune that is not in the companion object.
 *
 * ## The model
 *
 * A digit column is a `position` in *stop* units on a strip where stop `n` shows digit `n mod 10`.
 * Deriving the glyph from the index instead of storing one per stop is what makes a reversal safe:
 * indices move the other way and can never collide with a stop that is currently on screen.
 *
 * A change moves a column's `target` and never restarts anything. That one property is what makes a
 * single tap and a press-and-hold **the same code** — a hold is a target that keeps moving before
 * the spring has caught up.
 *
 * Two scalars per column, deliberately not one:
 *
 *  - `position` owns geometry — the offset, and the scale that goes with distance.
 *  - `settle` owns opacity, chasing the position's arrival with a slower spring.
 *
 * The recorder measured the reference finishing its geometry at ~400 ms and then taking another
 * ~350 ms to bring opacity from 0.81 to 1.00 with nothing moving. One scalar cannot express that:
 * when the position stops, everything derived from it stops.
 *
 * The wave is a hold on a column's *target*, never on its position, so a glyph already on screen is
 * never restarted and a burst still merges into one continuous roll.
 */
internal class NumericRollEngine {

  enum class GlyphRole { ANCHOR, ENTER, EXIT }

  data class GlyphSample(
    val key: String,
    val ch: String,
    val kind: TokenKind,
    val role: GlyphRole,
    val x: Float,
    val offsetY: Float,
    val alpha: Float,
    /** Uniform shrink with distance. The drum turns about a horizontal axis, so width is unaffected. */
    val scaleX: Float,
    /** The same shrink, times the drum's `cos` foreshortening. */
    val scaleY: Float,
    val blurLengthPx: Float,
    val stable: Boolean,
  )

  companion object {
    /**
     * The strip is a DRUM: ten digits on the ten faces of a turning decagon, not a flat ribbon.
     *
     * A stop is one face, so a stop of travel is one tenth of a turn. A glyph `d` stops from the
     * front is therefore at an angle `d * FACE_ANGLE` around the axis, and everything about how it
     * is drawn follows from that angle rather than from `d`:
     *
     *  - it sits `APOTHEM * sin(angle)` line heights off centre — the projection of its face onto
     *    the screen, which flattens as the face turns away instead of running off linearly;
     *  - it is foreshortened by `cos(angle)` VERTICALLY ONLY, because the drum turns about a
     *    horizontal axis. A digit's width does not change as the drum rolls; its height does.
     *
     * This replaces a flat `STEP_FRACTION` of 0.32 line heights per stop. The two agree for small
     * angles — `APOTHEM * FACE_ANGLE` is that same 0.32 — and diverge exactly where the reference
     * and this engine diverged: through the middle of a crossing, and under an alternation, where
     * the reference holds its pair 0.876 glyph heights apart against our 0.612 and WIDENS them as
     * the cadence slows while we narrow. A flat strip cannot span more than one step however the
     * target moves; a drum's two visible faces are held apart by the solid between them.
     *
     * The foreshortening is deliberately kept SEPARATE from `SCALE_AMOUNT`, which stays uniform.
     * A first attempt derived both the offset and the whole scale from the angle, and it did
     * produce the alternation's gap — middle band 1.513 down to 0.849 against the reference's
     * 0.756 — while costing the single crossing, 0.031 out to 0.181. One angle cannot own both:
     * the reference's crossing needs a shrink deeper than `cos` at that separation and a spacing
     * wider than the shrink implies. Two knobs, one angle each way.
     */
    const val FACES = 10
    const val FACE_ANGLE = (2.0 * Math.PI / FACES).toFloat()

    /**
     * Radius of the drum from the axis to a face, in line heights. MEASURED.
     *
     * At 0.509 — the value that makes the drum agree for small angles with the flat 0.32 strip it
     * replaces — the shape of the geometry changes and its amplitude does not, and that is what
     * the first round measured: the alternation's middle band moved 1.470 to 1.451 against the
     * reference's 0.760, and the single crossing lost 0.031 to 0.043. So the drum's SHAPE is not
     * what produces the gap; its SIZE is.
     *
     * Fitted against the single crossing's extent, which is the only thing that may set it. Three
     * rounds, mean extent over the three changing columns against the reference's 1.175:
     *
     *     apothem   0.509   1.150   1.539
     *     extent    1.131   1.749   1.947
     *
     * 0.555 is where that line crosses 1.175. It is very close to the 0.509 that reproduces the
     * flat strip this replaces, and that is the point: the drum's shape is right and its size is
     * pinned by the crossing.
     *
     * The alternation wanted 1.15 and cannot have it. At 1.15 the alternation's mean profile
     * matches the reference bin for bin — the middle band reads 0.715 against 0.760 — but the
     * single crossing then spans 1.75 glyph heights against 1.18, with a HOLE where the reference
     * has its central peak. Nothing downstream can close that: alpha scales the two lobes and
     * cannot fill the space between them, and the reference is only ~12% blurrier than this engine
     * at the crossing's floor, nowhere near enough to bridge a quarter of a glyph height. See the
     * drum section of `.agent/IOS_GROUND_TRUTH.md` for why the spacing has to come from somewhere
     * other than the angle.
     */
    const val APOTHEM = 0.555f

    /**
     * How much wider the drum turns while it is being CHASED. MEASURED, and the one knob here.
     *
     * The apothem above is pinned by the single crossing and the alternation wants roughly twice
     * it, so the two can only be reconciled by something that tells them apart — and the thing
     * that tells them apart is not the separation, which is the same in both, but whether changes
     * are still arriving. `crowd` below is that signal, and it is exactly zero for any change that
     * starts from rest, so the single crossing cannot move however this is set.
     *
     * The reference is not monotonic in cadence, which is what makes this fittable at all:
     *
     *     middle band     60 ms   120 ms   240 ms
     *     reference       0.760    1.460    1.292
     *     flat apothem    1.401    1.387    1.191
     *
     * Only 60 ms is wrong. At 120 and 240 this engine is already slightly WIDER than the reference,
     * so the widening has to be off there — which is what sets `CROWD_STEP` against `CROWD_RELAX`
     * rather than leaving them free.
     *
     * At 1.07 the 60 ms band came to 1.009 and 120 ms did not move, 1.387 to 1.384, which is the
     * cutoff behaving. Reading 1.009 back through the fixed-apothem sweep puts the effective
     * apothem at ~0.853, so `crowd` averages 0.50 over a run rather than saturating, and the
     * apothem the 60 ms band wants is ~1.05: 1.77 is that, at the same average.
     *
     * Refitted to 1.40 once the pair was drawn even and small. Levelling the shrink empties the
     * space between the two glyphs further on its own — the middle band went past the reference to
     * 0.592 against 0.760 — so the drum no longer needs to open as wide to produce the gap. The two
     * were fitted together because they are one visual outcome: how far apart contending glyphs
     * look.
     */
    const val CROWD_SPREAD = 1.00f

    /**
     * The chase signal, and why it is a threshold rather than a decay.
     *
     * `crowd` takes CROWD_STEP whenever a change commits onto a column that was NOT at rest, and
     * bleeds off at a constant rate — one full unit per CROWD_RELAX seconds. A constant rate, not
     * an exponential, because what is wanted is a CUTOFF: at a cadence `T` the signal gains
     * `CROWD_STEP` and loses `T / CROWD_RELAX` per cycle, so it saturates below a critical cadence
     * and sits at zero above it, sharply. An exponential decay only ever gives a ratio, and the
     * ratio between 60 ms and 120 ms is 2, which is nowhere near sharp enough to leave 120 ms alone.
     *
     * At 0.15 s the loss is 0.40 per cycle at 60 ms and 0.80 at 120 ms, so 0.60 saturates the first
     * within two cycles and never lifts the second off zero.
     *
     * The value used is lagged toward that raw signal over the same time constant. Without it the
     * raw signal saws between 0.6 and 1.0 every cycle, and a sawtooth on the apothem is a geometry
     * that visibly wobbles — the same failure the blur's radius had before it was quantised finely.
     */
    private const val CROWD_STEP = 0.60f
    private const val CROWD_RELAX = 0.15f

    /**
     * How far the pair is drawn EVENLY while the column is chased — both its opacity and its size.
     *
     * The reference shares its ink evenly between the two glyphs in every regime: the share in the
     * upper half of the pair swings 0.19 in a single crossing, 0.19 under a 60 ms alternation and
     * 0.19 through a continuous roll. This engine matched it where it was fitted and ran from 0.14
     * to 0.91 elsewhere — one glyph at a time, and the survivor slid, carrying the ink's centroid
     * three times as far as the reference's.
     *
     * Three earlier attempts each bought about a fifth of that and paid for it on the band or the
     * roll tail, and the arithmetic says why: the imbalance is a PRODUCT, not a cause. With the two
     * glyphs at 0.8 and 0.2 presence,
     *
     *     opacity   0.864 / 0.181                     ratio 4.8
     *     area      0.840 / 0.406  (shrink SQUARED)   ratio 2.1
     *     ink                                         ratio 9.9   -> share 0.91
     *
     * and 0.91 is exactly the measured worst frame. Levelling the opacity alone leaves 2.1 behind;
     * flattening the shrink alone leaves 4.8. Each is worth a fifth on its own and they multiply, so
     * they have to move together — which is why this is one knob driving both.
     *
     * Zero at rest like everything `crowd` drives, so `SCALE_AMOUNT` keeps Apple's value and the
     * single crossing cannot move. Above 1 and clamped, because `crowd` averages ~0.5 over a run.
     */
    private const val CROWD_EVEN = 2.00f
    private const val EVEN_LEVEL = 0.5f

    /**
     * The distance BOTH glyphs are given for the purpose of the shrink while the column is chased.
     *
     * Levelling the shrink towards 1 — no shrink at all — made the pair even and too BIG, which is
     * the opposite of the reference. Its ink is 0.779 of a settled glyph wide under a 60 ms
     * alternation and 0.877 through a roll; this engine read 0.907 and 0.955. Reported by eye, as
     * the reference's contending glyphs looking smaller than ours and blurred by the same amount.
     *
     * So both are levelled onto one distance and that distance is past the pair's own average. At
     * 0.5, `1 - SCALE_AMOUNT * 0.5` is 0.801 and the ink measured 0.855 — the blur carries the rest
     * of the width — so 0.75 is where the shrink itself has to sit for the ink to read the
     * reference's 0.779. Even AND small.
     */
    private const val EVEN_SHRINK_AT = 0.75f

    /** APPLE — `NumericTextConfiguration.delay`, 18/120. TOTAL spread of the wave, not the gap. */
    const val WAVE_TOTAL_SECONDS = 0.15f

    /**
     * APPLE — `NumericTextConfiguration.scale`, 51/128. How far a glyph shrinks at full separation.
     *
     * Restored to Apple's own number after the crossing's two glyphs were given separate opacity
     * curves. It had been cut to 0.20 because the arrival looked pale, but that was the shared
     * curve's fault, not the shrink's: with one curve, making a glyph smaller was the same thing
     * as making it fainter, because a smaller glyph carries less ink. With ENTER_ALPHA_EXPONENT
     * holding the arrival's brightness on its own, the size is free to be Apple's again.
     *
     * So of the five stored constants, `delay` and `scale` transfer and `offset` does not.
     */
    const val SCALE_AMOUNT = 0.3984f

    /**
     * Blur amplitude as a fraction of the line height, at full separation.
     *
     * Apple stores both an absolute `blur` (32/4 = 8.0 points) and a `relativeBlur` (32/128 = 0.25)
     * on the same byte and picks with an option flag; the default picks the absolute one. A library
     * that has to hold up at any font size wants the relative reading, and 0.25 is what the
     * reference's own crossing looks like.
     *
     * It follows the crossing's DISTANCE and not the glyph's speed, which is the opposite of what
     * a motion blur would do and is what the reference measures as. A shutter term proportional to
     * velocity was carried here for a while on the reasoning that a fast roll should smear more;
     * once both platforms could be driven at one cadence it turned out to be simply wrong. The
     * reference is SHARPER through a fast burst (0.60 of a settled glyph's edge) than at the floor
     * of a single crossing (~0.45), because each crossing in a burst is short. With the shutter at
     * 0.026 this engine measured 0.36 there, at 0.012 it measured 0.40, and with the term removed
     * it measures 0.603 against the reference's 0.600.
     *
     * It is isotropic — a defocus, not a smear along the roll. A directional blur preserves a
     * glyph's structure across its axis, so the digit stays readable and merely looks streaked;
     * the reference's mid-crossing glyphs are unreadable clouds. `NumericTextConfiguration` storing
     * its blur as a single number with no axis fits that reading.
     */
    const val BLUR_FRACTION = 0.50f

    /**
     * How far `animationDuration` may stretch or compress the spring. NOT a parity constant.
     *
     * The number is Apple's `NumericTextConfiguration.maxDurationMultiple`, but it is not being
     * used for what Apple uses it for, and it cannot be measured against the reference: SwiftUI's
     * `.numericText()` is a spring with no duration to set, so `animationDuration` does nothing on
     * iOS and this library says so in NumericTextSwiftUIHost's header. The prop scales Android's
     * springs alone.
     *
     * So this bounds a knob that only one platform has. 1.25 is a defensible bound and an
     * arbitrary one; do not report it as measured, and do not fit it — there is nothing to fit it
     * against.
     *
     * What Apple's own constant governs is untested and worth testing separately: whether the
     * reference's total duration grows with the number of changing columns or saturates. The wave
     * is already a fixed 0.15 s total however many columns move (`WAVE_TOTAL_SECONDS`), so the
     * question is whether the spring underneath it stretches too.
     */
    const val MAX_DURATION_MULTIPLE = 1.25f

    /**
     * MEASURED — the crossing's two glyphs do not share a curve.
     *
     * They did, and both readings of the result said the same thing: the arriving digit looked
     * weak, and the old one hung around long after the reference had cleared it. A single curve
     * cannot do both — brightening it lifts the ghost too, dimming it starves the arrival.
     *
     * So the arriver rises faster than linear and the leaver falls faster. Their sum at the
     * crossing still has to land on the reference's ink floor of ~0.51, which is what keeps the
     * pair honest: raising one without lowering the other shows up immediately.
     */
    const val ENTER_ALPHA_EXPONENT = 0.52f

    /**
     * Refitted from 1.40 once the drum was in: its `cos` foreshortening takes ~5% of a glyph's
     * area at the crossing's separation, and the crossing's ink floor went from 0.025 off the
     * reference to 0.039 off, low on all three columns and by the same 9%.
     *
     * At the floor both glyphs sit half a stop from their own, so the presence weighting hands
     * both the midpoint of the two exponents; moving that midpoint from 0.96 to 0.835 is worth
     * exactly the 9%, and it is the EXIT end that can afford it — `ENTER` is what holds the
     * arrival's brightness up near its own stop, where the floor is not being measured.
     *
     * 1.15 overshot to 0.016 high and 1.20 is the last 2% of it. `pieno` did not move: 418/501/584
     * at 1.40, 418/501/584 at 1.15, against the reference's 420/504/587. Lowering it does make the
     * departing glyph linger, but not on the clock that decides when the column reads full.
     */
    const val EXIT_ALPHA_EXPONENT = 1.20f

    /**
     * MEASURED — position spring.
     *
     * The reference's last column comes to rest at 537 ms on the decrement 1,242 -> 1,160; at 0.40
     * this engine took 602, a consistent 65-80 ms per column too slow.
     */
    private const val RESPONSE_SECONDS = 0.30f
    private const val DAMPING_RATIO = 0.90f

    /** MEASURED — opacity follower. Being fitted; the reference's opacity arrives at ~750 ms. */
    private const val SETTLE_RESPONSE_SECONDS = 0.22f


    /** Rest thresholds. Not tuning — they decide when to stop asking for frames. */
    private const val POSITION_EPSILON = 0.001f
    private const val VELOCITY_EPSILON = 0.005f
  }

  /** A stop this column will move to once [remaining] seconds have passed. */
  private class PendingStop(val stop: Int, var remaining: Float)

  private class Column(
    val key: String,
    var kind: TokenKind,
    /** The character shown when this column is a separator or sign; null for digit columns. */
    var literal: String?,
  ) {
    /**
     * What each stop shows. A handover advances the target by exactly ONE stop and writes the new
     * digit there, so a crossing is always two glyphs however far apart the digits are: 6 -> 4 is
     * one crossing, not eight. The reference's crossing measures ~1.2 glyph heights, which is two
     * glyphs, and its ink floor does not deepen with the digit distance.
     *
     * Indices only ever advance in the current direction, and anything more than one stop from the
     * position is culled, so a reversal can never write onto a stop that is on screen.
     */
    val charAt = HashMap<Int, String>()
    var position = 0f
    var velocity = 0f
    var target = 0

    /**
     * Stops waiting for their turn in the wave, each with its own countdown.
     *
     * A queue and not one pending stop, because the wave's delay belongs to a CHANGE, not to a
     * column. With a single slot, a burst piled every change that arrived during one hold into one
     * commit: traced on a 33 ms roll, the rightmost column stood still for 187 ms and then jumped
     * eight stops at once, then six — two launches instead of fourteen steps. The reference steps
     * evenly, and this engine finished the whole burst 64 ms early because of it.
     *
     * The merge property is unaffected: entries only ever move where the column is going, never
     * where it is, so a glyph on screen is still never restarted.
     */
    val pending = ArrayDeque<PendingStop>()

    var settle = 1f
    var settleVelocity = 0f

    /**
     * How hard this column is being chased: 0 for anything that started from rest, rising towards 1
     * while changes keep landing on it before it has resolved. [crowdRaw] is the impulse-and-bleed
     * signal, [crowd] the lagged one the geometry reads.
     */
    var crowdRaw = 0f
    var crowd = 0f

    var x = 0f
    var xVelocity = 0f
    var targetX = 0f

    /** 1 while the column belongs to the number, falling to 0 once it has been removed. */
    var alive = 1f
    var retiring = false

    fun goalStop(): Int = pending.lastOrNull()?.stop ?: target
  }

  private val columns = LinkedHashMap<String, Column>()
  private var targetLayout: List<KeyedSlot> = emptyList()
  private var lineHeightPx = 1f
  private var durationScale = 1f
  private var lastDirection = 1

  var targetText: String = ""
    private set

  var isRunning: Boolean = false
    private set

  fun targetWidth(): Float = targetLayout.firstOrNull()?.totalWidth ?: 0f

  // ── Public API ──────────────────────────────────────────────────────────────────────────────

  fun reset(layout: List<KeyedSlot>, text: String, lineHeight: Float) {
    columns.clear()
    targetLayout = layout
    targetText = text
    lineHeightPx = max(1f, lineHeight)
    for (slot in layout) {
      val column = Column(slot.key, slot.kind, literalOf(slot))
      column.target = 0
      column.charAt[0] = slot.char
      column.position = 0f
      column.x = xRel(slot)
      column.targetX = column.x
      columns[slot.key] = column
    }
    isRunning = false
  }

  fun setTarget(
    layout: List<KeyedSlot>,
    text: String,
    direction: Int,
    lineHeight: Float,
    animationDurationMs: Long,
  ) {
    lineHeightPx = max(1f, lineHeight)
    durationScale = animationDurationMs.coerceAtLeast(80L) / 320f
    lastDirection = if (direction < 0) -1 else 1
    targetText = text
    targetLayout = layout

    val incoming = layout.associateBy { it.key }
    for (column in columns.values) column.retiring = incoming[column.key] == null

    // The wave's total is fixed, so a column's share of it needs the count first.
    var changingCount = 0
    for (slot in layout) {
      val column = columns[slot.key] ?: continue
      if (stopFor(column, slot, lastDirection) != column.goalStop()) changingCount += 1
    }
    val gap = if (changingCount > 1) WAVE_TOTAL_SECONDS / (changingCount - 1) else 0f

    var waveIndex = 0
    for (slot in layout) {
      val existing = columns[slot.key]
      if (existing == null) {
        // A column being born starts one stop out, so it arrives the way a roll does.
        val born = Column(slot.key, slot.kind, literalOf(slot))
        born.target = 0
        born.charAt[0] = slot.char
        born.position = -lastDirection.toFloat()
        born.settle = 0f
        born.alive = 0f
        born.x = xRel(slot)
        born.targetX = born.x
        columns[slot.key] = born
        continue
      }
      existing.kind = slot.kind
      existing.targetX = xRel(slot)
      existing.retiring = false

      val next = stopFor(existing, slot, lastDirection)
      if (next != existing.goalStop()) {
        existing.literal = literalOf(slot)
        existing.charAt[next] = slot.char
        // Half a gap on the leader: it does not leave the instant the value changes, but it does
        // not wait a whole step either. The reference's columns start at 70 / 137 / 220 ms; at
        // zero this engine read 35 / 102 / 186 and at a full gap 101 / 176 / 243, which brackets
        // it — there is ~35 ms of latency before the first frame either way, so the leader's own
        // share is about half.
        // Every change pays the hold, including one arriving at a column already rolling.
        //
        // That is measurably not what the reference does — it finishes a burst 545 ms after the
        // last change and a single change in 587, so it is FASTER when already in flight, while
        // this engine takes 586 either way. But the two obvious shortcuts both overshoot: skipping
        // the hold for a column with work queued lands at 469, skipping it for any moving column at
        // 403, against the full hold's 586. The reference's 545 sits between them and none of the
        // three rules produces it, so the smallest error is the simple rule. See the burst section
        // of .agent/IOS_GROUND_TRUTH.md before trying a fourth.
        existing.pending.addLast(PendingStop(next, gap * (waveIndex + 0.5f)))
        waveIndex += 1
      }
    }
    isRunning = true
  }

  fun snapToTarget() {
    val alive = targetLayout.associateBy { it.key }
    val iterator = columns.entries.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      val slot = alive[entry.key]
      if (slot == null) {
        iterator.remove()
        continue
      }
      val column = entry.value
      column.pending.lastOrNull()?.let { column.target = it.stop }
      column.pending.clear()
      column.position = column.target.toFloat()
      column.velocity = 0f
      column.settle = 1f
      column.settleVelocity = 0f
      column.crowdRaw = 0f
      column.crowd = 0f
      column.alive = 1f
      column.retiring = false
      column.literal = literalOf(slot)
      column.charAt.keys.retainAll(setOf(column.target))
      column.charAt[column.target] = slot.char
      column.x = xRel(slot)
      column.targetX = column.x
      column.xVelocity = 0f
    }
    isRunning = false
  }

  fun step(dtSeconds: Float): Boolean {
    val dt = dtSeconds.coerceAtMost(0.04f)
    var active = false

    // A wider number does not take proportionally longer: the wave is a fixed total, and this caps
    // how far the spring itself may stretch with the caller's duration. (APPLE.)
    val response = (RESPONSE_SECONDS * durationScale).coerceIn(
      RESPONSE_SECONDS / MAX_DURATION_MULTIPLE,
      RESPONSE_SECONDS * MAX_DURATION_MULTIPLE,
    )

    val iterator = columns.entries.iterator()
    while (iterator.hasNext()) {
      val column = iterator.next().value

      if (column.retiring) {
        column.alive = max(0f, column.alive - dt / max(0.01f, response))
        if (column.alive <= 0f) {
          iterator.remove()
          continue
        }
        active = true
      } else if (column.alive < 1f) {
        column.alive = min(1f, column.alive + dt / max(0.01f, response))
        active = true
      }

      if (column.pending.isNotEmpty()) {
        val wasAtRest = abs(column.target - column.position) < POSITION_EPSILON
        var arrived = false
        for (entry in column.pending) entry.remaining -= dt
        while (column.pending.isNotEmpty() && column.pending.first().remaining <= 0f) {
          column.target = column.pending.removeFirst().stop
          arrived = true
        }
        // A change that lands on a column already in flight is the whole chase signal. One that
        // lands on a column at rest is a single crossing and must leave the geometry alone.
        if (arrived && !wasAtRest) {
          column.crowdRaw = min(1f, column.crowdRaw + CROWD_STEP)
        }
        if (arrived && wasAtRest) {
          // The follower starts a fresh run only when the column was actually at rest. Springing
          // down from the previous handover's 1 reads as a dip, which is why this reset exists —
          // but resetting it mid-flight is the same mistake the position never makes. In a burst,
          // commits land every 33 ms, so the follower was knocked back to zero over and over and
          // the last one started from scratch: the column kept resolving 41 ms past the reference.
          column.settle = 0f
          column.settleVelocity = 0f
        }
        active = true
      }

      if (stepCrowd(column, dt)) active = true
      if (stepPosition(column, response, dt)) active = true
      if (stepSettle(column, dt)) active = true
      if (stepX(column, response, dt)) active = true
    }

    if (!active) snapToTarget()
    isRunning = active
    return active
  }

  fun samples(): List<GlyphSample> {
    val out = ArrayList<GlyphSample>(columns.size * 2)

    for (column in columns.values) {
      val literal = column.literal
      if (literal != null) {
        // A separator has no strip to roll along; it only fades and glides.
        val alpha = column.alive
        if (alpha <= 0.01f) continue
        out.add(sample(column, literal, stop = column.target, distance = 0f, alpha = alpha))
        continue
      }

      val lowest = floor(column.position).toInt()
      for (stop in lowest..lowest + 1) {
        val distance = abs(stop - column.position)
        if (distance >= 1f) continue

        val presence = 1f - distance
        // Geometry is the arriver's ceiling while it is in flight; the follower takes over once it
        // has landed, which is the only way opacity can still resolve after the motion has stopped.
        //
        // The leaver stays on its distance. Putting it on the fade clock instead, to clear it as
        // early as the reference does, was measured and rejected: it never dips far enough and the
        // crossing's floor went from 0.017 off the reference to 0.144 off.
        // Levelled onto one opacity as the chase rises — half of the pair's ink imbalance. The
        // other half is the shrink, in `sample` below, and they only work together.
        val resting = if (stop == column.target) min(presence, column.settle) else presence
        val opacity = resting + (EVEN_LEVEL - resting) * evenness(column)
        // Weighted by how close this glyph is to its own stop, NOT by which stop is the target.
        //
        // A binary role means the two glyphs exchange exponents the instant the target moves. On a
        // single change that is invisible — the arriving glyph is a whole stop away and its opacity
        // is clamped near zero anyway — but under an alternation it is the whole behaviour: flipping
        // 0/1 every 67 ms made this engine slam between a solid 0 and a solid 1 with three times the
        // reference's swing, 0.307 against 0.103. Forcing both exponents equal collapsed it to
        // 0.082, which is what identified the swap as the cause.
        //
        // Weighting recovers the fitted values where they were fitted — at rest the arrival has
        // presence 1 and gets ENTER, the departure 0 and gets EXIT — while a strip parked between
        // two stops gives both the same blend, with nothing left to swap.
        //
        // Flattening this to the midpoint with the chase as well — the product's third term, worth
        // 1.33 — was measured and rejected. It does what it says on the balance, 0.50 to 0.43 on
        // the roll, and it costs the travel that was the whole point: 0.29 out to 0.43 against the
        // reference's 0.119, consistently across runs. The two exponents are what give the arriver
        // and the leaver different curves, and taking that away makes the pair hand over faster
        // rather than more evenly.
        val exponent =
          EXIT_ALPHA_EXPONENT + (ENTER_ALPHA_EXPONENT - EXIT_ALPHA_EXPONENT) * presence
        val alpha = pow(opacity, exponent) * column.alive
        if (alpha <= 0.01f) continue

        val ch = column.charAt[stop] ?: continue
        out.add(sample(column, ch, stop, distance, alpha))
      }
    }
    return out
  }

  // ── Internals ───────────────────────────────────────────────────────────────────────────────

  private fun sample(
    column: Column,
    ch: String,
    stop: Int,
    distance: Float,
    alpha: Float,
  ): GlyphSample {
    val settled = distance < POSITION_EPSILON &&
      abs(column.velocity) < VELOCITY_EPSILON &&
      column.settle > 0.999f &&
      column.alive > 0.999f
    val role = when {
      settled -> GlyphRole.ANCHOR
      stop == column.target -> GlyphRole.ENTER
      else -> GlyphRole.EXIT
    }
    // The face's angle around the drum's axis. Signed, so a glyph above the front and one below it
    // are foreshortened alike but offset opposite ways.
    val angle = (stop - column.position) * FACE_ANGLE
    // Both glyphs are pulled onto ONE distance as the chase rises — see EVEN_SHRINK_AT. Area goes
    // as the square of the shrink, so at 0.8 against 0.2 presence it alone hands the near glyph 2.1
    // times the far one's ink, the half of the imbalance levelling the opacity cannot touch. The
    // level is past their average rather than at 1, so the pair comes out even AND small.
    val even = evenness(column)
    val shrink = 1f - SCALE_AMOUNT * (distance + (EVEN_SHRINK_AT - distance) * even)
    // Only the OFFSET widens with the chase. The foreshortening is the face's own angle and has
    // nothing to do with how big the drum is, which is the whole reason the two are separate.
    val apothem = APOTHEM * (1f + CROWD_SPREAD * column.crowd)
    return GlyphSample(
      key = column.key,
      ch = ch,
      kind = column.kind,
      role = role,
      x = column.x,
      offsetY = apothem * lineHeightPx * sin(angle),
      alpha = alpha.coerceIn(0f, 1f),
      scaleX = shrink,
      scaleY = shrink * cos(angle),
      blurLengthPx = if (settled) 0f else lineHeightPx * BLUR_FRACTION * distance,
      stable = settled,
    )
  }

  /**
   * How evenly this column's pair is drawn right now: 0 at rest, 1 while fully chased.
   *
   * Off the RAW signal, not the lagged one the apothem uses, and the roll tail is why. The lag adds
   * ~150 ms on top of the bleed, so the last crossing of a burst — which takes ~400 ms — was still
   * being drawn evenly for most of its length, never dipped, and read as finished at 236 ms against
   * the reference's 615, with its floor at 0.637 against 0.409. The reference's last crossing looks
   * like an ordinary one. The raw signal is back to zero within `CROWD_RELAX`, which leaves the
   * final crossing to resolve on its own.
   *
   * The lag was there to stop a sawtooth, and this does not need it: through a fast alternation the
   * raw signal saws between 0.6 and 1.0, and 2.0 times either of those clamps to 1.
   */
  private fun evenness(column: Column): Float = min(1f, CROWD_EVEN * column.crowdRaw)

  private fun literalOf(slot: KeyedSlot): String? =
    if (slot.kind == TokenKind.DIGIT) null else slot.char

  /**
   * The stop this slot wants, reached from `from` in `direction`.
   *
   * A digit column takes the run that goes the way the value moved — 9 → 0 on an increment is one
   * step forward, not nine back — which is what makes a carry read as part of the same roll.
   */
  private fun stopFor(column: Column, slot: KeyedSlot, direction: Int): Int {
    val from = column.goalStop()
    if (slot.kind != TokenKind.DIGIT) return from
    if (column.charAt[from] == slot.char) return from
    // MINUS the direction, which is not a typo. Measured on a matched pair of reference runs from
    // the same starting value, tracking each column's ink centroid: 2,599 -> 2,722 moves the ink
    // DOWN by 0.070 glyph heights and 2,599 -> 2,476 moves it UP by 0.054. So the reference brings
    // an incrementing digit in from ABOVE. This engine had it the other way on both directions.
    return from - direction
  }

  private fun stepPosition(column: Column, response: Float, dt: Float): Boolean {
    val error = column.target - column.position
    if (abs(error) <= POSITION_EPSILON && abs(column.velocity) <= VELOCITY_EPSILON) {
      column.position = column.target.toFloat()
      column.velocity = 0f
      return false
    }
    val omega = (2.0 * Math.PI / max(0.05f, response)).toFloat()
    column.velocity +=
      ((omega * omega * error) - (2f * DAMPING_RATIO * omega * column.velocity)) * dt
    column.position += column.velocity * dt
    return true
  }

  /**
   * Bleed the chase signal and lag the value the geometry reads behind it.
   *
   * Returns true while either is still moving, because the apothem is still changing and therefore
   * so is where the glyphs are — a column that stopped asking for frames here would freeze part way
   * through relaxing back to its resting width.
   */
  private fun stepCrowd(column: Column, dt: Float): Boolean {
    if (column.crowdRaw <= 0f && column.crowd <= 0.001f) {
      column.crowd = 0f
      return false
    }
    column.crowdRaw = max(0f, column.crowdRaw - dt / CROWD_RELAX)
    column.crowd += (column.crowdRaw - column.crowd) * min(1f, dt / CROWD_RELAX)
    return true
  }

  private fun stepSettle(column: Column, dt: Float): Boolean {
    val arrived = (1f - min(1f, abs(column.target - column.position))).coerceIn(0f, 1f)
    if (abs(column.settle - arrived) <= 0.002f && abs(column.settleVelocity) <= VELOCITY_EPSILON) {
      column.settle = arrived
      column.settleVelocity = 0f
      return false
    }
    val omega = (2.0 * Math.PI / SETTLE_RESPONSE_SECONDS).toFloat()
    column.settleVelocity +=
      ((omega * omega * (arrived - column.settle)) -
        (2f * DAMPING_RATIO * omega * column.settleVelocity)) * dt
    column.settle = (column.settle + column.settleVelocity * dt).coerceIn(0f, 1f)
    return true
  }

  private fun stepX(column: Column, response: Float, dt: Float): Boolean {
    val error = column.targetX - column.x
    if (abs(error) <= 0.1f && abs(column.xVelocity) <= 0.1f) {
      column.x = column.targetX
      column.xVelocity = 0f
      return false
    }
    val omega = (2.0 * Math.PI / max(0.05f, response)).toFloat()
    column.xVelocity +=
      ((omega * omega * error) - (2f * DAMPING_RATIO * omega * column.xVelocity)) * dt
    column.x += column.xVelocity * dt
    return true
  }

  private fun xRel(slot: KeyedSlot): Float = slot.centerFromLeft - slot.totalWidth / 2f

  private fun pow(base: Float, exponent: Float): Float =
    Math.pow(base.toDouble(), exponent.toDouble()).toFloat()
}
