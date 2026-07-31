package com.numerictext

import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class TokenKind {
  DIGIT, GROUP_SEPARATOR, DECIMAL_SEPARATOR, SIGN, OTHER
}

data class NumericToken(val text: String, val kind: TokenKind, val logicalIndex: Int)
data class GlyphSlot(val oldToken: NumericToken?, val newToken: NumericToken?, val oldX: Float, val newX: Float, val oldWidth: Float, val newWidth: Float, val changed: Boolean)
data class TransitionPlan(val oldFormatted: String, val newFormatted: String, val slots: List<GlyphSlot>, val oldWidth: Float, val newWidth: Float)

/**
 * A single glyph position of a formatted number, tagged with a **stable key** that is
 * anchored to logical position (units, tens, …) rather than array index, so per-slot spring
 * state survives across value changes and length changes (the roadmap Part A requirement).
 *
 * Key convention:
 *  - `I{n}`  integer digit, n = number of integer digits to its right (I0 = units, I3 = thousands)
 *  - `F{n}`  fractional digit, n = position after the decimal separator (F0 = tenths)
 *  - `G{n}`  group separator, n = integer digits to its right (stable across 999→1,000)
 *  - `DEC`   decimal separator, `S` sign, `O{i}` anything else
 *
 * `centerFromLeft` is the glyph centre measured from the string's left edge; `distFromRight`
 * (= totalWidth − centerFromLeft) is what the renderer uses to right-anchor columns so a digit
 * that keeps its logical position never wobbles horizontally when the number's width changes.
 */
data class KeyedSlot(
  val key: String,
  val kind: TokenKind,
  val char: String,
  val centerFromLeft: Float,
  val width: Float,
  val totalWidth: Float
) {
  val distFromRight: Float get() = totalWidth - centerFromLeft
}

enum class TransitionStrategy { WHOLE_RUN, CHANGED_RUN, PER_GLYPH }

data class LayerPlan(
  val oldValue: Double,
  val newValue: Double,
  val oldFormatted: String,
  val newFormatted: String,
  val oldWidth: Float,
  val newWidth: Float,
  val commonPrefixUtf16End: Int,
  val oldChangedUtf16Start: Int,
  val oldChangedUtf16End: Int,
  val newChangedUtf16Start: Int,
  val newChangedUtf16End: Int,
  val oldSuffixUtf16Start: Int,
  val newSuffixUtf16Start: Int,
  val oldPrefixAdvance: Float,
  val oldChangedAdvance: Float,
  val oldSuffixAdvance: Float,
  val newPrefixAdvance: Float,
  val newChangedAdvance: Float,
  val newSuffixAdvance: Float,
  val strategy: TransitionStrategy
)

object TransitionLogic {
  // ── Formatting ──

  fun formatNumber(value: Double, locale: Locale, useGrouping: Boolean, minFractionDigits: Int, maxFractionDigits: Int): String {
    val fmt = NumberFormat.getNumberInstance(locale)
    fmt.isGroupingUsed = useGrouping
    fmt.minimumFractionDigits = minFractionDigits
    fmt.maximumFractionDigits = maxFractionDigits
    return fmt.format(value)
  }

  fun getDecimalFormatSymbols(locale: Locale): Triple<Char, Char, Char> {
    val symbols = DecimalFormatSymbols.getInstance(locale)
    return Triple(symbols.groupingSeparator, symbols.decimalSeparator, symbols.minusSign)
  }

  // ── Code-point-aware common prefix/suffix ──

  fun computeCommonPrefix(a: String, b: String): Int {
    val minLen = minOf(a.length, b.length)
    var ai = 0; var bi = 0
    while (ai < minLen && bi < minLen) {
      val cpA = a.codePointAt(ai); val cpB = b.codePointAt(bi)
      if (cpA != cpB) return ai
      val cl = Character.charCount(cpA); ai += cl; bi += cl
    }
    return ai
  }

  fun computeCommonSuffix(a: String, b: String, aPrefixEnd: Int): Int {
    var ai = a.length - 1; var bi = b.length - 1
    while (ai >= aPrefixEnd && bi >= 0) {
      val cpA = a.codePointBefore(ai + 1); val cpB = b.codePointBefore(bi + 1)
      if (cpA != cpB) break
      val cl = Character.charCount(cpA); ai -= cl; bi -= cl
    }
    return a.length - 1 - ai
  }

  // ── LayerPlan builder ──

