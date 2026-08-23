import { describe, expect, it } from '@jest/globals';
import { boxTable, boxWidth } from '../boxTable';
import { measureBox } from '../measureBox';
import { formatNumber } from '../numberFormat';
import type { NumericTextFormat } from '../types';

const FONT_SIZE = 48;

function widthFor(
  value: number,
  format: NumericTextFormat = {},
  locale = 'en-US'
): number {
  return boxWidth(value, boxTable(locale, format, FONT_SIZE));
}

/** What the plain-number path would reserve for exactly this value. */
function exactWidth(
  value: number,
  format: NumericTextFormat = {},
  locale = 'en-US'
): number {
  return measureBox(formatNumber(value, locale, format), FONT_SIZE).minWidth;
}

describe('boxTable', () => {
  it('returns the same table object for the same format', () => {
    const format = { style: 'currency' as const, currency: 'USD' };
    expect(boxTable('en-US', format, FONT_SIZE)).toBe(
      boxTable('en-US', { ...format }, FONT_SIZE)
    );
  });

  it('separates tables by locale, font size and format', () => {
    const table = boxTable('en-US', {}, FONT_SIZE);
    expect(boxTable('de-DE', {}, FONT_SIZE)).not.toBe(table);
    expect(boxTable('en-US', {}, 24)).not.toBe(table);
    expect(boxTable('en-US', { style: 'percent' }, FONT_SIZE)).not.toBe(table);
  });

  it('reserves the same height as the plain path', () => {
    expect(boxTable('en-US', {}, FONT_SIZE).minHeight).toBe(
      measureBox('8', FONT_SIZE).minHeight
    );
  });
});

