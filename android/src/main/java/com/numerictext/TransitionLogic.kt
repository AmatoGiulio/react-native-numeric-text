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

  /** Integer digits keep visual identity from the left; fractions from the decimal point. */
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
        TokenKind.OTHER -> "O$i"
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
