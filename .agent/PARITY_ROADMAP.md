# Roadmap verso il 100% di parità con SwiftUI `numericText`

Documento di continuazione. Stato al commit `3eb11a4` (Android per-glyph + spring driver).
Per il contesto completo dell'algoritmo vedi `NUMERIC_TEXT_ALGORITHM.md`.

---

## 0. Fatto in questa iterazione (molle per-slot) — da verificare a video

Refactor del renderer `PER_GLYPH` da **molla globale condivisa** a **molle indipendenti per
colonna logica**. Copre in un colpo A, B, C, D, F della lista sotto. 53 unit test verdi.

- **A — Molle per-slot.** Ogni colonna (unità, decine, …, separatori) è una `RollSlot` con
  `value/velocity/target` indipendenti, chiave stabile ancorata a destra
  (`TransitionLogic.layoutKeyedSlots` → `I0`=unità, `I3`=migliaia, `G3`=separatore, `F0`=decimi,
  `DEC`, `S`). Solo le colonne il cui carattere cambia fanno retarget; le altre restano a riposo
  → nitide. Le molle **persistono** tra i retarget del rapid-hold (continuità per-colonna).
- **Blur per-cifra dalla velocità della singola molla** (`|velocity|/blurVelocityRef`). Niente
  più `inputActivity` (rimosso): unità in hold veloce → retarget continuo → blur; migliaia ferme
  → nitide. Emerge naturalmente il differenziale di blur del ref.
- **B — Stagger destra→sinistra** (`staggerSeconds = 0.012`): le colonne che iniziano a rotolare
  partono in cascata dall'unità → onda invece di blocco sincronizzato.
- **C — Separatori fade+scale** (non roll): colonne separatore che nascono/muoiono usano la molla
  di *life* (alpha + scale da `bornMinScale`), branch nel renderer per `!rolling`.
- **D — Ancoraggio orizzontale**: colonne right-anchored per `distFromRight`; si interpola **solo
  l'origine di centraggio** (`currentCompWidth` via `springValue`), non la X per-cifra → niente
  micro-wobble sulle cifre che mantengono posizione logica.
- **F — Depth scale (cylinder)**: layer OLD `scale 1→0.86`, NEW `0.86→1` sul pivot del glifo
  (`RenderNode.scaleX/Y` + `pivotX/Y`), così il moto legge come rotazione su cilindro, non
  ghigliottina 2D.
- **Finestra verticale più corta** (`travelFactor 0.42→0.34`, `blurFactor 0.18→0.16`,
  `verticalHeadroomFactor 0.14→0.16`) → scia più corta e morbida, meno colonna nera.

**Knob nuovi** (in cima a `NumericTextView.kt`): `blurVelocityRef=9`, `staggerSeconds=0.012`,
`lifeStiffness=260`, `depthMinScale=0.86`, `bornMinScale=0.6`. Il blur/velocità e lo stagger sono
i primi da ritoccare a video.

**Perf**: nodi `RenderNode` old/new **poolati per slot** (campi di `RollSlot`, riusati tra i
frame; mai `new` in `onDraw`). Da profilare `onDraw` con molte colonne se serve.

**Ancora aperto**: E (deprecare `animationDuration`, esporre `stiffness`/`dampingRatio` come
prop), tuning fine dei knob a video, eventuale squash/stretch legato all'overshoot.

### Esito confronto frame iOS ref vs Android (video android-1 + iOS sim, 2026-07-23)
Harness side-by-side ([[parity-comparison-harness]]): stessa `SEQUENCE`, allineata via evento
999→1,000 (`t0_ios≈1.85s`, `t0_and≈2.5s`). Verdetto:
- **RISOLTO**: blur per-cifra (solo le cifre che cambiano), cifre invariate nitide, continuità
  rapid-hold (niente più black-blob — nel rapid il nostro unità sfoca, prefisso nitido come iOS).
