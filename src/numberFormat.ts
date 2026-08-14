import type { NumericTextFormat, NumericTextProps } from './types';

/**
 * One description of "what this number should read as", shared by the three places that have to
 * agree on it: the web fallback, the JS width estimate, and the props handed to the native
 * renderers.
 *
 * The native renderers do the real formatting, because the string that is drawn has to be
 * produced where it is drawn: the transition animates the structure of a formatted number, not a
 * string handed over ready-made. But the layout box is estimated in JS from a string JS formats
 * itself, so the two must resolve the same options from the same props. Everything that decides
 * the shape of the number therefore lives here rather than being spelled out again per platform.
 */

/**
 * The value a digit bound carries to native when the caller did not give one.
 *
 * A bound is a digit count, so it is never negative and -1 cannot be mistaken for a real one. The
 * renderer needs the difference: "0 fraction digits" and "however many this currency uses" are
 * different instructions, and an absent prop means the second.
 */
export const DIGITS_UNSET = -1;

export const DEFAULT_LOCALE = 'en-US';

/** The props that describe the number rather than the motion or the typography. */
type FormatProps = Pick<
  NumericTextProps,
  | 'format'
  | 'currency'
  | 'useGrouping'
  | 'minimumFractionDigits'
  | 'maximumFractionDigits'
>;

/** The flat scalars the native prop surface carries. `Intl` shapes are not codegen shapes. */
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

/**
 * The single format the whole component works from.
 *
 * The top-level shorthands are folded in first and [FormatProps.format] is laid over them, so a
 * component can say `currency="USD"` for the common case and still reach for the full options
 * object without the two contradicting each other.
 */
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
 * Whether [format] asks for money.
 *
 * `style: 'currency'` without a code is not money, it is a mistake. `Intl` throws on it; the
 * renderers cannot, so both they and this treat it as a plain number.
 */
function isCurrency(format: NumericTextFormat): boolean {
  return format.style === 'currency' && !!format.currency;
}

/**
 * [format] as `Intl.NumberFormat` options.
 *
 * A digit bound is passed through rather than defaulted, so `Intl` applies its own rule: 0..3
 * digits for a plain number, 0 for a percentage, and the currency's own count for money. Both
 * native renderers implement that same rule, which is what keeps a JS-measured box the size of a
 * natively drawn number.
 *
 * The one thing not passed through is a maximum below its minimum. `Intl` throws on that pair;
 * the native formatters silently clamp, and a formatter that throws inside a render is worse than
 * a number carrying one more decimal than was asked for.
 */
export function intlOptions(
  format: NumericTextFormat
): Intl.NumberFormatOptions {
  const options: Intl.NumberFormatOptions = {
    useGrouping: format.useGrouping ?? true,
  };

  if (isCurrency(format)) {
    options.style = 'currency';
    options.currency = format.currency;
    options.currencyDisplay = format.currencyDisplay ?? 'symbol';
    options.currencySign = format.currencySign ?? 'standard';
  } else if (format.style === 'percent') {
    options.style = 'percent';
  }

  if (format.minimumIntegerDigits !== undefined) {
    options.minimumIntegerDigits = format.minimumIntegerDigits;
  }

  assignBounds(
    options,
    'minimumSignificantDigits',
    'maximumSignificantDigits',
    format.minimumSignificantDigits,
    format.maximumSignificantDigits
  );
  assignBounds(
    options,
    'minimumFractionDigits',
    'maximumFractionDigits',
    format.minimumFractionDigits,
    format.maximumFractionDigits
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
  if (max !== undefined) {
    options[maxKey] = min === undefined ? max : Math.max(min, max);
  }
}

/**
 * [format] as the flat props the native renderers read. Absent bounds become [DIGITS_UNSET] so
 * each platform can apply the same default rule rather than being handed a guess made in JS.
 */
export function nativeFormatProps(
  format: NumericTextFormat
): NativeFormatProps {
  const currency = isCurrency(format);
  return {
    numberStyle: currency
      ? 'currency'
      : format.style === 'percent'
        ? 'percent'
        : 'decimal',
    currency: currency ? format.currency! : '',
    currencyDisplay: format.currencyDisplay ?? 'symbol',
    currencySign: format.currencySign ?? 'standard',
    useGrouping: format.useGrouping ?? true,
    minimumIntegerDigits: format.minimumIntegerDigits ?? DIGITS_UNSET,
    minimumFractionDigits: format.minimumFractionDigits ?? DIGITS_UNSET,
    maximumFractionDigits: format.maximumFractionDigits ?? DIGITS_UNSET,
    minimumSignificantDigits: format.minimumSignificantDigits ?? DIGITS_UNSET,
    maximumSignificantDigits: format.maximumSignificantDigits ?? DIGITS_UNSET,
  };
}

/**
 * [value] as the number the renderer will draw.
 *
 * Falls back to plain formatting when the options are rejected, which happens for an unknown
 * currency code and on a JS engine built without the full `Intl` data. Neither is worth throwing
 * out of a render: the native side formats the number that is actually shown, and this string
 * only has to be close enough to size the box.
 */
export function formatNumber(
  value: number,
  locale: string,
  format: NumericTextFormat,
  trailingDecimalSeparator = false
): string {
  let text: string;
  try {
    text = value.toLocaleString(locale, intlOptions(format));
  } catch {
    text = value.toLocaleString(locale);
  }
  return trailingDecimalSeparator
    ? withTrailingSeparator(text, decimalSeparatorFor(locale, format))
    : text;
}

/**
 * The decimal mark this locale and format would use.
 *
 * Read from the formatter rather than from a table, because a currency format may use a different
 * mark from a plain number in the same locale. A probe value with one forced fraction digit is the
 * only way to make `formatToParts` emit the mark when the format itself asks for none, which is
 * exactly the case this is needed for.
 */
function decimalSeparatorFor(
  locale: string,
  format: NumericTextFormat
): string {
  try {
    const probe: Intl.NumberFormatOptions = { ...intlOptions(format) };
    delete probe.minimumSignificantDigits;
    delete probe.maximumSignificantDigits;
    probe.minimumFractionDigits = 1;
    probe.maximumFractionDigits = 1;

    const parts = new Intl.NumberFormat(locale, probe).formatToParts(1.1);
    return parts.find((part) => part.type === 'decimal')?.value ?? '.';
  } catch {
    return '.';
  }
}

/**
 * [text] with [mark] after its last digit, unless it already carries one.
 *
 * After the last *digit*, not at the end of the string: `de-DE` writes `1.234 €`, and a mark
 * appended blindly would land beyond the currency symbol. `\p{Nd}` rather than `0-9` because a
 * locale may format in Arabic-Indic or Devanagari digits.
 */
function withTrailingSeparator(text: string, mark: string): string {
  if (text.includes(mark)) return text;

  let end = -1;
  for (const match of text.matchAll(/\p{Nd}/gu)) {
    end = match.index + match[0].length;
  }
  return end < 0 ? text + mark : text.slice(0, end) + mark + text.slice(end);
}
