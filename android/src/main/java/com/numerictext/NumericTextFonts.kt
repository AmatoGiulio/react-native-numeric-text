package com.numerictext

import android.content.res.AssetManager
import android.graphics.Paint
import android.graphics.Typeface

/**
 * The bundled default typeface.
 *
 * SwiftUI's numericText renders in SF Pro, which cannot be shipped. Android's own default is
 * Roboto, which is further from it than Sunghyun Sans — an OFL-licensed rounded face built as an
 * open SF Pro Rounded. It ships subsetted to the glyphs a formatted number uses; see
 * `.agent/tools/subset_font.sh`.
 */
internal object NumericTextFonts {
  const val BUNDLED = "sunghyun-sans"

  /** The name that opts back out to whatever the platform would have used. */
  const val SYSTEM = "system"

  // Nine static files rather than one variable font: the upstream release has no variable build.
  private val WEIGHT_FILES = listOf(
    100 to "Thin", 200 to "ExtraLight", 300 to "Light", 400 to "Regular", 500 to "Medium",
    600 to "SemiBold", 700 to "Bold", 800 to "ExtraBold", 900 to "Black",
  )

  private val cache = HashMap<String, Typeface?>()

  /**
   * Resolves a CSS-ish weight ("bold", "normal", "100".."900") to the nearest bundled file.
   * Unlike [Typeface.create], which can only synthesise NORMAL and BOLD, every step is a real cut.
   */
  fun weightOf(fontWeight: String): Int = when (fontWeight) {
    "normal" -> 400
    "bold" -> 700
    else -> fontWeight.toIntOrNull()?.coerceIn(100, 900) ?: 400
  }

  /** Nearest available cut; ties round up, matching how CSS resolves a missing weight upward. */
  fun assetPathFor(weight: Int): String {
    var best = WEIGHT_FILES.first()
    for (candidate in WEIGHT_FILES) {
      val d = Math.abs(candidate.first - weight)
      val bd = Math.abs(best.first - weight)
      if (d < bd || (d == bd && candidate.first > best.first)) best = candidate
    }
    return "fonts/SunghyunSans-${best.second}.ttf"
  }

  /**
   * The bundled face at [weight], or null when the asset cannot be read — a caller that gets null
   * must fall back rather than fail, since a missing asset should degrade to the system font.
   */
  fun bundled(assets: AssetManager, weight: Int): Typeface? {
    val path = assetPathFor(weight)
    return cache.getOrPut(path) {
      try {
        Typeface.createFromAsset(assets, path)
      } catch (_: RuntimeException) {
        null
      }
    }
  }

  /**
   * Whether [paint] can draw every character of [text].
   *
   * The bundled face covers Latin-script number formatting only. A locale that formats with
   * Arabic-Indic or Devanagari digits would otherwise draw tofu, so the view asks this before
   * committing to the bundled typeface and keeps the system one when the answer is no.
   */
  fun canRender(paint: Paint, text: String): Boolean {
    var i = 0
    while (i < text.length) {
      val cp = text.codePointAt(i)
      val chars = Character.charCount(cp)
      if (!paint.hasGlyph(text.substring(i, i + chars))) return false
      i += chars
    }
    return true
  }
}
