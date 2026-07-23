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
  readonly textColor?: ColorValue;
  readonly debugTransitionStrategy?: string;
  readonly debugManualProgress?: Double;
}

export default codegenNativeComponent<NativeProps>('NumericTextView');
