import { describe, expect, it } from '@jest/globals';
import {
  DIGITS_UNSET,
  formatNumber,
  intlOptions,
  nativeFormatProps,
  normalizeFormat,
  resolveFormat,
} from '../numberFormat';

describe('resolveFormat', () => {
  it('reads the currency shorthand as a currency style', () => {
    expect(resolveFormat({ currency: 'USD' })).toEqual({
      style: 'currency',
      currency: 'USD',
    });
  });

  it('lets format override the shorthands where they overlap', () => {
    expect(
      resolveFormat({
        currency: 'USD',
        useGrouping: true,
        format: { currency: 'EUR', useGrouping: false },
      })
    ).toEqual({
      style: 'currency',
      currency: 'EUR',
      useGrouping: false,
    });
  });

  it('leaves an absent prop absent rather than defaulting it', () => {
    expect(resolveFormat({})).toEqual({});
  });
});

describe('normalizeFormat', () => {
  it('normalizes a currency code before both JS and native see it', () => {
    expect(normalizeFormat({ style: 'currency', currency: 'usd' })).toMatchObject({
      style: 'currency',
      currency: 'USD',
    });
  });

  it('drops a malformed currency code instead of letting platforms disagree', () => {
    expect(normalizeFormat({ style: 'currency', currency: 'NOPE' })).toMatchObject({
      style: 'decimal',
    });
  });

  it('keeps accounting symbol-only', () => {
    const normalized = normalizeFormat({
      style: 'currency',
      currency: 'USD',
      currencyDisplay: 'code',
      currencySign: 'accounting',
    });
    expect(normalized.currencyDisplay).toBe('code');
    expect(normalized.currencySign).toBe('standard');
  });

  it('clamps digit bounds to the Intl contract', () => {
    expect(
      normalizeFormat({
        minimumIntegerDigits: 0,
        minimumFractionDigits: -1,
        maximumFractionDigits: 500,
        minimumSignificantDigits: 0,
        maximumSignificantDigits: 99,
      })
    ).toMatchObject({
      minimumIntegerDigits: 1,
      minimumFractionDigits: 0,
      maximumFractionDigits: 100,
      minimumSignificantDigits: 1,
      maximumSignificantDigits: 21,
    });
  });

  it('never leaves a maximum below its minimum', () => {
    expect(
      normalizeFormat({
        minimumFractionDigits: 4,
        maximumFractionDigits: 1,
      })
    ).toMatchObject({
      minimumFractionDigits: 4,
      maximumFractionDigits: 4,
    });
  });

  it('degrades the not-yet-supported currency name display to symbol', () => {
    expect(
      normalizeFormat({
        style: 'currency',
        currency: 'USD',
        currencyDisplay: 'name',
      })
    ).toMatchObject({ currencyDisplay: 'symbol' });
  });
});

describe('formatNumber', () => {
  it('formats a plain number to at most three decimals', () => {
    expect(formatNumber(1234.5678, 'en-US', {})).toBe('1,234.568');
  });

  it('takes the currencys own fraction digits when none were given', () => {
    expect(
      formatNumber(1234.5, 'en-US', resolveFormat({ currency: 'USD' }))
    ).toBe('$1,234.50');
    expect(
      formatNumber(1234.5, 'en-US', resolveFormat({ currency: 'JPY' }))
    ).toBe('¥1,235');
  });

  it('puts the symbol where the locale puts it', () => {
    expect(
      formatNumber(1234.5, 'de-DE', resolveFormat({ currency: 'EUR' }))
    ).toBe('1.234,50\u00a0€');
  });

  it('writes the currency as an ISO code on request', () => {
    expect(
      formatNumber(1234.5, 'en-US', {
        style: 'currency',
        currency: 'USD',
        currencyDisplay: 'code',
      })
    ).toBe('USD\u00a01,234.50');
  });

  it('brackets a negative amount in the accounting sign', () => {
    expect(
      formatNumber(-1234.5, 'en-US', {
        style: 'currency',
        currency: 'USD',
        currencySign: 'accounting',
      })
    ).toBe('($1,234.50)');
  });

  it('does not apply accounting to code display', () => {
    expect(
      formatNumber(-1234.5, 'en-US', {
        style: 'currency',
        currency: 'USD',
        currencyDisplay: 'code',
        currencySign: 'accounting',
      })
    ).toBe('-USD\u00a01,234.50');
  });

  it('multiplies a percentage by a hundred and drops its decimals', () => {
    expect(formatNumber(0.425, 'en-US', { style: 'percent' })).toBe('43%');
  });

  it('pads to a minimum integer width', () => {
    expect(formatNumber(9, 'en-US', { minimumIntegerDigits: 2 })).toBe('09');
  });

  it('rounds a half away from zero', () => {
    expect(formatNumber(2.5, 'en-US', { maximumFractionDigits: 0 })).toBe('3');
    expect(formatNumber(-2.5, 'en-US', { maximumFractionDigits: 0 })).toBe(
      '-3'
    );
  });

  it('falls back to a plain number on a malformed currency', () => {
    expect(
      formatNumber(12, 'en-US', { style: 'currency', currency: 'NOPE' })
    ).toBe('12');
  });

  it('treats a currency style with no code as a plain number', () => {
    expect(formatNumber(1234.5, 'en-US', { style: 'currency' })).toBe(
      '1,234.5'
    );
  });

  it('does not fall back to a short JS string for an oversized fraction bound', () => {
    const text = formatNumber(1, 'en-US', {
      minimumFractionDigits: 101,
      maximumFractionDigits: 101,
    });
    expect(text.split('.')[1]).toHaveLength(100);
  });
});

