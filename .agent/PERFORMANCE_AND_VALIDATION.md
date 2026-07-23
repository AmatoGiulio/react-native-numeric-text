# Performance and Validation

## Cosa misurare

La velocità JSI non è il rischio principale. Misurare:

- tempo `onDraw` durante transizione;
- allocazioni per frame;
- numero di invalidazioni;
- creazione di formatter/typeface/effect;
- uso di bitmap/layer;
- frame time e jank;
- memoria GPU/heap per transizione;
- costo con aggiornamenti rapidi.

## Regole performance

- nessun nuovo formatter a ogni frame;
- nessun nuovo `Paint` a ogni frame;
- nessuna bitmap allocata a ogni frame;
- nessun nuovo `RenderEffect` se può essere quantizzato/cached o evitato;
- nessun setState React per progress;
- nessun log per frame in build normale;
- invalidare solo mentre serve.

## Target iniziali

Non usare target arbitrari come prova di qualità. Come guardrail:

- 60 fps su device Android moderno nella demo semplice;
- nessun frame nero o vuoto;
- nessuna crescita continua della memoria dopo centinaia di transizioni;
- cleanup completo dopo detach;
- comportamento accettabile senza blur sui device/API non supportati.

## Reverse engineering visivo

### Esperimenti iOS

Registrare separatamente:

1. `numericText` con linear;
2. `numericText` con easeInOut;
3. `numericText` con spring;
4. opacity transition come controllo;
5. animazione disabilitata.

Casi minimi:

```text
0 → 1
1 → 2
1 → 9
9 → 0
9 → 10
99 → 100
100 → 99
2,576 → 2,577
```

### Analisi

Per ciascuna registrazione osservare:

- direzione old/new;
- distanza verticale;
- momento di massima perdita di nitidezza;
- curva alpha apparente;
- stabilità delle cifre non cambiate;
- movimento orizzontale con cambio di lunghezza;
- trattamento dei separator;
- overshoot o settling;
- durata effettiva.

### Criterio di fedeltà

Non cercare una equivalenza matematica non dimostrabile. Cercare:

- stessa lettura percettiva;
- stessa direzione e timing relativo;
- stessa continuità del testo;
- comportamento simile nei casi limite;
- differenze documentate e ridotte iterativamente.

## Strumenti

Sono accettabili:

- screen recording a frame rate noto;
- estrazione frame;
- overlay side-by-side;
- difference blending;
- profiler Android;
- Perfetto/System Trace;
- `dumpsys gfxinfo` come supporto, non unica prova.