describe('boxWidth', () => {
  it('grows with the digit count', () => {
    expect(widthFor(1)).toBeLessThan(widthFor(12));
    expect(widthFor(12)).toBeLessThan(widthFor(123));
    expect(widthFor(999)).toBeLessThan(widthFor(1000));
  });

  it('reserves room for the sign', () => {
    expect(widthFor(-123)).toBeGreaterThan(widthFor(123));
    expect(widthFor(-0)).toBe(widthFor(-1));
  });

  it('counts the digits the formatter will draw, not the ones passed in', () => {
    const zeroFraction = { maximumFractionDigits: 0 };
    // 999.6 formats as 1,000: the row has to be the four-digit one, or it clips.
    expect(widthFor(999.6, zeroFraction)).toBe(widthFor(1000, zeroFraction));
    expect(widthFor(999.4, zeroFraction)).toBe(widthFor(999, zeroFraction));
  });

  it('follows a currency to its own fraction count', () => {
    // JPY rounds to whole yen, so 999.6 is ¥1,000 while USD 999.6 is still $999.60.
    const jpy = { style: 'currency' as const, currency: 'JPY' };
    const usd = { style: 'currency' as const, currency: 'USD' };
    expect(widthFor(999.6, jpy)).toBe(widthFor(1000, jpy));
    expect(widthFor(999.6, usd)).toBe(widthFor(999, usd));
  });

  it('scales a percent before counting', () => {
    const percent = { style: 'percent' as const };
    expect(widthFor(0.42, percent)).toBe(widthFor(0.99, percent));
    expect(widthFor(0.42, percent)).toBeLessThan(widthFor(1.5, percent));
  });

  it('honours a minimum integer width', () => {
    const padded = { minimumIntegerDigits: 4 };
    expect(widthFor(9, padded)).toBe(widthFor(9999, padded));
  });

  it('clamps a magnitude past the table rather than falling off it', () => {
    expect(widthFor(1e30)).toBe(widthFor(1e21));
    expect(widthFor(Number.POSITIVE_INFINITY)).toBe(widthFor(0));
    expect(widthFor(Number.NaN)).toBe(widthFor(0));
  });

  // The one guarantee the table has to make: whatever the value formats to fits in the box the UI
  // thread picked for it. The reserved width may exceed the exact one — a shared value moves, and
  // every value in a bucket gets the bucket's widest — but it may never fall short.
  it('never reserves less than the value actually needs', () => {
    const formats: NumericTextFormat[] = [
      {},
      { useGrouping: false },
      { style: 'percent' },
      { minimumFractionDigits: 2, maximumFractionDigits: 2 },
      { maximumSignificantDigits: 3 },
      { minimumIntegerDigits: 3 },
      { style: 'currency', currency: 'USD' },
      { style: 'currency', currency: 'JPY' },
      { style: 'currency', currency: 'BHD' },
      { style: 'currency', currency: 'USD', currencyDisplay: 'code' },
      { style: 'currency', currency: 'USD', currencySign: 'accounting' },
    ];
    // Kept within the 21-integer-digit table: percent multiplies by 100, so the largest bases here
    // stay at or under 19 digits and land inside the table after scaling.
    const values = [
      0, 0.5, 1, 9, 9.99, 9.995, 42, 99.95, 99.995, 100, 999.6, 999.995, 1000,
      9999999.995, 12345.678, 999999.99, 1234567890.12, 1e16, 1e17,
      120000000000000000, -1, -9.99, -9.995, -1234.5, -1000000, -1e17,
    ];

    for (const locale of ['en-US', 'de-DE', 'fr-FR', 'hi-IN', 'ar-EG']) {
      for (const format of formats) {
        for (const value of values) {
          const short =
            widthFor(value, format, locale) < exactWidth(value, format, locale);
          expect({ locale, format, value, short }).toEqual({
            locale,
            format,
            value,
            short: false,
          });
        }
      }
    }
  });

  it('reserves exactly the value when the format fixes its fraction count', () => {
    const usd = { style: 'currency' as const, currency: 'USD' };
    expect(widthFor(1234.5, usd)).toBe(exactWidth(1234.5, usd));
    expect(widthFor(-1234.5, usd)).toBe(exactWidth(-1234.5, usd));
  });

  // The float trap: 9.995 * 100 is 999.4999… so a naive round drops to 9.99, but the formatter
  // rounds the decimal string and draws 10.00 — two integer digits, a wider box.
  it('counts the digit a half-boundary rounds up into, past float error', () => {
    const usd = { style: 'currency' as const, currency: 'USD' };
    expect(widthFor(9.995, usd)).toBe(exactWidth(10, usd));
    expect(widthFor(9.995, usd)).toBeGreaterThan(widthFor(9.99, usd));
    // and the plain 2-fraction case
    const two = { minimumFractionDigits: 2, maximumFractionDigits: 2 };
    expect(widthFor(9.995, two)).toBe(widthFor(10, two));
    expect(widthFor(9999999.995, two)).toBe(widthFor(10000000, two));
  });

  it('rounds significant digits the way the formatter does', () => {
    const sig3 = { maximumSignificantDigits: 3, minimumSignificantDigits: 3 };
    // 9.995 to 3 significant figures is 10.0 — two integer digits.
    expect(widthFor(9.995, sig3)).toBe(exactWidth(9.995, sig3));
    expect(widthFor(99.95, sig3)).toBe(exactWidth(99.95, sig3));
    expect(widthFor(999.5, sig3)).toBe(exactWidth(999.5, sig3));
  });

  // Past 15 digits the sample is padded, and Indian locales group the high-order digits by two.
  it('reserves Indian grouping for very large numbers', () => {
    const big = [1e16, 1e17, 1e18, 1e20, 12000000000000000, 999900000000000000];
    for (const value of big) {
      expect(widthFor(value, {}, 'hi-IN')).toBeGreaterThanOrEqual(
        exactWidth(value, {}, 'hi-IN')
      );
    }
    // and Indian grouping really is wider than Latin at this size (more separators)
    expect(widthFor(1e18, {}, 'hi-IN')).toBeGreaterThan(
      widthFor(1e18, {}, 'en-US')
    );
  });
});
