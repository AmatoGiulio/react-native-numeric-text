# react-native-numeric-text

Native numeric text content transitions for React Native.

`NumericText` uses SwiftUI's real `.contentTransition(.numericText())` on iOS 17+ and a native Android renderer tuned to reproduce the same rolling, blur, scale, structural changes, interruption and rapid-retarget behaviour.

## Installation

```sh
npm install react-native-numeric-text
```

or:

```sh
yarn add react-native-numeric-text
```

The package autolinks through React Native. No manual font linking is required.

The v0.1 native component targets React Native's **New Architecture (Fabric)**.

## Usage

```tsx
import { NumericText } from 'react-native-numeric-text';

<NumericText
  value={count}
  style={{ fontSize: 48, color: 'tomato', fontWeight: '600' }}
/>;
```

The first value is drawn without a transition. Later `value` changes animate.

## Props

| prop | default | description |
|---|---:|---|
| `value` | required | Number to display. |
| `locale` | `'en-US'` | BCP-47 locale used for decimal and grouping separators. |
| `direction` | `'automatic'` | `'automatic'`, `'up'`, or `'down'`. |
| `animationDuration` | `80` | Android only. Scales the native spring timing; it does not clamp the transition to an exact duration. |
| `reduceMotion` | `'system'` | `'system'`, `'always'`, or `'never'`. |
| `useGrouping` | `true` | Enables grouping separators. |
| `minimumFractionDigits` | `0` | Minimum number of fractional digits. |
| `maximumFractionDigits` | `3` | Maximum number of fractional digits. |
| `style` | — | Normal React Native text/view style. `fontSize`, `fontWeight`, `fontFamily`, and `color` are forwarded to the native text renderer. Native font size defaults to `48` and color to black when omitted. |
| `testID` | — | React Native test identifier. |

### Direction

With `direction="automatic"`, increasing values roll in the increasing direction and decreasing values roll in the opposite direction. During rapid updates the direction is resolved against the value the renderer is currently targeting, not only the last fully settled value.

```tsx
<NumericText value={score} direction="automatic" />
<NumericText value={score} direction="up" />
<NumericText value={score} direction="down" />
```

### Formatting

```tsx
<NumericText
  value={1234.5}
  locale="de-DE"
  useGrouping
  minimumFractionDigits={2}
  maximumFractionDigits={2}
/>
```

The formatter is native on iOS and Android. Web uses the equivalent JavaScript locale formatting path.

### Reduce motion

`reduceMotion="system"` follows the platform accessibility setting. `"always"` disables the transition. `"never"` keeps it enabled regardless of the system setting.

## Platforms

### iOS

On **iOS 17 and newer**, the component hosts SwiftUI and uses Apple's real:

```swift
.contentTransition(.numericText(countsDown: ...))
```

with SwiftUI's spring animation.

On older supported iOS versions the number still renders and formats natively, but changes are not given the numeric content transition because the API does not exist before iOS 17.

`animationDuration` is intentionally Android-only: SwiftUI's numeric transition is driven by its animation transaction and does not expose an equivalent duration property.

### Android

Android uses a native renderer built around:

- full-line shaping before slot extraction;
- one immutable raster per formatted value;
- stable logical digit/separator identity;
- persistent transition entries for interruption and rapid retargeting;
- native spring integration;
- hardware `RenderNode` + `RenderEffect` blur on Android 12 / API 31+;
- a temporary software-layer blur fallback while animating on API 24–30.

The Android minimum SDK is **24**.

### Web

Web renders the correctly formatted number with React Native `Text`. It does not currently animate the numeric transition.

## Fonts

Typography is platform-native where that gives the closest result to SwiftUI.

### iOS

The default is the rounded system design (SF on Apple platforms). `fontFamily: 'system'` opts back into the plain system design. A registered custom family can also be supplied through `style`.

### Android

Android ships a subset of [Sunghyun Sans](https://github.com/anaclumos/sunghyun-sans), an OFL-licensed rounded family chosen as a redistributable stand-in for Apple's rounded system typography. Nine real weights from 100 through 900 are included.

```tsx
// Bundled Android default / rounded system on iOS
<NumericText value={n} style={{ fontSize: 48 }} />

// Platform system font
<NumericText value={n} style={{ fontSize: 48, fontFamily: 'system' }} />

// A font registered by your application
<NumericText value={n} style={{ fontSize: 48, fontFamily: 'Inter' }} />
```

The Android subset contains Latin-script numeric formatting glyphs. If the active locale needs digits not present in the bundled face, the renderer falls back to the platform font instead of drawing missing-glyph boxes.

The bundled font license is included at `android/src/main/assets/fonts/OFL.txt`.

## Notes on parity

The goal is behavioural and perceptual parity with SwiftUI numeric text, not pixel-identical output across platforms. Apple and Android use different text rasterizers and, by default, different redistributable fonts.

The Android implementation preserves interrupted motion and structural changes such as `999 -> 1,000`, including grouping separators and newly appearing digits, rather than treating those cases as separate animation modes.

## Development

Common checks:

```sh
yarn typecheck
yarn lint
yarn test
yarn check
```

The example app lives in `example/`.

## License

MIT, except the bundled Sunghyun Sans font files in `android/src/main/assets/fonts/`, which are distributed under the SIL Open Font License 1.1. The full OFL text ships with the font assets.
