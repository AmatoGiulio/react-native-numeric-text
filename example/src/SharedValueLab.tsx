import { StatusBar } from 'expo-status-bar';
import { useCallback, useRef } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { NumericText } from 'react-native-numeric-text';
import {
  cancelAnimation,
  useSharedValue,
  withSpring,
  withTiming,
} from 'react-native-reanimated';

/**
 * The same component, fed by a shared value instead of a prop.
 *
 * Nothing here calls `setState`. The number below is driven from the UI thread by Reanimated, and
 * the render counter next to it stays put while it rolls — which is the whole point: a value that
 * moves every frame does not have to cost a React render every frame.
 */
export function SharedValueLab() {
  const amount = useSharedValue(1240.5);
  const renders = useRef(0);
  renders.current += 1;

  const sweep = useCallback(() => {
    cancelAnimation(amount);
    const target = Math.round(Math.random() * 99000) / 10;
    amount.value = withTiming(target, { duration: 2600 });
  }, [amount]);

  const spring = useCallback(() => {
    cancelAnimation(amount);
    const target = Math.round(Math.random() * 9900) / 10;
    amount.value = withSpring(target, { damping: 12, stiffness: 90 });
  }, [amount]);

  const reset = useCallback(() => {
    cancelAnimation(amount);
    amount.value = withTiming(0, { duration: 700 });
  }, [amount]);

  return (
    <View style={styles.screen}>
      <StatusBar style="dark" />

      <NumericText
        value={amount}
        currency="USD"
        animationDuration={320}
        style={styles.number}
      />

      <Text style={styles.caption}>
        React renders since mount: {renders.current}
      </Text>

      <View style={styles.controls}>
        <Button label="Sweep" onPress={sweep} />
        <Button label="Spring" onPress={spring} />
        <Button label="Reset" onPress={reset} />
      </View>
    </View>
  );
}

function Button({ label, onPress }: { label: string; onPress: () => void }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      hitSlop={8}
      style={({ pressed }) => [styles.button, pressed && styles.buttonPressed]}
    >
      <Text style={styles.buttonLabel}>{label}</Text>
    </Pressable>
  );
}

const INK = '#171719';

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 28,
    paddingHorizontal: 24,
    backgroundColor: '#fbfbf9',
  },
  number: {
    color: INK,
    fontSize: 64,
    fontWeight: '700',
    fontVariant: ['tabular-nums'],
  },
  caption: {
    color: '#6b6b70',
    fontSize: 15,
  },
  controls: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  button: {
    paddingHorizontal: 20,
    paddingVertical: 12,
    borderRadius: 22,
    backgroundColor: '#ececea',
  },
  buttonPressed: {
    opacity: 0.72,
    transform: [{ scale: 0.96 }],
  },
  buttonLabel: {
    color: INK,
    fontSize: 16,
    fontWeight: '600',
  },
});
