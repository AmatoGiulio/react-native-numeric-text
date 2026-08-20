import { describe, expect, it } from '@jest/globals';
import { isSharedValue } from '../sharedValue';

describe('isSharedValue', () => {
  it('separates a shared value from a plain number', () => {
    expect(isSharedValue(0)).toBe(false);
    expect(isSharedValue(-1234.5)).toBe(false);
    expect(isSharedValue(Number.NaN)).toBe(false);
    expect(isSharedValue({ value: 42 })).toBe(true);
  });

  it('does not read the value', () => {
    let reads = 0;
    const shared = {
      get value() {
        reads += 1;
        return 42;
      },
    };

    expect(isSharedValue(shared)).toBe(true);
    // Reanimated warns about a `.value` read outside a first render, and this runs on every render
    // of every instance.
    expect(reads).toBe(0);
  });
});
