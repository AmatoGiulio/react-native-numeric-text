import { StatusBar } from 'expo-status-bar';
import { memo, useCallback, useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import Animated, {
  Easing,
  type SharedValue,
  useAnimatedStyle,
  useSharedValue,
  withDelay,
  withTiming,
} from 'react-native-reanimated';
import {
  NumericText,
  type NumericTextFormat,
} from 'react-native-numeric-text';
import { ReleaseToggle } from './release-toggle';

type DemoState = {
  value: number;
  locale: string;
  format: NumericTextFormat;
};

type ReleaseStep = DemoState & {
  hold: number;
  label: number;
  outro?: boolean;
};

const LABELS = [
  'Number',
  'USD · symbol',
  'USD · code',
  'EUR · de-DE',
  'JPY · zero decimals',
  'Percent',
  'Accounting',
  'AED · RTL',
  'Rapid updates',
] as const;

const RELEASE_SEQUENCE: readonly ReleaseStep[] = [
  { value: 999, locale: 'en-US', format: {}, hold: 650, label: 0 },
  { value: 1000, locale: 'en-US', format: {}, hold: 850, label: 0 },

  {
    value: 999.99,
    locale: 'en-US',
    format: { style: 'currency', currency: 'USD' },
    hold: 650,
    label: 1,
  },
  {
    value: 1000,
    locale: 'en-US',
    format: { style: 'currency', currency: 'USD' },
    hold: 850,
    label: 1,
  },
  {
    value: 100,
    locale: 'en-US',
    format: {
      style: 'currency',
      currency: 'USD',
      currencyDisplay: 'code',
    },
    hold: 800,
    label: 2,
  },

  {
    value: 999.99,
    locale: 'de-DE',
    format: { style: 'currency', currency: 'EUR' },
    hold: 650,
    label: 3,
  },
  {
    value: 1000,
    locale: 'de-DE',
    format: { style: 'currency', currency: 'EUR' },
    hold: 850,
    label: 3,
  },

  {
    value: 999,
    locale: 'ja-JP',
    format: { style: 'currency', currency: 'JPY' },
    hold: 650,
    label: 4,
  },
  {
    value: 1000,
    locale: 'ja-JP',
    format: { style: 'currency', currency: 'JPY' },
    hold: 850,
    label: 4,
  },

  {
    value: 9.99,
    locale: 'en-US',
    format: { style: 'percent' },
    hold: 650,
    label: 5,
  },
  {
    value: 10,
    locale: 'en-US',
    format: { style: 'percent' },
    hold: 850,
    label: 5,
  },

  {
    value: -999.99,
    locale: 'en-US',
    format: {
      style: 'currency',
      currency: 'USD',
      currencySign: 'accounting',
    },
    hold: 700,
    label: 6,
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
    label: 6,
  },

  {
    value: 234.5,
    locale: 'ar-AE',
    format: { style: 'currency', currency: 'AED' },
    hold: 700,
    label: 7,
  },
  {
    value: 235.5,
    locale: 'ar-AE',
    format: { style: 'currency', currency: 'AED' },
    hold: 850,
    label: 7,
  },

  {
    value: 234.5,
    locale: 'ar-AE',
    format: { style: 'currency', currency: 'AED' },
    hold: 85,
    label: 8,
  },
  {
    value: 235.5,
    locale: 'ar-AE',
    format: { style: 'currency', currency: 'AED' },
    hold: 85,
    label: 8,
  },
  {
    value: 234.5,
    locale: 'ar-AE',
    format: { style: 'currency', currency: 'AED' },
    hold: 85,
    label: 8,
  },
  {
    value: 235.5,
    locale: 'ar-AE',
    format: { style: 'currency', currency: 'AED' },
    hold: 85,
    label: 8,
  },
  {
    value: 234.5,
    locale: 'ar-AE',
    format: { style: 'currency', currency: 'AED' },
    hold: 85,
    label: 8,
  },
  {
    value: 235.5,
    locale: 'ar-AE',
    format: { style: 'currency', currency: 'AED' },
    hold: 1100,
    label: 8,
  },

  {
    value: 235.5,
    locale: 'ar-AE',
    format: { style: 'currency', currency: 'AED' },
    hold: 1800,
    label: 8,
    outro: true,
  },
];

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
  onStep: (step: ReleaseStep) => void,
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
      return { at: when, run: () => onStep(step) };
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
  const first = RELEASE_SEQUENCE[0]!;
  const [state, setState] = useState<DemoState>(() => stateFromStep(first));
  const [playing, setPlaying] = useState(false);
  const activeLabel = useSharedValue(first.label);
  const outro = useSharedValue(0);

  const onDone = useCallback(() => setPlaying(false), []);
  const onStep = useCallback(
    (step: ReleaseStep) => {
      if (step.outro) {
        outro.value = 1;
        return;
      }

      outro.value = 0;
      activeLabel.value = step.label;
      setState(stateFromStep(step));
    },
    [activeLabel, outro]
  );
  const { play, stop } = useReleaseSequence(onStep, onDone);

  const demoSceneStyle = useAnimatedStyle(() => {
    'worklet';

    const hidden = outro.value === 1;
    return {
      opacity: withTiming(hidden ? 0 : 1, {
        duration: hidden ? 220 : 180,
        easing: Easing.out(Easing.cubic),
      }),
      transform: [
        {
          translateY: withTiming(hidden ? -10 : 0, {
            duration: 260,
            easing: Easing.out(Easing.cubic),
          }),
        },
        {
          scale: withTiming(hidden ? 0.985 : 1, {
            duration: 260,
            easing: Easing.out(Easing.cubic),
          }),
        },
      ],
    };
  });

  const outroStyle = useAnimatedStyle(() => {
    'worklet';

    const visible = outro.value === 1;
    return {
      opacity: visible
        ? withDelay(
            150,
            withTiming(1, {
              duration: 300,
              easing: Easing.out(Easing.cubic),
            })
          )
        : withTiming(0, { duration: 140 }),
      transform: [
        {
          translateY: visible
            ? withDelay(
                150,
                withTiming(0, {
                  duration: 320,
                  easing: Easing.out(Easing.cubic),
                })
              )
            : withTiming(10, { duration: 140 }),
        },
        {
          scale: visible
            ? withDelay(
                150,
                withTiming(1, {
                  duration: 320,
                  easing: Easing.out(Easing.cubic),
                })
              )
            : withTiming(0.985, { duration: 140 }),
        },
      ],
    };
  });

  const setPlayback = useCallback(
    (nextPlaying: boolean) => {
      if (nextPlaying === playing) return;

      if (nextPlaying) {
        outro.value = 0;
        setPlaying(true);
        play();
      } else {
        stop();
        setPlaying(false);
      }
    },
    [outro, play, playing, stop]
  );

  return (
    <View style={styles.screen}>
      <StatusBar style="dark" />

      <View pointerEvents="none" style={styles.balanceSlot} />

      <View style={styles.content}>
        <Animated.View style={[styles.demoScene, demoSceneStyle]}>
          <NumericText
            value={state.value}
            locale={state.locale}
            format={state.format}
            animationDuration={320}
            style={styles.number}
          />
          <LabelStack activeLabel={activeLabel} />
        </Animated.View>

        <Animated.View
          pointerEvents="none"
          style={[styles.outroScene, outroStyle]}
        >
          <Text style={styles.installLabel}>
            npm install react-native-numeric-text
          </Text>
        </Animated.View>
      </View>

      <View style={styles.playback}>
        <ReleaseToggle playing={playing} onCheckedChange={setPlayback} />
      </View>
    </View>
  );
}

