import { Pressable, StyleSheet, Text } from 'react-native';

type Props = {
  playing: boolean;
  onCheckedChange: (playing: boolean) => void;
};

export function ReleaseToggle({ playing, onCheckedChange }: Props) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ selected: playing }}
      onPress={() => onCheckedChange(!playing)}
      style={({ pressed }) => [
        styles.button,
        playing && styles.buttonActive,
        pressed && styles.buttonPressed,
      ]}
    >
      <Text style={[styles.label, playing && styles.labelActive]}>
        {playing ? 'Stop' : 'Play'}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    minWidth: 92,
    height: 48,
    paddingHorizontal: 24,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 24,
    backgroundColor: '#ededeb',
  },
  buttonActive: {
    backgroundColor: '#171719',
  },
  buttonPressed: {
    opacity: 0.72,
  },
  label: {
    color: '#171719',
    fontSize: 15,
    fontWeight: '600',
  },
  labelActive: {
    color: '#ffffff',
  },
});
