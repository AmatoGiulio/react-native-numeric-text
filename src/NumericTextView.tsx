import { Text } from 'react-native';

type Props = {
  value: number;
  locale?: string;
  direction?: string;
  animationDuration?: number;
  reduceMotion?: string;
  minimumFractionDigits?: number;
  maximumFractionDigits?: number;
  useGrouping?: boolean;
  style?: Record<string, unknown>;
  debugTransitionStrategy?: string;
  debugManualProgress?: number;
};

export function NumericTextView({
  value,
  locale = 'en-US',
  direction: _direction,
  animationDuration: _animationDuration,
  reduceMotion: _reduceMotion,
  minimumFractionDigits = 0,
  maximumFractionDigits = 3,
  useGrouping = true,
  style,
  debugTransitionStrategy: _debugTransitionStrategy,
  debugManualProgress: _debugManualProgress,
}: Props) {
  const formatted = value.toLocaleString(locale, {
    minimumFractionDigits,
    maximumFractionDigits,
    useGrouping,
  });
  return <Text style={style}>{formatted}</Text>;
}
