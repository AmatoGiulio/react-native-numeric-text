package com.numerictext

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
   * value. Applying locale/currency/digit options one-by-one lets intermediate formats retarget the
   * persistent glyph stack, so an affix from the previous format can survive into the next one.
   *
   * Stage the whole React transaction here and commit it once from onAfterUpdateTransaction. Format
   * changes are settled synchronously with motion temporarily disabled; value is applied last, so a
   * simultaneous format + value render performs exactly one numeric transition using the final
   * formatter.
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

  @ReactProp(name = "trailingDecimalSeparator")
  override fun setTrailingDecimalSeparator(view: NumericTextView?, value: Boolean) {
    pending(view)?.trailingDecimalSeparator = value
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
    val formatChanged = props.hasFormatProps()
    val finalReduceMotion = props.reduceMotion ?: view.numericReduceMotion

    props.direction?.let(view::setDirection)
    props.animationDuration?.let(view::setAnimationDuration)
    props.fontSize?.let(view::setFontSize)
    props.fontWeight?.let(view::setFontWeight)
    props.fontFamily?.let(view::setFontFamily)
    props.textColor?.let(view::setTextColor)

    if (formatChanged) {
      // The format is structural. Do not let intermediate formatter states enter the roll engine.
      view.setReduceMotion("always")
      props.locale?.let(view::setLocale)
      props.numberStyle?.let(view::setNumberStyle)
      props.currency?.let(view::setCurrency)
      props.currencyDisplay?.let(view::setCurrencyDisplay)
      props.currencySign?.let(view::setCurrencySign)
      props.useGrouping?.let(view::setUseGrouping)
      props.trailingDecimalSeparator?.let(view::setTrailingDecimalSeparator)
      props.minimumIntegerDigits?.let(view::setMinimumIntegerDigits)
      props.minimumFractionDigits?.let(view::setMinimumFractionDigits)
      props.maximumFractionDigits?.let(view::setMaximumFractionDigits)
      props.minimumSignificantDigits?.let(view::setMinimumSignificantDigits)
      props.maximumSignificantDigits?.let(view::setMaximumSignificantDigits)
      view.setReduceMotion(finalReduceMotion)
    } else {
      props.reduceMotion?.let(view::setReduceMotion)
    }

    // Value is deliberately last: it is the only part of the transaction allowed to roll.
    props.value?.let(view::setValue)
  }

  override fun onDropViewInstance(view: NumericTextView) {
    pendingByView.remove(view)
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
    var trailingDecimalSeparator: Boolean? = null,
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
    fun hasFormatProps(): Boolean =
      locale != null ||
        numberStyle != null ||
        currency != null ||
        currencyDisplay != null ||
        currencySign != null ||
        useGrouping != null ||
        trailingDecimalSeparator != null ||
        minimumIntegerDigits != null ||
        minimumFractionDigits != null ||
        maximumFractionDigits != null ||
        minimumSignificantDigits != null ||
        maximumSignificantDigits != null
  }

  companion object {
    const val NAME = "NumericTextView"
  }
}
