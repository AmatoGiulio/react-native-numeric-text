import { useCallback, useState } from 'react';
import { Lab } from './Lab';
import { Showcase } from './Showcase';

/**
 * Two screens with different jobs.
 *
 * `Showcase` is the demo: the number, two controls and one scripted run, clean enough to point a
 * camera at. `Lab` is the comparison harness — the SwiftUI toggle, the presets, the long scripted
 * sequence and the diagnostics — which is what the parity measurements are driven from.
 */
export default function App() {
  const [screen, setScreen] = useState<'showcase' | 'lab'>('showcase');

  const openLab = useCallback(() => setScreen('lab'), []);
  const openShowcase = useCallback(() => setScreen('showcase'), []);

  return screen === 'showcase' ? (
    <Showcase onOpenLab={openLab} />
  ) : (
    <Lab onOpenShowcase={openShowcase} />
  );
}
