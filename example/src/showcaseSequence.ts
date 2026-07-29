import type { Step } from './sequence';

/**
 * The short showcase — about ten seconds, meant to be recorded and watched, not measured.
 *
 * The long [SEQUENCE] exists so two platforms produce comparable recordings; it is deliberately
 * slow and repetitive because a frame comparison needs isolated, settled transitions. This one has
 * the opposite job: every beat has to earn its place on screen, so each scene appears once, in the
 * order that makes the renderer's behaviour legible — a single column first, then a birth, then a
 * death, then all of them at once, and the rapid case last because it is the one that looks
 * impossible until you see it.
 *
 * `phase` is a caption shown under the number. An empty caption keeps the previous one on screen,
 * which is how a scene's setup step stays silent: the caption should name what is about to happen,
 * not flicker during the repositioning before it.
 */

const SET = 300; // reposition before a scene — silent, deliberately too fast to read
const BEAT = 560; // a scene that reads in one glance
const HOLD = 700; // a scene worth watching land
const BURST = 42; // faster than a transition can settle, near the auto-repeat's own 30 ms
const REST = 620; // the final settle

/** Expand an inclusive integer range into per-step entries. */
function ramp(from: number, to: number, hold: number, phase: string): Step[] {
  const out: Step[] = [];
  const dir = to >= from ? 1 : -1;
  for (let v = from; dir > 0 ? v <= to : v >= to; v += dir) {
    out.push({ value: v, hold, phase });
  }
  return out;
}

export const SHOWCASE: Step[] = [
  { value: 2576, hold: SET, phase: '' },

  // One column moves and the others do not — the whole premise, stated first.
  { value: 2577, hold: BEAT, phase: 'One column rolls. The rest never move.' },
  { value: 2578, hold: BEAT, phase: 'One column rolls. The rest never move.' },

  // A digit that did not exist arrives along the roll axis, from further out than a roll travels.
  { value: 9, hold: SET, phase: '' },
  { value: 10, hold: HOLD, phase: 'A new digit is born, not slid in.' },

  // Two rolls and a birth, released left to right rather than together.
  { value: 99, hold: SET, phase: '' },
  { value: 100, hold: HOLD, phase: 'Released left to right, 17 ms apart.' },

  // The separator is its own column with its own life.
  { value: 999, hold: SET, phase: '' },
  { value: 1000, hold: HOLD, phase: 'The separator is born too.' },
  { value: 999, hold: HOLD, phase: 'And dies — in sequence, staying sharp.' },

  // Everything at once: the stagger reads as a wave across the number.
  { value: 1, hold: SET, phase: '' },
  {
    value: 9999,
    hold: HOLD,
    phase: 'Four columns. The stagger reads as a wave.',
  },
  { value: 1, hold: HOLD, phase: 'Four deaths, one after another.' },

  // Both sides of the decimal point, with the point itself as the anchor.
  { value: 1.9, hold: SET, phase: '' },
  { value: 2.0, hold: BEAT, phase: 'Both sides of the point, together.' },

  // The sign is a column as well, so it can die while a digit rolls.
  { value: -1, hold: SET, phase: '' },
  { value: 0, hold: BEAT, phase: 'The sign dies. The number re-centres.' },

  // The hard case, and the finale: changes arriving faster than any of them can finish, so each
  // spring carries its velocity into the next target instead of restarting.
  ...ramp(2575, 2600, BURST, 'Faster than they can settle. Nothing restarts.'),
  { value: 2600, hold: REST, phase: 'Settled.' },
];

export const SHOWCASE_START = SHOWCASE[0]!.value;

export const SHOWCASE_DURATION = SHOWCASE.reduce((sum, s) => sum + s.hold, 0);
