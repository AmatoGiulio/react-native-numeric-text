import type { NumericTextFormat, NumericTextProps } from './types';

/**
 * One description of "what this number should read as", shared by the web fallback, the JS width
 * estimate, and the props handed to the native renderers.
 */

export const DIGITS_UNSET = -1;
export const DEFAULT_LOCALE = 'en-US';

const MIN_INTEGER_DIGITS = 1;
const MAX_INTEGER_DIGITS = 21;
const MIN_FRACTION_DIGITS = 0;
const MAX_FRACTION_DIGITS = 100;
const MIN_SIGNIFICANT_DIGITS = 1;
const MAX_SIGNIFICANT_DIGITS = 21;

type FormatProps = Pick<
  NumericTextProps,
  | 'format'
  | 'currency'
  | 'useGrouping'
  | 'minimumFractionDigits'
  | 'maximumFractionDigits'
>;

export type NativeFormatProps = {
  numberStyle: string;
  currency: string;
  currencyDisplay: string;
  currencySign: string;
  useGrouping: boolean;
  minimumIntegerDigits: number;
  minimumFractionDigits: number;
  maximumFractionDigits: number;
  minimumSignificantDigits: number;
  maximumSignificantDigits: number;
};

export function resolveFormat(props: FormatProps): NumericTextFormat {
  const {
    format,
    currency,
    useGrouping,
    minimumFractionDigits,
    maximumFractionDigits,
  } = props;

  const shorthand: NumericTextFormat = {};
  if (currency !== undefined) {
    shorthand.style = 'currency';
    shorthand.currency = currency;
  }
  if (useGrouping !== undefined) shorthand.useGrouping = useGrouping;
  if (minimumFractionDigits !== undefined) {
    shorthand.minimumFractionDigits = minimumFractionDigits;
  }
  if (maximumFractionDigits !== undefined) {
    shorthand.maximumFractionDigits = maximumFractionDigits;
  }

  return format ? { ...shorthand, ...format } : shorthand;
}

/**
 * Canonicalizes the public format before any formatter sees it.
 *
 * Native prop setters cannot throw the way `Intl.NumberFormat` can. Letting each platform decide
 * what to do with an invalid digit bound therefore creates a much worse failure mode than a bad
 * string: JS can measure a short fallback while native draws tens or hundreds of digits. We keep
 * this component non-throwing, but clamp the numeric options to ECMA-402's supported ranges and
 * hand the exact same values to JS, Android and iOS.
 *
 * The public currency display contract is deliberately `symbol | code`. Any unsupported runtime
 * value degrades to `symbol` before JS or either native formatter sees it, so an untyped caller
 * cannot accidentally opt into a platform-specific affix model.
 */
export function normalizeFormat(format: NumericTextFormat): NumericTextFormat {
  const currency = normalizeCurrency(format.currency);
  const style =
    format.style === 'currency' && currency
      ? 'currency'
      : format.style === 'percent'
        ? 'percent'
        : 'decimal';

  const currencyDisplay = format.currencyDisplay === 'code' ? 'code' : 'symbol';
  const currencySign =
    currencyDisplay === 'symbol' && format.currencySign === 'accounting'
      ? 'accounting'
      : 'standard';

  const [minimumFractionDigits, maximumFractionDigits] = normalizeBounds(
    format.minimumFractionDigits,
    format.maximumFractionDigits,
    MIN_FRACTION_DIGITS,
    MAX_FRACTION_DIGITS
  );
  const [minimumSignificantDigits, maximumSignificantDigits] = normalizeBounds(
    format.minimumSignificantDigits,
    format.maximumSignificantDigits,
    MIN_SIGNIFICANT_DIGITS,
    MAX_SIGNIFICANT_DIGITS
  );

  return {
    style,
    ...(style === 'currency' ? { currency } : {}),
    ...(style === 'currency' ? { currencyDisplay, currencySign } : {}),
    useGrouping: format.useGrouping ?? true,
    ...defined(
      'minimumIntegerDigits',
      normalizeDigitBound(
        format.minimumIntegerDigits,
        MIN_INTEGER_DIGITS,
        MAX_INTEGER_DIGITS
      )
    ),
    ...defined('minimumFractionDigits', minimumFractionDigits),
    ...defined('maximumFractionDigits', maximumFractionDigits),
    ...defined('minimumSignificantDigits', minimumSignificantDigits),
    ...defined('maximumSignificantDigits', maximumSignificantDigits),
  };
}

