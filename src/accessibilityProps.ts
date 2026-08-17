import type { NumericTextAccessibilityProps, NumericTextProps } from './types';

/** Accessibility is a view concern, not part of numeric formatting. Keep the two prop surfaces apart. */
export function accessibilityPropsOf(
  props: NumericTextProps
): NumericTextAccessibilityProps {
  return {
    accessible: props.accessible,
    accessibilityLabel: props.accessibilityLabel,
    accessibilityHint: props.accessibilityHint,
    accessibilityRole: props.accessibilityRole,
    accessibilityLiveRegion: props.accessibilityLiveRegion,
    importantForAccessibility: props.importantForAccessibility,
    screenReaderFocusable: props.screenReaderFocusable,
    accessibilityLabelledBy: props.accessibilityLabelledBy,
    accessibilityElementsHidden: props.accessibilityElementsHidden,
    accessibilityViewIsModal: props.accessibilityViewIsModal,
    accessibilityLanguage: props.accessibilityLanguage,
  };
}
