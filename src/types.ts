import type { AccessibilityProps, StyleProp, TextStyle } from 'react-native';

/**
 * How to turn the value into the number that is drawn: a deliberately small subset of
 * `Intl.NumberFormatOptions`, resolved natively by `NumberFormatter` on iOS and `android.icu` on
 * Android.
 *
 * Every option kept here must have a stable transition model as well as a formatting meaning.
 * Localized currency names are intentionally deferred: the affix itself can change spelling with
 * the value (`dollar`/`dollars`), while the current engine treats affixes as stable text. Compact
 * notation and narrow currency symbols remain out for the same cross-platform/CLDR reasons as
 * before.
 *
 * Rounding is fixed at half-away-from-zero on both platforms, matching `Intl`'s default.
 */
export type NumericTextFormat = {
  /**
   * `'currency'` needs [currency] set and falls back to `'decimal'` without it. `'percent'`
   * multiplies by 100 and appends the locale's percent sign.
   */
  style?: 'decimal' | 'currency' | 'percent';

  /** ISO 4217 code (`'USD'`, `'EUR'`, `'JPY'`). */
  currency?: string;

  /** Currency symbol (`$1,234.56`) or ISO code (`USD 1,234.56`). */
  currencyDisplay?: 'symbol' | 'code';

  /**
   * How to write a negative amount. `'accounting'` is applied only with
   * `currencyDisplay: 'symbol'`; code display always uses the standard sign form.
   */
  currencySign?: 'standard' | 'accounting';

  /** Group separators (`1,000` vs `1000`). */
  useGrouping?: boolean;

  /** Pads with leading zeros to at least this many integer digits. */
  minimumIntegerDigits?: number;

  /**
   * Fraction bounds. Left out, decimal uses 0..3, percent uses 0, and currency uses its own
   * default fraction count (for example USD 2, JPY 0, BHD 3).
   */
  minimumFractionDigits?: number;
  maximumFractionDigits?: number;

  /** Significant-digit bounds; when present they take precedence over fraction bounds. */
  minimumSignificantDigits?: number;
  maximumSignificantDigits?: number;
};

type NumericTextAccessibilityProps = Pick<
  AccessibilityProps,
  | 'accessible'
  | 'accessibilityLabel'
  | 'accessibilityHint'
  | 'accessibilityRole'
  | 'accessibilityLiveRegion'
  | 'importantForAccessibility'
  | 'screenReaderFocusable'
  | 'accessibilityLabelledBy'
  | 'accessibilityElementsHidden'
  | 'accessibilityViewIsModal'
  | 'accessibilityLanguage'
>;

/**
 * A number that animates between values the way SwiftUI's `.contentTransition(.numericText())`
 * does: each digit column rolls on its own spring and rapid changes retain their motion.
 */
export type NumericTextProps = NumericTextAccessibilityProps & {
  /** The number to display. Changing it animates; the first render does not. */
  value: number;

  /**
   * BCP-47 tag deciding grouping and decimal marks. Defaults to `'en-US'` rather than the device
   * locale so a layout does not silently change with the device language.
   */
  locale?: string;

  /** Everything about the shape of the number. */
  format?: NumericTextFormat;

  /** Shorthand for `format={{ style: 'currency', currency }}`. */
  currency?: string;

  /**
   * Which way digits roll. `'automatic'` rolls up when the value grows and down when it shrinks.
   */
  direction?: 'automatic' | 'up' | 'down';

  /** Android only. Nominal spring duration in ms. */
  animationDuration?: number;

  /**
   * `'system'` follows the OS reduce-motion setting, `'always'` disables animation, and `'never'`
   * animates regardless of the system setting.
   */
  reduceMotion?: 'system' | 'always' | 'never';

  /** Shorthand for the same field of [format]. */
  minimumFractionDigits?: number;
  /** Shorthand for the same field of [format]. */
  maximumFractionDigits?: number;
  /** Shorthand for the same field of [format]. */
  useGrouping?: boolean;

  /**
   * `fontSize`, `fontWeight`, `fontFamily` and `color` are handed to the native renderer; the rest
   * applies to the view as usual. Native font size defaults to 48 and color to black.
   */
  style?: StyleProp<TextStyle>;

  testID?: string;
};
