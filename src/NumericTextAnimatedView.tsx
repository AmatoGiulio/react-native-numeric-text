import { type ComponentProps, type ComponentType } from 'react';
import NumericTextViewNativeComponent from './NumericTextViewNativeComponent';
import { accessibilityPropsOf } from './accessibilityProps';
import { boxTable, boxWidth } from './boxTable';
import {
  DEFAULT_LOCALE,
  nativeFormatProps,
  resolveFormat,
} from './numberFormat';
import {
  requireReanimated,
  type AnimatedProps,
  type ReanimatedApi,
} from './optionalReanimated';
import { resolveTextStyle } from './resolveTextStyle';
import type { NumericTextProps, NumericTextSharedValue } from './types';

/**
 * The same view, driven by a shared value instead of a prop.
 *
 * The number is written straight into the native view from the UI thread, so a value fed by a
 * gesture or a spring never touches the JS thread and never re-renders anything. React only sees
 * this component when something else about it changes — the format, the style, the locale.
 *
 * Everything the plain renderer resolves in JS is resolved the same way here; only the two things
 * that depend on the value itself — the number and its layout box — move to the UI thread.
 */

type AnimatedNumericTextProps = NumericTextProps & {
  value: NumericTextSharedValue;
};

/** `width` before the reaction has filled it. A real box width from `boxWidth` is never negative. */
const UNSEEDED_WIDTH = -1;

type NativeProps = ComponentProps<typeof NumericTextViewNativeComponent>;

let animatedComponent: ComponentType<AnimatedProps<NativeProps>> | null = null;

/**
 * Wrapped once, at first use.
 *
 * `createAnimatedComponent` returns a new component type on every call, and a new type is a new
 * element type: React would unmount the native view and mount another one on every render, which
 * is the one thing a transition renderer cannot survive.
 */
function animatedNativeComponent(reanimated: ReanimatedApi) {
  if (!animatedComponent) {
    animatedComponent = reanimated.createAnimatedComponent(
      NumericTextViewNativeComponent as ComponentType<NativeProps>
    );
  }
  return animatedComponent;
}

export function NumericTextAnimatedView(props: AnimatedNumericTextProps) {
  const reanimated = requireReanimated();
  const {
    useAnimatedProps,
    useAnimatedReaction,
    useAnimatedStyle,
    useSharedValue,
    withDelay,
    withTiming,
  } = reanimated;

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
  const table = boxTable(locale, format, text.fontSize);
  const holdMs = Math.max(animationDuration, 500) + 400;
  const minHeight = table.minHeight;

  // The box is driven entirely from the UI thread — the shared value is never read during render,
  // which Reanimated documents as a side-effect to avoid. `width` starts unseeded; until the
  // reaction fills it, the style below computes the exact box from `value` on the UI thread, so the
  // first frame is exact without a JS-thread read and without a zero-width flash.
  const width = useSharedValue(UNSEEDED_WIDTH);

  const animatedProps = useAnimatedProps<Partial<NativeProps>>(
    () => ({ value: value.value }),
    [value]
  );

  // The same rule the JS path applies in `useShrinkHeldBox`: grow at once, shrink only after the
  // transition that is still drawing the wider number has had time to finish. A zero-length timing
  // behind a delay is a step, not an animation; the box must not slide. The first reaction seeds the
  // held width from the width just before this change (`previous`), so even a first-change shrink
  // holds against the right floor instead of the unseeded sentinel.
  useAnimatedReaction(
    () => boxWidth(value.value, table),
    (next, previous) => {
      if (width.value < 0) width.value = previous ?? next;
      if (next >= width.value) {
        width.value = next;
      } else {
        width.value = withDelay(holdMs, withTiming(next, { duration: 0 }));
      }
    },
    [value, table, holdMs]
  );

  const box = useAnimatedStyle(() => {
    const seeded = width.value;
    return {
      minWidth: seeded >= 0 ? seeded : boxWidth(value.value, table),
      minHeight,
    };
  }, [minHeight, value, table]);

  const Animated = animatedNativeComponent(reanimated);

  return (
    <Animated
      {...accessibilityPropsOf(props)}
      animatedProps={animatedProps}
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
