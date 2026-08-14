package com.numerictext

import android.icu.math.BigDecimal
import android.icu.text.DecimalFormat
import android.icu.text.DecimalFormatSymbols
import android.icu.text.NumberFormat
import android.icu.util.Currency
import java.util.Locale

/** A digit bound the caller left out, so the format applies its own default. */
const val DIGITS_UNSET = -1

/**
 * The formatting props, exactly as `src/numberFormat.ts` resolved them.
 *
 * They arrive one at a time from the view manager, so the spec is a value the view can copy with
 * one field changed and hand back to [NumericTextFormatter.of].
 */
internal data class NumericFormatSpec(
  val locale: String = "en-US",
  val numberStyle: String = "decimal",
  val currency: String = "",
  val currencyDisplay: String = "symbol",
  val currencySign: String = "standard",
  val useGrouping: Boolean = true,
  val minimumIntegerDigits: Int = DIGITS_UNSET,
  val minimumFractionDigits: Int = DIGITS_UNSET,
  val maximumFractionDigits: Int = DIGITS_UNSET,
  val minimumSignificantDigits: Int = DIGITS_UNSET,
  val maximumSignificantDigits: Int = DIGITS_UNSET,
  val trailingDecimalSeparator: Boolean = false,
)

/**
 * Formats the number, and reports the marks the transition has to key on.
 *
 * `android.icu` rather than `java.text` throughout. The ISO-code, currency-name and accounting
 * forms are ICU styles with no `java.text` equivalent at this library's minimum SDK, and mixing
 * the two packages would leave the separators read from one formatter and the string produced by
 * another. Android's `java.text` delegates here anyway, so the plain decimal case is unchanged.
 *
 * The separators matter as much as the string. `TransitionLogic` splits the formatted line on
 * them, and a currency format may use the locale's *monetary* decimal mark rather than its
 * ordinary one; keying on the wrong character turns the fraction digits into unkeyed punctuation
 * and the decimal boundary stops holding still under a roll.
 */
