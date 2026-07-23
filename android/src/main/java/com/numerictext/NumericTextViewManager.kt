package com.numerictext

import android.graphics.Color
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

  override fun getDelegate(): ViewManagerDelegate<NumericTextView>? {
    return mDelegate
  }

  override fun getName(): String {
    return NAME
  }

  public override fun createViewInstance(context: ThemedReactContext): NumericTextView {
    return NumericTextView(context)
  }

  @ReactProp(name = "color")
  override fun setColor(view: NumericTextView?, color: Int?) {
    view?.setBackgroundColor(color ?: Color.TRANSPARENT)
  }

  companion object {
    const val NAME = "NumericTextView"
  }
}
