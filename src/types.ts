import type { StyleProp, TextStyle } from 'react-native';

/**
 * How to turn the value into the number that is drawn: a subset of `Intl.NumberFormatOptions`,
 * resolved natively by `NumberFormatter` on iOS and `android.icu` on Android.
 *
 * The shape is taken from `number-flow` (https://github.com/barvian/number-flow), which answers
 * the same question on the web with one `format` object rather than a growing row of flat props.
 * Borrowed with it: passing a bound through untouched so `Intl`'s own defaulting rule decides the
 * rest, instead of guessing a default here and handing each platform a number it did not ask for.
 *
 * It is a subset because every option here has to mean the same thing on both platforms at the
 * versions this library supports, and has to survive being animated: the renderers transition the
 * *structure* of a formatted number, so the string has to be produced where it is drawn rather
 * than passed in ready-made.
 *
 * Left out on purpose, and why:
 *
 *  - `notation: 'compact'` (`1.2K`). Both platforms can produce it, but from different CLDR
 *    vintages, so the two would disagree on the string for the same input.
 *  - `signDisplay`, `roundingIncrement`, `unit`. Neither platform exposes them below its floor
 *    (Android's `NumberFormatter` is API 30, and `NumberFormatter` on iOS has no equivalent).
 *  - `currencyDisplay: 'narrowSymbol'`. Same reason.
 *
 * Rounding is fixed at half-away-from-zero on both platforms, which is `Intl`'s own default and
 * neither platform's. It is not an option because the two renderers disagreeing about whether
 * `2.5` reads as `2` or `3` is a bug, not a preference.
 */
export type NumericTextFormat = {
  /**
   * `'currency'` needs [currency] set and falls back to `'decimal'` without it. `'percent'`
   * multiplies by 100 and appends the locale's percent sign, so `0.42` reads as `42%`.
   */
  style?: 'decimal' | 'currency' | 'percent';

  /**
   * ISO 4217 code (`'USD'`, `'EUR'`, `'JPY'`). The symbol, which side of the number it sits on,
   * the space around it, and the currency's own fraction digits all come from the platform's
   * locale data: `'en-US'` gives `$1,234.56` and `'de-DE'` gives `1.234,56 €`.
   */
  currency?: string;

  /**
   * How to write the currency: its symbol (`$1,234.56`), its ISO code (`USD 1,234.56`), or its
   * name in the locale (`1,234.56 US dollars`).
   */
  currencyDisplay?: 'symbol' | 'code' | 'name';

  /**
   * How to write a negative amount. `'accounting'` uses the locale's accounting form, which in
   * most locales means parentheses: `($1,234.56)` rather than `-$1,234.56`. Applied only with
   * `currencyDisplay: 'symbol'`, the one combination both platforms format natively.
   */
  currencySign?: 'standard' | 'accounting';

  /** Group separators (`1,000` vs `1000`). */
  useGrouping?: boolean;

  /**
   * Pads with leading zeros to at least this many integer digits, which is what holds a clock at
   * `05:09` and stops a counter changing width as it crosses a power of ten.
   */
  minimumIntegerDigits?: number;

  /**
   * How many digits to keep after the decimal mark. Left out, a plain number rounds to between 0
   * and 3, a percentage to 0, and an amount of money takes the currency's own count: 2 for
   * `'USD'`, 0 for `'JPY'`, 3 for `'BHD'`.
   *
   * Set [minimumFractionDigits] to hold a fixed number of decimals through a change that would
   * otherwise drop one. That is what keeps `1.50` from reading as `1.5`, and what stops the
   * fraction columns restructuring under a roll that should only have moved digits.
   */
  minimumFractionDigits?: number;
  maximumFractionDigits?: number;

  /**
   * Round to a number of significant digits instead of a number of decimals. Setting either bound
   * takes precedence over the fraction bounds, matching `Intl`.
   */
  minimumSignificantDigits?: number;
  maximumSignificantDigits?: number;
};

/**
 * A number that animates between values the way SwiftUI's
 * `.contentTransition(.numericText())` does: each digit column rolls on its own spring, digits
 * that appear or disappear are born and die rather than sliding, and a rapid run of changes
 * carries its momentum instead of restarting.
 */
export type NumericTextProps = {
  /** The number to display. Changing it animates; the first render does not. */
  value: number;

  /**
   * BCP-47 tag deciding grouping and decimal marks — `'en-US'` → `1,234.5`, `'de-DE'` → `1.234,5`.
   * Defaults to `'en-US'` rather than the device locale, so a layout does not change shape
   * depending on whose phone it renders on.
   */
  locale?: string;

  /**
   * Everything about the shape of the number: currency, percent, grouping, digit bounds. See
   * [NumericTextFormat].
   */
  format?: NumericTextFormat;

  /**
   * Shorthand for `format={{ style: 'currency', currency }}`, which is the common case:
   * `<NumericText value={total} currency="USD" />`. [format] overrides it where the two overlap.
   */
  currency?: string;

  /**
   * Draws the decimal mark after the last digit even though no fraction digit follows it yet.
   *
   * This exists for one case, and it is the case every amount field hits: `value` is a number, and
   * a number cannot hold `7.`. Someone typing `7`, `.`, `5` produces the values 7, 7, 7.5, so the
   * mark they typed has nowhere to live until the digit after it arrives, and the field either
   * swallows the keystroke or grows a second `.` of its own beside the component. A sibling `Text`
   * cannot be made to line up: this view reserves half an em of headroom for the transition's
   * overspill and centres the number inside it, so the gap to the right of the last digit is not a
   * fixed distance, and it moves with the value and the font.
   *
   * Set this from the raw input instead, and the mark becomes a real column: same font, same
   * baseline, keyed as `DEC`, and already in place when the first fraction digit is born beside it.
   *
   * ```tsx
   * <NumericText value={parsed} trailingDecimalSeparator={raw.endsWith('.')} />
   * ```
   *
   * It is a no-op whenever the number already has a decimal mark, so pairing it with
   * `minimumFractionDigits` is safe. The mark is the locale's own, and it is placed after the last
   * digit rather than at the end of the string, so a trailing currency symbol stays outside it.
   */
  trailingDecimalSeparator?: boolean;

  /**
   * Which way digits roll. `'automatic'` rolls up when the value grows and down when it shrinks,
   * which is what SwiftUI does; the other two force a direction regardless of the value.
   */
  direction?: 'automatic' | 'up' | 'down';

  /**
   * Android only. Nominal duration in ms; scales the springs rather than clamping them to an
   * exact time. SwiftUI owns the animation timing on iOS.
   */
  animationDuration?: number;

  /**
   * `'system'` follows the OS reduce-motion setting and cuts to the new value when it is on.
   * `'always'` never animates; `'never'` animates even when the user asked the system not to —
   * which is worth a reason before you reach for it.
   */
  reduceMotion?: 'system' | 'always' | 'never';

  /** Shorthand for the same field of [format]. */
  minimumFractionDigits?: number;
  /** Shorthand for the same field of [format]. */
  maximumFractionDigits?: number;
  /** Shorthand for the same field of [format]. */
  useGrouping?: boolean;

  /**
   * `fontSize`, `fontWeight`, `fontFamily` and `color` are read out and handed to the native
   * renderer, which draws the glyphs itself; everything else applies to the view as usual. When
   * omitted, native font size defaults to 48 and color to black.
   */
  style?: StyleProp<TextStyle>;

  testID?: string;
};
