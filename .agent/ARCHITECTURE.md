# Architecture

## Vista generale

```text
React props
   ↓
Codegen Fabric spec
   ↓
NumericTextView native component
   ├── iOS: SwiftUI Text + ContentTransition.numericText
   └── Android: custom numeric text renderer + native animation driver
```

## Principio di isolamento

JavaScript comunica intenti e valori. Non orchestra i frame.

Il lato JS deve:

- validare props dove utile;
- esporre una API dichiarativa;
- passare dati tipizzati al componente Codegen;
- non duplicare lo stato dell'animazione;
- non creare una view React per ogni cifra.

Il lato nativo deve:

- conservare valore visualizzato e target;
- formattare coerentemente il numero;
- calcolare il piano di transizione;
- disegnare e animare;
- gestire interrupt, detach e reduce motion;
- emettere start/end una sola volta per transizione logica.

## iOS

Usare la primitive reale SwiftUI:

```swift
Text(formattedValue)
  .monospacedDigit()
  .contentTransition(.numericText(value: numericValue))
  .animation(animation, value: numericValue)
```

La view Fabric iOS può ospitare una view SwiftUI. Non implementare un renderer custom iOS come prima scelta.

## Android

Implementare una custom `View`/`ViewGroup` Fabric-backed in Kotlin. Il renderer non deve dipendere da child React.

Componenti concettuali suggeriti, da introdurre solo quando necessari:

```text
NumericTextView
NumericFormatter
NumericLayoutEngine
NumericTransitionPlanner
NumericAnimationDriver
NumericRenderer
```

Non creare questa suddivisione meccanicamente. La prima verticale può essere più compatta; estrarre classi quando responsabilità e test lo giustificano.

## Stato minimo Android

```text
settledValue
settledFormattedText
currentTargetValue
transitionPlan
animationProgress
isRunning
```

Per interrupt avanzati potrebbe servire una rappresentazione dello stato visivo corrente per slot, non solo old/new.

## Clock

Usare un solo driver nativo per tutte le proprietà di un aggiornamento. Su Android, `ValueAnimator` è una scelta iniziale appropriata.

- frazione base lineare 0..1;
- curve specifiche calcolate per posizione, alpha, blur e layout;
- un solo callback per frame;
- atterraggio esplicito sui valori finali;
- cleanup idempotente;
- callback finale exactly-once.

## Threading

- props e view mutation sul main/UI thread;
- nessun lavoro pesante per frame;
- formatter e layout devono evitare allocazioni ripetute non necessarie;
- nessuna chiamata JS per frame.

## Compatibilità

- New Architecture only per l'MVP;
- Android e iOS;
- example Expo development build come consumer;
- nessun Expo Module runtime richiesto;
- nessun Nitro runtime richiesto.
