package com.numerictext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The span `fractionColor` tints, read off the keys `layoutKeyedSlots` assigns. */
class FractionSpanTest {

  private fun line(text: String): TextLineGeometry =
    TextLineGeometry(
      text = text,
      totalWidth = text.length.toFloat(),
      horizontals = FloatArray(text.length + 1) { it.toFloat() },
      layout = null,
      horizontalOrigin = 0f,
    )

  private fun fractionSpan(text: String): String =
    TransitionLogic.layoutKeyedSlots(text, ',', '.', '-', line(text))
      .filter { TransitionLogic.isFractionKey(it.key) }
      .joinToString("") { it.char }

  @Test
  fun `tints the separator and the digits after it`() {
    assertEquals(".56", fractionSpan("1,234.56"))
  }

  @Test
  fun `leaves the integer part alone`() {
    assertEquals("", fractionSpan("1,234"))
  }

  @Test
  fun `includes a trailing affix`() {
    assertTrue(fractionSpan("1,234.56 kr").endsWith("kr"))
  }

  @Test
  fun `does not tint a leading currency symbol`() {
    assertFalse(fractionSpan("$1,234.56").contains("$"))
  }

  @Test
  fun `classifies keys directly`() {
    assertTrue(TransitionLogic.isFractionKey("F0"))
    assertTrue(TransitionLogic.isFractionKey("DEC:."))
    assertTrue(TransitionLogic.isFractionKey("X0"))
    assertFalse(TransitionLogic.isFractionKey("I0"))
    assertFalse(TransitionLogic.isFractionKey("P0"))
    assertFalse(TransitionLogic.isFractionKey("G3:,"))
  }
}
