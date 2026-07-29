import { useCallback, useEffect, useRef } from 'react';

/**
 * Press-and-hold that accelerates the longer it is held.
 *
 * A flat 30 ms repeat made the control almost unusable: the first repeat landed before a normal
 * tap had finished, so tapping once often moved the number by three or four, and there was no
 * speed between "one" and "flat out".
 *
 * The shape here is the one a stepper wants. A press that ends quickly is exactly one step —
 * nothing repeats until [FIRST_DELAY] has passed, which is longer than any tap. After that the
 * interval eases from [SLOW] to [FAST] across [RAMP_MS], so a short hold nudges the value and a
 * long one runs the roll continuously, which is the case the renderer is most interesting in.
 */

/** Held for less than this and nothing repeats: a tap is one step. */
const FIRST_DELAY = 400;
/** Interval at the moment repeating begins. */
const SLOW = 240;
/** Interval once fully wound up. */
const FAST = 40;
/** How long the acceleration takes, measured from when repeating begins. */
const RAMP_MS = 2000;

/**
 * Eased so the first few repeats stay countable and the speed arrives afterwards — a linear ramp
 * spends most of its time already fast, which reads as no ramp at all.
 */
export function intervalAfter(heldMs: number): number {
  const t = Math.min(Math.max((heldMs - FIRST_DELAY) / RAMP_MS, 0), 1);
  return FAST + (SLOW - FAST) * Math.pow(1 - t, 2.2);
}

export function useHoldRepeat(onStep: (direction: number) => void) {
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const startedAt = useRef(0);
  // Read through a ref so a re-rendered callback does not restart the ramp mid-hold.
  const step = useRef(onStep);
  step.current = onStep;

  const stop = useCallback(() => {
    if (timer.current) {
      clearTimeout(timer.current);
      timer.current = null;
    }
  }, []);

  const start = useCallback(
    (direction: number) => {
      stop();
      startedAt.current = Date.now();
      step.current(direction);

      const tick = () => {
        const held = Date.now() - startedAt.current;
        step.current(direction);
        timer.current = setTimeout(tick, intervalAfter(held));
      };
      timer.current = setTimeout(tick, FIRST_DELAY);
    },
    [stop]
  );

  useEffect(() => stop, [stop]);

  return { start, stop };
}
