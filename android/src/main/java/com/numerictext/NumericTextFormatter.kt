package com.numerictext

import android.icu.math.BigDecimal
import android.icu.text.DecimalFormat
import android.icu.text.DecimalFormatSymbols
import android.icu.text.NumberFormat
import android.icu.util.Currency
import java.text.AttributedCharacterIterator
import java.util.Locale

const val DIGITS_UNSET = -1

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

internal enum class NumericFieldKind {
  INTEGER,
  FRACTION,
  GROUP_SEPARATOR,
  DECIMAL_SEPARATOR,
  SIGN,
}

internal data class NumericSemanticSpan(
  val start: Int,
  val end: Int,
  val kind: NumericFieldKind,
)

private data class NumericSemanticKey(
  val text: String,
  val groupingSeparator: Char,
  val decimalSeparator: Char,
  val minusSign: Char,
)

/**
 * Formats the number and preserves ICU's semantic fields for the transition tokenizer.
 *
 * Currency affixes are arbitrary localized text and can themselves contain '.', ',' or '-'. ICU's
 * `formatToCharacterIterator` labels the actual numeric ranges, so punctuation inside `B/.`,
 * `د.إ.` or `US-Dollar` stays an affix instead of stealing DEC/GROUP/SIGN keys.
 */
internal class NumericTextFormatter private constructor(
  private val format: NumberFormat,
  private val trailing: Boolean,
  val groupingSeparator: Char,
  val decimalSeparator: Char,
  val minusSign: Char,
  val glyphProbe: String,
) {
  fun format(value: Double): String {
    val raw = format.format(value)
    val spans = semanticSpansFor(value)

    if (!trailing || spans?.any { it.kind == NumericFieldKind.DECIMAL_SEPARATOR } == true) {
      if (spans != null) remember(raw, spans)
      return raw
    }

    var end = -1
    var i = 0
    while (i < raw.length) {
      val cp = raw.codePointAt(i)
      val width = Character.charCount(cp)
      if (Character.isDigit(cp)) end = i + width
      i += width
    }

    val insertion = if (end < 0) raw.length else end
    val text = raw.substring(0, insertion) + decimalSeparator + raw.substring(insertion)
    if (spans != null) {
      val shifted = ArrayList<NumericSemanticSpan>(spans.size + 1)
      for (span in spans) {
        shifted.add(
          when {
            span.end <= insertion -> span
            span.start >= insertion -> span.copy(start = span.start + 1, end = span.end + 1)
            else -> span.copy(end = span.end + 1)
          }
        )
      }
      shifted.add(
        NumericSemanticSpan(
          start = insertion,
          end = insertion + 1,
          kind = NumericFieldKind.DECIMAL_SEPARATOR,
        )
      )
      remember(text, shifted.sortedBy { it.start })
    }
    return text
  }

  private fun semanticSpansFor(value: Double): List<NumericSemanticSpan>? = try {
    val iterator = format.formatToCharacterIterator(value)
    val spans = ArrayList<NumericSemanticSpan>()
    var index = iterator.beginIndex
    while (index < iterator.endIndex) {
      iterator.setIndex(index)
      val end = iterator.runLimit
      fieldKind(iterator.attributes)?.let { spans.add(NumericSemanticSpan(index, end, it)) }
      index = end
    }
    spans.takeIf { it.any { span -> span.kind == NumericFieldKind.INTEGER || span.kind == NumericFieldKind.FRACTION } }
  } catch (_: RuntimeException) {
    null
  }

  private fun fieldKind(
    attributes: Map<AttributedCharacterIterator.Attribute, Any>,
  ): NumericFieldKind? = when {
    attributes.containsKey(NumberFormat.Field.SIGN) -> NumericFieldKind.SIGN
    attributes.containsKey(NumberFormat.Field.DECIMAL_SEPARATOR) ->
      NumericFieldKind.DECIMAL_SEPARATOR
    attributes.containsKey(NumberFormat.Field.GROUPING_SEPARATOR) ->
      NumericFieldKind.GROUP_SEPARATOR
    attributes.containsKey(NumberFormat.Field.FRACTION) -> NumericFieldKind.FRACTION
    attributes.containsKey(NumberFormat.Field.INTEGER) -> NumericFieldKind.INTEGER
    else -> null
  }

  private fun remember(text: String, spans: List<NumericSemanticSpan>) {
    rememberSemantics(
      NumericSemanticKey(text, groupingSeparator, decimalSeparator, minusSign),
      spans,
    )
  }

  companion object {
    private val semantics = LinkedHashMap<NumericSemanticKey, List<NumericSemanticSpan>>(64, 0.75f, true)

    fun semanticSpans(
      text: String,
      groupingSeparator: Char,
      decimalSeparator: Char,
      minusSign: Char,
    ): List<NumericSemanticSpan>? = synchronized(semantics) {
      semantics[NumericSemanticKey(text, groupingSeparator, decimalSeparator, minusSign)]
    }

    private fun rememberSemantics(
      key: NumericSemanticKey,
      spans: List<NumericSemanticSpan>,
    ) = synchronized(semantics) {
      semantics[key] = spans
      while (semantics.size > 128) {
        val iterator = semantics.entries.iterator()
        if (!iterator.hasNext()) break
        iterator.next()
        iterator.remove()
      }
    }

    fun of(spec: NumericFormatSpec): NumericTextFormatter {
      val locale = localeOf(spec.locale)
      val currency = currencyOf(spec.currency)
      val money = spec.numberStyle == "currency" && currency != null

      val format = NumberFormat.getInstance(locale, styleOf(spec, money))
      if (money) format.currency = currency
      format.isGroupingUsed = spec.useGrouping
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
      spec.currencySign == "accounting" -> NumberFormat.ACCOUNTINGCURRENCYSTYLE
      else -> NumberFormat.CURRENCYSTYLE
    }

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
      val defaultMin = if (money || spec.numberStyle == "percent") defaultMax else 0

      val (min, max) =
        boundsOf(
          spec.minimumFractionDigits,
          spec.maximumFractionDigits,
          defaultMin,
          defaultMax,
        )
      format.maximumFractionDigits = max
      format.minimumFractionDigits = min
    }

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
