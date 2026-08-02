package com.numerictext

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Motore a rullo per colonne persistenti basato su molle smorzate.
 * Ogni colonna di cifre è una molla indipendente con momentum.
 * Il retargeting sposta solo il target di ciascuna colonna senza ricostruire.
 * Il rullo mostra le cifre in transizione in simultanea per l'effetto tamburo.
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
    val velocityY: Float,
    val blurLengthPx: Float,
    val direction: Float,
    val stable: Boolean,
  )

  private data class Column(
    val key: String,
    var kind: TokenKind,
    val glyphs: MutableMap<Int, String> = HashMap(),
    var position: Float = 0f,
    var velocity: Float = 0f,
    var target: Int = 0,
    var targetX: Float = 0f,
    var x: Float = 0f,
    var xVelocity: Float = 0f,
    var retiring: Boolean = false,
    var retireAlpha: Float = 1f,
    /** Where this column is going once its turn in the wave comes. */
    var pendingTarget: Int? = null,
    /** Seconds still to wait before [pendingTarget] becomes [target]. */
    var hold: Float = 0f,
  )

  companion object {
    /**
     * Separation between consecutive digits on the strip, as a fraction of the line height — which
     * is the same physical quantity the old renderer called `travelFactor`.
     *
     * 0.5 -> 0.29, carried over from the exact-recorder fit of 2026-08-02 (see
     * .agent/IOS_GROUND_TRUTH.md): at each column's floor the reference keeps its crossing pair
     * inside 1.18-1.22 glyph heights, and `extent = glyphHeight + 1.21 x separation` solves to
     * 0.302 / 0.294 / 0.274 on the three columns independently. Re-fit here rather than trusted,
     * since the two engines place their glyphs differently.
     */
    const val STEP_FRACTION = 0.29f

    /**
     * Seconds between one column starting and the next one to its right.
     *
     * The reference does not move its columns together: measured with the exact recorder, the
     * three columns of a plain roll begin 67-83 ms apart and their ink floors step by the same
     * amount. Without this the whole old number slides off as one block while the whole new one
     * slides in — two legible compositions swapping, which is the single largest visual difference.
     *
     * It is a hold on the column's TARGET, never on its position: a glyph already on screen is
     * never restarted, so a burst still merges into one continuous roll.
     */
    const val COLUMN_STAGGER_SECONDS = 0.075f

    /** Fisica molla reattiva e smorzata. */
    private const val BASE_RESPONSE_SECONDS = 0.40f
    private const val DAMPING_RATIO = 0.80f

    /** Soglie di arresto. */
    private const val POSITION_EPSILON = 0.001f
    private const val VELOCITY_EPSILON = 0.005f

    /** Modello ottico. */
    private const val VISIBLE_RANGE = 1.0f
    private const val SHUTTER_SECONDS = 0.080f
    private const val MAX_TRAIL_FRACTION = 0.60f
    internal const val BLUR_X_FACTOR = 0.05f

    /** Asimmetria ENTER / EXIT */
    private const val ENTER_ALPHA_START = 0.15f
    private const val ENTER_START_SCALE = 0.65f
    private const val EXIT_ALPHA_HOLD = 0.35f
    private const val EXIT_END_SCALE = 0.75f

    fun smootherstep(x: Float): Float {
      val t = x.coerceIn(0f, 1f)
      return t * t * t * (t * (t * 6f - 15f) + 10f)
    }
  }

  private val columns = LinkedHashMap<String, Column>()
  private var targetLayout: List<KeyedSlot> = emptyList()
  private var lineHeightPx: Float = 1f
  private var durationMs: Long = 320L
  private var directionSign: Int = 1

  var targetText: String = ""
    private set

  var isRunning: Boolean = false
    private set

  fun reset(layout: List<KeyedSlot>, text: String, lineHeight: Float) {
    columns.clear()
    targetLayout = layout
    targetText = text
    lineHeightPx = max(1f, lineHeight)
    for (slot in layout) {
      columns[slot.key] = Column(
        key = slot.key,
        kind = slot.kind,
        glyphs = hashMapOf(0 to slot.char),
        position = 0f,
        target = 0,
        targetX = xRel(slot),
        x = xRel(slot),
      )
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
    durationMs = animationDurationMs.coerceAtLeast(80L)
    directionSign = if (direction < 0) 1 else -1
    targetText = text
    targetLayout = layout

    val incoming = layout.associateBy { it.key }

    for (column in columns.values) {
      if (incoming[column.key] == null) column.retiring = true
    }

    var changing = 0
    for (slot in layout) {
      val x = xRel(slot)
      val column = columns[slot.key]
      if (column == null) {
        val c = Column(
          key = slot.key,
          kind = slot.kind,
          glyphs = hashMapOf(0 to slot.char),
          position = -directionSign.toFloat(),
          velocity = 0f,
          target = 0,
          targetX = x,
          x = x,
          retireAlpha = 0f,
        )
        columns[slot.key] = c
      } else {
        column.kind = slot.kind
        column.targetX = x
        column.retiring = false
        val currentTargetGlyph = column.glyphs[column.pendingTarget ?: column.target]
        if (currentTargetGlyph != slot.char) {
          val next = (column.pendingTarget ?: column.target) + directionSign
          column.glyphs[next] = slot.char
          // Left-to-right wave. Only the columns that actually change content are counted, so a
          // change deep in the number does not inherit a delay from unchanged columns to its left.
          column.pendingTarget = next
          column.hold = COLUMN_STAGGER_SECONDS * changing
          changing += 1
        }
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
      } else {
        val c = entry.value
        c.retiring = false
        c.retireAlpha = 1f
        c.pendingTarget?.let { c.target = it }
        c.pendingTarget = null
        c.hold = 0f
        c.position = c.target.toFloat()
        c.velocity = 0f
        c.x = xRel(slot)
        c.targetX = c.x
        c.xVelocity = 0f
        c.glyphs.clear()
        c.glyphs[c.target] = slot.char
      }
    }
    isRunning = false
  }

  fun step(dtSeconds: Float): Boolean {
    val dt = dtSeconds.coerceAtMost(0.04f)
    var active = false

    val scaleFactor = durationMs / 320f
    val baseResponse = BASE_RESPONSE_SECONDS * scaleFactor

    val alive = targetLayout.associateBy { it.key }
    val iterator = columns.entries.iterator()

    while (iterator.hasNext()) {
      val entry = iterator.next()
      val c = entry.value

      if (c.retiring) {
        c.retireAlpha = max(0f, c.retireAlpha - dt * 10f)
        if (c.retireAlpha <= 0f) {
          iterator.remove()
          continue
        }
        active = true
      } else {
        c.retireAlpha = min(1f, c.retireAlpha + dt * 12f)
      }

      if (c.pendingTarget != null) {
        c.hold -= dt
        if (c.hold <= 0f) {
          c.target = c.pendingTarget!!
          c.pendingTarget = null
        }
        active = true
      }

      val posError = c.target.toFloat() - c.position
      val dist = abs(posError)

      val adaptiveResponse = if (dist > 0.8f) {
        baseResponse / (1f + 0.80f * (dist - 0.8f))
      } else {
        baseResponse
      }

      val omega = (2f * Math.PI / max(0.05f, adaptiveResponse)).toFloat()
      val accel = (omega * omega * posError) - (2f * DAMPING_RATIO * omega * c.velocity)

      c.velocity += accel * dt
      c.position += c.velocity * dt

      if (abs(c.position - c.target) > POSITION_EPSILON || abs(c.velocity) > VELOCITY_EPSILON) {
        active = true
      } else {
        c.pendingTarget?.let { c.target = it }
        c.pendingTarget = null
        c.hold = 0f
        c.position = c.target.toFloat()
        c.velocity = 0f
      }

      val slot = alive[c.key]
      val targetXVal = slot?.let { xRel(it) } ?: c.targetX
      c.targetX = targetXVal

      val xError = c.targetX - c.x
      val xOmega = (2f * Math.PI / baseResponse).toFloat()
      val xAccel = (xOmega * xOmega * xError) - (2f * DAMPING_RATIO * xOmega * c.xVelocity)

      c.xVelocity += xAccel * dt
      c.x += c.xVelocity * dt

      if (abs(c.x - c.targetX) > 0.1f || abs(c.xVelocity) > 0.1f) {
        active = true
      }

      pruneGlyphs(c)
    }

    if (!active) {
      snapToTarget()
    }
    isRunning = active
    return active
  }

  fun samples(): List<GlyphSample> {
    val out = ArrayList<GlyphSample>()
    val stepPx = lineHeightPx * STEP_FRACTION

    for (c in columns.values) {
      val velocityPx = c.velocity * stepPx
      val trail = (abs(velocityPx) * SHUTTER_SECONDS).coerceAtMost(lineHeightPx * MAX_TRAIL_FRACTION)
      val dir = if (velocityPx < 0f) -1f else 1f

      val sortedIndices = c.glyphs.keys.sorted()
      for (index in sortedIndices) {
        val ch = c.glyphs[index] ?: continue
        val relative = index - c.position
        val distance = abs(relative)

        if (distance >= VISIBLE_RANGE) continue

        val p = (distance / VISIBLE_RANGE).coerceIn(0f, 1f)
        val isEntering = index == c.target
        val isSettled = !isRunning && distance < POSITION_EPSILON

        val role = when {
          isSettled -> GlyphRole.ANCHOR
          isEntering -> GlyphRole.ENTER
          else -> GlyphRole.EXIT
        }

        val (alpha, scale, extraBlur) = when (role) {
          GlyphRole.ANCHOR -> Triple(1f, 1f, 0f)
          GlyphRole.EXIT -> {
            val a = 1f - p * p * p
            val s = 1f - p * (1f - EXIT_END_SCALE)
            val b = lineHeightPx * MAX_TRAIL_FRACTION * p * p
            Triple(a, s, b)
          }
          GlyphRole.ENTER -> {
            val a = ENTER_ALPHA_START + (1f - ENTER_ALPHA_START) * (1f - p)
            val s = ENTER_START_SCALE + (1f - ENTER_START_SCALE) * (1f - p)
            val b = lineHeightPx * MAX_TRAIL_FRACTION * (1f - p)
            Triple(a, s, b)
          }
        }

        val finalAlpha = (alpha * c.retireAlpha).coerceIn(0f, 1f)

        if (finalAlpha <= 0.01f) continue

        val finalBlur = if (role == GlyphRole.ANCHOR) 0f else trail + extraBlur

        out.add(
          GlyphSample(
            key = c.key,
            ch = ch,
            kind = c.kind,
            role = role,
            x = c.x,
            offsetY = relative * stepPx,
            alpha = finalAlpha,
            scale = scale,
            velocityY = velocityPx,
            blurLengthPx = finalBlur,
            direction = dir,
            stable = isSettled,
          )
        )
      }
    }
    return out
  }

  fun targetWidth(): Float = targetLayout.firstOrNull()?.totalWidth ?: 0f

  private fun pruneGlyphs(c: Column) {
    val centre = c.position.roundToInt()
    val minIndex = min(centre, c.target) - 1
    val maxIndex = max(centre, c.target) + 1
    val iterator = c.glyphs.keys.iterator()
    while (iterator.hasNext()) {
      val i = iterator.next()
      if (i < minIndex || i > maxIndex) iterator.remove()
    }
  }

  private fun xRel(slot: KeyedSlot): Float {
    val total = targetWidth()
    return slot.centerFromLeft - total / 2f
  }
}