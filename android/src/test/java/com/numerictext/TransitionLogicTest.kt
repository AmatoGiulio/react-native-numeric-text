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
}

class NumericRollEngineTest {

  private fun slot(ch: String, key: String, width: Float = 10f, total: Float = 50f): KeyedSlot =
    KeyedSlot(key, TokenKind.DIGIT, ch, width / 2f, width, total)

  private fun slots(text: String): List<KeyedSlot> =
    TransitionLogic.layoutKeyedSlots(text, ',', '.', '-') { 10f }

  // ── Direction sign ─────────────────────────────────────────────────────────

  @Test fun direction_increment_newEntersFromAbove() {
    val engine = NumericRollEngine()
    engine.reset(slots("5"), "5", 100f)
    engine.setTarget(slots("6"), "6", direction = 1, lineHeight = 100f, animationDurationMs = 320L)

    val samples = engine.samples()
    val entering = samples.first { it.ch == "6" }
    val exiting = samples.first { it.ch == "5" }

    assertTrue(entering.offsetY < 0f)  // enters from above (negative offset)
    assertEquals(0f, exiting.offsetY, 0.01f)  // still at center at t=0
  }

  @Test fun direction_decrement_newEntersFromBelow() {
    val engine = NumericRollEngine()
    engine.reset(slots("6"), "6", 100f)
    engine.setTarget(slots("5"), "5", direction = -1, lineHeight = 100f, animationDurationMs = 320L)

    val samples = engine.samples()
    val entering = samples.first { it.ch == "5" }
    val exiting = samples.first { it.ch == "6" }

    assertTrue(entering.offsetY > 0f)  // enters from below (positive offset)
    assertEquals(0f, exiting.offsetY, 0.01f)  // still at center at t=0
  }

  // ── Samples visibility ─────────────────────────────────────────────────────

  @Test fun samples_bothDigitsVisibleDuringRoll() {
    val engine = NumericRollEngine()
    engine.reset(slots("5"), "5", 100f)
    engine.setTarget(slots("6"), "6", direction = 1, lineHeight = 100f, animationDurationMs = 320L)

    // Advance halfway through
    for (i in 0..15) engine.step(0.01f)

    val samples = engine.samples()
    assertEquals(2, samples.count { it.kind == TokenKind.DIGIT })
    assertTrue(samples.any { it.ch == "5" })
    assertTrue(samples.any { it.ch == "6" })
    // Both should have partial alpha (overlapping in transition)
    assertTrue(samples.all { it.alpha in 0.1f..0.99f })
  }

  @Test fun samples_onlyOneVisibleWhenSettled() {
    val engine = NumericRollEngine()
    engine.reset(slots("5"), "5", 100f)
    engine.setTarget(slots("6"), "6", direction = 1, lineHeight = 100f, animationDurationMs = 320L)
    engine.snapToTarget()

    val samples = engine.samples()
    assertEquals(1, samples.size)
    assertEquals("6", samples[0].ch)
    assertEquals(1f, samples[0].alpha, 0.001f)
  }

  // ── Column persistence (retargeting) ──────────────────────────────────────

  @Test fun columns_persistAcrossRetargets() {
    val engine = NumericRollEngine()
    engine.reset(slots("5"), "5", 100f)
    engine.setTarget(slots("6"), "6", direction = 1, lineHeight = 100f, animationDurationMs = 320L)

    // Simulate mid-flight
    for (i in 0..4) engine.step(0.01f)

    // Retarget to 7 before reaching 6
    engine.setTarget(slots("7"), "7", direction = 1, lineHeight = 100f, animationDurationMs = 320L)

    val samples = engine.samples()
    // Should have all three glyphs (5 exiting, 6 intermediate, 7 entering)
    assertTrue(samples.any { it.ch == "5" })
    assertTrue(samples.any { it.ch == "6" })
    assertTrue(samples.any { it.ch == "7" })
  }

  // ── Smoothstep ─────────────────────────────────────────────────────────────

  @Test fun smootherstep_endpoints() {
    assertEquals(0f, NumericRollEngine.smootherstep(0f), 0.001f)
    assertEquals(1f, NumericRollEngine.smootherstep(1f), 0.001f)
  }

  @Test fun smootherstep_midpoint() {
    val v = NumericRollEngine.smootherstep(0.5f)
    assertEquals(0.5f, v, 0.001f)
  }
}
