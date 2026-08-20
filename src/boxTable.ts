import { measureBox } from './measureBox';
import { formatNumber, intlOptions, normalizeFormat } from './numberFormat';
import type { NumericTextFormat } from './types';

/**
 * The layout box for a number nobody in JavaScript has seen.
 *
 * A shared value never reaches the JS thread, so `measureBox` cannot be handed the formatted
 * string the way it is for a plain `value`. Under Fabric that box is the only thing giving this
 * view a size (see `measureBox`), so it has to come from somewhere.
 *
 * What it comes from is this table. Every number that formats to the same count of integer digits
 * occupies the same width — the bundled font's digits are tabular, and the separators, currency
 * affix and fraction digits that surround them are fixed by the format rather than by the value.
 * So JS formats one widest sample per integer-digit count, measures it once, and hands the
 * resulting row of widths to the UI thread. Picking a width there is then an array index, and the
 * box is exact on the very first frame instead of trailing the value by a round trip.
 */

/** Matches `numberFormat`'s own clamp: no format can ask for more integer digits than this. */
const MAX_INTEGER_DIGITS = 21;

/** Digits a double carries exactly. Past this the sample is padded rather than computed. */
const SAFE_DIGITS = 15;

/** Digits between grouping separators, for the padding path only. */
const GROUP_SIZE = 3;

/** Fraction digits assumed when the runtime cannot resolve the format's own default. */
const DEFAULT_DECIMAL_FRACTION_DIGITS = 3;
const DEFAULT_CURRENCY_FRACTION_DIGITS = 2;

export type BoxTable = {
  minHeight: number;
  /** Width in px by integer-digit count. Index 0 is unused; index 1 is a single digit. */
  positive: number[];
  negative: number[];
  /** The value is multiplied by this before its digits are counted: 100 for percent. */
  scale: number;
  /** Fraction digits the formatter rounds to. Ignored when [significantDigits] is set. */
  fractionDigits: number;
  /** Significant digits the formatter rounds to, or 0 when it rounds to fraction digits. */
  significantDigits: number;
  minimumIntegerDigits: number;
};

type Rounding = { fractionDigits: number; significantDigits: number };

/**
 * The width to reserve for [value].
 *
 * Runs on the UI thread on every change of the shared value, and on the JS thread once per mount
 * to seed the first frame. Rounding is reproduced rather than looked up because it decides the
 * digit count: at 999.6 with no fraction digits the formatter draws `1,000`, and a row too narrow
 * would clip it for as long as the value stayed there.
 */
export function boxWidth(value: number, table: BoxTable): number {
  'worklet';
  const scaled = value * table.scale;
  const negative = scaled < 0 || (scaled === 0 && 1 / scaled < 0);
  let magnitude = Math.abs(scaled);
  if (!Number.isFinite(magnitude)) magnitude = 0;

  if (table.significantDigits > 0 && magnitude > 0) {
    const unit = Math.pow(
      10,
      table.significantDigits - 1 - Math.floor(Math.log10(magnitude))
    );
    magnitude = Math.round(magnitude * unit) / unit;
  } else if (table.fractionDigits > 0) {
    const unit = Math.pow(10, table.fractionDigits);
    magnitude = Math.round(magnitude * unit) / unit;
  } else {
    magnitude = Math.round(magnitude);
  }

  // Counted by division rather than `log10` so no rounding of the logarithm can drop a digit, and
  // rather than by string length so a magnitude past 1e21 does not come back as `1e+21`.
  let digits = 1;
  let remaining = Math.trunc(magnitude);
  while (remaining >= 10 && digits < MAX_INTEGER_DIGITS) {
    remaining = Math.floor(remaining / 10);
    digits += 1;
  }
  if (digits < table.minimumIntegerDigits) digits = table.minimumIntegerDigits;

  const row = negative ? table.negative : table.positive;
  const index = digits >= row.length ? row.length - 1 : digits;
  return row[index] ?? 0;
}

const cache = new Map<string, BoxTable>();
const CACHE_LIMIT = 8;

/**
 * The table for one format, built once per distinct format and shared by every instance using it.
 *
 * The identity of the returned object matters as much as its contents: it is captured by the
 * worklets below, and a fresh object on every render would re-serialize them to the UI runtime.
 */
export function boxTable(
  locale: string,
  format: NumericTextFormat,
  fontSize: number | undefined
): BoxTable {
  const normalized = normalizeFormat(format);
  const key = `${locale}|${fontSize ?? ''}|${JSON.stringify(normalized)}`;
  const cached = cache.get(key);
  if (cached) return cached;

  const table = build(locale, normalized, fontSize);
  if (cache.size >= CACHE_LIMIT) {
    const oldest = cache.keys().next();
    if (!oldest.done) cache.delete(oldest.value);
  }
  cache.set(key, table);
  return table;
}

