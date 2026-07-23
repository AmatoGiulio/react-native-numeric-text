# AGENTS.md

## Missione

Costruire `react-native-numeric-text`, una libreria React Native New Architecture basata su un Fabric Native Component che:

- su iOS utilizza la primitive reale SwiftUI `ContentTransition.numericText`;
- su Android replica il comportamento visivo e semantico di SwiftUI `numericText` il più fedelmente possibile;
- esegue layout, rendering e animazione sul lato nativo;
- espone una API React dichiarativa, piccola e prevedibile;
- include una demo comparativa e una pipeline di validazione frame-by-frame.

La priorità attuale è Android. iOS è la reference funzionale e visiva, non una reinterpretazione custom.

## Regole non negoziabili

1. Non inventare il comportamento interno di SwiftUI come fatto certo. Apple documenta il contratto pubblico, non l'algoritmo interno.
2. Ogni ipotesi percettiva deve essere marcata come ipotesi e validata contro registrazioni della reference iOS.
3. Non implementare un semplice crossfade dell'intera stringa e dichiararlo equivalente.
4. Non animare dal thread JavaScript e non inviare aggiornamenti per frame attraverso il bridge.
5. Non usare Reanimated, Animated, Skia, Compose o Expo Modules per nascondere una implementazione Fabric incompleta.
6. Non convertire il progetto in Nitro Modules o Expo Modules.
7. Non modificare lo scaffold, le configurazioni Codegen o i nomi nativi senza prima ispezionare la struttura generata.
8. Non aggiungere dipendenze runtime senza una motivazione tecnica documentata.
9. Non fare refactor estranei alla milestone corrente.
10. Non dichiarare iOS verificato se l'ambiente non può compilarlo o eseguirlo.
11. Mantieni Android e iOS semanticamente coerenti, ma non forzare una implementazione identica: iOS deve delegare a SwiftUI.
12. Prima di ogni intervento, crea o aggiorna un piano con file coinvolti, rischi e criteri di verifica.

## Metodo di lavoro

Per ogni milestone:

1. Ispeziona lo stato del repository e gli script disponibili.
2. Esegui i controlli baseline prima di modificare codice.
3. Scrivi una breve analisi dei file e del flusso nativo esistente.
4. Definisci un piano minimo.
5. Implementa una singola verticale completa.
6. Esegui typecheck, lint, test e build Android disponibili.
7. Riporta esattamente cosa è stato verificato e cosa no.
8. Aggiorna `.agent/DECISIONS.md` solo per decisioni architetturali reali.

## Ordine delle priorità

1. Correttezza dell'architettura Fabric e lifecycle nativo.
2. Fedeltà percettiva ad Apple `numericText`.
3. Stabilità con aggiornamenti rapidi e interruzioni.
4. Correttezza tipografica, locale e layout.
5. Performance e allocazioni per frame.
6. Ergonomia API.
7. Estensioni e personalizzazione.

## Comandi

Non assumere i nomi degli script. Esegui prima:

```bash
yarn run
```

Comandi probabili, da confermare nel repository:

```bash
yarn
yarn typecheck
yarn lint
yarn test
yarn example android
```

Su Windows usa i comandi Gradle compatibili con PowerShell quando necessario.

## Output richiesto all'agente

Al termine di ogni iterazione restituisci:

- sintesi del comportamento implementato;
- file modificati;
- decisioni e motivazioni;
- comandi eseguiti e relativi risultati;
- limitazioni note;
- differenze ancora osservabili rispetto alla reference iOS;
- prossimo esperimento consigliato.