- **GAP PRINCIPALE (uniforme su tutte le transizioni)**: il roll Android è **troppo lungo** →
  glifo uscente e entrante restano separati verticalmente = due fantasmi scuri = **colonna nera
  alta**. iOS ha travel corto → i due glifi si **sovrappongono** → un'unica **massa grigia
  morbida**, glifo leggibile. → Fix applicato: `travelFactor 0.34→0.18`, `blurFactor 0.16→0.12`,
  `verticalHeadroomFactor 0.16→0.12`. **Da riverificare a video (solo Android, la ref iOS non
  cambia).**
- **GAP minore**: su shrink/bigjump (`1,000→999`, `9,999→1`) la cifra superstite scivola
  orizzontalmente un po' più che su iOS (right-anchor + interpolazione origine). iOS mantiene il
  nuovo valore più centrato. Da valutare dopo il blur: interpolare la X per-colonna tra layout
  vecchio-centrato e nuovo-centrato invece del solo right-anchor.

### Algoritmo iOS numericText — reverse engineering a 60fps (video android-2 + iOS sim)
Misurato frame-by-frame sui filmstrip 60fps della reference (single roll `2,576→2,577`, carry
`2,599→2,600`, grow `999→1,000`, decrement `2→1`). **Tratti fondamentali** (alcuni erano
implementati AL CONTRARIO):
- **Cascata LEFT→RIGHT**: su un cambio multi-cifra la cifra **più a sinistra rotola per prima**,
  poi via via verso destra (`2,599→2,600`: prima centinaia, poi decine, poi unità), con delay
  **~80ms per cifra**. Era right→left @12ms → **corretto** (`staggerSeconds=0.075`, sort per
  `cflNew` crescente).
- **Direzione del roll**: incremento → la nuova cifra entra dall'**alto** (il contenuto scende);
  decremento → dal **basso** (contenuto sale). Era invertito → **corretto** (nego `direction`
  nelle offset del renderer).
- **Durata roll per cifra ~130–160ms core** (coda morbida fino a ~250ms); settle globale ~280ms.
  → `dampingRatio 0.7→0.8` (poco bounce come `.spring()` iOS). Stiffness lasciata 320.
- **Travel ~0.3× l'altezza glifo** → `travelFactor 0.18→0.24` (+`verticalHeadroomFactor 0.16`).
- **Layout orizzontale = DUE layout centrati indipendenti** (vecchio+nuovo), crossfade; la cifra
  che sopravvive NON scivola, solo nascita/morte ai bordi. → refactor `RollSlot`
  (`cflOld/cflNew/hasOld/hasNew`), `drawSlots` usa `oldOriginX/newOriginX`, rimosso
  `distFromRight`/`currentCompWidth`. Fissa il GAP orizzontale.
- **Blur**: corto/grigio/morbido — già ok, invariato.
- Separatore/cifra di testa che nasce: **fade+scale** insieme alla cifra più a sinistra (early),
  non roll. Già così.

**Da riverificare a video (solo Android)**: la cascata left→right e la direzione del roll sono le
due correzioni percettivamente più grandi.

### Regola direzionale DIMOSTRATA (analisi 60fps di 0→-1, -1→0, 99→100, 10→9)
La direzione verticale è **GLOBALE per transizione** (= `countsDown`, dal confronto vecchio/nuovo
valore del numero INTERO), mai per-cifra: numero ↑ → tutto arriva dall'alto ed esce in basso;
numero ↓ → viceversa. Prova chiave: `0→-1` (numero scende, cifra 0→1 "sale") rotola nel verso del
DECREMENTO. Il "diagonale" di `10→9` = verticale globale + reflow orizzontale per-posizione.
Le ricreazioni web (shizukushq/numeric-text) usano 3 sezioni LCP, NON fedeli — ignorarle.