function build(
  locale: string,
  normalized: NumericTextFormat,
  fontSize: number | undefined
): BoxTable {
  const rounding = resolveRounding(locale, normalized);
  const scale = normalized.style === 'percent' ? 100 : 1;
  const positive: number[] = [0];
  const negative: number[] = [0];

  for (let digits = 1; digits <= MAX_INTEGER_DIGITS; digits += 1) {
    positive.push(
      measureBox(
        sample(digits, false, locale, normalized, rounding, scale),
        fontSize
      ).minWidth
    );
    negative.push(
      measureBox(
        sample(digits, true, locale, normalized, rounding, scale),
        fontSize
      ).minWidth
    );
  }
  positive[0] = positive[1]!;
  negative[0] = negative[1]!;

  return {
    minHeight: measureBox('8', fontSize).minHeight,
    positive,
    negative,
    scale,
    fractionDigits: rounding.fractionDigits,
    significantDigits: rounding.significantDigits,
    minimumIntegerDigits: normalized.minimumIntegerDigits ?? 1,
  };
}

/**
 * The widest text a number with [digits] integer digits can format to.
 *
 * Formatted rather than assembled, so grouping position, currency affix, sign form and the
 * interaction between significant and fraction digits all come from the same formatter that will
 * draw the real value. Only the digits a double cannot hold are appended afterwards: past 15
 * significant digits the sample would lose them, and what is being measured is a width, not a
 * number, so appending glyphs of the right kind is enough.
 */
function sample(
  digits: number,
  negative: boolean,
  locale: string,
  normalized: NumericTextFormat,
  rounding: Rounding,
  scale: number
): string {
  const integerDigits = Math.min(digits, SAFE_DIGITS);
  const fractionRoom = SAFE_DIGITS - integerDigits;
  const wantedFraction =
    rounding.significantDigits > 0 ? fractionRoom : rounding.fractionDigits;
  const fractionDigits = Math.min(wantedFraction, fractionRoom);

  // `8` throughout: the widest sample must not round up into an extra integer digit the way a run
  // of nines would, and every digit has the same advance anyway.
  const literal =
    '8'.repeat(integerDigits) +
    (fractionDigits > 0 ? `.${'8'.repeat(fractionDigits)}` : '');
  const magnitude = Number(literal) / scale;
  const text = formatNumber(
    negative ? -magnitude : magnitude,
    locale,
    normalized
  );

  const missingInteger = digits - integerDigits;
  const missingFraction = wantedFraction - fractionDigits;
  const missingGroups =
    normalized.useGrouping === false
      ? 0
      : Math.floor(missingInteger / GROUP_SIZE);

  return (
    text +
    '8'.repeat(missingInteger + missingFraction) +
    ','.repeat(missingGroups)
  );
}

/**
 * What the formatter rounds to, asked of the formatter itself.
 *
 * `resolvedOptions` is the only thing that knows a currency's own fraction count — JPY rounds to 0
 * and BHD to 3 — and getting that wrong moves the digit-count boundary and clips the number. The
 * fallback covers a runtime whose `Intl` cannot answer.
 */
function resolveRounding(
  locale: string,
  normalized: NumericTextFormat
): Rounding {
  try {
    const resolved = new Intl.NumberFormat(
      locale,
      intlOptions(normalized)
    ).resolvedOptions();
    const significant = resolved.maximumSignificantDigits;
    if (typeof significant === 'number' && Number.isFinite(significant)) {
      return { fractionDigits: 0, significantDigits: significant };
    }
    const fraction = resolved.maximumFractionDigits;
    if (typeof fraction === 'number' && Number.isFinite(fraction)) {
      return { fractionDigits: fraction, significantDigits: 0 };
    }
  } catch {
    // Falls through to the assumed defaults below.
  }

  if (normalized.maximumSignificantDigits !== undefined) {
    return {
      fractionDigits: 0,
      significantDigits: normalized.maximumSignificantDigits,
    };
  }
  if (normalized.maximumFractionDigits !== undefined) {
    return {
      fractionDigits: normalized.maximumFractionDigits,
      significantDigits: 0,
    };
  }
  return {
    fractionDigits:
      normalized.style === 'percent'
        ? 0
        : normalized.style === 'currency'
          ? DEFAULT_CURRENCY_FRACTION_DIGITS
          : DEFAULT_DECIMAL_FRACTION_DIGITS,
    significantDigits: 0,
  };
}
