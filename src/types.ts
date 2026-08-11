import type { StyleProp, TextStyle } from 'react-native';

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

  minimumFractionDigits?: number;
  maximumFractionDigits?: number;

  /** Group separators (`1,000` vs `1000`). */
  useGrouping?: boolean;

  /**
   * `fontSize`, `fontWeight`, `fontFamily` and `color` are read out and handed to the native
   * renderer, which draws the glyphs itself; everything else applies to the view as usual. When
   * omitted, native font size defaults to 48 and color to black.
   */
  style?: StyleProp<TextStyle>;

  testID?: string;
};
