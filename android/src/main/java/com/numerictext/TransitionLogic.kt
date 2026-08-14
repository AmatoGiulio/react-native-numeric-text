package com.numerictext

enum class TokenKind {
  DIGIT,
  GROUP_SEPARATOR,
  DECIMAL_SEPARATOR,
  SIGN,
  OTHER,
}

data class KeyedSlot(
  val key: String,
  val kind: TokenKind,
  val char: String,
  val centerFromLeft: Float,
  val totalWidth: Float,
  val leftFromLeft: Float,
  val rightFromLeft: Float,
  val utf16Start: Int,
  val utf16End: Int,
)

object TransitionLogic {
  private data class RawToken(
    val text: String,
    val codePoint: Int,
    val utf16Start: Int,
    val utf16End: Int,
  )

  private data class Token(
    val text: String,
    val kind: TokenKind,
    val fractional: Boolean,
    val utf16Start: Int,
    val utf16End: Int,
  )

  /**
   * Integer digits keep visual identity from the left; fractions from the decimal point; anything
   * outside the number keeps it from whichever end of the number it sits against.
   *
   * Affixes are deliberately excluded from numeric punctuation. A currency symbol or localized
   * currency name is arbitrary text: `B/. 1,234.50`, `د.إ. ١٬٢٣٤٫٥٠` and `1,00 US-Dollar` all
   * contain characters that can also be a locale's decimal/group/sign character. Classifying those
   * by character value alone creates duplicate DEC/S keys and can make the rasterizer overwrite a
   * real numeric slice. Structural punctuation is therefore recognized only inside the digit run;
   * a sign must sit outside it and must not be a hyphen joining two letters.
   */
  fun layoutKeyedSlots(
    formatted: String,
    groupSep: Char,
    decimalSep: Char,
    minusSign: Char,
    line: TextLineGeometry,
  ): List<KeyedSlot> {
    require(line.text == formatted) { "TextLineGeometry must belong to the formatted string" }

    val tokens = tokenize(formatted, groupSep, decimalSep, minusSign)
    val firstDigit = tokens.indexOfFirst { it.kind == TokenKind.DIGIT }
    val lastDigit = tokens.indexOfLast { it.kind == TokenKind.DIGIT }

    val integerDigitsToRight = IntArray(tokens.size)
    var digitsToRight = 0
    for (i in tokens.indices.reversed()) {
      integerDigitsToRight[i] = digitsToRight
      if (tokens[i].kind == TokenKind.DIGIT && !tokens[i].fractional) digitsToRight += 1
    }

    val result = ArrayList<KeyedSlot>(tokens.size)
    var integerPosition = 0
    var fractionalPosition = 0

    for (i in tokens.indices) {
      val token = tokens[i]
      val a = line.horizontalAt(token.utf16Start)
      val b = line.horizontalAt(token.utf16End)
      val left = minOf(a, b)
      val right = maxOf(a, b)

      val key = when (token.kind) {
        TokenKind.DIGIT ->
          if (token.fractional) {
            "F${fractionalPosition++}"
          } else {
            "I${integerPosition++}"
          }
        TokenKind.GROUP_SEPARATOR -> "G${integerDigitsToRight[i]}"
        TokenKind.DECIMAL_SEPARATOR -> "DEC"
        TokenKind.SIGN -> "S"
        TokenKind.OTHER -> affixKey(i, firstDigit, lastDigit)
      }

      result.add(
        KeyedSlot(
          key = key,
          kind = token.kind,
          char = token.text,
          centerFromLeft = (left + right) / 2f,
          totalWidth = line.totalWidth,
          leftFromLeft = left,
          rightFromLeft = right,
          utf16Start = token.utf16Start,
          utf16End = token.utf16End,
        )
      )
    }

    return result
  }

  /**
   * A key for a token outside the digits, counted outwards from the nearest end of the number.
   *
   * `P0` is the character immediately before the first digit and `X0` the one immediately after
   * the last, so `$1.00` and `($1.00)` agree that `$` is `P0` and disagree only about the bracket
   * that `($1.00)` also has. A token with no digits to sit against, which a formatter should never
   * produce, falls back to its position in the string.
   */
  private fun affixKey(index: Int, firstDigit: Int, lastDigit: Int): String = when {
    firstDigit >= 0 && index < firstDigit -> "P${firstDigit - index - 1}"
    lastDigit >= 0 && index > lastDigit -> "X${index - lastDigit - 1}"
    else -> "O$index"
  }

