# Numeric Text — Analisi dell'animazione e algoritmo target

Analisi derivata da: documentazione Apple (`ContentTransition`), 16 frame reali
(`docs/frame-19..34.png`, demo del contatore rapido) e l'implementazione Android
corrente. Ogni parametro numerico è **osservato/sperimentale**, non una costante Apple.

## 1. Cosa dice davvero Apple (contratto pubblico)

Da `ContentTransition` / `numericText(value:)` / `numericText(countsDown:)`:

- è una transizione **interna a una singola `Text`**, non un insert/remove di view;
- ha effetto solo dentro una transazione a cui è associata una `Animation`;
- è pensata per testo **numerico** che aumenta o diminuisce;
- `value:` → la direzione deriva dal segno di `(new - old)`; `countsDown:` → direzione esplicita.

Apple **non** documenta: matching dei glifi, curve interne, distanza di viaggio,
trattamento di separatori/cambi di lunghezza, uso di blur/mask, comportamento su
interrupt. Tutto ciò che segue è ricavato dalle frame.

La demo del post accoppia sempre `numericText` con `monospacedDigit()`: l'algoritmo
**assume slot a larghezza fissa** (cifre tabulari). Senza cifre tabulari il layout
degli slot va calcolato a mano.

## 2. Evidenza dalle frame (fatti, non ipotesi)

| Frame | Testo | Osservazione chiave |
|-------|-------|---------------------|
| 19 | `1,984` | stato fermo: tutto nitido, nessun offset |
| 20 | `·,984` | **solo** la cifra migliaia `1→2` sfoca e trasla in verticale; `,984` resta nitido e immobile |
| 21 | `2,1··4` | `2,` fermo; cifre centrali al blur massimo (illeggibili); `4` quasi fermo |
| 22 | `2,107` | `2,10` nitido; `7` con smear verticale marcato |
| 24 | `2,·07` | slot separatore/cifra in transito isolato; `07` nitido |
| 25 | `2,2·7` | slot centrale: **due glifi sovrapposti** (`3` nuovo + fantasma `8`) → crossfade |
| 30 | `2,35·` | `2,35` nitido; ultima cifra sfocata e spostata in verticale |
| 32/34 | `2,473`/`2,596` | cifre diverse a **livelli di blur diversi nello stesso frame** |

Tre conclusioni robuste:

1. **Per-glifo con slot ancorati.** Cifre invariate restano nitide e ferme anche
   quando sono adiacenti (o interne) a cifre che cambiano. → esclude whole-string;
   negli scenari con cifra interna stabile esclude anche il changed-run a blocco.
2. **Un solo step visivo per transizione.** Si vedono vecchio+nuovo in crossfade
   (frame 25), non lo scorrimento `8→9→0`. Non è un odometro.
3. **Blur a campana** = firma dell'effetto. Nitido agli estremi, picco a metà;
   vecchio e nuovo entrambi sfocati nella fase centrale.

Ciò che le frame **non** possono disambiguare (contatore continuo, +1):
changed-run vs per-glyph coincidono per gli incrementi unitari. Il discriminante
è il caso "cifra stabile fra due cifre che cambiano" e il cambio di lunghezza.

## 3. Pipeline target (per singola transizione A→B)

```
value A, value B
   │
   ├─ format(A), format(B)          # stesso locale, grouping, fraction digits
   │
   ├─ tokenizza in slot:            # DIGIT | GROUP_SEP | DECIMAL_SEP | SIGN | OTHER
   │     allineamento RIGHT (unità) per la parte intera,
   │     LEFT dal separatore decimale per la parte frazionaria,
   │     separatori come slot propri
   │
   ├─ per ogni slot → changed = (oldToken != newToken)
   │     slot invariati: disegnati una volta, nitidi, fermi (ancore)
   │     slot nuovi/rimossi (cambio lunghezza): entrano/escono come "changed"
   │
   └─ un solo driver 0→1 (spring) applica a OGNI slot changed:
         offsetY_old(p),  offsetY_new(p)     # traslazione verticale, segno = direzione
         alpha_old(p),    alpha_new(p)       # crossfade
         blur(p)                             # campana, 0→picco→0
      layout X interpolato tra larghezza(A) e larghezza(B)
      atterraggio esplicito sui valori finali; callback exactly-once
```

Direzione (`direction = sign(B - A)`, o `countsDown`):
- **incremento → roll verso l'alto** (nuovo entra dal basso, vecchio esce in alto);
- **decremento → roll verso il basso**.

> ⚠️ Il **segno** della direzione è l'unico dato che le frame (blur + contatore
> continuo) non fissano con certezza. È la convenzione SwiftUI/`countsDown` — da
> confermare on-device, dove basta un incremento singolo lento per leggerlo.

## 4. Curve e parametri osservati (sperimentali, da centralizzare)

Coerenti con le frame; da tarare nel confronto diretto con iOS.

