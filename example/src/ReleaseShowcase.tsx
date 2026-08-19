import { StatusBar } from 'expo-status-bar';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import {
  NumericText,
  type NumericTextFormat,
} from 'react-native-numeric-text';
import { NativeIconButton } from './native-icon-button';

type DemoState = {
  value: number;
  locale: string;
  format: NumericTextFormat;
};

type ReleaseStep = DemoState & {
  hold: number;
};

const RELEASE_SEQUENCE: readonly ReleaseStep[] = [
  // Plain number: establish the grouping carry before formatting enters.
  { value: 999, locale: 'en-US', format: {}, hold: 650 },
  { value: 1000, locale: 'en-US', format: {}, hold: 850 },

  // USD prefix symbol, then a format-only transition to the ISO code.
  {
    value: 999.99,
    locale: 'en-US',
    format: { style: 'currency', currency: 'USD' },
    hold: 650,
  },
  {
    value: 1000,
    locale: 'en-US',
    format: { style: 'currency', currency: 'USD' },
    hold: 850,
  },
  {
    value: 1000,
    locale: 'en-US',
    format: {
      style: 'currency',
      currency: 'USD',
      currencyDisplay: 'code',
    },
    hold: 800,
  },

  // EUR keeps the suffix symbol alive while the locale-specific punctuation changes.
  {
    value: 999.99,
    locale: 'de-DE',
    format: { style: 'currency', currency: 'EUR' },
    hold: 650,
  },
  {
    value: 1000,
    locale: 'de-DE',
    format: { style: 'currency', currency: 'EUR' },
    hold: 850,
  },

  // JPY exercises a currency whose native default has no fractional digits.
  {
    value: 999,
    locale: 'ja-JP',
    format: { style: 'currency', currency: 'JPY' },
    hold: 650,
  },
  {
    value: 1000,
    locale: 'ja-JP',
    format: { style: 'currency', currency: 'JPY' },
    hold: 850,
  },

  // Percent multiplies by 100, so 9.99 -> 10 crosses the 999% -> 1,000% boundary.
  {
    value: 9.99,
    locale: 'en-US',
    format: { style: 'percent' },
    hold: 650,
  },
  {
    value: 10,
    locale: 'en-US',
    format: { style: 'percent' },
    hold: 850,
  },

  // Accounting brackets leave the structure as the value crosses zero.
  {
    value: -999.99,
    locale: 'en-US',
    format: {
      style: 'currency',
      currency: 'USD',
      currencySign: 'accounting',
    },
    hold: 700,
  },
  {
    value: 1000,
    locale: 'en-US',
    format: {
      style: 'currency',
      currency: 'USD',
      currencySign: 'accounting',
    },
    hold: 950,
  },

  // Finish with an RTL currency and a rapid 85 ms retarget burst.
  {
    value: 1234.5,
    locale: 'ar-AE',
    format: { style: 'currency', currency: 'AED' },
    hold: 700,
  },
  {
    value: 1235.5,
    locale: 'ar-AE',
    format: { style: 'currency', currency: 'AED' },
    hold: 850,
  },
  {
    value: 1234.5,
    locale: 'ar-AE',
    format: { style: 'currency', currency: 'AED' },
    hold: 85,
  },
  {
    value: 1235.5,
    locale: 'ar-AE',
    format: { style: 'currency', currency: 'AED' },
    hold: 85,
  },
  {
    value: 1234.5,
    locale: 'ar-AE',
    format: { style: 'currency', currency: 'AED' },
    hold: 85,
  },
  {
    value: 1235.5,
    locale: 'ar-AE',
    format: { style: 'currency', currency: 'AED' },
    hold: 85,
  },
  {
    value: 1234.5,
    locale: 'ar-AE',
    format: { style: 'currency', currency: 'AED' },
    hold: 85,
  },
  {
    value: 1235.5,
    locale: 'ar-AE',
    format: { style: 'currency', currency: 'AED' },
    hold: 1100,
  },
];

const HOLD_DELAY_MS = 350;
const HOLD_REPEAT_MS = 90;

function stateFromStep(step: ReleaseStep): DemoState {
  return {
    value: step.value,
    locale: step.locale,
    format: step.format,
  };
}

function scheduleOnFrames(
  entries: readonly { at: number; run: () => void }[]
): () => void {
  const start = Date.now();
  let next = 0;
  let frame: number | null = null;

  const tick = () => {
    const elapsed = Date.now() - start;
    while (next < entries.length && entries[next]!.at <= elapsed) {
      entries[next]!.run();
      next += 1;
    }
    frame = next < entries.length ? requestAnimationFrame(tick) : null;
  };

  frame = requestAnimationFrame(tick);
  return () => {
    if (frame !== null) cancelAnimationFrame(frame);
    frame = null;
  };
}

