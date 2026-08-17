package com.numerictext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsdCodeRepeatTransitionTest {

  private fun slot(
    key: String,
    char: String,
    semanticKind: TokenKind,
    center: Float,
  ): KeyedSlot =
    KeyedSlot(
      key = key,
      kind =
        if (semanticKind == TokenKind.SIGN || semanticKind == TokenKind.OTHER) {
          TokenKind.DIGIT
        } else {
          semanticKind
        },
      semanticKind = semanticKind,
      char = char,
      centerFromLeft = center,
      totalWidth = 140f,
      leftFromLeft = center - 5f,
      rightFromLeft = center + 5f,
      utf16Start = 0,
      utf16End = char.length,
    )

  @Test
  fun secondPositiveToNegative_rollsUsdLettersWhileOldSignGhostIsStillExiting() {
    // Put the sign at the end of this synthetic visual layout so U/S/D can be fully settled while
    // the sign's outgoing entry is still above the render threshold. This deterministically
    // reproduces the real USD-code failure: a fading sign ghost must be reversible, but must not
    // make the active positive topology look as though it still contains a sign.
    val negative =
      listOf(
        slot("P2", "U", TokenKind.OTHER, 10f),
        slot("P1", "S", TokenKind.OTHER, 30f),
        slot("P0", "D", TokenKind.OTHER, 50f),
        slot("I0", "1", TokenKind.DIGIT, 80f),
        slot("S", "-", TokenKind.SIGN, 110f),
      )
    val positive = negative.filterNot { it.key == "S" }

    val engine = NumericRollEngine()
    engine.reset(
      layout = negative,
      text = "USD 1-",
      lineHeight = 100f,
      rasterId = 1,
      blurLengthPx = 50f,
    )

    engine.setTarget(
      layout = positive,
      text = "USD 1",
      direction = 1,
      lineHeight = 100f,
      animationDurationMs = 320L,
      rasterId = 2,
      blurLengthPx = 50f,
    )

    repeat(25) {
      Thread.sleep(20L)
      engine.step(0.020f)
    }

    val beforeReturn = engine.samples()
    for (key in listOf("P2", "P1", "P0")) {
      val letter = beforeReturn.single { it.key == key }
      assertTrue("$key should be settled before the second transition", letter.stable)
    }
    assertEquals(
      NumericRollEngine.GlyphRole.EXIT,
      beforeReturn.first { it.key == "S" }.role,
    )

    // This is a second USD sign transition. The old '-' is still a fading EXIT. It must be
    // available for reversal without being counted as active topology; otherwise formatGeometrySplit
    // becomes false and the unchanged U/S/D glyphs incorrectly remain anchors.
    engine.setTarget(
      layout = negative,
      text = "USD 1-",
      direction = -1,
      lineHeight = 100f,
      animationDurationMs = 320L,
      rasterId = 3,
      blurLengthPx = 50f,
    )

    repeat(11) {
      Thread.sleep(20L)
      engine.step(0.020f)
    }

    val duringReturn = engine.samples()
    for (key in listOf("P2", "P1", "P0")) {
      val letterSamples = duringReturn.filter { it.key == key }
      assertTrue(
        "$key must re-enter the unified format roll on every sign transition",
        letterSamples.any {
          !it.stable && (it.blurLengthPx > 0f || it.offsetY != 0f)
        },
      )
    }
  }

  @Test
  fun negativeToPositive_afterVisibleLettersSettle_doesNotSelfReuseCurrentUsdGlyphs() {
    val positive =
      listOf(
        slot("P2", "U", TokenKind.OTHER, 20f),
        slot("P1", "S", TokenKind.OTHER, 40f),
        slot("P0", "D", TokenKind.OTHER, 60f),
        slot("I0", "1", TokenKind.DIGIT, 100f),
      )
    val negative =
      listOf(
        slot("S", "-", TokenKind.SIGN, 10f),
        slot("P2", "U", TokenKind.OTHER, 30f),
        slot("P1", "S", TokenKind.OTHER, 50f),
        slot("P0", "D", TokenKind.OTHER, 70f),
        slot("I0", "9", TokenKind.DIGIT, 110f),
      )

    val engine = NumericRollEngine()
    engine.reset(
      layout = positive,
      text = "USD 1",
      lineHeight = 100f,
      rasterId = 1,
      blurLengthPx = 50f,
    )

    engine.setTarget(
      layout = negative,
      text = "-USD 9",
      direction = -1,
      lineHeight = 100f,
      animationDurationMs = 320L,
      rasterId = 2,
      blurLengthPx = 50f,
    )

    // Reproduce the failing window in usd-2.webm: the visible USD letters have already converged,
    // but hidden superseded entries keep the engine alive, so the following value change is a real
    // reversal rather than a canonical fresh start.
    var reachedWindow = false
    for (step in 0 until 80) {
      Thread.sleep(20L)
      engine.step(0.020f)
      val samples = engine.samples()
      val usdSettled =
        listOf("P2", "P1", "P0").all { key ->
          val visible = samples.filter { it.key == key }
          visible.size == 1 && visible.single().stable
        }
      if (engine.isRunning && usdSettled) {
        reachedWindow = true
        break
      }
    }
    assertTrue("fixture must reach visually-settled USD while engine history is still active", reachedWindow)

    engine.setTarget(
      layout = positive,
      text = "USD 1",
      direction = 1,
      lineHeight = 100f,
      animationDurationMs = 320L,
      rasterId = 3,
      blurLengthPx = 50f,
    )

    repeat(9) {
      Thread.sleep(20L)
      engine.step(0.020f)
    }

    val duringPositiveReturn = engine.samples()
    for (key in listOf("P2", "P1", "P0")) {
      val letterSamples = duringPositiveReturn.filter { it.key == key }
      assertTrue(
        "$key must roll again instead of reusing the just-superseded current glyph",
        letterSamples.any {
          !it.stable && (it.blurLengthPx > 0f || it.offsetY != 0f)
        },
      )
    }
  }
}
