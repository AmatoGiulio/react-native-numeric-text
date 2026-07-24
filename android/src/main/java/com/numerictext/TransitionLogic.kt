package com.numerictext

import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
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

  // Complementary crossfade: both glyphs ~0.5 at the midpoint → heavy overlap so the
  // blurred old + blurred new read as a single soft smear.
  fun oldOpacity(progress: Float): Float = 1f - smootherstep(progress)
  fun newOpacity(progress: Float): Float = smootherstep(progress)
  fun layoutInterpolation(progress: Float): Float = smootherstep(progress)

  // Smooth bell: 0 at both ends, 1 at the midpoint. Drives blur radius — the dominant,
  // "buttery" part of the effect. Present across most of the transition.
  fun blurEnvelope(progress: Float): Float = sin(PI * progress.coerceIn(0f, 1f)).toFloat()

  fun newBlurEnvelope(progress: Float): Float = blurEnvelope(progress)

  fun scaleEnvelope(progress: Float, minScale: Float = 0.92f): Float {
    val dist = abs(progress - 0.5f) * 2f
    return 1f - (1f - minScale) * (1f - smoothstep(0f, 1f, dist))
  }

  fun verticalMaskEnvelope(progress: Float): Float {
    return smoothstep(0f, 0.75f, 1f - abs(progress - 0.5f) * 2f)
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
