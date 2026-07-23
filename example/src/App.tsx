import { useState, useCallback } from 'react';
import { View, Text, Pressable, ScrollView, StyleSheet } from 'react-native';
import { NumericText } from 'react-native-numeric-text';

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

type ReduceMode = 'system' | 'always' | 'never';
type Strategy = 'changed_run' | 'whole_run' | 'per_glyph';

const MANUAL_PROGRESS_VALUES = [0.0, 0.1, 0.25, 0.4, 0.5, 0.65, 0.75, 0.9, 1.0];

export default function App() {
  const [value, setValue] = useState(2576);
  const [reduceMotion, setReduceMotion] = useState<ReduceMode>('system');
  const [strategy, setStrategy] = useState<Strategy>('per_glyph');
  const [manualProgress, setManualProgress] = useState<number | undefined>(
    undefined
  );

  const update = useCallback((next: number) => {
    setValue(next);
  }, []);

  const runStress = useCallback(() => {
    setValue(100);
    const timer1 = setTimeout(() => setValue(101), 200);
    const timer2 = setTimeout(() => setValue(102), 400);
    const timer3 = setTimeout(() => setValue(156), 600);
    return () => {
      clearTimeout(timer1);
      clearTimeout(timer2);
      clearTimeout(timer3);
    };
  }, []);

  const applyPreset = useCallback((p: Preset) => {
    setValue(p.from);
    setTimeout(() => setValue(p.to), 400);
  }, []);

  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.content}>
      <View style={styles.numberContainer}>
        <NumericText
          value={value}
          locale="en-US"
          direction="automatic"
          animationDuration={220}
          reduceMotion={reduceMotion}
          useGrouping
          minimumFractionDigits={0}
          maximumFractionDigits={3}
          style={styles.number}
          debugTransitionStrategy={strategy}
          debugManualProgress={manualProgress}
        />
      </View>

      <View style={styles.buttonsRow}>
        <Pressable
          style={styles.circleBtn}
          onPress={() => update(value - 1)}
          accessibilityLabel="Decrement"
        >
          <Text style={styles.circleBtnText}>−</Text>
        </Pressable>
        <Pressable
          style={styles.circleBtn}
          onPress={() => update(value + 1)}
          accessibilityLabel="Increment"
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

        <View style={styles.row}>
          <Pressable style={styles.actionBtn} onPress={runStress}>
            <Text style={styles.actionText}>Rapid: 100→101→102→156</Text>
          </Pressable>
        </View>

        <Text style={styles.sectionTitle}>Reduce Motion</Text>
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

        <Text style={styles.sectionTitle}>Transition Strategy</Text>
        <View style={styles.row}>
          {(['changed_run', 'whole_run', 'per_glyph'] as const).map((s) => (
            <Pressable
              key={s}
              style={[
                styles.toggleBtn,
                strategy === s && styles.toggleBtnActive,
              ]}
              onPress={() => setStrategy(s)}
            >
              <Text
                style={[
                  styles.toggleText,
                  strategy === s && styles.toggleTextActive,
                ]}
              >
                {s === 'changed_run'
                  ? 'CHANGED_RUN'
                  : s === 'whole_run'
                    ? 'WHOLE_RUN'
                    : 'PER_GLYPH'}
              </Text>
            </Pressable>
          ))}
        </View>

        <Text style={styles.sectionTitle}>Manual Progress (freeze frame)</Text>
        <View style={styles.row}>
          <Pressable
            style={[
              styles.toggleBtn,
              manualProgress === undefined && styles.toggleBtnActive,
            ]}
            onPress={() => setManualProgress(undefined)}
          >
            <Text
              style={[
                styles.toggleText,
                manualProgress === undefined && styles.toggleTextActive,
              ]}
            >
              Live
            </Text>
          </Pressable>
          {MANUAL_PROGRESS_VALUES.map((p) => (
            <Pressable
              key={p}
              style={[
                styles.progressBtn,
                manualProgress === p && styles.toggleBtnActive,
              ]}
              onPress={() => setManualProgress(p)}
            >
              <Text
                style={[
                  styles.progressText,
                  manualProgress === p && styles.toggleTextActive,
                ]}
              >
                {p.toFixed(2)}
              </Text>
            </Pressable>
          ))}
        </View>

        <Text style={styles.sectionTitle}>Diagnostics</Text>
        <Text style={styles.diag}>
          {`value: ${value}
strategy: ${strategy}
freeze: ${manualProgress ?? 'none'}
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
    paddingTop: 120,
    paddingBottom: 60,
    paddingHorizontal: 20,
  },
  numberContainer: {
    alignItems: 'center',
    justifyContent: 'center',
    height: 120,
    marginBottom: 20,
  },
  number: {
    fontSize: 84,
    fontWeight: '700',
    color: '#000',
  },
  buttonsRow: {
    flexDirection: 'row',
    gap: 24,
    marginBottom: 40,
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
  actionBtn: {
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderRadius: 8,
    backgroundColor: '#ffd60a',
    alignSelf: 'flex-start',
  },
  actionText: {
    fontSize: 13,
    fontWeight: '600',
    color: '#000',
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
  progressBtn: {
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 6,
    backgroundColor: '#e8e8ed',
    marginRight: 2,
    marginBottom: 4,
  },
  progressText: {
    fontSize: 11,
    color: '#333',
  },
  diag: {
    fontSize: 12,
    color: '#999',
    fontFamily: 'monospace',
    marginTop: 4,
  },
});
