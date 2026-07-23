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
  const numChars = digitCount + 3;
  const avgCharWidth =
    typeof flattened.fontSize === 'number'
      ? (flattened.fontSize as number) * 0.6
      : 48 * 0.6;
  const estimatedWidth = Math.ceil(numChars * avgCharWidth);
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
