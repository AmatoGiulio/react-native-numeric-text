import type { AccessibilityProps } from 'react-native';
import type { NumericTextProps } from './types';

export type NumericTextAccessibilityProps = Pick<
  AccessibilityProps,
  | 'accessible'
  | 'accessibilityLabel'
  | 'accessibilityHint'
  | 'accessibilityRole'
  | 'accessibilityLiveRegion'
  | 'importantForAccessibility'
  | 'screenReaderFocusable'
  | 'accessibilityLabelledBy'
  | 'accessibilityElementsHidden'
  | 'accessibilityViewIsModal'
  | 'accessibilityLanguage'
>;

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