```
durata            ~450–550 ms (la demo usa Animation.spring(); non c'è mapping 1:1
                  spring iOS ↔ ValueAnimator: misurare)
travel verticale  ~0.3–0.5 × line-height per slot (le frame mostrano < mezza altezza,
                  NON un roll a piena altezza da odometro)
blur              campana centrata ~0.4–0.55 del progress, 0 agli estremi;
                  raggio proporzionale alla velocità verticale dello slot
alpha old         ~1 → 0, spento entro ~metà/tre quarti
alpha new         ~0 → 1, acceso da prima del punto medio
settling          ease-out / coda spring nella seconda metà
```

Nota: `travel` attuale = `0.7 × line-height` ([NumericTextView.kt:86]) è
probabilmente **troppo** rispetto alle frame; abbassare verso 0.3–0.5 e confrontare.

## 5. Gap con l'implementazione Android attuale

Default = `CHANGED_RUN`: isola prefisso/suffisso comuni e anima il changed-run come
**blocco unico** (una coppia old/new full-text mascherata). Diverge da SwiftUI in:

- **cifra stabile interna** (`1,919 → 1,616`): SwiftUI tiene l'`1` centrale nitido;
  `CHANGED_RUN` sfoca l'intero blocco `919/616`.
- **cambio lunghezza / separatore** (`999 → 1,000`, `9 → 10`): prefisso/suffisso
  comuni vuoti → degenera in crossfade dell'intera stringa; si perde l'allineamento
  per-cifra alle unità.

Stato del codice (**implementato**):
- `TransitionLogic.buildPerGlyphPlan` + `buildCompoundSlots`: matching per-cifra
  right-aligned con separatori, ora locale-aware (`TransitionLogic.kt`).
- `NumericTextView.drawPerGlyph`: renderer per-slot. Ancore (slot invariati) disegnate
  una volta nitide con X interpolata; slot changed/inserted/removed rollano in verticale
  con crossfade + blur a campana, in due `saveLayer` (old/new) con soft-mask verticale.
- **`PER_GLYPH` è ora la strategia di default** (`resolveStrategy`); `onDraw` instrada su
  `drawPerGlyph`. `WHOLE_RUN`/`CHANGED_RUN` restano solo come strategie di debug/confronto.
- Le curve (`oldMotion`, `newOpacity`, `blurEnvelope`, …) restano centralizzate in
  `TransitionLogic` e sono condivise dai due renderer.
- `travelFactor = 0.5` (era 0.7), coerente con l'osservato; knob centrale da tarare.

Copertura test (JVM, `TransitionLogicTest`): interior digit stabile (`1,919→1,616`),
cambio lunghezza (`999→1,000`), carry frazionario (`1.9→2.0`), incremento singolo.

Confronto video (nostro output vs target `docs/*.mp4`, frame estratti):
- **Il blur del target è direzionale VERTICALE**, non isotropo. Il glifo resta leggibile
  in orizzontale (tratti orizzontali nitidi) e sfuma solo lungo l'asse del roll. Il nostro
  `BlurMaskFilter` isotropo produceva un "blob" tondo illeggibile che sbava anche in
  orizzontale. → **risolto**: `drawGlyphLayerDirectional` usa `RenderEffect.createBlurEffect`
  con raggio X piccolo (~0.10×) e raggio Y = `maxBlur·blurEnvelope` (API 31+); fallback
  isotropo ridotto sotto API 31 / canvas software.
- Il blur direzionale a pari magnitudine mantiene la cifra leggibile come nel target.

Rapid-hold continuity (**risolto**): un update a metà transizione non annulla/riavvia più.
`retargetTransition` mantiene l'origine (valore settled), l'animator in corso e il progress,
e sostituisce solo il target col valore più recente. Una raffica `100→101→…→156` diventa
**un unico roll continuo** invece di 56 micro-transizioni con dead-stop a ogni tick (lo
smootherstep ha velocità 0 agli estremi → chaining = scatto). Il blur alto durante il moto
nasconde lo swap del target. Limite noto: per hold più lunghi di `animationDurationMs`
l'animator completa e ri-parte (breve re-pulse ~ogni durata), non un flusso infinito.

Prossimi passi (non fatti, evidence-driven su device):
- confermare il **segno** della direzione (up/down) e tarare `travelFactor`/`blurFactor` vs iOS;
- valutare spring reale con preservazione di velocità (oggi smootherstep su animator lineare);
  eliminerebbe anche il re-pulse sui hold molto lunghi.

## 6. Confronto = prova, non intuizione

La fedeltà va dimostrata affiancando iOS e Android sugli stessi casi. Casi minimi
che separano gli algoritmi:

```
2,576 → 2,577      # +1: changed-run == per-glyph (baseline)
1,919 → 1,616      # cifra interna stabile: discrimina per-glyph
999   → 1,000      # cambio lunghezza + comparsa separatore
1.9   → 2.0        # frazionaria + carry
-1    → 0 → -1     # segno
```
