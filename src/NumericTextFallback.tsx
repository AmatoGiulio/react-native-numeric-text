import { memo } from 'react';
import { Text } from 'react-native';
import type { NumericTextProps } from './types';

/**
 * The number, formatted, with no animation.
 *
 * Used on platforms without a native renderer (currently web). It formats identically to the
 * native path so snapshots and web renders keep the same number formatting; only the transition is
 * missing.
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
