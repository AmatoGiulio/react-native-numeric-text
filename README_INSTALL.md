# React Native Numeric Text — Agent Pack

Questo pacchetto contiene il contesto operativo e le specifiche da copiare nella root del repository `react-native-numeric-text`.

## Installazione

Copia nella root del progetto:

- la cartella `.agent/`
- il file `PROMPT_PHASE_1.md`

Struttura risultante:

```text
react-native-numeric-text/
├── .agent/
│   ├── AGENTS.md
│   ├── PROJECT_CONTEXT.md
│   ├── REFERENCE_SOURCES.md
│   ├── PRODUCT_AND_API_SPEC.md
│   ├── ARCHITECTURE.md
│   ├── ANDROID_IMPLEMENTATION_SPEC.md
│   ├── IOS_REFERENCE_SPEC.md
│   ├── DEMO_SPEC.md
│   ├── TEST_MATRIX.md
│   ├── PERFORMANCE_AND_VALIDATION.md
│   └── DECISIONS.md
└── PROMPT_PHASE_1.md
```

## Uso

1. Verifica che lo scaffold Fabric generato compili ancora senza modifiche.
2. Fai un commit baseline.
3. Avvia l'agente dalla root del repository.
4. Incolla integralmente `PROMPT_PHASE_1.md`.
5. Non chiedere all'agente di completare tutta la libreria in un'unica iterazione.

Il primo prompt limita volutamente il lavoro alla fondazione verificabile della libreria e alla prima replica Android. Le specifiche complete restano disponibili in `.agent/` per le fasi successive.
