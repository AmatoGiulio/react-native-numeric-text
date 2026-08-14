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
  private data class Token(
    val text: String,
    val kind: TokenKind,
    val utf16Start: Int,
    val utf16End: Int,
  )

  /**
   * Integer digits keep visual identity from the left; fractions from the decimal point; anything
   * outside the number keeps it from whichever end of the number it sits against.
   *
   * That last rule is what a currency needs. A symbol, an ISO code, a percent sign or an
   * accounting bracket is keyed by its distance from the digits rather than by its offset in the
   * string, so `$999` -> `$1,000` moves one `$` sideways instead of killing it and being born
   * again a digit-width to the left, and `999 €` -> `1.000 €` does the same for a suffix.
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
    val decimalIndex = tokens.indexOfFirst { it.kind == TokenKind.DECIMAL_SEPARATOR }
    val integerEnd = if (decimalIndex >= 0) decimalIndex else tokens.size
    val firstDigit = tokens.indexOfFirst { it.kind == TokenKind.DIGIT }
    val lastDigit = tokens.indexOfLast { it.kind == TokenKind.DIGIT }

    val integerDigitsToRight = IntArray(tokens.size)
    var digitsToRight = 0
    for (i in integerEnd - 1 downTo 0) {
      integerDigitsToRight[i] = digitsToRight
      if (tokens[i].kind == TokenKind.DIGIT) digitsToRight += 1
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
          if (decimalIndex >= 0 && i > decimalIndex) {
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
    val out = ArrayList<Token>()
    var utf16Offset = 0
    val iterator = text.codePoints().iterator()

    while (iterator.hasNext()) {
      val cp = iterator.next()
      val char = String(Character.toChars(cp))
      val start = utf16Offset
      utf16Offset += char.length

      val kind = when {
        cp == groupSep.code -> TokenKind.GROUP_SEPARATOR
        cp == decimalSep.code -> TokenKind.DECIMAL_SEPARATOR
        cp == minusSign.code -> TokenKind.SIGN
        Character.isDigit(cp) -> TokenKind.DIGIT
        else -> TokenKind.OTHER
      }

      out.add(Token(char, kind, start, utf16Offset))
    }

    return out
  }
}
