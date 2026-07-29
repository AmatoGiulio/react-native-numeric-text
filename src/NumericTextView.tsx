import type { ComponentType } from 'react';
import { NumericTextFallback } from './NumericTextFallback';
import type { NumericTextDebugProps, NumericTextProps } from './types';

/**
 * Platforms with no native renderer (web) get the static, correctly formatted number.
 *
 * Typed with the debug props the native entry accepts — and ignoring them — so the component has
 * one prop type everywhere. This file is also what TypeScript resolves `./NumericTextView` to when
 * building types, so its signature is the published one.
 */
export const NumericTextView = NumericTextFallback as ComponentType<
  NumericTextProps & NumericTextDebugProps
>;
