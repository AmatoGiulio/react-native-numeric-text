import { Pressable, StyleSheet, Text } from 'react-native';
import type { ActionButtonProps } from './ActionButton.types';

/**
 * Web fallback. The native files beside this one render a real SwiftUI or Jetpack Compose button;
 * there is no `@expo/ui` universal layer on SDK 55 to share between them.
 */
export function ActionButton({
  label,
  onPress,
  variant = 'primary',
}: ActionButtonProps) {
  return (
    <Pressable
      style={({ pressed }) => [
        styles.button,
        variant === 'secondary' && styles.secondary,
        pressed && styles.pressed,
      ]}
      onPress={onPress}
      accessibilityRole="button"
    >
      <Text
        style={[styles.text, variant === 'secondary' && styles.secondaryText]}
      >
        {label}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    paddingHorizontal: 28,
    paddingVertical: 15,
    borderRadius: 999,
    backgroundColor: '#0b0b0d',
  },
  secondary: { backgroundColor: '#e7e7ec' },
  text: { fontSize: 16, fontWeight: '600', color: '#fff' },
  secondaryText: { color: '#0b0b0d' },
  pressed: { opacity: 0.6 },
});