internal class NumericTextFormatter private constructor(
  private val format: NumberFormat,
  private val trailing: Boolean,
  val groupingSeparator: Char,
  val decimalSeparator: Char,
  val minusSign: Char,
  /**
   * Every character this formatter can put on screen, for the bundled font's coverage check. It
   * is produced by formatting rather than by listing, so a currency symbol, an ISO code's letters
   * and an accounting form's parentheses are all included without enumerating them.
   */
  val glyphProbe: String,
) {

  /**
   * The formatted number, with the decimal mark held after the last digit when the caller asked
   * for it and the format produced none.
   *
   * After the last *digit*, not at the end of the string: `de-DE` writes `1.234 €`, and a mark
   * appended blindly would land beyond the currency symbol.
   */
  fun format(value: Double): String {
    val text = format.format(value)
    if (!trailing || text.indexOf(decimalSeparator) >= 0) return text

    var end = -1
    var i = 0
    while (i < text.length) {
      val cp = text.codePointAt(i)
      val width = Character.charCount(cp)
      if (Character.isDigit(cp)) end = i + width
      i += width
    }
    return if (end < 0) text + decimalSeparator
    else text.substring(0, end) + decimalSeparator + text.substring(end)
  }

  companion object {
    fun of(spec: NumericFormatSpec): NumericTextFormatter {
      val locale = localeOf(spec.locale)
      val currency = currencyOf(spec.currency)
      val money = spec.numberStyle == "currency" && currency != null

      val format = NumberFormat.getInstance(locale, styleOf(spec, money))
      if (money) format.currency = currency
      format.isGroupingUsed = spec.useGrouping

      // Intl rounds halves away from zero; ICU and Foundation both default to half-even. Follow
      // Intl, so `2.5` at zero decimals reads as `3` on Android, on iOS, and on the web fallback
      // rather than as `3`, `2`, `2`.
      format.roundingMode = BigDecimal.ROUND_HALF_UP

      if (spec.minimumIntegerDigits >= 0) {
        format.minimumIntegerDigits = spec.minimumIntegerDigits
      }
      applyDigitBounds(format, spec, money, currency)

      val symbols = symbolsOf(format, locale)
      return NumericTextFormatter(
        format = format,
        trailing = spec.trailingDecimalSeparator,
        groupingSeparator =
          if (money) symbols.monetaryGroupingSeparator else symbols.groupingSeparator,
        decimalSeparator =
          if (money) symbols.monetaryDecimalSeparator else symbols.decimalSeparator,
        minusSign = symbols.minusSign,
        glyphProbe = glyphProbeOf(format, symbols),
      )
    }

    private fun styleOf(spec: NumericFormatSpec, money: Boolean): Int = when {
      !money ->
        if (spec.numberStyle == "percent") NumberFormat.PERCENTSTYLE
        else NumberFormat.NUMBERSTYLE
      spec.currencyDisplay == "code" -> NumberFormat.ISOCURRENCYSTYLE
      spec.currencyDisplay == "name" -> NumberFormat.PLURALCURRENCYSTYLE
      // Accounting is a distinct CLDR pattern, so it is a style rather than a modifier, and it
      // only exists alongside the symbol. `code` and `name` above therefore win over it.
      spec.currencySign == "accounting" -> NumberFormat.ACCOUNTINGCURRENCYSTYLE
      else -> NumberFormat.CURRENCYSTYLE
    }

    /**
     * ECMA-402's rule for resolving digit bounds, so the three implementations of this component
     * round the same number to the same string.
     *
     * Significant digits win over fraction digits when either bound is given. Otherwise a bound
     * that was left out is filled from the style: 0 and 3 for a plain number, 0 and 0 for a
     * percentage, and the currency's own count twice over for money, which is what makes `USD`
     * show `1.50` and `JPY` show `150` without either being asked for.
     */
    private fun applyDigitBounds(
      format: NumberFormat,
      spec: NumericFormatSpec,
      money: Boolean,
      currency: Currency?,
    ) {
      val decimal = format as? DecimalFormat
      if (
        decimal != null &&
        (spec.minimumSignificantDigits >= 0 || spec.maximumSignificantDigits >= 0)
      ) {
        val (min, max) =
          boundsOf(spec.minimumSignificantDigits, spec.maximumSignificantDigits, 1, 21)
        decimal.setSignificantDigitsUsed(true)
        decimal.maximumSignificantDigits = max
        decimal.minimumSignificantDigits = min
        return
      }

      val defaultMax = when {
        money -> (currency?.defaultFractionDigits ?: 2).coerceAtLeast(0)
        spec.numberStyle == "percent" -> 0
        else -> 3
      }
      // A plain number is the only style that may drop a trailing zero, so it is the only one
      // whose minimum differs from its maximum.
      val defaultMin = if (money || spec.numberStyle == "percent") defaultMax else 0

      val (min, max) =
        boundsOf(
          spec.minimumFractionDigits,
          spec.maximumFractionDigits,
          defaultMin,
          defaultMax,
        )

      // Maximum first: ICU pulls the minimum down to meet a lower maximum, and the pair here is
      // already ordered, so setting it this way round never disturbs the other bound.
      format.maximumFractionDigits = max
      format.minimumFractionDigits = min
    }

    /**
     * A given bound with the other one filled in, never crossed.
     *
     * `Intl` throws when a caller gives a maximum below a minimum. This clamps instead: a number
     * with one decimal more than was asked for beats a formatter that refuses to draw.
     */
    private fun boundsOf(
      min: Int,
      max: Int,
      defaultMin: Int,
      defaultMax: Int,
    ): Pair<Int, Int> = when {
      min >= 0 && max >= 0 -> min to maxOf(min, max)
      min >= 0 -> min to maxOf(defaultMax, min)
      max >= 0 -> minOf(defaultMin, max) to max
      else -> defaultMin to defaultMax
    }

    /** The formatter's own symbols where it has them, so the marks belong to the string drawn. */
    private fun symbolsOf(format: NumberFormat, locale: Locale): DecimalFormatSymbols =
      (format as? DecimalFormat)?.decimalFormatSymbols
        ?: DecimalFormatSymbols.getInstance(locale)

    private fun glyphProbeOf(
      format: NumberFormat,
      symbols: DecimalFormatSymbols,
    ): String = buildString {
      val zero = symbols.zeroDigit
      for (offset in 0..9) append(zero + offset)
      append(symbols.groupingSeparator)
      append(symbols.decimalSeparator)
      append(symbols.monetaryGroupingSeparator)
      append(symbols.monetaryDecimalSeparator)
      append(symbols.minusSign)
      // A negative and a zero between them carry the sign, the affix and any accounting bracket.
      append(runCatching { format.format(-1234.5) }.getOrDefault(""))
      append(runCatching { format.format(0.0) }.getOrDefault(""))
    }

    private fun currencyOf(code: String): Currency? {
      if (code.isEmpty()) return null
      return try {
        Currency.getInstance(code)
      } catch (_: IllegalArgumentException) {
        null
      } catch (_: NullPointerException) {
        null
      }
    }

    private fun localeOf(tag: String): Locale = try {
      Locale.forLanguageTag(tag.replace("_", "-")).takeIf { it.language.isNotEmpty() }
        ?: Locale.US
    } catch (_: Exception) {
      Locale.US
    }
  }
}
