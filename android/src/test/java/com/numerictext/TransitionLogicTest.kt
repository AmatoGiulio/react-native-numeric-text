package com.numerictext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionLogicTest {

  private fun line(text: String): TextLineGeometry =
    TextLineGeometry(
      text = text,
      totalWidth = text.length.toFloat(),
      horizontals = FloatArray(text.length + 1) { it.toFloat() },
      layout = null,
      horizontalOrigin = 0f,
    )

  private fun keyed(text: String): List<KeyedSlot> =
    TransitionLogic.layoutKeyedSlots(text, ',', '.', '-', line(text))

  private fun keyMap(text: String): Map<String, String> = keyed(text).associate { it.key to it.char }

  private fun assertUniqueKeys(slots: List<KeyedSlot>) {
    assertEquals(slots.size, slots.map { it.key }.toSet().size)
  }

  @Test
  fun integerDigits_areAnchoredFromTheLeft() {
    assertEquals(
      mapOf("I0" to "2", "G3:," to ",", "I1" to "5", "I2" to "7", "I3" to "6"),
      keyMap("2,576"),
    )
  }

  @Test
  fun carryToFourDigits_preservesTheThreeExistingVisualColumns() {
    val old = keyMap("999")
    val next = keyMap("1,000")

    assertEquals("9", old["I0"])
    assertEquals("9", old["I1"])
    assertEquals("9", old["I2"])

    assertEquals("1", next["I0"])
    assertEquals("0", next["I1"])
    assertEquals("0", next["I2"])
    assertEquals("0", next["I3"])
    assertEquals(",", next["G3:,"])
  }

  @Test
  fun groupSeparator_isStructuralAndBornOnCarry() {
    assertFalse(keyMap("999").containsKey("G3:,"))
    assertEquals(",", keyMap("1,000")["G3:,"])
  }

  @Test
  fun fractions_areAnchoredFromTheDecimalPoint() {
    assertEquals(
      mapOf("I0" to "1", "DEC:." to ".", "F0" to "9"),
      keyMap("1.9"),
    )
    assertEquals(
      mapOf("I0" to "2", "DEC:." to ".", "F0" to "0"),
      keyMap("2.0"),
    )
  }

  @Test
  fun sign_hasStableStructuralKey() {
    assertEquals("-", keyMap("-1")["S"])
    assertEquals("1", keyMap("-1")["I0"])
  }

  @Test
  fun currencySymbol_keepsItsKeyWhenTheNumberGrowsADigit() {
    assertEquals("$", keyMap("\$999")["P0"])
    assertEquals("$", keyMap("\$1,000")["P0"])
  }

  @Test
  fun currencySymbol_keepsItsKeyAcrossASignChange() {
    assertEquals("$", keyMap("\$1.00")["P0"])
    assertEquals("$", keyMap("-\$1.00")["P0"])
    assertEquals("-", keyMap("-\$1.00")["S"])
  }

  @Test
  fun differentStructuralGlyphs_shareTheSameSemanticPosition() {
    assertEquals("$", keyMap("\$1")["P0"])
    assertEquals("€", keyMap("€1")["P0"])
  }

  @Test
  fun accountingBrackets_sitOutsideTheSymbol() {
    val accounting = keyMap("(\$1.00)")
    assertEquals("$", accounting["P0"])
    assertEquals("(", accounting["P1"])
    assertEquals(")", accounting["X0"])
  }

  @Test
  fun trailingSymbol_isKeyedFromTheEndOfTheNumber() {
    val small = TransitionLogic.layoutKeyedSlots("999,00\u00A0€", '.', ',', '-', line("999,00\u00A0€"))
      .associate { it.key to it.char }
    val large =
      TransitionLogic.layoutKeyedSlots("1.000,00\u00A0€", '.', ',', '-', line("1.000,00\u00A0€"))
        .associate { it.key to it.char }

    assertEquals("€", small["X1"])
    assertEquals("€", large["X1"])
    assertEquals("\u00A0", small["X0"])
    assertEquals("\u00A0", large["X0"])
  }

  @Test
  fun percentSign_isKeyedFromTheEndOfTheNumber() {
    assertEquals("%", keyMap("9%")["X0"])
    assertEquals("%", keyMap("99%")["X0"])
  }

  @Test
  fun currencyName_keysEachLetterOutwardFromTheNumber() {
    val letters = keyMap("1.00 US dollars")
    assertEquals(" ", letters["X0"])
    assertEquals("U", letters["X1"])
    assertEquals("s", letters["X10"])
  }

  @Test
  fun punctuationInsideCurrencyPrefix_isNotNumericStructure() {
    val text = "B/. 1,234.50"
    val slots = keyed(text)
    val map = slots.associate { it.key to it.char }

    assertUniqueKeys(slots)
    assertEquals(".", map["P1"])
    assertEquals("/", map["P2"])
    assertEquals("B", map["P3"])
    assertEquals(",", map["G3:,"])
    assertEquals(".", map["DEC:."])
    assertEquals("1", map["I0"])
    assertEquals("5", map["F0"])
    assertEquals(TokenKind.OTHER, slots.single { it.key == "P1" }.semanticKind)
    assertEquals(TokenKind.GROUP_SEPARATOR, slots.single { it.key == "G3:," }.semanticKind)
    assertEquals(TokenKind.DECIMAL_SEPARATOR, slots.single { it.key == "DEC:." }.semanticKind)
  }

  @Test
  fun punctuationInsideCurrencySuffix_isNotNumericStructure() {
    val text = "1,234.50 د.إ."
    val slots = keyed(text)

    assertUniqueKeys(slots)
    assertEquals(1, slots.count { it.semanticKind == TokenKind.DECIMAL_SEPARATOR })
    assertEquals(2, slots.count { it.char == "." && it.semanticKind == TokenKind.OTHER })
  }

  @Test
  fun hyphenInsideCurrencyName_isNotTheNumericSign() {
    val text = "-1,00 US-Dollar"
    val slots = TransitionLogic.layoutKeyedSlots(text, '.', ',', '-', line(text))

    assertUniqueKeys(slots)
    assertEquals(1, slots.count { it.semanticKind == TokenKind.SIGN })
    assertEquals("-", slots.single { it.semanticKind == TokenKind.SIGN }.char)
    assertEquals(1, slots.count { it.char == "-" && it.semanticKind == TokenKind.OTHER })
  }

  @Test
  fun affixesAndSigns_useValidatedDigitPhysics() {
    val slots = keyed("-\$1,234.50%")

    val sign = slots.single { it.key == "S" }
    val prefix = slots.single { it.key == "P0" }
    val suffix = slots.single { it.key == "X0" }
    assertEquals(TokenKind.DIGIT, sign.kind)
    assertEquals(TokenKind.DIGIT, prefix.kind)
    assertEquals(TokenKind.DIGIT, suffix.kind)
    assertEquals(TokenKind.SIGN, sign.semanticKind)
    assertEquals(TokenKind.OTHER, prefix.semanticKind)
    assertEquals(TokenKind.OTHER, suffix.semanticKind)

    val group = slots.single { it.key == "G3:," }
    val decimal = slots.single { it.key == "DEC:." }
    assertEquals(TokenKind.GROUP_SEPARATOR, group.kind)
    assertEquals(TokenKind.DECIMAL_SEPARATOR, decimal.kind)
  }

  @Test
  fun bidiDirectionalMarks_doNotCreateTransitionSlots() {
    val text = "\u061C-1"
    val slots = keyed(text)

    assertEquals(listOf("S", "I0"), slots.map { it.key })
    assertEquals(listOf("-", "1"), slots.map { it.char })
  }

  @Test
  fun tokenBoundsComeFromTheFullLineGeometry() {
    val slots = keyed("1,000")
    val comma = slots.first { it.key == "G3:," }
    assertEquals(1f, comma.leftFromLeft, 0.001f)
    assertEquals(2f, comma.rightFromLeft, 0.001f)
    assertEquals(5f, comma.totalWidth, 0.001f)
  }
}

