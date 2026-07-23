# Android Implementation Specification

## Obiettivo

Replicare la percezione di SwiftUI `ContentTransition.numericText` su Android, non creare un generico rolling counter.

## Prima verticale richiesta

La prima implementazione Android deve supportare bene:

```text
2,576 → 2,577
2,577 → 2,576
```

con:

- font tabulare/monospaziato quando supportato;
- direzione crescente e decrescente;
- clipping verticale;
- vecchio e nuovo stato sovrapposti;
- movimento verticale;
- alpha complementare/asimmetrica;
- perdita temporanea di nitidezza nel centro della sostituzione, solo se implementabile senza instabilità;
- animazione interamente nativa;
- nessun flash, salto di baseline o doppio testo statico.

## Non assumere prematuramente il matching per cifra

La reference iOS deve determinare se la primitive anima:

- l'intera stringa;
- run contigui modificati;
- singole cifre;
- una combinazione dipendente dal cambio.

La demo del post mostra un effetto di sostituzione numerica, ma uno screenshot o un breve video non provano l'algoritmo interno. La prima implementazione può usare old/new text layers interi come baseline sperimentale. Deve però essere strutturata in modo da poter evolvere a slot/glifi se il confronto dimostra che è necessario.

## Pipeline sperimentale consigliata

### Baseline A — whole-text replace

1. Formatta old e new.
2. Misura entrambe le stringhe con lo stesso `TextPaint`.
3. Mantieni baseline e allineamento coerenti.
4. Disegna old e new in due layer logici nello stesso `Canvas`.
5. Applica clip ai bounds del componente.
6. Crescente:
   - old si sposta verso l'alto;
   - new entra dal basso.
7. Decrescente:
   - old si sposta verso il basso;
   - new entra dall'alto.
8. Interpola alpha e posizione da un solo progress.

Questa baseline serve a produrre una demo funzionante e misurabile, non è automaticamente l'algoritmo finale.

### Baseline B — changed runs / slots

Introdurre solo se il confronto iOS mostra che parti invariate rimangono visivamente stabili.

Possibile modello:

```kotlin
data class GlyphSlot(
  val oldToken: String?,
  val newToken: String?,
  val oldX: Float,
  val newX: Float,
  val width: Float,
  val changed: Boolean,
  val kind: TokenKind,
)
```

Token kind possibili:

```text
DIGIT
GROUP_SEPARATOR
DECIMAL_SEPARATOR
SIGN
PREFIX
SUFFIX
```

## Formattazione

Usare API locale Android appropriate. Separare chiaramente:

- valore numerico;
- stringa formattata;
- token visuali;
- piano di transizione.

Non usare `value.toString()` come formatter definitivo.

Casi obbligatori:

```text
999 → 1,000
1,000 → 999
1.9 → 2.0
-1 → 0
0 → -1
```

## Typeface e metriche

- usare `TextPaint`/`Paint.FontMetrics` o primitive equivalenti;
- rispettare densità e `sp`;
- provare `fontFeatureSettings = "tnum"` quando il font lo supporta;
- non assumere che ogni font abbia cifre tabulari;
- baseline stabile durante l'animazione;
- evitare `includeFontPadding` implicito se non controllato;
- gestire fontWeight con API compatibili.

## Rendering

Prima scelta: custom drawing diretto in `onDraw`.

Evitare inizialmente:

- una `TextView` per cifra;
- nested view per ogni token;
- bitmap dell'intera view a ogni frame;
- `RenderEffect` ricreato senza caching a ogni frame;
- `saveLayer` globale non necessario;
- Compose dentro la Fabric View.

## Blur / perdita di nitidezza

Il blur è una ipotesi percettiva da validare. Implementarlo dopo position+alpha baseline.

Opzioni da valutare e misurare:

1. `RenderEffect` API 31+ applicato a layer controllati;
2. pre-render di old/new text su bitmap/RenderNode riutilizzabile per la durata della transizione;
3. simulazione con alpha e scale se il vero blur introduce costo o artefatti.

Requisiti:

- fallback senza blur sotto API 31;
- nessun flash nero;
- nessuna allocazione bitmap per frame;
- cleanup al termine/detach;
- blur massimo attorno alla fase centrale, zero agli estremi.

## Curve iniziali, non definitive

Parametri di partenza per esperimenti:

```text
duration: 450–550 ms
travel: 0.55–0.85 × line height
old alpha end: circa metà/fine transizione
new alpha start: prima del punto medio
blur peak: attorno a 0.4–0.55
settling: lieve spring/ease-out nella seconda metà
```

Non codificare questi valori come replica accertata di Apple. Centralizzarli e renderli facili da confrontare.

## Aggiornamenti rapidi

Casi:

```text
100 → 101 → 102 → 156
```

MVP accettabile:

- coalescere al target più recente;
- non accodare una animazione per ogni update;
- non mostrare valori ormai obsoleti per secondi;
- evitare callback duplicate.

Target ideale successivo:

- ripartire dallo stato visuale corrente verso il nuovo target;
- continuità di posizione e alpha;
- nessun salto quando arriva un nuovo valore a metà transizione.

## Lifecycle

Gestire:

- `onAttachedToWindow`;
- `onDetachedFromWindow`;
- cancellazione animator;
- reset di layer/effect;
- props ricevute prima del layout;
- dimensioni zero;
- ridimensionamento durante transizione;
- view recycling/mounting Fabric.

## Accessibilità

La view deve essere percepita come un singolo testo/valore:

- content description aggiornata al valore finale;
- evitare che old e new vengano esposti come due elementi;
- considerare annunci frequenti e non forzarli a ogni frame;
- reduce motion rispettato.
