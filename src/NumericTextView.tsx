import { NumericTextFallback } from './NumericTextFallback';

/** Platforms without a native renderer (currently web) get a static, correctly formatted number. */
export const NumericTextView = NumericTextFallback;
