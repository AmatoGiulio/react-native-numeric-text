package com.numerictext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionLogicTest {

  // --- Common prefix ---

  @Test
  fun commonPrefix_fullMatch() {
    assertEquals(5, TransitionLogic.computeCommonPrefix("hello", "hello world"))
  }

  @Test
  fun commonPrefix_noMatch() {
    assertEquals(0, TransitionLogic.computeCommonPrefix("abc", "xyz"))
  }

  @Test
  fun commonPrefix_partial() {
    assertEquals(4, TransitionLogic.computeCommonPrefix("2,585", "2,586"))
  }

  @Test
  fun commonPrefix_emptyFirst() {
    assertEquals(0, TransitionLogic.computeCommonPrefix("", "hello"))
  }

  @Test
  fun commonPrefix_emptySecond() {
    assertEquals(0, TransitionLogic.computeCommonPrefix("hello", ""))
  }

  @Test
  fun commonPrefix_bothEmpty() {
    assertEquals(0, TransitionLogic.computeCommonPrefix("", ""))
  }

  @Test
  fun commonPrefix_99vs100() {
    assertEquals(0, TransitionLogic.computeCommonPrefix("99", "100"))
  }

  @Test
  fun commonPrefix_999vs1000() {
    assertEquals(0, TransitionLogic.computeCommonPrefix("999", "1,000"))
  }

  // --- Common suffix ---

  @Test
  fun commonSuffix_fullMatch() {
    assertEquals(5, TransitionLogic.computeCommonSuffix("hello world", "world", 0))
  }

  @Test
  fun commonSuffix_noMatch() {
    assertEquals(0, TransitionLogic.computeCommonSuffix("abc", "xyz", 0))
  }

  @Test
  fun commonSuffix_afterPrefix() {
    // For "2,585" → "2,586":
    // prefixLen = 4 ("2,58")
    // from index 4, old = "5", new = "6"
    assertEquals(0, TransitionLogic.computeCommonSuffix("2,585", "2,586", 4))
  }

  @Test
  fun commonSuffix_doesNotOverlapPrefix() {
    // "abc" → "abc": prefix=3, suffix should not overlap
    assertEquals(0, TransitionLogic.computeCommonSuffix("abc", "abc", 3))
  }

  @Test
  fun commonSuffix_unchanged() {
    assertEquals(0, TransitionLogic.computeCommonSuffix("99", "100", 0))
  }

  @Test
  fun commonSuffix_999to1000() {
    assertEquals(0, TransitionLogic.computeCommonSuffix("999", "1,000", 0))
  }

  // --- LayerPlan builder (replaces old computeChangedRun) ---

  private fun charMeasure(text: String, start: Int, end: Int): Float = (end - start).toFloat()

  @Test
  fun layerPlan_2585to2586() {
    // "2,585" → "2,586": prefix "2,58" (4 chars), changed "5"→"6", no suffix
    val plan = TransitionLogic.buildLayerPlan(2585.0, 2586.0, "2,585", "2,586", ::charMeasure)
    assertEquals(4, plan.commonPrefixUtf16End)
    assertEquals(4, plan.oldChangedUtf16Start); assertEquals(5, plan.oldChangedUtf16End)
    assertEquals(4, plan.newChangedUtf16Start); assertEquals(5, plan.newChangedUtf16End)
    assertEquals(5, plan.oldSuffixUtf16Start); assertEquals(5, plan.newSuffixUtf16Start)
    assertEquals("2,58", plan.oldFormatted.substring(0, plan.commonPrefixUtf16End))
    assertEquals("5", plan.oldFormatted.substring(plan.oldChangedUtf16Start, plan.oldChangedUtf16End))
    assertEquals("6", plan.newFormatted.substring(plan.newChangedUtf16Start, plan.newChangedUtf16End))
  }

  @Test
  fun layerPlan_2586to2585() {
    val plan = TransitionLogic.buildLayerPlan(2586.0, 2585.0, "2,586", "2,585", ::charMeasure)
    assertEquals(4, plan.commonPrefixUtf16End)
    assertEquals("2,58", plan.oldFormatted.substring(0, plan.commonPrefixUtf16End))
    assertEquals("6", plan.oldFormatted.substring(plan.oldChangedUtf16Start, plan.oldChangedUtf16End))
    assertEquals("5", plan.newFormatted.substring(plan.newChangedUtf16Start, plan.newChangedUtf16End))
  }

  @Test
  fun layerPlan_9to10() {
    val plan = TransitionLogic.buildLayerPlan(9.0, 10.0, "9", "10", ::charMeasure)
    assertEquals(0, plan.commonPrefixUtf16End)
    assertEquals(0, plan.oldChangedUtf16Start); assertEquals(1, plan.oldChangedUtf16End)
    assertEquals(0, plan.newChangedUtf16Start); assertEquals(2, plan.newChangedUtf16End)
    assertEquals("9", plan.oldFormatted.substring(plan.oldChangedUtf16Start, plan.oldChangedUtf16End))
    assertEquals("10", plan.newFormatted.substring(plan.newChangedUtf16Start, plan.newChangedUtf16End))
  }

  @Test
  fun layerPlan_99to100() {
    val plan = TransitionLogic.buildLayerPlan(99.0, 100.0, "99", "100", ::charMeasure)
    assertEquals(0, plan.commonPrefixUtf16End)
    assertEquals("99", plan.oldFormatted.substring(plan.oldChangedUtf16Start, plan.oldChangedUtf16End))
    assertEquals("100", plan.newFormatted.substring(plan.newChangedUtf16Start, plan.newChangedUtf16End))
  }

  @Test
  fun layerPlan_999to1000() {
    val plan = TransitionLogic.buildLayerPlan(999.0, 1000.0, "999", "1,000", ::charMeasure)
    assertEquals(0, plan.commonPrefixUtf16End)
    assertEquals("999", plan.oldFormatted.substring(plan.oldChangedUtf16Start, plan.oldChangedUtf16End))
    assertEquals("1,000", plan.newFormatted.substring(plan.newChangedUtf16Start, plan.newChangedUtf16End))
  }

  @Test
  fun layerPlan_1000to999() {
    val plan = TransitionLogic.buildLayerPlan(1000.0, 999.0, "1,000", "999", ::charMeasure)
    assertEquals(0, plan.commonPrefixUtf16End)
    assertEquals("1,000", plan.oldFormatted.substring(plan.oldChangedUtf16Start, plan.oldChangedUtf16End))
    assertEquals("999", plan.newFormatted.substring(plan.newChangedUtf16Start, plan.newChangedUtf16End))
  }

  @Test
  fun layerPlan_19to20() {
    val plan = TransitionLogic.buildLayerPlan(1.9, 2.0, "1.9", "2.0", ::charMeasure)
    assertEquals(0, plan.commonPrefixUtf16End)
    assertEquals("1.9", plan.oldFormatted.substring(plan.oldChangedUtf16Start, plan.oldChangedUtf16End))
    assertEquals("2.0", plan.newFormatted.substring(plan.newChangedUtf16Start, plan.newChangedUtf16End))
  }

  @Test
  fun layerPlan_neg1to0() {
    val plan = TransitionLogic.buildLayerPlan(-1.0, 0.0, "-1", "0", ::charMeasure)
    assertEquals(0, plan.commonPrefixUtf16End)
    assertEquals("-1", plan.oldFormatted.substring(plan.oldChangedUtf16Start, plan.oldChangedUtf16End))
    assertEquals("0", plan.newFormatted.substring(plan.newChangedUtf16Start, plan.newChangedUtf16End))
  }

  @Test
  fun layerPlan_0toNeg1() {
    val plan = TransitionLogic.buildLayerPlan(0.0, -1.0, "0", "-1", ::charMeasure)
    assertEquals(0, plan.commonPrefixUtf16End)
    assertEquals("0", plan.oldFormatted.substring(plan.oldChangedUtf16Start, plan.oldChangedUtf16End))
    assertEquals("-1", plan.newFormatted.substring(plan.newChangedUtf16Start, plan.newChangedUtf16End))
  }

  @Test
  fun layerPlan_identical() {
    // Identical strings → prefix = full length, changed region empty
    val plan = TransitionLogic.buildLayerPlan(1234.0, 1234.0, "1,234", "1,234", ::charMeasure)
    assertEquals(5, plan.commonPrefixUtf16End) // "1,234" is 5 chars
    assertEquals(5, plan.oldChangedUtf16Start); assertEquals(5, plan.oldChangedUtf16End)
    assertEquals(5, plan.newChangedUtf16Start); assertEquals(5, plan.newChangedUtf16End)
  }

  @Test
  fun layerPlan_prefixSuffix() {
    // "ab123xy" → "ab456xy": prefix "ab" (2), changed "123"→"456", suffix "xy" (2)
    val plan = TransitionLogic.buildLayerPlan(123.0, 456.0, "ab123xy", "ab456xy", ::charMeasure)
    assertEquals(2, plan.commonPrefixUtf16End)
    assertEquals(2, plan.oldChangedUtf16Start); assertEquals(5, plan.oldChangedUtf16End)
    assertEquals(2, plan.newChangedUtf16Start); assertEquals(5, plan.newChangedUtf16End)
    assertEquals(5, plan.oldSuffixUtf16Start); assertEquals(5, plan.newSuffixUtf16Start)
    assertEquals("ab", plan.oldFormatted.substring(0, plan.commonPrefixUtf16End))
    assertEquals("123", plan.oldFormatted.substring(plan.oldChangedUtf16Start, plan.oldChangedUtf16End))
    assertEquals("456", plan.newFormatted.substring(plan.newChangedUtf16Start, plan.newChangedUtf16End))
    assertEquals("xy", plan.oldFormatted.substring(plan.oldSuffixUtf16Start))
    assertEquals("xy", plan.newFormatted.substring(plan.newSuffixUtf16Start))
  }

  @Test
  fun layerPlan_advanceWidths() {
    // With charMeasure = (end - start), each char = 1f width
    val plan = TransitionLogic.buildLayerPlan(99.0, 100.0, "99", "100", ::charMeasure)
    assertEquals(0f, plan.oldPrefixAdvance, 0.001f)
    assertEquals(2f, plan.oldChangedAdvance, 0.001f)
    assertEquals(0f, plan.oldSuffixAdvance, 0.001f)
    assertEquals(0f, plan.newPrefixAdvance, 0.001f)
    assertEquals(3f, plan.newChangedAdvance, 0.001f)
    assertEquals(0f, plan.newSuffixAdvance, 0.001f)
    assertEquals(2f, plan.oldWidth, 0.001f)
    assertEquals(3f, plan.newWidth, 0.001f)
  }

  // --- Vertical trajectory invariants ---

  @Test
  fun oldOffset_increment_atProgress0_isZero() {
    val offset = TransitionLogic.computeOldOffset(1, 100f, 0f)
    assertEquals(0f, offset, 0.001f)
  }

  @Test
  fun oldOffset_increment_atProgress1_isNegativeTravel() {
    val offset = TransitionLogic.computeOldOffset(1, 100f, 1f)
    assertEquals(-100f, offset, 0.001f)
  }

  @Test
  fun oldOffset_decrement_atProgress0_isZero() {
    val offset = TransitionLogic.computeOldOffset(-1, 100f, 0f)
    assertEquals(0f, offset, 0.001f)
  }

  @Test
  fun oldOffset_decrement_atProgress1_isPositiveTravel() {
    val offset = TransitionLogic.computeOldOffset(-1, 100f, 1f)
    assertEquals(100f, offset, 0.001f)
  }

  @Test
  fun newOffset_increment_atProgress0_isPositiveTravel() {
    // new starts below the transition window
    val offset = TransitionLogic.computeNewOffset(1, 100f, 0f)
    assertEquals(100f, offset, 0.001f)
  }

  @Test
  fun newOffset_increment_atProgress1_isZero() {
    val offset = TransitionLogic.computeNewOffset(1, 100f, 1f)
    assertEquals(0f, offset, 0.001f)
  }

  @Test
  fun newOffset_decrement_atProgress0_isNegativeTravel() {
    // new starts above the transition window
    val offset = TransitionLogic.computeNewOffset(-1, 100f, 0f)
    assertEquals(-100f, offset, 0.001f)
  }

  @Test
  fun newOffset_decrement_atProgress1_isZero() {
    val offset = TransitionLogic.computeNewOffset(-1, 100f, 1f)
    assertEquals(0f, offset, 0.001f)
  }

  // --- Curve invariants ---

  @Test
  fun oldOpacity_at0_is1() {
    assertEquals(1f, TransitionLogic.oldOpacity(0f), 0.001f)
  }

  @Test
  fun oldOpacity_at1_is0() {
    assertEquals(0f, TransitionLogic.oldOpacity(1f), 0.001f)
  }

  @Test
  fun newOpacity_at0_is0() {
    assertEquals(0f, TransitionLogic.newOpacity(0f), 0.001f)
  }

  @Test
  fun newOpacity_at1_is1() {
    assertEquals(1f, TransitionLogic.newOpacity(1f), 0.001f)
  }

  @Test
  fun layoutInterpolation_isIdentity() {
    assertEquals(0f, TransitionLogic.layoutInterpolation(0f), 0.001f)
    assertEquals(0.5f, TransitionLogic.layoutInterpolation(0.5f), 0.001f)
    assertEquals(1f, TransitionLogic.layoutInterpolation(1f), 0.001f)
  }

  // --- Per-glyph slot matching (the fidelity-critical logic) ---

  private fun perGlyph(old: String, new: String): TransitionPlan =
    TransitionLogic.buildPerGlyphPlan(old, new, ',', '.', '-') { it.length.toFloat() }

  @Test
  fun perGlyph_singleDigit_onlyLastChanges() {
    // 2,576 → 2,577: exactly one changed slot (6→7); tens digit 7 stays an anchor.
    val plan = perGlyph("2,576", "2,577")
    assertEquals(1, plan.slots.count { it.changed })
    val ch = plan.slots.first { it.changed }
    assertEquals("6", ch.oldToken?.text); assertEquals("7", ch.newToken?.text)
    assertEquals(true, plan.slots.any { !it.changed && it.oldToken?.text == "7" && it.newToken?.text == "7" })
  }

  @Test
  fun perGlyph_stableInteriorDigit_staysAnchor() {
    // 1,919 → 1,616: the interior "1" must NOT animate (this is where changed-run diverges).
    val plan = perGlyph("1,919", "1,616")
    assertEquals(2, plan.slots.count { it.changed })
    assertEquals(true, plan.slots.filter { it.changed }.all { it.oldToken?.text == "9" && it.newToken?.text == "6" })
    // interior units-hundreds "1" preserved as a sharp anchor between the two changed slots
    assertEquals(true, plan.slots.any { !it.changed && it.oldToken?.text == "1" && it.newToken?.text == "1" })
  }

  @Test
  fun perGlyph_lengthChange_unitsAligned_leadingInserted() {
    // 999 → 1,000: units align right (three 9→0 rolls); a new "1" and "," enter.
    val plan = perGlyph("999", "1,000")
    assertEquals(3, plan.slots.count { it.oldToken?.text == "9" && it.newToken?.text == "0" })
    assertEquals(true, plan.slots.any { it.oldToken == null && it.newToken?.text == "1" })
    assertEquals(true, plan.slots.any { it.oldToken == null && it.newToken?.text == "," })
  }

  // --- Spring driver ---

  private fun runSpring(dampingRatio: Float, steps: Int = 800): Pair<Float, Float> {
    var x = 0f; var v = 0f; var maxX = 0f
    repeat(steps) {
      val (nx, nv) = TransitionLogic.springStep(x, v, 1f, 320f, dampingRatio, 1f / 120f)
      x = nx; v = nv; if (x > maxX) maxX = x
    }
    return Pair(x, maxX)
  }

  @Test
  fun spring_settlesToGoal() {
    val (x, _) = runSpring(0.7f)
    assertEquals(1f, x, 0.01f)
  }

  @Test
  fun spring_underdamped_overshoots() {
    val (_, maxX) = runSpring(0.7f)
    assertTrue("expected overshoot past 1.0, got $maxX", maxX > 1.0f)
  }

  @Test
  fun spring_criticallyDamped_doesNotOvershoot() {
    val (_, maxX) = runSpring(1.0f)
    assertTrue("expected no overshoot, got $maxX", maxX <= 1.001f)
  }

  // --- Per-glyph enter/exit lifecycle curves ---

  @Test
  fun exit_alpha_startsOpaque_endsGone() {
    assertEquals(1f, TransitionLogic.exitAlpha(0f), 0.001f)
    assertEquals(0f, TransitionLogic.exitAlpha(1f), 0.001f)
  }

  @Test
  fun exit_alpha_dropsFastThenTails() {
    // Front-loaded departure: already well under half by a third of the way through, then a soft
    // low tail instead of a hard cut.
    assertTrue(TransitionLogic.exitAlpha(0.33f) < 0.5f)
    assertTrue(TransitionLogic.exitAlpha(0.85f) > 0f)
    assertTrue(TransitionLogic.exitAlpha(0.85f) < 0.1f)
  }

  @Test
  fun exit_blur_isFrontLoaded() {
    // Softness leads the motion: near-full blur very early, before it has visibly travelled.
    assertTrue(TransitionLogic.exitBlur(0.22f) > 0.95f)
    assertEquals(0f, TransitionLogic.exitBlur(0f), 0.001f)
  }

  @Test
  fun exit_shrinksAndTravels() {
    assertEquals(1f, TransitionLogic.exitScale(0f), 0.001f)
    assertEquals(0.82f, TransitionLogic.exitScale(1f), 0.001f)
    assertEquals(0f, TransitionLogic.exitOffsetFraction(0f), 0.001f)
    assertEquals(1f, TransitionLogic.exitOffsetFraction(1f), 0.001f)
  }

  @Test
  fun enter_alpha_lagsThenSolidifies() {
    assertEquals(0f, TransitionLogic.enterAlpha(0f), 0.001f)
    assertEquals(1f, TransitionLogic.enterAlpha(1f), 0.001f)
    // Still faint while the outgoing glyph is doing most of its leaving.
    assertTrue(TransitionLogic.enterAlpha(0.15f) < 0.35f)
  }

  @Test
  fun enter_blur_sharpensIn() {
    assertEquals(1f, TransitionLogic.enterBlur(0f), 0.001f)
    assertEquals(0f, TransitionLogic.enterBlur(1f), 0.001f)
  }

  @Test
  fun enter_arrivesInPlace_withSettle() {
    // Ends at its final size/position...
    assertEquals(1f, TransitionLogic.enterScale(1f), 0.001f)
    assertEquals(0f, TransitionLogic.enterOffsetFraction(1f), 0.001f)
    // ...and the gentle overshoot means it passes its target before settling back.
    assertTrue(TransitionLogic.easeOutBack(0.7f) > 1f)
  }

  // --- Keyed slot layout (per-slot spring scheduler input) ---

  private fun keyed(s: String) =
    TransitionLogic.layoutKeyedSlots(s, ',', '.', '-') { it.length.toFloat() }

  private fun keyMap(s: String) = keyed(s).associate { it.key to it.char }

  @Test
  fun keyed_integerDigits_keyedFromRight() {
    // Units → I0, tens → I1, … regardless of overall length.
    assertEquals(mapOf("I0" to "6", "I1" to "7", "I2" to "5", "I3" to "2", "G3" to ","), keyMap("2,576"))
  }

  @Test
  fun keyed_unitsKeyStable_acrossLengthChange() {
    // The units digit keeps key I0 in both "999" and "1,000" → its spring survives the carry.
    assertEquals("9", keyMap("999")["I0"])
    assertEquals("0", keyMap("1,000")["I0"])
    assertEquals("9", keyMap("999")["I2"])
    assertEquals("0", keyMap("1,000")["I2"])
  }

  @Test
  fun keyed_separatorKeyStable_bornOnCarry() {
    // 999 has no separator; 1,000 gains G3 and a new leading I3 — both absent from 999's keys.
    assertEquals(false, keyMap("999").containsKey("G3"))
    assertEquals(false, keyMap("999").containsKey("I3"))
    assertEquals(",", keyMap("1,000")["G3"])
    assertEquals("1", keyMap("1,000")["I3"])
  }

  @Test
  fun keyed_fractional_keyedFromLeft() {
    assertEquals(mapOf("I0" to "1", "DEC" to ".", "F0" to "9"), keyMap("1.9"))
    assertEquals(mapOf("I0" to "2", "DEC" to ".", "F0" to "0"), keyMap("2.0"))
  }

  @Test
  fun keyed_sign_keyed() {
    assertEquals("-", keyMap("-1")["S"])
    assertEquals("1", keyMap("-1")["I0"])
  }

  @Test
  fun keyed_distFromRight_unitsIsSmallest() {
    // With unit char widths, the rightmost glyph has the smallest distance-from-right.
    val slots = keyed("2,576")
    val units = slots.first { it.key == "I0" }
    val thousands = slots.first { it.key == "I3" }
    assertTrue(units.distFromRight < thousands.distFromRight)
    // Units centre sits half a glyph in from the right edge.
    assertEquals(0.5f, units.distFromRight, 0.001f)
  }

  @Test
  fun keyed_twoSeparators_distinctKeys() {
    val m = keyMap("1,000,000")
    assertEquals(",", m["G3"])
    assertEquals(",", m["G6"])
    assertEquals("1", m["I6"])
  }

  @Test
  fun perGlyph_fractional_carry() {
    // 1.9 → 2.0: integer 1→2 and fraction 9→0 both change; decimal point is an anchor.
    val plan = perGlyph("1.9", "2.0")
    assertEquals(true, plan.slots.any { !it.changed && it.oldToken?.text == "." && it.newToken?.text == "." })
    assertEquals(true, plan.slots.any { it.changed && it.oldToken?.text == "1" && it.newToken?.text == "2" })
    assertEquals(true, plan.slots.any { it.changed && it.oldToken?.text == "9" && it.newToken?.text == "0" })
  }

  // ── Continuous presence model ──
  //
  // These pin the properties the presence model exists for: a glyph's look is a pure function of
  // its current presence, so an interrupted transition can reverse instead of restarting.

  @Test
  fun presence_endpointsAreCleanExtremes() {
    // Fully absent: invisible and maximally soft. Fully present: opaque and perfectly sharp.
    assertEquals(0f, TransitionLogic.presenceAlpha(0f), 0.001f)
    assertEquals(1f, TransitionLogic.presenceBlur(0f), 0.001f)
    assertEquals(1f, TransitionLogic.presenceAlpha(1f), 0.001f)
    assertEquals(0f, TransitionLogic.presenceBlur(1f), 0.001f)
  }

  @Test
  fun presence_crossingIsACleanHandover() {
    // This asserted the opposite until 2026-07-30: that a crossing pair sums to MORE than one
    // glyph of ink (> 1.05), which is why the curve was sub-linear. The per-glyph template fit
    // says the reference's summed ink DIPS at the swap, to ~0.52 of one settled glyph.
    // The bottom stays flat — an arriving glyph must not light up before it has travelled, and the
    // reference's incoming ink is still 0.01 at +183 ms.
    assertTrue(TransitionLogic.presenceAlpha(0.2f) < 0.12f)
    // …but the TOP is not flat. A zero tangent at full presence made every departure loiter ~30 ms
    // before it began to go, which measured as every column's half-gone instant sitting that far
    // behind the reference. Smoothstep would give 0.993 here.
    assertTrue(
      "a departure must begin to shed as soon as it is released",
      TransitionLogic.presenceAlpha(0.95f) < 0.975f
    )
    // Mid-crossing each glyph carries well under half: two of them summing near the reference's
    // measured floor, not the 1.3 glyphs of ink the original curve was built to produce.
    assertEquals(0.40f, TransitionLogic.presenceAlpha(0.5f), 0.04f)
  }


  @Test
  fun presence_blurIsSoftMidFlightAndResolvesCleanly() {
    // Mid-flight a glyph must be clearly out of focus…
    assertTrue(TransitionLogic.presenceBlur(0.5f) >= 0.5f)
    // …and this used to demand >= 0.15 at p = 0.8 as well, on the belief that a glyph should
    // already be soft there. Measured, the reference is CRISP while it still holds most of its ink
    // (σ 0.03 at 0.8 of its ink, 0.07 at 0.5) and ours was the other way round (0.05, 0.05), which
    // is what made a departing ghost read as a smudge rather than a digit on its way out. The
    // curve now has a dead zone at the top, so this asserts the opposite: barely soft at 0.8.
    assertTrue(TransitionLogic.presenceBlur(0.8f) < 0.15f)
    // …but the tail must be short enough that the transition actually looks finished. A sub-linear
    // curve left a residual blur that, at a large font size, still measured several pixels.
    assertTrue(TransitionLogic.presenceBlur(0.98f) < 0.03f)
  }

  @Test
  fun presence_offsetGoesPastBaselineOnOvershoot() {
    // Presence is fed in unclamped so the spring's overshoot carries the glyph past its resting
    // baseline and back — the settle bounce.
    assertEquals(1f, TransitionLogic.presenceOffsetFraction(0f), 0.001f)
    assertEquals(0f, TransitionLogic.presenceOffsetFraction(1f), 0.001f)
    assertTrue(TransitionLogic.presenceOffsetFraction(1.05f) < 0f)
  }

  @Test
  fun presence_scaleSpansMinToFull() {
    assertEquals(0.72f, TransitionLogic.presenceScale(0f, 0.72f), 0.001f)
    assertEquals(1f, TransitionLogic.presenceScale(1f, 0.72f), 0.001f)
  }

  @Test
  fun rollTape_twoAdjacentLanesHandOffContinuously() {
    // Increment: the old lane goes 0 -> +1 while the target lane goes -1 -> 0. Their raw presence
    // remains complementary at every presentation phase; a retarget changes neither lane's current
    // position nor velocity.
    for (phase in listOf(0f, 0.2f, 0.5f, 0.8f, 1f)) {
      val oldPresence = TransitionLogic.rollTapePresence(
        offset = phase,
        isTarget = false,
        direction = 1
      )
      val newPresence = TransitionLogic.rollTapePresence(
        offset = phase - 1f,
        isTarget = true,
        direction = 1
      )
      assertEquals(1f, oldPresence + newPresence, 0.001f)
    }
  }

  @Test
  fun rollTape_targetStaysWholeAcrossSettleOvershoot() {
    assertEquals(1f, TransitionLogic.rollTapePresence(0.08f, true, 1), 0.001f)
    assertEquals(1f, TransitionLogic.rollTapePresence(-0.08f, true, -1), 0.001f)
  }

  @Test
  fun structuralArrival_resolvesVisuallyBeforeThePhysicalSpringSettles() {
    assertEquals(0f, TransitionLogic.structuralArrivalVisualPresence(0f), 0.001f)
    assertTrue(
      TransitionLogic.structuralArrivalVisualPresence(0.5f) > 0.5f
    )
    assertEquals(1f, TransitionLogic.structuralArrivalVisualPresence(0.82f), 0.001f)
    assertEquals(1f, TransitionLogic.structuralArrivalVisualPresence(1f), 0.001f)
  }

  @Test
  fun rollTape_reusesOnlyALaneOnTheRequestedArrivalSide() {
    // With the phase between A and B, A is a valid target for a reversal but not for another
    // same-direction digit cycle.
    assertTrue(
      TransitionLogic.rollTapeCanReuseLane(
        lane = 0f,
        phase = 0.4f,
        direction = -1,
        maxDistance = 1.35f
      )
    )
    assertTrue(
      !TransitionLogic.rollTapeCanReuseLane(
        lane = 0f,
        phase = 0.4f,
        direction = 1,
        maxDistance = 1.35f
      )
    )
    assertTrue(
      !TransitionLogic.rollTapeCanReuseLane(
        lane = 2f,
        phase = 0.4f,
        direction = 1,
        maxDistance = 1.35f
      )
    )
  }

  @Test
  fun rollTape_retargetDoesNotResetPresentationVelocity() {
    var phase = 0f
    var velocity = 0f
    repeat(8) {
      val result = TransitionLogic.springIntegrate(
        phase,
        velocity,
        1f,
        157.914f,
        1f,
        1f / 60f
      )
      phase = result.first
      velocity = result.second
    }
    val beforeRetarget = velocity
    val nextTarget = 2f

    // Moving the goal is the whole retarget operation. Feed the live presentation velocity into
    // the successor spring; a restart from zero would fail both assertions.
    assertTrue("the first segment must already be moving", beforeRetarget > 0f)
    val result = TransitionLogic.springIntegrate(
      phase,
      velocity,
      nextTarget,
      157.914f,
      1f,
      1f / 60f
    )
    assertTrue("the tape must keep moving through a monotonic retarget", result.second > 0f)
    assertTrue("the successor must inherit and accelerate the live motion", result.second > beforeRetarget)
  }

  @Test
  fun springStep_retargetPreservesVelocity() {
    // A glyph caught mid-exit and retargeted back to present must keep moving in the direction it
    // already had for at least one step — that continuity IS the "back" the reference shows.
    var p = 1f; var v = 0f
    repeat(6) { val (x, vv) = TransitionLogic.springStep(p, v, 0f, 150f, 0.85f, 1f / 60f); p = x; v = vv }
    assertTrue("should be mid-flight, was $p", p in 0.2f..0.95f)
    val vAtReversal = v
    assertTrue("should still be heading out", vAtReversal < 0f)
    val (pAfter, vAfter) = TransitionLogic.springStep(p, v, 1f, 150f, 0.85f, 1f / 60f)
    // Momentum survives the retarget: it is still moving away for this step, decelerating.
    assertTrue(pAfter < p)
    assertTrue(vAfter > vAtReversal)
  }
}
