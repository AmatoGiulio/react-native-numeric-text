package com.numerictext

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/** Persistent STACK renderer used by numericText on Android. */
internal class NumericRollEngine {

  enum class GlyphRole { ANCHOR, ENTER, EXIT }

  data class GlyphSample(
    val key: String,
    val ch: String,
    val kind: TokenKind,
    val role: GlyphRole,
    val renderId: Int = 0,
    val x: Float,
    val offsetY: Float,
    val alpha: Float,
    val scaleX: Float,
    val scaleY: Float,
    val blurLengthPx: Float,
    val stable: Boolean,
  )

  companion object {
    private var nextEntryId = 0

    const val BUILD_ID = "STACK-CLEAN-2026-08-07"

    private const val STACK_OFFSET = 0.3950f
    private const val STACK_FINAL_SCALE = 0.3984f
    private const val STACK_RESPONSE_SECONDS = 0.353f
    private const val STACK_DAMPING = 0.55f
    private const val STACK_SLOW_RESPONSE_SECONDS = 0.277f
    private const val STACK_SLOW_DAMPING = 1.00f
    private const val STACK_BLUR_FRACTION = 0.42f
    private const val STACK_EXIT_BLUR_SPEEDUP = 1.35f
    private const val STACK_CROWD_SPEEDUP = 0.70f
    private const val STACK_ALPHA_CEILING = 1.00f
    private const val STACK_BLUR_RESPONSE_SECONDS = 0.398f
    private const val STACK_BLUR_DAMPING = 0.91f
    private const val STACK_FLIP_BORN = 1.5f
    private const val STACK_FLIP_SOFT = 2.0f
    private const val STACK_LANE = 0.267f
    private const val STACK_LANE_SOFT = 0.30f
    private const val STACK_LANE_GATE = 0.12f

    private const val FLIP_STEP = 0.60f
    private const val FLIP_RELAX = 0.15f
    private const val CROWD_STEP = 0.60f
    private const val CROWD_RELAX = 0.15f

    private const val WAVE_TOTAL_SECONDS = 0.15f
    private const val MAX_DURATION_MULTIPLE = 1.25f
    private const val RESPONSE_SECONDS = 0.30f
    private const val X_DAMPING_RATIO = 0.90f

    private const val POSITION_EPSILON = 0.001f
    private const val VELOCITY_EPSILON = 0.005f
    private const val ENTRY_CULL_ALPHA = 0.004f
    private const val RENDER_ALPHA_EPSILON = 0.01f
    private const val STRUCTURAL_ENTRY_ALPHA = 0.32f
  }

  private enum class PendingKind { CHANGE, REMOVE, ENTER }

  private class PendingStop(
    val stop: Int,
    val direction: Int,
    val enqueuedAtNanos: Long,
    val dueAtNanos: Long,
    val kind: PendingKind,
  )

  private class Entry(val ch: String, var p: Float) {
    val id: Int = nextEntryId++

    var velocity = 0f
    var target = 0f
    var posTarget = 0f

    var q = p
    var qVelocity = 0f

    var b = abs(p)
    var bVelocity = 0f
    var blurTarget = 0f

    var superseded = false

    var alpha = 0f
    var alphaVelocity = 0f
    var alphaTarget = 1f
  }

