import { describe, expect, it } from '@jest/globals';
import { StyleSheet } from 'react-native';
import { resolveTextStyle } from '../resolveTextStyle';

describe('resolveTextStyle', () => {
  it('reads a plain object style', () => {
    expect(resolveTextStyle({ fontSize: 48, color: 'tomato' })).toMatchObject({
      fontSize: 48,
      textColor: 'tomato',
    });
  });

  // The regression this function exists for: an array style used to resolve to nothing, so the
  // number drew at the renderer's own defaults while the caller's style was silently ignored.
  it('flattens an array style, with later entries winning', () => {
    const styles = StyleSheet.create({
      base: { fontSize: 16, color: 'black' },
      big: { fontSize: 48 },
    });
    expect(resolveTextStyle([styles.base, styles.big])).toMatchObject({
      fontSize: 48,
      textColor: 'black',
    });
  });

  it('flattens nested arrays and skips falsy entries', () => {
    expect(
      resolveTextStyle([[{ fontSize: 12 }], false, null, [{ fontSize: 20 }]])
    ).toMatchObject({ fontSize: 20 });
  });

  // React Native accepts fontWeight as a number; the native side wants a string either way.
  it('normalises a numeric fontWeight to a string', () => {
    expect(resolveTextStyle({ fontWeight: 600 }).fontWeight).toBe('600');
    expect(resolveTextStyle({ fontWeight: '600' }).fontWeight).toBe('600');
    expect(resolveTextStyle({ fontWeight: 'bold' }).fontWeight).toBe('bold');
  });

  it('passes a non-string colour through rather than dropping it', () => {
    expect(resolveTextStyle({ color: 0xff0000 }).textColor).toBe(0xff0000);
  });

  it('returns nothing set for an absent or empty style', () => {
    for (const style of [undefined, null, false, {}, []] as const) {
      expect(resolveTextStyle(style)).toEqual({
        fontSize: undefined,
        fontWeight: undefined,
        fontFamily: undefined,
        textColor: undefined,
      });
    }
  });

  it('keeps the properties it does not own out of the result', () => {
    const resolved = resolveTextStyle({
      fontSize: 20,
      letterSpacing: 4,
      opacity: 0.5,
    });
    expect(Object.keys(resolved).sort()).toEqual([
      'fontFamily',
      'fontSize',
      'fontWeight',
      'textColor',
    ]);
  });
});
