import { useEffect, useState } from 'react';
import NumericTextViewNativeComponent from './NumericTextViewNativeComponent';

type Props = {
  value: number;
  locale?: string;
  direction?: 'automatic' | 'up' | 'down';
  animationDuration?: number;
  reduceMotion?: 'system' | 'always' | 'never';
  minimumFractionDigits?: number;
  maximumFractionDigits?: number;
  useGrouping?: boolean;
  style?: Record<string, unknown>;
  testID?: string;
  debugTransitionStrategy?: 'whole_run' | 'changed_run' | 'per_glyph';
  debugManualProgress?: number;
};

export function NumericTextView({
  value,
  locale = 'en-US',
  direction = 'automatic',
  animationDuration = 80,
  reduceMotion = 'system',
  minimumFractionDigits = 0,
  maximumFractionDigits = 3,
  useGrouping = true,
  style,
  testID,
  debugTransitionStrategy,
  debugManualProgress,
}: Props) {
  const flattened = style ?? {};

  const fontSize =
    typeof flattened.fontSize === 'number'
      ? (flattened.fontSize as number)
      : undefined;

  const displayValue = Math.abs(Math.round(value));
  const digitCount = String(displayValue).length;
  // `+3` covers grouping separators and a sign. Room for the transition's own overspill (blur halo,
  // outward-drifting dying glyphs) is added by the native view's own measurement instead, so a
  // whole extra character of layout is not claimed here.
  const numChars = digitCount + 3;

  // A shrink (9,999 → 1) re-renders with the narrow value immediately, while the wide outgoing
  // number is still fading on screen — so the box must not follow the value down until the
  // transition has finished. It may grow at once; it only shrinks late.
  const [boxChars, setBoxChars] = useState(numChars);
  const shrinkHoldMs = Math.max(animationDuration, 500) + 400;
  useEffect(() => {
    if (numChars >= boxChars) {
      setBoxChars(numChars);
      return;
    }
    const timer = setTimeout(() => setBoxChars(numChars), shrinkHoldMs);
    return () => clearTimeout(timer);
  }, [numChars, boxChars, shrinkHoldMs]);

  const avgCharWidth =
    typeof flattened.fontSize === 'number'
      ? (flattened.fontSize as number) * 0.6
      : 48 * 0.6;
  const estimatedWidth = Math.ceil(Math.max(boxChars, numChars) * avgCharWidth);
  const estimatedHeight = Math.ceil((fontSize ?? 48) * 1.5);

  return (
    <NumericTextViewNativeComponent
      value={value}
      direction={direction}
      locale={locale}
      animationDuration={animationDuration}
      useGrouping={useGrouping}
      minimumFractionDigits={minimumFractionDigits}
      maximumFractionDigits={maximumFractionDigits}
      reduceMotion={reduceMotion}
      fontSize={
        typeof flattened.fontSize === 'number'
          ? (flattened.fontSize as number)
          : undefined
      }
      fontWeight={
        typeof flattened.fontWeight === 'string'
          ? (flattened.fontWeight as string)
          : undefined
      }
      fontFamily={
        typeof flattened.fontFamily === 'string'
          ? (flattened.fontFamily as string)
          : undefined
      }
      textColor={
        typeof flattened.color === 'string'
          ? (flattened.color as string)
          : undefined
      }
      testID={testID}
      debugTransitionStrategy={debugTransitionStrategy}
      debugManualProgress={
        typeof debugManualProgress === 'number'
          ? debugManualProgress
          : undefined
      }
      style={[
        style,
        {
          minWidth: estimatedWidth,
          minHeight: estimatedHeight,
        },
      ]}
    />
  );
}
