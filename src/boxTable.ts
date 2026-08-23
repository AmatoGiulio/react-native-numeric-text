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
  const negative = value < 0 || (value === 0 && 1 / value < 0);
  const abs = Math.abs(value);

  // How many integer digits the formatter draws — counted AFTER rounding, because rounding is what
  // moves the boundary. The rounding has to match ICU/Intl exactly: `9.995` at two fraction digits
  // formats to `10.00`, not `9.99`, so the row must be the two-integer-digit one or the number
  // clips. Neither `Math.round(m * 100)` nor `m.toFixed(2)` agrees — the double nearest 9.995 is
  // 9.9949…, and both round it down — while Intl rounds the number's *shortest decimal string* with
  // half-away-from-zero. So the rounding is reproduced on that string here, digit by digit.
  let digits: number;
  const raw = abs.toString();
  if (!Number.isFinite(abs) || abs === 0 || raw.indexOf('e') >= 0) {
    // Zero, a magnitude past 1e21, or a fraction below 1e-6 where `toString` turns exponential —
    // all outside the range the table resolves per digit. Count the truncated integer part; a huge
    // magnitude is clamped to the last row below, and anything sub-integer has one integer digit.
    let magnitude = abs * table.scale;
    if (!Number.isFinite(magnitude)) magnitude = 0;
    digits = 1;
    let remaining = Math.trunc(magnitude);
    while (remaining >= 10) {
      remaining = Math.floor(remaining / 10);
      digits += 1;
    }
  } else {
    // Percent multiplies by 100 before formatting. Doing that in float would reintroduce exactly the
    // error the string trick removes (`9.995 * 100` is `999.4999…`), so the scale — always a power
    // of ten — is applied by shifting the decimal point along the digit string instead.
    const shift = table.scale > 1 ? Math.round(Math.log10(table.scale)) : 0;
    const dot = raw.indexOf('.');
    let intStr = dot < 0 ? raw : raw.slice(0, dot);
    let fracStr = dot < 0 ? '' : raw.slice(dot + 1);
    if (shift > 0) {
      if (fracStr.length >= shift) {
        intStr += fracStr.slice(0, shift);
        fracStr = fracStr.slice(shift);
      } else {
        intStr += fracStr + '0'.repeat(shift - fracStr.length);
        fracStr = '';
      }
    }

    let intLen = intStr.length;
    const glyphs = (intStr + fracStr).split('');

    // Index of the first digit rounding discards.
    let cut: number;
    if (table.significantDigits > 0) {
      let firstSig = 0;
      while (firstSig < glyphs.length && glyphs[firstSig] === '0')
        firstSig += 1;
      cut = firstSig + table.significantDigits;
    } else {
      cut = intLen + table.fractionDigits;
    }

    if (cut < glyphs.length && glyphs[cut]! >= '5') {
      let carry = 1;
      for (let i = cut - 1; i >= 0 && carry > 0; i -= 1) {
        const d = glyphs[i]!.charCodeAt(0) - 48 + carry;
        glyphs[i] = String.fromCharCode((d % 10) + 48);
        carry = d >= 10 ? 1 : 0;
      }
      if (carry > 0) {
        glyphs.unshift('1');
        intLen += 1;
      }
    }

    let lead = 0;
    while (lead < intLen - 1 && glyphs[lead] === '0') lead += 1;
    digits = intLen - lead;
  }

  if (digits > MAX_INTEGER_DIGITS) digits = MAX_INTEGER_DIGITS;
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
  const shape = groupingShape(locale);
  const scale = normalized.style === 'percent' ? 100 : 1;
  const positive: number[] = [0];
  const negative: number[] = [0];

  for (let digits = 1; digits <= MAX_INTEGER_DIGITS; digits += 1) {
    positive.push(
      measureBox(
        sample(digits, false, locale, normalized, rounding, scale, shape),
        fontSize
      ).minWidth
    );
    negative.push(
      measureBox(
        sample(digits, true, locale, normalized, rounding, scale, shape),
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
  scale: number,
  shape: GroupingShape
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
  // The separators the extra integer digits introduce. Counted from the locale's own grouping shape
  // rather than assumed to be one per three digits: past 15 digits the double can no longer be
  // formatted directly, and Indian locales group the high-order digits by two (12,34,56,789), so a
  // fixed /3 reserved too few separators and clipped very large numbers.
  const missingGroups =
    normalized.useGrouping === false
      ? 0
      : separatorCount(digits, shape) - separatorCount(integerDigits, shape);

  // The padded glyphs must be the locale's own: `ar-EG` draws Arabic-Indic digits and its own group
  // mark, and appending ASCII `8`/`,` there measured a width neither the real number nor the row it
  // indexes has.
  return (
    text +
    shape.eight.repeat(missingInteger + missingFraction) +
    shape.group.repeat(missingGroups)
  );
}

type GroupingShape = {
  primary: number;
  secondary: number;
  /** The locale's digit `8`, the widest tabular digit and the one the samples are built from. */
  eight: string;
  /** The locale's grouping separator. */
  group: string;
};

/** Grouping separators in a number with [n] integer digits, given the locale's grouping shape. */
function separatorCount(n: number, shape: GroupingShape): number {
  if (n <= shape.primary) return 0;
  return 1 + Math.floor((n - shape.primary - 1) / shape.secondary);
}

/**
 * The locale's grouping shape and the glyphs the padded samples are built from.
 *
 * `primary`/`secondary` are the size of the rightmost group and of the repeating groups above it:
 * most locales repeat one size (1,000,000) but Indian locales group the thousands and then by two
 * (10,00,000). `eight` and `group` are the locale's digit `8` and grouping mark, so a sample padded
 * past 15 digits carries the same glyphs — and the same width — the real number would.
 */
function groupingShape(locale: string): GroupingShape {
  const fallback: GroupingShape = {
    primary: 3,
    secondary: 3,
    eight: '8',
    group: ',',
  };
  try {
    const format = new Intl.NumberFormat(locale, {
      useGrouping: true,
      maximumFractionDigits: 0,
    });
    const runs: number[] = [];
    let group = fallback.group;
    for (const part of format.formatToParts(11111111111)) {
      if (part.type === 'integer') runs.push(part.value.length);
      else if (part.type === 'group') group = part.value;
    }
    const eight = format
      .formatToParts(8)
      .find((part) => part.type === 'integer');
    return {
      primary: runs.length ? runs[runs.length - 1]! : 3,
      secondary: runs.length >= 2 ? runs[runs.length - 2]! : (runs[0] ?? 3),
      eight: eight ? eight.value : fallback.eight,
      group,
    };
  } catch {
    return fallback;
  }
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
