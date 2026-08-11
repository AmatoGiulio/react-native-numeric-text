import { useCallback, useEffect, useRef } from 'react';

export type Step = { value: number; hold: number };

function ramp(from: number, to: number, hold: number): Step[] {
  return Array.from({ length: Math.abs(to - from) + 1 }, (_, index) => ({
    value: from + Math.sign(to - from) * index,
    hold,
  }));
}

/** A continuous 15-second tour of the library's main transition cases. */
export const SHOWCASE_SEQUENCE: Step[] = [
  { value: 1984, hold: 1000 },
  { value: 1985, hold: 550 },
  { value: 1986, hold: 550 },
  { value: 1987, hold: 700 },
  { value: 1999, hold: 700 },
  { value: 2000, hold: 1000 },
  { value: 999, hold: 800 },
  { value: 1000, hold: 900 },
  { value: 999, hold: 900 },
  { value: 10, hold: 700 },
  { value: 9, hold: 700 },
  { value: 0, hold: 700 },
  { value: -1, hold: 800 },
  { value: 0, hold: 700 },
  { value: 1, hold: 700 },
  { value: 9.8, hold: 700 },
  { value: 9.9, hold: 500 },
  { value: 10, hold: 900 },
  ...ramp(101, 110, 100),
  { value: 1984, hold: 500 },
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
