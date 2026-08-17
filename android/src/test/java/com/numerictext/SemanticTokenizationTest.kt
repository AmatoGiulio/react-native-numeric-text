package com.numerictext

import org.junit.Assert.assertEquals
import org.junit.Test

class SemanticTokenizationTest {
  private fun line(text: String): TextLineGeometry =
    TextLineGeometry(
      text = text,
      totalWidth = text.length.toFloat(),
      horizontals = FloatArray(text.length + 1) { it.toFloat() },
      layout = null,
      horizontalOrigin = 0f,
    )

  private fun span(text: String, needle: String, kind: NumericFieldKind, occurrence: Int = 0): NumericSemanticSpan {
    var from = 0
    var start = -1
    repeat(occurrence + 1) {
      start = text.indexOf(needle, from)
      require(start >= 0) { "Missing '$needle' in '$text'" }
      from = start + needle.length
    }
    return NumericSemanticSpan(start, start + needle.length, kind)
  }

  private fun keyed(
    text: String,
    group: Char,
    decimal: Char,
    spans: List<NumericSemanticSpan>,
  ): List<KeyedSlot> = TransitionLogic.layoutKeyedSlots(
    formatted = text,
    groupSep = group,
    decimalSep = decimal,
    minusSign = '-',
    line = line(text),
    semanticSpans = spans,
  )

  private fun assertUniqueKeys(slots: List<KeyedSlot>) {
    assertEquals(slots.size, slots.map { it.key }.toSet().size)
  }

  @Test
  fun panamaCurrencyPrefixDot_remainsAffixWhileNumericDotIsDecimal() {
    val text = "B/. 1,234.50"
    val spans = listOf(
      span(text, "1", NumericFieldKind.INTEGER),
      span(text, ",", NumericFieldKind.GROUP_SEPARATOR),
      span(text, "234", NumericFieldKind.INTEGER),
      span(text, ".", NumericFieldKind.DECIMAL_SEPARATOR, occurrence = 1),
      span(text, "50", NumericFieldKind.FRACTION),
    )
    val slots = keyed(text, ',', '.', spans)

    assertUniqueKeys(slots)
    assertEquals(1, slots.count { it.kind == TokenKind.DECIMAL_SEPARATOR })
    assertEquals(1, slots.count { it.char == "." && it.kind == TokenKind.OTHER })
    assertEquals(",", slots.single { it.key == "G3:," }.char)
  }

  @Test
  fun arabicCurrencySuffixDots_neverBecomeNumericDecimals() {
    val text = "1,234.50 د.إ."
    val spans = listOf(
      span(text, "1", NumericFieldKind.INTEGER),
      span(text, ",", NumericFieldKind.GROUP_SEPARATOR),
      span(text, "234", NumericFieldKind.INTEGER),
      span(text, ".", NumericFieldKind.DECIMAL_SEPARATOR),
      span(text, "50", NumericFieldKind.FRACTION),
    )
    val slots = keyed(text, ',', '.', spans)

    assertUniqueKeys(slots)
    assertEquals(1, slots.count { it.kind == TokenKind.DECIMAL_SEPARATOR })
    assertEquals(2, slots.count { it.char == "." && it.kind == TokenKind.OTHER })
  }

  @Test
  fun currencyNameHyphen_remainsAffixWhileLeadingMinusIsSign() {
    val text = "-1,00 US-Dollar"
    val spans = listOf(
      span(text, "-", NumericFieldKind.SIGN),
      span(text, "1", NumericFieldKind.INTEGER),
      span(text, ",", NumericFieldKind.DECIMAL_SEPARATOR),
      span(text, "00", NumericFieldKind.FRACTION),
    )
    val slots = keyed(text, '.', ',', spans)

    assertUniqueKeys(slots)
    assertEquals(1, slots.count { it.kind == TokenKind.SIGN })
    assertEquals(1, slots.count { it.char == "-" && it.kind == TokenKind.OTHER })
  }
}
