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
  val semanticKind: TokenKind = kind,
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
   * ICU semantic fields are the source of truth when available. A currency affix is arbitrary text
   * and may itself contain '.', ',' or '-'; character equality cannot tell whether those marks are
   * numeric structure or currency prose.
   *
   * semanticKind remains the source of identity. Visible SIGN/OTHER glyphs are emitted through the
   * DIGIT physics path so currency affixes, signs and suffixes use the exact validated roll, movement,
   * scale, blur, alpha and wave machinery as the digits. Numeric separators deliberately keep their
   * existing physics classification so first-release carries such as 999 -> 1,000 retain their
   * previously validated timing. Directional bidi marks stay in the shaped line but do not become
   * transition slots, because they are invisible and must not consume a wave phase.
   */
  internal fun layoutKeyedSlots(
    formatted: String,
    groupSep: Char,
    decimalSep: Char,
    minusSign: Char,
    line: TextLineGeometry,
    semanticSpans: List<NumericSemanticSpan>? = null,
  ): List<KeyedSlot> {
    require(line.text == formatted) { "TextLineGeometry must belong to the formatted string" }

    val semantics = semanticSpans ?: NumericTextFormatter.semanticSpans(
      formatted,
      groupSep,
      decimalSep,
      minusSign,
    )
    val tokens =
      (
        if (semantics != null) tokenizeSemantically(formatted, semantics)
        else tokenizeFallback(formatted, groupSep, decimalSep, minusSign)
      ).filterNot(::isDirectionalToken)
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
      val (left, right) = line.visualBounds(token.utf16Start, token.utf16End)

      val key = when (token.kind) {
        TokenKind.DIGIT ->
          if (token.fractional) "F${fractionalPosition++}"
          else "I${integerPosition++}"
        TokenKind.GROUP_SEPARATOR -> structuralKey("G${integerDigitsToRight[i]}", token.text)
        TokenKind.DECIMAL_SEPARATOR -> structuralKey("DEC", token.text)
        TokenKind.SIGN -> "S"
        TokenKind.OTHER -> affixKey(i, firstDigit, lastDigit)
      }
      val physicsKind = when (token.kind) {
        TokenKind.SIGN, TokenKind.OTHER -> TokenKind.DIGIT
        else -> token.kind
      }

      result.add(
        KeyedSlot(
          key = key,
          kind = physicsKind,
          semanticKind = token.kind,
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

  private fun structuralKey(base: String, glyph: String): String = "$base:$glyph"

  private fun affixKey(index: Int, firstDigit: Int, lastDigit: Int): String = when {
    firstDigit >= 0 && index < firstDigit -> "P${firstDigit - index - 1}"
    lastDigit >= 0 && index > lastDigit -> "X${index - lastDigit - 1}"
    else -> "O$index"
  }

  private fun tokenizeSemantically(
    text: String,
    spans: List<NumericSemanticSpan>,
  ): List<Token> = rawTokens(text).map { raw ->
    val span = spans.firstOrNull { raw.utf16Start >= it.start && raw.utf16End <= it.end }
    val kind = when (span?.kind) {
      NumericFieldKind.INTEGER, NumericFieldKind.FRACTION ->
        if (Character.isDigit(raw.codePoint)) TokenKind.DIGIT else TokenKind.OTHER
      NumericFieldKind.GROUP_SEPARATOR -> TokenKind.GROUP_SEPARATOR
      NumericFieldKind.DECIMAL_SEPARATOR -> TokenKind.DECIMAL_SEPARATOR
      NumericFieldKind.SIGN -> TokenKind.SIGN
      null -> TokenKind.OTHER
    }
    Token(
      text = raw.text,
      kind = kind,
      fractional = span?.kind == NumericFieldKind.FRACTION,
      utf16Start = raw.utf16Start,
      utf16End = raw.utf16End,
    )
  }

  /** Defensive path if ICU failed to provide fields. */
  private fun tokenizeFallback(
    text: String,
    groupSep: Char,
    decimalSep: Char,
    minusSign: Char,
  ): List<Token> {
    val raw = rawTokens(text)
    if (raw.isEmpty()) return emptyList()

    val firstDigit = raw.indexOfFirst { Character.isDigit(it.codePoint) }
    val lastDigit = raw.indexOfLast { Character.isDigit(it.codePoint) }
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
        token.codePoint == minusSign.code && isSignPosition(raw, index, firstDigit, lastDigit) ->
          TokenKind.SIGN
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

  private fun isDirectionalToken(token: Token): Boolean =
    isDirectionalMark(token.text.codePointAt(0))

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
    val before = raw.getOrNull(index - 1)?.codePoint
    val after = raw.getOrNull(index + 1)?.codePoint
    return !(before != null && after != null && Character.isLetter(before) && Character.isLetter(after))
  }

  private fun isDirectionalMark(codePoint: Int): Boolean =
    codePoint == 0x061C || codePoint == 0x200E || codePoint == 0x200F
}
