import { StatusBar } from 'expo-status-bar';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {
  NumericText,
  type NumericTextFormat,
} from 'react-native-numeric-text';

type Preset = {
  label: string;
  locale: string;
  format: NumericTextFormat;
  values: readonly [number, number];
  note: string;
};

type GtStep = {
  presetIndex: number;
  side: 0 | 1;
  hold: number;
  label: string;
};

const PRESETS: readonly Preset[] = [
  {
    label: 'USD symbol',
    locale: 'en-US',
    format: { style: 'currency', currency: 'USD' },
    values: [999.99, 1000],
    note: 'prefix symbol + grouping carry',
  },
  {
    label: 'EUR suffix',
    locale: 'de-DE',
    format: { style: 'currency', currency: 'EUR' },
    values: [999.99, 1000],
    note: 'suffix symbol + comma decimal',
  },
  {
    label: 'PAB punctuation',
    locale: 'es-PA',
    format: { style: 'currency', currency: 'PAB' },
    values: [1234.5, 1235.5],
    note: 'B/. contains the same dot as the numeric decimal mark',
  },
  {
    label: 'AED RTL',
    locale: 'ar-AE',
    format: { style: 'currency', currency: 'AED' },
    values: [1234.5, 1235.5],
    note: 'RTL + currency punctuation + bidi marks',
  },
  {
    label: 'Accounting',
    locale: 'en-US',
    format: {
      style: 'currency',
      currency: 'USD',
      currencySign: 'accounting',
    },
    values: [-999.99, 1000],
    note: 'parentheses disappear while the number crosses zero',
  },
  {
    label: 'USD code',
    locale: 'en-US',
    format: {
      style: 'currency',
      currency: 'USD',
      currencyDisplay: 'code',
    },
    values: [-999.99, 1000],
    note: 'stable ISO-code affix + sign change',
  },
  {
    label: 'Percent',
    locale: 'en-US',
    format: { style: 'percent' },
    values: [9.99, 10],
    note: 'x100 display + grouping carry around 1,000%',
  },
  {
    label: 'JPY zero digits',
    locale: 'ja-JP',
    format: { style: 'currency', currency: 'JPY' },
    values: [999, 1000],
    note: 'currency default has no fraction digits',
  },
  {
    label: 'BHD three digits',
    locale: 'en-US',
    format: { style: 'currency', currency: 'BHD' },
    values: [999.99, 1000],
    note: 'three default fraction digits + grouping carry',
  },
];

const BURST_INTERVAL_MS = 85;

// One tap produces the same event stream on iOS and Android. Long holds expose settled states;
// 85 ms runs exercise retriggering while the previous numeric transition is still in flight.
const GT_SEQUENCE: readonly GtStep[] = [
  { presetIndex: 1, side: 0, hold: 650, label: 'EUR A' },
  { presetIndex: 1, side: 1, hold: 850, label: 'EUR B' },
  { presetIndex: 1, side: 0, hold: 850, label: 'EUR A return' },

  { presetIndex: 2, side: 0, hold: 650, label: 'PAB A' },
  { presetIndex: 2, side: 1, hold: 850, label: 'PAB B' },
  { presetIndex: 2, side: 0, hold: BURST_INTERVAL_MS, label: 'PAB burst 1' },
  { presetIndex: 2, side: 1, hold: BURST_INTERVAL_MS, label: 'PAB burst 2' },
  { presetIndex: 2, side: 0, hold: BURST_INTERVAL_MS, label: 'PAB burst 3' },
  { presetIndex: 2, side: 1, hold: 950, label: 'PAB burst settle' },

  { presetIndex: 3, side: 0, hold: 650, label: 'AED A' },
  { presetIndex: 3, side: 1, hold: 850, label: 'AED B' },
  { presetIndex: 3, side: 0, hold: BURST_INTERVAL_MS, label: 'AED burst 1' },
  { presetIndex: 3, side: 1, hold: BURST_INTERVAL_MS, label: 'AED burst 2' },
  { presetIndex: 3, side: 0, hold: BURST_INTERVAL_MS, label: 'AED burst 3' },
  { presetIndex: 3, side: 1, hold: 950, label: 'AED burst settle' },

  { presetIndex: 4, side: 0, hold: 650, label: 'Accounting negative' },
  { presetIndex: 4, side: 1, hold: 950, label: 'Accounting positive' },
  { presetIndex: 5, side: 0, hold: 650, label: 'USD code negative' },
  { presetIndex: 5, side: 1, hold: 950, label: 'USD code positive' },
  { presetIndex: 6, side: 0, hold: 650, label: 'Percent A' },
  { presetIndex: 6, side: 1, hold: 950, label: 'Percent B' },
  { presetIndex: 7, side: 0, hold: 650, label: 'JPY A' },
  { presetIndex: 7, side: 1, hold: 950, label: 'JPY B' },
  { presetIndex: 8, side: 0, hold: 650, label: 'BHD A' },
  { presetIndex: 8, side: 1, hold: 1100, label: 'BHD B' },
];

