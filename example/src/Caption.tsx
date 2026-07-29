import { useEffect, useRef, useState } from 'react';
import { Animated, Easing, StyleSheet, useAnimatedValue } from 'react-native';

type Props = { text: string };

/**
 * The line under the number, naming the scene as it happens.
 *
 * It cross-fades rather than swapping: the caption is describing a transition, so it should not
 * arrive by cutting. Outgoing text falls and fades, incoming text rises into place — the same
 * gesture as the digits above it, which makes the two read as one event rather than a number
 * animating while a label blinks.
 *
 * The text only changes on the swap, held in state until the outgoing half has finished, so the
 * old caption is never seen wearing the new words.
 */
export function Caption({ text }: Props) {
  const [shown, setShown] = useState(text);
  const anim = useAnimatedValue(1);
  const pending = useRef(text);
  pending.current = text;

  useEffect(() => {
    if (text === shown) return;

    Animated.timing(anim, {
      toValue: 0,
      duration: 140,
      easing: Easing.in(Easing.quad),
      useNativeDriver: true,
    }).start(({ finished }) => {
      if (!finished) return;
      setShown(pending.current);
      Animated.timing(anim, {
        toValue: 1,
        duration: 260,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }).start();
    });
  }, [text, shown, anim]);

  return (
    <Animated.Text
      style={[
        styles.caption,
        {
          opacity: anim,
          transform: [
            {
              translateY: anim.interpolate({
                inputRange: [0, 1],
                outputRange: [6, 0],
              }),
            },
          ],
        },
      ]}
      numberOfLines={2}
    >
      {shown}
    </Animated.Text>
  );
}

const styles = StyleSheet.create({
  // Fixed height: a caption arriving or leaving must not nudge the number off centre.
  caption: {
    height: 44,
    fontSize: 15,
    lineHeight: 21,
    textAlign: 'center',
    color: '#7c7c88',
    letterSpacing: 0.1,
  },
});
