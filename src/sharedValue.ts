import type { NumericTextSharedValue, NumericTextValue } from './types';

/**
 * Whether [value] is a shared value rather than a plain number.
 *
 * Deliberately does not read `.value`, and deliberately does not look for one of Reanimated's
 * private marker fields. Reading `.value` during render is a side-effect Reanimated documents as one
 * to avoid, and this runs on every render of every instance; the marker fields are internal and have
 * been renamed before. The public prop type is `number | { value: number }`, so "not a number" is
 * the whole test.
 */
export function isSharedValue(
  value: NumericTextValue
): value is NumericTextSharedValue {
  return typeof value === 'object' && value !== null;
}
