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
  // MEASURED against the reference: an isolated single-digit roll (2,576 → 2,577) goes from onset
  // back to full darkness in 267 ms. A soft spring (150) took ~450-570 ms, which is why a preset
  // whose two values are 400 ms apart never showed its intermediate value as sharp, and why the
  // last arrival of a growth looked like it turned up long after the rest.
  //
  // A fast transition does NOT make a spam re-sharpen: presses land well inside 267 ms, so the
  // reversal catches the glyph mid-flight — which is exactly how the reference behaves.
  private val springStiffness: Float = 340f
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
  private val arriveDampingRatio: Float = 0.32f
  // Velocity that maps to full roll blur (position+velocity blend below). Scales with the spring:
  // peak presence velocity is ~5.0 at stiffness 150 but ~7.4 at 340, so keeping the old 9 here made
  // the velocity term 45% stronger than it was tuned to be. It then pulsed on every digit change —
  // read as a shimmer on a continuous roll — and could even re-blur a glyph that had arrived.
  private val blurVelocityRef: Float = 13f
  // Opacity drop at full blur. A Gaussian blur alone doesn't lighten a large glyph enough — the
  // reference's out-of-focus bolla is LIGHT grey, so opacity is coupled to the blur amount.
  // 0.35 washed the mid-transition out too much (user-confirmed: ghosts too pale + an "empty
  // breath" at the crossing that iOS never has — its grey mass stays present throughout).
  private val blurAlphaDrop: Float = 0.22f
  // Depth floor of a rolling glyph. 0.9 kept an arriving digit at essentially full size the whole
  // way, so nothing read as approaching from depth; the reference's barely-present glyphs measure
  // ~0.72 of their settled height, and presenceScale's convex falloff keeps the visible middle of
  // the roll near full size anyway.
  private val rollDepthMin: Float = 0.75f

  // Spacing between successive arrivals. Measured on the reference's 1 → 9,999 growth: consecutive
  // columns differ by ~0.2 of presence, which over a 267 ms transition is ~45 ms per arrival.
  private val enterSpacingSeconds: Float = 0.045f
  private val enterLag: Float = 0.04f

  // The horizontal reflow is a per-glyph spring, not a shared clock. A shared clock had to be
  // rewound on every retarget, which under a rapid spam restarted the reflow and made the
  // composition jerk sideways. Critically damped (no horizontal bounce) and soft enough that its
  // ~0.4s settle reproduces the late glide of the surviving "1" in 1→1.5 on its own.
  private val xStiffness: Float = 110f
  private val xDampingRatio: Float = 1f
  // LEFT→RIGHT cascade (reverse-engineered from the iOS reference at 60fps: on a multi-digit
  // change the leftmost changed column leads). Kept SUBTLE — a large delay turns the cascade into
  // a visibly sequential, machine-like wave.
  private val staggerSeconds: Float = 0.04f
  // Below this gap between two changes the exit cascade is off, and it fades in linearly up to
  // twice it. A hold on +/- repeats every 30 ms and the scripted burst every 45 ms — both must land
  // at zero — while the example's presets, which set two values 400 ms apart, must get the full
  // cascade the reference shows there.
  private val cascadeSpamMs: Float = 90f
  // How much faster than the presence spring a ROLL's departure fades. See pK below.
  private val rollExitFadeRate: Float = 1.3f
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
    var travelMul: Float = 1f      // roll = 1; a structural birth spawns much further out
    var minScale: Float = 0.9f
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
    /** The glyph this column is resolving toward, counting one still waiting out its stagger. */
    fun incoming(): GlyphState? = glyphs.lastOrNull { it.effectiveTarget >= 0.5f }
  }

  /** Upper bound on glyphs alive in one column; a fast roll legitimately keeps several in flight. */
  private val MAX_GLYPHS_PER_COLUMN = 6

  private val columns = LinkedHashMap<String, Column>()
  private var slotTargetText: String = "0"   // last scheduled target

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
  // Halved when the roll offset gained its own coordinate: a glyph now travels the FULL span
  // (arrival side -> baseline -> out the far side) instead of only half of it, so the same factor
  // doubled the distance covered. Measured on a continuous roll, the ink's vertical excursion had
  // gone to 56% of the glyph height against the reference's 18.8%. 0.12 undershot at 10.5%; 0.15
  // lands on the reference.
  private val travelFactor = 0.15f
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
  // Fitted to the reference growth: a structural birth bottoms out around 0.6 of full size.
  private val enterMinScale = 0.6f
  // Doubled alongside the halved travelFactor so a structural birth keeps the measured 0.48 of the
  // line height it spawns from in the reference.
  private val enterTravelFactor = 3.2f
  // Horizontal spawn displacement of a born glyph, as a fraction of its width: it appears
  // displaced toward the composition's growing edge (e.g. the trailing "5" from the right) and
  // slides to its slot. Direction derived from geometry — no hardcoding.
  private val enterSpawnXFactor = 0.15f
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

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val fm = textPaint.fontMetrics
    val textHeight = fm.descent - fm.ascent
    // Horizontal headroom matters as much as vertical: a dying glyph drifts outward and every
    // moving glyph carries a blur halo, so the ink reaches past the text's own advance and the
    // outermost digits were being clipped at the view's edge mid-transition.
    val hHeadroom = textHeight * blurFactor * 2f + overscanHorizontal
    val desiredWidth = if (animator != null && activePlan != null) {
      ceil(max(activePlan!!.oldWidth, activePlan!!.newWidth) + 2f * hHeadroom + paddingLeft + paddingRight).toInt()
    } else {
      val t = if (settledText.isNotEmpty()) settledText else "0"
      ceil(textPaint.measureText(t) + 2f * hHeadroom + paddingLeft + paddingRight).toInt()
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
        return
      }
      if (!settledLike && columns.isNotEmpty()) { drawSlots(canvas, null); return }
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
      if (isStill(g, scrub)) drawGlyph(canvas, g.ch, centreX + g.xRel, bl, textPaint)
    }

    // Pass 2 — everything in motion, each glyph a pure function of its own presence.
    for (c in columns.values) for (g in c.glyphs) {
      if (isStill(g, scrub)) continue
      val p = presenceOf(g)
      val pc = p.coerceIn(0f, 1f)
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
        max(
          TransitionLogic.presenceBlur(pc),
          (abs(g.v) / blurVelocityRef).coerceIn(0f, 1f) * 0.6f
        )
      }
      val alpha = (TransitionLogic.presenceAlpha(pc) * (1f - blurAlphaDrop * blurAmt) * 255f)
        .toInt().coerceIn(0, 255)
      if (alpha <= 0) continue
      // Unclamped p: the spring's overshoot carries the glyph slightly past its baseline.
      val yOff = travelPx * g.travelMul * TransitionLogic.rollOffsetShape(g.off)
      val xDrift = (if (g.xRelTarget >= 0f) 1f else -1f) * g.w * g.driftMul * (1f - pc)
      val scale = TransitionLogic.presenceScale(pc, g.minScale)
      val radius = maxBlurPx * blurAmt
      if (nodeCapable) {
        g.node = drawSlotGlyphNode(canvas, g.node, g.ch, centreX + g.xRel + xDrift, bl, yOff, scale, alpha, radius)
      } else {
        drawGlyphBlurred(canvas, g.ch, centreX + g.xRel + xDrift, bl + yOff, alpha, radius * 0.6f)
      }
    }
    textPaint.maskFilter = null; textPaint.alpha = 255
  }

  /** Settled at full presence, with nothing pending and no residual motion → draw it sharp. */
  private fun isStill(g: GlyphState, scrub: Float?): Boolean =
    scrub == null && g.target >= 1f && g.pendingTarget < 0f && g.delay <= 0f &&
      g.p >= 0.999f && abs(g.v) < 0.01f && abs(g.xRel - g.xRelTarget) < 0.3f &&
      // offV matters now that an arrival bounces: it crosses its target at full speed, and without
      // this it would be drawn sharp and unshifted for that one frame, then jump back out.
      abs(g.off) < 0.004f && abs(g.offV) < 0.02f

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
          minScale = rollDepthMin
        })
      }
    }
    slotTargetText = committed
  }

  // Diff the live columns against a new target and MOVE GOALS — never restart anything. A column
  // whose character changes retargets its current glyph to 0 and the incoming one to 1; if the
  // incoming character is still on screen as a fading glyph (the A→B→A of a preset spam), that very
  // glyph is reused and simply retargeted back to 1, so it springs back from wherever it is with
  // its velocity intact instead of being re-created at zero presence.
  private fun scheduleSlots(newFormatted: String, dir: Int) {
    val newLayout = TransitionLogic.layoutKeyedSlots(newFormatted, currentGroupSep, currentDecimalSep, currentMinusSign, slotMeasure)
    val newKeys = HashSet<String>(newLayout.size)

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
    val now = android.os.SystemClock.uptimeMillis()
    val sinceLastChange = (now - lastChangeUptimeMs).toFloat()
    lastChangeUptimeMs = now
    val restFraction = ((sinceLastChange - cascadeSpamMs) / cascadeSpamMs).coerceIn(0f, 1f)

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
        exitOff = dir * 2f
        travelMul = if (current == null && structural) enterTravelFactor else 1f
        minScale = if (current == null && structural) enterMinScale else rollDepthMin
        driftMul = if (current == null && structural) enterSpawnXFactor else 0f
        xRel = xRelOf(ks)
      }
      g.w = ks.width
      g.xRelTarget = xRelOf(ks)
      g.exitOff = dir * 2f                   // where it will go if it later leaves
      g.structuralExit = false               // it is arriving; a later death re-arms this
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
        // Two units, so the glyph keeps drifting clear instead of parking just off the baseline —
        // a fast roll needs departures to leave the frame, not pile up. The rate is what matters,
        // not the destination: see offStiffness below.
        other.exitOff = dir * 2f
        if (structural) {
          other.minScale = exitMinScale; other.driftMul = exitDriftOut
          // A structural death is the birth run backwards: it covers the same 1 unit (× the birth's
          // travel factor) that a born glyph spawns from, at the same spring rate. Measured on the
          // reference, a dying glyph is still visible after ~0.3 line-heights of roll; ours moved
          // 0.04 and was gone, which read as fading in place rather than rolling out.
          other.travelMul = enterTravelFactor
          other.exitOff = dir * exitTravelOfBirth
          other.structuralExit = true
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
        g.minScale = exitMinScale; g.driftMul = exitDriftOut
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
    for (ph in phases) {
      val columnIndex = colOrder.indexOf(ph.key)
      when {
        ph.isExit -> {
          ph.g.armDelay(restFraction * columnIndex * staggerSeconds)
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
        else -> {
          ph.g.armDelay(enterLag + entersSeen * enterSpacingSeconds)
          ph.g.aimAt(1f)
          entersSeen++
        }
      }
    }

    slotTargetText = newFormatted
  }

  private fun atRest(col: Column): Boolean = col.glyphs.all { isSettled(it) }

  private fun isSettled(g: GlyphState): Boolean =
    g.delay <= 0f && g.pendingTarget < 0f && abs(g.p - g.target) < 0.004f && abs(g.v) < 0.03f &&
      abs(g.off - g.offTarget) < 0.01f && abs(g.offV) < 0.05f &&
      abs(g.xRel - g.xRelTarget) < 0.35f && abs(g.xv) < 2f

  private fun tickSlots(dt: Float) {
    val colIt = columns.iterator()
    while (colIt.hasNext()) {
      val col = colIt.next().value
      val gIt = col.glyphs.iterator()
      while (gIt.hasNext()) {
        val g = gIt.next()
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
        val pK = when {
          g.target >= 0.5f -> springStiffness
          g.structuralExit -> springStiffness * deathRate * deathRate
          else -> springStiffness * rollExitFadeRate
        }
        val (p, v) = TransitionLogic.springIntegrate(g.p, g.v, g.target, pK, springDampingRatio, dt)
        g.p = p; g.v = v
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
          g.target >= 0.5f -> springStiffness
          // Same multiplier as its presence, so a death covers the same distance before it is gone.
          g.structuralExit -> springStiffness * deathRate * deathRate
          else -> springStiffness * 0.25f
        }
        // The settle bounce is the arrival's alone: a departure that rang would swing back toward
        // the baseline it is trying to leave.
        val offZ = if (g.target >= 0.5f) arriveDampingRatio else springDampingRatio
        val (o, ov) = TransitionLogic.springIntegrate(g.off, g.offV, g.offTarget, offK, offZ, dt)
        g.off = o; g.offV = ov
        val (x, xv) = TransitionLogic.springIntegrate(g.xRel, g.xv, g.xRelTarget, xStiffness, xDampingRatio, dt)
        g.xRel = x; g.xv = xv
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
      animationProgress = 1f; activePlan = null; columns.clear()
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
