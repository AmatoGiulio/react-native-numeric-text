import { memo } from 'react';
import { Text } from 'react-native';
import { DEFAULT_LOCALE, formatNumber, resolveFormat } from './numberFormat';
import type { NumericTextProps } from './types';

/**
 * The number, formatted, with no animation.
 *
 * Used on platforms without a native renderer (currently web). It formats identically to the
 * native path so snapshots and web renders keep the same number formatting; only the transition is
 * missing.
 */
function NumericTextFallbackImpl(props: NumericTextProps) {
  const { value, locale = DEFAULT_LOCALE, style, testID } = props;
  const formatted = formatNumber(value, locale, resolveFormat(props));

  return (
    <Text style={style} testID={testID}>
      {formatted}
    </Text>
  );
}

export const NumericTextFallback = memo(NumericTextFallbackImpl);
NumericTextFallback.displayName = 'NumericTextFallback';
