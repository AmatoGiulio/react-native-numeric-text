import type { Step } from './sequence';

/**
 * The showcase — meant to be watched, not measured.
 *
 * The long [SEQUENCE] exists so two platforms produce comparable recordings; it is slow and
 * repetitive because a frame comparison needs isolated, settled transitions. This one has the
 * opposite job, and its pacing follows from that: every beat lasts as long as the thing it shows
 * takes to read, rather than everything sharing one constant.
 *
 * A single column rolling is legible almost at once. Four columns cascading are not — the stagger
 * is the whole point, and it needs time after the motion stops for the eye to accept the number
 * has settled. So beats run from 800 ms for one digit to 1300 ms for a full-width change, and no
 * captioned scene is shorter than 800 ms, which is roughly how long its caption takes to read.
 *
 * `phase` is the caption. An empty one keeps the previous caption on screen, which is how a
 * scene's silent repositioning stays silent: the caption names what is about to happen, and should
 * not flicker during the setup for it.
 */

/** Repositioning before a scene: quick and uncaptioned, so it reads as a cut rather than a beat. */
const SET = 380;
/** One column changes. */
const ROLL = 800;
/** A digit appears where there was none — further to travel, more to watch. */
const BIRTH = 950;
/** Two columns roll and a third is born. */
const CARRY = 1000;
/** A grouping separator joins or leaves, which shifts everything on either side of it. */
const GROUP = 1050;
/** The whole number changes width. The stagger is the subject; it needs room after the motion. */
const WIDE = 1300;
/** Fraction and sign: small changes, but on an axis the eye is not expecting. */
const FRACTION = 880;
const SIGN = 900;
/** Repeat interval of the closing burst — still faster than a transition can settle. */
const BURST = 70;
/** The pause at the end, long enough to register that it has stopped. */
const REST = 1000;

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

  // The premise, stated first: one column moves and the others do not.
  { value: 2577, hold: ROLL, phase: 'One column rolls. The rest never move.' },
  { value: 2578, hold: ROLL, phase: 'One column rolls. The rest never move.' },

  // A digit that did not exist arrives along the roll axis, from further out than a roll travels.
  { value: 9, hold: SET, phase: '' },
  { value: 10, hold: BIRTH, phase: 'A new digit is born, not slid in.' },

  // Two rolls and a birth, released left to right rather than together.
  { value: 99, hold: SET, phase: '' },
  { value: 100, hold: CARRY, phase: 'Released left to right, 17 ms apart.' },

  // The separator is its own column, with its own life.
  { value: 999, hold: SET, phase: '' },
  { value: 1000, hold: GROUP, phase: 'The separator is born too.' },
  { value: 999, hold: GROUP, phase: 'And dies — in sequence, staying sharp.' },

  // Everything at once: the stagger reads as a wave across the number.
  { value: 1, hold: SET, phase: '' },
  {
    value: 9999,
    hold: WIDE,
    phase: 'Four columns. The stagger reads as a wave.',
  },
  { value: 1, hold: WIDE, phase: 'Four deaths, one after another.' },

  // Both sides of the decimal point, with the point itself as the anchor.
  { value: 1.9, hold: SET, phase: '' },
  { value: 2.0, hold: FRACTION, phase: 'Both sides of the point, together.' },

  // The sign is a column too, so it can die while a digit rolls.
  { value: -1, hold: SET, phase: '' },
  { value: 0, hold: SIGN, phase: 'The sign dies. The number re-centres.' },

  // The hard case, and the finale: changes arriving faster than any of them can finish, so every
  // spring carries its velocity into the next target instead of restarting.
  ...ramp(2580, 2600, BURST, 'Faster than they can settle. Nothing restarts.'),
  { value: 2600, hold: REST, phase: 'Settled.' },
];

export const SHOWCASE_START = SHOWCASE[0]!.value;

export const SHOWCASE_DURATION = SHOWCASE.reduce((sum, s) => sum + s.hold, 0);
