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
}
