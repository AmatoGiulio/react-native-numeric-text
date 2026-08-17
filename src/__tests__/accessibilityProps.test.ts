import { describe, expect, it } from '@jest/globals';
import { accessibilityPropsOf } from '../accessibilityProps';

describe('accessibilityPropsOf', () => {
  it('forwards accessibility semantics without leaking numeric props', () => {
    const forwarded = accessibilityPropsOf({
      value: 1000,
      currency: 'USD',
      format: { style: 'currency', currency: 'USD' },
      accessible: true,
      accessibilityLabel: 'Account balance',
      accessibilityHint: 'Current available balance',
      accessibilityRole: 'text',
      accessibilityLiveRegion: 'polite',
    });

    expect(forwarded).toMatchObject({
      accessible: true,
      accessibilityLabel: 'Account balance',
      accessibilityHint: 'Current available balance',
      accessibilityRole: 'text',
      accessibilityLiveRegion: 'polite',
    });
    expect(forwarded).not.toHaveProperty('value');
    expect(forwarded).not.toHaveProperty('currency');
    expect(forwarded).not.toHaveProperty('format');
  });
});
