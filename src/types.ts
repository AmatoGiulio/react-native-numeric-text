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

  /** Nominal duration in ms. The springs settle on their own, so this scales rather than clamps. */
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
   * renderer, which draws the glyphs itself; everything else applies to the view as usual.
   */
  style?: StyleProp<TextStyle>;

  testID?: string;
};

/**
 * Knobs the example app uses to drive the renderer by hand while comparing it against the
 * reference. Not part of the supported surface — they can change or vanish in any release.
 *
 * @internal
 */
export type NumericTextDebugProps = {
  debugTransitionStrategy?: 'whole_run' | 'changed_run' | 'per_glyph';
  debugManualProgress?: number;
};