const LabelStack = memo(function LabelStack({
  activeLabel,
}: {
  activeLabel: SharedValue<number>;
}) {
  return (
    <View pointerEvents="none" style={styles.labelViewport}>
      {LABELS.map((label, index) => (
        <AnimatedLabel
          key={label}
          index={index}
          label={label}
          activeLabel={activeLabel}
        />
      ))}
    </View>
  );
});

function AnimatedLabel({
  index,
  label,
  activeLabel,
}: {
  index: number;
  label: string;
  activeLabel: SharedValue<number>;
}) {
  const animatedStyle = useAnimatedStyle(() => {
    'worklet';

    const delta = index - activeLabel.value;
    const active = delta === 0;
    const targetY = active ? 0 : delta < 0 ? -8 : 8;

    return {
      opacity: withTiming(active ? 1 : 0, {
        duration: 190,
        easing: Easing.out(Easing.cubic),
      }),
      transform: [
        {
          translateY: withTiming(targetY, {
            duration: 240,
            easing: Easing.out(Easing.cubic),
          }),
        },
      ],
    };
  }, [index]);

  return (
    <Animated.View style={[styles.labelLayer, animatedStyle]}>
      <Text style={styles.label}>{label}</Text>
    </Animated.View>
  );
}

const INK = '#171719';
const EDGE_SLOT_HEIGHT = 110;

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    alignItems: 'center',
    paddingHorizontal: 20,
    backgroundColor: '#fbfbf9',
  },
  balanceSlot: {
    width: '100%',
    height: EDGE_SLOT_HEIGHT,
  },
  content: {
    flex: 1,
    width: '100%',
    alignItems: 'center',
    justifyContent: 'center',
  },
  demoScene: {
    width: '100%',
    alignItems: 'center',
  },
  outroScene: {
    position: 'absolute',
    left: 0,
    right: 0,
    alignItems: 'center',
    justifyContent: 'center',
  },
  number: {
    color: INK,
    fontSize: 64,
    fontWeight: '700',
    fontVariant: ['tabular-nums'],
    textAlign: 'center',
  },
  labelViewport: {
    width: '100%',
    height: 26,
    marginTop: 14,
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
  },
  labelLayer: {
    position: 'absolute',
    left: 0,
    right: 0,
    alignItems: 'center',
  },
  label: {
    color: '#6f6f73',
    fontSize: 16,
    lineHeight: 22,
    fontWeight: '600',
    letterSpacing: -0.15,
    textAlign: 'center',
  },
  installLabel: {
    maxWidth: '100%',
    paddingHorizontal: 8,
    color: INK,
    fontFamily: 'monospace',
    fontSize: 16,
    lineHeight: 22,
    fontWeight: '500',
    letterSpacing: -0.35,
    textAlign: 'center',
  },
  playback: {
    width: '100%',
    height: EDGE_SLOT_HEIGHT,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
