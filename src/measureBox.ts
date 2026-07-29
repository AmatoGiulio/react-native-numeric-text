/**
 * A minimum box for the native view, estimated in JS.
 *
 * This is not belt-and-braces around the native measurement — under Fabric it is the *only* thing
 * that gives the view a size. A native component's intrinsic size comes from a C++ shadow node's
 * `measureContent`, which this library does not implement; Android's own `onMeasure` never gets a
 * say. Without a minimum here Yoga lays the view out at 0x0 and the number vanishes.
 *
 * The estimate is per character rather than a flat average because the bundled font's tabular
 * digits and its separators differ by more than a factor of two, and a four-digit grouped number
 * is a quarter separator by character count.
 */

/** Advance widths as a fraction of font size, from the bundled Sunghyun Sans with `tnum` on. */
const DIGIT_EM = 0.6167;
const SEPARATOR_EM = 0.2541;
const SIGN_EM = 0.36;

/** Room for the transition's own overspill: a dying glyph drifts outward and carries a blur halo. */
const HEADROOM_EM = 0.5;

/** Falls back to this when no fontSize is given, matching the renderer's own default. */
const DEFAULT_FONT_SIZE = 48;

export type Box = { minWidth: number; minHeight: number };

/**
 * Width of [formatted] in font-size units.
 *
 * A custom `fontFamily` makes these advances approximate. They are a *minimum*, and the headroom
 * covers a font a little wider than the bundled one; a much wider one may clip, which is the price
 * of estimating in JS at all.
 */
export function widthInEm(formatted: string): number {
  let em = 0;
  for (const ch of formatted) {
    if (ch >= '0' && ch <= '9') em += DIGIT_EM;
    else if (ch === '-' || ch === '−' || ch === '+') em += SIGN_EM;
    else em += SEPARATOR_EM;
  }
  return em;
}

export function measureBox(
  formatted: string,
  fontSize: number | undefined
): Box {
  const size = fontSize ?? DEFAULT_FONT_SIZE;
  return {
    minWidth: Math.ceil((widthInEm(formatted) + HEADROOM_EM) * size),
    minHeight: Math.ceil(size * 1.5),
  };
}

/**
 * The wider of two boxes.
 *
 * A shrink (9,999 -> 1) re-renders with the narrow value immediately, while the wide outgoing
 * number is still on screen fading out. The box may grow at once but must not follow the value
 * down until the transition has finished, or the departing digits are clipped mid-flight.
 */
export function widest(a: Box, b: Box): Box {
  return {
    minWidth: Math.max(a.minWidth, b.minWidth),
    minHeight: Math.max(a.minHeight, b.minHeight),
  };
}
