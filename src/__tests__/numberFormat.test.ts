import { describe, expect, it } from '@jest/globals';
import {
  DIGITS_UNSET,
  formatNumber,
  intlOptions,
  nativeFormatProps,
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
    // The renderers need to tell "0 fraction digits" from "the currency's own count", so a prop
    // nobody set must not arrive as a number.
    expect(resolveFormat({})).toEqual({});
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
    // Written escaped because the space before the symbol is a no-break one, which is exactly the
    // kind of character the transition has to key on and a reader cannot see.
    expect(
      formatNumber(1234.5, 'de-DE', resolveFormat({ currency: 'EUR' }))
    ).toBe('1.234,50\u00a0€');
  });

  it('writes the currency as a code or a name on request', () => {
    const currency = 'USD';
    expect(
      formatNumber(1234.5, 'en-US', {
        style: 'currency',
        currency,
        currencyDisplay: 'code',
      })
    ).toBe('USD\u00a01,234.50');
    expect(
      formatNumber(1234.5, 'en-US', {
        style: 'currency',
        currency,
        currencyDisplay: 'name',
      })
    ).toBe('1,234.50 US dollars');
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

  it('multiplies a percentage by a hundred and drops its decimals', () => {
    expect(formatNumber(0.425, 'en-US', { style: 'percent' })).toBe('43%');
  });

  it('pads to a minimum integer width', () => {
    expect(formatNumber(9, 'en-US', { minimumIntegerDigits: 2 })).toBe('09');
  });

  it('rounds a half away from zero, which is Intls rule and neither platforms', () => {
    // Both native formatters are told to do the same. The three implementations disagreeing about
    // whether 2.5 reads as 2 or 3 would be a bug, so it is not left to each platform's default.
    expect(formatNumber(2.5, 'en-US', { maximumFractionDigits: 0 })).toBe('3');
    expect(formatNumber(-2.5, 'en-US', { maximumFractionDigits: 0 })).toBe(
      '-3'
    );
  });

  it('falls back to a plain number rather than throwing on an unknown currency', () => {
    expect(
      formatNumber(12, 'en-US', { style: 'currency', currency: 'NOPE' })
    ).toBe('12');
  });

  it('treats a currency style with no code as a plain number', () => {
    // Intl throws on this pair. The renderers cannot, so all three agree to ignore it.
    expect(formatNumber(1234.5, 'en-US', { style: 'currency' })).toBe(
      '1,234.5'
    );
  });
});

describe('formatNumber, trailing decimal separator', () => {
  // `value` is a number and a number cannot hold `7.`, so typing 7 . 5 produces 7, 7, 7.5. The
  // flag is what gives the mark somewhere to live between the second and third keystroke.
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
    // de-DE writes the symbol last. Appending blindly would put the mark beyond the euro sign.
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
    // Intl throws on that pair; the native formatters clamp. Clamping here keeps a bad prop from
    // taking a render down.
    const options = intlOptions({
      minimumFractionDigits: 4,
      maximumFractionDigits: 1,
    });
    expect(options.maximumFractionDigits).toBe(4);
    expect(() => (1).toLocaleString('en-US', options)).not.toThrow();
  });

  it('passes a bound through untouched so Intl applies its own default to the other', () => {
    expect(intlOptions({ maximumFractionDigits: 1 })).not.toHaveProperty(
      'minimumFractionDigits'
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
});