function normalizeCurrency(value: string | undefined): string | undefined {
  if (!value || !/^[A-Za-z]{3}$/.test(value)) return undefined;
  return value.toUpperCase();
}

function normalizeBounds(
  minValue: number | undefined,
  maxValue: number | undefined,
  lower: number,
  upper: number
): [number | undefined, number | undefined] {
  const min = normalizeDigitBound(minValue, lower, upper);
  let max = normalizeDigitBound(maxValue, lower, upper);
  if (min !== undefined && max !== undefined && max < min) max = min;
  return [min, max];
}

function normalizeDigitBound(
  value: number | undefined,
  lower: number,
  upper: number
): number | undefined {
  if (value === undefined || !Number.isFinite(value)) return undefined;
  return Math.min(upper, Math.max(lower, Math.floor(value)));
}

function defined<K extends keyof NumericTextFormat>(
  key: K,
  value: NumericTextFormat[K] | undefined
): Pick<NumericTextFormat, K> | Record<never, never> {
  return value === undefined
    ? {}
    : ({ [key]: value } as Pick<NumericTextFormat, K>);
}

function isCurrency(format: NumericTextFormat): boolean {
  return format.style === 'currency' && !!format.currency;
}

export function intlOptions(
  format: NumericTextFormat
): Intl.NumberFormatOptions {
  const normalized = normalizeFormat(format);
  const options: Intl.NumberFormatOptions = {
    useGrouping: normalized.useGrouping,
  };

  if (isCurrency(normalized)) {
    options.style = 'currency';
    options.currency = normalized.currency;
    options.currencyDisplay = normalized.currencyDisplay;
    options.currencySign = normalized.currencySign;
  } else if (normalized.style === 'percent') {
    options.style = 'percent';
  }

  if (normalized.minimumIntegerDigits !== undefined) {
    options.minimumIntegerDigits = normalized.minimumIntegerDigits;
  }

  assignBounds(
    options,
    'minimumSignificantDigits',
    'maximumSignificantDigits',
    normalized.minimumSignificantDigits,
    normalized.maximumSignificantDigits
  );
  assignBounds(
    options,
    'minimumFractionDigits',
    'maximumFractionDigits',
    normalized.minimumFractionDigits,
    normalized.maximumFractionDigits
  );

  return options;
}

function assignBounds(
  options: Intl.NumberFormatOptions,
  minKey: 'minimumFractionDigits' | 'minimumSignificantDigits',
  maxKey: 'maximumFractionDigits' | 'maximumSignificantDigits',
  min: number | undefined,
  max: number | undefined
): void {
  if (min !== undefined) options[minKey] = min;
  if (max !== undefined) options[maxKey] = max;
}

export function nativeFormatProps(
  format: NumericTextFormat
): NativeFormatProps {
  const normalized = normalizeFormat(format);
  const currency = isCurrency(normalized);
  return {
    numberStyle: currency
      ? 'currency'
      : normalized.style === 'percent'
        ? 'percent'
        : 'decimal',
    currency: currency ? normalized.currency! : '',
    currencyDisplay: normalized.currencyDisplay ?? 'symbol',
    currencySign: normalized.currencySign ?? 'standard',
    useGrouping: normalized.useGrouping ?? true,
    minimumIntegerDigits: normalized.minimumIntegerDigits ?? DIGITS_UNSET,
    minimumFractionDigits: normalized.minimumFractionDigits ?? DIGITS_UNSET,
    maximumFractionDigits: normalized.maximumFractionDigits ?? DIGITS_UNSET,
    minimumSignificantDigits:
      normalized.minimumSignificantDigits ?? DIGITS_UNSET,
    maximumSignificantDigits:
      normalized.maximumSignificantDigits ?? DIGITS_UNSET,
  };
}

export function formatNumber(
  value: number,
  locale: string,
  format: NumericTextFormat
): string {
  const normalized = normalizeFormat(format);
  try {
    return value.toLocaleString(locale, intlOptions(normalized));
  } catch {
    try {
      return value.toLocaleString(locale);
    } catch {
      return value.toLocaleString(DEFAULT_LOCALE);
    }
  }
}
