import { useCallback, useEffect, useRef, useState } from 'react';
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

/**
 * The measurement preset, and the sync marker that makes it measurable.
 *
 * Every parity comparison so far had to GUESS which video frame a transition started on, by looking
 * for motion — and inside a run of changes that reliably finds the tail of the previous one instead
 * of the start of this one, which silently invalidated several measurements.
 *
 * So the preset ships its own marker. Pressing the button parks the value at PRESET_FROM, waits for
 * it to settle, then sets PRESET_TO and turns the sync bar black **in the same React commit** — the
 * native view receives the new value on the very frame the bar goes dark. An analysis script finds
 * the first dark frame of the bar and has the onset exactly, on either platform, with nothing
 * inferred.
 *
 * 1,242 -> 1,160 changes three columns without changing the digit count (so it stays on the plain
 * roll path, not the structural one), and its tens digit goes UP (4 -> 6) while the value goes down
 * — a mixed case a pure decrement would not cover.
 */
const PRESET_FROM = 1242;
const PRESET_TO = 1160;
const PRESET_SETTLE_MS = 1200;
const SYNC_FLASH_MS = 400;

const PLAY_LABEL = `Play · ${Math.round(SEQUENCE_DURATION / 1000)}s`;

type Props = { onOpenLab: () => void };

export function Showcase({ onOpenLab }: Props) {
  const [value, setValue] = useState(START);
  const [playing, setPlaying] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);

  useEffect(
    () => () => {
      timers.current.forEach(clearTimeout);
    },
    []
  );

  const runPreset = useCallback(() => {
    timers.current.forEach(clearTimeout);
    timers.current = [];
    setSyncing(false);
    setValue(PRESET_FROM);
    timers.current.push(
      setTimeout(() => {
        // One commit: the value the renderer must animate, and the marker that dates it.
        setValue(PRESET_TO);
        setSyncing(true);
        timers.current.push(setTimeout(() => setSyncing(false), SYNC_FLASH_MS));
      }, PRESET_SETTLE_MS)
    );
  }, []);

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
      {/* Sync marker. Fixed position, clear of the status bar above and the number below, so a
          measuring band for either never overlaps it. */}
      <View style={[styles.sync, syncing && styles.syncOn]} />

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

      <View style={styles.actions}>
        <Pressable
          style={({ pressed }) => [styles.preset, pressed && styles.pressed]}
          onPress={runPreset}
          disabled={playing}
          accessibilityRole="button"
        >
          <Text style={styles.presetText}>
            {PRESET_FROM.toLocaleString('en-US')} →{' '}
            {PRESET_TO.toLocaleString('en-US')}
          </Text>
        </Pressable>

        <Pressable
          style={({ pressed }) => [styles.play, pressed && styles.pressed]}
          onPress={togglePlay}
          accessibilityRole="button"
        >
          <Text style={styles.playText}>{playing ? 'Stop' : PLAY_LABEL}</Text>
        </Pressable>
      </View>
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

  // Absolute so it cannot shift the layout when it toggles — a moved number would be a second,
  // invisible variable in every frame comparison.
  sync: {
    position: 'absolute',
    top: 96,
    left: 0,
    right: 0,
    height: 30,
    backgroundColor: 'transparent',
  },
  syncOn: {
    backgroundColor: '#000',
  },

  actions: {
    alignItems: 'center',
    gap: 12,
  },
  preset: {
    paddingHorizontal: 22,
    paddingVertical: 11,
    borderRadius: 999,
    backgroundColor: QUIET,
  },
  presetText: {
    fontSize: 15,
    fontWeight: '600',
    color: '#4a4a55',
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
