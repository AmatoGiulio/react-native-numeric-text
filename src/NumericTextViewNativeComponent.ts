import {
  codegenNativeComponent,
  type ColorValue,
  type ViewProps,
} from 'react-native';
import type { Double, Float, Int32 } from './codegen-types';

interface NativeProps extends ViewProps {
  readonly value?: Double;
  readonly direction?: string;
  readonly locale?: string;
  readonly animationDuration?: Double;
  readonly useGrouping?: boolean;
  readonly minimumFractionDigits?: Int32;
  readonly maximumFractionDigits?: Int32;
  readonly reduceMotion?: string;
  readonly fontSize?: Float;
  readonly fontWeight?: string;
  readonly fontFamily?: string;
  readonly textColor?: ColorValue;
  readonly debugTransitionStrategy?: string;
  readonly debugManualProgress?: Double;
  /**
   * Android debug switch: 'drum' | 'stack' | 'auto'. Selects which engine draws a column. Global
   * rather than per-view, and 'auto' leaves whatever the recorder's marker file decided. No effect
   * on iOS, which hosts the real SwiftUI view.
   */
  readonly debugEngine?: string;
}

export default codegenNativeComponent<NativeProps>('NumericTextView');
