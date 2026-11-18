package com.numerictext

import org.junit.Assert.assertTrue
import org.junit.Test

class UsdCodeRepeatTransitionTest {

  // A fake clock, so the format wave's delays elapse exactly as each test says.
  private var now = 1_000_000_000L

  private fun engine() = NumericRollEngine().also { it.clockNanos = { now } }

  private fun NumericRollEngine.run(ms: Long, frames: Int) {
    repeat(frames) {
      now += ms * 1_000_000L
      step(ms / 1000f)
    }
  }

  /** The letter's strongest glyph is opaque and at rest, i.e. it reads as settled on screen. */
  private fun letterLooksSettled(samples: List<NumericRollEngine.GlyphSample>, key: String): Boolean {
    val glyph = samples.filter { it.key == key }.maxByOrNull { it.alpha } ?: return false
    return glyph.alpha >= 0.99f && glyph.blurLengthPx < 1f && kotlin.math.abs(glyph.offsetY) < 1f
  }

  private fun letterIsRolling(samples: List<NumericRollEngine.GlyphSample>, key: String): Boolean =
    samples.any { it.key == key && !it.stable && (it.blurLengthPx > 0f || it.offsetY != 0f) }

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

    val engine = engine()
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

    // 540 ms in: the letters read as settled, and the sign's outgoing ghost is still on screen.
    engine.run(20L, 27)

    val beforeReturn = engine.samples()
    for (key in listOf("P2", "P1", "P0")) {
      assertTrue("$key should look settled before the second transition", letterLooksSettled(beforeReturn, key))
    }
    val ghost = beforeReturn.single { it.key == "S" }
    assertTrue("the old sign should still be fading out", ghost.alpha in 0.001f..0.1f)

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

    engine.run(20L, 12)

    val duringReturn = engine.samples()
    for (key in listOf("P2", "P1", "P0")) {
      assertTrue(
        "$key must re-enter the unified format roll on every sign transition",
        letterIsRolling(duringReturn, key),
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

    val engine = engine()
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
      engine.run(20L, 1)
      val samples = engine.samples()
      // The negative value must have arrived: the sign and the new digit fully in, the letters still.
      val arrived =
        samples.any { it.key == "S" && it.ch == "-" && it.alpha >= 0.99f } &&
          samples.filter { it.key == "I0" }.maxByOrNull { it.alpha }?.ch == "9"
      val usdSettled = listOf("P2", "P1", "P0").all { letterLooksSettled(samples, it) }
      if (engine.isRunning && arrived && usdSettled) {
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

    engine.run(20L, 12)

    val duringPositiveReturn = engine.samples()
    for (key in listOf("P2", "P1", "P0")) {
      assertTrue(
        "$key must roll again instead of reusing the just-superseded current glyph",
        letterIsRolling(duringPositiveReturn, key),
      )
    }
  }
}
