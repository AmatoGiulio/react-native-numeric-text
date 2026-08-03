package com.numerictext

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

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
    val scale: Float,
    val blurLengthPx: Float,
    val stable: Boolean,
  )

  companion object {
    /**
     * Separation of two stops, in line heights.
     *
     * Apple's `NumericTextConfiguration.offset` is 19/32 = 0.59375 and this was that, but the
     * side-by-side grid says the reference's two glyphs CONTEND for the same space — they overlap,
     * blurred, rather than sliding past each other stacked apart. Its crossing spans 1.18 glyph
     * heights against 1.55 here, and that gap did not close across five fits of every other knob.
     * So 0.59375 is not the separation of two visible stops; being fitted here instead.
     */
    const val STEP_FRACTION = 0.32f

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
    const val BLUR_FRACTION = 0.42f

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
    const val EXIT_ALPHA_EXPONENT = 1.60f

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
    val stepPx = lineHeightPx * STEP_FRACTION

    for (column in columns.values) {
      val literal = column.literal
      if (literal != null) {
        // A separator has no strip to roll along; it only fades and glides.
        val alpha = column.alive
        if (alpha <= 0.01f) continue
        out.add(
          sample(column, literal, stop = column.target, distance = 0f, alpha = alpha, stepPx = stepPx)
        )
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
        val opacity = if (stop == column.target) min(presence, column.settle) else presence
        val exponent =
          if (stop == column.target) ENTER_ALPHA_EXPONENT else EXIT_ALPHA_EXPONENT
        val alpha = pow(opacity, exponent) * column.alive
        if (alpha <= 0.01f) continue

        val ch = column.charAt[stop] ?: continue
        out.add(sample(column, ch, stop, distance, alpha, stepPx))
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
    stepPx: Float,
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
    return GlyphSample(
      key = column.key,
      ch = ch,
      kind = column.kind,
      role = role,
      x = column.x,
      offsetY = (stop - column.position) * stepPx,
      alpha = alpha.coerceIn(0f, 1f),
      scale = 1f - SCALE_AMOUNT * distance,
      blurLengthPx = if (settled) 0f else lineHeightPx * BLUR_FRACTION * distance,
      stable = settled,
    )
  }

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
