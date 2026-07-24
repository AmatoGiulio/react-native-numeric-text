import { View, Text, StyleSheet } from 'react-native';

export type AnimatedNumberProps = {
  value: number;
  size?: number;
};

// Android stub: the SwiftUI reference cannot run here. The `.android.tsx` / `.ios.tsx` split keeps
// @expo/ui/swift-ui from ever being imported on Android. On Android, compare against "This library".
export function AnimatedNumber(_props: AnimatedNumberProps) {
  return (
    <View style={styles.wrap}>
      <Text style={styles.note}>
        SwiftUI reference is iOS-only.{'\n'}Select “This library” to record on
        Android.
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    height: 120,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 24,
  },
  note: { fontSize: 15, color: '#999', textAlign: 'center', lineHeight: 22 },
});
