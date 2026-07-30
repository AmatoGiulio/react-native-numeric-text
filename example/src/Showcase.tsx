import { useCallback, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { NumericText } from 'react-native-numeric-text';
import { SEQUENCE, SEQUENCE_DURATION, useSequencePlayer } from './sequence';

/**
 * The demo screen, kept to what a camera should see: the number, the two buttons that change it,
 * and one button that plays the scripted run.
 *
 * The number and the +/− pair follow the SwiftUI example this library is measured against
 * (SchroederNathan/expo-ui-examples, `numeric-transitions`): a single count, one step of ±123 per
 * press — big enough that several columns change at once — and a centred stack with the buttons
 * under the number. Everything here is plain React Native; the only native thing on screen is the
 * number itself, which is the point of the recording.
 *
 * The measurement harness — the SwiftUI reference toggle, the presets, the diagnostics — lives on
 * the Lab screen. This one just plays the same [SEQUENCE] the Lab does, so a Showcase recording on
 * iOS and one on Android line up frame for frame.
 */

const START = 1000;
const STEP = 123;

const PLAY_LABEL = `Play · ${Math.round(SEQUENCE_DURATION / 1000)}s`;

type Props = { onOpenLab: () => void };

export function Showcase({ onOpenLab }: Props) {
  const [value, setValue] = useState(START);
  const [playing, setPlaying] = useState(false);

  const onDone = useCallback(() => setPlaying(false), []);
  const { play, stop } = useSequencePlayer(
    setValue,
    undefined,
    onDone,
    SEQUENCE
  );

  const togglePlay = useCallback(() => {
    if (playing) {
      stop();
      setPlaying(false);
      return;
    }
    setPlaying(true);
    play();
  }, [playing, play, stop]);

  const change = useCallback((delta: number) => {
    setValue((n) => n + delta);
  }, []);

  return (
    <View style={styles.screen}>
      <Pressable
        style={styles.lab}
        onPress={onOpenLab}
        accessibilityRole="button"
      >
        <Text style={styles.labText}>Lab</Text>
      </Pressable>

      <View style={styles.stack}>
        <NumericText value={value} style={styles.number} />

        <View style={styles.row}>
          <Round
            label="−"
            hint="Decrement"
            onPress={() => change(-STEP)}
            disabled={playing}
          />
          <Round
            label="+"
            hint="Increment"
            onPress={() => change(STEP)}
            disabled={playing}
          />
        </View>
      </View>

      <Pressable
        style={({ pressed }) => [styles.play, pressed && styles.pressed]}
        onPress={togglePlay}
        accessibilityRole="button"
      >
        <Text style={styles.playText}>{playing ? 'Stop' : PLAY_LABEL}</Text>
      </Pressable>
    </View>
  );
}

type RoundProps = {
  label: string;
  hint: string;
  onPress: () => void;
  disabled: boolean;
};

function Round({ label, hint, onPress, disabled }: RoundProps) {
  return (
    <Pressable
      style={({ pressed }) => [styles.round, pressed && styles.pressed]}
      onPress={onPress}
      disabled={disabled}
      accessibilityRole="button"
      accessibilityLabel={hint}
    >
      <Text style={styles.roundText}>{label}</Text>
    </Pressable>
  );
}

const INK = '#0b0b0d';
const QUIET = '#e7e7ec';

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: '#f4f4f6',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingTop: 72,
    paddingBottom: 56,
    paddingHorizontal: 24,
  },
  lab: {
    alignSelf: 'flex-end',
    paddingHorizontal: 14,
    paddingVertical: 7,
    borderRadius: 999,
    backgroundColor: QUIET,
  },
  labText: {
    fontSize: 13,
    fontWeight: '600',
    color: '#6a6a75',
    letterSpacing: 0.3,
  },

  // The reference stack: number, then the buttons, 32 apart.
  stack: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 32,
  },
  number: {
    fontSize: 84,
    fontWeight: '700',
    color: INK,
  },
  row: {
    flexDirection: 'row',
    gap: 16,
  },
  round: {
    width: 62,
    height: 62,
    borderRadius: 31,
    backgroundColor: QUIET,
    alignItems: 'center',
    justifyContent: 'center',
  },
  roundText: {
    fontSize: 28,
    fontWeight: '500',
    color: INK,
    lineHeight: 32,
  },

  play: {
    paddingHorizontal: 28,
    paddingVertical: 14,
    borderRadius: 999,
    backgroundColor: INK,
  },
  playText: {
    fontSize: 16,
    fontWeight: '600',
    color: '#fff',
  },

  pressed: {
    opacity: 0.6,
  },
});
