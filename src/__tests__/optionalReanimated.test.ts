import { describe, expect, it } from '@jest/globals';
import { optionalReanimated, requireReanimated } from '../optionalReanimated';

// This workspace deliberately does not depend on Reanimated: the library must build, typecheck and
// run without it, and these tests are the "without it" half of that contract. If Reanimated is ever
// added to the root package, these are the tests to look at first.
describe('optionalReanimated', () => {
  it('reports its absence instead of throwing', () => {
    expect(optionalReanimated()).toBeNull();
  });

  it('names the package it needs when a shared value arrives without it', () => {
    expect(() => requireReanimated()).toThrow(/react-native-reanimated/);
  });
});
