import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Animated,
  Easing,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { NumericText } from 'react-native-numeric-text';
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
 */
export function Showcase({ onOpenLab }: Props) {
  const [value, setValue] = useState(SHOWCASE_START);
  const [caption, setCaption] = useState('');
  const [playing, setPlaying] = useState(false);
  const progress = useRef(new Animated.Value(0)).current;

  // An empty phase keeps the previous caption up: a scene's silent setup step should not blank
  // the line for 300 ms before the scene it is setting up.
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

  // Press and hold to repeat — the renderer's hardest case is reachable with a thumb, not only
  // from the scripted run.
  const holdRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const startHold = useCallback(
    (dir: number) => {
      if (playing) return;
      setValue((v) => v + dir);
      if (holdRef.current) clearInterval(holdRef.current);
      holdRef.current = setInterval(() => setValue((v) => v + dir), 30);
    },
    [playing]
  );
  const stopHold = useCallback(() => {
    if (holdRef.current) {
      clearInterval(holdRef.current);
      holdRef.current = null;
    }
  }, []);
  useEffect(() => () => stopHold(), [stopHold]);

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
        <Text style={styles.caption} numberOfLines={2}>
          {caption}
        </Text>
      </View>

      <View style={styles.controls}>
        <View style={styles.progressTrack}>
          {/* No visibility toggle needed: at rest the scale is 0, so the fill has no width. */}
          <Animated.View
            style={[styles.progressFill, { transform: [{ scaleX: progress }] }]}
          />
        </View>

        <Pressable
          style={({ pressed }) => [
            styles.playBtn,
            playing && styles.playBtnActive,
            pressed && styles.pressed,
          ]}
          onPress={playing ? stopSequence : startSequence}
          accessibilityRole="button"
        >
          <Text style={[styles.playText, playing && styles.playTextActive]}>
            {playing ? 'Stop' : `Play the sequence · ${DURATION_LABEL}`}
          </Text>
        </Pressable>

        <View style={styles.row}>
          <Pressable
            style={({ pressed }) => [styles.circle, pressed && styles.pressed]}
            onPressIn={() => startHold(-1)}
            onPressOut={stopHold}
            accessibilityLabel="Decrement, hold to repeat"
          >
            <Text style={styles.circleText}>−</Text>
          </Pressable>
          <Pressable
            style={({ pressed }) => [styles.circle, pressed && styles.pressed]}
            onPress={() => !playing && setValue(SHOWCASE_START)}
            accessibilityLabel="Reset"
          >
            <Text style={styles.resetText}>⟲</Text>
          </Pressable>
          <Pressable
            style={({ pressed }) => [styles.circle, pressed && styles.pressed]}
            onPressIn={() => startHold(1)}
            onPressOut={stopHold}
            accessibilityLabel="Increment, hold to repeat"
          >
            <Text style={styles.circleText}>+</Text>
          </Pressable>
        </View>

        <Text style={styles.hint}>Hold + or − to roll continuously</Text>
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
  // Reserved height, so a caption appearing or leaving never nudges the number off centre.
  caption: {
    height: 44,
    fontSize: 15,
    lineHeight: 21,
    textAlign: 'center',
    color: '#7c7c88',
    letterSpacing: 0.1,
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

  playBtn: {
    paddingHorizontal: 28,
    paddingVertical: 15,
    borderRadius: 999,
    backgroundColor: INK,
  },
  playBtnActive: {
    backgroundColor: '#e7e7ec',
  },
  playText: {
    fontSize: 16,
    fontWeight: '600',
    color: '#ffffff',
    letterSpacing: 0.2,
  },
  playTextActive: {
    color: INK,
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
  resetText: {
    fontSize: 22,
    color: '#6a6a75',
    lineHeight: 26,
  },
  pressed: {
    opacity: 0.6,
  },

  hint: {
    fontSize: 13,
    color: '#9a9aa6',
  },
});
