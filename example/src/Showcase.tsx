import { useCallback, useState } from 'react';
import {
  Animated,
  Easing,
  Pressable,
  StyleSheet,
  Text,
  View,
  useAnimatedValue,
} from 'react-native';
import { NumericText } from 'react-native-numeric-text';
import { Caption } from './Caption';
import { ActionButton } from './controls/ActionButton';
import { useHoldRepeat } from './controls/useHoldRepeat';
import { useSequencePlayer } from './sequence';
import {
  SHOWCASE,
  SHOWCASE_DURATION,
  SHOWCASE_START,
} from './showcaseSequence';

const DURATION_LABEL = `${(SHOWCASE_DURATION / 1000).toFixed(0)}s`;

type Props = { onOpenLab: () => void };

/**
 * The demo screen: the number, the two controls people actually reach for, and one button that
 * plays the whole story.
 *
 * Everything the comparison lab needs — the SwiftUI toggle, the presets, the diagnostics — lives
 * on the other screen. This one is meant to be pointed at a camera, so nothing appears on it that
 * would not survive being recorded.
 *
 * Play and reset are real native buttons, SwiftUI on iOS and Jetpack Compose on Android. The
 * increment and decrement pair are not, and cannot be: neither native button reports press-down
 * and press-up, only a completed press, and a control that accelerates while held needs to know
 * it is still being held. See `controls/ActionButton.types.ts`.
 */
export function Showcase({ onOpenLab }: Props) {
  const [value, setValue] = useState(SHOWCASE_START);
  const [caption, setCaption] = useState('');
  const [playing, setPlaying] = useState(false);
  const progress = useAnimatedValue(0);

  // An empty phase keeps the previous caption up: a scene's silent setup step should not blank
  // the line before the scene it sets up.
  const onPhase = useCallback((phase: string) => {
    if (phase) setCaption(phase);
  }, []);

  const onDone = useCallback(() => {
    setPlaying(false);
    setCaption('');
  }, []);

  const { play, stop } = useSequencePlayer(setValue, onPhase, onDone, SHOWCASE);

  const startSequence = useCallback(() => {
    setPlaying(true);
    setCaption('');
    progress.setValue(0);
    Animated.timing(progress, {
      toValue: 1,
      duration: SHOWCASE_DURATION,
      easing: Easing.linear,
      useNativeDriver: true,
    }).start();
    play();
  }, [play, progress]);

  const stopSequence = useCallback(() => {
    stop();
    progress.stopAnimation();
    progress.setValue(0);
    setPlaying(false);
    setCaption('');
  }, [stop, progress]);

  const bump = useCallback((direction: number) => {
    setValue((v) => v + direction);
  }, []);
  const hold = useHoldRepeat(bump);

  const reset = useCallback(() => {
    if (playing) return;
    hold.stop();
    setValue(SHOWCASE_START);
  }, [playing, hold]);

  return (
    <View style={styles.screen}>
      <Pressable
        style={styles.labLink}
        onPress={onOpenLab}
        accessibilityRole="button"
      >
        <Text style={styles.labLinkText}>Lab</Text>
      </Pressable>

      <View style={styles.stage}>
        <NumericText
          value={value}
          locale="en-US"
          direction="automatic"
          animationDuration={220}
          useGrouping
          maximumFractionDigits={3}
          style={styles.number}
        />
        <Caption text={caption} />
      </View>

      <View style={styles.controls}>
        <View style={styles.progressTrack}>
          {/* No visibility toggle needed: at rest the scale is 0, so the fill has no width. */}
          <Animated.View
            style={[styles.progressFill, { transform: [{ scaleX: progress }] }]}
          />
        </View>

        <View style={styles.actions}>
          <ActionButton
            label={playing ? 'Stop' : `Play · ${DURATION_LABEL}`}
            icon={playing ? 'stop' : 'play'}
            onPress={playing ? stopSequence : startSequence}
          />
          <ActionButton
            label="Reset"
            icon="reset"
            variant="secondary"
            onPress={reset}
          />
        </View>

        <View style={styles.row}>
          <Pressable
            style={({ pressed }) => [styles.circle, pressed && styles.pressed]}
            onPressIn={() => !playing && hold.start(-1)}
            onPressOut={hold.stop}
            accessibilityLabel="Decrement, hold to repeat"
          >
            <Text style={styles.circleText}>−</Text>
          </Pressable>
          <Pressable
            style={({ pressed }) => [styles.circle, pressed && styles.pressed]}
            onPressIn={() => !playing && hold.start(1)}
            onPressOut={hold.stop}
            accessibilityLabel="Increment, hold to repeat"
          >
            <Text style={styles.circleText}>+</Text>
          </Pressable>
        </View>

        <Text style={styles.hint}>Hold + or − — it speeds up as you hold</Text>
      </View>
    </View>
  );
}

const INK = '#0b0b0d';

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: '#f4f4f6',
    justifyContent: 'space-between',
    paddingTop: 72,
    paddingBottom: 56,
    paddingHorizontal: 24,
  },
  labLink: {
    alignSelf: 'flex-end',
    paddingHorizontal: 14,
    paddingVertical: 7,
    borderRadius: 999,
    backgroundColor: '#e7e7ec',
  },
  labLinkText: {
    fontSize: 13,
    fontWeight: '600',
    color: '#6a6a75',
    letterSpacing: 0.3,
  },

  stage: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 28,
  },
  number: {
    fontSize: 88,
    fontWeight: '700',
    color: INK,
  },

  controls: {
    alignItems: 'center',
    gap: 22,
  },
  progressTrack: {
    width: '60%',
    height: 2,
    borderRadius: 1,
    backgroundColor: '#e2e2e8',
    overflow: 'hidden',
  },
  progressFill: {
    width: '100%',
    height: '100%',
    backgroundColor: INK,
    // scaleX grows from the centre by default; anchor it left so it reads as a progress bar.
    transformOrigin: 'left',
  },

  actions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },

  row: {
    flexDirection: 'row',
    gap: 18,
  },
  circle: {
    width: 62,
    height: 62,
    borderRadius: 31,
    backgroundColor: '#e7e7ec',
    alignItems: 'center',
    justifyContent: 'center',
  },
  circleText: {
    fontSize: 28,
    fontWeight: '500',
    color: INK,
    lineHeight: 32,
  },
  pressed: {
    opacity: 0.6,
  },

  hint: {
    fontSize: 13,
    color: '#9a9aa6',
  },
});
