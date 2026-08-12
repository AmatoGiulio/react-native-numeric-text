import { useCallback, useEffect, useRef } from 'react';

export type Step = { value: number; hold: number };

/** A focused 10-second promo sequence: scale, structural changes, rollback and retriggers. */
export const SHOWCASE_SEQUENCE: Step[] = [
  // Scala iniziale: tre incrementi singoli, facili da leggere.
  { value: 1992, hold: 460 },
  { value: 1993, hold: 450 },
  { value: 1994, hold: 450 },
  // Cambi strutturali netti, come nella sequenza Lab.
  { value: 99, hold: 800 },
  { value: 100, hold: 800 },
  { value: 1, hold: 800 },
  // Discesa lenta: -1, -1, -1, -1, -1.
  { value: 0, hold: 650 },
  { value: -1, hold: 550 },
  { value: -2, hold: 450 },
  { value: -3, hold: 350 },
  { value: -4, hold: 300 },
  // Contro-trigger a 60 ms: la struttura si interrompe e lascia la coda delle due cifre.
  { value: 1000, hold: 400 },
  { value: 999, hold: 360 },
  { value: 1000, hold: 360 },

  // Press-and-hold simulato: +123 continuo, con intervalli sempre più brevi.
  { value: 1123, hold: 360 },
  { value: 1246, hold: 300 },
  { value: 1369, hold: 240 },
  { value: 1492, hold: 180 },
  { value: 1615, hold: 150 },
  { value: 1738, hold: 120 },
  { value: 1861, hold: 100 },
  { value: 1984, hold: 80 },
  { value: 2107, hold: 60 },
  { value: 2230, hold: 60 },
  { value: 2353, hold: 60 },
  { value: 2476, hold: 60 },
  // Coda di rientro: il rollback rallenta e chiude sul valore iniziale.
  { value: 2353, hold: 120 },
  { value: 2230, hold: 140 },
  { value: 2107, hold: 180 },
  { value: 1992, hold: 660 },
];

function scheduleOnFrames(
  entries: { at: number; run: () => void }[]
): () => void {
  const start = Date.now();
  let next = 0;
  let frame: number | null = null;

  const tick = () => {
    const elapsed = Date.now() - start;
    while (next < entries.length && entries[next]!.at <= elapsed) {
      entries[next]!.run();
      next += 1;
    }
    frame = next < entries.length ? requestAnimationFrame(tick) : null;
  };

  frame = requestAnimationFrame(tick);
  return () => {
    if (frame !== null) cancelAnimationFrame(frame);
    frame = null;
  };
}

export function useSequencePlayer(
  onValue: (value: number) => void,
  onDone?: () => void
) {
  const cancel = useRef<(() => void) | null>(null);

  const stop = useCallback(() => {
    cancel.current?.();
    cancel.current = null;
  }, []);

  const play = useCallback(() => {
    stop();
    let at = 0;
    const entries = SHOWCASE_SEQUENCE.map((step) => {
      const when = at;
      at += step.hold;
      return { at: when, run: () => onValue(step.value) };
    });
    entries.push({
      at,
      run: () => {
        cancel.current = null;
        onDone?.();
      },
    });
    cancel.current = scheduleOnFrames(entries);
  }, [onDone, onValue, stop]);

  useEffect(() => stop, [stop]);

  return { play, stop };
}