### Iterazione android-8 → grid: direzione OK, mancano durata + sequencing (Passo 2 fatto)
Grid `grid8_*`: direzione/spawn corretti ✓. Gap: (a) durata ~metà di iOS; (b) niente ordine
interno — iOS su `10→9` fa exit "1" → enter "9" (dal basso, "0" ancora NITIDO) → exit "0" per
ultimo; (c) anchor scivola troppo presto. → Implementato:
- **Classificatore STRUTTURALE**: se cambia il conteggio delle cifre intere, una colonna che
  cambia carattere diventa EXIT+ENTER **indipendenti** (niente roll accoppiato); struttura
  stabile → roll come prima. Criterio sulla struttura numerica, NON sulla larghezza px.
- **Cascata di fasi unificata**: exit e enter di ogni slot = fasi separate ordinate per X
  (left→right), `exitDelay`/`delay` indipendenti → l'ordine iOS emerge dalla geometria.
- Durate: `exitDuration 0.26→0.40`, `enterDuration 0.30→0.44`, `stagger 0.03→0.05`,
  `anchorLagStart 0.35→0.5`.
Da riverificare a video con grid su `10→9`, `1→1.5`, `999→1,000`.

### Iterazione android-11 — VALIDATA (grid + zoom blur)
Stato committato. Tutti gli eventi chiave ora combaciano strutturalmente con iOS:
- `9,999→1` / `2,600→9`: fade sul posto left→right, macchia dell'entrante a metà evento (~f17 vs
  f13 iOS), durata ~23 frame ≈ iOS. Fix determinanti: exit CONGELATI alla posizione vecchia
  (+ deriva outward 0.18) — prima cavalcavano la contrazione e collassavano al centro; ordinamento
  fasi in spazio CENTRO-RELATIVO (exit nel layout old, enter nel new → l'1 si interleava
  correttamente: 9→,→9→ENTER 1→9→9); enter su stagger COMPRESSO (0.4×, `enterCascadeCompression`)
  perché iOS fa comparire la macchia entrante presto, non al suo turno posizionale.
- `1→1.5`: l'1 SCIVOLA (clock di reflow dedicato `layoutP` 0.40s — la molla globale lo faceva
  saltare in 2 frame), `.5` nasce come macchia e si mette a fuoco. Residuo: iOS sequenzia
  `.` poi `5`, noi quasi insieme.
- `10→9`: exit 1 → enter 9 (0 ancora nitido) → exit 0 per ultimo ✓.
- **Blur = bolla** (zoom pixel-level): NON è un limite Android, era calibro. 3 fix: raggio
  `blurFactor 0.12→0.16`, quasi-isotropo (radiusX `0.35→0.85`·Y), **alpha accoppiata al blur**
  (`blurAlphaDrop 0.35`: opacità ×(1−0.35·blur) → massa grigia chiara come iOS, un gaussian da
  solo non schiarisce abbastanza). Residuo micro: risoluzione finale un filo più rapida/scura.
Micro-delta rimasti: entrante ~3 frame più tardi nei big shrink; `.`+`5` insieme; fine-roll.

