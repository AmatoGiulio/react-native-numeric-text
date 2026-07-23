# PROMPT — Phase 1: Foundation + Android Reference Replica

Sei dentro il repository `react-native-numeric-text`, appena generato con `create-react-native-library` come **Fabric view**. Lo scaffold compila già correttamente su Android. Non rigenerare il progetto e non convertirlo a Expo Modules, Turbo Module standalone o Nitro Modules.

Leggi integralmente, in questo ordine:

1. `.agent/AGENTS.md`
2. `.agent/PROJECT_CONTEXT.md`
3. `.agent/REFERENCE_SOURCES.md`
4. `.agent/PRODUCT_AND_API_SPEC.md`
5. `.agent/ARCHITECTURE.md`
6. `.agent/ANDROID_IMPLEMENTATION_SPEC.md`
7. `.agent/IOS_REFERENCE_SPEC.md`
8. `.agent/DEMO_SPEC.md`
9. `.agent/TEST_MATRIX.md`
10. `.agent/PERFORMANCE_AND_VALIDATION.md`
11. `.agent/DECISIONS.md`

## Contesto essenziale

Il progetto nasce da una sfida pubblica su X. Nathan Schroeder ha mostrato una demo Expo UI che usa SwiftUI:

```tsx
contentTransition('numericText', { countsDown })
animation(Animation.spring(), value)
monospacedDigit()
```

Reference:

- post: https://x.com/nater02/status/2079903811838079059
- codice: https://github.com/SchroederNathan/expo-ui-examples/blob/main/src/examples/numeric-transitions/animated-number.tsx
- Apple `ContentTransition`: https://developer.apple.com/documentation/swiftui/contenttransition
- Apple `numericText(countsDown:)`: https://developer.apple.com/documentation/swiftui/contenttransition/numerictext(countsdown:)
- Apple `numericText(value:)`: https://developer.apple.com/documentation/swiftui/contenttransition/numerictext(value:)
- reference architetturale secondaria: https://github.com/AmatoGiulio/react-native-morph

Giulio ha risposto pubblicamente che avrebbe provato a replicare il comportamento su Android. Questo repository deve diventare quella implementazione.

## Obiettivo della fase

Produrre una prima verticale completa e verificabile:

```tsx
<NumericText value={2576} />
```

che:

- usa realmente il Fabric Native Component generato;
- renderizza il numero nativamente su Android;
- anima nativamente gli aggiornamenti crescenti e decrescenti;
- replica almeno il caso hero `2,576 ↔ 2,577`;
- include la demo con pulsanti `−` e `+` simile al post;
- mantiene lo scaffold iOS compilabile senza inventare una replica custom;
- prepara l'iOS path per delegare a SwiftUI `ContentTransition.numericText`, ma non deve compromettere Android né dichiarare verifiche iOS non eseguite.

## Vincoli

- Android è la priorità.
- Nessun progress animato dal JS thread.
- Nessun `Animated.Text`, Reanimated o Skia.
- Nessuna view React per cifra.
- Nessuna dipendenza runtime nuova senza approvazione tecnica esplicita.
- Non copiare `react-native-morph`: usa solo pattern pertinenti dopo averne ispezionato il codice.
- Non dichiarare che Apple anima per singolo glifo senza prova.
- Non implementare ancora currency, percentuali o children arbitrari.
- Non cambiare il package Android `com.numerictext` in questa fase.
- Non cambiare `codegenConfig.type: "all"` solo perché il progetto contiene una view.
- Non modificare file generati da Codegen.

## Sequenza obbligatoria

### 1. Audit prima delle modifiche

Ispeziona:

- `package.json` e scripts;
- spec `*NativeComponent.ts`;
- wrapper/export TypeScript;
- classi Android view manager/view;
- classi iOS e mapping component provider;
- example app;
- podspec/Gradle solo per comprendere il setup.

Esegui i controlli baseline disponibili e annota i risultati.

Prima di scrivere codice, restituisci nel log di lavoro un piano con:

- file da modificare;
- API minima proposta;
- flusso props → view → renderer;
- strategia Android baseline;
- rischi Codegen/Fabric;
- verifiche previste.

Poi procedi senza attendere conferma, purché il piano rispetti queste specifiche.

### 2. API minima TypeScript

Trasforma il placeholder generato in una API pubblica:

```tsx
<NumericText
  value={value}
  locale="en-US"
  direction="automatic"
  animationDuration={500}
  style={styles.number}
/>
```

Props minime della fase:

