# iOS Reference Specification

## Ruolo di iOS

iOS è contemporaneamente:

1. implementazione reale della libreria;
2. reference osservabile per Android.

## Implementazione richiesta

Usare SwiftUI e la primitive di sistema, non replicarla manualmente:

```swift
Text(formattedText)
  .font(...)
  .monospacedDigit()
  .contentTransition(.numericText(value: numericValue))
  .animation(animation, value: numericValue)
```

La variante `countsDown:` può essere usata quando serve una direzione esplicita:

```swift
.contentTransition(.numericText(countsDown: countsDown))
```

Quando `direction == automatic`, preferire la semantica `value:` se compatibile con il deployment target. Per `up`/`down`, usare una strategia coerente e documentata.

## Hosting dentro Fabric

Lo scaffold iOS generato va rispettato. L'agente deve:

1. ispezionare la classe `NumericTextView` generata;
2. mantenere il provider Codegen configurato;
3. ospitare SwiftUI nella view nativa senza introdurre un Expo Module;
4. aggiornare lo stato SwiftUI quando cambiano le props Fabric;
5. gestire layout e lifecycle correttamente;
6. non creare un nuovo hosting controller a ogni aggiornamento del valore.

## Stato SwiftUI

Possibile direzione:

- `ObservableObject` o stato equivalente posseduto dalla view nativa;
- proprietà aggiornabili: value, formattedText, direction, style essenziale;
- hosting creato una volta;
- aggiornamenti sul main thread.

La struttura esatta deve adattarsi al template generato e al deployment target.

## Animazione

La demo Expo usa `Animation.spring()`. Non assumere che la spring di SwiftUI abbia una corrispondenza 1:1 con una `SpringAnimation` Android senza misurazioni.

Per il laboratorio iOS servono modalità controllabili:

```text
system spring
linear con durata nota
easeInOut con durata nota
animazioni disabilitate
```

Questo permette di separare la contribution di `numericText` dalla curva esterna.

## Formattazione

La stringa visualizzata e il valore passato a `.numericText(value:)` devono rappresentare lo stesso numero.

- usare locale coerente;
- grouping e fraction digits coerenti con Android;
- `monospacedDigit()` nella demo reference;
- font rounded bold nella replica del post.

## Verifica

Se l'agente lavora su Windows:

- può preparare codice iOS solo se il cambiamento è minimo e ragionato;
- deve dichiarare che non è stato compilato;
- non deve modificare aggressivamente podspec, bridging o provider senza possibilità di verifica;
- la priorità operativa resta Android.
