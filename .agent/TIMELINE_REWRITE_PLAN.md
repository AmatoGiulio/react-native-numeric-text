# Piano Rewrite — Modello a Timeline

Branch: `feat/timeline-based-rewrite` (da `feat/per-slot-springs-ios-parity`)

## Perché

L'implementazione attuale (~3035 righe, ~57 costanti, 11 sistemi interagenti) modella
l'animazione con molle fisiche quando il SDK Apple rivela che `numericText` è una
configurazione dichiarativa serializzata in `RBTransition` (RenderBox) con ~6 parametri:
`direction`, `offset`, `scale`, `blur`, `delay`, `maxDurationMultiple`.

Nell'SDK NON esiste distinzione API tra "roll" e "structural" — il motore RenderBox la
gestisce internamente. L'operazione atomica è sempre la stessa: per ogni glifo,
`(offsetY(t), blur(t), scale(t), alpha(t))` guidato da curve di easing, non da molle.

## Obiettivo

Sostituire l'engine a molle con un modello dichiarativo a timeline + easing.
Da ~57 costanti a ~12. Da ~3035 righe a ~800. Da 11 sistemi a 3.

## Cosa buttare via

- Molle fisiche (springStep, springIntegrate, springIntegrateInto, sub-stepping a 240Hz)
- Tape system (rollTape*, tapePhase, tapeLane, tapePendingTarget, tapeDelay, culling)
- Crowding gates (cascadeSpamMs, offsetCrowdMs, changeSpacing, offsetSpacing)
- Per-role rate branching (8 code path in tickSlots con stiffness×rate×crowding×slow²...)
- Curve duplicate (exitAlpha/enterAlpha, exitBlur/Blur, deathBlur, structuralArrivalVisualPresence, rollBlurEnvelope... 15+ envelope functions)
- GlyphState con 20+ campi di stato fisico (v, offV, xv, tapeLane, structuralExit, substitutionExit, structuralBirth, waveIndex, pendingTarget, delay, exitOff...)

## Cosa tenere

- `layoutKeyedSlots()` — tokenizzazione e keying per posizione logica
- `tokenize()` — parsing formato numerico
- `formatNumber()` / `getDecimalFormatSymbols()` — formattazione
- Renderer glifo (RenderNode + blur direzionale, canvas.drawText per sharp path)
- onMeasure con headroom per blur/roll
- Props Fabric (React Native ViewManager)

## Nuova architettura

### File

| File | Azione | Righe stimate |
|---|---|---|
| `NumericTextTimeline.kt` | **Nuovo** — easing, curve builder, classificatore | ~250 |
| `NumericTextView.kt` | **Riscrittura** — scheduling + drawing semplificati | ~400 |
| `TransitionLogic.kt` | **Sfoltire** — solo layout/tokenize/format | ~150 |

### Modello

```kotlin
// Una transizione è una lista di GlyphTransition, una per glifo
data class GlyphTransition(
    val key: String,           // chiave stabile (I0, I1, ..., G3, DEC, ...)
    val ch: String,            // carattere
    val kind: TokenKind,       // DIGIT, GROUP_SEP, DECIMAL_SEP, SIGN
    val startX: Float,         // posizione X iniziale (centre-relative)
    val endX: Float,           // posizione X finale
    val delayMs: Float,        // quando parte rispetto all'inizio
    val durationMs: Float,     // durata
    val travelPx: Float,       // distanza verticale (negativa = dall'alto)
    val peakBlurPx: Float,     // blur massimo
    val minScale: Float,       // scala minima
    val fadeOut: Boolean,      // true = il glifo sta uscendo (alpha 1→0)
    val fadeIn: Boolean,       // true = il glifo sta entrando (alpha 0→1)
    val isAnchor: Boolean      // true = glifo fermo, sempre nitido
)

// Parametri globali
object NumericTextTimeline {
    // Timing
    const val ROLL_DURATION_MS = 380f
    const val ROLL_STAGGER_MS = 75f
    const val STRUCTURAL_BIRTH_DURATION_MS = 450f
    const val STRUCTURAL_DEATH_DURATION_MS = 350f
    const val STRUCTURAL_STAGGER_MS = 45f

    // Visual
    const val TRAVEL_FRACTION = 0.45f      // offset / line-height
    const val PEAK_BLUR_FRACTION = 0.16f   // blur / line-height
    const val MIN_SCALE = 0.74f
    const val BLUR_ALPHA_COUPLING = 0.22f
}
```

