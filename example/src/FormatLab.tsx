import { StatusBar } from 'expo-status-bar';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
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
    values: [-1234.5, 1234.5],
    note: 'stable ISO-code affix + sign change',
  },
  {
    label: 'Percent',
    locale: 'en-US',
    format: { style: 'percent' },
    values: [0.099, 0.1],
    note: 'value is multiplied by 100 before display',
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
    values: [9.999, 10],
    note: 'currency default keeps three fraction digits',
  },
];

const BURST_INTERVAL_MS = 85;

export function FormatLab() {
  const [state, setState] = useState(() => ({ presetIndex: 0, side: 0 as 0 | 1 }));
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);
  const preset = PRESETS[state.presetIndex]!;
  const value = preset.values[state.side];

  const clearBurst = useCallback(() => {
    for (const timer of timers.current) clearTimeout(timer);
    timers.current = [];
  }, []);

  useEffect(() => clearBurst, [clearBurst]);

  const selectPreset = useCallback(
    (presetIndex: number) => {
      clearBurst();
      // Change format and value in one React update. Structural format changes should commit as one
      // atomic snap; subsequent A/B and burst changes exercise the numeric transition itself.
      setState({ presetIndex, side: 1 });
    },
    [clearBurst]
  );

  const setSide = useCallback(
    (side: 0 | 1) => {
      clearBurst();
      setState((current) => ({ ...current, side }));
    },
    [clearBurst]
  );

  const burst = useCallback(() => {
    clearBurst();
    const sequence: readonly (0 | 1)[] = [1, 0, 1, 0, 1];
    sequence.forEach((side, index) => {
      timers.current.push(
        setTimeout(() => {
          setState((current) => ({ ...current, side }));
        }, index * BURST_INTERVAL_MS)
      );
    });
  }, [clearBurst]);

  const nextPresetAndValue = useCallback(() => {
    clearBurst();
    setState((current) => ({
      presetIndex: (current.presetIndex + 1) % PRESETS.length,
      side: current.side === 0 ? 1 : 0,
    }));
  }, [clearBurst]);

  return (
    <View style={styles.screen}>
      <StatusBar style="dark" />

      <View style={styles.readout}>
        <Text style={styles.title}>{preset.label}</Text>
        <Text style={styles.meta}>
          {preset.locale} · {preset.note}
        </Text>
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
    minHeight: 260,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 10,
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
