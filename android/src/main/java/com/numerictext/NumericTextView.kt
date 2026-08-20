package com.numerictext

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.text.TextPaint
import android.view.Choreographer
import android.view.View
import com.facebook.react.common.assets.ReactFontManager
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Android numericText renderer. The complete formatted line is shaped and rasterized once, then
 * persistent STACK entries animate keyed slices of that immutable raster.
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
  private var formatTransitionPending = false

  /** Everything about the shape of the number, as `src/numberFormat.ts` resolved it. */
  private var formatSpec = NumericFormatSpec()

  var numericDirection: String = "automatic"; private set
  var animationDurationMs: Long = 320L; private set
  var numericReduceMotion: String = "system"; private set
  var numericFontSize: Float = 48f; private set
  var numericFontWeight: String = "normal"; private set
  var numericFontFamily: String = NumericTextFonts.BUNDLED; private set
  var numericTextColor: Int = Color.BLACK; private set

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
  private val lineGeometryCache = LinkedHashMap<String, TextLineGeometry>(32, 0.75f, true)
  private var paintGeneration = 0
  private var nextRasterId = 1

  private data class PreparedKey(
    val text: String,
    val group: Char,
    val decimal: Char,
    val minus: Char,
  )

  /**
   * Two formats can key the same characters differently: a comma is a grouping mark in `en-US`
   * and a decimal mark in `de-DE`. The marks in force are therefore part of the cache key.
   */
  private fun preparedKeyFor(text: String) = PreparedKey(
    text,
    formatter.groupingSeparator,
    formatter.decimalSeparator,
    formatter.minusSign,
  )

  private data class PreparedText(
    val layout: List<KeyedSlot>,
    val raster: NumericTextRaster,
  )

  private val preparedByKey = LinkedHashMap<PreparedKey, PreparedText>(16, 0.75f, true)
  private val preparedById = HashMap<Int, PreparedText>(16)
  private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  private var rasterColorFilter = PorterDuffColorFilter(numericTextColor, PorterDuff.Mode.SRC_IN)

  /**
   * Optional second tint, for the fraction span.
   *
   * The raster is drawn white and tinted at composite time, so a colour is a property of the draw
   * rather than of the bitmap. A second one therefore costs nothing but choosing a different filter
   * per slice — and because every draw goes through [drawRolling], settled and transitioning frames
   * pick it up alike. Null draws the whole number in [numericTextColor].
   */
  private var numericFractionColor: Int? = null
  private var fractionColorFilter: PorterDuffColorFilter? = null
  private var lastDesiredWidth = -1

  // Render caches
  private val glyphNodeCache = HashMap<String, RenderNode>(32)
  private val activeGlyphNodeKeys = HashSet<String>(32)
  private val gaussianEffectCache = HashMap<Int, RenderEffect>(24)

  // Formatter
  private var formatter: NumericTextFormatter = NumericTextFormatter.of(formatSpec)

  init {
    clipToOutline = true
    recalcTextPaint()
  }

  private fun colorFilterFor(key: String): PorterDuffColorFilter =
    if (TransitionLogic.isFractionKey(key)) fractionColorFilter ?: rasterColorFilter
    else rasterColorFilter

  internal fun setFractionColor(value: Int) {
    if (numericFractionColor == value) return
    numericFractionColor = value
    fractionColorFilter = PorterDuffColorFilter(value, PorterDuff.Mode.SRC_IN)
    invalidate()
  }

  private fun hHeadroom(): Float = textHeightPx * 0.36f + 4f

  /** The glyph's vertical middle, signed from its baseline. Negative: ascent is above it. */
  private fun opticalCentre(): Float = (fmAscent + fmDescent) / 2f

  private fun settledDesiredWidth(text: String): Int =
    ceil(lineWidthOf(text) + 2f * hHeadroom() + paddingLeft + paddingRight).toInt()

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val h = textHeightPx
    val contentWidth = if (engine.isRunning) max(measureOldWidth, measureNewWidth)
    else lineWidthOf(settledText.ifEmpty { "0" })

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
    NumericTextFrameRecorder.configure(this)
    if (engine.isRunning) {
      beginAnimationRenderPath()
      postFrame()
    }
  }

  override fun onDetachedFromWindow() {
    stopFrames()
    endAnimationRenderPath()
    super.onDetachedFromWindow()
  }

  private var edgeFadeGradient: LinearGradient? = null
  private var edgeFadeMaskPaint: Paint? = null
  private val softwareBlurCache = HashMap<Int, BlurMaskFilter>()
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

    drawRolling(canvas)

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

  private fun drawRolling(canvas: Canvas) {
    val baseline = baselineY(height / 2f)
    val centreX = width / 2f
    val hardwareNodes = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated

    val ordered = engine.samples().sortedBy { it.role.ordinal }

    if (hardwareNodes) activeGlyphNodeKeys.clear()

    for (sample in ordered) {
      val alpha = (sample.alpha * 255f).roundToInt().coerceIn(0, 255)
      if (alpha <= 0) continue

      val prepared = preparedById[sample.rasterId] ?: continue
      val slice = prepared.raster.slice(sample.key) ?: continue
      val x = centreX + sample.x

      if (hardwareNodes) {
        drawRasterNode(canvas, sample, prepared.raster, slice, x, baseline, alpha)
      } else {
        drawRasterSoftware(canvas, sample, prepared.raster, slice, x, baseline, alpha)
      }
    }

    if (hardwareNodes) {
      glyphNodeCache.keys.retainAll(activeGlyphNodeKeys)
    }

    bitmapPaint.alpha = 255
    bitmapPaint.maskFilter = null
    bitmapPaint.colorFilter = rasterColorFilter
  }

  /**
   * The same isotropic blur for canvases that cannot take a RenderEffect.
   *
   * BlurMaskFilter is exactly a 2D gaussian on the glyph's coverage, which is what is wanted here;
   * the nine- and then seventeen-tap directional version this replaces was solving the wrong
   * problem. The ground-truth recorder draws through a software canvas, so this is also the path
   * every measurement is taken through — it has to match the RenderEffect above, not approximate
   * something else.
   */
  private fun drawRasterSoftware(
    canvas: Canvas,
    sample: NumericRollEngine.GlyphSample,
    raster: NumericTextRaster,
    slice: RasterSlice,
    x: Float,
    baseline: Float,
    alpha: Int,
  ) {
    val source = slice.source
    val left = x - slice.anchorX
    val y = baseline + sample.offsetY
    val top = y - raster.baseline
    val destination = RectF(
      left,
      top,
      left + source.width(),
      top + source.height(),
    )

    bitmapPaint.alpha = alpha
    bitmapPaint.colorFilter = colorFilterFor(sample.key)
    bitmapPaint.maskFilter =
      if (sample.stable || sample.blurLengthPx < BLUR_MIN_PX) {
        null
      } else {
        val bucket =
          (sample.blurLengthPx * BLUR_RADIUS_FACTOR * BLUR_STEPS_PER_PX)
            .roundToInt()
            .coerceIn(1, 480)
        softwareBlurCache.getOrPut(bucket) {
          BlurMaskFilter(bucket / BLUR_STEPS_PER_PX, BlurMaskFilter.Blur.NORMAL)
        }
      }

    canvas.save()
    canvas.scale(sample.scaleX, sample.scaleY, x, y + opticalCentre())
    canvas.drawBitmap(raster.bitmap, source, destination, bitmapPaint)
    canvas.restore()

    bitmapPaint.maskFilter = null
    bitmapPaint.alpha = 255
  }

  @SuppressLint("NewApi")
  private fun drawRasterNode(
    canvas: Canvas,
    sample: NumericRollEngine.GlyphSample,
    raster: NumericTextRaster,
    slice: RasterSlice,
    centreX: Float,
    baseline: Float,
    alpha: Int,
  ) {
    val source = slice.source
    val margin = ceil(textHeightPx * 0.80f)
    val nodeW = source.width() + (2f * margin).toInt()
    val nodeH = source.height() + (2f * margin).toInt()
    val idealLeft = centreX - slice.anchorX - margin
    val idealTop = baseline - raster.baseline - margin
    val baseLeft = floor(idealLeft).toInt()
    val baseTop = floor(idealTop).toInt()
    val localAnchorX = margin + slice.anchorX
    val localBaseline = margin + raster.baseline

    val cacheKey =
      "${paintGeneration}_${numericTextColor}_${numericFractionColor}_${sample.rasterId}_${sample.key}_${sample.renderId}_${nodeW}_${nodeH}"
    activeGlyphNodeKeys.add(cacheKey)

    var node = glyphNodeCache[cacheKey]
    if (node == null || !node.hasDisplayList()) {
      node = RenderNode(cacheKey)
      node.setHasOverlappingRendering(false)
      node.setPosition(baseLeft, baseTop, baseLeft + nodeW, baseTop + nodeH)

      val recording = node.beginRecording()
      bitmapPaint.alpha = 255
      bitmapPaint.maskFilter = null
      bitmapPaint.colorFilter = colorFilterFor(sample.key)
      recording.drawBitmap(
        raster.bitmap,
        source,
        RectF(
          margin,
          margin,
          margin + source.width(),
          margin + source.height(),
        ),
        bitmapPaint,
      )
      node.endRecording()
      glyphNodeCache[cacheKey] = node
    } else {
      node.setPosition(baseLeft, baseTop, baseLeft + nodeW, baseTop + nodeH)
    }

    node.pivotX = localAnchorX
    node.pivotY = localBaseline + opticalCentre()
    node.translationX = idealLeft - baseLeft
    node.translationY = sample.offsetY + (idealTop - baseTop)
    node.scaleX = sample.scaleX
    node.scaleY = sample.scaleY
    node.alpha = alpha / 255f
    node.setRenderEffect(effectFor(sample.blurLengthPx))
    canvas.drawRenderNode(node)
  }

  @SuppressLint("NewApi")
  private fun effectFor(lengthPx: Float): RenderEffect? {
    if (lengthPx < BLUR_MIN_PX) return null
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val bucket = (lengthPx * BLUR_RADIUS_FACTOR * BLUR_STEPS_PER_PX).roundToInt().coerceIn(1, 480)
    return gaussianEffectCache.getOrPut(bucket) {
      val radius = bucket / BLUR_STEPS_PER_PX
      RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL)
    }
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
    prunePreparedTextCache()
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
    endAnimationRenderPath()
    prunePreparedTextCache()
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
    if (!formatTransitionPending) {
      if (formatted == targetText && engine.isRunning) return
      if (formatted == settledText && !engine.isRunning) return
    }

    val next = preparedTextOf(formatted)
    val oldWidth =
      if (engine.isRunning || formatTransitionPending) engine.targetWidth()
      else lineWidthOf(settledText)
    val direction = resolveDirection(numericValue, if (engine.isRunning) targetValue else settledValue)

    if (!engine.isRunning && targetText == settledText && !formatTransitionPending) {
      val current = preparedTextOf(settledText)
      engine.reset(current.layout, settledText, textHeightPx, current.raster.id, appleBlurLengthPx())
    }

    targetText = formatted
    targetValue = numericValue
    measureOldWidth = oldWidth
    measureNewWidth = next.raster.lineWidth

    if (!shouldAnimate()) {
      engine.setTarget(
        next.layout, formatted, direction, textHeightPx, animationDurationMs, next.raster.id,
        appleBlurLengthPx(),
      )
      formatTransitionPending = false
      engine.snapToTarget()
      finishMotion()
      return
    }

    engine.setTarget(
      next.layout, formatted, direction, textHeightPx, animationDurationMs, next.raster.id,
      appleBlurLengthPx(),
    )
    formatTransitionPending = false
    beginAnimationRenderPath()
    prunePreparedTextCache()
    updateContentDescription()
    if (measureNewWidth != measureOldWidth || max(measureOldWidth, measureNewWidth) > measuredWidth - 2f * hHeadroom()) {
      requestLayout()
    }
    postFrame()
    invalidate()
  }

  /**
   * Apple's numericText blur is relative: packed byte 32 decodes as 32 / 128 = 0.25.
   *
   * RenderBox applies that value relative to the text line geometry. Our renderer consumes a blur
   * length and converts it to Gaussian radius with BLUR_RADIUS_FACTOR (0.5), so feed the engine
   * the inverse-scaled length required to produce radius = 0.25 * lineHeight.
   */
  private fun appleBlurLengthPx(): Float =
    (APPLE_RELATIVE_BLUR * textHeightPx) / BLUR_RADIUS_FACTOR

  /**
   * Android's hardware Canvas does not support Paint.setMaskFilter(), while RenderEffect only
   * exists from API 31. During animation on older Android releases, render this small custom View
   * through a software layer so the existing BlurMaskFilter path produces the same isotropic blur.
   *
   * The layer is enabled only for the lifetime of the animation and never performs pixel readback
   * or per-frame bitmap allocation in our code. API 31+ stays on the RenderNode/RenderEffect path.
   */
  private fun beginAnimationRenderPath() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && layerType != LAYER_TYPE_SOFTWARE) {
      setLayerType(LAYER_TYPE_SOFTWARE, null)
    }
  }

  private fun endAnimationRenderPath() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && layerType == LAYER_TYPE_SOFTWARE) {
      setLayerType(LAYER_TYPE_NONE, null)
    }
  }

  private fun resolveDirection(towards: Double, from: Double): Int = when (numericDirection) {
    "up" -> 1
    "down" -> -1
    else -> towards.compareTo(from).coerceIn(-1, 1).let { if (it == 0) 1 else it }
  }

  private fun shouldAnimate(): Boolean = when (numericReduceMotion) {
    "always" -> false
    "never" -> true
    else -> try {
      Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    } catch (_: Exception) {
      true
    }
  }

  /**
   * Rebuilds the formatter after a formatting prop changed, and re-checks the typeface with it.
   *
   * During a simultaneous format + value transaction the old raster must remain addressable until
   * the new target has been installed. A formatter may change the selected typeface (for example
   * when moving into an Arabic currency), so that preservation has to survive recalcTextPaint too.
   */
  private fun recalcFormatter(preservePreparedRasters: Boolean = false) {
    formatter = NumericTextFormatter.of(formatSpec)
    if (textPaint.typeface != resolveTypeface()) {
      recalcTextPaint(preservePreparedRasters)
    }
  }

  private fun formatNumber(value: Double): String = formatter.format(value)

  internal fun setFormatSpec(value: NumericFormatSpec, deferReformat: Boolean) {
    if (value == formatSpec) return
    formatSpec = value
    recalcFormatter(preservePreparedRasters = deferReformat)

    if (deferReformat) {
      // Key lookups from now on belong to the final formatter, while preparedById still owns the
      // immutable old raster referenced by the engine.
      preparedByKey.clear()
      formatTransitionPending = true
    } else {
      formatTransitionPending = false
      reformatAtRest()
    }
  }

  /** Applies [change] to the formatting props, and reformats if it changed anything. */
  private fun updateFormat(change: (NumericFormatSpec) -> NumericFormatSpec) {
    val next = change(formatSpec)
    if (next == formatSpec) return
    setFormatSpec(next, deferReformat = false)
  }

  private fun recalcTextPaint(preservePreparedRasters: Boolean = false) {
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
    lineGeometryCache.clear()
    if (preservePreparedRasters) preparedByKey.clear() else clearPreparedTextCache()
    paintGeneration++
    edgeFadeGradient = null
    edgeFadeMaskPaint = null
    clearRenderCaches()
  }

  private fun clearRenderCaches() {
    glyphNodeCache.clear()
    gaussianEffectCache.clear()
  }

  private fun preparedTextOf(text: String): PreparedText {
    val key = preparedKeyFor(text)
    preparedByKey[key]?.let { return it }

    val line = lineGeometryOf(text)
    val layout = TransitionLogic.layoutKeyedSlots(
      text,
      formatter.groupingSeparator,
      formatter.decimalSeparator,
      formatter.minusSign,
      line,
    )
    val raster = NumericTextRasterizer.rasterize(
      id = nextRasterId++,
      line = line,
      slots = layout,
      lineHeight = textHeightPx,
      ascent = fmAscent,
    )
    val prepared = PreparedText(layout, raster)
    preparedByKey[key] = prepared
    preparedById[raster.id] = prepared
    return prepared
  }

  private fun clearPreparedTextCache() {
    preparedByKey.clear()
    preparedById.clear()
  }

  private fun prunePreparedTextCache() {
    val referenced = engine.referencedRasterIds()

    if (preparedByKey.size > RASTER_CACHE_TARGET) {
      val iterator = preparedByKey.entries.iterator()
      while (preparedByKey.size > RASTER_CACHE_TARGET && iterator.hasNext()) {
        val entry = iterator.next()
        val id = entry.value.raster.id
        if (id !in referenced) iterator.remove()
      }
    }

    val keepIds = HashSet<Int>(preparedByKey.size + referenced.size)
    for (prepared in preparedByKey.values) keepIds.add(prepared.raster.id)
    keepIds.addAll(referenced)
    preparedById.keys.retainAll(keepIds)
  }

  private fun lineGeometryOf(text: String): TextLineGeometry {
    lineGeometryCache[text]?.let { return it }

    val line = NumericTextTypesetter.typeset(text, textPaint)
    lineGeometryCache[text] = line

    if (lineGeometryCache.size > 48) {
      val iterator = lineGeometryCache.entries.iterator()
      if (iterator.hasNext()) {
        iterator.next()
        iterator.remove()
      }
    }

    return line
  }

  private fun lineWidthOf(text: String): Float = preparedTextOf(text).raster.lineWidth

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
    return if (NumericTextFonts.canRender(probe, formatter.glyphProbe)) bundled else system
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
      formatTransitionPending = false
      val prepared = preparedTextOf(settledText)
      engine.reset(prepared.layout, settledText, textHeightPx, prepared.raster.id, appleBlurLengthPx())
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

  fun setLocale(value: String) = updateFormat { it.copy(locale = value) }
  fun setNumberStyle(value: String) = updateFormat { it.copy(numberStyle = value) }
  fun setCurrency(value: String) = updateFormat { it.copy(currency = value) }
  fun setCurrencyDisplay(value: String) = updateFormat { it.copy(currencyDisplay = value) }
  fun setCurrencySign(value: String) = updateFormat { it.copy(currencySign = value) }
  fun setUseGrouping(value: Boolean) = updateFormat { it.copy(useGrouping = value) }

  fun setMinimumIntegerDigits(value: Int) =
    updateFormat { it.copy(minimumIntegerDigits = value) }

  fun setMinimumFractionDigits(value: Int) =
    updateFormat { it.copy(minimumFractionDigits = value) }

  fun setMaximumFractionDigits(value: Int) =
    updateFormat { it.copy(maximumFractionDigits = value) }

  fun setMinimumSignificantDigits(value: Int) =
    updateFormat { it.copy(minimumSignificantDigits = value) }

  fun setMaximumSignificantDigits(value: Int) =
    updateFormat { it.copy(maximumSignificantDigits = value) }

  private fun reformatAtRest() {
    if (engine.isRunning) {
      startOrRetarget()
      return
    }
    settledText = formatNumber(settledValue)
    targetText = settledText
    val prepared = preparedTextOf(settledText)
    engine.reset(prepared.layout, settledText, textHeightPx, prepared.raster.id, appleBlurLengthPx())
    updateContentDescription()
    requestLayout()
    invalidate()
  }

  fun setFontSize(value: Float) {
    val next = value.coerceAtLeast(4f)
    if (next == numericFontSize) return
    numericFontSize = next
    formatTransitionPending = false
    recalcTextPaint()
    val prepared = preparedTextOf(targetText)
    engine.reset(prepared.layout, targetText, textHeightPx, prepared.raster.id, appleBlurLengthPx())
    requestLayout()
    invalidate()
  }

  fun setFontWeight(value: String) {
    if (value == numericFontWeight) return
    numericFontWeight = value
    formatTransitionPending = false
    recalcTextPaint()
    val prepared = preparedTextOf(targetText)
    engine.reset(prepared.layout, targetText, textHeightPx, prepared.raster.id, appleBlurLengthPx())
    requestLayout()
    invalidate()
  }

  fun setFontFamily(value: String) {
    if (value == numericFontFamily) return
    numericFontFamily = value
    formatTransitionPending = false
    recalcTextPaint()
    val prepared = preparedTextOf(targetText)
    engine.reset(prepared.layout, targetText, textHeightPx, prepared.raster.id, appleBlurLengthPx())
    requestLayout()
    invalidate()
  }

  fun setTextColor(value: Int) {
    if (value == numericTextColor) return
    numericTextColor = value
    textPaint.color = value
    rasterColorFilter = PorterDuffColorFilter(value, PorterDuff.Mode.SRC_IN)
    clearRenderCaches()
    invalidate()
  }

  companion object {
    private const val RASTER_CACHE_TARGET = 12

    /** Relative interpretation: packed byte 32 / 128 = 0.25 of the text line height. */
    private const val APPLE_RELATIVE_BLUR = 0.25f

    /** Below this the glyph is drawn sharp — a sub-pixel blur is cost without an effect. */
    private const val BLUR_MIN_PX = 0.75f

    /** Gaussian radius per unit of the engine's blur length. */
    private const val BLUR_RADIUS_FACTOR = 0.5f

    /**
     * Quantisation of the blur radius, in steps per pixel.
     *
     * The radius has to be bucketed because a RenderEffect allocates and this runs per glyph per
     * frame, but at half-pixel steps the decaying blur walks down its buckets one visible notch at
     * a time and the glyph reads as vibrating while it settles. Eight steps per pixel keeps the
     * cache bounded and puts each notch under the threshold of a visible change.
     */
    private const val BLUR_STEPS_PER_PX = 8f
  }
}