class NumericRollEngineTest {

  private fun line(text: String): TextLineGeometry =
    TextLineGeometry(
      text = text,
      totalWidth = text.length * 10f,
      horizontals = FloatArray(text.length + 1) { it * 10f },
      layout = null,
      horizontalOrigin = 0f,
    )

  private fun slots(text: String): List<KeyedSlot> =
    TransitionLogic.layoutKeyedSlots(text, ',', '.', '-', line(text))

  private fun reset(engine: NumericRollEngine, text: String, rasterId: Int = 1) {
    engine.reset(
      layout = slots(text),
      text = text,
      lineHeight = 100f,
      rasterId = rasterId,
      blurLengthPx = 50f,
    )
  }

  private fun target(
    engine: NumericRollEngine,
    text: String,
    direction: Int,
    rasterId: Int,
  ) {
    engine.setTarget(
      layout = slots(text),
      text = text,
      direction = direction,
      lineHeight = 100f,
      animationDurationMs = 320L,
      rasterId = rasterId,
      blurLengthPx = 50f,
    )
  }

  private fun advance(engine: NumericRollEngine, frames: Int = 1) {
    repeat(frames) { engine.step(0.01f) }
  }

  @Test
  fun increment_entersFromAboveAndExitsBelow() {
    val engine = NumericRollEngine()
    reset(engine, "5")
    target(engine, "6", direction = 1, rasterId = 2)
    advance(engine)

    val samples = engine.samples()
    assertTrue(samples.first { it.ch == "6" }.offsetY < 0f)
    assertTrue(samples.first { it.ch == "5" }.offsetY > 0f)
  }

