import { memo } from 'react';
import { Text } from 'react-native';
import { accessibilityPropsOf } from './accessibilityProps';
import { DEFAULT_LOCALE, formatNumber, resolveFormat } from './numberFormat';
import type { NumericTextProps } from './types';

/** Static fallback for platforms without the native transition renderer. */
function NumericTextFallbackImpl(props: NumericTextProps) {
  const { value, locale = DEFAULT_LOCALE, style, testID } = props;
  const formatted = formatNumber(value, locale, resolveFormat(props));

  return (
    <Text {...accessibilityPropsOf(props)} style={style} testID={testID}>
      {formatted}
    </Text>
  );
}

export const NumericTextFallback = memo(NumericTextFallbackImpl);
NumericTextFallback.displayName = 'NumericTextFallback';
