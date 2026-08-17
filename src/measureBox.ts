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
 * is a quarter separator by character count. Once a number can carry a currency (`$1,234.56`,
 * `1.234,56 €`, `USD 1,234.56`) the spread is wider still, so symbols and letters are charged
 * separately from punctuation rather than being lumped in with the comma.
 */

/** Advance widths as a fraction of font size, from the bundled Sunghyun Sans with `tnum` on. */
const DIGIT_EM = 0.6167;
const SEPARATOR_EM = 0.2541;
const SIGN_EM = 0.36;

/** Currency symbols, the percent sign, and the letters of an ISO currency code. */
const GLYPH_EM = 0.62;

/** Room for the transition's own overspill: a dying glyph drifts outward and carries a blur halo. */
const HEADROOM_EM = 0.5;

/**
 * JS and native do not necessarily shape with the same face. iOS' default rounded system font in
 * particular accumulates more advance than the bundled-font constants above on a long currency
 * line. Fabric cannot ask that native Text for its intrinsic width, so reserve a small cumulative
 * allowance per Unicode scalar instead of hiding all metric drift in one fixed headroom value.
 *
 * 0.06 em per scalar is the measured margin that keeps `BHD 1,000.000` at fontSize 38 from
 * truncating on iOS while remaining modest for the short, digit-only cases this component targets.
 */
const NATIVE_METRIC_DRIFT_EM = 0.06;

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
    else if (isNarrow(ch)) em += SEPARATOR_EM;
    else em += GLYPH_EM;
  }
  return em;
}

/**
 * Whether [ch] is punctuation or a space rather than something with ink to spare.
 *
 * The separators a formatter emits are few and known: the comma, the stop, the apostrophe forms,
 * the middle dot, the Arabic marks, and the several fixed-width spaces a French or Swiss locale
 * groups with. They are therefore listed rather than derived. Anything else in a formatted number is a
 * digit, a sign, or a symbol or letter that carries a currency, and those are charged as glyphs.
 */
function isNarrow(ch: string): boolean {
  return NARROW.has(ch);
}

const NARROW = new Set([
  ',',
  '.',
  "'",
  '’', // right single quote, the Swiss grouping separator
  '·', // middle dot
  '٫', // Arabic decimal separator
  '٬', // Arabic thousands separator
  '，', // fullwidth comma
  '．', // fullwidth stop
  '(',
  ')',
  ' ',
  ' ', // no-break space
  ' ', // figure space
  ' ', // punctuation space
  ' ', // thin space
  ' ', // narrow no-break space
]);

export function measureBox(
  formatted: string,
  fontSize: number | undefined
): Box {
  const size = fontSize ?? DEFAULT_FONT_SIZE;
  const scalarCount = Array.from(formatted).length;
  return {
    minWidth: Math.ceil(
      (widthInEm(formatted) +
        HEADROOM_EM +
        scalarCount * NATIVE_METRIC_DRIFT_EM) *
        size
    ),
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
