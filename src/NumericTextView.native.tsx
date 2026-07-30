import { memo, useEffect, useRef, useState } from 'react';
import NumericTextViewNativeComponent from './NumericTextViewNativeComponent';
import { measureBox, widest, type Box } from './measureBox';
import { resolveTextStyle } from './resolveTextStyle';
import type { NumericTextDebugProps, NumericTextProps } from './types';

type Props = NumericTextProps & NumericTextDebugProps;

/**
 * One component, one prop shape, two native renderers.
 *
 * Android draws the transition itself (`android/…/NumericTextView.kt`: a spring per digit column).
 * iOS asks SwiftUI for its own `.contentTransition(.numericText())`
 * (`ios/NumericTextSwiftUIHost.swift`) — the behaviour the Android renderer is measured against, so
 * there is nothing to reimplement there. Web and anything else without a native view resolve
 * `./NumericTextView` to the static fallback instead of this file.
 *
 * `animationDuration` reaches Android only; SwiftUI's numeric transition is a spring with no
 * duration to set. Both sides otherwise read the same props, including the text properties pulled
 * out of `style` — each renderer draws its own glyphs, so it needs them as props.
 */
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
