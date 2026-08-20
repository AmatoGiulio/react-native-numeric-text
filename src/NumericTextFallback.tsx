import { memo, useState } from 'react';
import { Text } from 'react-native';
import { accessibilityPropsOf } from './accessibilityProps';
import {
  DEFAULT_LOCALE,
  formatNumber,
  resolveFormat,
  splitFractionSpan,
} from './numberFormat';
import { requireReanimated } from './optionalReanimated';
import { isSharedValue } from './sharedValue';
import type { NumericTextProps, NumericTextSharedValue } from './types';

/** Static fallback for platforms without the native transition renderer. */
function NumericTextFallbackImpl(props: NumericTextProps) {
  const { value } = props;

  return isSharedValue(value) ? (
    <SharedValueFallback {...props} value={value} />
  ) : (
    <StaticFallback {...props} value={value} />
  );
}

/**
 * There is no transition to drive here, so the shared value is mirrored onto the JS thread and
 * rendered as an ordinary number. This costs a render per change, which is exactly what the native
 * path exists to avoid — but a platform drawing static text has nothing to spend it on.
 */
function SharedValueFallback(
  props: NumericTextProps & { value: NumericTextSharedValue }
) {
  const { value } = props;
  const { runOnJS, useAnimatedReaction } = requireReanimated();
  const [current, setCurrent] = useState(() => value.value);

  useAnimatedReaction(
    () => value.value,
    (next) => {
      runOnJS(setCurrent)(next);
    },
    [value]
  );

  return <StaticFallback {...props} value={current} />;
}

/** One number, in one colour or two — the fraction span in `fractionColor` when it is set. */
function StaticFallback(props: NumericTextProps & { value: number }) {
  const { value, locale = DEFAULT_LOCALE, style, testID, fractionColor } = props;
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
