package com.numerictext

import org.junit.Assert.assertEquals
import org.junit.Test

class NumericTextFontsTest {

  // --- Weight parsing ---

  @Test
  fun weight_keywords() {
    assertEquals(400, NumericTextFonts.weightOf("normal"))
    assertEquals(700, NumericTextFonts.weightOf("bold"))
  }

  @Test
  fun weight_numericStrings() {
    assertEquals(100, NumericTextFonts.weightOf("100"))
    assertEquals(500, NumericTextFonts.weightOf("500"))
    assertEquals(900, NumericTextFonts.weightOf("900"))
  }

  @Test
  fun weight_garbageFallsBackToRegular() {
    assertEquals(400, NumericTextFonts.weightOf(""))
    assertEquals(400, NumericTextFonts.weightOf("heavy-ish"))
  }

  @Test
  fun weight_outOfRangeClamps() {
    assertEquals(100, NumericTextFonts.weightOf("50"))
    assertEquals(900, NumericTextFonts.weightOf("1200"))
  }

  // --- Choosing a cut ---
  // Every bundled weight is a real file, so an exact request must never be synthesised from
  // a neighbour.

  @Test
  fun assetPath_exactWeightsMapToTheirOwnFile() {
    val expected = mapOf(
      100 to "Thin", 200 to "ExtraLight", 300 to "Light", 400 to "Regular", 500 to "Medium",
      600 to "SemiBold", 700 to "Bold", 800 to "ExtraBold", 900 to "Black",
    )
    for ((weight, name) in expected) {
      assertEquals("fonts/SunghyunSans-$name.ttf", NumericTextFonts.assetPathFor(weight))
    }
  }

  @Test
  fun assetPath_inBetweenWeightsTakeTheNearestCut() {
    assertEquals("fonts/SunghyunSans-Regular.ttf", NumericTextFonts.assetPathFor(420))
    assertEquals("fonts/SunghyunSans-Medium.ttf", NumericTextFonts.assetPathFor(480))
  }

  @Test
  fun assetPath_tiesRoundUp() {
    // 450 sits exactly between Regular and Medium; CSS resolves such a tie upward.
    assertEquals("fonts/SunghyunSans-Medium.ttf", NumericTextFonts.assetPathFor(450))
  }
}
