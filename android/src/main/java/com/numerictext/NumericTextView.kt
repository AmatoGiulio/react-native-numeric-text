package com.numerictext

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.text.TextPaint
import android.view.Choreographer
import android.view.View
import com.facebook.react.common.assets.ReactFontManager
import com.numerictext.NumericRollEngine.GlyphRole
import com.numerictext.NumericRollEngine.GlyphSample
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
/**
 * Android implementation of SwiftUI-like numericText.
 *
 * V8 uses persistent rolling columns rather than rebuilding ENTER/EXIT timelines.
 * Repeated value updates only move each column's target stop, preserving current
 * position and velocity. Rendering uses a normalized directional blur on Android 13+
 * and a conservative Gaussian fallback on Android 12.
 */
class NumericTextView(context: Context) : View(context), Choreographer.FrameCallback {

  // Props
  private var numericValue: Double = 0.0
  private var settledValue: Double = 0.0

  /**
   * The value the engine is currently rolling TOWARDS, which is not the settled one during a burst.
   *
   * The roll's direction has to be read against this. `settledValue` only advances when motion
   * finishes, so during a burst every change was compared against where the number was before the
   * burst began: 1,010 -> 1,020 -> 1,010 resolved the last step as "up", and the roll kept going
   * the same way whichever way the value moved.
   */
  private var targetValue: Double = 0.0
  private var settledText: String = "0"
  private var targetText: String = "0"
  private var hasSettledOnce = false

  var numericLocale: String = "en-US"; private set
  var numericDirection: String = "automatic"; private set
  var animationDurationMs: Long = 320L; private set
  var numericUseGrouping: Boolean = true; private set
  var numericMinFractionDigits: Int = 0; private set
  var numericMaxFractionDigits: Int = 3; private set
  var numericReduceMotion: String = "system"; private set
  var numericFontSize: Float = 48f; private set
  var numericFontWeight: String = "normal"; private set
  var numericFontFamily: String = NumericTextFonts.BUNDLED; private set
  var numericTextColor: Int = Color.BLACK; private set
  var debugTransitionStrategy: String = ""; private set
  var debugManualProgress: Float = -1f; private set

  // Persistent motion
  private val engine = NumericRollEngine()
  private var framePosted = false
  private var lastFrameNanos = 0L
  private var measureOldWidth = 0f
  private var measureNewWidth = 0f

  // Text paint
  private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
  private var fmAscent = 0f
  private var fmDescent = 0f
  private var textHeightPx = 0f
  private val advanceCache = HashMap<String, Float>(24)
  private var paintGeneration = 0
  private var lastDesiredWidth = -1

  // Render caches
  private val glyphNodeCache = HashMap<String, RenderNode>(32)
  private val directionalEffectCache = HashMap<Int, RenderEffect>(32)
  private val gaussianEffectCache = HashMap<Int, RenderEffect>(24)

  // Formatter
  private var formatter: NumberFormat? = null
  private var currentFormatterLocale: Locale? = null
  private var currentGroupSep: Char = ','
  private var currentDecimalSep: Char = '.'
  private var currentMinusSign: Char = '-'

  init {
    clipToOutline = true
    recalcTextPaint()
    recalcFormatter()
  }

  private fun hHeadroom(): Float = textHeightPx * 0.36f + 4f

  private fun settledDesiredWidth(text: String): Int =
    ceil(advanceOf(text) + 2f * hHeadroom() + paddingLeft + paddingRight).toInt()

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
  val h = textHeightPx
  val contentWidth = if (engine.isRunning) max(measureOldWidth, measureNewWidth)
  else advanceOf(settledText.ifEmpty { "0" })
  
  val dw = ceil(contentWidth + 2f * hHeadroom() + paddingLeft + paddingRight).toInt()
  
    val vHeadroom = h * 1.2f
  val dh = ceil(h + 2f * vHeadroom + paddingTop + paddingBottom).toInt()
  
