# Reference Sources

## Reference primaria: Apple

### SwiftUI `ContentTransition`

https://developer.apple.com/documentation/swiftui/contenttransition

Contratto pubblico rilevante:

- una content transition si applica al contenuto all'interno di una singola view;
- non equivale alla transizione di inserimento/rimozione della view;
- ha effetto all'interno di una transazione a cui è associata una `Animation`.

### `numericText(countsDown:)`

https://developer.apple.com/documentation/swiftui/contenttransition/numerictext(countsdown:)

Contratto pubblico rilevante:

- è destinata a `Text` che visualizzano testo numerico;
- produce una transizione non standard pensata per caratteri numerici che aumentano o diminuiscono;
- `countsDown` specifica che il numero sta diminuendo.

### `numericText(value:)`

https://developer.apple.com/documentation/swiftui/contenttransition/numerictext(value:)

Contratto pubblico rilevante:

- riceve il valore numerico rappresentato dal `Text`;
- usa la differenza tra valore precedente e nuovo per determinare la direzione dell'animazione.

### Limite della documentazione

Apple non pubblica:

- algoritmo di matching dei glifi;
- curve interne;
- distanza esatta di viaggio;
- trattamento preciso di separatori e cambi di lunghezza;
- uso interno di blur, mask, drawing group o snapshot;
- comportamento con aggiornamenti interrotti.

Questi aspetti devono essere osservati sperimentalmente. Non presentarli come informazioni Apple ufficiali.

## Reference Expo / post X

Codice demo:

https://github.com/SchroederNathan/expo-ui-examples/blob/main/src/examples/numeric-transitions/animated-number.tsx

Post X fornito dal proprietario del progetto:

https://x.com/nater02/status/2079903811838079059

La demo combina quattro elementi distinti:

1. font rounded bold;
2. `contentTransition('numericText', { countsDown })`;
3. `Animation.spring()` associata al valore;
4. cifre monospaziate/tabulari tramite `monospacedDigit()`.

Non attribuire alla sola `numericText` tutto ciò che può derivare dalla spring esterna o dalle metriche del font.

## Reference React Native

### Fabric Native Components

https://reactnative.dev/docs/fabric-native-components-introduction

Principi:

- definire una spec TypeScript/Flow;
- lasciare a Codegen la generazione del glue code;
- implementare la view nativa;
- usare Fabric per componenti che rappresentano una view nativa.

### Codegen

https://reactnative.dev/docs/the-new-architecture/using-codegen

Convenzioni rilevanti:

- le specifiche Fabric terminano con `NativeComponent`;
- `codegenConfig.type` può essere `components` o `all`;
- il codice generato è legato alla versione React Native e normalmente non va modificato manualmente.

## Regole di consultazione

- Preferire fonti primarie e codice effettivo.
- Non usare blog o snippet come prova dell'algoritmo Apple.
- Ogni parametro visivo iniziale deve essere registrato come valore sperimentale, non come costante “corretta”.
