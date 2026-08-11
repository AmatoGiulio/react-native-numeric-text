import {
  FilledIconButton,
  FilledTonalIconButton,
  Host,
  Icon,
} from '@expo/ui/jetpack-compose';
import { size } from '@expo/ui/jetpack-compose/modifiers';
import { View } from 'react-native';

type IconName = 'add' | 'play-arrow' | 'remove' | 'stop';
type Props = { icon: IconName; primary: boolean };

const ICONS = {
  'add': require('../assets/material-symbols/add.xml'),
  'play-arrow': require('../assets/material-symbols/play-arrow.xml'),
  'remove': require('../assets/material-symbols/remove.xml'),
  'stop': require('../assets/material-symbols/stop.xml'),
} as const;

export function NativeIconButton({ icon, primary }: Props) {
  const Button = primary ? FilledIconButton : FilledTonalIconButton;

  return (
    <View
      pointerEvents="none"
      accessible={false}
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
    >
      <Host matchContents>
        <Button
          colors={{
            containerColor: primary ? '#171719' : '#ededeb',
            contentColor: primary ? '#ffffff' : '#171719',
          }}
          modifiers={[size(58, 58)]}
        >
          <Icon
            source={ICONS[icon]}
            size={primary ? 28 : 26}
            tint={primary ? '#ffffff' : '#171719'}
          />
        </Button>
      </Host>
    </View>
  );
}
