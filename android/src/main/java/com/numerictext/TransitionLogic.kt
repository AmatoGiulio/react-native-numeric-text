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
)

object TransitionLogic {
  private data class Token(val text: String, val kind: TokenKind)

  /** Integer digits keep visual identity from the left; fractions from the decimal point. */
  fun layoutKeyedSlots(
    formatted: String,
    groupSep: Char,
    decimalSep: Char,
    minusSign: Char,
    measure: (String) -> Float,
  ): List<KeyedSlot> {
    val tokens = tokenize(formatted, groupSep, decimalSep, minusSign)
    val widths = tokens.map { measure(it.text) }
    val totalWidth = widths.sum()
    val decimalIndex = tokens.indexOfFirst { it.kind == TokenKind.DECIMAL_SEPARATOR }
    val integerEnd = if (decimalIndex >= 0) decimalIndex else tokens.size

    val integerDigitsToRight = IntArray(tokens.size)
    var digitsToRight = 0
    for (i in integerEnd - 1 downTo 0) {
      integerDigitsToRight[i] = digitsToRight
      if (tokens[i].kind == TokenKind.DIGIT) digitsToRight += 1
    }

    val result = ArrayList<KeyedSlot>(tokens.size)
    var x = 0f
    var integerPosition = 0
    var fractionalPosition = 0

    for (i in tokens.indices) {
      val token = tokens[i]
      val width = widths[i]
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
        TokenKind.OTHER -> "O$i"
      }

      result.add(
        KeyedSlot(
          key = key,
          kind = token.kind,
          char = token.text,
          centerFromLeft = x + width / 2f,
          totalWidth = totalWidth,
        )
      )
      x += width
    }

    return result
  }

  private fun tokenize(
    text: String,
    groupSep: Char,
    decimalSep: Char,
    minusSign: Char,
  ): List<Token> {
    val out = ArrayList<Token>()
    val iterator = text.codePoints().iterator()

    while (iterator.hasNext()) {
      val cp = iterator.next()
      val char = String(Character.toChars(cp))
      val kind = when {
        cp == groupSep.code -> TokenKind.GROUP_SEPARATOR
        cp == decimalSep.code -> TokenKind.DECIMAL_SEPARATOR
        cp == minusSign.code -> TokenKind.SIGN
        Character.isDigit(cp) -> TokenKind.DIGIT
        else -> TokenKind.OTHER
      }
      out.add(Token(char, kind))
    }

    return out
  }
}
