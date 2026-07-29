import { Button, Host, Icon, Row, Text } from '@expo/ui/jetpack-compose';
import type { ActionButtonProps, ActionIcon } from './ActionButton.types';

// Metro bundles .xml vector drawables with no extra config. These are drawn in the repo rather
// than pulled from an icon set — see example/assets/icons.
const ICON: Record<ActionIcon, number> = {
  play: require('../../assets/icons/play.xml'),
  stop: require('../../assets/icons/stop.xml'),
  reset: require('../../assets/icons/reset.xml'),
};

const INK = '#0b0b0d';
const QUIET = '#e7e7ec';

export function ActionButton({
  label,
  icon,
  onPress,
  variant = 'primary',
}: ActionButtonProps) {
  const primary = variant === 'primary';
  const content = primary ? '#ffffff' : INK;

  return (
    <Host matchContents>
      <Button
        onClick={onPress}
        colors={{
          containerColor: primary ? INK : QUIET,
          contentColor: content,
        }}
        contentPadding={{ start: 20, end: 24, top: 12, bottom: 12 }}
      >
        <Row horizontalArrangement={{ spacedBy: 8 }} verticalAlignment="center">
          <Icon source={ICON[icon]} size={18} tint={content} />
          <Text color={content}>{label}</Text>
        </Row>
      </Button>
    </Host>
  );
}