  private fun tokenize(
    text: String,
    groupSep: Char,
    decimalSep: Char,
    minusSign: Char,
  ): List<Token> {
    val raw = rawTokens(text)
    if (raw.isEmpty()) return emptyList()

    val firstDigit = raw.indexOfFirst { Character.isDigit(it.codePoint) }
    val lastDigit = raw.indexOfLast { Character.isDigit(it.codePoint) }

    // A real decimal separator is either between the first and last digit, or immediately after
    // the last digit for `trailingDecimalSeparator`. Choosing from the digit run means a dot in a
    // prefix such as Panama's `B/.` can never become DEC.
    val decimalIndex = when {
      firstDigit < 0 -> -1
      else -> {
        val between =
          (firstDigit + 1 until lastDigit)
            .lastOrNull { raw[it].codePoint == decimalSep.code }
        between
          ?: (lastDigit + 1)
            .takeIf { it < raw.size && raw[it].codePoint == decimalSep.code }
          ?: -1
      }
    }

    val integerEnd = if (decimalIndex >= 0) decimalIndex else lastDigit + 1

    return raw.mapIndexed { index, token ->
      val digit = Character.isDigit(token.codePoint)
      val fractional = digit && decimalIndex >= 0 && index > decimalIndex
      val kind = when {
        digit -> TokenKind.DIGIT
        index == decimalIndex -> TokenKind.DECIMAL_SEPARATOR
        firstDigit >= 0 &&
          index > firstDigit &&
          index < integerEnd &&
          token.codePoint == groupSep.code &&
          hasDigitBefore(raw, index, firstDigit) &&
          hasDigitAfter(raw, index, integerEnd) -> TokenKind.GROUP_SEPARATOR
        token.codePoint == minusSign.code &&
          isSignPosition(raw, index, firstDigit, lastDigit) -> TokenKind.SIGN
        else -> TokenKind.OTHER
      }

      Token(
        text = token.text,
        kind = kind,
        fractional = fractional,
        utf16Start = token.utf16Start,
        utf16End = token.utf16End,
      )
    }
  }

  private fun rawTokens(text: String): List<RawToken> {
    val out = ArrayList<RawToken>()
    var utf16Offset = 0
    val iterator = text.codePoints().iterator()

    while (iterator.hasNext()) {
      val cp = iterator.next()
      val char = String(Character.toChars(cp))
      val start = utf16Offset
      utf16Offset += char.length
      out.add(RawToken(char, cp, start, utf16Offset))
    }
    return out
  }

  private fun hasDigitBefore(raw: List<RawToken>, index: Int, lowerBound: Int): Boolean {
    for (i in index - 1 downTo lowerBound) {
      if (Character.isDigit(raw[i].codePoint)) return true
      if (!isDirectionalMark(raw[i].codePoint)) return false
    }
    return false
  }

  private fun hasDigitAfter(raw: List<RawToken>, index: Int, upperBound: Int): Boolean {
    for (i in index + 1 until upperBound) {
      if (Character.isDigit(raw[i].codePoint)) return true
      if (!isDirectionalMark(raw[i].codePoint)) return false
    }
    return false
  }

  private fun isSignPosition(
    raw: List<RawToken>,
    index: Int,
    firstDigit: Int,
    lastDigit: Int,
  ): Boolean {
    if (firstDigit < 0 || (index in firstDigit..lastDigit)) return false

    // A localized currency name can contain a hyphen (`US-Dollar`). That is prose, not the
    // number's sign. A real sign can sit next to a currency symbol or bidi mark, so only reject the
    // unmistakable letter-hyphen-letter case.
    val before = raw.getOrNull(index - 1)?.codePoint
    val after = raw.getOrNull(index + 1)?.codePoint
    return !(before != null && after != null && Character.isLetter(before) && Character.isLetter(after))
  }

  private fun isDirectionalMark(codePoint: Int): Boolean =
    codePoint == 0x061C || codePoint == 0x200E || codePoint == 0x200F
}