### Classificatore

La distinzione roll/structural è data dal conteggio delle cifre intere:
- `oldIntCount == newIntCount` → **roll**: ogni colonna che cambia carattere ha
  old+new che condividono lo slot, crossfade verticale
- `oldIntCount != newIntCount` → **structural**: le colonne coinvolte hanno lifecycle
  indipendenti (nascita/morte), le exit restano congelate, le enter compaiono dall'esterno

### Curve di easing

```kotlin
// Offset: easeOutBack per il settle morbido
fun offsetEasing(t: Float): Float  // easeOutBack(t, overshoot=0.9f)

// Blur: bell curve (smootherstep rovesciato)
fun blurEasing(t: Float): Float    // 1 - smootherstep(2*|t-0.5|)

// Alpha: easeInOut per crossfade
fun alphaEasing(t: Float): Float   // smootherstep(t)

// Scale: easeOut
fun scaleEasing(t: Float): Float   // 1 - (1-t)^2
```

### Rendering

Stesso di prima ma semplificato:
1. Per ogni `GlyphTransition`, calcolare `progress = clamp((globalTime - delayMs) / durationMs, 0, 1)`
2. Per gli anchor: `canvas.drawText()` diretto, sempre nitido
3. Per i glifi in movimento: RenderNode con offsetY, blur, scale, alpha
4. Il globalTime è guidato da un ValueAnimator 0→1 (o dal system clock per retarget)

## Fasi

### Fase 1 — NumericTextTimeline.kt (1 giorno)
- Easing functions (easeOutBack, smootherstep, bell curve)
- `buildTransitions()`: data old/new layout + direction → List<GlyphTransition>
- Classificatore roll/structural

### Fase 2 — NumericTextView.kt rewrite (1.5 giorni)
- Rimuovere tutte le molle, tape, crowding gates
- Sostituire scheduleSlots con chiamata a buildTransitions
- Sostituire tickSlots con interpolazione per progress
- Mantenere drawSlots con il nuovo modello GlyphTransition
- Mantenere onMeasure, formatting, props

### Fase 3 — TransitionLogic.kt cleanup (0.5 giorni)
- Tenere: formatNumber, getDecimalFormatSymbols, tokenize, layoutKeyedSlots, buildCompoundSlots
- Rimuovere: spring*, presence*, blur*, exit*, enter*, roll*, death*, smoothstep, smootherstep, easeOut*, remap

### Fase 4 — Test e verifica (0.5 giorni)
- Aggiornare TransitionLogicTest
- Compilazione + lint
- Verifica su device (preset scriptato, grid comparison)

---

## 2026-07-31 — Revisione: sampled-start timeline

Prima iterazione sul device: la resa era "dissolvenza", non roll. Analisi (video
`docs/31-07/`, confronto frame-by-frame con iOS) ha isolato 4 difetti strutturali:

1. **Clock deformato**: una molla guidava il tempo della timeline (warp 1.8×→2.7×),
   rendendo intarabili stagger e durate. → clock = tempo reale (ms).
2. **Curve speculari** vecchio/nuovo → dissolve. La reference è un handover
   asimmetrico: l'exit tiene intero ~40% poi cade; l'enter si accende dal ~45%.
3. **Blur quasi isotropo** (X=0.85Y) → nuvole tonde. Reference: X≈0.10Y.
4. **Retarget = jump cut** (piano ricostruito dalle stringhe). → start state
   campionato dallo schermo (`sampleInstances`): semantica presentation→target
   di SwiftUI, continuità su retarget senza tape.

Bug collaterale eliminato: `maxTransitionMs=600` troncava i piani structural
(631ms+) → `allDone` mai vero, invalidate infinito.

Modello finale: `GlyphTransition(role: ANCHOR|GLIDE|ENTER|EXIT)` come
interpolazione startState→endState su clock reale; completion = max(endMs);
nessuna molla nel clock. Costanti seeded dalle misure di NEXT.md, da rifittare
con template_fit.py / roll_shape.py.
