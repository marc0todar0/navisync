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

## Versione Linux (TUI)
Stessa logica di sync, interfaccia a terminale (Lanterna) invece di Compose: gira anche via SSH.

```bash
just tui              # build + avvio dell'interfaccia
just sync             # sync headless con la config salvata (cron/systemd)
just desktop-install  # symlink in ~/.local/bin
```

Nella TUI: form di configurazione, `Verifica`, `Salva`, `Sync ora` (finestra con avanzamento, log e
`Interrompi`) e `Playlist` per caricare `.m3u/.m3u8` da un file o da un'intera cartella.

Per averla anche nel launcher (Omarchy/Hyprland), `~/.local/share/applications/NaviSync.desktop`:

```ini
[Desktop Entry]
Name=NaviSync
Exec=xdg-terminal-exec --app-id=TUI.tile -e navisync
Terminal=false
Type=Application
Icon=folder-music
```

La config sta in `~/.config/navisync/config.properties` (rispetta `XDG_CONFIG_HOME`), un file di testo
modificabile a mano. Su Linux non c'è un keystore su cui contare come su Android, quindi **la password
è in chiaro** in quel file, creato con permessi `0600`; in alternativa passala da `NAVISYNC_PASSWORD`
(ha la precedenza e non viene mai scritta su disco), che è la via giusta per cron e systemd.

## Struttura
```
core/      logica condivisa: sync engine, client Subsonic, playlist, matcher (Kotlin/JVM puro)
app/       Android: Compose, WorkManager, DataStore + Jetpack Security
desktop/   Linux: TUI Lanterna, config su file, coroutine al posto del WorkManager
```

## Permessi (Android)
L'app usa **MANAGE_EXTERNAL_STORAGE** ("accesso a tutti i file"): è pensata per essere installata via
APK (sideload) e scrivere in una cartella normale (es. `/sdcard/Music/NaviSync`) accessibile da altri
player. Per una eventuale pubblicazione su Play Store andrebbe riscritta su Storage Access Framework.

## Build
Richiede JDK 17+ (il bytecode è 17, ma si compila con JDK più recenti). L'APK richiede anche
l'Android SDK (platform 35, build-tools 35); il binario Linux no.

```bash
just            # elenco delle ricette
just debug      # APK di debug
just release    # APK firmato (chiede la password del keystore)
just desktop    # binario Linux in desktop/build/install/navisync
just check      # compila Android + desktop senza produrre artefatti
```

## Stack
Kotlin · Retrofit + Moshi + OkHttp (condivisi) · Jetpack Compose / Material 3 + WorkManager +
DataStore e Jetpack Security su Android · Lanterna su Linux.

## Stato
v0.1 — connessione, indicizzazione completa, sync brani con download paralleli e ripresa atomica
(`.part` → rename), copertine, export playlist `.m3u8`, upload playlist su Navidrome, sync preferiti
bidirezionale (Liked Songs). Android e Linux condividono lo stesso motore di sync.
