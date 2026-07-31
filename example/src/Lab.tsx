import { useState, useCallback, useRef, useEffect } from 'react';
import { View, Text, Pressable, ScrollView, StyleSheet } from 'react-native';
import { NumericText } from 'react-native-numeric-text';
import { AnimatedNumber } from './reference/AnimatedNumber';
import {
  SEQUENCE_START,
  SEQUENCE_DURATION,
  useSequencePlayer,
} from './sequence';

type Preset = { label: string; from: number; to: number };

const PRESETS: Preset[] = [
  { label: '2,576 → 2,577', from: 2576, to: 2577 },
  { label: '2,577 → 2,576', from: 2577, to: 2576 },
  { label: '9 → 10', from: 9, to: 10 },
  { label: '99 → 100', from: 99, to: 100 },
  { label: '999 → 1,000', from: 999, to: 1000 },
  { label: '1,000 → 999', from: 1000, to: 999 },
  { label: '1.9 → 2.0', from: 1.9, to: 2.0 },
  { label: '-1 → 0', from: -1, to: 0 },
  { label: '0 → -1', from: 0, to: -1 },
  { label: '1 → 9,999', from: 1, to: 9999 },
];

type Impl = 'reference' | 'library';
type ReduceMode = 'system' | 'always' | 'never';

type LabProps = { onOpenShowcase: () => void };

