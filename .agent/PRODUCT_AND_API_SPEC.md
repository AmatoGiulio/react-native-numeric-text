# Product and API Specification

## API MVP

```tsx
import { NumericText } from 'react-native-numeric-text';

<NumericText
  value={2576}
  locale="en-US"
  style={{ fontSize: 84, fontWeight: '700' }}
/>
```

L'API deve seguire il più possibile la semantica di `Text`, ma l'MVP non deve fingere supporto completo a tutte le props di React Native `Text`.

## Props MVP

```ts
type NumericDirection = 'automatic' | 'up' | 'down';
type ReduceMotionMode = 'system' | 'always' | 'never';

type NumericTextProps = {
  value: number;
  locale?: string;
  direction?: NumericDirection;
  animationDuration?: number;
  reduceMotion?: ReduceMotionMode;
  minimumFractionDigits?: number;
  maximumFractionDigits?: number;
  useGrouping?: boolean;
  style?: TextStyle;
  testID?: string;
  onTransitionStart?: () => void;
  onTransitionEnd?: () => void;
};
```

## Default MVP

```text
locale: sistema o en-US secondo il comportamento scelto e documentato
direction: automatic
animationDuration: valore misurato/validato, non assunto come replica della spring Apple
reduceMotion: system
minimumFractionDigits: 0
maximumFractionDigits: 3
useGrouping: true
```

## Semantica della direzione

- `automatic`: confronta il valore numerico precedente con quello nuovo.
- `up`: forza la direzione visiva crescente.
- `down`: forza la direzione visiva decrescente.
- valori uguali non devono avviare una nuova animazione.
- `NaN` e infinito devono essere rifiutati o gestiti in modo esplicito e testato.

## Scope MVP

Incluso:

- numeri finiti;
- interi positivi e negativi;
- grouping separator;
- parte decimale;
- direzione automatica;
- aggiornamenti rapidi;
- reduce motion;
- style essenziale: font size, weight, family, color, letter spacing, line height quando supportabile correttamente;
- accessibilità come singolo valore testuale.

Non incluso nella prima milestone:

- children arbitrari;
- stringhe numeriche libere;
- attraversamento dei valori intermedi;
- valute e unità con API dedicate;
- animazione per-character configurabile pubblicamente;
- web;
- old architecture;
- supporto a layout multilinea;
- dipendenza da font custom non forniti dall'app consumer.

## Nome nativo

Conservare il nome generato `NumericTextView` salvo ragioni reali. Evitare rename cosmetici nella prima fase perché coinvolgono Codegen, provider iOS e classi Android.

## Misurazione

Il componente deve poter essere usato con dimensioni esplicite nell'MVP. L'intrinsic measurement perfetto può essere una milestone separata se lo scaffold non lo supporta in modo immediato.
