import type { ComponentType } from 'react';

/**
 * Reanimated, if the app has it.
 *
 * A shared value can only reach this library from an app that already depends on
 * `react-native-reanimated`, so the dependency is optional and is never imported statically: an
 * `import` would put it in every consumer's module graph and break the bundle for the majority who
 * pass a plain number. The `require` sits inside a `try` on purpose — Metro treats a `require` in a
 * `try` block as an optional dependency and leaves it unresolved instead of failing the build.
 *
 * Only the handful of entry points this library uses are picked out of the module, and each is
 * described structurally. Nothing here refers to Reanimated's own types, so the published `.d.ts`
 * files stay compilable without it installed.
 */

type Mutable<T> = { value: T };

/** The props an animated component adds on top of the component it wraps. */
export type AnimatedProps<P extends object> = Omit<P, 'style'> & {
  style?: unknown;
  animatedProps?: object;
};

export type ReanimatedApi = {
  createAnimatedComponent: <P extends object>(
    component: ComponentType<P>
  ) => ComponentType<AnimatedProps<P>>;
  useAnimatedProps: <P extends object>(
    updater: () => P,
    dependencies?: unknown[]
  ) => P;
  useAnimatedStyle: <S extends object>(
    updater: () => S,
    dependencies?: unknown[]
  ) => S;
  useSharedValue: <T>(initialValue: T) => Mutable<T>;
  useAnimatedReaction: <T>(
    prepare: () => T,
    react: (current: T, previous: T | null) => void,
    dependencies?: unknown[]
  ) => void;
  withDelay: <T>(delayMs: number, animation: T) => T;
  withTiming: (toValue: number, config?: { duration?: number }) => number;
  runOnJS: <A extends unknown[]>(
    fn: (...args: A) => void
  ) => (...args: A) => void;
};

type ReanimatedModule = Partial<ReanimatedApi> & {
  default?: Partial<ReanimatedApi>;
};

declare const require: (moduleName: string) => unknown;

let resolved: ReanimatedApi | null | undefined;

function load(): ReanimatedApi | null {
  let reanimated: ReanimatedModule;
  try {
    reanimated = require('react-native-reanimated') as ReanimatedModule;
  } catch {
    return null;
  }

  // `createAnimatedComponent` is both a named export and a member of the default export; older
  // versions only carried the latter. `runOnJS` moved to `react-native-worklets` as `scheduleOnRN`
  // and is still re-exported here, so the move is absorbed rather than waited for.
  const createAnimatedComponent =
    reanimated.createAnimatedComponent ??
    reanimated.default?.createAnimatedComponent;
  const runOnJS = reanimated.runOnJS ?? scheduleOnRN();

  if (
    !createAnimatedComponent ||
    !reanimated.useAnimatedProps ||
    !reanimated.useAnimatedStyle ||
    !reanimated.useSharedValue ||
    !reanimated.useAnimatedReaction ||
    !reanimated.withDelay ||
    !reanimated.withTiming ||
    !runOnJS
  ) {
    return null;
  }

  return {
    createAnimatedComponent,
    runOnJS,
    useAnimatedProps: reanimated.useAnimatedProps,
    useAnimatedStyle: reanimated.useAnimatedStyle,
    useSharedValue: reanimated.useSharedValue,
    useAnimatedReaction: reanimated.useAnimatedReaction,
    withDelay: reanimated.withDelay,
    withTiming: reanimated.withTiming,
  };
}

/** Reanimated 4's `runOnJS` lives in `react-native-worklets` under its new name. */
function scheduleOnRN(): ReanimatedApi['runOnJS'] | undefined {
  try {
    const worklets = require('react-native-worklets') as {
      runOnJS?: ReanimatedApi['runOnJS'];
      scheduleOnRN?: ReanimatedApi['runOnJS'];
    };
    return worklets.runOnJS ?? worklets.scheduleOnRN;
  } catch {
    return undefined;
  }
}

/** Reanimated if it is installed and complete, `null` otherwise. */
export function optionalReanimated(): ReanimatedApi | null {
  if (resolved === undefined) resolved = load();
  return resolved;
}

/**
 * Reanimated, or a readable failure.
 *
 * Only reachable from a render that was handed a shared value, which is not something an app
 * without Reanimated can produce — so this throwing means the module is installed but unusable
 * (a broken install, or an incompatible major), not that the caller made a mistake.
 */
export function requireReanimated(): ReanimatedApi {
  const reanimated = optionalReanimated();
  if (!reanimated) {
    throw new Error(
      'react-native-numeric-text: `value` was given a shared value, but react-native-reanimated ' +
        'could not be loaded. Install it (and rebuild the app) to drive the number from the UI thread.'
    );
  }
  return reanimated;
}
