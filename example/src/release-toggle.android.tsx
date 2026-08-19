import { Host, Text, ToggleButton } from '@expo/ui/jetpack-compose';

type Props = {
  playing: boolean;
  onCheckedChange: (playing: boolean) => void;
};

export function ReleaseToggle({ playing, onCheckedChange }: Props) {
  return (
    <Host matchContents>
      <ToggleButton checked={playing} onCheckedChange={onCheckedChange}>
        <Text>{playing ? 'Stop' : 'Play'}</Text>
      </ToggleButton>
    </Host>
  );
}
