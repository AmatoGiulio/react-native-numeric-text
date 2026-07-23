# Demo Specification

## Obiettivo

La demo deve essere un laboratorio tecnico, non una pagina marketing.

## Schermata principale — replica del post

Aspetto:

```text
        2,576

     [ − ] [ + ]
```

Requisiti:

- sfondo quasi bianco;
- numero grande, bold, rounded;
- cifre tabulari;
- pulsanti circolari minimali;
- incremento/decremento di 1;
- layout stabile;
- nessuna animazione JS che alteri il confronto.

## Controls di laboratorio

Aggiungere controlli semplici, non necessariamente tutti nella prima iterazione:

- value preset;
- increment/decrement;
- locale;
- direction automatic/up/down;
- duration;
- reduce motion;
- toggle grouping;
- fraction digits;
- rapid update stress;
- reset.

## Preset obbligatori

```text
2,576 → 2,577
2,577 → 2,576
9 → 10
99 → 100
999 → 1,000
1,000 → 999
1.9 → 2.0
-1 → 0
0 → -1
rapid: 100 → 101 → 102 → 156
large jump: 1 → 9,999
```

## Modalità diagnostica

Mostrare, in una sezione discreta:

```text
platform
old value
current target
formatted old/new
direction
duration
animation running
reduce motion
```

Non inserire log per-frame in React state.

## Confronto iOS/Android

Produrre registrazioni con:

- stessa risoluzione logica del componente;
- stesso font o equivalente di sistema;
- stesso valore iniziale/finale;
- stessa configurazione locale;
- frame rate noto;
- sfondo semplice;
- controlli fuori dal crop del numero.

## Scrubbing futuro

Una modalità debug con progress manuale 0..1 è altamente desiderabile. Deve essere debug-only e non parte dell'API pubblica MVP. Serve per confrontare frame a:

```text
0.00
0.10
0.25
0.40
0.50
0.65
0.75
0.90
1.00
```
