# Numeric Text — sequenza promo Android / iOS

Durata master: **10,00 s**. Formato consigliato: **1080×1920, 30 fps**, due mock affiancati. La sorgente deve essere la schermata **Showcase**, non la schermata Lab/preset: entrambe le piattaforme devono partire dallo stesso valore e seguire la stessa sequenza automatica.

## Timeline comune

| Timecode | Azione identica sui due mock | Obiettivo visivo |
|---|---|---|
| 00.00–00.80 | Hold su `1,992` | Presentare il punto di partenza |
| 00.80–02.25 | `1,992 → 1,993 → 1,994 → 1,995` | Scala iniziale a incrementi singoli |
| 02.25–04.65 | `1,995 → 99 → 100 → 1` | Tre cambi strutturali netti |
| 04.65–07.30 | `1 → 0 → -1 → -2 → -3 → -4` | Discesa lenta in cinque step |
| 07.30–07.82 | `-4 → 1,000 → 999 → 1,000` | Contro-trigger: due cifre restano sospese |
| 07.82–08.18 | `1,000 → 1,123` | Inizio del press-and-hold `+123` |
| 08.18–08.48 | `1,123 → 1,246` | Il ritmo aumenta |
| 08.48–08.72 | `1,246 → 1,369` | Il ritmo aumenta ancora |
| 08.72–08.90 | `1,369 → 1,492` | Roll più serrato |
| 08.90–09.05 | `1,492 → 1,615` | Accelerazione da hold |
| 09.05–09.17 | `1,615 → 1,738` | Accelerazione continua |
| 09.17–09.27 | `1,738 → 1,861` | Roll rapido |
| 09.27–09.35 | `1,861 → 1,984` | Roll molto rapido |
| 09.35–09.59 | `1,984 → 2,107 → 2,230 → 2,353 → 2,476` | Hold al massimo ritmo, retrigger a 60 ms |
| 08.13–08.37 | `1,984 → 2,107 → 2,230 → 2,353 → 2,476` | Hold al massimo ritmo, retrigger a 60 ms |
| 08.37–08.49 | `2,476 → 2,353` | Inizio del rollback |
| 08.49–08.63 | `2,353 → 2,230` | Il rollback rallenta |
| 08.63–10.00 | `2,230 → 2,107 → 1,992`, poi hold | Assestamento sul valore iniziale |

## Regole di registrazione

- Stessa build React Native, stesso dataset e stesso font.
- Stessa scala del mock e stesso orientamento portrait.
- La sequenza va avviata con un solo tap su `Play sequence` in entrambi i device; poi nessun tap manuale durante i 10 s.
- Nessun taglio o speed ramp separato per piattaforma.
- Audio e click, se presenti, vanno applicati al master composito dopo l’allineamento video.

## Layout del master composito

- Canvas: `1080×1920`, sfondo chiaro.
- Mock Android a sinistra, iOS a destra, stessa altezza e stessa baseline.
- Label fisse in alto: `ANDROID` e `iOS`.
- Divider verticale sottile al centro.
- Da `09.25` a `10.00`: piccola caption centrale `Same motion. Same timing.`.
