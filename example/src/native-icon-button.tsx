import { StyleSheet, Text, View } from 'react-native';

type IconName = 'add' | 'play-arrow' | 'remove' | 'stop';
type Props = { icon: IconName; primary: boolean };

const ICONS = {
  'add': '+',
  'play-arrow': '▶',
  'remove': '−',
  'stop': '■',
} as const;

export function NativeIconButton({ icon, primary }: Props) {
  return (
    <View style={[styles.button, primary && styles.primary]}>
      <Text style={[styles.icon, primary && styles.primaryIcon]}>
        {ICONS[icon]}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  button: {
    width: 58,
    height: 58,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 29,
    backgroundColor: '#ededeb',
  },
  primary: {
    backgroundColor: '#171719',
  },
  icon: {
    color: '#171719',
    fontSize: 26,
  },
  primaryIcon: {
    color: '#ffffff',
  },
});
