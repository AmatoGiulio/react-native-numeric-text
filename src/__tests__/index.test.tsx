import { describe, expect, it } from '@jest/globals';
import * as api from '../index';

describe('public surface', () => {
  it('exports NumericText and its NumericTextView alias, as the same component', () => {
    expect(api.NumericText).toBeDefined();
    expect(api.NumericTextView).toBe(api.NumericText);
  });

  // A guard on the package's shape: anything appearing here is a deliberate API decision, not a
  // re-export that leaked out of an internal module.
  it('exports nothing else', () => {
    expect(Object.keys(api).sort()).toEqual(['NumericText', 'NumericTextView']);
  });
});