```ts
value: Double
direction: 'automatic' | 'up' | 'down'
locale: string
animationDuration: Double
useGrouping: boolean
minimumFractionDigits: Int32
maximumFractionDigits: Int32
reduceMotion: 'system' | 'always' | 'never'
```

Aggiungi eventi start/end solo se il template Codegen li supporta senza allargare troppo la fase. In caso contrario documentali come follow-up.

Mantieni `style`/`ViewProps` compatibili con lo scaffold. Per font size/color/weight scegli la via meno rischiosa supportata dal template: props esplicite o parsing delle props generate. Non fingere supporto completo a `TextStyle`.

### 3. Android static renderer

Prima rendi correttamente il valore finale senza animazione:

- formatter locale;
- grouping;
- fraction digits;
- font size/weight/color minimi;
- baseline e centratura stabili;
- content description come singolo testo.

Usa Kotlin e il normale lifecycle della view Fabric generata.

### 4. Android transition baseline

Implementa una baseline **whole-text old/new** controllata da un singolo `ValueAnimator`:

- old e new disegnati nello stesso custom canvas/view;
- clip ai bounds;
- up: old esce verso l'alto, new entra dal basso;
- down: old esce verso il basso, new entra dall'alto;
- alpha interpolata;
- distanza relativa alle font metrics/line height;
- stato finale atterra esattamente a progress 1;
- cleanup exactly-once;
- cancel/detach sicuri;
- same value non anima;
- update durante animazione coalesciuto almeno all'ultimo target.

Questa è una baseline sperimentale. Struttura il codice affinché un futuro planner per changed runs/glifi possa sostituire il whole-text renderer senza riscrivere il componente Fabric.

### 5. Blur

Non partire dal blur.

Dopo che movimento, alpha e lifecycle funzionano:

- valuta un leggero blur al centro solo su Android API 31+;
- nessuna allocazione per frame;
- nessun flash nero;
- fallback pulito senza blur;
- se il blur rende la fase instabile, lascialo disattivato e documenta un esperimento successivo.

La fase può essere considerata valida anche senza blur se la pipeline base è robusta. Non simulare un risultato falso con overlay arbitrari.

### 6. Demo

Sostituisci la demo placeholder con una schermata ispirata al post:

```text
        2,576

     [ − ] [ + ]
```

Requisiti:

- `useState(2576)`;
- incremento/decremento di 1;
- font grande bold rounded o il più vicino disponibile;
- layout minimale;
- nessuna animazione JS;
- aggiungi sotto una piccola sezione test con almeno:
  - `999 → 1,000`;
  - `1,000 → 999`;
  - rapid updates;
  - toggle reduce motion.

### 7. iOS

Ispeziona il path iOS generato e documenta esattamente come verrà ospitato SwiftUI.

Implementa l'hosting SwiftUI in questa fase solo se:

- la modifica è contenuta;
- non rompe il provider Fabric;
- l'ambiente permette verifica oppure il codice è una trasposizione minimale e sicura.

La destinazione iOS deve usare la primitive reale:

```swift
Text(formattedText)
  .monospacedDigit()
  .contentTransition(.numericText(value: value))
  .animation(..., value: value)
```

Non implementare un clone manuale iOS. Se non puoi compilare iOS, non fare refactor ampi: lascia un piano file-by-file e dichiara non verificato.

### 8. Test e verifica

Esegui tutti i comandi applicabili scoperti con `yarn run`, inclusi almeno:

- typecheck;
- lint;
- test disponibili;
- build/install example Android.

Verifica manualmente o tramite strumenti disponibili:

```text
2,576 → 2,577
2,577 → 2,576
999 → 1,000
1,000 → 999
rapid updates
reduce motion
```

Controlla:

- nessun flash;
- nessun testo vuoto;
- nessuna baseline jump evidente;
- direzione corretta;
- stato finale nitido;
- nessun animator residuo dopo detach.

## Deliverable finale dell'agente

Restituisci:

1. architettura effettivamente implementata;
2. lista completa dei file modificati;
3. API pubblica risultante con esempio;
4. comportamento Android ottenuto;
5. stato iOS: implementato/verificato/non verificato;
6. comandi eseguiti e risultati;
7. limitazioni note rispetto a SwiftUI;
8. eventuali ipotesi ancora non validate;
9. proposta precisa per Phase 2: laboratorio frame-by-frame e changed-run/glyph planner.

Non fermarti a una analisi teorica: completa la verticale Android funzionante, mantenendo il repository compilabile.