export function FormatLab() {
  const [state, setState] = useState(() => ({ presetIndex: 0, side: 0 as 0 | 1 }));
  const [gtStepIndex, setGtStepIndex] = useState<number | null>(null);
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);
  const preset = PRESETS[state.presetIndex]!;
  const value = preset.values[state.side];
  const gtStep = gtStepIndex == null ? null : GT_SEQUENCE[gtStepIndex];

  const clearTimers = useCallback(() => {
    for (const timer of timers.current) clearTimeout(timer);
    timers.current = [];
  }, []);

  const cancelGtRun = useCallback(() => {
    clearTimers();
    setGtStepIndex(null);
  }, [clearTimers]);

  useEffect(
    () => () => {
      for (const timer of timers.current) clearTimeout(timer);
    },
    []
  );

  const selectPreset = useCallback(
    (presetIndex: number) => {
      cancelGtRun();
      // Change format and value in one React update so Android receives one logical transaction.
      setState({ presetIndex, side: 1 });
    },
    [cancelGtRun]
  );

  const setSide = useCallback(
    (side: 0 | 1) => {
      cancelGtRun();
      setState((current) => ({ ...current, side }));
    },
    [cancelGtRun]
  );

  const burst = useCallback(() => {
    cancelGtRun();
    const sequence: readonly (0 | 1)[] = [1, 0, 1, 0, 1];
    sequence.forEach((side, index) => {
      timers.current.push(
        setTimeout(() => {
          setState((current) => ({ ...current, side }));
        }, index * BURST_INTERVAL_MS)
      );
    });
  }, [cancelGtRun]);

  const nextPresetAndValue = useCallback(() => {
    cancelGtRun();
    setState((current) => ({
      presetIndex: (current.presetIndex + 1) % PRESETS.length,
      side: current.side === 0 ? 1 : 0,
    }));
  }, [cancelGtRun]);

  const runGtSequence = useCallback(() => {
    clearTimers();

    const first = GT_SEQUENCE[0]!;
    setGtStepIndex(0);
    setState({ presetIndex: first.presetIndex, side: first.side });

    let elapsed = 0;
    for (let index = 1; index < GT_SEQUENCE.length; index += 1) {
      elapsed += GT_SEQUENCE[index - 1]!.hold;
      const step = GT_SEQUENCE[index]!;
      timers.current.push(
        setTimeout(() => {
          setGtStepIndex(index);
          setState({ presetIndex: step.presetIndex, side: step.side });
        }, elapsed)
      );
    }

    elapsed += GT_SEQUENCE[GT_SEQUENCE.length - 1]!.hold;
    timers.current.push(setTimeout(() => setGtStepIndex(null), elapsed));
  }, [clearTimers]);

  return (
    <View style={styles.screen}>
      <StatusBar style="dark" />

      <View style={styles.readout}>
        <Text style={styles.title}>{preset.label}</Text>
        <Text style={styles.meta}>
          {preset.locale} · {preset.note}
        </Text>
        {gtStep ? (
          <Text style={styles.gtMarker}>
            GT {String(gtStepIndex! + 1).padStart(2, '0')}/{GT_SEQUENCE.length} ·{' '}
            {Platform.OS} · {gtStep.label}
          </Text>
        ) : (
          <Text style={styles.gtMarker}>manual · {Platform.OS}</Text>
        )}
        <NumericText
          value={value}
          locale={preset.locale}
          format={preset.format}
          animationDuration={320}
          style={styles.number}
        />
        <Text style={styles.raw}>raw: {String(value)}</Text>
      </View>

      <View style={styles.actions}>
        <Action label="A" onPress={() => setSide(0)} />
        <Action label="B" onPress={() => setSide(1)} />
        <Action label="BURST" onPress={burst} />
        <Action label="NEXT + VALUE" onPress={nextPresetAndValue} />
        <Action label="GT RUN" onPress={runGtSequence} />
      </View>

      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.presets}
      >
        {PRESETS.map((item, index) => (
          <Pressable
            key={`${item.locale}-${item.label}`}
            accessibilityRole="button"
            accessibilityLabel={`Select ${item.label}`}
            onPress={() => selectPreset(index)}
            style={({ pressed }) => [
              styles.preset,
              index === state.presetIndex && styles.presetSelected,
              pressed && styles.pressed,
            ]}
          >
            <Text
              style={[
                styles.presetText,
                index === state.presetIndex && styles.presetTextSelected,
              ]}
            >
              {item.label}
            </Text>
          </Pressable>
        ))}
      </ScrollView>
    </View>
  );
}

function Action({ label, onPress }: { label: string; onPress: () => void }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      style={({ pressed }) => [styles.action, pressed && styles.pressed]}
    >
      <Text style={styles.actionText}>{label}</Text>
    </Pressable>
  );
}

const INK = '#171719';

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    justifyContent: 'center',
    paddingHorizontal: 20,
    paddingVertical: 28,
    gap: 28,
    backgroundColor: '#fbfbf9',
  },
  readout: {
    minHeight: 280,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 9,
  },
  title: {
    color: INK,
    fontSize: 20,
    fontWeight: '700',
  },
  meta: {
    maxWidth: 420,
    color: '#6c6c72',
    fontSize: 13,
    textAlign: 'center',
  },
  gtMarker: {
    color: '#8b8b91',
    fontSize: 11,
    fontVariant: ['tabular-nums'],
    textAlign: 'center',
  },
  number: {
    color: INK,
    fontSize: 38,
    fontWeight: '700',
    fontVariant: ['tabular-nums'],
  },
  raw: {
    color: '#8b8b91',
    fontSize: 12,
  },
  actions: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'center',
    gap: 10,
  },
  action: {
    minHeight: 42,
    justifyContent: 'center',
    paddingHorizontal: 14,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: '#b8b8bd',
    borderRadius: 12,
    backgroundColor: '#ffffff',
  },
  actionText: {
    color: INK,
    fontSize: 13,
    fontWeight: '700',
  },
  presets: {
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: 2,
  },
  preset: {
    minHeight: 38,
    justifyContent: 'center',
    paddingHorizontal: 12,
    borderRadius: 19,
    backgroundColor: '#ececef',
  },
  presetSelected: {
    backgroundColor: INK,
  },
  presetText: {
    color: INK,
    fontSize: 12,
    fontWeight: '600',
  },
  presetTextSelected: {
    color: '#ffffff',
  },
  pressed: {
    opacity: 0.65,
  },
});
