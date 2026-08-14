package com.numerictext

import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewManagerDelegate
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.viewmanagers.NumericTextViewManagerInterface
import com.facebook.react.viewmanagers.NumericTextViewManagerDelegate

@ReactModule(name = NumericTextViewManager.NAME)
class NumericTextViewManager : SimpleViewManager<NumericTextView>(),
  NumericTextViewManagerInterface<NumericTextView> {
  private val mDelegate: ViewManagerDelegate<NumericTextView>

  init {
    mDelegate = NumericTextViewManagerDelegate(this)
  }

  override fun getDelegate(): ViewManagerDelegate<NumericTextView>? = mDelegate

  override fun getName(): String = NAME

  override fun createViewInstance(context: ThemedReactContext): NumericTextView {
    return NumericTextView(context)
  }

  @ReactProp(name = "value")
  override fun setValue(view: NumericTextView?, value: Double) {
    view?.setValue(value)
  }

  @ReactProp(name = "direction")
  override fun setDirection(view: NumericTextView?, direction: String?) {
    view?.setDirection(direction ?: "automatic")
  }

  @ReactProp(name = "locale")
  override fun setLocale(view: NumericTextView?, locale: String?) {
    view?.setLocale(locale ?: "en-US")
  }

  @ReactProp(name = "animationDuration")
  override fun setAnimationDuration(view: NumericTextView?, value: Double) {
    view?.setAnimationDuration(value)
  }

  @ReactProp(name = "reduceMotion")
  override fun setReduceMotion(view: NumericTextView?, mode: String?) {
    view?.setReduceMotion(mode ?: "system")
  }

  @ReactProp(name = "numberStyle")
  override fun setNumberStyle(view: NumericTextView?, value: String?) {
    view?.setNumberStyle(value ?: "decimal")
  }

  @ReactProp(name = "currency")
  override fun setCurrency(view: NumericTextView?, value: String?) {
    view?.setCurrency(value ?: "")
  }

  @ReactProp(name = "currencyDisplay")
  override fun setCurrencyDisplay(view: NumericTextView?, value: String?) {
    view?.setCurrencyDisplay(value ?: "symbol")
  }

  @ReactProp(name = "currencySign")
  override fun setCurrencySign(view: NumericTextView?, value: String?) {
    view?.setCurrencySign(value ?: "standard")
  }

  @ReactProp(name = "useGrouping")
  override fun setUseGrouping(view: NumericTextView?, value: Boolean) {
    view?.setUseGrouping(value)
  }

  @ReactProp(name = "minimumIntegerDigits")
  override fun setMinimumIntegerDigits(view: NumericTextView?, value: Int) {
    view?.setMinimumIntegerDigits(value)
  }

  @ReactProp(name = "minimumFractionDigits")
  override fun setMinimumFractionDigits(view: NumericTextView?, value: Int) {
    view?.setMinimumFractionDigits(value)
  }

  @ReactProp(name = "maximumFractionDigits")
  override fun setMaximumFractionDigits(view: NumericTextView?, value: Int) {
    view?.setMaximumFractionDigits(value)
  }

  @ReactProp(name = "minimumSignificantDigits")
  override fun setMinimumSignificantDigits(view: NumericTextView?, value: Int) {
    view?.setMinimumSignificantDigits(value)
  }

  @ReactProp(name = "maximumSignificantDigits")
  override fun setMaximumSignificantDigits(view: NumericTextView?, value: Int) {
    view?.setMaximumSignificantDigits(value)
  }

  @ReactProp(name = "fontSize")
  override fun setFontSize(view: NumericTextView?, value: Float) {
    view?.setFontSize(value)
  }

  @ReactProp(name = "fontWeight")
  override fun setFontWeight(view: NumericTextView?, weight: String?) {
    view?.setFontWeight(weight ?: "normal")
  }

  @ReactProp(name = "fontFamily")
  override fun setFontFamily(view: NumericTextView?, family: String?) {
    view?.setFontFamily(family ?: NumericTextFonts.BUNDLED)
  }

  @ReactProp(name = "textColor")
  override fun setTextColor(view: NumericTextView?, color: Int?) {
    view?.setTextColor(color ?: android.graphics.Color.BLACK)
  }


  companion object {
    const val NAME = "NumericTextView"
  }
}
