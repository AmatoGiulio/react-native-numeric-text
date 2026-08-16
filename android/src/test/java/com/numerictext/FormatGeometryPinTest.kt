package com.numerictext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatGeometryPinTest {

  private fun slot(
    key: String,
    char: String,
    semanticKind: TokenKind,
    center: Float,
  ): KeyedSlot =
    KeyedSlot(
      key = key,
      kind = if (semanticKind == TokenKind.SIGN || semanticKind == TokenKind.OTHER) {
        TokenKind.DIGIT
      } else {
        semanticKind
      },
      semanticKind = semanticKind,
      char = char,
      centerFromLeft = center,
      totalWidth = 100f,
      leftFromLeft = center - 5f,
      rightFromLeft = center + 5f,
      utf16Start = 0,
      utf16End = char.length,
    )

  @Test
  fun valueUpdate_releasesCurrentFormatPinAndRestoresSuffixReflow() {
    val oldFormat =
      listOf(
        slot("P0", "$", TokenKind.OTHER, center = 20f),
        slot("I0", "1", TokenKind.DIGIT, center = 70f),
      )
    val eurSmall =
      listOf(
        slot("I0", "1", TokenKind.DIGIT, center = 30f),
        slot("X0", "€", TokenKind.OTHER, center = 80f),
      )
    val eurLarge =
      listOf(
        slot("I0", "1", TokenKind.DIGIT, center = 20f),
        slot("I1", "0", TokenKind.DIGIT, center = 40f),
        slot("X0", "€", TokenKind.OTHER, center = 90f),
      )

    val engine = NumericRollEngine()
    engine.reset(
      layout = oldFormat,
      text = "$1",
      lineHeight = 100f,
      rasterId = 1,
      blurLengthPx = 50f,
    )
    engine.setTarget(
      layout = eurSmall,
      text = "1€",
      direction = 1,
      lineHeight = 100f,
      animationDurationMs = 320L,
      rasterId = 2,
      blurLengthPx = 50f,
    )

    Thread.sleep(220L)
    engine.step(0.01f)

    val pinnedEuro = engine.samples().first { it.ch == "€" }
    assertEquals(30f, pinnedEuro.x, 0.01f)

    // A value-only update in the same EUR format must release the temporary format pin. The suffix
    // starts from the exact X already on screen, then resumes the original horizontal reflow as the
    // numeric text grows.
    engine.setTarget(
      layout = eurLarge,
      text = "10€",
      direction = 1,
      lineHeight = 100f,
      animationDurationMs = 320L,
      rasterId = 3,
      blurLengthPx = 50f,
    )

    val beforeStep = engine.samples().first { it.ch == "€" }
    assertEquals(30f, beforeStep.x, 0.01f)

    engine.step(0.016f)

    val movingEuro = engine.samples().first { it.ch == "€" }
    assertTrue(movingEuro.x > 30f)
    assertTrue(movingEuro.x < 40f)
  }
}
