import { describe, expect, it, jest } from '@jest/globals';

// The other half of the Reanimated contract: when the runtime is Reanimated 4, `runOnJS` lives in
// `react-native-worklets` — and there it is also spelled `scheduleOnRN`, which is NOT curried. This
// file stands up a virtual Reanimated whose only scheduler is `scheduleOnRN` and checks the loader
// hands back a `runOnJS` of the curried shape the call sites use. Isolated in its own file so the
// virtual mocks never reach the "Reanimated is absent" tests.

const noop = () => undefined;
const reanimatedHooks = {
  createAnimatedComponent: (component: unknown) => component,
  useAnimatedProps: noop,
  useAnimatedStyle: noop,
  useSharedValue: (initial: unknown) => ({ value: initial }),
  useAnimatedReaction: noop,
  withDelay: (_delay: number, animation: unknown) => animation,
  withTiming: (toValue: number) => toValue,
};

describe('optionalReanimated with only worklets scheduleOnRN', () => {
  it('adapts the non-curried scheduleOnRN into the curried runOnJS shape', () => {
    const scheduleOnRN = jest.fn();
    jest.resetModules();
    jest.doMock('react-native-reanimated', () => ({ ...reanimatedHooks }), {
      virtual: true,
    });
    jest.doMock('react-native-worklets', () => ({ scheduleOnRN }), {
      virtual: true,
    });

    const { optionalReanimated } = require('../optionalReanimated');
    const api = optionalReanimated();
    expect(api).not.toBeNull();

    const target = () => undefined;
    api.runOnJS(target)(1, 'two');
    expect(scheduleOnRN).toHaveBeenCalledWith(target, 1, 'two');
  });
});
