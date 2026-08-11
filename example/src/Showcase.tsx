import { StatusBar } from 'expo-status-bar';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { NumericText } from 'react-native-numeric-text';
import { NativeIconButton } from './native-icon-button';
import { SHOWCASE_SEQUENCE, useSequencePlayer } from './sequence';

const START_VALUE = SHOWCASE_SEQUENCE[0]!.value;
const STEP = 123;
const HOLD_DELAY_MS = 350;
const HOLD_REPEAT_MS = 90;

export function Showcase() {
  const [value, setValue] = useState(START_VALUE);
  const [playing, setPlaying] = useState(false);
  const holdDelay = useRef<ReturnType<typeof setTimeout> | null>(null);
  const holdInterval = useRef<ReturnType<typeof setInterval> | null>(null);

  const onDone = useCallback(() => setPlaying(false), []);
  const { play, stop } = useSequencePlayer(setValue, onDone);

  const stopHold = useCallback(() => {
    if (holdDelay.current !== null) clearTimeout(holdDelay.current);
    if (holdInterval.current !== null) clearInterval(holdInterval.current);
    holdDelay.current = null;
    holdInterval.current = null;
  }, []);

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
    (delta: number) => {
      stopHold();
      if (playing) {
        stop();
        setPlaying(false);
      }

      setValue((current) => current + delta);
      holdDelay.current = setTimeout(() => {
        holdInterval.current = setInterval(
          () => setValue((current) => current + delta),
          HOLD_REPEAT_MS
        );
      }, HOLD_DELAY_MS);
    },
    [playing, stop, stopHold]
  );

  return (
    <View style={styles.screen}>
      <StatusBar style="dark" />

      <NumericText
        value={value}
        animationDuration={320}
        minimumFractionDigits={0}
        maximumFractionDigits={2}
        useGrouping
        style={styles.number}
      />

      <View style={styles.controls}>
        <IconButton
          icon="remove"
          accessibilityLabel="Decrement"
          onPressIn={() => startHold(-STEP)}
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
          onPressIn={() => startHold(STEP)}
          onPressOut={stopHold}
        />
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
    gap: 40,
    paddingHorizontal: 24,
    backgroundColor: '#fbfbf9',
  },
  number: {
    color: INK,
    fontSize: 88,
    fontWeight: '700',
    fontVariant: ['tabular-nums'],
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
