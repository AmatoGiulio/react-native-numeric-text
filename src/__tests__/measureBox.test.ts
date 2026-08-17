import { describe, expect, it } from '@jest/globals';
import { measureBox, widest, widthInEm } from '../measureBox';

describe('widthInEm', () => {
  it('charges a separator far less than a digit', () => {
    // The two differ by more than 2x in the bundled font, which is why the estimate is per
    // character: a flat average over "1,000" is wrong by most of a digit.
    expect(widthInEm(',')).toBeLessThan(widthInEm('0') / 2);
  });

  it('gives every digit the same width', () => {
    const widths = [...'0123456789'].map((d) => widthInEm(d));
    expect(new Set(widths).size).toBe(1);
  });

  it('adds up across a grouped number', () => {
    expect(widthInEm('1,000')).toBeCloseTo(
      4 * widthInEm('0') + widthInEm(','),
      5
    );
  });

  it('counts a minus sign', () => {
    expect(widthInEm('-1')).toBeGreaterThan(widthInEm('1'));
    expect(widthInEm('−1')).toBe(widthInEm('-1'));
  });

  it('charges a currency symbol as a glyph rather than as punctuation', () => {
    // `$` measures 0.6064 em in the bundled Regular and a comma 0.2541. Charging the first at the
    // second's width under-reserves the box by most of a digit, which the headroom cannot absorb
    // once a format carries several of them.
    for (const symbol of ['$', '€', '£', '¥', '₹', '%']) {
      expect(widthInEm(symbol)).toBeGreaterThan(widthInEm(','));
    }
  });

  it('charges the letters of an ISO currency code', () => {
    expect(widthInEm('USD')).toBeGreaterThan(3 * widthInEm(','));
  });

  it('keeps the spaces a locale groups with narrow', () => {
    // fr-FR groups with a narrow no-break space and puts a no-break space before the symbol.
    // Charging either as a glyph would reserve most of a digit for a gap.
    for (const space of ['\u00a0', '\u2007', '\u2008', '\u2009', '\u202f']) {
      expect(widthInEm(space)).toBe(widthInEm(','));
    }
  });
});

describe('measureBox', () => {
  it('scales with font size', () => {
    const small = measureBox('1,000', 24);
    const large = measureBox('1,000', 48);
    expect(large.minWidth).toBeGreaterThan(small.minWidth);
    expect(large.minHeight).toBeGreaterThan(small.minHeight);
  });

  it('leaves headroom beyond the glyphs themselves', () => {
    // A dying glyph drifts outward and carries a blur halo, so the ink reaches past the advance.
    const size = 48;
    expect(measureBox('1', size).minWidth).toBeGreaterThan(
      widthInEm('1') * size
    );
  });

  it('falls back to the renderer default when no font size is given', () => {
    expect(measureBox('1', undefined)).toEqual(measureBox('1', 48));
  });

  it('is wider for a longer number', () => {
    expect(measureBox('9,999', 48).minWidth).toBeGreaterThan(
      measureBox('1', 48).minWidth
    );
  });
});

describe('widest', () => {
  it('takes the larger of each dimension independently', () => {
    expect(
      widest({ minWidth: 10, minHeight: 90 }, { minWidth: 40, minHeight: 20 })
    ).toEqual({ minWidth: 40, minHeight: 90 });
  });
});
