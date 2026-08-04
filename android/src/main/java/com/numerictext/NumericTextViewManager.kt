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

  @ReactProp(name = "useGrouping")
  override fun setUseGrouping(view: NumericTextView?, value: Boolean) {
    view?.setUseGrouping(value)
  }

  @ReactProp(name = "minimumFractionDigits")
  override fun setMinimumFractionDigits(view: NumericTextView?, value: Int) {
    view?.setMinimumFractionDigits(value)
  }

  @ReactProp(name = "maximumFractionDigits")
  override fun setMaximumFractionDigits(view: NumericTextView?, value: Int) {
    view?.setMaximumFractionDigits(value)
  }

  @ReactProp(name = "reduceMotion")
  override fun setReduceMotion(view: NumericTextView?, mode: String?) {
    view?.setReduceMotion(mode ?: "system")
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

  @ReactProp(name = "debugTransitionStrategy")
  override fun setDebugTransitionStrategy(view: NumericTextView?, strategy: String?) {
    view?.setDebugTransitionStrategy(strategy ?: "")
  }

  @ReactProp(name = "debugManualProgress")
  override fun setDebugManualProgress(view: NumericTextView?, progress: Double) {
    view?.setDebugManualProgress(progress.toFloat())
  }

  /**
   * Which engine draws a column: the roll model on a drum, or the stack of transitions.
   *
   * A DEBUG switch, and deliberately global rather than per-view: [NumericRollEngine.stackMode] is
   * a companion property that every column reads, and making it per-instance is a refactor with no
   * user-facing purpose. "auto" (or unset) leaves whatever the recorder's marker file decided, so
   * `.agent/tools/round.sh` keeps working unchanged; only an explicit "drum" or "stack" overrides
   * it. With several views on screen the last one to be configured wins — which is what the example
   * app's single selector is for.
   */
  @ReactProp(name = "debugEngine")
  override fun setDebugEngine(view: NumericTextView?, engine: String?) {
    when (engine) {
      "stack" -> NumericRollEngine.stackMode = true
      "drum" -> NumericRollEngine.stackMode = false
      else -> return
    }
    view?.invalidate()
  }

  companion object {
    const val NAME = "NumericTextView"
  }
}
