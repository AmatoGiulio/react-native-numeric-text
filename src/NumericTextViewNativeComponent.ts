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
  readonly reduceMotion?: string;

  // Formatting. Flat scalars rather than one object: this is the shape codegen carries well, and
  // `src/numberFormat.ts` is where the `Intl`-shaped prop is resolved down to them.
  readonly numberStyle?: string;
  readonly currency?: string;
  readonly currencyDisplay?: string;
  readonly currencySign?: string;
  readonly useGrouping?: boolean;
  /** A digit count, or -1 for "the format's own default"; see `DIGITS_UNSET`. */
  readonly minimumIntegerDigits?: Int32;
  readonly minimumFractionDigits?: Int32;
  readonly maximumFractionDigits?: Int32;
  readonly minimumSignificantDigits?: Int32;
  readonly maximumSignificantDigits?: Int32;

  readonly fontSize?: Float;
  readonly fontWeight?: string;
  readonly fontFamily?: string;
  readonly textColor?: ColorValue;
}

export default codegenNativeComponent<NativeProps>('NumericTextView');