### Iterazione android-3 → "più rigido" (feedback utente) — diagnosi a 60fps
Confronto 60fps iOS vs android-3 (carry `2,599→2,600`, single `2,576→2,577`):
- ✅ **Direzione roll corretta** (incremento: nuova cifra dall'alto, contenuto scende) e **cascata
  left→right corretta**. Le due correzioni grosse hanno funzionato.
- ❌ **Causa del "rigido" = molla troppo morbida/lenta**: iOS completa un roll di cifra in ~5-6
  frame (~90ms) con partenza immediata; android-3 impiegava ~11+ frame (~200ms) con partenza
  fiacca. Il roll draggy fa sembrare la cascata laboriosa. → Fix: `springStiffness 320→700`
  (roll ~130ms, partenza scattante), `dampingRatio 0.8→0.78`, `blurVelocityRef 9→13` (compensa la
  velocità di picco più alta così il blur resta uguale), `staggerSeconds 0.075→0.07`. Da
  riverificare a video.

---

## 1. Dove siamo (stato attuale)

Implementato e verificato (46 unit test verdi, solo logica pura; il visivo lo prova
l'utente su device — [[device-testing-is-user-job]]):

- **Renderer per-glifo** con matching right-aligned, ancore nitide per le cifre invariate
  (anche interne), unità allineate nei cambi di lunghezza.
- **Blur direzionale verticale** via `RenderEffect.createBlurEffect(rxPiccolo, ryGrande)`
  (API 31+, fallback isotropo ridotto sotto 31), raggio ∝ altezza font.
- **Driver a molla** (`TransitionLogic.springStep`, Eulero semi-implicito): `dampingRatio 0.7`
  → overshoot/bounce; `springVelocity` preservata tra i retarget.
- **Continuità rapid-hold**: retarget su qualsiasi animazione attiva (niente restart→origine),
  re-base del segmento completato con velocità conservata, no-settle finché input recente (160ms).
- **Blur guidato dalla velocità** + `inputActivity` (segnale burst 0→1) che mantiene sfocate le
  cifre che cambiano durante un hold veloce, nitide quando l'input si ferma.
- **Example**: `+/-` con auto-repeat on hold (30ms) per riprodurre lo spam.

### Mappa file
- `android/.../TransitionLogic.kt` — logica pura: formatter, matching slot (`buildPerGlyphPlan`,
  `buildCompoundSlots`), curve, `springStep`. **Testabile, testala qui.**
- `android/.../NumericTextView.kt` — Fabric View: driver molla (`tickSpring`, `startSpringTicker`),
  lifecycle (`setValue`/`startTransition`/`retargetTransition`/`settleTo`), renderer
  (`drawPerGlyph`, `drawGlyphLayerDirectional`), knob in cima alla classe.
- `example/src/App.tsx` — playground (hold-repeat, preset, strategy, freeze-frame).

### Knob attuali (in cima a `NumericTextView.kt`)
```
travelFactor = 0.42        // roll verticale come frazione della line-height
blurFactor = 0.18          // raggio blur (verticale) come frazione della line-height
verticalHeadroomFactor=0.14// margine verticale per non clippare blur/roll
springStiffness = 320      // velocità di assestamento (~4/(ratio·√k) s)
springDampingRatio = 0.7   // bounce (↓ = più rimbalzo)
blurVelocityRef = 8        // velocità molla che mappa a blur pieno
burstGapSeconds = 0.15     // input più fitti = hold → blur sostenuto
activityDecaySeconds = 0.18// quanto il blur "resta" dopo lo stop
```

---

## 2. Cosa manca per il 100% (in ordine di impatto)

### A. Molle per-slot (per-digit velocity blur) — IL passo grosso
**Problema.** Oggi la molla è *condivisa* da tutte le cifre che cambiano; la velocità ha un
profilo per-segmento (accelera/decelera), quindi il blur *pulsa* una volta per segmento.
`inputActivity` lo maschera durante lo spam, ma non è la resa fisica del ref, dove **ogni cifra
è sfocata in proporzione a quanto rotola lei** (unità sempre in moto, migliaia quasi ferme).

**Soluzione.** Una molla indipendente per slot logico (unità, decine, …), ciascuna con proprio
target/valore/velocità, che retarget-a indipendentemente quando *quella* cifra cambia. Il blur
del singolo slot = f(|velocità del suo spring|). Naturalmente:
- unità durante hold veloce → retarget continuo → mai a riposo → blur pieno;
- migliaia → retarget raro → a riposo → nitide.

Questo **sostituisce** anche `inputActivity` (diventa emergente) e **abilita lo stagger** (B).

**Evidenza (confronto frame ref vs nostro, spam veloce):** il ref differenzia il blur per
cifra — unità molto sfocate, decine poco, e lo smear è grigio "spalmato" ma **leggibile**,
ogni cifra nella sua colonna. Il nostro `inputActivity` uniforme mette **tutte** le cifre
changed al blur massimo → due colonne scure adiacenti si **fondono in un blob nero**
illeggibile. È la prova concreta che serve il blur per-cifra (A).

> **Mitigazione immediata (1 riga, prima di A):** abbassare il tetto del blur sostenuto in
> `drawPerGlyph`, es. `max(velocityBlur, inputActivity * 0.5f)` (nuovo knob `sustainedBlurCeiling`),
> così durante lo spam non satura in blob nero e resta leggibile. Palliativo finché non c'è A.

**Nodi implementativi (non banali):**
- **Chiave stabile per slot = ancorata a DESTRA (`index_from_right`).** NON l'indice
  nell'array (cambia con la lunghezza). Convenzione:
  ```
  position_0 → sempre unità
  position_1 → sempre decine
  position_2 → sempre centinaia
  position_3 → migliaia / primo separatore
  ...
  ```
  Così le molle delle posizioni basse (0..k) mantengono la loro fisica indipendente anche
  quando cambia la lunghezza. Un **nuovo slot che nasce a sinistra** (es. decine di migliaia in
  `9,999 → 10,000`) parte con `scale/alpha = 0`, target `1.0`, **senza disturbare** le molle
  0..k già in moto. Uno slot che muore (decremento di lunghezza) fa il percorso inverso.
- **Perf — pool di `RenderNode` a livello di vista.** Il renderer batcha oggi tutti gli slot
  changed in 1 `RenderNode`; per-slot blur → più nodi. **MAI** `RenderNode()` o
  `RenderEffect.createBlurEffect()` dentro `onDraw`/`drawPerGlyph`: allocare/riciclare i nodi
  come campi della vista (pool indicizzato per `index_from_right`), e per-frame **aggiornare
  solo i parametri** (`setPosition`, re-record, `setRenderEffect` col nuovo raggio). Valutare
  anche il raggruppamento di slot con raggio simile in un solo nodo. Profilare `onDraw`.
- Gestire nascita/morte slot (cambio lunghezza) con molle che entrano/escono (vedi sopra).

> Stima: è un refactor del driver + renderer. Da fare con un test di logica per lo scheduler
> per-slot (matching per `index_from_right` + retarget + nascita/morte) prima di cablare il
> disegno. `inputActivity` diventa emergente e va rimosso.

### B. Staggering / cascata (#5) — richiesto dall'utente
**Cosa.** Ritardo progressivo **destra→sinistra**, ~**10–15 ms per cifra**, così un cambio
multi-cifra (es. `2,950 → 10,000`, preset `Rapid`) fa un effetto d'onda invece di muoversi
"a blocchi sincronizzati".

**Due livelli:**
1. **Cheap (fits arch attuale, fattibile subito):** al *render*, ogni slot changed usa un
   progress sfasato `slotProgress = clamp(springValue - k·staggerFrac)` con `k` = indice da
   destra tra gli slot changed. Applicare lo sfasamento **almeno all'offset verticale** (che è
   per-glifo dentro il node); alpha/blur possono restare batchati (uniformi) per non rompere il
   `RenderNode` condiviso → onda posizionale senza costo. `staggerFrac` ~ 10–15ms / durata-roll.
2. **Full:** emerge gratis dalle molle per-slot (A) dando a ogni slot un piccolo delay di
   partenza `k·delay`.

> Consiglio: fare il **cheap stagger** come primo win serale (basso rischio), poi valutare A.

### C. Transizione dei separatori (virgole) — richiesto dall'utente
**Cosa.** Quando una virgola **compare** (`999 → 1,000`) o **cambia posizione**
(`9,999 → 10,000`), in SwiftUI ha un **fade-in + scale** dedicato, non un roll verticale come le
cifre. Oggi i nostri slot separatore inseriti/rimossi animano come le cifre (roll+blur) → stacco.

**Soluzione.** Nel loop degli slot changed, se `slot.newToken?.kind == GROUP_SEPARATOR`
(o `DECIMAL_SEPARATOR`), renderizzare con **alpha fade + scale** dal centro (pivot sulla virgola)
invece dell'offset verticale + blur. `GlyphSlot` porta già `NumericToken.kind`, quindi il branch
è locale al renderer. Serve un piccolo path separato dal blur batchato (una `Matrix`/`canvas`
scale attorno al pivot dello slot). Curve: scale ~0.6→1.0, alpha 0→1, su `springValue` clampato.

### D. Anchoring orizzontale (#3)
**Cosa.** Le ancore interpolano X (`oldX→newX` con `hProg`) durante i cambi di larghezza →
micro-oscillazioni orizzontali sulle cifre che dovrebbero restare ferme. `tnum` è già attivo.

**Soluzione.** Per le cifre che restano nello **stesso slot logico** (stessa posizione, stessa
larghezza tabulare), **non interpolare X**: fissarla. Interpolare la X solo per lo shift globale
dovuto alla comparsa di un separatore/segno a sinistra. In pratica: calcolare le posizioni slot
right-aligned e tenerle stabili; muovere solo l'origine complessiva.

### F. Scala di profondità / cylinder roll (tocco finale) — richiesto dall'utente
**Cosa.** In SwiftUI la cifra non trasla solo in 2D: la **uscente** scala `1.0 → ~0.85` mentre
sale/scende e sfoca; la **entrante** nasce a `~0.85` e torna a `1.0` agganciandosi alla baseline.
Più un leggero **squash/stretch verticale** legato alla velocità/overshoot della molla (si stira
in moto, si contrae all'atterraggio). L'insieme **Blur + Scale-down + Alpha** fa percepire la
cifra come un oggetto che ruota su un cilindro 3D dietro lo schermo / si allontana in profondità.
Oggi la scala X/Y resta `1.0` → il moto sembra una **ghigliottina 2D** e il blur pare un
"difetto di rendering" invece di una rotazione.

**Soluzione.** Scala basata sul progress, pivot al centro:
- `outgoingScale = lerp(1.0, 0.85, progress)` sul layer OLD;
- `incomingScale = lerp(0.85, 1.0, progress)` sul layer NEW;
- opzionale squash/stretch: `scaleY` leggermente ≠ `scaleX`, legato a `|springVelocity|`.

Implementazione **pulita e cheap**: i layer directional-blur sono già `RenderNode` separati
(old/new) → usare `node.setScaleY(...)` / `setScaleX(...)` + `setPivotX/Y` (pivot = centro della
regione changed). **Niente transform per-glifo in `onDraw`.** Con le molle per-slot (A) la scala
diventa per-cifra. Costo/rischio basso: 2 setter sui nodi esistenti.

### E. Rifiniture / decisioni aperte
- **`animationDuration` → deprecare (DECISO).** In SwiftUI `.numericText()` non è a tempo: è
  guidata interamente dalla molla di sistema. Per la parità 1:1: **ignorare/deprecare
  `animationDuration`** (tenerlo solo come eventuale fallback se la fisica è disabilitata) ed
  esporre invece prop **espliciti `stiffness` e `dampingRatio`**. Non rimappare durata→stiffness.
- **Direzione (segno):** NON è un bug — i test `newOffset_*` provano incremento=dal basso (su),
  decremento=dall'alto (giù). Se on-device sembra invertito, è un solo cambio di segno di
  `currentDirection`, ma prima verificare con incremento singolo lento.
- **Trailing blur direzionale:** valutare solo se, alzato il travel, la direzione resta ambigua.
  Il ref sembra usare blur ~simmetrico su cifra chiaramente traslata, quindi probabilmente basta
  travel + velocità.
- **Perf:** con molle/blur per-slot, profilare `onDraw` (RenderEffect per-slot, allocazioni).
  Vedi skill `argent-react-native-profiler` se serve.

---

## 3. Ordine consigliato per stasera
1. **Cheap stagger (B.1)** — piccolo, alto impatto percettivo, basso rischio.
2. **Separatori fade/scale (C)** — locale al renderer, ben delimitato.
3. **Anchoring X (D)** — toglie il micro-shift.
4. **Molle per-slot (A)** — il grosso; farlo per ultimo, con test sullo scheduler per-slot.
   Rende `inputActivity` emergente e dà lo stagger "full".

Ogni step: verificare compilazione + `:react-native-numeric-text:testDebugUnitTest`; il visivo
lo prova l'utente su device.