function useReleaseSequence(
  onStep: (state: DemoState) => void,
  onDone: () => void
) {
  const cancel = useRef<(() => void) | null>(null);

  const stop = useCallback(() => {
    cancel.current?.();
    cancel.current = null;
  }, []);

  const play = useCallback(() => {
    stop();

    let at = 0;
    const entries = RELEASE_SEQUENCE.map((step) => {
      const when = at;
      at += step.hold;
      return {
        at: when,
        run: () => onStep(stateFromStep(step)),
      };
    });

    entries.push({
      at,
      run: () => {
        cancel.current = null;
        onDone();
      },
    });

    cancel.current = scheduleOnFrames(entries);
  }, [onDone, onStep, stop]);

  useEffect(() => stop, [stop]);

  return { play, stop };
}

export function ReleaseShowcase() {
  const [state, setState] = useState<DemoState>(() =>
    stateFromStep(RELEASE_SEQUENCE[0]!)
  );
  const [playing, setPlaying] = useState(false);
  const holdDelay = useRef<ReturnType<typeof setTimeout> | null>(null);
  const holdInterval = useRef<ReturnType<typeof setInterval> | null>(null);

  const stopHold = useCallback(() => {
    if (holdDelay.current !== null) clearTimeout(holdDelay.current);
    if (holdInterval.current !== null) clearInterval(holdInterval.current);
    holdDelay.current = null;
    holdInterval.current = null;
  }, []);

  const onDone = useCallback(() => setPlaying(false), []);
  const onStep = useCallback((next: DemoState) => setState(next), []);
  const { play, stop } = useReleaseSequence(onStep, onDone);

  useEffect(() => stopHold, [stopHold]);

  const togglePlayback = useCallback(() => {
    stopHold();
    if (playing) {
      stop();
      setPlaying(false);
      return;
    }

    setPlaying(true);
    play();
  }, [play, playing, stop, stopHold]);

  const startHold = useCallback(
    (direction: -1 | 1) => {
      stopHold();
      if (playing) {
        stop();
        setPlaying(false);
      }

      const delta = state.format.style === 'percent' ? 0.01 : 1;
      const applyDelta = () =>
        setState((current) => ({
          ...current,
          value: current.value + direction * delta,
        }));

      applyDelta();
      holdDelay.current = setTimeout(() => {
        holdInterval.current = setInterval(applyDelta, HOLD_REPEAT_MS);
      }, HOLD_DELAY_MS);
    },
    [playing, state.format.style, stop, stopHold]
  );

  return (
    <View style={styles.screen}>
      <StatusBar style="dark" />

      <View style={styles.showcase}>
        <NumericText
          value={state.value}
          locale={state.locale}
          format={state.format}
          animationDuration={320}
          style={styles.number}
        />

        <View style={styles.controls}>
          <IconButton
            icon="remove"
            accessibilityLabel="Decrement"
            onPressIn={() => startHold(-1)}
            onPressOut={stopHold}
          />
          <IconButton
            icon={playing ? 'stop' : 'play-arrow'}
            accessibilityLabel={playing ? 'Stop sequence' : 'Play sequence'}
            primary
            onPress={togglePlayback}
          />
          <IconButton
            icon="add"
            accessibilityLabel="Increment"
            onPressIn={() => startHold(1)}
            onPressOut={stopHold}
          />
        </View>
      </View>
    </View>
  );
}

type IconButtonProps = {
  icon: 'add' | 'play-arrow' | 'remove' | 'stop';
  accessibilityLabel: string;
  primary?: boolean;
  onPress?: () => void;
  onPressIn?: () => void;
  onPressOut?: () => void;
};

function IconButton({
  icon,
  accessibilityLabel,
  primary = false,
  onPress,
  onPressIn,
  onPressOut,
}: IconButtonProps) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      onPress={onPress}
      onPressIn={onPressIn}
      onPressOut={onPressOut}
      hitSlop={8}
      style={({ pressed }) => [styles.button, pressed && styles.buttonPressed]}
    >
      <NativeIconButton icon={icon} primary={primary} />
    </Pressable>
  );
}

const INK = '#171719';

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 20,
    backgroundColor: '#fbfbf9',
  },
  showcase: {
    width: '100%',
    alignItems: 'center',
    gap: 34,
  },
  number: {
    color: INK,
    fontSize: 64,
    fontWeight: '700',
    fontVariant: ['tabular-nums'],
    textAlign: 'center',
  },
  controls: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 14,
  },
  button: {
    width: 58,
    height: 58,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 29,
  },
  buttonPressed: {
    opacity: 0.72,
    transform: [{ scale: 0.96 }],
  },
});
