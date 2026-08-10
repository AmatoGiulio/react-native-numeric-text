package com.numerictext

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class RasterSlice(
  val source: Rect,
  val anchorX: Float,
)

internal class NumericTextRaster(
  val id: Int,
  val text: String,
  val bitmap: Bitmap,
  val baseline: Float,
  val lineWidth: Float,
  private val slices: Map<String, RasterSlice>,
) {
  fun slice(key: String): RasterSlice? = slices[key]
}

/** One immutable raster of the fully-shaped destination line, partitioned into keyed slices. */
internal object NumericTextRasterizer {
  fun rasterize(
    id: Int,
    line: TextLineGeometry,
    slots: List<KeyedSlot>,
    lineHeight: Float,
    ascent: Float,
  ): NumericTextRaster {
    val bleed = ceil(lineHeight * 0.80f).toInt().coerceAtLeast(1)
    val linePixels = ceil(line.totalWidth).toInt().coerceAtLeast(1)
    val width = linePixels + bleed * 2
    val height = ceil(lineHeight).toInt().coerceAtLeast(1) + bleed * 2
    val baseline = bleed - ascent

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setDensity(Bitmap.DENSITY_NONE)

    val layout = line.layout
    if (layout != null && line.text.isNotEmpty()) {
      val canvas = Canvas(bitmap)
      canvas.save()
      canvas.translate(
        bleed - line.horizontalOrigin,
        baseline - layout.getLineBaseline(0),
      )
      layout.draw(canvas)
      canvas.restore()
    }

    return NumericTextRaster(
      id = id,
      text = line.text,
      bitmap = bitmap,
      baseline = baseline,
      lineWidth = line.totalWidth,
      slices = partition(slots, bleed, width, height),
    )
  }

  private fun partition(
    slots: List<KeyedSlot>,
    originX: Int,
    bitmapWidth: Int,
    bitmapHeight: Int,
  ): Map<String, RasterSlice> {
    if (slots.isEmpty()) return emptyMap()

    val visual = slots.sortedBy { it.leftFromLeft }
    val boundaries = IntArray(visual.size + 1)
    boundaries[0] = 0
    boundaries[visual.size] = bitmapWidth

    for (i in 1 until visual.size) {
      val previous = visual[i - 1]
      val next = visual[i]
      val join = (previous.rightFromLeft + next.leftFromLeft) * 0.5f
      boundaries[i] = (originX + join).roundToInt().coerceIn(0, bitmapWidth)
    }

    for (i in 1 until boundaries.size) {
      if (boundaries[i] < boundaries[i - 1]) boundaries[i] = boundaries[i - 1]
    }

    val result = HashMap<String, RasterSlice>(slots.size)
    for (i in visual.indices) {
      val slot = visual[i]
      val left = boundaries[i]
      val right = boundaries[i + 1]
      if (right <= left) continue

      val anchorInBitmap = originX + slot.centerFromLeft
      result[slot.key] = RasterSlice(
        source = Rect(left, 0, right, bitmapHeight),
        anchorX = anchorInBitmap - left,
      )
    }

    return result
  }
}
