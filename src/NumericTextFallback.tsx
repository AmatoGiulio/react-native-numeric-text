import { memo } from 'react';
import { Text } from 'react-native';
import { accessibilityPropsOf } from './accessibilityProps';
import {
  DEFAULT_LOCALE,
  formatNumber,
  resolveFormat,
  splitFractionSpan,
} from './numberFormat';
import type { NumericTextProps } from './types';

/** Static fallback for platforms without the native transition renderer. */
function NumericTextFallbackImpl(props: NumericTextProps) {
  const {
    value,
    locale = DEFAULT_LOCALE,
    style,
    testID,
    fractionColor,
  } = props;
  const format = resolveFormat(props);
  const accessibility = accessibilityPropsOf(props);

  if (fractionColor == null) {
    return (
      <Text {...accessibility} style={style} testID={testID}>
        {formatNumber(value, locale, format)}
      </Text>
    );
  }

  // Two coloured runs in one Text, matching the native `fractionColor` split — the fraction span
  // (decimal separator, fraction digits, trailing affix) drawn in `fractionColor`, the rest in the
  // `style` colour. The whole thing stays one accessible node.
  const { head, fraction } = splitFractionSpan(value, locale, format);

  return (
    <Text {...accessibility} style={style} testID={testID}>
      {head}
      {fraction ? (
        <Text style={{ color: fractionColor }}>{fraction}</Text>
      ) : null}
    </Text>
  );
}

export const NumericTextFallback = memo(NumericTextFallbackImpl);
NumericTextFallback.displayName = 'NumericTextFallback';
