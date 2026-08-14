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
  private val committedFormatByView = WeakHashMap<NumericTextView, NumericFormatSpec>()

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
   * Fabric may also invoke formatting setters during a transaction where their effective values did
   * not change. Therefore "a formatting setter ran" is not enough to classify the transaction as a
   * structural format change. We stage the props, resolve one final NumericFormatSpec against the
   * last committed spec, and compare the two complete values.
   *
   * Real format changes settle atomically with motion disabled, including a simultaneous value
   * change. Transactions whose resolved format is unchanged keep normal numeric rolling.
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
    val previousFormat = committedFormatByView[view] ?: NumericFormatSpec()
    val finalFormat = props.resolveFormat(previousFormat)
    val formatChanged = finalFormat != previousFormat
    val finalReduceMotion = props.reduceMotion ?: view.numericReduceMotion

    props.direction?.let(view::setDirection)
    props.animationDuration?.let(view::setAnimationDuration)
    props.fontSize?.let(view::setFontSize)
    props.fontWeight?.let(view::setFontWeight)
    props.fontFamily?.let(view::setFontFamily)
    props.textColor?.let(view::setTextColor)

    if (formatChanged) {
      // A real format change changes the identity of the whole rendered string. Keep motion disabled
      // through the value assignment as well: otherwise the old numeric value can be reinterpreted
      // in the new domain (for example 1234.5 -> 123450%) and animated to the new value.
      view.setReduceMotion("always")
      applyFormatDiff(view, previousFormat, finalFormat)
      props.value?.let(view::setValue)
      view.setReduceMotion(finalReduceMotion)
    } else {
      props.reduceMotion?.let(view::setReduceMotion)
      // The formatter is unchanged, so a value update is a genuine numeric transition.
      props.value?.let(view::setValue)
    }

    committedFormatByView[view] = finalFormat
  }

  private fun applyFormatDiff(
    view: NumericTextView,
    previous: NumericFormatSpec,
    next: NumericFormatSpec,
  ) {
    if (next.locale != previous.locale) view.setLocale(next.locale)
    if (next.numberStyle != previous.numberStyle) view.setNumberStyle(next.numberStyle)
    if (next.currency != previous.currency) view.setCurrency(next.currency)
    if (next.currencyDisplay != previous.currencyDisplay) {
      view.setCurrencyDisplay(next.currencyDisplay)
    }
    if (next.currencySign != previous.currencySign) view.setCurrencySign(next.currencySign)
    if (next.useGrouping != previous.useGrouping) view.setUseGrouping(next.useGrouping)
    if (next.trailingDecimalSeparator != previous.trailingDecimalSeparator) {
      view.setTrailingDecimalSeparator(next.trailingDecimalSeparator)
    }
    if (next.minimumIntegerDigits != previous.minimumIntegerDigits) {
      view.setMinimumIntegerDigits(next.minimumIntegerDigits)
    }
    if (next.minimumFractionDigits != previous.minimumFractionDigits) {
      view.setMinimumFractionDigits(next.minimumFractionDigits)
    }
    if (next.maximumFractionDigits != previous.maximumFractionDigits) {
      view.setMaximumFractionDigits(next.maximumFractionDigits)
    }
    if (next.minimumSignificantDigits != previous.minimumSignificantDigits) {
      view.setMinimumSignificantDigits(next.minimumSignificantDigits)
    }
    if (next.maximumSignificantDigits != previous.maximumSignificantDigits) {
      view.setMaximumSignificantDigits(next.maximumSignificantDigits)
    }
  }

  override fun onDropViewInstance(view: NumericTextView) {
    pendingByView.remove(view)
    committedFormatByView.remove(view)
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
      trailingDecimalSeparator = trailingDecimalSeparator ?: base.trailingDecimalSeparator,
    )
  }

  companion object {
    const val NAME = "NumericTextView"
  }
}
