package com.numerictext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatGeometryPinTest {

  // The engine schedules format waves against its clock. A fake one makes every delay exact: time
  // only moves when a test says so, never because the machine running it was slow or fast.
  private var now = 1_000_000_000L

  private fun engine() = NumericRollEngine().also { it.clockNanos = { now } }

  private fun elapse(ms: Long) {
    now += ms * 1_000_000L
  }

  /** Advances the clock and the engine together, one frame of [ms] at a time. */
  private fun NumericRollEngine.run(ms: Long, frames: Int) {
    repeat(frames) {
      elapse(ms)
      step(ms / 1000f)
    }
  }

  /**
   * The screen shows exactly [layout], every glyph fully opaque and at rest, whether or not the
   * engine has let go yet.
   */
  private fun visuallySettledOn(
    samples: List<NumericRollEngine.GlyphSample>,
    layout: List<KeyedSlot>,
  ): Boolean =
    samples.map { it.key to it.ch }.toSet() == layout.map { it.key to it.char }.toSet() &&
      samples.all { it.alpha >= 0.99f && it.blurLengthPx < 1f && kotlin.math.abs(it.offsetY) < 1f }

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

    val engine = engine()
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

    elapse(220L)
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

    val engine = engine()
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

    // The engine stays running for a while after the glyphs look still, because faded ghosts are still
    // converging. Each transition below starts inside that window, the one the USD recording hit.
    fun finishUntilVisuallySettledButStillRunning(layout: List<KeyedSlot>): Boolean {
      repeat(120) {
        engine.run(16L, 1)
        if (engine.isRunning && visuallySettledOn(engine.samples(), layout)) return true
      }
      return false
    }

    target(positive, "U0", direction = 1, rasterId = 2)
    assertTrue(finishUntilVisuallySettledButStillRunning(positive))

    target(negative, "-U9", direction = -1, rasterId = 3)
    assertTrue(finishUntilVisuallySettledButStillRunning(negative))

    // This is the second A -> B transition from the USD recording. The previous frame is already
    // visually settled, so hidden historical entries must be collapsed before scheduling the new
    // per-glyph wave. U therefore receives a fresh old/new roll instead of remaining as a static
    // full-opacity anchor while only the digit changes.
    target(positive, "U0", direction = 1, rasterId = 4)
    engine.run(16L, 8)

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

    val engine = engine()
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

    elapse(260L)
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

    val engine = engine()
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
    // leaving and is fading out while the rest of the wave is still in flight.
    engine.run(10L, 13)
    val leaving = engine.samples().single { it.key == "S" }
    assertTrue("the sign should be fading out", leaving.alpha < 0.9f)
    assertTrue("the sign should be moving away", leaving.offsetY > 0f)

    engine.setTarget(
      layout = negative,
      text = "-U9",
      direction = -1,
      lineHeight = 100f,
      animationDurationMs = 320L,
      rasterId = 3,
      blurLengthPx = 50f,
    )

    // Reversing must bring that same outgoing glyph back, never drop it and spawn a fresh one.
    var lowest = leaving.alpha
    repeat(40) {
      engine.run(10L, 1)
      val sign = engine.samples().filter { it.key == "S" }
      assertEquals("the sign must stay on screen through the reversal", 1, sign.size)
      assertEquals("-", sign.single().ch)
      assertEquals("the outgoing sign must be revived, not replaced", leaving.renderId, sign.single().renderId)
      lowest = minOf(lowest, sign.single().alpha)
    }
    val returned = engine.samples().single { it.key == "S" }
    assertTrue("the sign kept fading for a moment before turning back", lowest < leaving.alpha)
    assertTrue("the sign should be back to nearly full opacity", returned.alpha > 0.9f)
  }
}
