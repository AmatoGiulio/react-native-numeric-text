import { memo, useEffect, useRef, useState } from 'react';
import NumericTextViewNativeComponent from './NumericTextViewNativeComponent';
import { accessibilityPropsOf } from './accessibilityProps';
import { measureBox, widest, type Box } from './measureBox';
import {
  DEFAULT_LOCALE,
  formatNumber,
  nativeFormatProps,
  resolveFormat,
} from './numberFormat';
import { resolveTextStyle } from './resolveTextStyle';
import type { NumericTextProps } from './types';

/**
 * One component, one prop shape, two native renderers.
 *
 * Android draws the transition itself; iOS delegates the numeric transition to SwiftUI. Formatting
 * is passed as props rather than as a finished string because each renderer needs the numeric
 * structure where it draws it. JS reproduces the format only to reserve a safe layout box.
 */
function NumericTextViewImpl(props: NumericTextProps) {
  const {
    value,
    locale = DEFAULT_LOCALE,
    direction = 'automatic',
    animationDuration = 80,
    reduceMotion = 'system',
    style,
    testID,
  } = props;

  const text = resolveTextStyle(style);
  const format = resolveFormat(props);
  const formatted = formatNumber(value, locale, format);
  const box = useShrinkHeldBox(
    measureBox(formatted, text.fontSize),
    Math.max(animationDuration, 500) + 400
  );

  return (
    <NumericTextViewNativeComponent
      {...accessibilityPropsOf(props)}
      value={value}
      direction={direction}
      locale={locale}
      animationDuration={animationDuration}
      reduceMotion={reduceMotion}
      {...nativeFormatProps(format)}
      fontSize={text.fontSize}
      fontWeight={text.fontWeight}
      fontFamily={text.fontFamily}
      textColor={text.textColor}
      fractionColor={props.fractionColor}
      testID={testID}
      style={[style, box]}
    />
  );
}

function sameBox(a: Box, b: Box): boolean {
  return a.minWidth === b.minWidth && a.minHeight === b.minHeight;
}

function useShrinkHeldBox(target: Box, holdMs: number): Box {
  const [held, setHeld] = useState(target);
  const targetRef = useRef(target);
  targetRef.current = target;

  const targetMinWidth = target.minWidth;
  const targetMinHeight = target.minHeight;
  const grew =
    targetMinWidth >= held.minWidth && targetMinHeight >= held.minHeight;
  const settled =
    targetMinWidth === held.minWidth && targetMinHeight === held.minHeight;

  useEffect(() => {
    if (settled) return;
    if (grew) {
      setHeld({ minWidth: targetMinWidth, minHeight: targetMinHeight });
      return;
    }

    // Capture the box this timer belongs to. React Native can delay/coalesce JS timers; if a newer
    // formatted value arrives before this callback gets CPU time, targetRef.current already points
    // at that newer (possibly much narrower) box. Shrinking to targetRef.current here clipped the
    // outgoing raster during format changes such as `1,000% -> ¥999`. A stale release may only
    // commit when its own target is still current.
    const shrinkTo = { minWidth: targetMinWidth, minHeight: targetMinHeight };
    const timer = setTimeout(() => {
      if (sameBox(targetRef.current, shrinkTo)) {
        setHeld(shrinkTo);
      }
    }, holdMs);
    return () => clearTimeout(timer);
  }, [settled, grew, targetMinWidth, targetMinHeight, holdMs]);

  return settled ? target : widest(target, held);
}

export const NumericTextView = memo(NumericTextViewImpl);
NumericTextView.displayName = 'NumericTextView';
