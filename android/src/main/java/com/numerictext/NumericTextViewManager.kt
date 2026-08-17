package com.numerictext

import com.facebook.react.R
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewManagerDelegate
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.viewmanagers.NumericTextViewManagerDelegate
import com.facebook.react.viewmanagers.NumericTextViewManagerInterface
import java.util.WeakHashMap

@ReactModule(name = NumericTextViewManager.NAME)
class NumericTextViewManager : SimpleViewManager<NumericTextView>(),
  NumericTextViewManagerInterface<NumericTextView> {
  private val mDelegate: ViewManagerDelegate<NumericTextView>
  private val pendingByView = WeakHashMap<NumericTextView, PendingProps>()
  private val committedFormatByView = WeakHashMap<NumericTextView, NumericFormatSpec>()
  private val committedValueByView = WeakHashMap<NumericTextView, Double>()

  init {
    mDelegate = NumericTextViewManagerDelegate(this)
  }

  override fun getDelegate(): ViewManagerDelegate<NumericTextView>? = mDelegate

  override fun getName(): String = NAME

  override fun createViewInstance(context: ThemedReactContext): NumericTextView {
    return NumericTextView(context)
  }

  /**
   * React may deliver every changed prop through a separate setter, but a formatter is one logical
   * value. Stage the whole transaction, resolve one final NumericFormatSpec, and hand that complete
   * spec to the View before installing the final numeric target.
   *
   * NumericTextView keeps the previous immutable raster alive across that formatter swap, so the
   * engine transitions directly from the old rendered string to the final rendered string. There is
   * no intermediate "old value interpreted by new formatter" baseline and no opportunity for a
   * half-old currency/locale to enter the persistent stack.
   */
  private fun pending(view: NumericTextView?): PendingProps? {
    if (view == null) return null
    return pendingByView.getOrPut(view) { PendingProps() }
  }

  @ReactProp(name = "value")
  override fun setValue(view: NumericTextView?, value: Double) {
    pending(view)?.value = value
  }

  @ReactProp(name = "direction")
  override fun setDirection(view: NumericTextView?, direction: String?) {
    pending(view)?.direction = direction ?: "automatic"
  }

  @ReactProp(name = "locale")
  override fun setLocale(view: NumericTextView?, locale: String?) {
    pending(view)?.locale = locale ?: "en-US"
  }

  @ReactProp(name = "animationDuration")
  override fun setAnimationDuration(view: NumericTextView?, value: Double) {
    pending(view)?.animationDuration = value
  }

  @ReactProp(name = "reduceMotion")
  override fun setReduceMotion(view: NumericTextView?, mode: String?) {
    pending(view)?.reduceMotion = mode ?: "system"
  }

  @ReactProp(name = "numberStyle")
  override fun setNumberStyle(view: NumericTextView?, value: String?) {
    pending(view)?.numberStyle = value ?: "decimal"
  }

  @ReactProp(name = "currency")
  override fun setCurrency(view: NumericTextView?, value: String?) {
    pending(view)?.currency = value ?: ""
  }

  @ReactProp(name = "currencyDisplay")
  override fun setCurrencyDisplay(view: NumericTextView?, value: String?) {
    pending(view)?.currencyDisplay = value ?: "symbol"
  }

  @ReactProp(name = "currencySign")
  override fun setCurrencySign(view: NumericTextView?, value: String?) {
    pending(view)?.currencySign = value ?: "standard"
  }

  @ReactProp(name = "useGrouping")
  override fun setUseGrouping(view: NumericTextView?, value: Boolean) {
    pending(view)?.useGrouping = value
  }

  @ReactProp(name = "minimumIntegerDigits")
  override fun setMinimumIntegerDigits(view: NumericTextView?, value: Int) {
    pending(view)?.minimumIntegerDigits = value
  }

  @ReactProp(name = "minimumFractionDigits")
  override fun setMinimumFractionDigits(view: NumericTextView?, value: Int) {
    pending(view)?.minimumFractionDigits = value
  }

  @ReactProp(name = "maximumFractionDigits")
  override fun setMaximumFractionDigits(view: NumericTextView?, value: Int) {
    pending(view)?.maximumFractionDigits = value
  }

  @ReactProp(name = "minimumSignificantDigits")
  override fun setMinimumSignificantDigits(view: NumericTextView?, value: Int) {
    pending(view)?.minimumSignificantDigits = value
  }

  @ReactProp(name = "maximumSignificantDigits")
  override fun setMaximumSignificantDigits(view: NumericTextView?, value: Int) {
    pending(view)?.maximumSignificantDigits = value
  }

  @ReactProp(name = "fontSize")
  override fun setFontSize(view: NumericTextView?, value: Float) {
    pending(view)?.fontSize = value
  }

  @ReactProp(name = "fontWeight")
  override fun setFontWeight(view: NumericTextView?, weight: String?) {
    pending(view)?.fontWeight = weight ?: "normal"
  }

  @ReactProp(name = "fontFamily")
  override fun setFontFamily(view: NumericTextView?, family: String?) {
    pending(view)?.fontFamily = family ?: NumericTextFonts.BUNDLED
  }

  @ReactProp(name = "textColor")
  override fun setTextColor(view: NumericTextView?, color: Int?) {
    pending(view)?.textColor = color ?: android.graphics.Color.BLACK
  }

  override fun onAfterUpdateTransaction(view: NumericTextView) {
    super.onAfterUpdateTransaction(view)
    val props = pendingByView.remove(view) ?: return
    val previousFormat = committedFormatByView[view] ?: NumericFormatSpec()
    val finalFormat = props.resolveFormat(previousFormat)
    val formatChanged = finalFormat != previousFormat
    val previousValue = committedValueByView[view]
    val finalValue = props.value ?: previousValue

    props.direction?.let(view::setDirection)
    props.animationDuration?.let(view::setAnimationDuration)
    props.reduceMotion?.let(view::setReduceMotion)
    props.fontSize?.let(view::setFontSize)
    props.fontWeight?.let(view::setFontWeight)
    props.fontFamily?.let(view::setFontFamily)
    props.textColor?.let(view::setTextColor)

    if (formatChanged) {
      val transitionValue = finalValue
      view.setFormatSpec(finalFormat, deferReformat = transitionValue != null)
      transitionValue?.let(view::setValue)
    } else {
      props.value?.let(view::setValue)
    }

    committedFormatByView[view] = finalFormat
    finalValue?.let { committedValueByView[view] = it }

    // NumericTextView supplies the formatted number as its default contentDescription. BaseViewManager
    // stores an explicit React Native accessibilityLabel in this tag; a value/format update happens
    // at the end of the transaction and must not overwrite that consumer-provided label.
    (view.getTag(R.id.accessibility_label) as? String)?.let { view.contentDescription = it }
  }

  override fun onDropViewInstance(view: NumericTextView) {
    pendingByView.remove(view)
    committedFormatByView.remove(view)
    committedValueByView.remove(view)
    super.onDropViewInstance(view)
  }

  private data class PendingProps(
    var value: Double? = null,
    var direction: String? = null,
    var locale: String? = null,
    var animationDuration: Double? = null,
    var reduceMotion: String? = null,
    var numberStyle: String? = null,
    var currency: String? = null,
    var currencyDisplay: String? = null,
    var currencySign: String? = null,
    var useGrouping: Boolean? = null,
    var minimumIntegerDigits: Int? = null,
    var minimumFractionDigits: Int? = null,
    var maximumFractionDigits: Int? = null,
    var minimumSignificantDigits: Int? = null,
    var maximumSignificantDigits: Int? = null,
    var fontSize: Float? = null,
    var fontWeight: String? = null,
    var fontFamily: String? = null,
    var textColor: Int? = null,
  ) {
    fun resolveFormat(base: NumericFormatSpec): NumericFormatSpec = base.copy(
      locale = locale ?: base.locale,
      numberStyle = numberStyle ?: base.numberStyle,
      currency = currency ?: base.currency,
      currencyDisplay = currencyDisplay ?: base.currencyDisplay,
      currencySign = currencySign ?: base.currencySign,
      useGrouping = useGrouping ?: base.useGrouping,
      minimumIntegerDigits = minimumIntegerDigits ?: base.minimumIntegerDigits,
      minimumFractionDigits = minimumFractionDigits ?: base.minimumFractionDigits,
      maximumFractionDigits = maximumFractionDigits ?: base.maximumFractionDigits,
      minimumSignificantDigits = minimumSignificantDigits ?: base.minimumSignificantDigits,
      maximumSignificantDigits = maximumSignificantDigits ?: base.maximumSignificantDigits,
    )
  }

  companion object {
    const val NAME = "NumericTextView"
  }
}
