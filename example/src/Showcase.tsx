import { useCallback, useEffect, useRef, useState } from 'react';
import { Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import { NumericText } from 'react-native-numeric-text';
import {
  SEQUENCE,
  SEQUENCE_DURATION,
  scheduleOnFrames,
  useSequencePlayer,
} from './sequence';

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

/**
 * The third preset: the STRUCTURAL shrink, sync-marked like the other two.
 *
 * It is not an exotic case — it is what the "−" button does the first time anyone presses it, since
 * the screen starts at 1,000 and steps by 123. The digit count drops 4 -> 3, which puts the whole
 * change on the renderer's structural path (every column a birth or a death rather than a roll), and
 * that path has never been measured against the reference on a shrink of this shape: the fits behind
 * it come from 1 -> 9,999 and 1,000 -> 1, both far more extreme.
 *
 * Same marker trick as the two above, so the onset is read and not inferred.
 */
const SHRINK_FROM = 1000;
const SHRINK_TO = 877;
const GROW_FROM = 1000;
const GROW_TO = 10000;

/**
 * The two changes a real user reports on, neither of which any preset covered.
 *
 * `9,950 → 10,123` is the CARRY: the digit count grows 4 → 5, the separator moves one place left,
 * and every column that stays is also rolling. It is the case where a structural birth, a
 * horizontal reflow and three simultaneous rolls all run in the same transaction — each measured
 * alone before, never together.
 *
 * `1,000 → 999` is its mirror and the smallest possible shrink: one step of −1 takes four digits to
 * three, deletes the separator, and every surviving column rolls 0 → 9, i.e. the roll's direction
 * disagrees with the value's for all three of them. The shrink preset already in the file
 * (1,000 → 877) shares the structure but not this: there the units roll 0 → 7 with the carry, here
 * every column does the same thing at once, which is what makes the hole (if there is one) obvious.
 */
const CARRY_FROM = 9950;
const CARRY_TO = 10123;
const NINES_FROM = 1000;
const NINES_TO = 999;

/**
 * A HUMANISED tap cadence — the fourth regime, and the one the complaints are actually made in.
 *
 * `taps ×8 · 220ms` is a metronome, and a metronome is not what a thumb does: a person presses in
 * bursts, two or three quick ones and then a pause, and the renderer's crowding gates blend on
 * exactly that spacing. A fixed 220 ms therefore samples ONE point of the gate; this samples the
 * range a hand actually produces, 200-650 ms.
 *
 * The gaps are a literal table rather than a seeded generator on purpose: it has to replay
 * identically on both platforms, and two JS engines agreeing on `Math.random` is not something to
 * rely on. Read it as "quick-quick-pause", three times over, with the pauses at the two lengths
 * where the offset gate ([offsetCrowdMs] = 260 ms on Android) has just expired and where it is long
 * gone.
 */
const HUMAN_GAPS = [220, 400, 210, 650, 240, 380, 200, 640, 230, 410, 200];

/**
 * The second preset: a CONTINUOUS roll, scripted.
 *
 * The single change above measures the isolated case. It says nothing about the one a viewer sees
 * most — holding +/− while the number runs — and that case has its own code path in the renderer
 * (every rate is blended toward a "spam" value as changes crowd), so it can regress while the
 * isolated case measures perfectly. It did.
 *
 * Tapping the button by hand cannot measure it: the taps land 100-200 ms apart, the spacing varies
 * per run, and it differs between a simulator and an emulator — so iOS and Android would be
 * answering different questions. Here the ticks are on a fixed clock, the same on both platforms,
 * and the sync marker dates the first one exactly like the preset above.
 *
 * 30 ms is what a real press-and-hold repeats at, and 14 ticks of +123 keeps the whole run at four
 * digits, so it stays on the roll path rather than the structural one.
 */
const HOLD_FROM = 1000;
const HOLD_STEP_MS = 30;
const HOLD_TICKS = 14;

/**
 * The controlled roll: one unit at a time over a short fixed range. The matching reverse preset
 * below makes the direction comparison explicit while keeping both runs quick to record.
 */
const CONTROLLED_ROLL_FROM = 1000;
const CONTROLLED_ROLL_TO = 1010;
const CONTROLLED_ROLL_STEP_MS = 30;
const CONTROLLED_ROLL_TICKS = CONTROLLED_ROLL_TO - CONTROLLED_ROLL_FROM;

/**
 * The third cadence: TAPS, not a hold — the one a person actually makes.
 *
 * 30 ms is a press-and-hold and 1.2 s is a single change, and both are measured. Between them sits
 * the case every complaint has actually been about: someone pressing the button as fast as a thumb
 * comfortably goes. It is its own regime because the renderer blends every rate by how long ago the
 * last change came in, and the blend is finished by ~180 ms — so a 220 ms tap is treated exactly
 * like a change from rest, and each digit is asked to start a whole roll it will never finish
 * before the next tap.
 *
 * Scripted rather than tapped, for the same reason as the hold above: a hand cannot repeat a
 * cadence closely enough for two platforms to be answering the same question.
 */
/**
 * Two behaviours that no preset covered, both about what happens when a change arrives before the
 * previous one has finished.
 *
 * REVERSAL: a roll that is interrupted, halfway through, by a roll the other way. The engine's
 * central claim is that a change moves where a column is going and never where it is, so a
 * reversal should bend the motion rather than restart it. Nothing tested that.
 *
 * ALTERNATION: the same digit flipped back and forth faster than it can settle. The reference
 * parks the strip BETWEEN two stops and the parking point depends on the cadence — press quickly
 * and it sits nearer the incoming digit, slower and it falls back toward the outgoing one. Two
 * intervals, because one cannot show a dependence on the interval.
 */
const ALTERNATE_TICKS = 20;
const ALTERNATE_FAST_MS = 60;
const ALTERNATE_SLOW_MS = 120;
/**
 * A third cadence, to test whether the reference's alternation blend is a stack of overlapping
 * transitions. At 60 ms roughly six of a ~350 ms transition are alive at once, at 120 ms three, at
 * 240 ms about one and a half — so the hypothesis predicts the blend climbs back toward what a
 * single crossing measures, while a model holding one persistent state per column stays flat.
 */
const ALTERNATE_SLOWEST_MS = 240;

const TAP_STEP_MS = 220;
const TAP_TICKS = 8;
/**
 * …and the same run backwards, from where the forward one ends.
 *
 * Same eight changes, same columns, same digits, opposite sign — so anything that differs between
 * the two is the renderer treating up and down differently at a tap cadence, which is the question
 * that keeps being asked. Both stay at four digits, so neither touches the structural path.
 */
const TAP_DOWN_FROM = HOLD_FROM + TAP_TICKS * STEP;

const PLAY_LABEL = `Play · ${Math.round(SEQUENCE_DURATION / 1000)}s`;

type Props = { onOpenLab: () => void };

export function Showcase({ onOpenLab }: Props) {
  const [value, setValue] = useState(START);
  const [playing, setPlaying] = useState(false);
  // Android only, and 'auto' means "leave whatever the frame recorder's marker file decided", so a
  // measuring round is unaffected unless this is touched deliberately.
  const [engine, setEngine] = useState<'auto' | 'drum' | 'stack'>('stack');
  const [syncing, setSyncing] = useState(false);
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);
  /** Cancels the in-flight frame-clock run, if any. See scheduleOnFrames. */
  const cancelFrames = useRef<(() => void) | null>(null);

  useEffect(
    () => () => {
      timers.current.forEach(clearTimeout);
    },
    []
  );

  const runStep = useCallback((from: number, to: number) => {
    timers.current.forEach(clearTimeout);
    timers.current = [];
    setSyncing(false);
    setValue(from);
    timers.current.push(
      setTimeout(() => {
        // One commit: the value the renderer must animate, and the marker that dates it.
        setValue(to);
        setSyncing(true);
        timers.current.push(setTimeout(() => setSyncing(false), SYNC_FLASH_MS));
      }, PRESET_SETTLE_MS)
    );
  }, []);

  const runPreset = useCallback(
    () => runStep(PRESET_FROM, PRESET_TO),
    [runStep]
  );
  // The measurement preset run BACKWARDS. Same three columns, same digits, opposite direction — so
  // any difference between this and `runPreset` is the renderer treating up and down differently,
  // which by construction it should not.
  const runMirror = useCallback(
    () => runStep(PRESET_TO, PRESET_FROM),
    [runStep]
  );
  const runShrink = useCallback(
    () => runStep(SHRINK_FROM, SHRINK_TO),
    [runStep]
  );
  const runGrowth = useCallback(() => runStep(GROW_FROM, GROW_TO), [runStep]);
  const runCarry = useCallback(() => runStep(CARRY_FROM, CARRY_TO), [runStep]);
  const runNines = useCallback(() => runStep(NINES_FROM, NINES_TO), [runStep]);

  const runTicks = useCallback(
    (
      stepMs: number,
      ticks: number,
      from = HOLD_FROM,
      sign = 1,
      delta = STEP
    ) => {
      timers.current.forEach(clearTimeout);
      timers.current = [];
      cancelFrames.current?.();
      setSyncing(false);
      setValue(from);
      timers.current.push(
        setTimeout(() => {
          // Same one-commit trick as runStep: the first tick of the roll and the marker that dates
          // it land together, so the first dark frame IS the frame the roll started on.
          setValue(from + sign * delta);
          setSyncing(true);
          timers.current.push(
            setTimeout(() => setSyncing(false), SYNC_FLASH_MS)
          );
          // On the frame clock, not on timers. A batch of near-simultaneous setTimeouts is
          // coalesced by the JS timer queue, and differently per platform: this preset was
          // measured firing every 31 ms on iOS and every 113 ms on Android, which made the two
          // platforms' rolls impossible to compare.
          const entries = [];
          for (let i = 2; i <= ticks; i += 1) {
            const to = from + sign * i * delta;
            entries.push({ at: (i - 1) * stepMs, run: () => setValue(to) });
          }
          cancelFrames.current = scheduleOnFrames(entries);
        }, PRESET_SETTLE_MS)
      );
    },
    []
  );

  const runHold = useCallback(
    () => runTicks(HOLD_STEP_MS, HOLD_TICKS),
    [runTicks]
  );
  // The same hold, downward. The roll's SHAPE is directional in the reference — during an increment
  // its rolling column hangs below the line and during a decrement above it — and a preset that
  // only goes one way cannot show that, so it went unmeasured while three sessions tuned the roll.
  const runHoldDown = useCallback(
    () => runTicks(HOLD_STEP_MS, HOLD_TICKS, HOLD_FROM + HOLD_TICKS * STEP, -1),
    [runTicks]
  );
  const runControlledRoll = useCallback(
    () =>
      runTicks(
        CONTROLLED_ROLL_STEP_MS,
        CONTROLLED_ROLL_TICKS,
        CONTROLLED_ROLL_FROM,
        1,
        1
      ),
    [runTicks]
  );
  const runControlledRollDown = useCallback(
    () =>
      runTicks(
        CONTROLLED_ROLL_STEP_MS,
        CONTROLLED_ROLL_TICKS,
        CONTROLLED_ROLL_TO,
        -1,
        1
      ),
    [runTicks]
  );

  /**
   * The humanised run. Same one-commit sync marker as everything else: the first tap and the black
   * bar land in one React commit, so the first dark frame IS the frame the run started on.
   */
  const runHuman = useCallback(() => {
    timers.current.forEach(clearTimeout);
    timers.current = [];
    cancelFrames.current?.();
    setSyncing(false);
    setValue(HOLD_FROM);
    timers.current.push(
      setTimeout(() => {
        setValue(HOLD_FROM + STEP);
        setSyncing(true);
        timers.current.push(setTimeout(() => setSyncing(false), SYNC_FLASH_MS));
        let at = 0;
        const entries = HUMAN_GAPS.map((gap, i) => {
          at += gap;
          const to = HOLD_FROM + (i + 2) * STEP;
          return { at, run: () => setValue(to) };
        });
        cancelFrames.current = scheduleOnFrames(entries);
      }, PRESET_SETTLE_MS)
    );
  }, []);
  /** A roll up, reversed into a roll down halfway. */
  const runReversal = useCallback(() => {
    timers.current.forEach(clearTimeout);
    timers.current = [];
    cancelFrames.current?.();
    setSyncing(false);
    setValue(HOLD_FROM);
    timers.current.push(
      setTimeout(() => {
        setValue(HOLD_FROM + STEP);
        setSyncing(true);
        timers.current.push(setTimeout(() => setSyncing(false), SYNC_FLASH_MS));
        const half = Math.floor(HOLD_TICKS / 2);
        const entries = [];
        for (let i = 2; i <= HOLD_TICKS; i += 1) {
          // Up to the halfway point, then back down through the same values.
          const step = i <= half ? i : half - (i - half);
          entries.push({
            at: (i - 1) * HOLD_STEP_MS,
            run: () => setValue(HOLD_FROM + step * STEP),
          });
        }
        cancelFrames.current = scheduleOnFrames(entries);
      }, PRESET_SETTLE_MS)
    );
  }, []);

  /** One digit flipped back and forth faster than it settles. */
  const runAlternate = useCallback((stepMs: number) => {
    timers.current.forEach(clearTimeout);
    timers.current = [];
    cancelFrames.current?.();
    setSyncing(false);
    setValue(HOLD_FROM);
    timers.current.push(
      setTimeout(() => {
        setValue(HOLD_FROM + 1);
        setSyncing(true);
        timers.current.push(setTimeout(() => setSyncing(false), SYNC_FLASH_MS));
        const entries = [];
        for (let i = 2; i <= ALTERNATE_TICKS; i += 1) {
          const to = HOLD_FROM + (i % 2 === 0 ? 0 : 1);
          entries.push({ at: (i - 1) * stepMs, run: () => setValue(to) });
        }
        cancelFrames.current = scheduleOnFrames(entries);
      }, PRESET_SETTLE_MS)
    );
  }, []);
  const runAlternateFast = useCallback(
    () => runAlternate(ALTERNATE_FAST_MS),
    [runAlternate]
  );
  const runAlternateSlow = useCallback(
    () => runAlternate(ALTERNATE_SLOW_MS),
    [runAlternate]
  );
  const runAlternateSlowest = useCallback(
    () => runAlternate(ALTERNATE_SLOWEST_MS),
    [runAlternate]
  );

  const runTaps = useCallback(
    () => runTicks(TAP_STEP_MS, TAP_TICKS),
    [runTicks]
  );
  const runTapsDown = useCallback(
    () => runTicks(TAP_STEP_MS, TAP_TICKS, TAP_DOWN_FROM, -1),
    [runTicks]
  );

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

      {/* The top row keeps the Lab pill's own height, so adding the engine switch beside it does
          NOT move anything below. That matters more than it looks: `.agent/tools/round.sh` taps
          FIXED SCREEN COORDINATES for the preset buttons, and a layout shift here would silently
          put a whole round of measurements on the wrong transition. */}
      <View style={styles.topRow}>
        {Platform.OS === 'android' ? (
          <Pressable
            style={styles.lab}
            onPress={() =>
              setEngine((e) =>
                e === 'auto' ? 'drum' : e === 'drum' ? 'stack' : 'auto'
              )
            }
            accessibilityRole="button"
            accessibilityLabel={`Motore: ${engine}`}
          >
            <Text style={styles.labText}>
              {engine === 'auto' ? 'motore: auto' : `motore: ${engine}`}
            </Text>
          </Pressable>
        ) : (
          <View />
        )}
        <Pressable
          style={styles.lab}
          onPress={onOpenLab}
          accessibilityRole="button"
        >
          <Text style={styles.labText}>Lab</Text>
        </Pressable>
      </View>

      <View style={styles.stack}>
        <NumericText
          animationDuration={320}
          value={value}
          style={styles.number}
          debugEngine={engine}
        />

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
        <View style={styles.presetRow}>
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
            style={({ pressed }) => [styles.preset, pressed && styles.pressed]}
            onPress={runMirror}
            disabled={playing}
            accessibilityRole="button"
          >
            <Text style={styles.presetText}>
              {PRESET_TO.toLocaleString('en-US')} →{' '}
              {PRESET_FROM.toLocaleString('en-US')}
            </Text>
          </Pressable>

          <Pressable
            style={({ pressed }) => [styles.preset, pressed && styles.pressed]}
            onPress={runShrink}
            disabled={playing}
            accessibilityRole="button"
          >
            <Text style={styles.presetText}>
              {SHRINK_FROM.toLocaleString('en-US')} →{' '}
              {SHRINK_TO.toLocaleString('en-US')}
            </Text>
          </Pressable>

          <Pressable
            style={({ pressed }) => [styles.preset, pressed && styles.pressed]}
            onPress={runGrowth}
            disabled={playing}
            accessibilityRole="button"
          >
            <Text style={styles.presetText}>
              {GROW_FROM.toLocaleString('en-US')} →{' '}
              {GROW_TO.toLocaleString('en-US')}
            </Text>
          </Pressable>

          <Pressable
            style={({ pressed }) => [styles.preset, pressed && styles.pressed]}
            onPress={runCarry}
            disabled={playing}
            accessibilityRole="button"
          >
            <Text style={styles.presetText}>
              {CARRY_FROM.toLocaleString('en-US')} →{' '}
              {CARRY_TO.toLocaleString('en-US')}
            </Text>
          </Pressable>

          <Pressable
            style={({ pressed }) => [styles.preset, pressed && styles.pressed]}
            onPress={runNines}
            disabled={playing}
            accessibilityRole="button"
          >
            <Text style={styles.presetText}>
              {NINES_FROM.toLocaleString('en-US')} →{' '}
              {NINES_TO.toLocaleString('en-US')}
            </Text>
          </Pressable>

          <Pressable
            style={({ pressed }) => [styles.preset, pressed && styles.pressed]}
            onPress={runHuman}
            disabled={playing}
            accessibilityRole="button"
            accessibilityLabel="Humanised taps"
          >
            <Text style={styles.presetText}>
              human ×{HUMAN_GAPS.length + 1}
            </Text>
          </Pressable>

          <Pressable
            style={({ pressed }) => [styles.preset, pressed && styles.pressed]}
            onPress={runReversal}
            disabled={playing}
            accessibilityRole="button"
            accessibilityLabel="Reversed roll"
          >
            <Text style={styles.presetText}>
              roll ⇄ ×{HOLD_TICKS} · {HOLD_STEP_MS}ms
            </Text>
          </Pressable>

          <Pressable
            style={({ pressed }) => [styles.preset, pressed && styles.pressed]}
            onPress={runAlternateFast}
            disabled={playing}
            accessibilityRole="button"
            accessibilityLabel="Fast alternation"
          >
            <Text style={styles.presetText}>
              alterna ×{ALTERNATE_TICKS} · {ALTERNATE_FAST_MS}ms
            </Text>
          </Pressable>

          <Pressable
            style={({ pressed }) => [styles.preset, pressed && styles.pressed]}
            onPress={runAlternateSlow}
            disabled={playing}
            accessibilityRole="button"
            accessibilityLabel="Slow alternation"
          >
            <Text style={styles.presetText}>
              alterna ×{ALTERNATE_TICKS} · {ALTERNATE_SLOW_MS}ms
            </Text>
          </Pressable>

          <Pressable
            style={({ pressed }) => [styles.preset, pressed && styles.pressed]}
            onPress={runAlternateSlowest}
            disabled={playing}
            accessibilityRole="button"
            accessibilityLabel="Slowest alternation"
          >
            <Text style={styles.presetText}>
              alterna ×{ALTERNATE_TICKS} · {ALTERNATE_SLOWEST_MS}ms
            </Text>
          </Pressable>

          <Pressable
            style={({ pressed }) => [styles.preset, pressed && styles.pressed]}
            onPress={runHold}
            disabled={playing}
            accessibilityRole="button"
            accessibilityLabel="Continuous roll"
          >
            <Text style={styles.presetText}>
              roll + ×{HOLD_TICKS} · {HOLD_STEP_MS}ms
            </Text>
          </Pressable>

          <Pressable
            style={({ pressed }) => [styles.preset, pressed && styles.pressed]}
            onPress={runHoldDown}
            disabled={playing}
            accessibilityRole="button"
            accessibilityLabel="Continuous roll down"
          >
            <Text style={styles.presetText}>
              roll − ×{HOLD_TICKS} · {HOLD_STEP_MS}ms
            </Text>
          </Pressable>

          <Pressable
            style={({ pressed }) => [styles.preset, pressed && styles.pressed]}
            onPress={runControlledRoll}
            disabled={playing}
            accessibilityRole="button"
            accessibilityLabel="Controlled continuous roll from 1,000 to 2,350"
          >
            <Text style={styles.presetText}>
              {CONTROLLED_ROLL_FROM.toLocaleString('en-US')} →{' '}
              {CONTROLLED_ROLL_TO.toLocaleString('en-US')} ·{' '}
              {CONTROLLED_ROLL_STEP_MS}ms
            </Text>
          </Pressable>

          <Pressable
            style={({ pressed }) => [styles.preset, pressed && styles.pressed]}
            onPress={runControlledRollDown}
            disabled={playing}
            accessibilityRole="button"
            accessibilityLabel="Controlled continuous roll from 1,010 to 1,000"
          >
            <Text style={styles.presetText}>
              {CONTROLLED_ROLL_TO.toLocaleString('en-US')} →{' '}
              {CONTROLLED_ROLL_FROM.toLocaleString('en-US')} ·{' '}
              {CONTROLLED_ROLL_STEP_MS}ms
            </Text>
          </Pressable>

          <Pressable
            style={({ pressed }) => [styles.preset, pressed && styles.pressed]}
            onPress={runTaps}
            disabled={playing}
            accessibilityRole="button"
            accessibilityLabel="Human taps"
          >
            <Text style={styles.presetText}>
              taps + ×{TAP_TICKS} · {TAP_STEP_MS}ms
            </Text>
          </Pressable>

          <Pressable
            style={({ pressed }) => [styles.preset, pressed && styles.pressed]}
            onPress={runTapsDown}
            disabled={playing}
            accessibilityRole="button"
            accessibilityLabel="Human taps down"
          >
            <Text style={styles.presetText}>
              taps − ×{TAP_TICKS} · {TAP_STEP_MS}ms
            </Text>
          </Pressable>
        </View>

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
  topRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  lab: {
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

  // FIXED height, contents anchored to the bottom. The buttons are measurement scaffolding and
  // they get added to; in flow, every addition steals height from [stack] and moves the number up
  // (67 px on Android, 72 on iOS, the last time it happened) — which silently re-crops every
  // fixed-window probe and reads as a large clean regression that is not real. Reserving the space
  // once means the number's position is a property of the screen, not of how many presets exist.
  actions: {
    height: 320,
    alignItems: 'center',
    justifyContent: 'flex-end',
    gap: 12,
  },
  presetRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'center',
    gap: 8,
  },
  preset: {
    paddingHorizontal: 16,
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
