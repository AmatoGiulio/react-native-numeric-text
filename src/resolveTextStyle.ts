import {
  StyleSheet,
  type ColorValue,
  type StyleProp,
  type TextStyle,
} from 'react-native';

/** The text properties the native renderer draws with, pulled out of a normal RN text style. */
export type ResolvedTextStyle = {
  fontSize?: number;
  fontWeight?: string;
  fontFamily?: string;
  textColor?: ColorValue;
};

/**
 * Reads the glyph-affecting properties out of a `style` prop.
 *
 * The native view draws its own text, so it needs these as props rather than as view styles. Three
 * things here are easy to get wrong and were:
 *
 * `style` is a `StyleProp`, not an object — it can be an array, a registered style ID, `false`, or
 * nested arrays. Reading `.fontSize` off it directly finds nothing for anyone passing
 * `style={[styles.base, styles.big]}`, and the number silently draws at the renderer's own
 * defaults. It has to go through `StyleSheet.flatten`.
 *
 * `fontWeight` is legally a number in React Native (`fontWeight: 600`) as well as a string. A
 * string-only check dropped the numeric form, which matters now that the bundled font has nine
 * real cuts to choose between rather than two.
 *
 * `color` can be a string, a processed colour number, or an opaque `PlatformColor` object. All
 * three are valid `ColorValue`s that codegen knows how to convert, so it is passed through rather
 * than filtered down to strings.
 */
export function resolveTextStyle(
  style: StyleProp<TextStyle>
): ResolvedTextStyle {
  const flat = StyleSheet.flatten(style) as TextStyle | undefined;
  if (!flat) return {};

  const { fontSize, fontWeight, fontFamily, color } = flat;

  return {
    fontSize: typeof fontSize === 'number' ? fontSize : undefined,
    fontWeight:
      typeof fontWeight === 'string'
        ? fontWeight
        : typeof fontWeight === 'number'
          ? String(fontWeight)
          : undefined,
    fontFamily: typeof fontFamily === 'string' ? fontFamily : undefined,
    textColor: color ?? undefined,
  };
}
