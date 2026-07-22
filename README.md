# NaviSync

App Android che sincronizza **tutta** la musica di un server [Navidrome](https://www.navidrome.org/)
(o qualsiasi server compatibile **Subsonic**) in una cartella locale del telefono, riutilizzabile da
qualsiasi player. Le **playlist** vengono esportate in `.m3u8` con percorsi relativi, e i **preferiti**
(le tracce con la stella) possono essere sincronizzati come una playlist speciale in entrambe le direzioni.

Non è un vero `rsync`: Navidrome espone le API Subsonic via HTTP, quindi il sync è una logica
idempotente costruita su quelle API (indicizza il server → confronta con la cartella → scarica solo i
file mancanti o cambiati, usando l'endpoint `download` che restituisce il **file originale** non
transcodificato).

## Come funziona
1. Inserisci URL server, username, password e cartella di destinazione.
2. **Verifica** la connessione (ping Subsonic).
3. **Sync ora**: l'app scarica i file mancanti rispecchiando la struttura `Artist/Album/traccia.ext`
   del server, esporta le playlist in `Playlists/*.m3u8` e (opzionale) salva `cover.jpg` per album.

Il sync gira in un `WorkManager` con notifica di avanzamento e sopravvive alla chiusura dell'app.
È idempotente: rilanciandolo scarica solo le novità.

### Opzioni
- **Scarica copertine** — salva `cover.jpg` in ogni cartella album.
- **Sincronizza playlist** — esporta `.m3u8` con percorsi relativi.
- **Sincronizza preferiti** *(default OFF)* — tratta i preferiti (tracce con la stella) come una playlist
  speciale, con nome configurabile (default `Liked Songs`). Vedi sotto.
- **Mirror** *(default OFF)* — elimina in locale i file non più presenti sul server.
- **Download paralleli** — 1–8 (default 4).

### Preferiti (Liked Songs)
Quando **Sincronizza preferiti** è attivo, il nome scelto mappa alla lista dei preferiti del server in
entrambe le direzioni:
- **Download** — le tracce con la stella vengono esportate come `Playlists/<nome>.m3u8` (solo i brani
  presenti su disco), come una normale playlist.
- **Upload** (scheda *Carica playlist*) — un file `.m3u`/`.m3u8` con quel nome **non** crea una playlist:
  aggiorna le stelle sul server in **mirror completo**, così i preferiti diventano esattamente le tracce
  del file (mette la stella alle nuove, la toglie a quelle non più presenti). Se qualche traccia non viene
  abbinata alla libreria, il file è saltato e le stelle non vengono toccate; un file vuoto è ignorato per
  sicurezza (non azzera i preferiti).

## Permessi
L'app usa **MANAGE_EXTERNAL_STORAGE** ("accesso a tutti i file"): è pensata per essere installata via
APK (sideload) e scrivere in una cartella normale (es. `/sdcard/Music/NaviSync`) accessibile da altri
player. Per una eventuale pubblicazione su Play Store andrebbe riscritta su Storage Access Framework.

## Build
Richiede JDK 17+ e Android SDK (platform 35, build-tools 35).

```bash
./gradlew assembleDebug          # genera app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Stack
Kotlin · Jetpack Compose / Material 3 · Retrofit + Moshi + OkHttp · WorkManager ·
DataStore + Jetpack Security (password cifrata).

## Stato
v0.1 — connessione, indicizzazione completa, sync brani con download paralleli e ripresa atomica
(`.part` → rename), copertine, export playlist `.m3u8`, upload playlist su Navidrome, sync preferiti
bidirezionale (Liked Songs), mirror mode opzionale.
