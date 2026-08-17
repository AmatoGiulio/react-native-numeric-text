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

  @Test
  fun repeatedSettledFormatChange_startsFromCanonicalTargetState() {
    val negative =
      listOf(
        slot("S", "-", TokenKind.SIGN, center = 10f),
        slot("P0", "U", TokenKind.OTHER, center = 30f),
        slot("I0", "9", TokenKind.DIGIT, center = 75f),
      )
    val positive =
      listOf(
        slot("P0", "U", TokenKind.OTHER, center = 20f),
        slot("I0", "0", TokenKind.DIGIT, center = 80f),
      )

    val engine = NumericRollEngine()
    engine.reset(
      layout = negative,
      text = "-U9",
      lineHeight = 100f,
      rasterId = 1,
      blurLengthPx = 50f,
    )

    fun target(layout: List<KeyedSlot>, text: String, direction: Int, rasterId: Int) {
      engine.setTarget(
        layout = layout,
        text = text,
        direction = direction,
        lineHeight = 100f,
        animationDurationMs = 320L,
        rasterId = rasterId,
        blurLengthPx = 50f,
      )
    }

    fun finishUntilVisuallySettledButStillRunning(): Boolean {
      Thread.sleep(240L)
      repeat(120) {
        engine.step(0.016f)
        val samples = engine.samples()
        if (
          engine.isRunning &&
            samples.isNotEmpty() &&
            samples.all { it.stable && it.alpha >= 0.99f }
        ) {
          return true
        }
      }
      return false
    }

    target(positive, "U0", direction = 1, rasterId = 2)
    assertTrue(finishUntilVisuallySettledButStillRunning())

    target(negative, "-U9", direction = -1, rasterId = 3)
    assertTrue(finishUntilVisuallySettledButStillRunning())

    // This is the second A -> B transition from the USD recording. The previous frame is already
    // visually settled, so hidden historical entries must be collapsed before scheduling the new
    // per-glyph wave. U therefore receives a fresh old/new roll instead of remaining as a static
    // full-opacity anchor while only the digit changes.
    target(positive, "U0", direction = 1, rasterId = 4)
    Thread.sleep(130L)
    engine.step(0.016f)

    val uSamples = engine.samples().filter { it.key == "P0" && it.ch == "U" }
    assertTrue(uSamples.size >= 2)
    assertTrue(uSamples.any { it.blurLengthPx > 0f || it.offsetY != 0f })
  }

  @Test
  fun rapidFormatTaps_dropTargetsThatNeverReachedTheScreen() {
    val negative =
      listOf(
        slot("S", "-", TokenKind.SIGN, center = 10f),
        slot("P0", "U", TokenKind.OTHER, center = 30f),
        slot("I0", "9", TokenKind.DIGIT, center = 75f),
      )
    val positive =
      listOf(
        slot("P0", "U", TokenKind.OTHER, center = 20f),
        slot("I0", "0", TokenKind.DIGIT, center = 80f),
      )

    val engine = NumericRollEngine()
    engine.reset(
      layout = negative,
      text = "-U9",
      lineHeight = 100f,
      rasterId = 1,
      blurLengthPx = 50f,
    )

    fun target(layout: List<KeyedSlot>, text: String, direction: Int, rasterId: Int) {
      engine.setTarget(
        layout = layout,
        text = text,
        direction = direction,
        lineHeight = 100f,
        animationDurationMs = 320L,
        rasterId = rasterId,
        blurLengthPx = 50f,
      )
    }

    // No frame is advanced between these requests, so the intermediate positive targets have never
    // been visible. The final request is the same layout that is physically still on screen.
    target(positive, "U0", direction = 1, rasterId = 2)
    target(negative, "-U9", direction = -1, rasterId = 3)
    target(positive, "U0", direction = 1, rasterId = 4)
    target(negative, "-U9", direction = -1, rasterId = 5)

    Thread.sleep(260L)
    engine.step(0.016f)

    val samples = engine.samples()
    assertEquals(3, samples.size)
    assertEquals("-", samples.single { it.key == "S" }.ch)
    assertEquals("U", samples.single { it.key == "P0" }.ch)
    assertEquals("9", samples.single { it.key == "I0" }.ch)
    assertTrue(samples.all { it.stable })
  }

  @Test
  fun reversingAfterSignRemovalStarted_revivesTheOutgoingGlyph() {
    val negative =
      listOf(
        slot("S", "-", TokenKind.SIGN, center = 10f),
        slot("P0", "U", TokenKind.OTHER, center = 30f),
        slot("I0", "9", TokenKind.DIGIT, center = 75f),
      )
    val positive =
      listOf(
        slot("P0", "U", TokenKind.OTHER, center = 20f),
        slot("I0", "0", TokenKind.DIGIT, center = 80f),
      )

    val engine = NumericRollEngine()
    engine.reset(
      layout = negative,
      text = "-U9",
      lineHeight = 100f,
      rasterId = 1,
      blurLengthPx = 50f,
    )

    engine.setTarget(
      layout = positive,
      text = "U0",
      direction = 1,
      lineHeight = 100f,
      animationDurationMs = 320L,
      rasterId = 2,
      blurLengthPx = 50f,
    )

    // The sign is the first visual event in this fixture; after the format onset it has committed to
    // EXIT while the rest of the wave is still in flight.
    Thread.sleep(130L)
    engine.step(0.016f)
    assertEquals(NumericRollEngine.GlyphRole.EXIT, engine.samples().first { it.key == "S" }.role)

    engine.setTarget(
      layout = negative,
      text = "-U9",
      direction = -1,
      lineHeight = 100f,
      animationDurationMs = 320L,
      rasterId = 3,
      blurLengthPx = 50f,
    )
    engine.step(0.016f)

    val sign = engine.samples().first { it.key == "S" && it.ch == "-" }
    assertEquals(NumericRollEngine.GlyphRole.ENTER, sign.role)
    assertTrue(sign.blurLengthPx > 0f || sign.offsetY != 0f)
  }
}
