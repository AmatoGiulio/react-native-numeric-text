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
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

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

  // Knobs — tune against iOS. dampingRatio < 1 gives the snappy overshoot; stiffness sets
  // how fast it settles (~4/(ratio·√stiffness) seconds).
  // Soft and flowing, NOT snappy. A stiff spring makes every digit a crisp discrete event, which
  // reads as mechanical/robotic; the reference feels docile because each roll is soft and the
  // rolls blend into one another. Keep the roll gentle and let the blur carry the motion.
  private val springStiffness: Float = 320f
  // Under-damped enough to keep a slight, lively settle in the roll's direction.
  private val springDampingRatio: Float = 0.70f
  // Spring velocity (progress units/s) that maps to full per-digit blur. A fast-rolling digit
  // (units during a rapid hold) reaches full blur; a rarely-changing digit (thousands) stays
  // at rest → sharp. This *per-slot* coupling replaces the old global `inputActivity`.
  // Spring velocity that maps to full blur. Tracks springStiffness (peak velocity ∝ √stiffness).
  private val blurVelocityRef: Float = 9f

  // Birth/death (slot appearing/disappearing) spring. Must be as quick as the roll: when it was
  // much softer the outgoing value lingered on screen long after the digits had rolled — the
  // "old value stays visible too long" artefact. Slight damping < 1 so it follows the roll's physics.
  private val lifeStiffness: Float = 380f
  private val lifeDampingRatio: Float = 0.9f
  // LEFT→RIGHT cascade. Reverse-engineered from the iOS reference at 60fps: on a multi-digit
  // change the LEFTMOST changed column rolls first and each column to its right follows ~80ms
  // later (e.g. 2,599→2,600: hundreds, then tens, then units). This is the opposite order and a
  // far larger delay than a cosmetic stagger — it's a defining trait of SwiftUI numericText.
  // Kept SUBTLE. The order (left→right) is what the reference does, but a large delay turns the
  // cascade into a visibly sequential, machine-like wave. A small offset gives the wave without
  // making each column a discrete step.
  private val staggerSeconds: Float = 0.03f

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

  // ── Per-slot spring model (default PER_GLYPH renderer) ──
  //
  // Each logical column (units, tens, …, separators) owns an independent spring keyed by a
  // stable id (see TransitionLogic.KeyedSlot). Only columns whose character changes retarget;
  // unchanged columns stay at rest and render sharp. Blur is derived per column from *its own*
  // spring velocity, so units blur heavily during a hold while thousands stay crisp — the
  // reference iOS behaviour that a single shared spring could not reproduce.
  private class RollSlot {
    var kind: TokenKind = TokenKind.DIGIT
    var fromChar: String = ""      // character rolling out ("" for a newly-born column)
    var toChar: String = ""        // character rolling in / current target
    var value: Float = 1f          // roll progress 0→1 (overshoots for the bounce)
    var velocity: Float = 0f
    var direction: Int = 1
    var delay: Float = 0f          // remaining stagger delay before the roll starts
    var rolling: Boolean = false
    var life: Float = 1f           // presence 0→1 (birth/death fade + scale)
    var lifeVel: Float = 0f
    var lifeTarget: Float = 1f
    // Two independent centred layouts (matching iOS): the outgoing glyph is drawn at its position
    // in the OLD layout, the incoming glyph at its position in the NEW layout. `cfl*` is the glyph
    // centre measured from that layout's left edge; combined with the layout origin it gives the
    // absolute X. This is what stops a surviving digit from sliding when the width changes.
    var cflOld: Float = 0f
    var cflNew: Float = 0f
    var hasOld: Boolean = false    // present in the old layout (has an outgoing position)
    var hasNew: Boolean = false    // present in the new layout (has an incoming position)
    var oldNode: RenderNode? = null
    var newNode: RenderNode? = null
  }

  private val rollSlots = LinkedHashMap<String, RollSlot>()
  private var slotOldWidth: Float = 0f
  private var slotNewWidth: Float = 0f
  private var slotTargetText: String = "0"   // last scheduled target (source for the OLD layout)

  private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    textAlign = Paint.Align.LEFT
  }

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
  private val travelFactor = 0.24f
  // blurFactor: peak per-digit blur radius as a fraction of line-height. Lower = softer/greyer.
  private val blurFactor = 0.12f
  // Extra height (per side, × line-height) reserved so the blur/roll can breathe.
  private val verticalHeadroomFactor = 0.16f
  // Depth scale: a rolling glyph shrinks toward this as it leaves and grows back on arrival,
  // so the motion reads as a digit rotating on a cylinder rather than a flat 2D guillotine.
  private val depthMinScale = 0.88f
  // Born/dying separators & leading digits scale in from this (fade + scale, no vertical roll).
  private val bornMinScale = 0.6f

  private val travel: Float get() = getTextHeight() * travelFactor

  init {
    recalcTextPaint()
    recalcFormatter()
  }

  private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

  // ── Measurement ──

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val fm = textPaint.fontMetrics
    val textHeight = fm.descent - fm.ascent
    val desiredWidth = if (animator != null && activePlan != null) {
      ceil(max(activePlan!!.oldWidth, activePlan!!.newWidth) + paddingLeft + paddingRight).toInt()
    } else {
      val t = if (settledText.isNotEmpty()) settledText else "0"
      ceil(textPaint.measureText(t) + paddingLeft + paddingRight).toInt()
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
        if (rollSlots.isNotEmpty()) drawSlots(canvas, progress.coerceIn(0f, 0.999f)) else drawSettled(canvas)
        return
      }
      if (!settledLike && rollSlots.isNotEmpty()) { drawSlots(canvas, null); return }
      drawSettled(canvas)
      return
    }

    // Debug strategies: whole-run / changed-run block renderer.
    val plan = resolvePlan()
    if (settledLike || plan == null) {
      if (scrubbing && plan != null) {
        drawTransition(canvas, plan, progress.coerceIn(0f, 0.999f))
        return
      }
      drawSettled(canvas)
      return
    }

    drawTransition(canvas, plan, progress)
  }

  private fun drawSettled(canvas: Canvas) {
    textPaint.maskFilter = null
    textPaint.alpha = 255
    val cx = width / 2f
    val cy = height / 2f
    val bl = baselineY(cy)
    canvas.drawText(settledText, cx - textPaint.measureText(settledText) / 2f, bl, textPaint)
  }

  // ── Per-slot renderer (faithful SwiftUI numericText) ──
  //
  // Columns are right-anchored: each keeps its distance from the composition's right edge, and
  // only the overall centring origin interpolates as the width changes — so digits that keep
  // their logical position never slide horizontally (roadmap D). Unchanged columns draw sharp;
  // rolling columns crossfade old→new with a short vertical roll, per-digit velocity blur and a
  // depth scale; born/dying columns (leading digits, separators) fade + scale instead of rolling.

  private fun drawSlots(canvas: Canvas, scrub: Float?) {
    val bl = baselineY(height / 2f)
    // Two independent centred layouts (the iOS model): old glyphs live in the old layout, new
    // glyphs in the new layout, both centred in the view — so a surviving digit never slides
    // horizontally when the width changes; only born/dying columns appear/leave at the edge.
    val oldOriginX = (width - slotOldWidth) / 2f
    val newOriginX = (width - slotNewWidth) / 2f
    val h = getTextHeight()
    val travelPx = h * travelFactor
    val maxBlurPx = h * blurFactor
    val nodeCapable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canvas.isHardwareAccelerated

    // Pass 1 — static anchors (sharp), drawn directly at their new-layout position.
    textPaint.maskFilter = null; textPaint.alpha = 255
    for (s in rollSlots.values) {
      if (!isAnimated(s)) drawGlyph(canvas, s.toChar, newOriginX + s.cflNew, bl, textPaint)
    }

    // Pass 2 — moving / born / dying columns, each with its own blur + depth.
    for (s in rollSlots.values) {
      if (!isAnimated(s)) continue
      val newCx = newOriginX + s.cflNew
      val oldCx = oldOriginX + s.cflOld
      val life = s.life.coerceIn(0f, 1f)

      // iOS roll direction: increment → new enters from the TOP (content moves down); decrement
      // → new from the bottom. Our offset helpers encode the opposite convention, so negate.
      val d = -s.direction

      if (s.rolling && s.fromChar.isNotEmpty()) {
        val p = scrub ?: s.value
        val pC = p.coerceIn(0f, 1f)
        // Blur has TWO sources. Velocity alone made the softness follow the spring's mechanical
        // profile, so it arrived late and the glyphs read as two separate hard objects. A
        // position-based term gives the transition its own early softness, like the reference.
        val motionBlur = (abs(s.velocity) / blurVelocityRef).coerceIn(0f, 1f)
        val transitionBlur = TransitionLogic.blurEnvelope(pC)
        val blurAmt = if (scrub != null) transitionBlur
          else max(motionBlur * 0.6f, transitionBlur * 0.85f)
        val radius = maxBlurPx * blurAmt
        // Position keeps the RAW spring value (overshoot intact → the glyph still lands with
        // physics); alpha/scale use an overshoot-free progress so they don't wobble at the end.
        val pv = TransitionLogic.smootherstep(pC)
        val oldOff = TransitionLogic.computeOldOffset(d, travelPx, p)
        val newOff = TransitionLogic.computeNewOffset(d, travelPx, p)
        // Gently asymmetric crossfade with a GENEROUS overlap. A linear 1-p / p fade keeps total
        // ink at 1.0 throughout and two overlapping blurred layers read darker than one, so the
        // sum dips slightly at the crossing to give the reference's soft grey mass. Cutting the
        // overlap hard (old leaving very early) makes each digit a discrete event → mechanical.
        val oldA = 1f - TransitionLogic.smoothstep(0.05f, 0.70f, pC)
        val newA = TransitionLogic.smoothstep(0.22f, 0.92f, pC)
        val oldAlpha = (oldA * life * 255f).toInt().coerceIn(0, 255)
        val newAlpha = (newA * life * 255f).toInt().coerceIn(0, 255)
        val oldScale = lerp(1f, depthMinScale, pv)
        val newScale = lerp(depthMinScale, 1f, pv)
        if (nodeCapable) {
          s.oldNode = drawSlotGlyphNode(canvas, s.oldNode, s.fromChar, oldCx, bl, oldOff, oldScale, oldAlpha, radius)
          s.newNode = drawSlotGlyphNode(canvas, s.newNode, s.toChar, newCx, bl, newOff, newScale, newAlpha, radius)
        } else {
          drawGlyphBlurred(canvas, s.fromChar, oldCx, bl + oldOff, oldAlpha, radius * 0.6f)
          drawGlyphBlurred(canvas, s.toChar, newCx, bl + newOff, newAlpha, radius * 0.6f)
        }
      } else {
        // Birth / death (separators, leading digits). Previously this was opacity + scale ONLY, so
        // a vanishing digit stayed a perfectly sharp glyph while it faded and read as lingering.
        // The reference dissolves it: blur peaks mid-transition and it drifts along the roll axis.
        val cx = if (s.hasNew) newCx else oldCx
        val lifeBlur = TransitionLogic.blurEnvelope(life)
        val radius = maxBlurPx * lifeBlur * 0.85f
        // Follow the roll's sense: a born glyph arrives from the incoming side, a dying one leaves
        // toward the outgoing side.
        val off = if (s.hasNew) TransitionLogic.computeNewOffset(d, travelPx * 0.6f, life)
                  else TransitionLogic.computeOldOffset(d, travelPx * 0.6f, 1f - life)
        val alpha = (life * 255f).toInt().coerceIn(0, 255)
        val scale = lerp(bornMinScale, 1f, life)
        if (nodeCapable) {
          s.newNode = drawSlotGlyphNode(canvas, s.newNode, s.toChar, cx, bl, off, scale, alpha, radius)
        } else {
          textPaint.alpha = alpha
          canvas.save(); canvas.scale(scale, scale, cx, bl - h * 0.3f)
          drawGlyphBlurred(canvas, s.toChar, cx, bl + off, alpha, radius * 0.6f)
          canvas.restore()
          textPaint.alpha = 255
        }
      }
    }
    textPaint.maskFilter = null; textPaint.alpha = 255
  }

  private fun isAnimated(s: RollSlot): Boolean =
    s.rolling || s.lifeTarget == 0f || abs(s.life - 1f) > 0.01f

  private fun drawGlyph(canvas: Canvas, text: String, centerX: Float, baseline: Float, paint: TextPaint) {
    if (text.isEmpty()) return
    canvas.drawText(text, centerX - paint.measureText(text) / 2f, baseline, paint)
  }

  // Records one glyph into a reused per-slot RenderNode and applies the column's transforms:
  // a vertical-only blur (RenderEffect, API 31+), a depth scale about the glyph pivot, the roll
  // translation, and the crossfade alpha. Reusing the node avoids per-frame allocation.
  @SuppressLint("NewApi")
  private fun drawSlotGlyphNode(
    canvas: Canvas, existing: RenderNode?, ch: String,
    cx: Float, bl: Float, translationY: Float, scale: Float, alpha: Int, blurRadiusY: Float
  ): RenderNode? {
    if (alpha <= 0 || ch.isEmpty()) return existing
    val node = existing ?: RenderNode("slotGlyph")
    node.setPosition(0, 0, width, height)
    val rec = node.beginRecording()
    textPaint.alpha = 255; textPaint.maskFilter = null
    rec.drawText(ch, cx - textPaint.measureText(ch) / 2f, bl, textPaint)
    node.endRecording()
    node.pivotX = cx
    node.pivotY = bl
    node.translationY = translationY
    node.scaleX = scale
    node.scaleY = scale
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      node.setRenderEffect(
        // Not purely vertical: a sliver of horizontal blur softens the glyph edge so the motion
        // reads as an out-of-focus object rather than a graphic vertical smear.
        if (blurRadiusY >= 0.8f)
          RenderEffect.createBlurEffect(max(1f, blurRadiusY * 0.35f), blurRadiusY, Shader.TileMode.DECAL)
        else null
      )
    }
    node.alpha = alpha / 255f
    canvas.drawRenderNode(node)
    return node
  }

  // Isotropic-blur fallback for old devices / software canvas; allocates a BlurMaskFilter.
  private fun drawGlyphBlurred(canvas: Canvas, text: String, centerX: Float, baseline: Float, alpha: Int, radius: Float) {
    if (text.isEmpty() || alpha <= 0) return
    textPaint.alpha = alpha
    textPaint.maskFilter = if (radius >= 0.8f) BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) else null
    canvas.drawText(text, centerX - textPaint.measureText(text) / 2f, baseline, textPaint)
    textPaint.maskFilter = null
  }

  // ── Per-slot scheduling ──

  private val slotMeasure: (String) -> Float = { textPaint.measureText(it) }

  // Rebuild the per-slot map from a settled string: every column an at-rest anchor.
  private fun seedSlots(committed: String) {
    rollSlots.clear()
    val layout = TransitionLogic.layoutKeyedSlots(committed, currentGroupSep, currentDecimalSep, currentMinusSign, slotMeasure)
    for (ks in layout) {
      rollSlots[ks.key] = RollSlot().apply {
        kind = ks.kind; fromChar = ""; toChar = ks.char
        value = 1f; velocity = 0f; rolling = false
        life = 1f; lifeVel = 0f; lifeTarget = 1f
        cflOld = ks.centerFromLeft; cflNew = ks.centerFromLeft; hasOld = true; hasNew = true
      }
    }
    val total = layout.firstOrNull()?.totalWidth ?: slotMeasure(committed)
    slotOldWidth = total; slotNewWidth = total
    slotTargetText = committed
  }

  // Diff the current slot map against a new target: changed columns retarget (carrying velocity),
  // new columns are born, vanished columns start dying. Called for the first transition (after
  // seedSlots) and on every mid-flight retarget, so per-column continuity survives a rapid hold.
  private fun scheduleSlots(newFormatted: String, dir: Int) {
    val oldLayout = TransitionLogic.layoutKeyedSlots(slotTargetText, currentGroupSep, currentDecimalSep, currentMinusSign, slotMeasure)
    val newLayout = TransitionLogic.layoutKeyedSlots(newFormatted, currentGroupSep, currentDecimalSep, currentMinusSign, slotMeasure)
    val oldByKey = oldLayout.associateBy { it.key }
    val newKeys = HashSet<String>(newLayout.size)
    val started = ArrayList<RollSlot>()

    for (ks in newLayout) {
      newKeys.add(ks.key)
      val existing = rollSlots[ks.key]
      if (existing == null) {
        // Born: appears with a fade + scale at its new-layout position (no roll).
        rollSlots[ks.key] = RollSlot().apply {
          kind = ks.kind; fromChar = ""; toChar = ks.char
          value = 1f; velocity = 0f; rolling = false
          life = 0f; lifeVel = 0f; lifeTarget = 1f
          direction = dir
          cflNew = ks.centerFromLeft; hasNew = true
          cflOld = oldByKey[ks.key]?.centerFromLeft ?: ks.centerFromLeft
          hasOld = oldByKey.containsKey(ks.key)
        }
      } else {
        existing.kind = ks.kind
        existing.lifeTarget = 1f
        // The glyph's current on-screen spot becomes its outgoing (OLD) position.
        existing.cflOld = existing.cflNew; existing.hasOld = true
        existing.cflNew = ks.centerFromLeft; existing.hasNew = true
        if (existing.toChar != ks.char) {
          val wasRolling = existing.rolling
          existing.fromChar = existing.toChar     // roll from the previous target
          existing.toChar = ks.char
          existing.value = 0f                      // restart the roll; velocity is preserved
          existing.direction = dir
          existing.rolling = true
          // Only columns starting from rest join the cascade. Re-staggering a column that is
          // already mid-roll would freeze it mid-flight — during rapid multi-digit updates the
          // next value arrives before the cascade finishes, and that read as stutter/dropped frames.
          if (wasRolling) existing.delay = 0f else started.add(existing)
        }
      }
    }
    // Columns no longer present start their death animation, frozen at their OLD-layout position.
    for ((k, s) in rollSlots) if (!newKeys.contains(k)) {
      s.lifeTarget = 0f; s.hasNew = false
      oldByKey[k]?.let { s.cflOld = it.centerFromLeft; s.hasOld = true }
    }

    // LEFT→RIGHT stagger: leftmost (smallest centre-from-left) newly-rolling column first.
    started.sortBy { it.cflNew }
    for ((i, s) in started.withIndex()) s.delay = i * staggerSeconds

    // Two centred layouts: remember each layout's total width to place its origin.
    slotOldWidth = oldLayout.firstOrNull()?.totalWidth ?: slotMeasure(slotTargetText)
    slotNewWidth = newLayout.firstOrNull()?.totalWidth ?: slotMeasure(newFormatted)
    slotTargetText = newFormatted
  }

  private fun tickSlots(dt: Float) {
    val it = rollSlots.iterator()
    while (it.hasNext()) {
      val s = it.next().value
      // Presence (birth/death) — critically damped, no bounce.
      val (lx, lv) = TransitionLogic.springStep(s.life, s.lifeVel, s.lifeTarget, lifeStiffness, lifeDampingRatio, dt)
      s.life = lx.coerceIn(0f, 1.05f); s.lifeVel = lv

      if (s.delay > 0f) {
        s.delay = (s.delay - dt).coerceAtLeast(0f)
      } else if (s.rolling) {
        val (x, v) = TransitionLogic.springStep(s.value, s.velocity, 1f, springStiffness, springDampingRatio, dt)
        s.value = x; s.velocity = v
        if (x >= 1f && abs(v) < 0.02f) { s.rolling = false; s.value = 1f; s.velocity = 0f }
      }

      if (s.lifeTarget == 0f && s.life < 0.02f) it.remove()
    }
  }

  private fun slotsAtRest(): Boolean =
    rollSlots.values.all {
      !it.rolling && it.delay <= 0f && it.lifeTarget == 1f && abs(it.life - 1f) < 0.02f
    }

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

    val changedClipTop = bl + textPaint.fontMetrics.ascent - travel * 0.3f
    val changedClipBottom = bl + textPaint.fontMetrics.descent + travel * 0.3f

    val maskTop = bl + textPaint.fontMetrics.ascent - travel * 0.5f
    val maskBottom = bl + textPaint.fontMetrics.descent + travel * 0.5f

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

    val stableTop = bl + textPaint.fontMetrics.ascent - travel * 0.3f
    val stableBottom = bl + textPaint.fontMetrics.descent + travel * 0.3f

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
    val ascent = textPaint.fontMetrics.ascent
    val descent = textPaint.fontMetrics.descent
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
      animationProgress = 1f; activePlan = null; rollSlots.clear()
      updateContentDescription(); invalidate(); requestLayout(); return
    }
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
    numericLocale = v; currentFormatterLocale = null; recalcFormatter(); textLayersNeedRebuild = true
    maskPaintNeedsUpdate = true; requestLayout()
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
    val quiet = (now - lastValueChangeNanos) > 160_000_000L
    if (x >= 1f && quiet && slotsAtRest() && abs(x - 1f) < 0.002f && abs(v) < 0.02f) {
      if (!completionFired) { completionFired = true; springValue = 1f; springVelocity = 0f; settleTo(formatNumber(numericValue)) }
      return
    }
    invalidate()
  }

  // Mid-flight retarget: keep the running animator + global progress and swap the target to the
  // newest value; per-slot, only the columns whose character actually changed retarget (carrying
  // their velocity), so a rapid burst becomes one continuous per-column roll.
  private fun retargetTransition() {
    if (springValue >= 1f) {
      activePlan?.let { settledValue = it.newValue; settledText = it.newFormatted }
      springValue = (springValue - 1f).coerceAtLeast(0f)
    }

    val oldFormatted = settledText
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
    animationProgress = 1f; activePlan = null; rollSlots.clear()
    if (debugManualProgress < 0f) { debugPlan = null }
    springValue = 1f; springVelocity = 0f
    updateContentDescription(); invalidate()
    val a = animator; animator = null; a?.removeAllListeners(); a?.cancel()
    val dw = ceil(textPaint.measureText(text) + paddingLeft + paddingRight).toInt().coerceAtLeast(suggestedMinimumWidth)
    if (measuredWidth != dw) requestLayout()
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
    val w = when (numericFontWeight) { "bold" -> Typeface.BOLD; "normal" -> Typeface.NORMAL
      else -> { val n = numericFontWeight.toIntOrNull() ?: Typeface.NORMAL; if (n >= 700) Typeface.BOLD else Typeface.NORMAL } }
    textPaint.typeface = Typeface.create(Typeface.DEFAULT, w)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) textPaint.fontFeatureSettings = "tnum"
    textLayersNeedRebuild = true; maskPaintNeedsUpdate = true
  }

  private fun getTextHeight(): Float { val fm = textPaint.fontMetrics; return fm.descent - fm.ascent }
  private fun baselineY(centerY: Float): Float { val fm = textPaint.fontMetrics; val h = fm.descent - fm.ascent; return centerY + h / 2f - fm.descent }
  private fun updateContentDescription() { contentDescription = settledText }
}
