import { memo, useEffect, useRef, useState } from 'react';
import NumericTextViewNativeComponent from './NumericTextViewNativeComponent';
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
      testID={testID}
      style={[style, box]}
    />
  );
}

function useShrinkHeldBox(target: Box, holdMs: number): Box {
  const [held, setHeld] = useState(target);
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

export const NumericTextView = memo(NumericTextViewImpl);
NumericTextView.displayName = 'NumericTextView';
