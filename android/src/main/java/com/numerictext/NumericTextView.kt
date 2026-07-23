package com.numerictext

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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

  // ── State ──
  private var currentDirection: Int = 1
  private var animationProgress: Float = 1f
  private var animator: ValueAnimator? = null

  // Spring driver state. `springValue` is the progress (0→1, may overshoot >1 for the
  // bounce); `springVelocity` is preserved across retargets for inertia continuity.
  private var springValue: Float = 1f
  private var springVelocity: Float = 0f
  private var lastTickNanos: Long = 0L
  private var lastValueChangeNanos: Long = 0L
  // 0→1 "burst activity": rises when values arrive faster than a transition can settle, so
  // the changing digits stay blurred during a rapid hold (like SwiftUI) and only sharpen
  // once input stops. Unchanged digits are anchors and stay sharp regardless.
  private var inputActivity: Float = 0f
  private val burstGapSeconds: Float = 0.15f   // inputs closer than this count as a burst
  private val activityDecaySeconds: Float = 0.18f // how long blur lingers after input stops
  // Knobs — tune against iOS. dampingRatio < 1 gives the snappy overshoot; stiffness sets
  // how fast it settles (~4/(ratio·√stiffness) seconds).
  private val springStiffness: Float = 320f
  private val springDampingRatio: Float = 0.7f
  // Velocity (progress units/s) that maps to full blur. Higher spring velocity — e.g. an
  // inherited rapid-hold — pushes blur toward max, like SwiftUI.
  private val blurVelocityRef: Float = 8f
  private var formatter: NumberFormat? = null
  private var currentFormatterLocale: Locale? = null
  private var currentGroupSep: Char = ','
  private var currentDecimalSep: Char = '.'
  private var currentMinusSign: Char = '-'

  private var activePlan: LayerPlan? = null
  private var debugPlan: LayerPlan? = null
  private var perGlyphPlan: TransitionPlan? = null
  private var pendingCompletion: (() -> Unit)? = null
  private var completionFired: Boolean = false
  private var textLayersNeedRebuild: Boolean = true
  private var maskPaintNeedsUpdate: Boolean = true

  // Full-text layers cached as RenderNode (API 29+)
  private var oldTextNode: RenderNode? = null
  private var newTextNode: RenderNode? = null

  // Per-glyph directional-blur layers (API 31+), reused across frames.
  private var perGlyphOldNode: RenderNode? = null
  private var perGlyphNewNode: RenderNode? = null
  private var cachedOldFormatted: String? = null
  private var cachedNewFormatted: String? = null

  private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    textAlign = Paint.Align.LEFT
  }

  // Soft vertical mask paint (reused, updated when font metrics change)
  private val verticalMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
  }

  private val overscanHorizontal = 4f

  // Two central visual knobs — tune against on-device iOS comparison.
  // travelFactor: vertical roll as a fraction of line-height. The SwiftUI roll is subtle;
  //   most of the "motion" read comes from the blur, not the travel.
  private val travelFactor = 0.42f
  // blurFactor: peak blur radius as a fraction of line-height. This is the dominant effect.
  private val blurFactor = 0.18f
  // Extra height (per side, × line-height) reserved so the blur/roll can breathe.
  private val verticalHeadroomFactor = 0.14f
  private val travel: Float get() = getTextHeight() * travelFactor

  init {
    recalcTextPaint()
    recalcFormatter()
  }

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
      val pg = perGlyphPlan
      if (pg != null && (!settledLike || scrubbing)) {
        drawPerGlyph(canvas, pg, if (scrubbing) progress.coerceIn(0f, 0.999f) else progress)
        return
      }
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
    textPaint.alpha = 255
    val cx = width / 2f
    val cy = height / 2f
    val bl = baselineY(cy)
    canvas.drawText(settledText, cx - textPaint.measureText(settledText) / 2f, bl, textPaint)
  }

  private fun drawTransition(canvas: Canvas, plan: LayerPlan, progress: Float) {
    val bl = baselineY(height / 2f)
    val hProg = TransitionLogic.layoutInterpolation(progress)

    // Origins in view space
    val oldOriginX = (width.toFloat() - plan.oldWidth) / 2f
    val newOriginX = (width.toFloat() - plan.newWidth) / 2f
    val originX = oldOriginX + (newOriginX - oldOriginX) * hProg

    val changedLeft = originX + plan.oldPrefixAdvance + (plan.newPrefixAdvance - plan.oldPrefixAdvance) * hProg
    val interpolChangedAdv = plan.oldChangedAdvance + (plan.newChangedAdvance - plan.oldChangedAdvance) * hProg

    val hOverscan = overscanHorizontal
    val changedRegionLeft = changedLeft - hOverscan
    val changedRegionRight = changedLeft + interpolChangedAdv + hOverscan

    // 1) Stable prefix + suffix: draw new full text clipped to the stable region
    drawStableRegions(canvas, plan, bl, originX, hProg)

    // 2) Changed region: old + new full-text layers with transforms and soft mask
    val oldOriginXFull = oldOriginX
    val newOriginXFull = newOriginX

    val oldOffset = TransitionLogic.computeOldOffset(currentDirection, travel, progress)
    val newOffset = TransitionLogic.computeNewOffset(currentDirection, travel, progress)
    val oldAlpha = (255 * TransitionLogic.oldOpacity(progress)).toInt().coerceIn(0, 255)
    val newAlpha = (255 * TransitionLogic.newOpacity(progress)).toInt().coerceIn(0, 255)

    // Old changed bounds in view space
    val oldChangedViewLeft = oldOriginXFull + plan.oldPrefixAdvance
    val oldChangedViewRight = oldChangedViewLeft + plan.oldChangedAdvance
    val newChangedViewLeft = newOriginXFull + plan.newPrefixAdvance
    val newChangedViewRight = newChangedViewLeft + plan.newChangedAdvance

    updateMaskPaint(bl)

    val changedClipTop = bl + textPaint.fontMetrics.ascent - travel * 0.3f
    val changedClipBottom = bl + textPaint.fontMetrics.descent + travel * 0.3f

    // Soft vertical mask bounds
    val maskTop = bl + textPaint.fontMetrics.ascent - travel * 0.5f
    val maskBottom = bl + textPaint.fontMetrics.descent + travel * 0.5f

    canvas.save()

    // Clip to the overall changed region horizontally, extended vertically
    canvas.clipRect(changedRegionLeft, changedClipTop, changedRegionRight, changedClipBottom)

    // --- Old text layer ---
    if (oldAlpha > 0 && plan.oldChangedUtf16Start < plan.oldChangedUtf16End) {
      val oldSave = canvas.saveLayer(
        oldChangedViewLeft - hOverscan, changedClipTop,
        oldChangedViewRight + hOverscan, changedClipBottom,
        null
      )
      textPaint.alpha = oldAlpha
      val scale = TransitionLogic.scaleEnvelope(progress)
      val pivotY = bl
      if (scale < 1f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // Use RenderEffect for scale if available, else just translate
      }
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

    // --- New text layer ---
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

    // Prefix region: from originX to originX + interpolated prefix advance
    if (plan.commonPrefixUtf16End > 0) {
      val prefixAdv = plan.oldPrefixAdvance + (plan.newPrefixAdvance - plan.oldPrefixAdvance) * hProg
      val prefixRight = originX + prefixAdv
      canvas.save()
      canvas.clipRect(originX, stableTop, originX + prefixAdv, stableBottom)
      drawFullText(canvas, plan.newFormatted, originX, bl, textPaint)
      canvas.restore()
    }

    // Suffix region: from originX + interpolated prefix + changed to originX + interpolated total
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

  // ── Per-glyph renderer (faithful SwiftUI numericText) ──
  //
  // Each matched slot (right-aligned digits, separators as their own slots) is drawn
  // independently: unchanged slots stay sharp and static (anchors), changed slots roll
  // vertically with a crossfade + bell-curve blur. This keeps a stable interior digit
  // sharp (e.g. 1,919→1,616) and aligns units across length changes (999→1,000) — the
  // two cases where the changed-run block renderer diverges from iOS.

  private fun drawPerGlyph(canvas: Canvas, plan: TransitionPlan, progress: Float) {
    val bl = baselineY(height / 2f)
    // `progress` (spring value) can overshoot >1 for the bounce; clamp for layout/alpha,
    // use raw for the vertical offset so the new glyph springs slightly past its baseline.
    val pClamped = progress.coerceIn(0f, 1f)
    val hProg = TransitionLogic.layoutInterpolation(pClamped)

    val oldLeftView = (width - plan.oldWidth) / 2f
    val newLeftView = (width - plan.newWidth) / 2f

    val oldOffset = TransitionLogic.computeOldOffset(currentDirection, travel, progress)
    val newOffset = TransitionLogic.computeNewOffset(currentDirection, travel, progress)
    val oldAlpha = (255 * (1f - pClamped)).toInt().coerceIn(0, 255)
    val newAlpha = (255 * pClamped).toInt().coerceIn(0, 255)
    // Blur radius scales with glyph height (a fixed px radius is invisible on a large glyph)
    // AND with the spring's velocity — fast motion (incl. inherited rapid-hold inertia)
    // blurs more, sharp at rest. This velocity coupling is the SwiftUI "buttery" cue.
    val maxBlur = getTextHeight() * blurFactor
    val blurAmount =
      if (debugManualProgress >= 0f) TransitionLogic.blurEnvelope(progress) // freeze-frame: position-based
      else maxOf(
        (kotlin.math.abs(springVelocity) / blurVelocityRef).coerceIn(0f, 1f), // single-step pulse
        inputActivity // sustained during a fast hold
      )
    val oldRadius = maxBlur * blurAmount
    val newRadius = maxBlur * blurAmount

    fun slotCx(slot: GlyphSlot): Float = when {
      slot.oldToken != null && slot.newToken != null -> {
        val o = oldLeftView + slot.oldX; val n = newLeftView + slot.newX
        o + (n - o) * hProg
      }
      slot.newToken != null -> newLeftView + slot.newX
      else -> oldLeftView + slot.oldX
    }

    // 1) Anchors: unchanged slots drawn once, sharp, at the interpolated slot position.
    textPaint.maskFilter = null
    textPaint.alpha = 255
    for (slot in plan.slots) {
      if (!slot.changed && slot.oldToken != null && slot.newToken != null) {
        val oldCx = oldLeftView + slot.oldX
        val newCx = newLeftView + slot.newX
        drawGlyph(canvas, slot.newToken.text, oldCx + (newCx - oldCx) * hProg, bl, textPaint)
      }
    }

    // 2) Changed / inserted / removed slots roll in-slot with a VERTICAL motion blur.
    //    Directional blur (tiny X radius, larger Y) keeps the glyph readable horizontally
    //    and reads as vertical motion — an isotropic blur turns it into a shapeless blob.
    val changed = plan.slots.filter { it.changed || it.oldToken == null || it.newToken == null }
    if (changed.isEmpty()) { textPaint.maskFilter = null; return }

    val directional = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated
    if (directional) {
      val oldItems = changed.mapNotNull { s -> s.oldToken?.let { Triple(it.text, slotCx(s), bl + oldOffset) } }
      val newItems = changed.mapNotNull { s -> s.newToken?.let { Triple(it.text, slotCx(s), bl + newOffset) } }
      perGlyphOldNode = drawGlyphLayerDirectional(canvas, perGlyphOldNode, oldItems, oldAlpha, oldRadius)
      perGlyphNewNode = drawGlyphLayerDirectional(canvas, perGlyphNewNode, newItems, newAlpha, newRadius)
    } else {
      // Fallback (<API 31 or software canvas): isotropic blur, reduced so it's less blobby.
      for (slot in changed) {
        val cx = slotCx(slot)
        slot.oldToken?.let { drawGlyphBlurred(canvas, it.text, cx, bl + oldOffset, oldAlpha, oldRadius * 0.6f) }
        slot.newToken?.let { drawGlyphBlurred(canvas, it.text, cx, bl + newOffset, newAlpha, newRadius * 0.6f) }
      }
    }
    textPaint.maskFilter = null
  }

  private fun drawGlyph(canvas: Canvas, text: String, centerX: Float, baseline: Float, paint: TextPaint) {
    if (text.isEmpty()) return
    canvas.drawText(text, centerX - paint.measureText(text) / 2f, baseline, paint)
  }

  // Renders the given glyphs into a reused RenderNode and applies a vertical-only blur
  // (RenderEffect, API 31+). The whole layer is then composited at `alpha` for the crossfade.
  @SuppressLint("NewApi")
  private fun drawGlyphLayerDirectional(
    canvas: Canvas, existing: RenderNode?,
    items: List<Triple<String, Float, Float>>, alpha: Int, radiusY: Float
  ): RenderNode? {
    if (alpha <= 0 || items.isEmpty()) return existing
    val node = existing ?: RenderNode("perGlyphLayer")
    node.setPosition(0, 0, width, height)
    val rec = node.beginRecording()
    textPaint.maskFilter = null
    textPaint.alpha = 255
    for ((text, cx, baseline) in items) {
      rec.drawText(text, cx - textPaint.measureText(text) / 2f, baseline, textPaint)
    }
    node.endRecording()
    node.setRenderEffect(
      if (radiusY >= 0.8f)
        RenderEffect.createBlurEffect(max(1f, radiusY * 0.10f), radiusY, Shader.TileMode.DECAL)
      else null
    )
    node.alpha = alpha / 255f
    canvas.drawRenderNode(node)
    return node
  }

  // ponytail: isotropic-blur fallback for old devices; allocates a BlurMaskFilter per frame.
  private fun drawGlyphBlurred(canvas: Canvas, text: String, centerX: Float, baseline: Float, alpha: Int, radius: Float) {
    if (text.isEmpty() || alpha <= 0) return
    textPaint.alpha = alpha
    textPaint.maskFilter = if (radius >= 0.8f) BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) else null
    canvas.drawText(text, centerX - textPaint.measureText(text) / 2f, baseline, textPaint)
    textPaint.maskFilter = null
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

  // ── Soft mask ──

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

  // ── Blur (API 31+) ──

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
    val gap = if (lastValueChangeNanos == 0L) Float.MAX_VALUE else (now - lastValueChangeNanos) / 1_000_000_000f
    numericValue = newValue
    lastValueChangeNanos = now
    // Two inputs within burstGap → we're in a fast hold; hold blur high. Isolated taps keep
    // the natural velocity-driven pulse.
    if (gap < burstGapSeconds) inputActivity = 1f
    if (!hasSettledOnce) {
      hasSettledOnce = true; settledValue = newValue; settledText = formatNumber(newValue)
      animationProgress = 1f; activePlan = null; perGlyphPlan = null
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

    if (!shouldAnimate()) { settleTo(newFormatted); return }

    val needsLayout = max(plan.oldWidth, plan.newWidth) > measuredWidth || plan.newWidth != plan.oldWidth
    if (needsLayout) requestLayout()

    startSpringTicker()
  }

  // A ValueAnimator used only as a per-frame clock; the spring is integrated by real dt.
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

    inputActivity = (inputActivity - dt / activityDecaySeconds).coerceAtLeast(0f)

    val (x, v) = TransitionLogic.springStep(
      springValue, springVelocity, 1f, springStiffness, springDampingRatio, dt
    )
    springValue = x
    springVelocity = v
    animationProgress = x

    // Settled: near the goal AND slow AND no value change in the last ~160ms. The last
    // condition keeps the ticker alive during a rapid hold, so each press re-bases and
    // rolls continuously instead of settling and dead-starting between presses.
    val quiet = (now - lastValueChangeNanos) > 160_000_000L
    if (x >= 1f && quiet && kotlin.math.abs(x - 1f) < 0.002f && kotlin.math.abs(v) < 0.02f) {
      if (!completionFired) { completionFired = true; springValue = 1f; springVelocity = 0f; settleTo(formatNumber(numericValue)) }
      return
    }
    invalidate()
  }

  // Mid-flight retarget: keep the burst origin (settled value) and the running animator
  // + progress, and swap the target to the newest value. A rapid burst (100→101→…→156)
  // becomes ONE continuous roll instead of restarting on every tick. The blur is high while
  // the target keeps moving, so the target swap is not visible.
  private fun retargetTransition() {
    // If the current segment already reached its end (spring at/over 1), commit it and roll
    // the new target from the start, carrying velocity. Seamless because the finished value
    // is exactly what's on screen — this is what keeps a sustained hold flowing.
    if (springValue >= 1f) {
      activePlan?.let { settledValue = it.newValue; settledText = it.newFormatted }
      springValue = (springValue - 1f).coerceAtLeast(0f)
    }

    val oldFormatted = settledText
    val newFormatted = formatNumber(numericValue)
    if (newFormatted == oldFormatted) {
      // Bounced back to the origin mid-flight — nothing left to roll to; settle.
      cancelAnimation(); settleTo(oldFormatted); return
    }
    if (activePlan?.newFormatted == newFormatted) return // target text unchanged

    val dir = when (numericDirection) {
      "up" -> 1; "down" -> -1
      else -> numericValue.compareTo(settledValue).coerceIn(-1, 1).let { if (it == 0) currentDirection else it }
    }
    val plan = buildPlan(resolveStrategy(), settledValue, numericValue, oldFormatted, newFormatted)
    activePlan = plan
    if (debugManualProgress >= 0f) debugPlan = plan
    currentDirection = dir
    // animationProgress and the running animator are intentionally preserved.

    val needsLayout = max(plan.oldWidth, plan.newWidth) > measuredWidth || plan.newWidth != plan.oldWidth
    if (needsLayout) requestLayout()
    invalidate()
  }

  private fun buildPlan(strategy: TransitionStrategy, oldValue: Double, newValue: Double, oldFormatted: String, newFormatted: String): LayerPlan {
    val measure = { text: String, start: Int, end: Int -> textPaint.measureText(text, start, end) }
    return when (strategy) {
      TransitionStrategy.WHOLE_RUN -> TransitionLogic.buildWholeRunPlan(oldValue, newValue, oldFormatted, newFormatted, measure)
      TransitionStrategy.CHANGED_RUN -> TransitionLogic.buildLayerPlan(oldValue, newValue, oldFormatted, newFormatted, measure)
      TransitionStrategy.PER_GLYPH -> {
        perGlyphPlan = TransitionLogic.buildPerGlyphPlan(
          oldFormatted, newFormatted,
          currentGroupSep, currentDecimalSep, currentMinusSign
        ) { textPaint.measureText(it) }
        // LayerPlan still built so onMeasure has old/new widths.
        TransitionLogic.buildLayerPlan(oldValue, newValue, oldFormatted, newFormatted, measure)
      }
    }
  }

  private fun settleTo(text: String) {
    settledText = text; settledValue = numericValue
    animationProgress = 1f; activePlan = null; pendingCompletion = null
    // Keep the per-glyph plan alive while debug-scrubbing so the freeze-frame renders.
    if (debugManualProgress < 0f) { debugPlan = null; perGlyphPlan = null }
    springValue = 1f; springVelocity = 0f; inputActivity = 0f
    updateContentDescription(); invalidate()
    // Stop the (infinite) spring ticker; remove the listener first so no further tick fires.
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