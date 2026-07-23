# Project Context

## Origine del progetto

Il progetto nasce da una sfida pubblica su X.

Nathan Schroeder ha pubblicato una demo Expo UI che espone su React Native l'animazione del numero tipica di iOS tramite SwiftUI:

```tsx
<Text
  modifiers={[
    font({ size, weight: 'bold', design: 'rounded' }),
    contentTransition('numericText', { countsDown }),
    animation(Animation.spring(), value),
    monospacedDigit(),
  ]}
>
  {value.toLocaleString('en-US')}
</Text>
```

Nel thread, Giulio Amato ha risposto che avrebbe provato a replicare il comportamento su Android. Nathan ha risposto: “Would love to see it!!”. La libreria è l'esecuzione concreta di quella sfida.

## Repository

Package:

```text
react-native-numeric-text
```

Componente pubblico previsto:

```tsx
<NumericText value={2576} />
```

Lo scaffold è stato creato con `create-react-native-library` come **Fabric view**, con example app Expo CLI. La libreria resta un Fabric Native Component puro; l'example Expo è solamente un consumer di sviluppo.

Configurazione Codegen osservata:

```json
{
  "codegenConfig": {
    "name": "NumericTextViewSpec",
    "type": "all",
    "jsSrcsDir": "src",
    "android": {
      "javaPackageName": "com.numerictext"
    },
    "ios": {
      "components": {
        "NumericTextView": {
          "className": "NumericTextView"
        }
      }
    }
  }
}
```

`type: "all"` è valido: Codegen genera solo le specifiche effettivamente presenti. Non convertirlo arbitrariamente in `components`.

## Obiettivo di prodotto

Non costruire un generico contatore, un odometro o un componente che attraversa tutti i valori intermedi.

La libreria deve rappresentare una **content transition del testo numerico**:

- il valore cambia semanticamente da A a B;
- la direzione visiva deriva dal fatto che il valore sale o scende;
- le parti rilevanti del contenuto numerico transitano con movimento verticale, dissolvenza e perdita temporanea di leggibilità;
- la composizione mantiene l'impressione di un singolo testo che cambia contenuto, non di due label indipendenti sovrapposte.

## Reference architetturale secondaria

Repository esistente:

```text
https://github.com/AmatoGiulio/react-native-morph
```

`react-native-morph` non deve essere copiata integralmente. È utile per studiare:

- singolo driver nativo;
- gestione di props e lifecycle;
- cleanup delle proprietà imperative;
- reduce motion;
- aggiornamenti rapidi e latest-target state machine;
- problemi Fabric relativi a mount/layout;
- Android `ValueAnimator` e sincronizzazione delle proprietà.

Non sono adatti a questo progetto:

- due alberi React completi sovrapposti;
- morph dell'intera view;
- signature generiche dei children;
- blur e scale applicati indiscriminatamente all'intero contenuto;
- snapshot generici del componente.

## Principio guida

La libreria deve apparire come la controparte Android della primitive iOS, non come una animazione “ispirata a”. La fedeltà va dimostrata tramite confronto, non dichiarata per intuizione.
