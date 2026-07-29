/**
 * One button, three implementations.
 *
 * `@expo/ui` only gained its universal layer in SDK 56; on 55 the package exposes `swift-ui` and
 * `jetpack-compose` separately and nothing that spans them. So this is the shared prop shape and
 * the platform files render a real SwiftUI Button and a real Jetpack Compose Button from it.
 *
 * Deliberately limited to discrete presses. Neither native button surfaces press-down and
 * press-up, only a completed press, so a control that has to repeat while held — the increment and
 * decrement pair — cannot be built from them and stays a React Native Pressable.
 */
export type ActionIcon = 'play' | 'stop' | 'reset';

export type ActionButtonProps = {
  label: string;
  icon: ActionIcon;
  onPress: () => void;
  /** `primary` is the filled call to action; `secondary` is the quieter one beside it. */
  variant?: 'primary' | 'secondary';
};