  private class Column(
    val key: String,
    var kind: TokenKind,
  ) {
    val charAt = HashMap<Int, String>()
    val pending = ArrayDeque<PendingStop>()
    val entries = ArrayList<Entry>()

    var target = 0

    var crowdRaw = 0f
    var crowd = 0f

    var flipRaw = 0f
    var flipGate = 0f
    var lastDir: Int? = null

    var x = 0f
    var xVelocity = 0f
    var targetX = 0f

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

  fun reset(layout: List<KeyedSlot>, text: String, lineHeight: Float) {
    columns.clear()
    targetLayout = layout
    targetText = text
    lineHeightPx = max(1f, lineHeight)

    for (slot in layout) {
      val column = Column(slot.key, slot.kind)
      column.charAt[0] = slot.char
      column.entries.add(Entry(slot.char, 0f).also { it.alpha = 1f })
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

    val previousSlots = targetLayout
    val previousByKey = previousSlots.associateBy { it.key }

    targetText = text
    targetLayout = layout

    val eventNanos = System.nanoTime()
    val incomingByKey = layout.associateBy { it.key }

    val structuralEnterKeys = HashSet<String>()
    for (slot in layout) {
      if (previousByKey[slot.key] == null) {
        structuralEnterKeys.add(slot.key)
      }
    }

    val structuralRemovalKeys = HashSet<String>()
    for (slot in previousSlots) {
      if (incomingByKey[slot.key] == null) {
        structuralRemovalKeys.add(slot.key)
      }
    }

    for ((key, column) in columns) {
      column.retiring = incomingByKey[key] == null
    }

    val digitEventKeys = LinkedHashSet<String>()

    for (slot in layout) {
      if (slot.kind != TokenKind.DIGIT) continue

      val column = columns[slot.key]
      if (
        structuralEnterKeys.contains(slot.key) ||
          column == null ||
          stopFor(column, slot, lastDirection) != column.goalStop()
      ) {
        digitEventKeys.add(slot.key)
      }
    }

    for (slot in previousSlots) {
      if (slot.kind == TokenKind.DIGIT && structuralRemovalKeys.contains(slot.key)) {
        digitEventKeys.add(slot.key)
      }
    }

    val changingCount = digitEventKeys.size
    val gap = if (changingCount > 1) WAVE_TOTAL_SECONDS / (changingCount - 1) else 0f

    fun enqueue(
      column: Column,
      stop: Int,
      kind: PendingKind,
      wavePhase: Int,
    ) {
      val phase =
        if (changingCount > 0) wavePhase.coerceIn(0, changingCount - 1) else 0

      val waveDelayNanos =
        (
          gap.toDouble() *
            (phase + 0.5) *
            1_000_000_000.0
        ).toLong()

      val requestedDueNanos = eventNanos + waveDelayNanos
      val previousPending = column.pending.lastOrNull()

      val dueAtNanos =
        if (previousPending == null) {
          requestedDueNanos
        } else {
          val arrivalSpacingNanos =
            (eventNanos - previousPending.enqueuedAtNanos).coerceAtLeast(1L)

          maxOf(
            requestedDueNanos,
            previousPending.dueAtNanos + arrivalSpacingNanos,
          )
        }

      column.pending.addLast(
        PendingStop(
          stop = stop,
          direction = lastDirection,
          enqueuedAtNanos = eventNanos,
          dueAtNanos = dueAtNanos,
          kind = kind,
        )
      )
    }

    val newPhaseByKey = wavePhases(layout, digitEventKeys, changingCount)
    val oldPhaseByKey = wavePhases(previousSlots, digitEventKeys, changingCount)

    for (slot in layout) {
      var column = columns[slot.key]

      if (column == null) {
        column = Column(slot.key, slot.kind)
        column.x = xRel(slot)
        column.targetX = column.x
        columns[slot.key] = column
      }

      column.kind = slot.kind
      column.targetX = xRel(slot)
      column.retiring = false

      if (structuralEnterKeys.contains(slot.key)) {
        val stop = column.goalStop()
        column.charAt[stop] = slot.char

        enqueue(
          column = column,
          stop = stop,
          kind = PendingKind.ENTER,
          wavePhase = newPhaseByKey[slot.key] ?: 0,
        )
        continue
      }

      val next = stopFor(column, slot, lastDirection)
      if (next != column.goalStop()) {
        column.charAt[next] = slot.char

        enqueue(
          column = column,
          stop = next,
          kind = PendingKind.CHANGE,
          wavePhase = newPhaseByKey[slot.key] ?: 0,
        )
      }
    }

    for (slot in previousSlots) {
      if (!structuralRemovalKeys.contains(slot.key)) continue
      val column = columns[slot.key] ?: continue

      enqueue(
        column = column,
        stop = column.goalStop(),
        kind = PendingKind.REMOVE,
        wavePhase = oldPhaseByKey[slot.key] ?: 0,
      )
    }

    isRunning = true
  }

  fun snapToTarget() {
    val alive = targetLayout.associateBy { it.key }
    val iterator = columns.entries.iterator()

    while (iterator.hasNext()) {
      val mapEntry = iterator.next()
      val slot = alive[mapEntry.key]
      if (slot == null) {
        iterator.remove()
        continue
      }

      val column = mapEntry.value
      column.pending.lastOrNull()?.let { column.target = it.stop }
      column.pending.clear()
      column.crowdRaw = 0f
      column.crowd = 0f
      column.flipRaw = 0f
      column.flipGate = 0f
      column.lastDir = null
      column.retiring = false
      column.charAt.keys.retainAll(setOf(column.target))
      column.charAt[column.target] = slot.char
      column.entries.clear()
      column.entries.add(Entry(slot.char, 0f).also { it.alpha = 1f })
      column.x = xRel(slot)
      column.targetX = column.x
      column.xVelocity = 0f
    }

    isRunning = false
  }

  fun step(dtSeconds: Float): Boolean {
    val dt = dtSeconds.coerceAtMost(0.04f)
    val nowNanos = System.nanoTime()
    var active = false

    val response = (RESPONSE_SECONDS * durationScale).coerceIn(
      RESPONSE_SECONDS / MAX_DURATION_MULTIPLE,
      RESPONSE_SECONDS * MAX_DURATION_MULTIPLE,
    )

    val iterator = columns.entries.iterator()
    while (iterator.hasNext()) {
      val column = iterator.next().value

      val structuralExitComplete =
        column.retiring &&
          column.pending.isEmpty() &&
          column.entries.isNotEmpty() &&
          column.entries.all {
            it.superseded &&
              it.alpha <= ENTRY_CULL_ALPHA &&
              abs(it.alphaVelocity) <= VELOCITY_EPSILON &&
              abs(it.p - it.posTarget) <= POSITION_EPSILON &&
              abs(it.velocity) <= VELOCITY_EPSILON
          }

      if (structuralExitComplete) {
        iterator.remove()
        continue
      }

      if (column.pending.isNotEmpty()) {
        active = true

        if (column.pending.first().dueAtNanos <= nowNanos) {
          val pendingStop = column.pending.removeFirst()
          val stop = pendingStop.stop
          val commitDir = pendingStop.direction

          val wasAtRest =
            column.entries.all {
              abs(it.p) < POSITION_EPSILON && abs(it.velocity) < VELOCITY_EPSILON
            }

          column.target = stop

          if (!wasAtRest) {
            column.crowdRaw = min(1f, column.crowdRaw + CROWD_STEP)
          }

          val oldDir = column.lastDir
          if (oldDir != null && oldDir != commitDir && !wasAtRest) {
            column.flipRaw = min(1f, column.flipRaw + FLIP_STEP)
          }
          column.lastDir = commitDir

          when (pendingStop.kind) {
            PendingKind.REMOVE -> commitRemove(column, commitDir)
            PendingKind.ENTER -> commitEnter(column, stop, commitDir)
            PendingKind.CHANGE -> commitChange(column, stop, commitDir, oldDir, wasAtRest)
          }

          active = true
        }
      }

      if (stepCrowd(column, dt)) active = true
      if (stepEntries(column, response, dt)) active = true
      if (stepX(column, response, dt)) active = true
    }

    if (!active) snapToTarget()
    isRunning = active
    return active
  }

  fun samples(): List<GlyphSample> {
    val out = ArrayList<GlyphSample>(columns.size * 2)
    for (column in columns.values) emitStack(column, out)
    return out
  }

  private fun commitRemove(column: Column, direction: Int) {
    for (entry in column.entries) {
      if (!entry.superseded) {
        supersede(entry, direction)
      }
    }
  }

  private fun commitEnter(column: Column, stop: Int, direction: Int) {
    val ch = column.charAt[stop] ?: return
    val entry = Entry(ch, incomingAmplitude(direction, column.flipRaw))
    entry.alpha = STRUCTURAL_ENTRY_ALPHA
    column.entries.add(entry)
  }

  private fun commitChange(
    column: Column,
    stop: Int,
    direction: Int,
    oldDirection: Int?,
    wasAtRest: Boolean,
  ) {
    val ch = column.charAt[stop] ?: return

    for (entry in column.entries) {
      if (!entry.superseded) {
        supersede(entry, direction)
      }
    }

    val reversing =
      oldDirection != null &&
        oldDirection != direction &&
        !wasAtRest

    val reuse =
      if (reversing) {
        column.entries.lastOrNull { it.superseded && it.ch == ch }
      } else {
        null
      }

    if (reuse != null) {
      reuse.superseded = false
      reuse.target = 0f
      reuse.posTarget = 0f
      reuse.alphaTarget = 1f
      reuse.blurTarget = 0f
      return
    }

    val entry = Entry(ch, incomingAmplitude(direction, column.flipRaw))
    if (column.entries.isEmpty()) {
      entry.alpha = STRUCTURAL_ENTRY_ALPHA
    }
    column.entries.add(entry)
  }

  private fun supersede(entry: Entry, direction: Int) {
    entry.superseded = true
    entry.target = direction.toFloat()
    entry.posTarget = direction.toFloat()
    entry.blurTarget = 1f
    entry.alphaTarget = 0f
  }

  private fun incomingAmplitude(direction: Int, flipRaw: Float): Float =
    -direction.toFloat() *
      (1f + (STACK_FLIP_BORN - 1f) * flipRaw)

  private fun emitStack(column: Column, out: MutableList<GlyphSample>) {
    val count = column.entries.size
    if (count == 0) return

    val raw = FloatArray(count)
    var total = 0f

    for (i in 0 until count) {
      val presence = max(0f, column.entries[i].alpha)
      raw[i] = presence
      total += presence
    }

    val norm =
      if (total > STACK_ALPHA_CEILING) STACK_ALPHA_CEILING / total else 1f

    for (i in 0 until count) {
      val alpha = raw[i] * norm
      if (alpha <= RENDER_ALPHA_EPSILON) continue

      val entry = column.entries[i]
      val distance = min(1f, abs(entry.q))

      val hasOtherVisibleEntry =
        column.entries.indices.any { j ->
          j != i && column.entries[j].alpha > RENDER_ALPHA_EPSILON
        }

      val settled =
        i == count - 1 &&
          !hasOtherVisibleEntry &&
          abs(entry.p) < POSITION_EPSILON &&
          distance < POSITION_EPSILON &&
          abs(entry.velocity) < VELOCITY_EPSILON

      val shrink = 1f - (1f - STACK_FINAL_SCALE) * distance
      val baseOffset = STACK_OFFSET * entry.p
      val effectiveOffset =
        if (column.flipGate > 0f) {
          val laneTarget = STACK_LANE * tanh(entry.p / max(1e-3f, STACK_LANE_SOFT))
          baseOffset + (laneTarget - baseOffset) * column.flipGate
        } else {
          baseOffset
        }

      out.add(
        GlyphSample(
          key = column.key,
          ch = entry.ch,
          kind = column.kind,
          role = when {
            settled -> GlyphRole.ANCHOR
            i == count - 1 -> GlyphRole.ENTER
            else -> GlyphRole.EXIT
          },
          renderId = entry.id,
          x = column.x,
          offsetY = effectiveOffset * lineHeightPx,
          alpha = alpha.coerceIn(0f, 1f),
          scaleX = shrink,
          scaleY = shrink,
          blurLengthPx =
            if (settled) {
              0f
            } else {
              lineHeightPx * STACK_BLUR_FRACTION * entry.b.coerceIn(0f, 1f)
            },
          stable = settled,
        )
      )
    }
  }

  private fun stopFor(column: Column, slot: KeyedSlot, direction: Int): Int {
    val from = column.goalStop()
    if (slot.kind != TokenKind.DIGIT) return from
    if (column.charAt[from] == slot.char) return from
    return from - direction
  }

  private fun wavePhases(
    layout: List<KeyedSlot>,
    digitEventKeys: Set<String>,
    changingCount: Int,
  ): Map<String, Int> {
    if (layout.isEmpty()) return emptyMap()

    val phases = HashMap<String, Int>(layout.size)
    var phase = 0

    for (slot in layout) {
      if (slot.kind == TokenKind.DIGIT && digitEventKeys.contains(slot.key)) {
        phases[slot.key] =
          if (changingCount > 0) phase.coerceAtMost(changingCount - 1) else 0
        phase += 1
      } else {
        phases[slot.key] =
          if (changingCount > 0) phase.coerceAtMost(changingCount - 1) else 0
      }
    }

    return phases
  }

  private fun stepEntries(column: Column, response: Float, dt: Float): Boolean {
    var moving = false

    val oneWayCrowd = column.crowd * column.crowdRaw
    val reversalSuppression = (1f - column.flipRaw).coerceIn(0f, 1f)
    val geometryRush =
      1f + STACK_CROWD_SPEEDUP * oneWayCrowd * reversalSuppression

    val base = response / RESPONSE_SECONDS

    val fast =
      (
        2.0 * Math.PI /
          max(0.05f, STACK_RESPONSE_SECONDS * base / geometryRush)
      ).toFloat()

    val slow =
      (
        2.0 * Math.PI /
          max(0.05f, STACK_SLOW_RESPONSE_SECONDS * base)
      ).toFloat()

    val scale =
      (
        2.0 * Math.PI /
          max(0.05f, STACK_SLOW_RESPONSE_SECONDS * base / geometryRush)
      ).toFloat()

    val blur =
      (
        2.0 * Math.PI /
          max(0.05f, STACK_BLUR_RESPONSE_SECONDS * base)
      ).toFloat()

    for (entry in column.entries) {
      val error = entry.posTarget - entry.p
      if (abs(error) > POSITION_EPSILON || abs(entry.velocity) > VELOCITY_EPSILON) {
        entry.velocity +=
          ((fast * fast * error) - (2f * STACK_DAMPING * fast * entry.velocity)) * dt
        entry.p += entry.velocity * dt
        moving = true
      } else {
        entry.p = entry.target
        entry.velocity = 0f
      }

      val blurClock =
        if (entry.superseded) blur * STACK_EXIT_BLUR_SPEEDUP else blur
      val bError = entry.blurTarget - entry.b

      if (abs(bError) > POSITION_EPSILON || abs(entry.bVelocity) > VELOCITY_EPSILON) {
        entry.bVelocity +=
          (
            (blurClock * blurClock * bError) -
              (2f * STACK_BLUR_DAMPING * blurClock * entry.bVelocity)
          ) * dt

        entry.b = (entry.b + entry.bVelocity * dt).coerceIn(0f, 1f)

        if (
          (entry.b <= 0f && entry.bVelocity < 0f) ||
            (entry.b >= 1f && entry.bVelocity > 0f)
        ) {
          entry.bVelocity = 0f
        }

        moving = true
      } else {
        entry.b = entry.blurTarget
        entry.bVelocity = 0f
      }

      val aError = entry.alphaTarget - entry.alpha
      if (abs(aError) > POSITION_EPSILON || abs(entry.alphaVelocity) > VELOCITY_EPSILON) {
        val alphaClock =
          slow / (1f + (STACK_FLIP_SOFT - 1f) * column.flipRaw)

        entry.alphaVelocity +=
          (
            (alphaClock * alphaClock * aError) -
              (2f * STACK_SLOW_DAMPING * alphaClock * entry.alphaVelocity)
          ) * dt
        entry.alpha += entry.alphaVelocity * dt
        moving = true
      } else {
        entry.alpha = entry.alphaTarget
        entry.alphaVelocity = 0f
      }

      val qError = entry.target - entry.q
      if (abs(qError) > POSITION_EPSILON || abs(entry.qVelocity) > VELOCITY_EPSILON) {
        entry.qVelocity +=
          (
            (scale * scale * qError) -
              (2f * STACK_SLOW_DAMPING * scale * entry.qVelocity)
          ) * dt
        entry.q += entry.qVelocity * dt
        moving = true
      } else {
        entry.q = entry.target
        entry.qVelocity = 0f
      }
    }

    if (column.entries.size > 1) {
      val keep = column.entries.takeLast(2)
      column.entries.retainAll { it in keep || it.alpha > ENTRY_CULL_ALPHA }
    }

    return moving
  }

  private fun stepCrowd(column: Column, dt: Float): Boolean {
    column.flipRaw = max(0f, column.flipRaw - dt / FLIP_RELAX)

    val laneTarget = if (column.flipRaw > 0f) 1f else 0f
    column.flipGate +=
      (laneTarget - column.flipGate) * min(1f, dt / STACK_LANE_GATE)

    if (column.crowdRaw <= 0f && column.crowd <= 0.001f) {
      column.crowd = 0f
      return false
    }

    column.crowdRaw = max(0f, column.crowdRaw - dt / CROWD_RELAX)
    column.crowd +=
      (column.crowdRaw - column.crowd) * min(1f, dt / CROWD_RELAX)
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
      ((omega * omega * error) - (2f * X_DAMPING_RATIO * omega * column.xVelocity)) * dt
    column.x += column.xVelocity * dt
    return true
  }

  private fun xRel(slot: KeyedSlot): Float =
    slot.centerFromLeft - slot.totalWidth / 2f
}
