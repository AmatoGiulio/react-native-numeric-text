# react-native-numeric-text

High-fidelity numeric text transitions for React Native, rendered natively on iOS and Android.

`NumericText` is built for counters, balances, prices, scores, dashboards, and any interface where numeric changes are frequent enough that motion becomes part of the product experience.

On **iOS 17+**, the component uses SwiftUI's real `.contentTransition(.numericText())`. On **Android**, it uses a dedicated native renderer designed around the same interaction class: rolling digits, blur and scale, structural changes, interruption, rapid retargeting, and continuous updates.

> **v0.1 targets React Native's New Architecture (Fabric).**

## Demo

[![Android numeric text transition demo](docs/assets/numeric-text-android-demo-poster.png)](docs/assets/numeric-text-android-demo.mp4)

<p align="center"><sub>Google Pixel 9 Pro · Android · click the preview to play the 15-second demo</sub></p>

The example app includes a minimal showcase designed for recording the core behaviour without debug UI: ordinary changes, sustained updates, grouping boundaries, decimal boundaries, and fast reversals.

## Origin

This project started from a simple question:

**Can SwiftUI's `numericText` transition feel just as native on Android?**

The initial spark was [Nathan Schroeder's Expo UI demo](https://x.com/nater02/status/2079903810760081812), which showed how little code is needed to expose SwiftUI's native `numericText` transition on iOS.

I replied that I wanted to reproduce the behaviour on Android as well. `react-native-numeric-text` is the result of that experiment becoming a native rendering project.

The platform strategy is intentionally asymmetric:

- **iOS 17+** uses the system implementation that already exists: SwiftUI `.contentTransition(.numericText())`.
- **Android** uses a custom renderer built specifically for numeric transitions, including structural digit identity, formatted-value layout, interruption, retriggering, direction changes, and long-running interactive updates.

The goal is not to pretend both platforms render text identically. The goal is to give the same React Native component the same class of polished numeric interaction on both platforms.

This project is independent and is not affiliated with Expo or Apple.

## Why numeric text needs its own transition model

Animating a number well is not the same problem as fading one string into another.

A formatted number has structure. Digits can persist while their neighbours change, separators can appear or disappear, fractional digits are anchored differently from integer digits, and a new value can arrive before the previous animation has settled.

A production-quality transition therefore has to remain coherent through cases such as:

```text
9       -> 10
999     -> 1,000
9.99    -> 10.00
12,499  -> 12,500
```

and it has to keep behaving correctly when updates arrive continuously rather than one at a time.

`react-native-numeric-text` treats those cases as one transition system instead of a collection of isolated effects.

## Features

- Native rendering on iOS and Android.
- SwiftUI `.contentTransition(.numericText())` on iOS 17+.
- Dedicated Android numeric-transition renderer.
- Stable interruption and rapid-retarget behaviour.
- Continuous increment/decrement updates without resetting the whole animation.
- Structural handling of integer digits, fractional digits, grouping separators, decimal separators, and signs.
- Locale-aware native number formatting through an `Intl.NumberFormat`-shaped `format` prop.
- Native currency display: symbol, ISO code, or name, with the accounting sign for negatives.
- Native percent display.
- Configurable grouping, integer padding, fractional precision, and significant digits.
- Identical rounding on iOS, Android, and web.
- Automatic, forced-up, and forced-down transition direction.
- System-aware reduced-motion support.
- Android 12+ hardware blur path.
- Android 7-11 compatibility blur path during animation.
- Bundled rounded Android numeric typeface with safe platform-font fallback.
- Correctly formatted static web fallback.

## Installation

```sh
npm install react-native-numeric-text
```

or:

```sh
yarn add react-native-numeric-text
```

The package autolinks through React Native. No manual Android font linking is required.

For iOS, install pods as usual after adding the dependency:

```sh
cd ios
pod install
```

## Quick start

```tsx
import { NumericText } from 'react-native-numeric-text';

export function Balance({ value }: { value: number }) {
  return (
    <NumericText
      value={value}
      style={{
        fontSize: 48,
        fontWeight: '700',
        color: '#111111',
      }}
    />
  );
}
```

The first value is rendered immediately. Later `value` changes transition natively.

## Formatting is part of the transition

Formatting is resolved before the transition is built; separators are not decorative overlays added afterward.

```tsx
<NumericText
  value={1234.5}
  locale="de-DE"
  format={{ useGrouping: true, minimumFractionDigits: 2, maximumFractionDigits: 2 }}
/>
```

This keeps locale-specific punctuation structurally associated with the digits while the value changes.

The `format` prop is a subset of `Intl.NumberFormatOptions`, and each platform resolves it with its own formatter: `NumberFormatter` on iOS, `android.icu` on Android, `Intl` on web. The string is produced where it is drawn, because the renderer animates the structure of a formatted number rather than a string handed to it ready-made.

`format` is an object, so `NumericText` will re-render whenever the parent does unless you keep it stable. Hoist it to module scope or wrap it in `useMemo` if you rely on the memo skipping renders.

### Currency

```tsx
<NumericText value={1234.5} currency="USD" />          // $1,234.50
<NumericText value={1234.5} currency="JPY" />          // ¥1,235
<NumericText value={1234.5} locale="de-DE" currency="EUR" />  // 1.234,50 €
```

`currency` is shorthand for `format={{ style: 'currency', currency }}`. The full form adds how the currency is written and how a negative amount is signed:

```tsx
<NumericText value={1234.5} format={{ style: 'currency', currency: 'USD', currencyDisplay: 'code' }} />
// USD 1,234.50

<NumericText value={1234.5} format={{ style: 'currency', currency: 'USD', currencyDisplay: 'name' }} />
// 1,234.50 US dollars

<NumericText value={-1234.5} format={{ style: 'currency', currency: 'USD', currencySign: 'accounting' }} />
// ($1,234.50)
```

The currency affix takes part in the transition rather than sitting on top of it. It is keyed by its distance from the digits, not by its offset in the string, so `$999` → `$1,000` slides one `$` left instead of destroying it and creating another one, and a trailing `1.234,50 €` keeps its symbol through the same change. Where a locale uses a different decimal mark for money than for plain numbers, the renderers key on the monetary one, so the decimal boundary still holds the fraction digits still.

Fraction digits follow the currency when you do not set them: two for `USD`, none for `JPY`, three for `BHD`.

`currencySign: 'accounting'` applies with `currencyDisplay: 'symbol'`, the one combination both platforms format natively; with `'code'` or `'name'` the standard sign is used. `Intl`'s `currencyDisplay: 'narrowSymbol'` is not offered, because neither platform's native formatter exposes it at the versions this library supports.

### Percent, padding, and significant digits

```tsx
<NumericText value={0.42} format={{ style: 'percent' }} />                  // 42%
<NumericText value={9} format={{ minimumIntegerDigits: 2 }} />              // 09
<NumericText value={1234.5} format={{ maximumSignificantDigits: 3 }} />     // 1,230
```

`minimumIntegerDigits` is what holds a clock at `05:09` and stops a counter changing width as it crosses a power of ten. Significant digits take precedence over fraction digits when either bound is set, matching `Intl`.

### Fractions and rounding

A decimal mark is structural, not punctuation. Integer digits keep their identity from the left, fraction digits from the decimal mark, and the mark itself holds still, so `9.99` → `10.00` moves the digits it has to and leaves the rest alone.

Set `minimumFractionDigits` to hold a fixed number of decimals through a change that would otherwise drop one. Without it, `1.50` renders as `1.5` and the fraction columns restructure under a roll that should only have moved digits:

```tsx
<NumericText value={price} format={{ minimumFractionDigits: 2, maximumFractionDigits: 2 }} />
```

Rounding is half-away-from-zero on both platforms and on web: `2.5` at zero decimals reads as `3` everywhere. That is `Intl`'s default and neither platform's (`NumberFormatter` and ICU both round half-to-even left alone), so it is set explicitly rather than exposed as an option. Two renderers disagreeing about the number they draw is a bug, not a preference.

## Direction

By default, direction follows the numeric change:

```tsx
<NumericText value={score} direction="automatic" />
```

You can force a direction independently of the value:

```tsx
<NumericText value={score} direction="up" />
<NumericText value={score} direction="down" />
```

During rapid updates, automatic direction is resolved against the value the renderer is currently targeting. A retrigger does not have to wait for the previous transition to settle.

## Reduced motion

```tsx
<NumericText value={count} reduceMotion="system" />
```

`"system"` follows the platform accessibility setting. `"always"` disables the transition. `"never"` keeps transitions enabled regardless of the system setting.

## API

### `NumericTextProps`

| Prop | Type | Default | Description |
|---|---|---|---|
| `value` | `number` | required | Number to display. The first render does not animate. |
| `locale` | `string` | `'en-US'` | BCP-47 locale used for native number formatting. |
| `format` | `NumericTextFormat` | `{}` | How to shape the number. See below. |
| `currency` | `string` | none | Shorthand for `format={{ style: 'currency', currency }}`. `format` wins where the two overlap. |
| `direction` | `'automatic' \| 'up' \| 'down'` | `'automatic'` | Direction of the numeric transition. |
| `animationDuration` | `number` | `80` | Android only. Nominal timing input used to scale the native transition; it is not a hard duration clamp. |
| `reduceMotion` | `'system' \| 'always' \| 'never'` | `'system'` | Accessibility behaviour for motion. |
| `useGrouping` | `boolean` | `true` | Shorthand for the same field of `format`. |
| `minimumFractionDigits` | `number` | none | Shorthand for the same field of `format`. |
| `maximumFractionDigits` | `number` | none | Shorthand for the same field of `format`. |
| `style` | `StyleProp<TextStyle>` | none | Text/view style. `fontSize`, `fontWeight`, `fontFamily`, and `color` are forwarded to the native renderer. |
| `testID` | `string` | none | React Native test identifier. |

When `fontSize` or `color` are omitted, the native defaults are `48` and black.

### `NumericTextFormat`

A subset of `Intl.NumberFormatOptions`. Every option is resolved by the platform's own formatter, and means the same thing on both.

| Option | Type | Default | Description |
|---|---|---|---|
| `style` | `'decimal' \| 'currency' \| 'percent'` | `'decimal'` | `'currency'` needs `currency` and falls back to `'decimal'` without it. `'percent'` multiplies by 100. |
| `currency` | `string` | none | ISO 4217 code. |
| `currencyDisplay` | `'symbol' \| 'code' \| 'name'` | `'symbol'` | `$1,234.56`, `USD 1,234.56`, `1,234.56 US dollars`. |
| `currencySign` | `'standard' \| 'accounting'` | `'standard'` | `'accounting'` brackets a negative amount. Applies with `currencyDisplay: 'symbol'`. |
| `useGrouping` | `boolean` | `true` | Enables grouping separators. |
| `minimumIntegerDigits` | `number` | none | Pads with leading zeros to at least this width. |
| `minimumFractionDigits` | `number` | style's own | `0` for a plain number, `0` for a percentage, the currency's count for money. |
| `maximumFractionDigits` | `number` | style's own | `3` for a plain number, `0` for a percentage, the currency's count for money. |
| `minimumSignificantDigits` | `number` | none | Takes precedence over the fraction bounds. |
| `maximumSignificantDigits` | `number` | none | Takes precedence over the fraction bounds. |

`Intl` options that are **not** supported, and why:

| Option | Reason |
|---|---|
| `notation: 'compact'` (`1.2K`) | Both platforms can produce it, but from different CLDR vintages, so they would disagree on the string for the same input. |
| `signDisplay` | Android's `NumberFormatter` is API 30; iOS's `NumberFormatter` has no equivalent. |
| `currencyDisplay: 'narrowSymbol'` | Same. |
| `unit`, `unitDisplay`, `roundingIncrement`, `roundingMode` | Same, except `roundingMode`, which is fixed at half-away-from-zero on purpose. |

## Platform behaviour

| Platform | Behaviour |
|---|---|
| iOS 17+ | Native SwiftUI `.contentTransition(.numericText())`, formatted by `NumberFormatter`. |
| Earlier supported iOS | Native formatting and rendering without the unavailable numeric content transition. |
| Android API 31+ | Native renderer using `RenderNode` and `RenderEffect` for the blur path, formatted by `android.icu`. |
| Android API 24-30 | Same transition model with a temporary software-layer blur path while animating. |
| Web | Correctly formatted static `Text` via `Intl`; numeric transitions are not currently animated. |

The minimum Android SDK is **24**.

## Android architecture

The Android implementation is deliberately not a grid of independent digit `Text` views.

The renderer is built around a small set of invariants:

1. **Typeset the complete formatted line first.** Layout and glyph positions come from the full value before the line is partitioned into logical transition slots.
2. **Keep immutable value rasters.** Outgoing content keeps the pixels that belonged to its original formatted value while incoming content references the new target value.
3. **Use structural identity.** Integer digits are anchored from the left, fractional digits from the decimal boundary, and punctuation receives stable semantic identity. A currency affix, a percent sign and an accounting bracket are keyed by their distance from the digits, so they survive the number growing or losing one.
4. **Preserve history during retriggers.** A new target does not require the previous transition to finish first.
5. **Keep frame work bounded.** Expensive bitmap extraction and per-slot bitmap creation stay out of the normal render/update hot path.
6. **Use analytic motion evaluation.** Native transition state can be evaluated directly for the current frame instead of integrating a simulation with frame-rate-dependent state.

Those constraints let ordinary one-step changes, `999 -> 1,000`, reversals, and sustained press-and-hold updates use the same underlying model.

## Interruption and retargeting

Fast input is a first-class case rather than a stress test added after the renderer was designed.

When a value changes again during an active transition, the renderer keeps the relevant outgoing and incoming history and retargets toward the new formatted value. This avoids collapsing rapid input into a sequence of disconnected fades or restarting the entire line on every update.

The result is intended for real controls where a user may tap repeatedly, hold a button, or reverse direction while motion is still active.

## Typography

### iOS

The default presentation uses the rounded system design on Apple platforms. `fontFamily: 'system'` opts into the plain system design. A font registered by the host application can also be supplied through `style`.

### Android

Android includes a subset of [Sunghyun Sans](https://github.com/anaclumos/sunghyun-sans), an OFL-licensed rounded family used as a redistributable counterpart to the Apple rounded-system presentation. Nine real weights from 100 through 900 are bundled.

```tsx
// Default rounded presentation
<NumericText value={value} style={{ fontSize: 48 }} />

// Platform system font
<NumericText
  value={value}
  style={{ fontSize: 48, fontFamily: 'system' }}
/>

// Font registered by the host application
<NumericText
  value={value}
  style={{ fontSize: 48, fontFamily: 'Inter' }}
/>
```

The bundled Android subset contains Latin-script numeric-formatting glyphs: digits, the separators and signs a locale formats with, the currency symbols, and the Latin letters an ISO code or a currency name needs. That is about 33 KB a weight, 300 KB for the nine.

Coverage is checked against the characters the current format will actually draw, not against the locale alone, so a currency symbol or a currency name is part of the question. When any of them is missing from the bundled face, the renderer falls back to the platform font rather than drawing missing-glyph boxes.

The full font license is included at `android/src/main/assets/fonts/OFL.txt`.

## Parity model

The target is **behavioural and perceptual parity**, not pixel-identical output across operating systems.

Apple and Android use different text engines, rasterizers, fonts, and rendering stacks. The useful cross-platform contract is therefore the behaviour of the transition: structural continuity, direction, interruption, retriggering, formatting changes, and motion quality.

On iOS 17+, SwiftUI itself is the reference implementation. Android is tuned against that behaviour while remaining native to Android's rendering stack.

## Performance

The Android renderer is designed for continuously changing values, not only isolated showcase transitions.

The normal animation path avoids per-frame bitmap extraction and per-slot bitmap creation. API 31+ uses cached native rendering primitives and `RenderEffect`; API 24-30 temporarily switches only the numeric view to the software blur path while it is animating, then restores the normal layer state.

This matters for counters with press-and-hold controls, live balances, scores, timers, and rapidly updating dashboards.

## Example app

The repository includes an `example/` application used both as a public showcase and as a development harness.

The public screen is intentionally minimal: one number, two controls, and a deterministic demo sequence. A separate lab remains available for deeper validation without exposing diagnostic UI in recordings or screenshots.

The example application is part of the repository but intentionally excluded from the published npm package.

## Release verification

Before a release candidate is published, the repository verification gate runs:

```sh
yarn install --immutable
yarn check
yarn prepare
npm pack --dry-run
```

The generated package is then tested as a real tarball from a clean consumer project rather than relying only on workspace resolution.

## License

MIT.

The bundled Sunghyun Sans assets are distributed under the SIL Open Font License 1.1; see `android/src/main/assets/fonts/OFL.txt`.
