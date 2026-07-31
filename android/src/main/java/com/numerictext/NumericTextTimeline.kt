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
  )

  companion object {
    const val STEP_FRACTION = 0.5f
    private const val BASE_RESPONSE_SECONDS = 0.40f
    private const val DAMPING_RATIO = 0.80f
    private const val POSITION_EPSILON = 0.001f
    private const val VELOCITY_EPSILON = 0.005f

    const val VISIBLE_RANGE = 1.0f
    internal const val BLUR_X_FACTOR = 0.05f

    /* ---- ported from per-slot-springs TransitionLogic ---- */

    private const val TOP_SLOPE = 0.8f
    fun presenceAlpha(presence: Float): Float {
      val c = presence.coerceIn(0f, 1f); val c2 = c * c; val c3 = c2 * c
      return (3f * c2 - 2f * c3) + TOP_SLOPE * (c3 - c2)
    }

    fun presenceScale(presence: Float, minScale: Float, exponent: Float = 2.2f): Float {
      val a = 1f - presence.coerceIn(0f, 1f)
      return 1f - (1f - minScale) * Math.pow(a.toDouble(), exponent.toDouble()).toFloat()
    }

    private const val BLUR_ONSET = 0.10f
    private const val BLUR_FULL = 0.62f
    fun presenceBlur(presence: Float, softness: Float = 1f): Float {
      val a = 1f - presence.coerceIn(0f, 1f)
      val onset = BLUR_ONSET * softness
      return smoothstep(onset, BLUR_FULL, a)
    }

    fun presenceOffsetFraction(presence: Float): Float {
      val a = 1f - presence
      val m = Math.pow(abs(a).toDouble(), 1.43).toFloat()
      return if (a < 0f) -m else m
    }

    fun rollOffsetShape(off: Float): Float {
      val m = Math.pow(abs(off).toDouble(), 1.43).toFloat()
      return if (off < 0f) -m else m
    }

    fun deathBlur(presence: Float): Float =
      Math.pow((1f - presence.coerceIn(0f, 1f)).toDouble(), 1.6).toFloat()

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
      val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
      return t * t * (3f - 2f * t)
    }

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
        val currentTargetGlyph = column.glyphs[column.target]
        if (currentTargetGlyph != slot.char) {
          val next = column.target + directionSign
          column.glyphs[next] = slot.char
          column.target = next
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
      val dir = if (velocityPx < 0f) -1f else 1f

      val sortedIndices = c.glyphs.keys.sorted()
      for (index in sortedIndices) {
        val ch = c.glyphs[index] ?: continue
        val relative = index - c.position
        val distance = abs(relative)

        if (distance >= VISIBLE_RANGE) continue

        val presence = (1f - distance / VISIBLE_RANGE).coerceIn(0f, 1f)
        val isEntering = index == c.target
        val isSettled = !isRunning && distance < POSITION_EPSILON

        val role = when {
          isSettled -> GlyphRole.ANCHOR
          isEntering -> GlyphRole.ENTER
          else -> GlyphRole.EXIT
        }

        val alpha = presenceAlpha(presence)
        val (minS, exp) = when (role) {
          GlyphRole.EXIT -> 0.74f to 2.2f
          GlyphRole.ENTER -> 0.66f to 0.55f
          else -> 1f to 2.2f
        }
        val scale = presenceScale(presence, minS, exp)

        val blurPx = lineHeightPx * 0.18f * presenceBlur(presence)

        val finalAlpha = (alpha * c.retireAlpha).coerceIn(0f, 1f)
        if (finalAlpha <= 0.01f) continue

        val shapedOff = rollOffsetShape(relative)
        val offsetY = shapedOff * stepPx

        out.add(
          GlyphSample(
            key = c.key, ch = ch, kind = c.kind, role = role,
            x = c.x, offsetY = offsetY,
            alpha = finalAlpha, scale = scale,
            velocityY = velocityPx, blurLengthPx = blurPx,
            direction = dir, stable = isSettled,
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