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
  // Measured: the iOS roll's settle tail is ~0.45s (centroid still moving 24+ frames after
  // onset) with a gentle mass overshoot — that's a soft spring ≈150-170, not 320.
  private val springStiffness: Float = 170f
  // Under-damped enough to keep a slight, lively settle in the roll's direction.
  private val springDampingRatio: Float = 0.78f
  // Velocity that maps to full roll blur (position+velocity blend below).
  private val blurVelocityRef: Float = 9f
  // Opacity drop at full blur. A Gaussian blur alone doesn't lighten a large glyph enough — the
  // reference's out-of-focus bolla is LIGHT grey, so opacity is coupled to the blur amount.
  // 0.35 washed the mid-transition out too much (user-confirmed: ghosts too pale + an "empty
  // breath" at the crossing that iOS never has — its grey mass stays present throughout).
  private val blurAlphaDrop: Float = 0.22f
  // Depth of the overlapped roll (subtle — the roll reads mostly from blur + vertical travel).
  private val rollDepthMin: Float = 0.9f

  // BORN/DYING lifecycle durations (seconds). Measured against the iOS reference grids: a length
  // change spans ~300-400ms there; our previous 0.26/0.30 completed in roughly half iOS's time.
  private val exitDuration: Float = 0.32f
  private val enterDuration: Float = 0.40f
  // Fraction of the positional stagger an ENTER keeps (measured: iOS 9,999→1 enter starts
  // ~0.12s in = lag + 3·stagger·0.55; full stagger entered too late, 0.4 too early).
  private val enterCascadeCompression: Float = 0.55f
  // Spacing BETWEEN successive arrivals (measured on 1→1.5: iOS separates "." and "5" by ~5
  // frames — the same unhurried pace as the rolls, not the tight exit stagger).
  private val enterSpacingSeconds: Float = 0.05f
  private val enterLag: Float = 0.04f

  // The horizontal reflow (anchors sliding, layouts recomposing) runs on its OWN slow clock. The
  // global spring settles in ~150ms, and driving the anchor from it made the surviving "1" of
  // 1→1.5 JUMP left in ~2 frames instead of gliding late over ~120ms like the reference.
  private val layoutReflowSeconds: Float = 0.45f
  private var layoutP: Float = 1f
  // LEFT→RIGHT cascade (reverse-engineered from the iOS reference at 60fps: on a multi-digit
  // change the leftmost changed column leads). Kept SUBTLE — a large delay turns the cascade into
  // a visibly sequential, machine-like wave.
  private val staggerSeconds: Float = 0.04f
  // ROLLS cascade slower than exits (measured on 2,599→2,600: ~4.5-5 frames ≈ 75-83ms per digit,
  // vs ~2.25 frames between exits in 9,999→1).
  private val rollStaggerSeconds: Float = 0.075f

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

  // ── Per-glyph lifecycle model (default PER_GLYPH renderer) ──
  //
  // Each logical column (units, tens, …, separators) is keyed by a stable id (see
  // TransitionLogic.KeyedSlot) and owns up to TWO independent glyph lifecycles: one leaving and
  // one arriving, each with its own clock and its own property curves. Only columns whose
  // character changes are disturbed; the rest render sharp. This replaced a single shared spring
  // driving every property, which could not reproduce the reference's onset/termination.
  private class RollSlot {
    var kind: TokenKind = TokenKind.DIGIT
    // A column is one of two things, and they animate differently (this distinction is the whole
    // point — conflating them made a pure digit roll pop):
    //   • ROLL — the same logical slot changes value (6→7). Old and new OVERLAP and roll together
    //     on ONE shared spring; the two glyphs coexist, giving the continuous rotation feel.
    //   • BORN / DYING — a whole column appears or disappears (a leading digit, a separator). One
    //     glyph, with its own degrade-in / degrade-out lifecycle (blur, scale, drift out of the way).
    var exitChar: String = ""      // glyph on its way out ("" = nothing leaving)
    var enterChar: String = ""     // glyph arriving / currently settled character
    var rolling: Boolean = false   // true → overlapped roll; false → born/dying lifecycle
    var rollP: Float = 1f          // shared roll progress (overshoots past 1 for the settle)
    var rollV: Float = 0f          // roll velocity, preserved across retargets for continuity
    var exitP: Float = 1f          // 0→1 lifecycle exit progress (1 = fully gone)
    var enterP: Float = 1f         // 0→1 lifecycle enter progress (1 = fully arrived and sharp)
    var delay: Float = 0f          // stagger before the roll / the incoming glyph starts
    var exitDelay: Float = 0f      // stagger before the outgoing glyph starts leaving
    var direction: Int = 1
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
  private val blurFactor = 0.16f
  // Extra height (per side, × line-height) reserved so the blur/roll can breathe.
  private val verticalHeadroomFactor = 0.16f
  // Depth scale: a rolling glyph shrinks toward this as it leaves and grows back on arrival,
  // so the motion reads as a digit rotating on a cylinder rather than a flat 2D guillotine.
  // Depth: the leaving glyph shrinks more (it recedes), the arriving one barely scales — it
  // resolves by coming into focus rather than by travelling or growing a lot.
  private val exitMinScale = 0.74f
  // A born glyph is a BLOB when it spawns: small + heavily blurred, displaced from its final
  // spot (outward + along the roll axis), then it slides in and comes into focus. A tame
  // fade-in-place read as "the final number at low opacity" — too recognisable.
  private val enterMinScale = 0.72f
  private val enterTravelFactor = 2.0f
  // Horizontal spawn displacement of a born glyph, as a fraction of its width: it appears
  // displaced toward the composition's growing edge (e.g. the trailing "5" from the right) and
  // slides to its slot. Direction derived from geometry — no hardcoding.
  private val enterSpawnXFactor = 0.15f
  // Small outward drift of a leaving glyph (fraction of its width, away from the new centre) —
  // the reference's dying digits spread slightly apart as they fade, they don't converge.
  private val exitDriftOut = 0.18f
  // The surviving ANCHOR reflows LATE: it starts sliding only after the births are underway
  // ("the 1 moves left once the 5 is sharp"). Fraction of the transition before its slide starts.
  private val anchorLagStart = 0.12f

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
    // HORIZONTAL REFLOW. Each glyph has its OWN X trajectory that interpolates between its position
    // in the old centred layout and its position in the new one, driven by a single shared clock
    // (`xL`). A surviving/replacing column slides smoothly old→new instead of jumping; born/dying
    // columns slide with the composition as it expands/contracts. The trajectory is derived purely
    // from the two measured layouts — no hardcoded direction — so it works for any font/locale.
    val oldOriginX = (width - slotOldWidth) / 2f
    val newOriginX = (width - slotNewWidth) / 2f
    val sp = if (scrub != null) scrub.coerceIn(0f, 1f) else layoutP.coerceIn(0f, 1f)
    val xL = TransitionLogic.smootherstep(sp)
    // Lagged clock: the surviving anchor slides only after the births are underway.
    val xLa = TransitionLogic.smoothstep(anchorLagStart, 1f, sp)
    val newCenterX = newOriginX + slotNewWidth / 2f
    val h = getTextHeight()
    val travelPx = h * travelFactor
    val maxBlurPx = h * blurFactor
    val nodeCapable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canvas.isHardwareAccelerated

    // A glyph's current X: the old glyph flows toward the new slot (or, if it has no new slot, it
    // rides the shrinking composition); the new glyph flows in from where the old slot was.
    fun xOldOf(s: RollSlot) = lerp(oldOriginX + s.cflOld, (if (s.hasNew) newOriginX + s.cflNew else newOriginX + s.cflOld), xL)
    fun xNewOf(s: RollSlot) = lerp((if (s.hasOld) oldOriginX + s.cflOld else oldOriginX + s.cflNew), newOriginX + s.cflNew, xL)
    fun anchorXOf(s: RollSlot) = lerp(oldOriginX + s.cflOld, newOriginX + s.cflNew, xLa)

    // Pass 1 — settled anchors (sharp). They still slide along their reflow trajectory: an anchor
    // whose logical position shifts (e.g. the "1" in 1→1.5) glides to its new spot.
    textPaint.maskFilter = null; textPaint.alpha = 255
    for (s in rollSlots.values) {
      if (!isAnimated(s)) drawGlyph(canvas, s.enterChar, anchorXOf(s), bl, textPaint)
    }

    // Pass 2 — moving / born / dying columns, each with its own blur + depth.
    for (s in rollSlots.values) {
      if (!isAnimated(s)) continue
      val newCx = xNewOf(s)
      val oldCx = xOldOf(s)

      // iOS roll direction: increment → new enters from the TOP (content moves down); decrement
      // → new from the bottom. Our offset helpers encode the opposite convention, so negate.
      val d = -s.direction

      if (s.rolling && s.delay > 0f) {
        // Waiting its turn in the left→right cascade: still showing the previous value, sharp.
        textPaint.maskFilter = null; textPaint.alpha = 255
        drawGlyph(canvas, s.exitChar, oldCx, bl, textPaint)
      } else if (s.rolling) {
        // ── OVERLAPPED ROLL: old and new coexist and roll together on the shared spring. The two
        // glyphs blend into one soft moving mass — the continuous digit roll. NO sideways drift.
        val p = scrub ?: s.rollP
        val pC = p.coerceIn(0f, 1f)
        val motionBlur = (abs(s.rollV) / blurVelocityRef).coerceIn(0f, 1f)
        val transitionBlur = TransitionLogic.rollBlurEnvelope(pC)
        val blurAmt = if (scrub != null) transitionBlur else max(motionBlur * 0.6f, transitionBlur * 0.85f)
        val radius = maxBlurPx * blurAmt
        val pv = TransitionLogic.smootherstep(pC)
        val oldOff = TransitionLogic.computeOldOffset(d, travelPx, p)
        val newOff = TransitionLogic.computeNewOffset(d, travelPx, p)
        // Gently asymmetric crossfade with generous overlap; the sum dips slightly at the crossing
        // so two overlapping blurred layers don't read as a doubled dark glyph.
        val lighten = 1f - blurAlphaDrop * blurAmt
        val oldAlpha = ((1f - TransitionLogic.smoothstep(0.08f, 0.78f, pC)) * lighten * 255f).toInt().coerceIn(0, 255)
        val newAlpha = (TransitionLogic.smoothstep(0.14f, 0.96f, pC) * lighten * 255f).toInt().coerceIn(0, 255)
        val oldScale = lerp(1f, rollDepthMin, pv)
        val newScale = lerp(rollDepthMin, 1f, pv)
        if (nodeCapable) {
          s.oldNode = drawSlotGlyphNode(canvas, s.oldNode, s.exitChar, oldCx, bl, oldOff, oldScale, oldAlpha, radius)
          s.newNode = drawSlotGlyphNode(canvas, s.newNode, s.enterChar, newCx, bl, newOff, newScale, newAlpha, radius)
        } else {
          drawGlyphBlurred(canvas, s.exitChar, oldCx, bl + oldOff, oldAlpha, radius * 0.6f)
          drawGlyphBlurred(canvas, s.enterChar, newCx, bl + newOff, newAlpha, radius * 0.6f)
        }
      } else if (!s.rolling) {
        // ── LEAVING glyph (dying column, or the outgoing half of a structural replacement).
        // It fades ESSENTIALLY IN PLACE: frozen at its old-layout position with only a small
        // outward drift (away from the new composition's centre) — measured against iOS, where a
        // big shrink (2,600→9) fades the old digits where they stand. Riding the full layout
        // contraction made every dying glyph slide to the middle and pile up into a jumble.
        if (s.exitChar.isNotEmpty()) {
          val e = if (scrub != null) scrub.coerceIn(0f, 1f) else s.exitP
          val alpha = (TransitionLogic.exitAlpha(e) * (1f - blurAlphaDrop * TransitionLogic.exitBlur(e)) * 255f).toInt().coerceIn(0, 255)
          if (alpha > 0) {
            val exCx = oldOriginX + s.cflOld
            val radius = maxBlurPx * TransitionLogic.exitBlur(e)
            val frac = TransitionLogic.exitOffsetFraction(e)
            // Same side as the roll's outgoing glyph: leaves DOWN for an increment, UP for a
            // decrement (e.g. the dying "1" in 10→9 exits upward).
            val yOff = -d * travelPx * frac
            val dirX = if (exCx >= newCenterX) 1f else -1f
            val xOff = dirX * textPaint.measureText(s.exitChar) * exitDriftOut * frac
            val scale = TransitionLogic.exitScale(e, exitMinScale)
            if (nodeCapable) {
              s.oldNode = drawSlotGlyphNode(canvas, s.oldNode, s.exitChar, exCx + xOff, bl, yOff, scale, alpha, radius)
            } else {
              drawGlyphBlurred(canvas, s.exitChar, exCx + xOff, bl + yOff, alpha, radius * 0.6f)
            }
          }
        }
        // ── BORN (enter only): spawns as a displaced BLOB (outward + along the roll axis, small +
        // very blurred) and slides to its slot coming into focus. `off` is the residual offset:
        // full at n=0, zero at n=1.
        if (s.enterChar.isNotEmpty() && s.delay <= 0f) {
          val n = if (scrub != null) scrub.coerceIn(0f, 1f) else s.enterP
          val alpha = (TransitionLogic.enterAlpha(n) * (1f - blurAlphaDrop * TransitionLogic.enterBlur(n)) * 255f).toInt().coerceIn(0, 255)
          if (alpha > 0) {
            val radius = maxBlurPx * TransitionLogic.enterBlur(n)
            val off = TransitionLogic.enterOffsetFraction(n)              // 1 → 0 as it settles
            // Same side as the roll's incoming glyph: from the TOP for an increment (content
            // moves down), from the bottom for a decrement.
            val yOff = d * travelPx * enterTravelFactor * off
            val finalX = newOriginX + s.cflNew
            val dirX = if (finalX >= newCenterX) 1f else -1f             // outward = growing edge
            val xSpawn = dirX * textPaint.measureText(s.enterChar) * enterSpawnXFactor * off
            val scale = TransitionLogic.enterScale(n, enterMinScale)
            if (nodeCapable) {
              s.newNode = drawSlotGlyphNode(canvas, s.newNode, s.enterChar, finalX + xSpawn, bl, yOff, scale, alpha, radius)
            } else {
              drawGlyphBlurred(canvas, s.enterChar, finalX + xSpawn, bl + yOff, alpha, radius * 0.6f)
            }
          }
        }
      }
    }
    textPaint.maskFilter = null; textPaint.alpha = 255
  }

  /** A column is animating while it is rolling, leaving, still arriving, or waiting to start. */
  private fun isAnimated(s: RollSlot): Boolean =
    s.rolling || s.delay > 0f || s.exitChar.isNotEmpty() || s.enterP < 1f

  private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

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
        // Near-ISOTROPIC: the reference's blurred glyph is a round, out-of-focus bolla, not a
        // directional streak. Only a slight vertical bias remains to hint at the roll axis.
        if (blurRadiusY >= 0.8f)
          RenderEffect.createBlurEffect(max(1f, blurRadiusY * 0.85f), blurRadiusY, Shader.TileMode.DECAL)
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
        kind = ks.kind; exitChar = ""; enterChar = ks.char
        exitP = 1f; enterP = 1f; delay = 0f
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

    // STRUCTURAL classifier (numeric structure, not pixel width): when the integer digit count
    // changes (10→9, 999→1,000), a changed digit column is NOT a same-slot substitution — the
    // reference shows its old glyph leaving and the new one arriving as independent glyphs with
    // their own timing (the "0" of 10→9 stays sharp and leaves LAST while the "9" is already
    // emerging). Stable structure keeps the overlapped roll.
    val oldIntCount = oldLayout.count { it.key.startsWith("I") }
    val newIntCount = newLayout.count { it.key.startsWith("I") }
    val structural = oldIntCount != newIntCount

    // Phases of the left→right cascade: each slot's exit and enter are separate entries, ordered
    // by the X they happen at. CRUCIAL: exits live in the old layout and enters in the new one,
    // so the shared axis is the position RELATIVE TO EACH LAYOUT'S CENTRE (both are centred in
    // the view). This interleaves arrivals among departures exactly like the reference —
    // 9,999→1: exit 9(-82) → exit ,(-47) → exit 9(-12) → ENTER 1(0) → exit 9(+35) → exit 9(+82).
    val oldW = oldLayout.firstOrNull()?.totalWidth ?: slotMeasure(slotTargetText)
    val newW = newLayout.firstOrNull()?.totalWidth ?: slotMeasure(newFormatted)
    data class Phase(val s: RollSlot, val x: Float, val isExit: Boolean)
    val phases = ArrayList<Phase>()
    fun exitX(cflOld: Float) = cflOld - oldW / 2f
    fun enterX(cflNew: Float) = cflNew - newW / 2f

    for (ks in newLayout) {
      newKeys.add(ks.key)
      val existing = rollSlots[ks.key]
      if (existing == null) {
        // Born: an enter with no exit.
        rollSlots[ks.key] = RollSlot().apply {
          kind = ks.kind; exitChar = ""; enterChar = ks.char
          exitP = 1f; enterP = 0f
          direction = dir
          cflNew = ks.centerFromLeft; hasNew = true
          cflOld = oldByKey[ks.key]?.centerFromLeft ?: ks.centerFromLeft
          hasOld = oldByKey.containsKey(ks.key)
        }.also { phases.add(Phase(it, enterX(it.cflNew), isExit = false)) }
      } else {
        existing.kind = ks.kind
        // The glyph's current on-screen spot becomes its outgoing (OLD) position.
        existing.cflOld = existing.cflNew; existing.hasOld = true
        existing.cflNew = ks.centerFromLeft; existing.hasNew = true
        if (existing.enterChar != ks.char) {
          val wasBusy = (existing.rolling && existing.rollP < 1f) ||
            (!existing.rolling && (existing.exitChar.isNotEmpty() || existing.enterP < 1f))
          existing.direction = dir
          if (structural) {
            // Independent lifecycles: old glyph leaves, new glyph arrives, each on its own clock.
            existing.exitChar = existing.enterChar
            existing.enterChar = ks.char
            existing.rolling = false
            existing.exitP = 0f; existing.enterP = 0f
            if (wasBusy) { existing.delay = 0f; existing.exitDelay = 0f }
            else {
              phases.add(Phase(existing, exitX(existing.cflOld), isExit = true))
              phases.add(Phase(existing, enterX(existing.cflNew), isExit = false))
            }
          } else {
            // Same slot in a stable structure → the overlapped ROLL (continuous digit roll).
            existing.exitChar = existing.enterChar
            existing.enterChar = ks.char
            existing.rolling = true
            existing.rollP = 0f                    // restart the roll; rollV preserved for continuity
            existing.exitP = 1f; existing.enterP = 1f
            // Only columns starting from rest join the cascade. Re-staggering a column already
            // mid-roll would freeze it — the rapid-burst stutter.
            if (wasBusy) existing.delay = 0f else phases.add(Phase(existing, enterX(existing.cflNew), isExit = false))
          }
        }
      }
    }
    // Columns no longer present: a DYING lifecycle (exit only), frozen at their OLD-layout position.
    for ((k, s) in rollSlots) if (!newKeys.contains(k)) {
      val wasIdle = s.enterP >= 1f && s.exitChar.isEmpty() && !s.rolling
      s.hasNew = false; s.rolling = false
      if (s.enterChar.isNotEmpty()) { s.exitChar = s.enterChar; s.exitP = 0f; s.enterChar = "" }
      s.enterP = 1f
      s.direction = dir
      oldByKey[k]?.let { s.cflOld = it.centerFromLeft; s.hasOld = true }
      if (wasIdle) phases.add(Phase(s, exitX(s.cflOld), isExit = true))
    }

    // LEFT→RIGHT cascade over ALL phases (ink-timeline measurements, 9,999→1 @60fps):
    //  • EXITS are CONTIGUOUS — iOS spaces them ~2.25 frames apart regardless of interleaved
    //    arrivals, so they are indexed by their own ordinal (a global index made later exits
    //    start 2-4 frames late).
    //  • ENTERS keep the global positional index on a compressed stagger (×0.55): the iOS "1"
    //    starts ~0.12s in — while the overlapping old ink is still ~60% present, so the centre
    //    never goes empty (our deep "empty dip" came from entering too late).
    phases.sortBy { it.x }
    var exitOrdinal = 0
    var entersSeen = 0
    for ((i, ph) in phases.withIndex()) {
      when {
        // Exits are contiguous, but one that follows an arrival waits half a step extra — the
        // reference's "handoff": in 10→9 the "0" stays firm until the "9" is perceptible.
        ph.isExit -> ph.s.exitDelay = (exitOrdinal++ + entersSeen * 0.5f) * staggerSeconds
        ph.s.rolling -> ph.s.delay = i * rollStaggerSeconds
        else -> {
          ph.s.delay = enterLag + i * staggerSeconds * enterCascadeCompression + entersSeen * enterSpacingSeconds
          entersSeen++
        }
      }
    }

    // Two centred layouts: remember each layout's total width to place its origin.
    slotOldWidth = oldLayout.firstOrNull()?.totalWidth ?: slotMeasure(slotTargetText)
    slotNewWidth = newLayout.firstOrNull()?.totalWidth ?: slotMeasure(newFormatted)
    slotTargetText = newFormatted
  }

  private fun tickSlots(dt: Float) {
    val it = rollSlots.iterator()
    while (it.hasNext()) {
      val s = it.next().value
      if (s.rolling) {
        if (s.delay > 0f) { s.delay = (s.delay - dt).coerceAtLeast(0f) }
        else {
          // Overlapped roll on a shared spring (soft, continuous — the good digit roll).
          val (x, v) = TransitionLogic.springStep(s.rollP, s.rollV, 1f, springStiffness, springDampingRatio, dt)
          s.rollP = x; s.rollV = v
          if (x >= 1f && abs(v) < 0.02f) { s.rolling = false; s.rollP = 1f; s.rollV = 0f; s.exitChar = "" }
        }
      } else {
        // Lifecycle: exit and enter are independently gated so the left→right cascade can
        // interleave departures and arrivals (10→9: exit "1" → enter "9" → exit "0").
        if (s.exitDelay > 0f) s.exitDelay = (s.exitDelay - dt).coerceAtLeast(0f)
        else if (s.exitChar.isNotEmpty() && s.exitP < 1f) {
          s.exitP = (s.exitP + dt / exitDuration).coerceAtMost(1f)
          if (s.exitP >= 1f) s.exitChar = ""
        }
        if (s.delay > 0f) s.delay = (s.delay - dt).coerceAtLeast(0f)
        else if (s.enterP < 1f) s.enterP = (s.enterP + dt / enterDuration).coerceAtMost(1f)
      }
      // A column with nothing left to show and nothing arriving is done.
      if (s.enterChar.isEmpty() && s.exitChar.isEmpty()) it.remove()
    }
  }

  private fun slotsAtRest(): Boolean =
    rollSlots.values.all {
      it.delay <= 0f && it.exitDelay <= 0f && !it.rolling && it.exitChar.isEmpty() && it.enterP >= 1f
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
    layoutP = 0f

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

    if (layoutP < 1f) layoutP = (layoutP + dt / layoutReflowSeconds).coerceAtMost(1f)
    tickSlots(dt)

    // Settled: global spring at the goal AND slow, every column at rest, AND no value change in
    // the last ~160ms. The recency guard keeps the ticker alive during a rapid hold, so each
    // press re-bases and rolls continuously instead of settling and dead-starting between presses.
    val quiet = (now - lastValueChangeNanos) > 160_000_000L
    if (x >= 1f && quiet && slotsAtRest() && layoutP >= 1f && abs(x - 1f) < 0.002f && abs(v) < 0.02f) {
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
    layoutP = 0f

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