describe('formatNumber, trailing decimal separator', () => {
  it('holds the mark after the last digit when nothing follows it yet', () => {
    expect(formatNumber(7, 'en-US', {}, true)).toBe('7.');
  });

  it('is a no-op once a fraction digit arrives', () => {
    expect(formatNumber(7.5, 'en-US', {}, true)).toBe('7.5');
  });

  it('is a no-op when the format already prints a mark', () => {
    const fixed = { minimumFractionDigits: 2, maximumFractionDigits: 2 };
    expect(formatNumber(7, 'en-US', fixed, true)).toBe('7.00');
  });

  it('uses the locale mark, not a full stop', () => {
    expect(formatNumber(1234, 'de-DE', {}, true)).toBe('1.234,');
  });

  it('goes after the last digit rather than at the end of the string', () => {
    const euro = resolveFormat({ currency: 'EUR' });
    const zeroDecimals = {
      ...euro,
      minimumFractionDigits: 0,
      maximumFractionDigits: 0,
    };
    expect(formatNumber(1234, 'de-DE', zeroDecimals, true)).toBe('1.234, €');
  });

  it('keeps a leading currency symbol outside the mark', () => {
    const usd = {
      ...resolveFormat({ currency: 'USD' }),
      maximumFractionDigits: 0,
    };
    expect(formatNumber(7, 'en-US', usd, true)).toBe('$7.');
  });

  it('does nothing when the flag is off, which is the default', () => {
    expect(formatNumber(7, 'en-US', {})).toBe('7');
  });
});

describe('intlOptions', () => {
  it('never asks for a maximum below its minimum', () => {
    const options = intlOptions({
      minimumFractionDigits: 4,
      maximumFractionDigits: 1,
    });
    expect(options.maximumFractionDigits).toBe(4);
    expect(() => (1).toLocaleString('en-US', options)).not.toThrow();
  });

  it('passes a bound through untouched when it is already valid', () => {
    expect(intlOptions({ maximumFractionDigits: 1 })).not.toHaveProperty(
      'minimumFractionDigits'
    );
    expect(intlOptions({ maximumFractionDigits: 1 }).maximumFractionDigits).toBe(
      1
    );
  });
});

describe('nativeFormatProps', () => {
  it('marks an absent bound so the renderer can apply the same default', () => {
    expect(nativeFormatProps({})).toEqual({
      numberStyle: 'decimal',
      currency: '',
      currencyDisplay: 'symbol',
      currencySign: 'standard',
      useGrouping: true,
      minimumIntegerDigits: DIGITS_UNSET,
      minimumFractionDigits: DIGITS_UNSET,
      maximumFractionDigits: DIGITS_UNSET,
      minimumSignificantDigits: DIGITS_UNSET,
      maximumSignificantDigits: DIGITS_UNSET,
    });
  });

  it('carries a currency down as flat scalars', () => {
    const props = nativeFormatProps(resolveFormat({ currency: 'EUR' }));
    expect(props.numberStyle).toBe('currency');
    expect(props.currency).toBe('EUR');
  });

  it('drops a currency style that has no code, as the formatters do', () => {
    const props = nativeFormatProps({ style: 'currency' });
    expect(props.numberStyle).toBe('decimal');
    expect(props.currency).toBe('');
  });

  it('hands native the same clamped bound JS uses', () => {
    const props = nativeFormatProps({
      minimumFractionDigits: 101,
      maximumFractionDigits: 500,
    });
    expect(props.minimumFractionDigits).toBe(100);
    expect(props.maximumFractionDigits).toBe(100);
  });
});
