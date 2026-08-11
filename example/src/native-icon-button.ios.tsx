import { Button, Host, Image } from '@expo/ui/swift-ui';
import {
  buttonStyle,
  clipShape,
  controlSize,
  frame,
  tint,
} from '@expo/ui/swift-ui/modifiers';
import { View } from 'react-native';

type IconName = 'add' | 'play-arrow' | 'remove' | 'stop';
type Props = { icon: IconName; primary: boolean };

const ICONS = {
  'add': 'plus',
  'play-arrow': 'play.fill',
  'remove': 'minus',
  'stop': 'stop.fill',
} as const;

export function NativeIconButton({ icon, primary }: Props) {
  return (
    <View
      pointerEvents="none"
      accessible={false}
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
    >
      <Host matchContents>
        <Button
          modifiers={[
            buttonStyle(primary ? 'borderedProminent' : 'glass'),
            clipShape('circle'),
            controlSize('large'),
            ...(primary ? [tint('#171719')] : []),
          ]}
        >
          <Image
            systemName={ICONS[icon]}
            size={primary ? 22 : 20}
            modifiers={[frame({ width: 24, height: 24 })]}
          />
        </Button>
      </Host>
    </View>
  );
}
