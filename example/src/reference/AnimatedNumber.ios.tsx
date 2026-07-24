import { Host, Text } from '@expo/ui/swift-ui';
import {
  Animation,
  animation,
  contentTransition,
  font,
  frame,
  monospacedDigit,
} from '@expo/ui/swift-ui/modifiers';
import { useState } from 'react';
import { Dimensions } from 'react-native';

export type AnimatedNumberProps = {
  value: number;
  size?: number;
};

// Full-screen pixel width for the Host. A SwiftUI Host does NOT resolve a percentage width
// ('100%') into a finite layout proposal — that left the Text measured at 0 / off-screen. An
// explicit pixel width + a finite `frame(maxWidth)` centres the Text reliably.
const HOST_WIDTH = Math.floor(Dimensions.get('window').width);

// The iOS gold-standard reference: SwiftUI's `.numericText()` content transition (as published by
// Nathan Schroeder). `onLayoutContent` logs the measured SwiftUI content size for diagnostics.
export function AnimatedNumber({ value, size = 84 }: AnimatedNumberProps) {
  const [prev, setPrev] = useState(value);
  const [countsDown, setCountsDown] = useState(false);
  if (prev !== value) {
    setCountsDown(value < prev);
    setPrev(value);
  }

  return (
    <Host
      style={{ width: HOST_WIDTH, height: 120 }}
      onLayoutContent={(e) =>
        console.log('[AnimatedNumber] host content size', e.nativeEvent)
      }
    >
      <Text
        modifiers={[
          font({ size, weight: 'bold', design: 'rounded' }),
          monospacedDigit(),
          contentTransition('numericText', { countsDown }),
          animation(Animation.spring(), value),
          frame({ maxWidth: HOST_WIDTH, maxHeight: 120, alignment: 'center' }),
        ]}
      >
        {value.toLocaleString('en-US')}
      </Text>
    </Host>
  );
}