  @Test
  fun decrement_entersFromBelowAndExitsAbove() {
    val engine = NumericRollEngine()
    reset(engine, "6")
    target(engine, "5", direction = -1, rasterId = 2)
    advance(engine)

    val samples = engine.samples()
    assertTrue(samples.first { it.ch == "5" }.offsetY > 0f)
    assertTrue(samples.first { it.ch == "6" }.offsetY < 0f)
  }

  @Test
  fun movingEntriesKeepHistoricalAndTargetRasterIdsAlive() {
    val engine = NumericRollEngine()
    reset(engine, "5", rasterId = 10)
    target(engine, "6", direction = 1, rasterId = 11)
    advance(engine)

    assertEquals(setOf(10, 11), engine.referencedRasterIds())
  }

  @Test
  fun outgoingEntryCarriesBlurWhileSettledEntryDoesNot() {
    val engine = NumericRollEngine()
    reset(engine, "5")
    target(engine, "6", direction = 1, rasterId = 2)
    advance(engine, frames = 5)

    val samples = engine.samples()
    assertTrue(samples.first { it.ch == "5" }.blurLengthPx > 0f)
  }

  @Test
  fun sameDirectionRetargetKeepsIntermediateHistoryVisible() {
    val engine = NumericRollEngine()
    reset(engine, "5")
    target(engine, "6", direction = 1, rasterId = 2)
    advance(engine, frames = 4)

    target(engine, "7", direction = 1, rasterId = 3)
    advance(engine)

    val chars = engine.samples().map { it.ch }.toSet()
    assertTrue("5" in chars)
    assertTrue("6" in chars)
    assertTrue("7" in chars)
  }

  @Test
  fun changedStructuralGlyph_rollsThroughTheSameColumn() {
    val oldSlots = slots("\$1")
    val newSlots = slots("€1")
    assertEquals(
      oldSlots.single { it.char == "$" }.key,
      newSlots.single { it.char == "€" }.key,
    )

    val engine = NumericRollEngine()
    reset(engine, "\$1")
    target(engine, "€1", direction = 1, rasterId = 2)
    advance(engine)

    val samples = engine.samples().filter { it.key == "P0" }
    assertTrue(samples.any { it.ch == "€" })
    assertTrue(samples.any { it.ch == "$" })
    assertTrue(samples.first { it.ch == "$" }.blurLengthPx > 0f)
    assertTrue(samples.first { it.ch == "€" }.offsetY < 0f)
    assertTrue(samples.first { it.ch == "$" }.offsetY > 0f)
  }

  @Test
  fun snapToTargetLeavesOneStableTargetGlyph() {
    val engine = NumericRollEngine()
    reset(engine, "5")
    target(engine, "6", direction = 1, rasterId = 2)
    engine.snapToTarget()

    val samples = engine.samples()
    assertEquals(1, samples.size)
    assertEquals("6", samples.single().ch)
    assertEquals(1f, samples.single().alpha, 0.001f)
    assertTrue(samples.single().stable)
  }
}
