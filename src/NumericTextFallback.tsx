import { memo } from 'react';
import { Text } from 'react-native';
import type { NumericTextProps } from './types';

/**
 * The number, formatted, with no animation.
 *
 * Used on every platform without a native renderer — web, and iOS until its native view is more
 * than a placeholder. It formats identically to the native path so a snapshot or a web render
 * matches what a phone eventually draws; only the transition is missing.
 *
 * This lives in its own file rather than in `NumericTextView.tsx` because the native entry point
 * needs it too, and `./NumericTextView` resolves back to `NumericTextView.native.tsx` there.
 */
function NumericTextFallbackImpl({
  value,
  locale = 'en-US',
  minimumFractionDigits = 0,
  maximumFractionDigits = 3,
  useGrouping = true,
  style,
  testID,
}: NumericTextProps) {
  const formatted = value.toLocaleString(locale, {
    minimumFractionDigits,
    maximumFractionDigits,
    useGrouping,
  });
  return (
    <Text style={style} testID={testID}>
      {formatted}
    </Text>
  );
}

export const NumericTextFallback = memo(NumericTextFallbackImpl);
NumericTextFallback.displayName = 'NumericTextFallback';
