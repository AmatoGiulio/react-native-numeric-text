package com.numerictext

import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Geometry extracted from one fully-shaped single-line text layout. */
data class TextLineGeometry(
  val text: String,
  val totalWidth: Float,
  private val horizontals: FloatArray,
  internal val layout: StaticLayout?,
  internal val horizontalOrigin: Float,
) {
  fun horizontalAt(utf16Offset: Int): Float =
    horizontals[utf16Offset.coerceIn(0, horizontals.lastIndex)]

  /**
   * Returns the visual cell occupied by one logical text range.
   *
   * A bidi boundary can have two valid caret positions. `getPrimaryHorizontal(start/end)` chooses
   * only one of them, so pairing those two carets can cut directly through the glyph next to an
   * RTL/LTR boundary. Android's selection path is range-aware and follows the shaped visual run,
   * which is exactly the geometry the raster partition needs.
   */
  fun visualBounds(utf16Start: Int, utf16End: Int): Pair<Float, Float> {
    val start = utf16Start.coerceIn(0, text.length)
    val end = utf16End.coerceIn(start, text.length)
    val shaped = layout

    if (shaped != null && end > start) {
      val path = Path()
      shaped.getSelectionPath(start, end, path)
      val bounds = RectF()
      path.computeBounds(bounds, true)

      val left = bounds.left - horizontalOrigin
      val right = bounds.right - horizontalOrigin
      if (left.isFinite() && right.isFinite() && right > left) {
        return left to right
      }
    }

    // JVM tests and zero-width control characters do not have a useful selection rectangle.
    val a = horizontalAt(start)
    val b = horizontalAt(end)
    return min(a, b) to max(a, b)
  }
}

/**
 * Typesets the complete formatted number before TransitionLogic splits it into animated slots.
 *
 * This keeps kerning, punctuation bearings, fallback shaping and bidi placement in the same line
 * layout that produced the final width. The animation engine still receives independent slots; only
 * their geometry now comes from the finished line rather than from summed per-character advances.
 */
internal object NumericTextTypesetter {
  fun typeset(text: String, paint: TextPaint): TextLineGeometry {
    if (text.isEmpty()) {
      return TextLineGeometry(text, 0f, FloatArray(1), null, 0f)
    }

    val layoutPaint = TextPaint(paint).apply { color = Color.WHITE }
    val desiredWidth = Layout.getDesiredWidth(text, layoutPaint).coerceAtLeast(0f)
    val layoutWidth = ceil(desiredWidth).toInt().coerceAtLeast(1)

    val layout =
      StaticLayout.Builder.obtain(text, 0, text.length, layoutPaint, layoutWidth)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .setMaxLines(1)
        .build()

    val raw = FloatArray(text.length + 1)
    for (offset in raw.indices) {
      raw[offset] = layout.getPrimaryHorizontal(offset)
    }

    var left = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    for (x in raw) {
      left = min(left, x)
      right = max(right, x)
    }

    if (!left.isFinite() || !right.isFinite()) {
      left = 0f
      right = desiredWidth
    }

    for (index in raw.indices) {
      raw[index] -= left
    }

    val boundaryWidth = (right - left).coerceAtLeast(0f)
    val lineWidth = layout.getLineWidth(0).coerceAtLeast(0f)
    val totalWidth = max(boundaryWidth, lineWidth)

    return TextLineGeometry(text, totalWidth, raw, layout, left)
  }
}
