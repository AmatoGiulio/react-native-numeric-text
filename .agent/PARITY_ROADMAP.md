# Roadmap verso il 100% di parità con SwiftUI `numericText`

Documento di continuazione. Stato al commit `3eb11a4` (Android per-glyph + spring driver).
Per il contesto completo dell'algoritmo vedi `NUMERIC_TEXT_ALGORITHM.md`.

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
