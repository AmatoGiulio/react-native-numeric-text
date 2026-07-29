# react-native-numeric-text

Native numeric text content transitions for React Native.

## Installation


```sh
npm install react-native-numeric-text
```


## Usage


```js
import { NumericTextView } from "react-native-numeric-text";

// ...

<NumericTextView color="tomato" />
```


## Font

The view ships with its own typeface and uses it by default, so a number looks the same on every
device instead of following whatever the vendor set as the system sans.

That default is [Sunghyun Sans](https://github.com/anaclumos/sunghyun-sans) — an open rounded face
in the spirit of SF Pro Rounded, under the SIL Open Font License 1.1. It is the closest freely
redistributable stand-in for the face this library's transition is modelled on; Android's own
Roboto is further from it. Digits are drawn with the `tnum` feature on, so every digit occupies the
same advance and the still columns of a number do not shift while one of them rolls.

Only the glyphs a formatted number can use are bundled — digits, separators, signs — which is nine
weights in about 100 KB. Nothing needs linking or `react-native.config.js`: the files live in the
library's Android assets and are merged into your APK.

Set `fontFamily` in the style to change it:

```js
// the bundled Sunghyun Sans (default)
<NumericText value={n} style={{ fontSize: 48 }} />

// the platform's own font, as before this was bundled
<NumericText value={n} style={{ fontSize: 48, fontFamily: 'system' }} />

// any family you have registered in your app
<NumericText value={n} style={{ fontSize: 48, fontFamily: 'Inter' }} />
```

`fontWeight` picks among the nine real cuts (100–900) rather than synthesising a bold, so `500` and
`600` are distinct rather than both rounding to regular.

A locale whose numerals fall outside the bundled subset — `ar-EG`, `hi-IN` and the like — falls
back to the system font on its own, so it renders its own digits rather than tofu.

To regenerate the bundled files from upstream, run `.agent/tools/subset_font.sh`.

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

MIT, except the bundled font: `android/src/main/assets/fonts/` is Sunghyun Sans, © its authors,
under the SIL Open Font License 1.1 (the full text ships alongside it as `OFL.txt`).

---

Made with [create-react-native-library](https://github.com/callstack/react-native-builder-bob)
