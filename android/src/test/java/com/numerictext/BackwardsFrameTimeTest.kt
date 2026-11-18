package com.numerictext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackwardsFrameTimeTest {

  private fun digit(key: String, char: String, center: Float): KeyedSlot =
    KeyedSlot(
      key = key,
      kind = TokenKind.DIGIT,
      semanticKind = TokenKind.DIGIT,
      char = char,
      centerFromLeft = center,
      totalWidth = 60f,
      leftFromLeft = center - 5f,
      rightFromLeft = center + 5f,
      utf16Start = 0,
      utf16End = char.length,
    )

  private fun snapshot(engine: NumericRollEngine): List<String> =
    engine.samples().map { "${it.key}:${it.ch}:${it.alpha}:${it.blurLengthPx}" }

  private fun step(engine: NumericRollEngine, dt: Float) {
    Thread.sleep(20L)
    engine.step(dt)
  }

  @Test
  fun backwardsFrameTime_holdsTheRollInPlace() {
    val engine = NumericRollEngine()
    engine.reset(
      layout = listOf(digit("I1", "1", 10f), digit("I0", "2", 30f)),
      text = "12",
      lineHeight = 100f,
      rasterId = 1,
      blurLengthPx = 50f,
    )
    engine.setTarget(
      layout = listOf(digit("I1", "3", 10f), digit("I0", "4", 30f)),
      text = "34",
      direction = 1,
      lineHeight = 100f,
      animationDurationMs = 320L,
      rasterId = 2,
      blurLengthPx = 50f,
    )

    // Run until the first digit is mid-roll: both "1" and "3" are on screen. Its change is due
    // 75 ms after setTarget (half of WAVE_TOTAL_SECONDS for two changing digits), and step() reads
    // that against System.nanoTime(), so five 20 ms sleeps are needed to be past it on any machine.
    repeat(5) { step(engine, 0.016f) }
    val midRoll = snapshot(engine)
    assertTrue("3 should be entering", engine.samples().any { it.key == "I1" && it.ch == "3" })

    // A frame clock that steps backwards gives a negative dt. The alpha and blur springs are
    // explicit Euler steps, so a negative dt ran them in reverse: the entering "3" was culled and
    // the old "1" came back fully blurred. A backwards frame must leave the roll where it is.
    repeat(4) { step(engine, -0.5f) }
    assertEquals(midRoll, snapshot(engine))

    // Normal frames afterwards keep rolling, with finite values that drawRolling can round.
    repeat(60) { i ->
      step(engine, 0.016f)
      for (sample in engine.samples()) {
        assertTrue("frame $i: alpha of ${sample.key} is ${sample.alpha}", sample.alpha.isFinite())
        assertTrue(
          "frame $i: blur of ${sample.key} is ${sample.blurLengthPx}",
          sample.blurLengthPx.isFinite(),
        )
      }
    }
    val settled = engine.samples().filter { it.alpha > 0.5f }.map { it.ch }
    assertEquals(listOf("3", "4"), settled)
  }
}
