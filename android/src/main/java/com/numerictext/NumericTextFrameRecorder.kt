package com.numerictext

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * GROUND TRUTH, Android side — the counterpart of `NumericTextFrameRecorder` in
 * `ios/NumericTextSwiftUIHost.swift`, writing the same format so `.agent/tools/ground_truth.py`
 * reads both without knowing which platform produced a run.
 *
 * See `.agent/IOS_GROUND_TRUTH.md` for why this exists. In short: every parity number until now was
 * inferred from a screen recording — variable frame rate, resampled, compressed, with t=0 found by
 * hunting for a sync flash — and the resulting run-to-run spread was as large as the differences
 * being tuned. On iOS the same recorder brought that spread to zero.
 *
 * One difference from iOS, and it is in this side's favour. There the frames had to be re-rendered
 * from the layer on a display-link tick, because SwiftUI's drawing is closed. Here the capture
 * happens inside `onDraw`, so what is recorded is exactly what was drawn, on the frame it was
 * drawn, with no possibility of sampling a state one tick early or late.
 *
 * Switched on by creating a marker file, so toggling it needs no rebuild:
 *
 *     adb shell touch /sdcard/Android/data/<pkg>/files/numerictext-record.on
 *     adb pull /sdcard/Android/data/<pkg>/files/numerictext-record
 */
object NumericTextFrameRecorder {

  /** Captured area, as a fraction of the view's own bounds — the number leaves its box. */
  private const val MARGIN = 0.35f
  /** How long after the last value change to keep the recording open, in ms. */
  private const val TAIL_MS = 1600L

  private var checkedEnabled = false
  private var enabled = false

  /**
   * Which half of a crossing to draw into the capture. 0 = everything, as on screen; 1 = only the
   * glyphs on their way OUT; 2 = only the glyphs on their way in.
   *
   * Summed ink cannot say which of a crossing's two glyphs is which, and that is what stalled the
   * settle-tail investigation: `t_geom` is gated by whichever glyph leaves last, so two arrival
   * knobs were tried against a departure defect. Unlike SwiftUI's, this renderer is ours, so it can
   * simply be asked to leave one half out.
   *
   * A separate RUN rather than a second plane per frame: the recorder measured a run-to-run spread
   * of 0.000 on every metric once re-zeroed, so two runs of the same preset are directly
   * comparable, and this costs no extra memory, disk or draw time.
   */
  var drawFilter: Int = 0
    private set

  /** True only while the recorder is taking its own extra pass — never during the on-screen draw. */
  val isCapturing: Boolean get() = capturing

  /** Whether the glyph with this presence target belongs in the capture currently being taken. */
  fun excludes(target: Float): Boolean = when {
    !capturing || drawFilter == 0 -> false
    drawFilter == 1 -> target >= 0.5f
    else -> target < 0.5f
  }

  private var recording = false
  private var capturing = false
  private var bitmap: Bitmap? = null
  private var canvas: Canvas? = null
  private var pixels: IntArray = IntArray(0)
  private var plane: ByteArray = ByteArray(0)
  // Streamed straight to disk. Holding a run in memory the way the iOS recorder does costs ~90 MB
  // here and the doubling of the backing array on top of it, which is an OutOfMemoryError.
  private var sink: java.io.OutputStream? = null
  private var binFile: File? = null

  private var offsetX = 0
  private var offsetY = 0
  private var startNanos = 0L
  private var label = ""
  private var countsDown = false
  private val times = ArrayList<Double>()
  private val marks = ArrayList<Pair<Double, String>>()
  private var finalize: Runnable? = null
  private var host: View? = null

  private fun enabled(view: View): Boolean {
    if (!checkedEnabled) {
      checkedEnabled = true
      val dir = view.context.getExternalFilesDir(null)
      enabled = dir != null && File(dir, "numerictext-record.on").exists()
      drawFilter = when {
        dir == null -> 0
        File(dir, "numerictext-record.outgoing").exists() -> 1
        File(dir, "numerictext-record.incoming").exists() -> 2
        else -> 0
      }
    }
    return enabled
  }

