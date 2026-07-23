# Architectural Decisions

## ADR-001 — Fabric Native Component puro

**Stato:** accettato

La libreria usa un Fabric Native Component generato con `create-react-native-library`.

Motivazione:

- il prodotto è una view nativa;
- il driver deve rimanere nativo;
- non servono chiamate JSI ad alta frequenza;
- evitare dipendenza Expo runtime;
- evitare complessità Nitro non giustificata;
- massima integrazione con React Native New Architecture.

## ADR-002 — iOS delega a SwiftUI

**Stato:** accettato

Su iOS usare `ContentTransition.numericText` reale. Non ricreare manualmente un algoritmo che il sistema già offre.

## ADR-003 — Android custom renderer

**Stato:** accettato

Android richiede una replica custom nativa. Il punto di partenza è un renderer Kotlin controllato da un singolo driver, non child React animati.

## ADR-004 — Android first, evidence driven

**Stato:** accettato

La priorità è produrre una replica Android verificabile. I dettagli non documentati di Apple vengono trattati come ipotesi sperimentali.

## ADR-005 — Nessuna dipendenza grafica iniziale

**Stato:** accettato

Non aggiungere Skia, Compose, Reanimated o librerie di animazione. Prima dimostrare che Canvas/TextPaint/ValueAnimator non bastano.

## Decisioni aperte

- whole-string vs changed-run/glyph rendering;
- strategia blur API 31+;
- modello preciso per interrupt;
- intrinsic measurement;
- deployment target iOS per `.numericText(value:)`;
- formatter condiviso semanticamente tra piattaforme;
- mapping completo di React Native `TextStyle`.
