import { Button, Host } from '@expo/ui/swift-ui';
import { buttonStyle, controlSize, tint } from '@expo/ui/swift-ui/modifiers';
import type { ActionButtonProps, ActionIcon } from './ActionButton.types';

/** SF Symbols, so the icons are the system's own rather than shipped art. */
const SYMBOL: Record<
  ActionIcon,
  'play.fill' | 'stop.fill' | 'arrow.counterclockwise'
> = {
  play: 'play.fill',
  stop: 'stop.fill',
  reset: 'arrow.counterclockwise',
};

export function ActionButton({
  label,
  icon,
  onPress,
  variant = 'primary',
}: ActionButtonProps) {
  return (
    <Host matchContents>
      <Button
        label={label}
        systemImage={SYMBOL[icon]}
        onPress={onPress}
        modifiers={[
          buttonStyle(variant === 'primary' ? 'borderedProminent' : 'bordered'),
          controlSize('large'),
          tint('#0b0b0d'),
        ]}
      />
    </Host>
  );
}