  setMeasuredDimension(
    resolveSize(maxOf(dw, suggestedMinimumWidth), widthMeasureSpec),
    resolveSize(maxOf(dh, suggestedMinimumHeight), heightMeasureSpec),
  )
}



  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    recalcFormatter()
    recalcTextPaint()
    if (engine.isRunning) postFrame()
  }

  override fun onDetachedFromWindow() {
    stopFrames()
    super.onDetachedFromWindow()
  }
  private var edgeFadeGradient: LinearGradient? = null
  private var edgeFadeMaskPaint: Paint? = null
  private var lastFadeWidth = -1
  private var lastFadeHeight = -1

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (width <= 0 || height <= 0) return

    val marginY = textHeightPx * 1.2f
    val baseline = baselineY(height / 2f)

    val topClip = kotlin.math.max(0f, baseline + fmAscent - marginY)
    val bottomClip = kotlin.math.min(height.toFloat(), baseline + fmDescent + marginY)

    val fadePx = textHeightPx * 0.55f

    canvas.saveLayer(0f, topClip, width.toFloat(), bottomClip, null)

    if (!engine.isRunning && targetText == settledText) {
      drawSettled(canvas)
    } else {
      drawRolling(canvas)
    }

    val fadeTop = kotlin.math.max(topClip, baseline + fmAscent - marginY + fadePx)
    val fadeBottom = kotlin.math.min(bottomClip, baseline + fmDescent + marginY - fadePx)

    if (edgeFadeGradient == null || lastFadeWidth != width || lastFadeHeight != height) {
      edgeFadeGradient = LinearGradient(
        0f, topClip, 0f, bottomClip,
        intArrayOf(0x00000000, 0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000),
        floatArrayOf(
          0f,
          (fadeTop - topClip) / (bottomClip - topClip),
          (fadeBottom - topClip) / (bottomClip - topClip),
          1f
        ),
        Shader.TileMode.CLAMP
      )
      edgeFadeMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = edgeFadeGradient
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
      }
      lastFadeWidth = width
      lastFadeHeight = height
    } else if (edgeFadeMaskPaint != null) {
      edgeFadeMaskPaint!!.shader = edgeFadeGradient
    }

    edgeFadeMaskPaint?.let { canvas.drawRect(0f, topClip, width.toFloat(), bottomClip, it) }

    canvas.restore()

    // Ground-truth capture: exactly what was drawn, on the frame it was drawn. No-op unless armed.
    NumericTextFrameRecorder.capture(this)
  }

  private fun drawSettled(canvas: Canvas) {
    textPaint.maskFilter = null
    textPaint.alpha = 255
    val baseline = baselineY(height / 2f)
    val left = width / 2f - advanceOf(settledText) / 2f
    canvas.drawText(settledText, left, baseline, textPaint)
  }

  private fun drawRolling(canvas: Canvas) {
    val baseline = baselineY(height / 2f)
    val centreX = width / 2f
    val hardwareNodes = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canvas.isHardwareAccelerated

    val samples = engine.samples()
    val ordered = samples.sortedBy { it.role.ordinal }

    for (sample in ordered) {
      val alpha = (sample.alpha * 255f).roundToInt().coerceIn(0, 255)
      if (alpha <= 0) continue
      val x = centreX + sample.x
      if (hardwareNodes) {
        drawGlyphNode(canvas, sample, x, baseline, alpha)
      } else {
        drawGlyphSoftware(canvas, sample, x, baseline, alpha)
      }
    }
    textPaint.alpha = 255
    textPaint.maskFilter = null
  }

  /**
   * The directional blur, for canvases that cannot run the shader.
   *
   * Drawn as the shader's own nine taps along the vertical axis rather than with a BlurMaskFilter,
   * which is isotropic and therefore smears sideways as well. That difference is not academic: the
   * ground-truth recorder draws through a software canvas, so every comparison against iOS was
   * putting a round blur next to the reference's vertical one and reading the result as "our
   * crossing is mushier". It is the same reason a device without a hardware canvas looked wrong.
   */
  private fun drawGlyphSoftware(
    canvas: Canvas,
    sample: NumericRollEngine.GlyphSample,
    x: Float,
    baseline: Float,
    alpha: Int,
  ) {
    val left = x - advanceOf(sample.ch) / 2f
    val y = baseline + sample.offsetY
    val trail = sample.blurLengthPx * 1.5f * sample.direction

    canvas.save()
    canvas.scale(sample.scale, sample.scale, x, y)
    // Below half a pixel of trail, draw sharp — the same threshold the hardware path uses. Nine
    // taps perfectly overlapped composite to about 64% of full opacity, not 100%, so taking the
    // blur path for a glyph that has all but stopped left it permanently dim and back-to-full
    // never arrived: it read 600 ms against the reference's 420.
    if (abs(trail) < 0.75f || sample.stable) {
      textPaint.alpha = alpha
      canvas.drawText(sample.ch, left, y, textPaint)
    } else {
      // Through a layer, so the taps blend with each other once and the glyph's own alpha is
      // applied to the result. Drawing them straight onto the canvas composites nine times over,
      // which reads as a glyph that will not finish resolving — it pushed back-to-full from 420 ms
      // to 600.
      // Through a layer, with the taps ADDED rather than drawn over each other. Over-drawing is
      // not what a blur does: nine taps perfectly overlapped composite to about 64% of full
      // opacity, so the crossing came out a fifth too faint — floor 0.365 against the reference's
      // 0.428. Added, the weights sum to 1.0 and a still glyph is exactly itself, which is what
      // the shader computes.
      // Tight around this glyph plus its trail. A loose rect reaches over the neighbouring columns,
      // and the layer's own compositing then shows up on digits that are not moving at all — the
      // unchanging column read 0.799 of its settled ink instead of 1.000.
      val w = advanceOf(sample.ch)
      val pad = abs(trail) + textHeightPx * 0.5f
      canvas.saveLayerAlpha(left - pad, y - textHeightPx - pad, left + w + pad, y + pad, alpha)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) textPaint.blendMode = BlendMode.PLUS
      for (i in BLUR_TAPS.indices step 2) {
        textPaint.alpha = (255f * BLUR_TAPS[i + 1]).roundToInt().coerceIn(0, 255)
        if (textPaint.alpha > 0) {
          canvas.drawText(sample.ch, left, y + BLUR_TAPS[i] * trail, textPaint)
        }
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) textPaint.blendMode = null
      canvas.restore()
    }
    canvas.restore()
    textPaint.alpha = 255
  }

  @SuppressLint("NewApi")
  private fun drawGlyphNode(
    canvas: Canvas,
    sample: NumericRollEngine.GlyphSample,
    centreX: Float,
    baseline: Float,
    alpha: Int,
  ) {
    val advance = advanceOf(sample.ch)
    val margin = ceil(textHeightPx * 0.62f)
    val nodeW = ceil(advance + 2f * margin).toInt()
    val nodeH = ceil(textHeightPx + 2f * margin).toInt()
    val idealLeft = centreX - margin - advance / 2f
    val idealTop = baseline - (margin - fmAscent)
    val baseLeft = floor(idealLeft).toInt()
    val baseTop = floor(idealTop).toInt()
    val localCX = margin + advance / 2f
    val localBL = margin - fmAscent

    val cacheKey = "${paintGeneration}_${numericTextColor}_${sample.key}_${sample.ch}_${nodeW}_${nodeH}"
    var node = glyphNodeCache[cacheKey]
    if (node == null || !node.hasDisplayList()) {
      node = RenderNode(cacheKey)
      node.setHasOverlappingRendering(false)
      node.setPosition(baseLeft, baseTop, baseLeft + nodeW, baseTop + nodeH)
      val recording = node.beginRecording()
      textPaint.alpha = 255
      textPaint.maskFilter = null
      recording.drawText(sample.ch, localCX - advance / 2f, localBL, textPaint)
      node.endRecording()
      glyphNodeCache[cacheKey] = node
    } else {
      node.setPosition(baseLeft, baseTop, baseLeft + nodeW, baseTop + nodeH)
    }

    node.pivotX = localCX
    node.pivotY = localBL
    node.translationX = idealLeft - baseLeft
    node.translationY = sample.offsetY + (idealTop - baseTop)
    node.scaleX = sample.scale
    node.scaleY = sample.scale
    node.alpha = alpha / 255f

    node.setRenderEffect(effectFor(sample.blurLengthPx, sample.direction))
    canvas.drawRenderNode(node)
  }

  @SuppressLint("NewApi")
  private fun effectFor(lengthPx: Float, direction: Float): RenderEffect? {
    if (lengthPx < 0.5f) return null
    // The directional (shutter) blur needs a runtime shader, which is API 33. Restoring this file
    // from 8cc89a5 brought back a WIP state where this guard had been deleted and its closing brace
    // left behind, so the function ended early and the companion object below it never parsed.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val lengthBucket = (lengthPx * 3f).roundToInt().coerceIn(1, 96)
      val directionBucket = if (direction < 0f) -1 else 1
      val key = lengthBucket * directionBucket
      return directionalEffectCache.getOrPut(key) {
        val shader = RuntimeShader(DIRECTIONAL_BLUR_SHADER)
        shader.setFloatUniform("trail", lengthBucket / 2f)
        shader.setFloatUniform("direction", directionBucket.toFloat())
        RenderEffect.createRuntimeShaderEffect(shader, "content")
      }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val bucket = (lengthPx * 4f).roundToInt().coerceIn(1, 40)
      return gaussianEffectCache.getOrPut(bucket) {
        val ry = bucket / 4f
        RenderEffect.createBlurEffect(ry * NumericRollEngine.BLUR_X_FACTOR, ry, Shader.TileMode.DECAL)
      }
    }
    return null
  }

  override fun doFrame(frameTimeNanos: Long) {
    framePosted = false
    if (!isAttachedToWindow || !engine.isRunning) {
      lastFrameNanos = 0L
      return
    }

    if (lastFrameNanos == 0L) {
      lastFrameNanos = frameTimeNanos
      invalidate()
      postFrame()
      return
    }

    val dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
    lastFrameNanos = frameTimeNanos
    val active = engine.step(dt)
    invalidate()

    if (active) {
      postFrame()
    } else {
      finishMotion()
    }
  }

  private fun postFrame() {
    if (framePosted || !isAttachedToWindow) return
    framePosted = true
    Choreographer.getInstance().postFrameCallback(this)
  }

  private fun stopFrames() {
    if (framePosted) Choreographer.getInstance().removeFrameCallback(this)
    framePosted = false
    lastFrameNanos = 0L
  }

  private fun finishMotion() {
    engine.snapToTarget()
    settledText = targetText
    settledValue = numericValue
    targetValue = numericValue
    measureOldWidth = 0f
    measureNewWidth = 0f
    lastFrameNanos = 0L
    updateContentDescription()
    invalidate()
    val desired = settledDesiredWidth(settledText)
    if (desired != lastDesiredWidth) {
      lastDesiredWidth = desired
      requestLayout()
    }
  }

  private fun startOrRetarget() {
    val formatted = formatNumber(numericValue)
    if (formatted == targetText && engine.isRunning) return
    if (formatted == settledText && !engine.isRunning) return

    val oldWidth = if (engine.isRunning) engine.targetWidth() else advanceOf(settledText)
    val newLayout = layoutOf(formatted)
    val direction = resolveDirection(numericValue, if (engine.isRunning) targetValue else settledValue)

    if (!engine.isRunning && targetText == settledText) {
      engine.reset(layoutOf(settledText), settledText, textHeightPx)
    }

    targetText = formatted
    targetValue = numericValue
    measureOldWidth = oldWidth
    measureNewWidth = newLayout.firstOrNull()?.totalWidth ?: 0f

    if (!shouldAnimate()) {
      engine.setTarget(newLayout, formatted, direction, textHeightPx, animationDurationMs)
      engine.snapToTarget()
      finishMotion()
      return
    }

    engine.setTarget(newLayout, formatted, direction, textHeightPx, animationDurationMs)
    updateContentDescription()
    if (measureNewWidth != measureOldWidth || max(measureOldWidth, measureNewWidth) > measuredWidth - 2f * hHeadroom()) {
      requestLayout()
    }
    postFrame()
    invalidate()
  }

  private fun resolveDirection(towards: Double, from: Double): Int = when (numericDirection) {
    "up" -> 1
    "down" -> -1
    else -> towards.compareTo(from).coerceIn(-1, 1).let { if (it == 0) 1 else it }
  }

  private fun layoutOf(formatted: String): List<KeyedSlot> =
    TransitionLogic.layoutKeyedSlots(
      formatted,
      currentGroupSep,
      currentDecimalSep,
      currentMinusSign,
    ) { advanceOf(it) }

  private fun shouldAnimate(): Boolean = when (numericReduceMotion) {
    "always" -> false
    "never" -> true
    else -> try {
      Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    } catch (_: Exception) {
      true
    }
  }

  private fun recalcFormatter() {
    formatter = null
    currentFormatterLocale = null
  }

  private fun formatNumber(value: Double): String {
    val locale = resolveLocale()
    if (formatter == null || currentFormatterLocale != locale) {
      formatter = NumberFormat.getNumberInstance(locale)
      currentFormatterLocale = locale
      val symbols = DecimalFormatSymbols.getInstance(locale)
      currentGroupSep = symbols.groupingSeparator
      currentDecimalSep = symbols.decimalSeparator
      currentMinusSign = symbols.minusSign
    }
    formatter?.let {
      it.isGroupingUsed = numericUseGrouping
      it.minimumFractionDigits = numericMinFractionDigits
      it.maximumFractionDigits = numericMaxFractionDigits
    }
    return formatter?.format(value) ?: value.toString()
  }

  private fun resolveLocale(): Locale = try {
    Locale.forLanguageTag(numericLocale.replace("_", "-"))
  } catch (_: Exception) {
    val parts = numericLocale.split("-", "_")
    when (parts.size) {
      1 -> Locale(parts[0])
      2 -> Locale(parts[0], parts[1])
      else -> Locale.US
    }
  }

  private fun recalcTextPaint() {
    textPaint.color = numericTextColor
    textPaint.textSize = numericFontSize * resources.displayMetrics.scaledDensity
    textPaint.isAntiAlias = true
    textPaint.isSubpixelText = true
    textPaint.typeface = resolveTypeface()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) textPaint.fontFeatureSettings = "tnum"
    val metrics = textPaint.fontMetrics
    fmAscent = metrics.ascent
    fmDescent = metrics.descent
    textHeightPx = metrics.descent - metrics.ascent
    advanceCache.clear()
    paintGeneration++
    edgeFadeGradient = null
    edgeFadeMaskPaint = null
    clearRenderCaches()
  }

  private fun clearRenderCaches() {
    glyphNodeCache.clear()
    directionalEffectCache.clear()
    gaussianEffectCache.clear()
  }

  private fun advanceOf(text: String): Float = advanceCache.getOrPut(text) { textPaint.measureText(text) }

  private fun resolveTypeface(): Typeface {
    val weight = NumericTextFonts.weightOf(numericFontWeight)
    val style = if (weight >= 700) Typeface.BOLD else Typeface.NORMAL
    val system = Typeface.create(Typeface.DEFAULT, style)
    if (numericFontFamily == NumericTextFonts.SYSTEM) return system
    if (numericFontFamily != NumericTextFonts.BUNDLED) {
      return try {
        ReactFontManager.getInstance().getTypeface(numericFontFamily, style, context.assets)
      } catch (_: RuntimeException) {
        system
      }
    }
    val bundled = NumericTextFonts.bundled(context.assets, weight) ?: return system
    val probe = TextPaint(textPaint).apply { typeface = bundled }
    return if (NumericTextFonts.canRender(probe, localeGlyphProbe())) bundled else system
  }

  private fun localeGlyphProbe(): String {
    val symbols = try {
      DecimalFormatSymbols.getInstance(resolveLocale())
    } catch (_: Exception) {
      return "0123456789,.-"
    }
    val zero = symbols.zeroDigit
    return buildString {
      for (i in 0..9) append(zero + i)
      append(symbols.groupingSeparator)
      append(symbols.decimalSeparator)
      append(symbols.minusSign)
    }
  }

  private fun baselineY(centerY: Float): Float = centerY + textHeightPx / 2f - fmDescent
  private fun updateContentDescription() { contentDescription = targetText }

  fun setValue(value: Double) {
    numericValue = value
    if (!hasSettledOnce) {
      settledValue = value
      settledText = formatNumber(value)
      targetText = settledText
      hasSettledOnce = true
      engine.reset(layoutOf(settledText), settledText, textHeightPx)
      updateContentDescription()
      requestLayout()
      invalidate()
      return
    }
    NumericTextFrameRecorder.arm(this, formatNumber(value), value < settledValue)
    startOrRetarget()
  }

  fun setDirection(value: String) { numericDirection = value }
  fun setAnimationDuration(value: Double) { animationDurationMs = value.toLong().coerceAtLeast(80L) }
  fun setReduceMotion(value: String) { numericReduceMotion = value }
  fun setDebugTransitionStrategy(value: String) { debugTransitionStrategy = value }
  fun setDebugManualProgress(value: Float) { debugManualProgress = value }

  fun setLocale(value: String) {
    if (value == numericLocale) return
    numericLocale = value
    recalcFormatter()
    reformatAtRest()
  }

  fun setUseGrouping(value: Boolean) {
    if (value == numericUseGrouping) return
    numericUseGrouping = value
    recalcFormatter()
    reformatAtRest()
  }

  fun setMinimumFractionDigits(value: Int) {
    if (value == numericMinFractionDigits) return
    numericMinFractionDigits = value
    recalcFormatter()
    reformatAtRest()
  }

  fun setMaximumFractionDigits(value: Int) {
    if (value == numericMaxFractionDigits) return
    numericMaxFractionDigits = value
    recalcFormatter()
    reformatAtRest()
  }

  private fun reformatAtRest() {
    if (engine.isRunning) {
      startOrRetarget()
      return
    }
    settledText = formatNumber(settledValue)
    targetText = settledText
    engine.reset(layoutOf(settledText), settledText, textHeightPx)
    updateContentDescription()
    requestLayout()
    invalidate()
  }

  fun setFontSize(value: Float) {
    val next = value.coerceAtLeast(4f)
    if (next == numericFontSize) return
    numericFontSize = next
    recalcTextPaint()
    engine.reset(layoutOf(targetText), targetText, textHeightPx)
    requestLayout()
    invalidate()
  }

  fun setFontWeight(value: String) {
    if (value == numericFontWeight) return
    numericFontWeight = value
    recalcTextPaint()
    engine.reset(layoutOf(targetText), targetText, textHeightPx)
    requestLayout()
    invalidate()
  }

  fun setFontFamily(value: String) {
    if (value == numericFontFamily) return
    numericFontFamily = value
    recalcTextPaint()
    engine.reset(layoutOf(targetText), targetText, textHeightPx)
    requestLayout()
    invalidate()
  }

  fun setTextColor(value: Int) {
    if (value == numericTextColor) return
    numericTextColor = value
    textPaint.color = value
    clearRenderCaches()
    invalidate()
  }

  companion object {
    /**
     * Offset, weight — the blur kernel, shared by the shader and the software path.
     *
     * Seventeen rather than nine. Nine is enough for a short trail and visibly banded on a long
     * one: the frame grid showed the individual copies as stripes across the glyph while the
     * reference's smear was smooth, which reads as a crossing that is somehow both blurrier and
     * more mechanical than iOS's.
     */
    private val BLUR_TAPS = floatArrayOf(
      -0.5000f, 0.0029f,
      -0.4375f, 0.0072f,
      -0.3750f, 0.0159f,
      -0.3125f, 0.0308f,
      -0.2500f, 0.0530f,
      -0.1875f, 0.0808f,
      -0.1250f, 0.1092f,
      -0.0625f, 0.1308f,
      +0.0000f, 0.1389f,
      +0.0625f, 0.1308f,
      +0.1250f, 0.1092f,
      +0.1875f, 0.0808f,
      +0.2500f, 0.0530f,
      +0.3125f, 0.0308f,
      +0.3750f, 0.0159f,
      +0.4375f, 0.0072f,
      +0.5000f, 0.0029f,
    )

    private const val DIRECTIONAL_BLUR_SHADER = """
      uniform shader content;
      uniform float trail;
      uniform float direction;

      half4 main(float2 p) {
        float2 axis = float2(0.0, direction * trail);
        half4 c = half4(0.0);
        c += content.eval(p + axis * -0.5000) * 0.0029;
        c += content.eval(p + axis * -0.4375) * 0.0072;
        c += content.eval(p + axis * -0.3750) * 0.0159;
        c += content.eval(p + axis * -0.3125) * 0.0308;
        c += content.eval(p + axis * -0.2500) * 0.0530;
        c += content.eval(p + axis * -0.1875) * 0.0808;
        c += content.eval(p + axis * -0.1250) * 0.1092;
        c += content.eval(p + axis * -0.0625) * 0.1308;
        c += content.eval(p + axis * +0.0000) * 0.1389;
        c += content.eval(p + axis * +0.0625) * 0.1308;
        c += content.eval(p + axis * +0.1250) * 0.1092;
        c += content.eval(p + axis * +0.1875) * 0.0808;
        c += content.eval(p + axis * +0.2500) * 0.0530;
        c += content.eval(p + axis * +0.3125) * 0.0308;
        c += content.eval(p + axis * +0.3750) * 0.0159;
        c += content.eval(p + axis * +0.4375) * 0.0072;
        c += content.eval(p + axis * +0.5000) * 0.0029;
        return c;
      }
    """
  }
}
