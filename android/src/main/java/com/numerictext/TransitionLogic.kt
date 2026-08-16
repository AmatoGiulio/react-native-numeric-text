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
   * Integer digits keep logical identity from the left; fractions from the decimal point. Affix
   * identity, however, is derived from shaped visual geometry rather than logical string order.
   * That distinction matters for bidi formats: an AED affix can live after the digits logically but
   * render to their left. Treating that token as a suffix would incorrectly match it with a former
   * visual suffix and make the column travel horizontally across the number.
   *
   * ICU semantic fields remain the source of truth for token meaning. Visible SIGN/OTHER glyphs are
   * emitted through the DIGIT physics path so currency affixes, signs and suffixes use the exact
   * validated roll, movement, scale, blur, alpha and wave machinery as numeric digits. Numeric
   * separators deliberately keep their first-release physics classification. Directional bidi marks
   * stay in the shaped line but do not become transition slots because they have no visible glyph.
   *
   * Returned slots are ordered by visual X. NumericRollEngine's existing wave therefore traverses the
   * shaped line in screen order, including RTL affixes, without changing any animation constants.
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

    if (tokens.isEmpty()) return emptyList()

    val firstDigit = tokens.indexOfFirst { it.kind == TokenKind.DIGIT }
    val lastDigit = tokens.indexOfLast { it.kind == TokenKind.DIGIT }

    val integerDigitsToRight = IntArray(tokens.size)
    var digitsToRight = 0
    for (i in tokens.indices.reversed()) {
      integerDigitsToRight[i] = digitsToRight
      if (tokens[i].kind == TokenKind.DIGIT && !tokens[i].fractional) digitsToRight += 1
    }

    val lefts = FloatArray(tokens.size)
    val rights = FloatArray(tokens.size)
    val centers = FloatArray(tokens.size)
    for (i in tokens.indices) {
      val (left, right) = line.visualBounds(tokens[i].utf16Start, tokens[i].utf16End)
      lefts[i] = left
      rights[i] = right
      centers[i] = (left + right) / 2f
    }

    val digitIndices = tokens.indices.filter { tokens[it].kind == TokenKind.DIGIT }
    val numericLeft = digitIndices.minOfOrNull { lefts[it] }
    val numericRight = digitIndices.maxOfOrNull { rights[it] }
    val visualEpsilon = 0.001f

    val prefixRank = IntArray(tokens.size) { -1 }
    val suffixRank = IntArray(tokens.size) { -1 }

    if (numericLeft != null && numericRight != null) {
      tokens.indices
        .filter {
          tokens[it].kind == TokenKind.OTHER &&
            rights[it] <= numericLeft + visualEpsilon
        }
        .sortedByDescending { centers[it] }
        .forEachIndexed { rank, tokenIndex -> prefixRank[tokenIndex] = rank }

      tokens.indices
        .filter {
          tokens[it].kind == TokenKind.OTHER &&
            lefts[it] >= numericRight - visualEpsilon
        }
        .sortedBy { centers[it] }
        .forEachIndexed { rank, tokenIndex -> suffixRank[tokenIndex] = rank }
    }

    val result = ArrayList<KeyedSlot>(tokens.size)
    var integerPosition = 0
    var fractionalPosition = 0

    for (i in tokens.indices) {
      val token = tokens[i]
      val key = when (token.kind) {
        TokenKind.DIGIT ->
          if (token.fractional) "F${fractionalPosition++}"
          else "I${integerPosition++}"
        TokenKind.GROUP_SEPARATOR -> structuralKey("G${integerDigitsToRight[i]}", token.text)
        TokenKind.DECIMAL_SEPARATOR -> structuralKey("DEC", token.text)
        TokenKind.SIGN -> "S"
        TokenKind.OTHER -> visualAffixKey(i, prefixRank, suffixRank)
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
          centerFromLeft = centers[i],
          totalWidth = line.totalWidth,
          leftFromLeft = lefts[i],
          rightFromLeft = rights[i],
          utf16Start = token.utf16Start,
          utf16End = token.utf16End,
        )
      )
    }

    return result.sortedWith(
      compareBy<KeyedSlot> { it.centerFromLeft }
        .thenBy { it.leftFromLeft }
        .thenBy { it.utf16Start }
    )
  }

  private fun structuralKey(base: String, glyph: String): String = "$base:$glyph"

  private fun visualAffixKey(
    tokenIndex: Int,
    prefixRank: IntArray,
    suffixRank: IntArray,
  ): String = when {
    prefixRank[tokenIndex] >= 0 -> "P${prefixRank[tokenIndex]}"
    suffixRank[tokenIndex] >= 0 -> "X${suffixRank[tokenIndex]}"
    else -> "O$tokenIndex"
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