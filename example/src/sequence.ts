import { useCallback, useEffect, useRef } from 'react';

/**
 * A single scripted step: set `value`, then wait `hold` ms before the next step. `phase` is a
 * human label shown on-screen so a given frame in the recording can be matched to what it is
 * testing (single roll, carry, length change, rapid burst, …).
 */
export type Step = { value: number; hold: number; phase: string };

// Timings (ms). CLEAN lets a transition fully settle before the next one (isolated, easy to read
// frame-by-frame). RAPID mimics a finger held on +/- (spam faster than a transition can settle).
const CLEAN = 700;
const CARRY = 850;
const LONG = 1200; // big jumps we want to watch land
const PAUSE = 1100; // between phases, so the recording is easy to segment
const RAPID = 45;
const MED = 130;

// Expand an inclusive integer range into per-step entries (used for the rapid-hold burst).
function ramp(from: number, to: number, hold: number, phase: string): Step[] {
  const out: Step[] = [];
  const dir = to >= from ? 1 : -1;
  for (let v = from; dir > 0 ? v <= to : v >= to; v += dir)
    out.push({ value: v, hold, phase });
  return out;
}

/**
 * The studied sequence. One continuous timeline covering every case that stresses the renderer,
 * in a deterministic order with deterministic timing — so the SAME script drives the iOS
 * reference and the Android library and the two recordings line up for frame comparison.
 */
export const SEQUENCE: Step[] = [
  // 0 — start marker
  { value: 2576, hold: PAUSE, phase: 'start' },

  // 1 — clean single-digit increments (the units roll in isolation)
  { value: 2577, hold: CLEAN, phase: 'single +1' },
  { value: 2578, hold: CLEAN, phase: 'single +1' },
  { value: 2579, hold: CLEAN, phase: 'single +1' },
  { value: 2580, hold: PAUSE, phase: 'single +1' },

  // 2 — carries (two/three digits change at once, thousands stays put)
  { value: 2590, hold: CARRY, phase: 'carry tens' },
  { value: 2599, hold: CARRY, phase: 'carry tens' },
  { value: 2600, hold: PAUSE, phase: 'carry hundreds' },

  // 3 — length grows (a new leading digit + separator are born; units must stay aligned)
  { value: 9, hold: PAUSE, phase: 'grow: set 9' },
  { value: 10, hold: CLEAN, phase: 'grow 9→10' },
  { value: 99, hold: PAUSE, phase: 'grow: set 99' },
  { value: 100, hold: CLEAN, phase: 'grow 99→100' },
  { value: 999, hold: PAUSE, phase: 'grow: set 999' },
  { value: 1000, hold: LONG, phase: 'grow 999→1,000' },

  // 4 — length shrinks (leading digit + separator die)
  { value: 999, hold: LONG, phase: 'shrink 1,000→999' },
  { value: 100, hold: PAUSE, phase: 'shrink: set 100' },
  { value: 99, hold: CLEAN, phase: 'shrink 100→99' },
  { value: 10, hold: PAUSE, phase: 'shrink: set 10' },
  { value: 9, hold: PAUSE, phase: 'shrink 10→9' },

  // 5 — big jumps (many columns change, large width change)
  { value: 1, hold: PAUSE, phase: 'bigjump: set 1' },
  { value: 9999, hold: LONG, phase: 'bigjump 1→9,999' },
  { value: 1, hold: LONG, phase: 'bigjump 9,999→1' },

  // 6 — decimals (fraction column rolls; decimal separator is an anchor)
  { value: 1.5, hold: PAUSE, phase: 'decimals: set 1.5' },
  { value: 1.9, hold: CLEAN, phase: 'decimals 1.5→1.9' },
  { value: 2.4, hold: CLEAN, phase: 'decimals 1.9→2.4' },
  { value: 2.5, hold: PAUSE, phase: 'decimals 2.4→2.5' },

  // 7 — sign changes crossing zero
  { value: 2, hold: PAUSE, phase: 'sign: set 2' },
  { value: 1, hold: CLEAN, phase: 'sign 2→1' },
  { value: 0, hold: CLEAN, phase: 'sign 1→0' },
  { value: -1, hold: CLEAN, phase: 'sign 0→-1' },
  { value: -2, hold: CLEAN, phase: 'sign -1→-2' },
  { value: -1, hold: CLEAN, phase: 'sign -2→-1' },
  { value: 0, hold: PAUSE, phase: 'sign -1→0' },

  // 8 — rapid hold (spam faster than settle): units never rests, higher columns rarely move.
  { value: 100, hold: PAUSE, phase: 'rapid: set 100' },
  ...ramp(101, 140, RAPID, 'rapid hold +1'),
  { value: 140, hold: PAUSE, phase: 'rapid: settle' },

  // 9 — rapid mixed jumps (multi-digit changes in quick succession)
  { value: 1200, hold: PAUSE, phase: 'rapid mixed: set 1,200' },
  { value: 1234, hold: MED, phase: 'rapid mixed' },
  { value: 1250, hold: MED, phase: 'rapid mixed' },
  { value: 1299, hold: MED, phase: 'rapid mixed' },
  { value: 1300, hold: MED, phase: 'rapid mixed' },
  { value: 1400, hold: MED, phase: 'rapid mixed' },
  { value: 1500, hold: MED, phase: 'rapid mixed' },
  { value: 1999, hold: MED, phase: 'rapid mixed' },
  { value: 2000, hold: PAUSE, phase: 'rapid mixed: settle' },

  // 10 — decrement run (down direction, single column)
  ...ramp(1999, 1994, 320, 'decrement -1'),
  { value: 1994, hold: PAUSE, phase: 'end' },
];

export const SEQUENCE_START = SEQUENCE[0]!.value;

/** Total wall-clock duration of the sequence (ms), useful for logging / progress. */
export const SEQUENCE_DURATION = SEQUENCE.reduce((sum, s) => sum + s.hold, 0);

/**
 * Drives `onValue` / `onPhase` through the scripted [SEQUENCE] with real timers. Deterministic:
 * pressing Play on iOS and on Android produces the same value-vs-time curve, so the two
 * recordings are directly comparable.
 */
export function useSequencePlayer(
  onValue: (v: number) => void,
  onPhase?: (phase: string) => void,
  onDone?: () => void
) {
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);

  const stop = useCallback(() => {
    timers.current.forEach(clearTimeout);
    timers.current = [];
  }, []);

  const play = useCallback(() => {
    stop();
    let t = 0;
    for (const step of SEQUENCE) {
      const at = t;
      timers.current.push(
        setTimeout(() => {
          onValue(step.value);
          onPhase?.(step.phase);
        }, at)
      );
      t += step.hold;
    }
    timers.current.push(setTimeout(() => onDone?.(), t));
  }, [onValue, onPhase, onDone, stop]);

  useEffect(() => stop, [stop]);

  return { play, stop };
}
