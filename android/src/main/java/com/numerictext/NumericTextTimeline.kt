package com.numerictext

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
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
    val rasterId: Int,
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

    const val BUILD_ID = "V0.1-RC3-RELEASE-SURFACE-2026-08-11"

    private const val STACK_OFFSET = 0.3950f
    private const val STACK_FINAL_SCALE = 0.3984375f

    private const val APPLE_EFFECT_MASS = 1.0f
    private const val APPLE_EFFECT_STIFFNESS = 344.0f
    private const val APPLE_EFFECT_DAMPING = 37.0f

    private const val APPLE_MOVE_MASS = 2.0f
    private const val APPLE_MOVE_STIFFNESS = 470.0f
    private const val APPLE_MOVE_DAMPING = 34.0f

    private const val STACK_ALPHA_RESPONSE_SECONDS = 0.277f
    private const val STACK_ALPHA_DAMPING = 1.00f
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
    // Frame-aligned USD A/B GT: SwiftUI keeps the old line effectively static for roughly
    // 70 ms before the per-glyph format wave becomes visually active. Apply the onset to the
    // entire unified format wave, never to ordinary same-format numeric changes.
    private const val FORMAT_ROLL_ONSET_SECONDS = 0.070f

    // Kept for the legacy structural path. SIGN/OTHER now travel through DIGIT physics,
    // so formatGeometrySplit below is the source of truth for unified per-glyph rolls.
    private const val AFFIX_DIGIT_LEAD_SECONDS = 0.085f
    private const val AFFIX_ENTER_DELAY_SECONDS = 0.050f
    private const val AFFIX_PREFIX_REMOVE_DELAY_SECONDS = 0.050f
    private const val AFFIX_SUFFIX_REMOVE_DELAY_SECONDS = 0.100f
    private const val AFFIX_EXIT_DISTANCE = 0.45f
    private const val AFFIX_REPLACEMENT_EXIT_DISTANCE = 1.0f
    private const val AFFIX_EXIT_ALPHA_SLOWDOWN = 1.35f

    private const val POSITION_EPSILON = 0.001f
    private const val VELOCITY_EPSILON = 0.005f
    private const val ENTRY_CULL_ALPHA = 0.004f
    private const val RENDER_ALPHA_EPSILON = 0.01f
    private const val STRUCTURAL_ENTRY_ALPHA = 0.32f
    private const val GEOMETRY_EPSILON_PX = 0.1f
  }

  private enum class PendingKind { CHANGE, REMOVE, ENTER }

  private class PendingStop(
    val stop: Int,
    val direction: Int,
    val enqueuedAtNanos: Long,
    val dueAtNanos: Long,
    val kind: PendingKind,
    val rasterId: Int,
    val replacementAffixExit: Boolean = false,
    var pinnedX: Float = Float.NaN,
  )

  private class Entry(val ch: String, var p: Float, var rasterId: Int) {
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
    var replacementAffixExit = false
    var pinnedX = Float.NaN

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
  private var targetRasterId = 0
  private var maxBlurLengthPx = 1f

  var targetText: String = ""
    private set

  var isRunning: Boolean = false
    private set

  fun targetWidth(): Float = targetLayout.firstOrNull()?.totalWidth ?: 0f

  fun reset(
    layout: List<KeyedSlot>,
    text: String,
    lineHeight: Float,
    rasterId: Int,
    blurLengthPx: Float,
  ) {
    columns.clear()
    targetLayout = layout
    targetText = text
    targetRasterId = rasterId
    lineHeightPx = max(1f, lineHeight)
    maxBlurLengthPx = max(0f, blurLengthPx)

    for (slot in layout) {
      val column = Column(slot.key, slot.kind)
      column.charAt[0] = slot.char
      column.entries.add(Entry(slot.char, 0f, rasterId).also { it.alpha = 1f })
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
    rasterId: Int,
    blurLengthPx: Float,
  ) {
    lineHeightPx = max(1f, lineHeight)
    maxBlurLengthPx = max(0f, blurLengthPx)
    durationScale = animationDurationMs.coerceAtLeast(80L) / 320f
    lastDirection = if (direction < 0) -1 else 1

    val previousSlots = targetLayout
    val previousByKey = previousSlots.associateBy { it.key }
    val previousTargetRasterId = targetRasterId

    targetText = text
    targetRasterId = rasterId
    targetLayout = layout

    val eventNanos = System.nanoTime()
    val incomingByKey = layout.associateBy { it.key }

    val structuralEnterKeys = HashSet<String>()
    for (slot in layout) {
      if (previousByKey[slot.key] == null) structuralEnterKeys.add(slot.key)
    }

    val structuralRemovalKeys = HashSet<String>()
    for (slot in previousSlots) {
      if (incomingByKey[slot.key] == null) structuralRemovalKeys.add(slot.key)
    }

    val formatGeometrySplit =
      layout.any {
        structuralEnterKeys.contains(it.key) && isAffixKind(it.semanticKind)
      } ||
        previousSlots.any {
          structuralRemovalKeys.contains(it.key) && isAffixKind(it.semanticKind)
        } ||
        layout.any { slot ->
          val previous = previousByKey[slot.key]
          previous != null &&
            (isAffixKind(previous.semanticKind) || isAffixKind(slot.semanticKind)) &&
            previous.char != slot.char
        }

    val geometryChangeKeys = HashSet<String>()
    if (formatGeometrySplit) {
      for (slot in layout) {
        val previous = previousByKey[slot.key] ?: continue
        if (abs(xRel(previous) - xRel(slot)) > GEOMETRY_EPSILON_PX) {
          geometryChangeKeys.add(slot.key)
        }
      }

      for (column in columns.values) {
        for (entry in column.entries) {
          if (entry.pinnedX.isNaN()) entry.pinnedX = column.x
        }
      }
    } else {
      // A per-entry X pin belongs only to the format transition that created it. When the next
      // transaction keeps the same format and changes only the value, continuing glyphs must return
      // to the first-release horizontal reflow. Preserve already-superseded old-format ghosts at
      // their pinned X, but release the current target raster without a visual jump.
      for ((key, column) in columns) {
        if (!previousByKey.containsKey(key) || !incomingByKey.containsKey(key)) continue

        val currentPinned =
          column.entries.lastOrNull {
            !it.superseded &&
              it.rasterId == previousTargetRasterId &&
              !it.pinnedX.isNaN()
          }
        if (currentPinned != null) {
          column.x = currentPinned.pinnedX
          column.xVelocity = 0f
        }

        for (entry in column.entries) {
          if (!entry.superseded && entry.rasterId == previousTargetRasterId) {
            entry.pinnedX = Float.NaN
          }
        }
        for (pending in column.pending) {
          if (pending.kind != PendingKind.REMOVE && pending.rasterId == previousTargetRasterId) {
            pending.pinnedX = Float.NaN
          }
        }
      }
    }

    val hasAffixEnter =
      layout.any { structuralEnterKeys.contains(it.key) && isAffixKind(it.kind) }
    val hasAffixRemoval =
      previousSlots.any { structuralRemovalKeys.contains(it.key) && isAffixKind(it.kind) }
    val affixTopologyChanged = hasAffixEnter || hasAffixRemoval
    val affixReplacement = hasAffixEnter && hasAffixRemoval

    for ((key, column) in columns) {
      column.retiring = incomingByKey[key] == null
    }

    val digitEventKeys = LinkedHashSet<String>()

    for (slot in layout) {
      if (slot.kind != TokenKind.DIGIT) continue

      val column = columns[slot.key]
      if (
        formatGeometrySplit ||
          structuralEnterKeys.contains(slot.key) ||
          geometryChangeKeys.contains(slot.key) ||
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
      sourceRasterId: Int,
      replacementAffixExit: Boolean = false,
      pinnedX: Float = Float.NaN,
    ) {
      val phase = if (changingCount > 0) wavePhase.coerceIn(0, changingCount - 1) else 0
      val waveDelayNanos =
        (gap.toDouble() * (phase + 0.5) * 1_000_000_000.0).toLong()
      val formatOnsetNanos =
        if (formatGeometrySplit) {
          (FORMAT_ROLL_ONSET_SECONDS.toDouble() * 1_000_000_000.0).toLong()
        } else {
          0L
        }
      val digitLeadNanos =
        if (affixTopologyChanged && column.kind == TokenKind.DIGIT) {
          (AFFIX_DIGIT_LEAD_SECONDS.toDouble() * 1_000_000_000.0).toLong()
        } else {
          0L
        }
      val affixDelayNanos =
        (affixDelaySeconds(column, kind).toDouble() * 1_000_000_000.0).toLong()

      val requestedDueNanos =
        eventNanos + formatOnsetNanos + digitLeadNanos + affixDelayNanos + waveDelayNanos
      val previousPending = column.pending.lastOrNull()

      val dueAtNanos =
        if (previousPending == null) {
          requestedDueNanos
        } else {
          val arrivalSpacingNanos =
            (eventNanos - previousPending.enqueuedAtNanos).coerceAtLeast(1L)
          maxOf(requestedDueNanos, previousPending.dueAtNanos + arrivalSpacingNanos)
        }

      column.pending.addLast(
        PendingStop(
          stop = stop,
          direction = lastDirection,
          enqueuedAtNanos = eventNanos,
          dueAtNanos = dueAtNanos,
          kind = kind,
          rasterId = sourceRasterId,
          replacementAffixExit = replacementAffixExit,
          pinnedX = pinnedX,
        )
      )
    }

    val newPhaseByKey = wavePhases(layout, digitEventKeys, changingCount)
    val oldPhaseByKey = wavePhases(previousSlots, digitEventKeys, changingCount)

    for (slot in layout) {
      val incomingX = xRel(slot)
      var column = columns[slot.key]

      if (column == null) {
        column = Column(slot.key, slot.kind)
        column.x = incomingX
        column.targetX = column.x
        columns[slot.key] = column
      }

      column.kind = slot.kind
      column.targetX = incomingX
      column.retiring = false

      if (structuralEnterKeys.contains(slot.key)) {
        val stop = column.goalStop()
        column.charAt[stop] = slot.char

        enqueue(
          column = column,
          stop = stop,
          kind = PendingKind.ENTER,
          wavePhase = newPhaseByKey[slot.key] ?: 0,
          sourceRasterId = rasterId,
          pinnedX = if (formatGeometrySplit) incomingX else Float.NaN,
        )
        continue
      }

      val ordinaryNext = stopFor(column, slot, lastDirection)
      val next =
        if (formatGeometrySplit && ordinaryNext == column.goalStop()) {
          column.goalStop() - lastDirection
        } else {
          ordinaryNext
        }

      if (next != column.goalStop()) {
        column.charAt[next] = slot.char

        enqueue(
          column = column,
          stop = next,
          kind = PendingKind.CHANGE,
          wavePhase = newPhaseByKey[slot.key] ?: 0,
          sourceRasterId = rasterId,
          pinnedX = if (formatGeometrySplit) incomingX else Float.NaN,
        )
      } else {
        bindCurrentRaster(column, slot.char, rasterId)
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
        sourceRasterId = -1,
        replacementAffixExit = affixReplacement && isAffixKind(column.kind),
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
      column.entries.add(Entry(slot.char, 0f, targetRasterId).also { it.alpha = 1f })
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
    val appleTimeScale = response / RESPONSE_SECONDS

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

          if (!wasAtRest) column.crowdRaw = min(1f, column.crowdRaw + CROWD_STEP)

          val oldDir = column.lastDir
          if (oldDir != null && oldDir != commitDir && !wasAtRest) {
            column.flipRaw = min(1f, column.flipRaw + FLIP_STEP)
          }
          column.lastDir = commitDir

          when (pendingStop.kind) {
            PendingKind.REMOVE ->
              commitRemove(column, commitDir, pendingStop.replacementAffixExit)
            PendingKind.ENTER ->
              commitEnter(column, stop, commitDir, pendingStop.rasterId, pendingStop.pinnedX)
            PendingKind.CHANGE ->
              commitChange(
                column,
                stop,
                commitDir,
                oldDir,
                wasAtRest,
                pendingStop.rasterId,
                pendingStop.pinnedX,
              )
          }

          active = true
        }
      }

      if (stepCrowd(column, dt)) active = true
      if (stepEntries(column, response, appleTimeScale, dt)) active = true
      if (stepX(column, appleTimeScale, dt)) active = true
    }

    if (!active) snapToTarget()
    isRunning = active
    return active
  }

  fun referencedRasterIds(): Set<Int> {
    val ids = LinkedHashSet<Int>()
    if (targetRasterId > 0) ids.add(targetRasterId)
    for (column in columns.values) {
      for (entry in column.entries) if (entry.rasterId > 0) ids.add(entry.rasterId)
      for (pending in column.pending) if (pending.rasterId > 0) ids.add(pending.rasterId)
    }
    return ids
  }

  fun samples(): List<GlyphSample> {
    val out = ArrayList<GlyphSample>(columns.size * 2)
    for (column in columns.values) emitStack(column, out)
    return out
  }

  private fun commitRemove(
    column: Column,
    direction: Int,
    replacementAffixExit: Boolean,
  ) {
    for (entry in column.entries) {
      if (!entry.superseded) {
        supersede(entry, direction, column.kind, replacementAffixExit)
      }
    }
  }

  private fun commitEnter(
    column: Column,
    stop: Int,
    direction: Int,
    rasterId: Int,
    pinnedX: Float,
  ) {
    val ch = column.charAt[stop] ?: return
    val entry = Entry(ch, incomingAmplitude(direction, column.flipRaw), rasterId)
    entry.alpha = STRUCTURAL_ENTRY_ALPHA
    entry.pinnedX = pinnedX
    column.entries.add(entry)
  }

  private fun commitChange(
    column: Column,
    stop: Int,
    direction: Int,
    oldDirection: Int?,
    wasAtRest: Boolean,
    rasterId: Int,
    pinnedX: Float,
  ) {
    val ch = column.charAt[stop] ?: return

    for (entry in column.entries) {
      if (!entry.superseded) {
        supersede(entry, direction, column.kind, replacementAffixExit = false)
      }
    }

    val reversing = oldDirection != null && oldDirection != direction && !wasAtRest
    val reuse =
      if (reversing) {
        column.entries.lastOrNull { it.superseded && it.ch == ch }
      } else {
        null
      }

    if (reuse != null) {
      reuse.superseded = false
      reuse.replacementAffixExit = false
      reuse.target = 0f
      reuse.posTarget = 0f
      reuse.alphaTarget = 1f
      reuse.blurTarget = 0f
      reuse.rasterId = rasterId
      reuse.pinnedX = pinnedX
      return
    }

    val entry = Entry(ch, incomingAmplitude(direction, column.flipRaw), rasterId)
    entry.pinnedX = pinnedX
    if (column.entries.isEmpty()) entry.alpha = STRUCTURAL_ENTRY_ALPHA
    column.entries.add(entry)
  }

  private fun bindCurrentRaster(column: Column, ch: String, rasterId: Int) {
    val current = column.entries.lastOrNull { !it.superseded && it.ch == ch } ?: return
    current.rasterId = rasterId
  }

  private fun supersede(
    entry: Entry,
    direction: Int,
    kind: TokenKind,
    replacementAffixExit: Boolean,
  ) {
    val isReplacement = replacementAffixExit && isAffixKind(kind)
    val exitDistance = when {
      isReplacement -> AFFIX_REPLACEMENT_EXIT_DISTANCE
      isAffixKind(kind) -> AFFIX_EXIT_DISTANCE
      else -> 1f
    }
    entry.superseded = true
    entry.replacementAffixExit = isReplacement
    entry.target = direction.toFloat() * exitDistance
    entry.posTarget = direction.toFloat() * exitDistance
    entry.blurTarget = 1f
    entry.alphaTarget = 0f
  }

  private fun incomingAmplitude(direction: Int, flipRaw: Float): Float =
    -direction.toFloat() * (1f + (STACK_FLIP_BORN - 1f) * flipRaw)

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

    val norm = if (total > STACK_ALPHA_CEILING) STACK_ALPHA_CEILING / total else 1f

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
          rasterId = entry.rasterId,
          x = if (entry.pinnedX.isNaN()) column.x else entry.pinnedX,
          offsetY = effectiveOffset * lineHeightPx,
          alpha = alpha.coerceIn(0f, 1f),
          scaleX = shrink,
          scaleY = shrink,
          blurLengthPx =
            if (settled) 0f else maxBlurLengthPx * entry.b.coerceIn(0f, 1f),
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

  private fun isAffixKind(kind: TokenKind): Boolean =
    kind == TokenKind.SIGN || kind == TokenKind.OTHER

  private fun isSuffixAffix(column: Column): Boolean =
    column.kind == TokenKind.OTHER && column.key.startsWith("X")

  private fun affixDelaySeconds(column: Column, kind: PendingKind): Float {
    if (!isAffixKind(column.kind)) return 0f
    return when (kind) {
      PendingKind.ENTER -> AFFIX_ENTER_DELAY_SECONDS
      PendingKind.REMOVE ->
        if (isSuffixAffix(column)) AFFIX_SUFFIX_REMOVE_DELAY_SECONDS
        else AFFIX_PREFIX_REMOVE_DELAY_SECONDS
      PendingKind.CHANGE -> 0f
    }
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
        phases[slot.key] = if (changingCount > 0) phase.coerceAtMost(changingCount - 1) else 0
        phase += 1
      } else {
        phases[slot.key] = if (changingCount > 0) phase.coerceAtMost(changingCount - 1) else 0
      }
    }

    return phases
  }

  private fun stepEntries(
    column: Column,
    response: Float,
    appleTimeScale: Float,
    dt: Float,
  ): Boolean {
    var moving = false

    val oneWayCrowd = column.crowd * column.crowdRaw
    val reversalSuppression = (1f - column.flipRaw).coerceIn(0f, 1f)
    val geometryRush = 1f + STACK_CROWD_SPEEDUP * oneWayCrowd * reversalSuppression
    val base = response / RESPONSE_SECONDS
    val alphaClock =
      (2.0 * Math.PI / max(0.05f, STACK_ALPHA_RESPONSE_SECONDS * base)).toFloat()
    val blurClockBase =
      (2.0 * Math.PI / max(0.05f, STACK_BLUR_RESPONSE_SECONDS * base)).toFloat()

    for (entry in column.entries) {
      val moveScale = appleTimeScale / geometryRush
      val moveResult = integrateSpringExact(
        value = entry.p,
        velocity = entry.velocity,
        target = entry.posTarget,
        mass = APPLE_MOVE_MASS,
        stiffness = APPLE_MOVE_STIFFNESS,
        damping = APPLE_MOVE_DAMPING,
        timeScale = moveScale,
        dt = dt,
      )
      entry.p = moveResult.value
      entry.velocity = moveResult.velocity

      val moveActive =
        abs(entry.posTarget - entry.p) > POSITION_EPSILON ||
          abs(entry.velocity) > VELOCITY_EPSILON
      if (!moveActive) {
        entry.p = entry.posTarget
        entry.velocity = 0f
      } else {
        moving = true
      }

      val blurClock =
        if (entry.superseded) blurClockBase * STACK_EXIT_BLUR_SPEEDUP else blurClockBase
      val bError = entry.blurTarget - entry.b

      if (abs(bError) > POSITION_EPSILON || abs(entry.bVelocity) > VELOCITY_EPSILON) {
        entry.bVelocity +=
          ((blurClock * blurClock * bError) -
            (2f * STACK_BLUR_DAMPING * blurClock * entry.bVelocity)) * dt
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
        val affixExitSlowdown =
          if (entry.superseded && isAffixKind(column.kind)) AFFIX_EXIT_ALPHA_SLOWDOWN else 1f
        val localAlphaClock =
          alphaClock /
            ((1f + (STACK_FLIP_SOFT - 1f) * column.flipRaw) * affixExitSlowdown)

        entry.alphaVelocity +=
          ((localAlphaClock * localAlphaClock * aError) -
            (2f * STACK_ALPHA_DAMPING * localAlphaClock * entry.alphaVelocity)) * dt
        entry.alpha += entry.alphaVelocity * dt
        moving = true
      } else {
        entry.alpha = entry.alphaTarget
        entry.alphaVelocity = 0f
      }

      val effectScale = appleTimeScale / geometryRush
      val scaleResult = integrateSpringExact(
        value = entry.q,
        velocity = entry.qVelocity,
        target = entry.target,
        mass = APPLE_EFFECT_MASS,
        stiffness = APPLE_EFFECT_STIFFNESS,
        damping = APPLE_EFFECT_DAMPING,
        timeScale = effectScale,
        dt = dt,
      )
      entry.q = scaleResult.value
      entry.qVelocity = scaleResult.velocity

      val scaleActive =
        abs(entry.target - entry.q) > POSITION_EPSILON ||
          abs(entry.qVelocity) > VELOCITY_EPSILON
      if (!scaleActive) {
        entry.q = entry.target
        entry.qVelocity = 0f
      } else {
        moving = true
      }
    }

    if (column.entries.size > 1) {
      val keep = column.entries.takeLast(2)
      column.entries.retainAll { it in keep || it.alpha > ENTRY_CULL_ALPHA }
    }

    return moving
  }

  private data class SpringResult(val value: Float, val velocity: Float)

  private fun integrateSpringExact(
    value: Float,
    velocity: Float,
    target: Float,
    mass: Float,
    stiffness: Float,
    damping: Float,
    timeScale: Float,
    dt: Float,
  ): SpringResult {
    val scale = max(1e-4f, timeScale).toDouble()
    val h = dt.toDouble()
    if (h <= 0.0) return SpringResult(value, velocity)

    val m = mass.toDouble()
    val k = stiffness.toDouble() / (scale * scale)
    val c = damping.toDouble() / scale

    val y0 = (value - target).toDouble()
    val v0 = velocity.toDouble()
    val omega0 = sqrt(k / m)
    val zeta = c / (2.0 * sqrt(k * m))

    val y: Double
    val v: Double

    when {
      zeta < 1.0 - 1e-6 -> {
        val wd = omega0 * sqrt(1.0 - zeta * zeta)
        val decay = exp(-zeta * omega0 * h)
        val a = y0
        val b = (v0 + zeta * omega0 * y0) / wd
        val cosT = cos(wd * h)
        val sinT = sin(wd * h)
        val shape = a * cosT + b * sinT
        y = decay * shape
        v = decay *
          (-zeta * omega0 * shape + (-a * wd * sinT + b * wd * cosT))
      }

      zeta > 1.0 + 1e-6 -> {
        val root = sqrt(zeta * zeta - 1.0)
        val r1 = -omega0 * (zeta - root)
        val r2 = -omega0 * (zeta + root)
        val c1 = (v0 - r2 * y0) / (r1 - r2)
        val c2 = y0 - c1
        val e1 = exp(r1 * h)
        val e2 = exp(r2 * h)
        y = c1 * e1 + c2 * e2
        v = c1 * r1 * e1 + c2 * r2 * e2
      }

      else -> {
        val decay = exp(-omega0 * h)
        val a = y0
        val b = v0 + omega0 * y0
        y = decay * (a + b * h)
        v = decay * (b - omega0 * (a + b * h))
      }
    }

    return SpringResult((target + y).toFloat(), v.toFloat())
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

  private fun stepX(column: Column, appleTimeScale: Float, dt: Float): Boolean {
    val error = column.targetX - column.x
    if (abs(error) <= 0.1f && abs(column.xVelocity) <= 0.1f) {
      column.x = column.targetX
      column.xVelocity = 0f
      return false
    }

    val result = integrateSpringExact(
      value = column.x,
      velocity = column.xVelocity,
      target = column.targetX,
      mass = APPLE_EFFECT_MASS,
      stiffness = APPLE_EFFECT_STIFFNESS,
      damping = APPLE_EFFECT_DAMPING,
      timeScale = appleTimeScale,
      dt = dt,
    )
    column.x = result.value
    column.xVelocity = result.velocity
    return true
  }

  private fun xRel(slot: KeyedSlot): Float =
    slot.centerFromLeft - slot.totalWidth / 2f
}