  fun arm(view: View, label: String, countsDown: Boolean) {
    if (!enabled(view)) return
    if (view.width <= 0 || view.height <= 0) return

    if (recording) {
      // A burst stays one recording; the marks are what make the cadence readable afterwards.
      marks.add(Pair((System.nanoTime() - startNanos) / 1_000_000.0, label))
      rearmFinalize(view)
      return
    }

    val width = (view.width * (1f + 2f * MARGIN)).toInt()
    val height = (view.height * (1f + 2f * MARGIN)).toInt()
    val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap = target
    canvas = Canvas(target)
    pixels = IntArray(width * height)
    plane = ByteArray(width * height)
    val dir = File(view.context.getExternalFilesDir(null), "numerictext-record")
    dir.mkdirs()
    binFile = File(dir, "run-${System.currentTimeMillis()}.bin")
    sink = java.io.BufferedOutputStream(java.io.FileOutputStream(binFile), 1 shl 20)
    offsetX = (view.width * MARGIN).toInt()
    offsetY = (view.height * MARGIN).toInt()

    startNanos = System.nanoTime()
    this.label = label
    this.countsDown = countsDown
    times.clear()
    marks.clear()
    marks.add(Pair(0.0, label))
    host = view
    recording = true
    rearmFinalize(view)
  }

  private fun rearmFinalize(view: View) {
    finalize?.let { view.removeCallbacks(it) }
    val runnable = Runnable { stop() }
    finalize = runnable
    view.postDelayed(runnable, TAIL_MS)
  }

  /**
   * Called at the end of `onDraw`. Re-entrant by construction — `view.draw` runs `onDraw` again —
   * so the guard is what keeps this to exactly one extra pass per frame.
   */
  fun capture(view: View) {
    if (!recording || capturing) return
    val target = bitmap ?: return
    val into = canvas ?: return

    capturing = true
    try {
      target.eraseColor(0)
      into.save()
      into.translate(offsetX.toFloat(), offsetY.toFloat())
      view.draw(into)
      into.restore()
    } finally {
      capturing = false
    }

    val width = target.width
    val height = target.height
    target.getPixels(pixels, 0, width, 0, 0, width, height)
    // Alpha only: the text is one solid colour, so coverage — opacity and blur included — is the
    // alpha channel, and one byte per pixel keeps a whole run in memory.
    val out = plane
    for (index in out.indices) out[index] = (pixels[index] ushr 24).toByte()
    sink?.write(out)
    times.add((System.nanoTime() - startNanos) / 1_000_000.0)
  }

  private fun stop() {
    if (!recording) return
    recording = false
    val view = host
    val target = bitmap
    host = null
    finalize = null
    if (view == null || target == null || times.isEmpty()) {
      release()
      return
    }

    val meta = JSONObject()
    meta.put("label", label)
    meta.put("countsDown", countsDown)
    meta.put("platform", "android")
    meta.put("width", target.width)
    meta.put("height", target.height)
    meta.put("scale", 1)
    meta.put("format", "gray8-alpha")
    meta.put("drawFilter", drawFilter)
    meta.put("frames", times.size)
    meta.put("times", JSONArray(times))
    meta.put("drawBounds", JSONArray())
    meta.put(
      "captureRect",
      JSONArray(listOf(-offsetX, -offsetY, target.width, target.height))
    )
    val markArray = JSONArray()
    for ((at, name) in marks) {
      markArray.put(JSONObject().put("t", at).put("label", name))
    }
    meta.put("marks", markArray)

    sink?.flush(); sink?.close(); sink = null
    val bin = binFile
    if (bin != null) {
      File(bin.parentFile, bin.name.removeSuffix(".bin") + ".json").writeText(meta.toString())
      android.util.Log.i(
        "numerictext-record",
        "$label frames=${times.size} ${target.width}x${target.height} filter=$drawFilter -> ${bin.name}"
      )
    }
    release()
  }

  private fun release() {
    bitmap?.recycle()
    bitmap = null
    canvas = null
    pixels = IntArray(0)
    plane = ByteArray(0)
    try { sink?.close() } catch (_: Exception) {}
    sink = null
    binFile = null
    times.clear()
    marks.clear()
  }
}
