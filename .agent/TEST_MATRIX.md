# Test Matrix

## Unit test — logica pura

Quando la logica viene estratta dal renderer, testare:

### Direction

```text
1 → 2 = up
2 → 1 = down
2 → 2 = no transition
forced up/down override automatic
```

### Formatting

```text
en-US 2576 = 2,576
it-IT 2576 = 2.576
decimal digits coerenti
negative sign
zero e negative zero se rilevante
```

### Transition planning

```text
same length
length increase
length decrease
group separator insertion/removal
decimal separator
sign insertion/removal
```

### State machine

```text
update while idle
update during animation
multiple rapid updates
cancel on detach
finish exactly once
same target ignored
```

## Android instrumentation / visual test

Minimo:

- component mounts;
- prop value updates;
- correct final text;
- no crash on repeated updates;
- no animator after detach;
- reduce motion final state;
- API 31+ blur path se presente;
- fallback pre-31 se supportato dall'example/device matrix.

## Manual visual acceptance

Per ogni caso:

- baseline non salta;
- testo non viene tagliato in modo errato;
- old e new non rimangono entrambi leggibili troppo a lungo;
- nessun flash nero/bianco;
- nessuna frame con testo vuoto;
- direzione coerente con aumento/diminuzione;
- stato finale nitido e perfettamente centrato;
- nessuna trasformazione residua dopo fine/cancel.

## Build acceptance

Prima di dichiarare completata una milestone:

```text
TypeScript/Codegen spec valida
lint passa
test disponibili passano
Android clean build passa
example Android si installa e apre
nessun warning nuovo ignorato senza spiegazione
```

La build iOS va riportata separatamente come verificata o non verificata.