export function Lab({ onOpenShowcase }: LabProps) {
  const [value, setValue] = useState(SEQUENCE_START);
  const [impl, setImpl] = useState<Impl>('library');
  const [reduceMotion, setReduceMotion] = useState<ReduceMode>('system');
  const [phase, setPhase] = useState('idle');
  const [playing, setPlaying] = useState(false);

  const onDone = useCallback(() => {
    setPlaying(false);
    setPhase('done');
  }, []);
  const { play, stop } = useSequencePlayer(setValue, setPhase, onDone);

  const playSequence = useCallback(() => {
    setPlaying(true);
    play();
  }, [play]);
  const stopSequence = useCallback(() => {
    stop();
    setPlaying(false);
    setPhase('idle');
  }, [stop]);

  // Press-and-hold auto-repeat, so +/- can reproduce the fast-spam (rapid roll) behaviour.
  const holdRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const startHold = useCallback((dir: number) => {
    setValue((v) => v + dir);
    if (holdRef.current) clearInterval(holdRef.current);
    holdRef.current = setInterval(() => setValue((v) => v + dir), 30);
  }, []);
  const stopHold = useCallback(() => {
    if (holdRef.current) {
      clearInterval(holdRef.current);
      holdRef.current = null;
    }
  }, []);
  useEffect(() => () => stopHold(), [stopHold]);

  const applyPreset = useCallback((p: Preset) => {
    setValue(p.from);
    setTimeout(() => setValue(p.to), 400);
  }, []);

  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.content}>
      <Pressable style={styles.backBtn} onPress={onOpenShowcase}>
        <Text style={styles.backText}>← Demo</Text>
      </Pressable>

      {/* Implementation under test — the SAME `value` drives both, so the same scripted
          sequence produces comparable recordings on iOS (reference) and Android (library). */}
      <View style={styles.segment}>
        {(['reference', 'library'] as const).map((m) => (
          <Pressable
            key={m}
            style={[styles.segmentBtn, impl === m && styles.segmentBtnActive]}
            onPress={() => setImpl(m)}
          >
            <Text
              style={[
                styles.segmentText,
                impl === m && styles.segmentTextActive,
              ]}
            >
              {m === 'reference' ? 'Reference (SwiftUI)' : 'This library'}
            </Text>
          </Pressable>
        ))}
      </View>

      <View style={styles.numberContainer}>
        {impl === 'reference' ? (
          <AnimatedNumber value={value} size={84} />
        ) : (
          <NumericText
            value={value}
            locale="en-US"
            direction="automatic"
            animationDuration={350}
            reduceMotion={reduceMotion}
            useGrouping
            minimumFractionDigits={0}
            maximumFractionDigits={3}
            style={styles.number}
            debugTransitionStrategy="per_glyph"
          />
        )}
      </View>

      {/* Scripted sequence — press once, record, get an identical run to compare across platforms. */}
      <View style={styles.playRow}>
        <Pressable
          style={[styles.playBtn, playing && styles.playBtnActive]}
          onPress={playing ? stopSequence : playSequence}
        >
          <Text style={styles.playText}>
            {playing
              ? '■ Stop sequence'
              : `▶ Play sequence (${Math.round(SEQUENCE_DURATION / 1000)}s)`}
          </Text>
        </Pressable>
      </View>
      <Text
        style={styles.phase}
      >{`phase: ${phase}   ·   value: ${value.toLocaleString('en-US')}`}</Text>

      <View style={styles.buttonsRow}>
        <Pressable
          style={styles.circleBtn}
          onPressIn={() => startHold(-1)}
          onPressOut={stopHold}
          accessibilityLabel="Decrement (hold to repeat)"
        >
          <Text style={styles.circleBtnText}>−</Text>
        </Pressable>
        <Pressable
          style={styles.circleBtn}
          onPressIn={() => startHold(1)}
          onPressOut={stopHold}
          accessibilityLabel="Increment (hold to repeat)"
        >
          <Text style={styles.circleBtnText}>+</Text>
        </Pressable>
      </View>

      <View style={styles.controls}>
        <Text style={styles.sectionTitle}>Presets</Text>
        <View style={styles.presetsGrid}>
          {PRESETS.map((p) => (
            <Pressable
              key={p.label}
              style={styles.presetBtn}
              onPress={() => applyPreset(p)}
            >
              <Text style={styles.presetText}>{p.label}</Text>
            </Pressable>
          ))}
        </View>

        <Text style={styles.sectionTitle}>Reduce Motion (library only)</Text>
        <View style={styles.row}>
          {(['system', 'always', 'never'] as const).map((mode) => (
            <Pressable
              key={mode}
              style={[
                styles.toggleBtn,
                reduceMotion === mode && styles.toggleBtnActive,
              ]}
              onPress={() => setReduceMotion(mode)}
            >
              <Text
                style={[
                  styles.toggleText,
                  reduceMotion === mode && styles.toggleTextActive,
                ]}
              >
                {mode}
              </Text>
            </Pressable>
          ))}
        </View>

        <Text style={styles.sectionTitle}>Diagnostics</Text>
        <Text style={styles.diag}>
          {`impl: ${impl}
phase: ${phase}
value: ${value}
reduceMotion: ${reduceMotion}`}
        </Text>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: '#f5f5f7',
  },
  content: {
    alignItems: 'center',
    paddingTop: 100,
    paddingBottom: 60,
    paddingHorizontal: 20,
  },
  backBtn: {
    alignSelf: 'flex-start',
    marginBottom: 16,
    paddingHorizontal: 14,
    paddingVertical: 7,
    borderRadius: 999,
    backgroundColor: '#e8e8ed',
  },
  backText: {
    fontSize: 13,
    fontWeight: '600',
    color: '#6a6a75',
  },
  segment: {
    flexDirection: 'row',
    backgroundColor: '#e8e8ed',
    borderRadius: 10,
    padding: 3,
    marginBottom: 24,
  },
  segmentBtn: {
    paddingHorizontal: 18,
    paddingVertical: 8,
    borderRadius: 8,
  },
  segmentBtnActive: {
    backgroundColor: '#fff',
  },
  segmentText: {
    fontSize: 14,
    fontWeight: '600',
    color: '#666',
  },
  segmentTextActive: {
    color: '#000',
  },
  numberContainer: {
    alignItems: 'center',
    justifyContent: 'center',
    height: 120,
    marginBottom: 12,
  },
  number: {
    fontSize: 84,
    fontWeight: '700',
    color: '#000',
  },
  playRow: {
    marginBottom: 8,
  },
  playBtn: {
    paddingHorizontal: 22,
    paddingVertical: 12,
    borderRadius: 10,
    backgroundColor: '#007aff',
  },
  playBtnActive: {
    backgroundColor: '#ff3b30',
  },
  playText: {
    fontSize: 15,
    fontWeight: '700',
    color: '#fff',
  },
  phase: {
    fontSize: 12,
    color: '#999',
    fontFamily: 'monospace',
    marginBottom: 24,
  },
  buttonsRow: {
    flexDirection: 'row',
    gap: 24,
    marginBottom: 32,
  },
  circleBtn: {
    width: 56,
    height: 56,
    borderRadius: 28,
    backgroundColor: '#e8e8ed',
    alignItems: 'center',
    justifyContent: 'center',
  },
  circleBtnText: {
    fontSize: 28,
    color: '#000',
    lineHeight: 32,
  },
  controls: {
    width: '100%',
    maxWidth: 400,
  },
  sectionTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#666',
    marginTop: 20,
    marginBottom: 10,
    textTransform: 'uppercase',
    letterSpacing: 1,
  },
  presetsGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  presetBtn: {
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 8,
    backgroundColor: '#e8e8ed',
  },
  presetText: {
    fontSize: 13,
    color: '#333',
  },
  row: {
    marginTop: 8,
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 4,
  },
  toggleBtn: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 8,
    backgroundColor: '#e8e8ed',
    marginRight: 4,
    marginBottom: 4,
  },
  toggleBtnActive: {
    backgroundColor: '#007aff',
  },
  toggleText: {
    fontSize: 13,
    color: '#333',
  },
  toggleTextActive: {
    color: '#fff',
  },
  diag: {
    fontSize: 12,
    color: '#999',
    fontFamily: 'monospace',
    marginTop: 4,
  },
});