  fun buildLayerPlan(
    oldValue: Double, newValue: Double,
    oldFormatted: String, newFormatted: String,
    measureAdvance: (String, Int, Int) -> Float
  ): LayerPlan {
    val prefixEnd = computeCommonPrefix(oldFormatted, newFormatted)
    val suffixLen = computeCommonSuffix(oldFormatted, newFormatted, prefixEnd)

    val oldChangedStart = prefixEnd
    val oldChangedEnd = oldFormatted.length - suffixLen
    val newChangedStart = prefixEnd
    val newChangedEnd = newFormatted.length - suffixLen
    val oldSuffixStart = oldChangedEnd
    val newSuffixStart = newChangedEnd

    val oldPrefixAdv = if (oldChangedStart > 0) measureAdvance(oldFormatted, 0, oldChangedStart) else 0f
    val oldChangedAdv = if (oldChangedEnd > oldChangedStart) measureAdvance(oldFormatted, oldChangedStart, oldChangedEnd) else 0f
    val oldSuffixAdv = if (oldFormatted.length > oldSuffixStart) measureAdvance(oldFormatted, oldSuffixStart, oldFormatted.length) else 0f
    val newPrefixAdv = if (newChangedStart > 0) measureAdvance(newFormatted, 0, newChangedStart) else 0f
    val newChangedAdv = if (newChangedEnd > newChangedStart) measureAdvance(newFormatted, newChangedStart, newChangedEnd) else 0f
    val newSuffixAdv = if (newFormatted.length > newSuffixStart) measureAdvance(newFormatted, newSuffixStart, newFormatted.length) else 0f

    return LayerPlan(
      oldValue = oldValue, newValue = newValue,
      oldFormatted = oldFormatted, newFormatted = newFormatted,
      oldWidth = oldPrefixAdv + oldChangedAdv + oldSuffixAdv,
      newWidth = newPrefixAdv + newChangedAdv + newSuffixAdv,
      commonPrefixUtf16End = prefixEnd,
      oldChangedUtf16Start = oldChangedStart, oldChangedUtf16End = oldChangedEnd,
      newChangedUtf16Start = newChangedStart, newChangedUtf16End = newChangedEnd,
      oldSuffixUtf16Start = oldSuffixStart, newSuffixUtf16Start = newSuffixStart,
      oldPrefixAdvance = oldPrefixAdv, oldChangedAdvance = oldChangedAdv, oldSuffixAdvance = oldSuffixAdv,
      newPrefixAdvance = newPrefixAdv, newChangedAdvance = newChangedAdv, newSuffixAdvance = newSuffixAdv,
      strategy = TransitionStrategy.CHANGED_RUN
    )
  }

  fun buildWholeRunPlan(oldValue: Double, newValue: Double, oldFormatted: String, newFormatted: String, measureAdvance: (String, Int, Int) -> Float): LayerPlan {
    val oldW = measureAdvance(oldFormatted, 0, oldFormatted.length)
    val newW = measureAdvance(newFormatted, 0, newFormatted.length)
    return LayerPlan(
      oldValue = oldValue, newValue = newValue,
      oldFormatted = oldFormatted, newFormatted = newFormatted,
      oldWidth = oldW, newWidth = newW,
      commonPrefixUtf16End = 0,
      oldChangedUtf16Start = 0, oldChangedUtf16End = oldFormatted.length,
      newChangedUtf16Start = 0, newChangedUtf16End = newFormatted.length,
      oldSuffixUtf16Start = oldFormatted.length, newSuffixUtf16Start = newFormatted.length,
      oldPrefixAdvance = 0f, oldChangedAdvance = oldW, oldSuffixAdvance = 0f,
      newPrefixAdvance = 0f, newChangedAdvance = newW, newSuffixAdvance = 0f,
      strategy = TransitionStrategy.WHOLE_RUN
    )
  }

  // ── Smoothstep / remap ──

  fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
  }

  fun remap(value: Float, low1: Float, high1: Float, low2: Float, high2: Float): Float {
    return low2 + (value - low1) * (high2 - low2) / (high1 - low1)
  }

  // ── Central curve envelopes (all map linear 0‑1 progress → property 0‑1) ──

  // Smootherstep (Ken Perlin): ease-in-out with zero 1st/2nd derivative at the ends.
  fun smootherstep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * t * (t * (t * 6f - 15f) + 10f)
  }

  // Identity: the easing now comes from the spring driver (see springStep), which also
  // lets the value overshoot past 1 → the new glyph springs slightly past its resting
  // baseline and settles back (the underdamped "bounce"). Old/new share it → rigid strip.
  fun oldMotion(progress: Float): Float = progress
  fun newMotion(progress: Float): Float = progress

  // Semi-implicit Euler step of a unit-mass spring toward `goal`. Returns (value, velocity).
  // dampingRatio < 1 → underdamped (overshoot + settle); ~1 → critically damped.
  // Velocity is preserved across retargets by feeding it back in — that is what gives the
  // continuous, inertia-carrying feel on rapid updates.
  fun springStep(
    value: Float, velocity: Float, goal: Float,
    stiffness: Float, dampingRatio: Float, dt: Float
  ): Pair<Float, Float> {
    val damping = 2f * dampingRatio * sqrt(stiffness)
    val accel = -stiffness * (value - goal) - damping * velocity
    val v = velocity + accel * dt
    val x = value + v * dt
    return Pair(x, v)
  }

  /** Longest integration step before a stiff spring starts to visibly judder (~240 Hz). */
  const val SPRING_SUB_STEP = 1f / 240f

  /**
   * Integrate the spring over [dt] in fixed sub-steps.
   *
   * Semi-implicit Euler's error grows with stiffness × dt, so a stiff spring driven straight from
   * the frame delta tracks the frame pacing rather than the clock: on an emulator, where frame
   * times are irregular, that showed up as a continuous roll trembling instead of gliding
   * (measured: 77% of frames reversed the ink's vertical direction against the reference's 19%).
   * Sub-stepping decouples the motion from frame jitter without changing the spring itself.
   */
  fun springIntegrate(
    value: Float, velocity: Float, goal: Float,
    stiffness: Float, dampingRatio: Float, dt: Float
  ): Pair<Float, Float> {
    val out = FloatArray(2)
    springIntegrateInto(value, velocity, goal, stiffness, dampingRatio, dt, out)
    return Pair(out[0], out[1])
  }

  /**
   * [springIntegrate] writing into [out] (`[value, velocity]`) instead of returning a pair.
   *
   * The renderer integrates three springs per glyph per frame, each in 240 Hz sub-steps — seven of
   * them in a 30 ms frame. Returning `Pair<Float, Float>` allocates the pair *and* boxes both
   * floats, so the pair-returning form cost ~70 short-lived objects per glyph per frame and grew
   * linearly with the digit count. This form allocates nothing, and hoists the damping term's
   * square root out of the sub-step loop since none of its inputs vary within a call.
   */
  fun springIntegrateInto(
    value: Float, velocity: Float, goal: Float,
    stiffness: Float, dampingRatio: Float, dt: Float, out: FloatArray
  ) {
    val damping = 2f * dampingRatio * sqrt(stiffness)
    var x = value
    var v = velocity
    var remaining = dt
    while (remaining > 0f) {
      val step = min(remaining, SPRING_SUB_STEP)
      val accel = -stiffness * (x - goal) - damping * v
      v += accel * step
      x += v * step
      remaining -= step
    }
    out[0] = x
    out[1] = v
  }

  // Complementary crossfade: both glyphs ~0.5 at the midpoint → heavy overlap so the
  // blurred old + blurred new read as a single soft smear.
  fun oldOpacity(progress: Float): Float = 1f - smootherstep(progress)
  fun newOpacity(progress: Float): Float = smootherstep(progress)
  fun layoutInterpolation(progress: Float): Float = smootherstep(progress)

  // Smooth bell: 0 at both ends, 1 at the midpoint. Drives blur radius — the dominant,
  // "buttery" part of the effect. Present across most of the transition.
  fun blurEnvelope(progress: Float): Float = sin(PI * progress.coerceIn(0f, 1f)).toFloat()

  // Asymmetric bell for the ROLL: peaks early and DECAYS SLOWLY — the reference's roll keeps a
  // soft edge for a long tail (~16-21 frames of recovery in the ink timelines vs our former ~10).
  fun rollBlurEnvelope(progress: Float): Float {
    val t = progress.coerceIn(0f, 1f).toDouble()
    return sin(PI * Math.pow(t, 0.72)).toFloat()
  }

  fun newBlurEnvelope(progress: Float): Float = blurEnvelope(progress)

  fun scaleEnvelope(progress: Float, minScale: Float = 0.92f): Float {
    val dist = abs(progress - 0.5f) * 2f
    return 1f - (1f - minScale) * (1f - smoothstep(0f, 1f, dist))
  }

  fun verticalMaskEnvelope(progress: Float): Float {
    return smoothstep(0f, 0.75f, 1f - abs(progress - 0.5f) * 2f)
  }

  // ── Per-glyph enter/exit lifecycle curves ──
  //
  // The reference does NOT crossfade two glyphs inside a slot on one shared progress. Every glyph
  // owns an independent lifecycle: the OUTGOING one degrades immediately (softness leads, then it
  // shrinks and drifts out of the way, extinguishing with a gentle tail), while the INCOMING one
  // appears near its final position and simply comes into focus — it barely travels.
  //
  // Each curve maps its own normalised progress (0→1) to one visual property, so onset and
  // termination can be tuned per property instead of being welded to a single spring.

  /** Gentle overshoot easing — gives the arriving glyph a small settle in the roll's direction. */
  fun easeOutBack(x: Float, overshoot: Float = 0.9f): Float {
    val t = x.coerceIn(0f, 1f) - 1f
    return 1f + (overshoot + 1f) * t * t * t + overshoot * t * t
  }

  fun easeOut(x: Float): Float { val t = x.coerceIn(0f, 1f); val k = 1f - t; return 1f - k * k }

  // Outgoing glyph. Alpha drops fast at first, then extinguishes with a soft tail (a hard cut
  // reads as the old value "popping"; a linear fade reads as it lingering).
  fun exitAlpha(e: Float): Float {
    val k = 1f - e.coerceIn(0f, 1f)
    return k * k * sqrt(k.coerceAtLeast(0f))
  }
  /** Front-loaded: the outgoing glyph is soft almost immediately, before it has visibly moved. */
  fun exitBlur(e: Float): Float = smoothstep(0f, 0.22f, e)
  fun exitScale(e: Float, minScale: Float = 0.82f): Float = 1f + (minScale - 1f) * easeOut(e)
  /** Fraction of the exit travel already covered (it accelerates away). */
  fun exitOffsetFraction(e: Float): Float = easeOut(e)

  // Incoming glyph. Appears slightly after the exit begins and resolves by sharpening in place.
  fun enterAlpha(n: Float): Float = smoothstep(0.06f, 0.62f, n)
  fun enterBlur(n: Float): Float = 1f - smoothstep(0.10f, 0.80f, n)
  fun enterScale(n: Float, minScale: Float = 0.90f): Float =
    minScale + (1f - minScale) * easeOutBack(n)
  /** Fraction of the (short) entry travel still to cover; slight overshoot gives the settle. */
  fun enterOffsetFraction(n: Float): Float = 1f - easeOutBack(n)

  // ── Continuous presence model (default PER_GLYPH renderer) ──
  //
  // The lifecycle curves above map a *phase timer* to a property, so the only way to interrupt one
  // is to restart it — which is why a rapid preset spam read as a series of flat, complete
  // changes. The reference does something else: every glyph carries a continuous PRESENCE
  // p ∈ [0,1] (0 = fully absent, 1 = settled and sharp) driven by a spring toward 0 or 1, and every
  // visual property is a pure function of the CURRENT p. Retargeting only moves the goal, so a
  // glyph caught at p = 0.4 on its way out springs back from 0.4 carrying its velocity — the
  // "back" the reference shows, and the reason its spam never re-sharpens: presses arrive faster
  // than the spring settles, so p hovers mid-range and the number stays a soft grey mass.
  //
  // p is deliberately NOT clamped by the caller before the offset is taken: the spring overshoots
  // past 1 and the glyph rides slightly past its resting baseline before settling back.

  /**
   * Presence of a glyph placed on a continuous roll tape.
   *
   * Non-target lanes use a triangular window: a glyph is whole on the baseline and contributes no
   * ink one lane away in either direction. The target lane is asymmetric in the direction of
   * arrival, so a small spring overshoot beyond its baseline stays fully present instead of
   * flickering. Position, velocity and presence therefore remain functions of one persistent phase;
   * a retarget cannot restart or teleport any glyph already on screen.
   */
  fun rollTapePresence(offset: Float, isTarget: Boolean, direction: Int): Float {
    val d = if (direction < 0) -1f else 1f
    return if (isTarget) {
      (1f + d * offset).coerceIn(0f, 1f)
    } else {
      (1f - abs(offset)).coerceIn(0f, 1f)
    }
  }

  /**
   * Whether a matching old lane is a valid immediate reversal target.
   *
   * Only a lane physically waiting on the requested arrival side may be reused. Without the signed
   * side check, encountering the same digit later in a monotonic count would pull an old lane
   * backwards through the strip.
   */
  fun rollTapeCanReuseLane(
    lane: Float,
    phase: Float,
    direction: Int,
    maxDistance: Float
  ): Boolean {
    val signedDistance = (lane - phase) * if (direction < 0) -1f else 1f
    return signedDistance > 0f && signedDistance <= maxDistance
  }

  /**
   * Visual phase used by a glyph born during a topology change.
   *
   * SwiftUI's numeric transition resolves the incoming ink before its underlying spring has
   * mathematically settled. Keeping geometry on the real spring while finishing focus/density at
   * 82% reproduces that controlled handoff without shortening or restarting the motion itself.
   */
  fun structuralArrivalVisualPresence(p: Float): Float =
    smoothstep(0f, 0.82f, p.coerceIn(0f, 1f))

  /**
   * Opacity — an S with a FLAT bottom and a LIVE top.
   *
   * It began as `p^e` (sub-linear, on a belief that a crossing pair must sum to more than one glyph
   * of ink) and then became plain smoothstep, when the per-glyph fit showed the opposite: the
   * reference's summed ink dips at the swap, and what it really does is hold the outgoing glyph
   * whole and then drop it. Smoothstep gave that, but with one artefact — its tangent at p = 1 is
   * zero, so a departing glyph LOITERS at full opacity for ~30 ms before it starts to go anywhere.
   * Measured, every column's half-gone instant sat ~30 ms behind the reference's however the
   * springs were tuned, and the column's ink floor ran high (0.66 against 0.52) because both glyphs
   * were being held near their extremes for too long.
   *
   * So: a cubic Hermite with h(0)=0, h(1)=1, h'(0)=0 and h'(1)=[TOP_SLOPE].
   *
   *     h(p) = (3p² − 2p³) + m·(p³ − p²)
   *
   * The flat bottom is kept — that is what stops an arriving glyph lighting up before it has
   * travelled, and it is measured: the reference's incoming ink is still at 0.01 of its settled
   * value 183 ms in. The top is given a real slope, so a departure begins to shed the moment it is
   * released, which is what the reference does (its leftmost column is already down to 0.76 by
   * +33 ms, with no plateau at all).
   */
  fun presenceAlpha(p: Float): Float {
    val c = p.coerceIn(0f, 1f)
    val c2 = c * c
    val c3 = c2 * c
    return (3f * c2 - 2f * c3) + TOP_SLOPE * (c3 - c2)
  }

  // A gate that faded the barely-present glyph toward nothing was tried here on 2026-07-30 and
  // measured wrong. The idea was that a long travel needs a near-invisible far glyph to keep the
  // column's centre of mass from swinging — but the reference does not do that. Its arriving glyph,
  // a full glyph-height above the baseline, is SOLID: the height its ink reaches is 1.02 glyph
  // heights whether ink is counted at 30%, 20%, 12% or 6% darkness, so there is no faint gradient up
  // there, there is a digit. (Ours, with the gate, read 0.11 / 0.37 / 0.70 / 0.23 at those same
  // thresholds — a fade, which is exactly what the gate produced.) The centroid needs no protecting
  // either: the reference's own excursion on an isolated roll measures 0.554 glyph heights against
  // our 0.043, so the swing is something we are missing, not something to suppress.

  /**
   * Slope of [presenceAlpha] at full presence. 0 is plain smoothstep; 1 makes the curve
   * `2p² − p³`, whose slope at the top equals its average.
   *
   * This trades two measured quantities against each other. Raising it moves every departure
   * earlier — worth ~18 ms of the ~30 ms lag at m = 1 — and lowers the ink both glyphs carry mid
   * -crossing, which is wanted while the column's floor reads above the reference. Raising it too
   * far empties the crossing, which is the failure the original sub-linear curve was invented to
   * avoid. Set from the floor: the reference sits at ~0.52 and we were at 0.66.
   */
  private const val TOP_SLOPE = 0.8f

  /**
   * Softness — linear in the presence deficit.
   *
   * A sub-linear exponent (0.5) gave a very long tail: with a large font the peak blur radius is
   * tens of pixels, so even a residual (1−p) of 0.001 still read as soft, and a transition never
   * looked like it had finished. Linear resolved cleanly but landed too sharp: fitted per column on
   * 1 -> 9,999, the reference's arriving glyphs peak at 0.12-0.16 of a line height of blur, ours at
   * 0.05-0.12, and the later a column arrived the sharper it turned up. 0.75 lifts the middle of the
   * curve (0.59 against 0.50 at p = 0.5, 0.41 against 0.30 at p = 0.7) while still reaching zero at
   * p = 1, so a glyph resolves at the same moment but is softer on the way in.
   */
  @JvmOverloads
  fun presenceBlur(p: Float, softness: Float = 1f): Float {
    val c = p.coerceIn(0f, 1f)
    val a = 1f - c
    // Was LINEAR in the presence deficit, so a glyph began to soften the instant it began to fade.
    // Measured against the reference, that is backwards in time: sampling each outgoing glyph's σ
    // at the moment it still holds 0.8 of its ink and again at 0.5,
    //
    //     iOS    0.03  ->  0.07      (crisp while it is whole, soft once it is going)
    //     linear 0.05  ->  0.05      (already smeared while whole, never softer than that)
    //
    // …which is why our departing ghost reads as a smudge and the reference's reads as a digit
    // leaving. The peak was never the problem — ours measured at or below the reference's — the
    // ORDER was. A smoothstep with a dead zone at the top holds the glyph sharp until it has
    // actually started to go, then softens harder over the middle, ending at the same short tail.
    //
    // `softness` is 1 for a change that stands alone and falls toward 0 as they crowd. A glyph in a
    // continuous roll never gets near full presence, so it never leaves the soft part of the curve
    // and the whole roll reads as a smear; the same curve is exactly right for a single arrival.
    // The exponent scales with it, and it does not touch WHERE any glyph is — which is why this
    // sharpens a fast roll without moving the centre the fade rate governs.
    val onset = BLUR_ONSET * softness
    val bumped = smoothstep(onset, BLUR_FULL, a)
    val exp = 1f + (1f - softness) * BLUR_SPAM_FALLOFF
    return if (exp <= 1.001f) bumped else Math.pow(bumped.toDouble(), exp.toDouble()).toFloat()
  }

  /** How much faster softness falls with presence when changes crowd. See [presenceBlur]. */
  private const val BLUR_SPAM_FALLOFF = 0.4f

  /**
   * How much of a glyph's ink must be gone before it starts to soften, and where it is fully soft.
   *
   * The dead zone at the top is the whole point: it is what keeps a departing digit crisp while it
   * is still a digit. `BLUR_ONSET` is scaled by `softness` so a crowded roll — where no glyph ever
   * gets near full presence — loses the dead zone and blurs as it always did.
   */
  private const val BLUR_ONSET = 0.10f
  private const val BLUR_FULL = 0.62f

  /**
   * Softness of a DYING glyph — deliberately lagged, and with no velocity term.
   *
   * The reference removes the glyphs of a shrinking composition one at a time and each stays crisp
   * while it thins: on 1,000 -> 1 its peak ink is still 100% with total mass at 79%, and 1.8x the
   * mass by the time three quarters have gone. A linear softness (plus the roll's velocity term,
   * which peaks the instant a departure is released) blurred them all together instead, at a
   * peak/mass ratio of ~1.05 the whole way — the grey smear that a big shrink read as.
   */
  fun deathBlur(p: Float): Float =
    Math.pow((1f - p.coerceIn(0f, 1f)).toDouble(), 1.6).toFloat()

  /**
   * Depth: an absent glyph is small and grows into place as it resolves.
   *
   * The default exponent (2.2) is fitted to the iOS reference's 1→9,999 STRUCTURAL growth, where a
   * glyph's ink height against its own settled height measured 0.97 / 0.96 / 0.94 / 0.87 / 0.72 at
   * presences of roughly 0.78 / 0.65 / 0.58 / 0.33 / 0.17 — it stays near full size for most of its
   * arrival and only the barely-present ones read as small.
   *
   * That fit is for a BIRTH, not a plain roll, and using it for both was never checked until a frame
   * grid was compared column by column: at exponent 2.2 the curve's slope at p = 0 is
   * (1 − minScale) × 2.2, so a rolling glyph is already at 0.80 of settled height by p = 0.1 and
   * 0.89 by p = 0.3 — a digit that looks essentially arrived a tenth of the way into a transition
   * most of us would call "just starting". A LOWER exponent inverts the shape: it stays close to
   * minScale for most of p and only rises steeply as p → 1, which is what "the new digit arrives
   * from above, small, and only reaches full size as it lands" looks like. Set by inspecting a
   * frame grid against the reference rather than by a fit — an isolated roll's two glyphs overlap
   * too much in a short travel for either one's ink to be isolated and measured directly.
   */
  @JvmOverloads
  fun presenceScale(p: Float, minScale: Float, exponent: Float = 2.2f): Float {
    val a = 1f - p.coerceIn(0f, 1f)
    return 1f - (1f - minScale) * Math.pow(a.toDouble(), exponent.toDouble()).toFloat()
  }

  /**
   * Residual displacement along the roll axis, as a fraction of the travel: full at p = 0, zero at
   * p = 1, and NEGATIVE past 1 — that is the settle bounce, which is why `p` is taken unclamped and
   * why the power below preserves sign.
   *
   * The exponent is fitted to the same reference frames, where a glyph's rise above its settled
   * centre measured 0.08 / 0.17 / 0.20 / 0.38 / 0.53 glyph-heights at those same presences: the
   * arriving glyph hangs high early and then drops into place late, rather than sliding linearly.
   */
  fun presenceOffsetFraction(p: Float): Float {
    val a = 1f - p
    val m = Math.pow(abs(a).toDouble(), 1.43).toFloat()
    return if (a < 0f) -m else m
  }

  /**
   * Shapes a glyph's position along the roll axis (−1 = waiting on the arrival side, 0 = at the
   * baseline, +1 = gone out the far side) into the fraction of the travel actually drawn.
   *
   * Same convex exponent fitted to the reference's growth frames: a glyph hangs out near the end of
   * its travel and then covers the last stretch to the baseline quickly. Sign-preserving, so it
   * stays continuous as a glyph passes through the baseline and carries on out the other side —
   * which is what makes a continuous roll read as rotation rather than as a bounce.
   */
  fun rollOffsetShape(off: Float): Float {
    val m = Math.pow(abs(off).toDouble(), 1.43).toFloat()
    return if (off < 0f) -m else m
  }

  // ── Trajectory helpers ──

  fun computeOldOffset(direction: Int, travel: Float, progress: Float): Float {
    return -direction * travel * oldMotion(progress)
  }

  fun computeNewOffset(direction: Int, travel: Float, progress: Float): Float {
    return direction * travel * (1f - newMotion(progress))
  }

  // ── PER_GLYPH helpers (preserved) ──

  fun tokenize(text: String, groupSep: Char, decimalSep: Char, minusSign: Char): List<NumericToken> {
    val tokens = mutableListOf<NumericToken>()
    var logicalIdx = 0; var idx = 0
    while (idx < text.length) {
      val codePoint = text.codePointAt(idx)
      val charCount = Character.charCount(codePoint)
      val ch = text.substring(idx, idx + charCount)
      val kind = when {
        Character.isDigit(codePoint) -> TokenKind.DIGIT
        ch.length == 1 && ch[0] == decimalSep -> TokenKind.DECIMAL_SEPARATOR
        ch.length == 1 && ch[0] == groupSep -> TokenKind.GROUP_SEPARATOR
        ch.length == 1 && ch[0] == minusSign -> TokenKind.SIGN
        else -> TokenKind.OTHER
      }
      tokens.add(NumericToken(text = ch, kind = kind, logicalIndex = logicalIdx))
      logicalIdx++; idx += charCount
    }
    return tokens
  }

  fun buildCompoundSlots(oldTokens: List<NumericToken>, newTokens: List<NumericToken>): List<Triple<Int, Int, Boolean>> {
    val od = oldTokens.indexOfFirst { it.kind == TokenKind.DECIMAL_SEPARATOR }
    val nd = newTokens.indexOfFirst { it.kind == TokenKind.DECIMAL_SEPARATOR }
    val oie = if (od >= 0) od else oldTokens.size
    val nie = if (nd >= 0) nd else newTokens.size
    val oit = oldTokens.subList(0, oie); val nit = newTokens.subList(0, nie)
    val intSlots = buildIntegerSlots(oit, nit, oldTokens, newTokens)
    val decSlots = mutableListOf<Triple<Int, Int, Boolean>>()
    if (od >= 0 || nd >= 0) { val c = od >= 0 && nd >= 0 && oldTokens[od].text != newTokens[nd].text; decSlots.add(Triple(od, nd, c)) }
    val oft = if (od >= 0) oldTokens.subList(od + 1, oldTokens.size) else emptyList()
    val nft = if (nd >= 0) newTokens.subList(nd + 1, newTokens.size) else emptyList()
    return intSlots + decSlots + buildFractionalSlots(oft, nft, oldTokens, newTokens)
  }

  private fun buildIntegerSlots(oi: List<NumericToken>, ni: List<NumericToken>, ot: List<NumericToken>, nt: List<NumericToken>): List<Triple<Int, Int, Boolean>> {
    val od = oi.filter { it.kind == TokenKind.DIGIT }; val nd = ni.filter { it.kind == TokenKind.DIGIT }
    val oR = od.reversed(); val nR = nd.reversed(); val mL = maxOf(oR.size, nR.size)
    val dp = mutableListOf<Pair<Int, Int>>()
    for (i in 0 until mL) { val oD = oR.getOrNull(i); val nD = nR.getOrNull(i); dp.add(0, Pair(if (oD != null) ot.indexOf(oD) else -1, if (nD != null) nt.indexOf(nD) else -1)) }
    val slots = mutableListOf<Triple<Int, Int, Boolean>>()
    var loi = -1; var lni = -1
    for ((odi, ndi) in dp) {
      insSep(ot, nt, oi, ni, loi, odi, lni, ndi, slots)
      slots.add(Triple(odi, ndi, odi >= 0 && ndi >= 0 && ot[odi].text != nt[ndi].text))
      if (odi >= 0) loi = odi; if (ndi >= 0) lni = ndi
    }
    insSep(ot, nt, oi, ni, loi, Int.MAX_VALUE, lni, Int.MAX_VALUE, slots)
    return slots
  }

  private fun insSep(ot: List<NumericToken>, nt: List<NumericToken>, oi: List<NumericToken>, ni: List<NumericToken>, aoi: Int, boi: Int, ani: Int, bni: Int, slots: MutableList<Triple<Int, Int, Boolean>>) {
    val os = oi.map { ot.indexOf(it) }.filter { it > aoi && it < boi && it >= 0 && ot[it].kind != TokenKind.DIGIT }.sorted()
    val ns = ni.map { nt.indexOf(it) }.filter { it > ani && it < bni && it >= 0 && nt[it].kind != TokenKind.DIGIT }.sorted()
    val un = BooleanArray(ns.size) { false }
    for (osi in os) { val osv = ot[osi]; var mni = -1; for (j in ns.indices) { if (!un[j] && nt[ns[j]].kind == osv.kind && nt[ns[j]].text == osv.text) { mni = ns[j]; un[j] = true; break } }; slots.add(Triple(osi, mni, mni < 0)) }
    for (j in ns.indices) { if (!un[j]) slots.add(Triple(-1, ns[j], true)) }
  }

  private fun buildFractionalSlots(of: List<NumericToken>, nf: List<NumericToken>, ot: List<NumericToken>, nt: List<NumericToken>): List<Triple<Int, Int, Boolean>> {
    val ml = maxOf(of.size, nf.size); val slots = mutableListOf<Triple<Int, Int, Boolean>>()
    for (i in 0 until ml) { val oT = of.getOrNull(i); val nT = nf.getOrNull(i); val oi = if (oT != null) ot.indexOf(oT) else -1; val ni = if (nT != null) nt.indexOf(nT) else -1; slots.add(Triple(oi, ni, oi >= 0 && ni >= 0 && ot[oi].text != nt[ni].text)) }
    return slots
  }

  /**
   * Lay out a formatted number into keyed glyph slots (see [KeyedSlot]). Pure and testable —
   * the renderer's per-slot spring scheduler diffs two of these lists to decide which columns
   * roll, which are born, and which die.
   */
  fun layoutKeyedSlots(
    formatted: String,
    groupSep: Char, decimalSep: Char, minusSign: Char,
    measure: (String) -> Float
  ): List<KeyedSlot> {
    val tokens = tokenize(formatted, groupSep, decimalSep, minusSign)
    val widths = tokens.map { measure(it.text) }
    val total = widths.sum()
    val decIdx = tokens.indexOfFirst { it.kind == TokenKind.DECIMAL_SEPARATOR }
    val intEnd = if (decIdx >= 0) decIdx else tokens.size

    // For every token index, how many integer digits sit to its right (units digit → 0).
    val intDigitsToRight = IntArray(tokens.size)
    var c = 0
    for (i in intEnd - 1 downTo 0) {
      intDigitsToRight[i] = c
      if (tokens[i].kind == TokenKind.DIGIT) c++
    }

    val result = ArrayList<KeyedSlot>(tokens.size)
    var cum = 0f
    var fracPos = 0
    for (i in tokens.indices) {
      val t = tokens[i]; val w = widths[i]; val center = cum + w / 2f
      val key = when (t.kind) {
        TokenKind.DIGIT -> if (decIdx >= 0 && i > decIdx) "F${fracPos++}" else "I${intDigitsToRight[i]}"
        TokenKind.GROUP_SEPARATOR -> "G${intDigitsToRight[i]}"
        TokenKind.DECIMAL_SEPARATOR -> "DEC"
        TokenKind.SIGN -> "S"
        else -> "O$i"
      }
      result.add(KeyedSlot(key, t.kind, t.text, center, w, total))
      cum += w
    }
    return result
  }

  fun buildPerGlyphPlan(
    oldFormatted: String, newFormatted: String,
    groupSep: Char, decimalSep: Char, minusSign: Char,
    measureWidth: (String) -> Float
  ): TransitionPlan {
    val ot = tokenize(oldFormatted, groupSep, decimalSep, minusSign)
    val nt = tokenize(newFormatted, groupSep, decimalSep, minusSign)
    val ow = ot.map { measureWidth(it.text) }; val nw = nt.map { measureWidth(it.text) }
    return TransitionPlan(oldFormatted, newFormatted,
      buildCompoundSlots(ot, nt).mapIndexed { _, (oi, ni, c) ->
        GlyphSlot(if (oi >= 0) ot[oi] else null, if (ni >= 0) nt[ni] else null,
          if (oi >= 0) (0 until oi).sumOf { ow[it].toDouble() }.toFloat() + ow[oi] / 2f else 0f,
          if (ni >= 0) (0 until ni).sumOf { nw[it].toDouble() }.toFloat() + nw[ni] / 2f else 0f,
          if (oi >= 0) ow[oi] else 0f, if (ni >= 0) nw[ni] else 0f, c)
      }, ow.sum(), nw.sum())
  }
}
