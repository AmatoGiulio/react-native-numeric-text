import { memo, useEffect, useRef, useState } from 'react';
import { Platform } from 'react-native';
import NumericTextViewNativeComponent from './NumericTextViewNativeComponent';
import { NumericTextFallback } from './NumericTextFallback';
import { measureBox, widest, type Box } from './measureBox';
import { resolveTextStyle } from './resolveTextStyle';
import type { NumericTextDebugProps, NumericTextProps } from './types';

type Props = NumericTextProps & NumericTextDebugProps;

/**
 * Only Android draws today — `ios/NumericTextView.mm` is still a placeholder that renders an empty
 * view, so routing iOS to the native component would show nothing at all. Until it calls the real
 * `.contentTransition(.numericText())`, iOS gets the static fallback: the right number, no
 * animation, rather than a blank space where a number should be.
 */
const HAS_NATIVE_RENDERER = Platform.OS === 'android';

function NumericTextViewImpl({
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
  const text = resolveTextStyle(style);

  const formatted = value.toLocaleString(locale, {
    minimumFractionDigits,
    maximumFractionDigits,
    useGrouping,
  });
  const box = useShrinkHeldBox(
    measureBox(formatted, text.fontSize),
    Math.max(animationDuration, 500) + 400
  );

  if (!HAS_NATIVE_RENDERER) {
    return (
      <NumericTextFallback
        value={value}
        locale={locale}
        minimumFractionDigits={minimumFractionDigits}
        maximumFractionDigits={maximumFractionDigits}
        useGrouping={useGrouping}
        style={style}
        testID={testID}
      />
    );
  }

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
      fontSize={text.fontSize}
      fontWeight={text.fontWeight}
      fontFamily={text.fontFamily}
      textColor={text.textColor}
      testID={testID}
      debugTransitionStrategy={debugTransitionStrategy}
      debugManualProgress={
        typeof debugManualProgress === 'number'
          ? debugManualProgress
          : undefined
      }
      style={[style, box]}
    />
  );
}

/**
 * The box the view should reserve: grows with the number immediately, shrinks only after
 * [holdMs], so a departing wider number is not clipped while it is still fading out.
 */
function useShrinkHeldBox(target: Box, holdMs: number): Box {
  const [held, setHeld] = useState(target);
  // Read inside the effect rather than depending on the object, which is new every render.
  const targetRef = useRef(target);
  targetRef.current = target;

  const grew =
    target.minWidth >= held.minWidth && target.minHeight >= held.minHeight;
  const settled =
    target.minWidth === held.minWidth && target.minHeight === held.minHeight;

  useEffect(() => {
    if (settled) return;
    if (grew) {
      setHeld(targetRef.current);
      return;
    }
    const timer = setTimeout(() => setHeld(targetRef.current), holdMs);
    return () => clearTimeout(timer);
  }, [settled, grew, target.minWidth, target.minHeight, holdMs]);

  return settled ? target : widest(target, held);
}

/**
 * Re-renders only when a prop actually differs. The value changes far more often than anything
 * else here, and every render of a parent would otherwise walk this whole tree to produce an
 * identical element.
 */
export const NumericTextView = memo(NumericTextViewImpl);
NumericTextView.displayName = 'NumericTextView';
