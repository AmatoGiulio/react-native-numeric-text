package com.numerictext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionLogicTest {

  // ── Common prefix ─────────────────────────────────────────────────────────

  @Test fun commonPrefix_fullMatch() = assertEquals(5, TransitionLogic.computeCommonPrefix("hello", "hello world"))
  @Test fun commonPrefix_noMatch() = assertEquals(0, TransitionLogic.computeCommonPrefix("abc", "xyz"))
  @Test fun commonPrefix_partial() = assertEquals(4, TransitionLogic.computeCommonPrefix("2,585", "2,586"))
  @Test fun commonPrefix_emptyFirst() = assertEquals(0, TransitionLogic.computeCommonPrefix("", "hello"))
  @Test fun commonPrefix_emptySecond() = assertEquals(0, TransitionLogic.computeCommonPrefix("hello", ""))
  @Test fun commonPrefix_bothEmpty() = assertEquals(0, TransitionLogic.computeCommonPrefix("", ""))
  @Test fun commonPrefix_99vs100() = assertEquals(0, TransitionLogic.computeCommonPrefix("99", "100"))
  @Test fun commonPrefix_999vs1000() = assertEquals(0, TransitionLogic.computeCommonPrefix("999", "1,000"))

  // ── Common suffix ─────────────────────────────────────────────────────────

  @Test fun commonSuffix_fullMatch() = assertEquals(11, TransitionLogic.computeCommonSuffix("hello world", "hello world", 0))
  @Test fun commonSuffix_noMatch() = assertEquals(0, TransitionLogic.computeCommonSuffix("abc", "xyz", 0))
  @Test fun commonSuffix_afterPrefix() { val pref = TransitionLogic.computeCommonPrefix("aaXbb", "aaYbb"); assertEquals(2, TransitionLogic.computeCommonSuffix("aaXbb", "aaYbb", pref)) }
  @Test fun commonSuffix_doesNotOverlapPrefix() { val pref = TransitionLogic.computeCommonPrefix("X", "Y"); val sf = TransitionLogic.computeCommonSuffix("X", "Y", pref); assertTrue(sf <= minOf("X".length, "Y".length) - pref) }
  @Test fun commonSuffix_unchanged() = assertEquals(0, TransitionLogic.computeCommonSuffix("2,585", "2,586", 3))
  @Test fun commonSuffix_999to1000() { val sf = TransitionLogic.computeCommonSuffix("999", "1,000", 0); assertTrue(sf <= 0) }

  // ── LayerPlan ─────────────────────────────────────────────────────────────

  private fun layerPlan(o: String, n: String, measure: (String, Int, Int) -> Float = { _, s, e -> (e - s).toFloat() }) =
    TransitionLogic.buildLayerPlan(0.0, 0.0, o, n, measure)

  @Test fun layerPlan_2585to2586() { val p = layerPlan("2,585", "2,586"); assertEquals("2,58", p.oldFormatted.take(p.commonPrefixUtf16End)); assertEquals("5", p.oldFormatted.substring(p.oldChangedUtf16Start, p.oldChangedUtf16End)); assertEquals("6", p.newFormatted.substring(p.newChangedUtf16Start, p.newChangedUtf16End)) }
  @Test fun layerPlan_2586to2585() { val p = layerPlan("2,586", "2,585"); assertEquals("6", p.oldFormatted.substring(p.oldChangedUtf16Start, p.oldChangedUtf16End)) }
  @Test fun layerPlan_9to10() { val p = layerPlan("9", "10"); assertEquals(0, p.commonPrefixUtf16End); assertEquals(0, p.oldChangedUtf16Start); assertEquals(1, p.oldChangedUtf16End) }
  @Test fun layerPlan_99to100() { val p = layerPlan("99", "100"); assertEquals(0, p.commonPrefixUtf16End); assertEquals(true, p.oldWidth > 0); assertEquals(true, p.newWidth > p.oldWidth) }
  @Test fun layerPlan_999to1000() { val p = layerPlan("999", "1,000"); assertEquals(0, p.commonPrefixUtf16End) }
  @Test fun layerPlan_1000to999() { val p = layerPlan("1,000", "999"); assertEquals(0, p.commonPrefixUtf16End) }
  @Test fun layerPlan_19to20() { val p = layerPlan("1.9", "2.0"); assertEquals(0, p.commonPrefixUtf16End) }
  @Test fun layerPlan_neg1to0() { val p = layerPlan("-1", "0"); assertEquals(0, p.commonPrefixUtf16End); assertTrue(p.oldWidth > 0) }
  @Test fun layerPlan_0toNeg1() { val p = layerPlan("0", "-1"); assertEquals(0, p.commonPrefixUtf16End) }
  @Test fun layerPlan_identical() { val p = layerPlan("1,234", "1,234"); assertEquals("1,234".length, p.commonPrefixUtf16End); assertEquals(true, p.oldChangedUtf16Start == p.oldChangedUtf16End) }
  @Test fun layerPlan_prefixSuffix() { val p = layerPlan("12a34", "12b34"); assertEquals(2, p.commonPrefixUtf16End); assertEquals("a", p.oldFormatted.substring(p.oldChangedUtf16Start, p.oldChangedUtf16End)); assertEquals("b", p.newFormatted.substring(p.newChangedUtf16Start, p.newChangedUtf16End)) }
  @Test fun layerPlan_advanceWidths() { val p = layerPlan("99", "100") { _, s, e -> (e - s).toFloat() * 2f }; assertEquals(4f, p.oldWidth, 0.001f); assertEquals(6f, p.newWidth, 0.001f) }

  // ── Keyed slot layout ─────────────────────────────────────────────────────

  private fun keyed(s: String) = TransitionLogic.layoutKeyedSlots(s, ',', '.', '-') { it.length.toFloat() }
  private fun keyMap(s: String) = keyed(s).associate { it.key to it.char }

  @Test fun keyed_integerDigits_keyedFromRight() = assertEquals(mapOf("I0" to "6", "I1" to "7", "I2" to "5", "I3" to "2", "G3" to ","), keyMap("2,576"))
  @Test fun keyed_unitsKeyStable_acrossLengthChange() { assertEquals("9", keyMap("999")["I0"]); assertEquals("0", keyMap("1,000")["I0"]); assertEquals("9", keyMap("999")["I2"]); assertEquals("0", keyMap("1,000")["I2"]) }
  @Test fun keyed_separatorKeyStable_bornOnCarry() { assertEquals(false, keyMap("999").containsKey("G3")); assertEquals(false, keyMap("999").containsKey("I3")); assertEquals(",", keyMap("1,000")["G3"]); assertEquals("1", keyMap("1,000")["I3"]) }
  @Test fun keyed_fractional_keyedFromLeft() { assertEquals(mapOf("I0" to "1", "DEC" to ".", "F0" to "9"), keyMap("1.9")); assertEquals(mapOf("I0" to "2", "DEC" to ".", "F0" to "0"), keyMap("2.0")) }
  @Test fun keyed_sign_keyed() { assertEquals("-", keyMap("-1")["S"]); assertEquals("1", keyMap("-1")["I0"]) }
  @Test fun keyed_distFromRight_unitsIsSmallest() { val s = keyed("2,576"); val u = s.first { it.key == "I0" }; val t = s.first { it.key == "I3" }; assertTrue(u.distFromRight < t.distFromRight); assertEquals(0.5f, u.distFromRight, 0.001f) }
  @Test fun keyed_twoSeparators_distinctKeys() { val m = keyMap("1,000,000"); assertEquals(",", m["G3"]); assertEquals(",", m["G6"]); assertEquals("1", m["I6"]) }

  // ── Per-glyph plan ────────────────────────────────────────────────────────

  private fun perGlyph(old: String, new: String): TransitionPlan =
    TransitionLogic.buildPerGlyphPlan(old, new, ',', '.', '-') { it.length.toFloat() }

  @Test fun perGlyph_singleDigit_onlyLastChanges() { val p = perGlyph("2,576", "2,577"); assertEquals(1, p.slots.count { it.changed }); val ch = p.slots.first { it.changed }; assertEquals("6", ch.oldToken?.text); assertEquals("7", ch.newToken?.text); assertEquals(true, p.slots.any { !it.changed && it.oldToken?.text == "7" && it.newToken?.text == "7" }) }
  @Test fun perGlyph_stableInteriorDigit_staysAnchor() { val p = perGlyph("1,919", "1,616"); assertEquals(2, p.slots.count { it.changed }); assertEquals(true, p.slots.filter { it.changed }.all { it.oldToken?.text == "9" && it.newToken?.text == "6" }); assertEquals(true, p.slots.any { !it.changed && it.oldToken?.text == "1" && it.newToken?.text == "1" }) }
  @Test fun perGlyph_lengthChange_unitsAligned_leadingInserted() { val p = perGlyph("999", "1,000"); assertEquals(3, p.slots.count { it.oldToken?.text == "9" && it.newToken?.text == "0" }); assertEquals(true, p.slots.any { it.oldToken == null && it.newToken?.text == "1" }); assertEquals(true, p.slots.any { it.oldToken == null && it.newToken?.text == "," }) }
  @Test fun perGlyph_fractional_carry() { val p = perGlyph("1.9", "2.0"); assertEquals(true, p.slots.any { !it.changed && it.oldToken?.text == "." && it.newToken?.text == "." }); assertEquals(true, p.slots.any { it.changed && it.oldToken?.text == "1" && it.newToken?.text == "2" }); assertEquals(true, p.slots.any { it.changed && it.oldToken?.text == "9" && it.newToken?.text == "0" }) }

  // ── Easing ────────────────────────────────────────────────────────────────

  @Test fun smoothstep_at0_is0() = assertEquals(0f, TransitionLogic.smoothstep(0f, 1f, 0f), 0.001f)
  @Test fun smoothstep_at1_is1() = assertEquals(1f, TransitionLogic.smoothstep(0f, 1f, 1f), 0.001f)
  @Test fun smootherstep_at0_is0() = assertEquals(0f, TransitionLogic.smootherstep(0f), 0.001f)
  @Test fun smootherstep_at1_is1() = assertEquals(1f, TransitionLogic.smootherstep(1f), 0.001f)
  @Test fun remap_linear() = assertEquals(0.5f, TransitionLogic.remap(0.5f, 0f, 1f, 0f, 1f), 0.001f)

  // ── Measured crowded-roll choreography ───────────────────────────────────

  @Test fun simpleRoll_singleStepKeepsPhysicalPhase() {
    assertEquals(
      0.42f,
      TransitionLogic.simpleRollVisualPhase(0.42f, 1f, 1, 0.2f, 1),
      0.001f
    )
  }

  @Test fun simpleRoll_recoversDisplayedStepsCoalescedByFabric() {
    assertEquals(5, TransitionLogic.displayedStepCount(1001.0, 1006.0, 0))
    assertEquals(2, TransitionLogic.displayedStepCount(10.01, 10.03, 2))
    assertEquals(0, TransitionLogic.displayedStepCount(1.0, 1.25, 0))
    assertEquals(5, TransitionLogic.directedDigitSteps(1, 6, 1))
    assertEquals(2, TransitionLogic.directedDigitSteps(8, 0, 1))
    assertEquals(2, TransitionLogic.directedDigitSteps(0, 8, -1))
    assertEquals(0, TransitionLogic.directedDigitSteps(4, 4, 1))
  }

  @Test fun simpleRoll_incrementHoldsPenultimateThenCrossesFinalLane() {
    assertEquals(8.86f, TransitionLogic.simpleRollVisualPhase(8.7f, 10f, 1, 0.100f, 10), 0.001f)
    assertEquals(8.86f, TransitionLogic.simpleRollVisualPhase(9.2f, 10f, 1, 0.146f, 10), 0.001f)
    assertEquals(9.36f, TransitionLogic.simpleRollVisualPhase(9.6f, 10f, 1, 0.2045f, 10), 0.002f)
    assertEquals(9.86f, TransitionLogic.simpleRollVisualPhase(9.9f, 10f, 1, 0.263f, 10), 0.001f)
    assertEquals(9.86f, TransitionLogic.simpleRollVisualPhase(9.9f, 10f, 1, 0.280f, 10), 0.001f)
    assertEquals(10f, TransitionLogic.simpleRollVisualPhase(9.9f, 10f, 1, 0.300f, 10), 0.001f)
    assertEquals(10.025f, TransitionLogic.simpleRollVisualPhase(9.9f, 10f, 1, 0.335f, 10), 0.002f)
    assertEquals(10.05f, TransitionLogic.simpleRollVisualPhase(9.9f, 10f, 1, 0.370f, 10), 0.001f)
    assertEquals(10.025f, TransitionLogic.simpleRollVisualPhase(9.9f, 10f, 1, 0.435f, 10), 0.002f)
    assertEquals(10f, TransitionLogic.simpleRollVisualPhase(9.9f, 10f, 1, 0.500f, 10), 0.001f)
  }

  @Test fun simpleRoll_decrementIsExactMirror() {
    assertEquals(-8.86f, TransitionLogic.simpleRollVisualPhase(-8.7f, -10f, -1, 0.100f, 10), 0.001f)
    assertEquals(-8.86f, TransitionLogic.simpleRollVisualPhase(-9.2f, -10f, -1, 0.146f, 10), 0.001f)
    assertEquals(-9.36f, TransitionLogic.simpleRollVisualPhase(-9.6f, -10f, -1, 0.2045f, 10), 0.002f)
    assertEquals(-9.86f, TransitionLogic.simpleRollVisualPhase(-9.9f, -10f, -1, 0.263f, 10), 0.001f)
    assertEquals(-9.86f, TransitionLogic.simpleRollVisualPhase(-9.9f, -10f, -1, 0.280f, 10), 0.001f)
    assertEquals(-10f, TransitionLogic.simpleRollVisualPhase(-9.9f, -10f, -1, 0.300f, 10), 0.001f)
    assertEquals(-10.025f, TransitionLogic.simpleRollVisualPhase(-9.9f, -10f, -1, 0.335f, 10), 0.002f)
    assertEquals(-10.05f, TransitionLogic.simpleRollVisualPhase(-9.9f, -10f, -1, 0.370f, 10), 0.001f)
    assertEquals(-10.025f, TransitionLogic.simpleRollVisualPhase(-9.9f, -10f, -1, 0.435f, 10), 0.002f)
    assertEquals(-10f, TransitionLogic.simpleRollVisualPhase(-9.9f, -10f, -1, 0.500f, 10), 0.001f)
  }

  @Test fun simpleRoll_fastSmearBrakesBeforeReadableHold() {
    assertEquals(1f, TransitionLogic.simpleRollFastKeep(0.045f, 10), 0.001f)
    assertTrue(TransitionLogic.simpleRollFastKeep(0.095f, 10) in 0.45f..0.55f)
    assertEquals(0f, TransitionLogic.simpleRollFastKeep(0.146f, 10), 0.001f)
    assertEquals(1f, TransitionLogic.simpleRollFastKeep(1f, 1), 0.001f)
  }

  @Test fun simpleRoll_launchStartsSlowThenReleasesItsHalfLaneLag() {
    assertEquals(0f, TransitionLogic.simpleRollLaunchLag(0.040f, 4), 0.001f)
    assertTrue(TransitionLogic.simpleRollLaunchLag(0.100f, 4) in 0.42f..0.45f)
    assertEquals(0.50f, TransitionLogic.simpleRollLaunchLag(0.117f, 4), 0.002f)
    assertTrue(TransitionLogic.simpleRollLaunchLag(0.167f, 4) in 0.24f..0.26f)
    assertEquals(0f, TransitionLogic.simpleRollLaunchLag(0.217f, 4), 0.001f)
    assertEquals(0f, TransitionLogic.simpleRollLaunchLag(0.117f, 1), 0.001f)
  }

  @Test fun simpleRoll_finalCrossingAndInkEnvelopeMatchMeasuredMilestones() {
    assertEquals(0.15f, TransitionLogic.simpleRollFinalBlur(0.146f, 10), 0.001f)
    assertEquals(1f, TransitionLogic.simpleRollFinalBlur(0.2045f, 10), 0.001f)
    assertTrue(TransitionLogic.simpleRollFinalBlur(0.300f, 10) in 0.14f..0.15f)
    assertTrue(TransitionLogic.simpleRollFinalBlur(0.370f, 10) in 0.08f..0.10f)
    assertTrue(TransitionLogic.simpleRollFinalBlur(0.435f, 10) in 0.01f..0.03f)
    assertEquals(0f, TransitionLogic.simpleRollFinalBlur(0.500f, 10), 0.001f)
    assertEquals(1f, TransitionLogic.simpleRollFinalAlpha(0.146f, 10), 0.001f)
    assertEquals(0.70f, TransitionLogic.simpleRollFinalAlpha(0.2045f, 10), 0.002f)
    assertEquals(1f, TransitionLogic.simpleRollFinalAlpha(0.263f, 10), 0.001f)
    assertEquals(1f, TransitionLogic.simpleRollFinalAlpha(0.296f, 10), 0.001f)
    assertEquals(1f, TransitionLogic.simpleRollFinalAlpha(0.500f, 10), 0.001f)
    assertEquals(0f, TransitionLogic.simpleRollSettlingPairBlend(0.045f, 10), 0.001f)
    assertTrue(TransitionLogic.simpleRollSettlingPairBlend(0.095f, 10) in 0.30f..0.35f)
    assertEquals(0.65f, TransitionLogic.simpleRollSettlingPairBlend(0.146f, 10), 0.001f)
    assertEquals(0.85f, TransitionLogic.simpleRollSettlingPairBlend(0.2045f, 10), 0.002f)
    assertEquals(0.65f, TransitionLogic.simpleRollSettlingPairBlend(0.263f, 10), 0.001f)
    assertEquals(0.325f, TransitionLogic.simpleRollSettlingPairBlend(0.288f, 10), 0.002f)
    assertEquals(0f, TransitionLogic.simpleRollSettlingPairBlend(0.313f, 10), 0.001f)
    assertEquals(0f, TransitionLogic.simpleRollSettlingPairBlend(0.2f, 1), 0.001f)
    assertTrue(!TransitionLogic.simpleRollCanCommit(0.499f, 10))
    assertTrue(TransitionLogic.simpleRollCanCommit(0.500f, 10))
    assertTrue(TransitionLogic.simpleRollCanCommit(0f, 1))
  }
}
