package eu.todaro.navisync.desktop

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.graphics.SimpleTheme
import com.googlecode.lanterna.gui2.BasicWindow
import com.googlecode.lanterna.gui2.Borders
import com.googlecode.lanterna.gui2.Button
import com.googlecode.lanterna.gui2.CheckBox
import com.googlecode.lanterna.gui2.ComboBox
import com.googlecode.lanterna.gui2.DefaultWindowManager
import com.googlecode.lanterna.gui2.Direction
import com.googlecode.lanterna.gui2.EmptySpace
import com.googlecode.lanterna.gui2.GridLayout
import com.googlecode.lanterna.gui2.Label
import com.googlecode.lanterna.gui2.LinearLayout
import com.googlecode.lanterna.gui2.MultiWindowTextGUI
import com.googlecode.lanterna.gui2.Panel
import com.googlecode.lanterna.gui2.ProgressBar
import com.googlecode.lanterna.gui2.TextBox
import com.googlecode.lanterna.gui2.Window
import com.googlecode.lanterna.gui2.dialogs.MessageDialog
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import eu.todaro.navisync.data.subsonic.SubsonicClient
import eu.todaro.navisync.domain.PlaylistPlan
import eu.todaro.navisync.domain.PushProgress
import eu.todaro.navisync.domain.ServerConfig
import eu.todaro.navisync.domain.SyncProgress
import eu.todaro.navisync.sync.PushBus
import eu.todaro.navisync.sync.SyncBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Interfaccia a terminale: stesse funzioni della UI Compose su Android (config, sync, push
 * playlist), disegnate con Lanterna. Sync e push stanno in finestre a schermo intero: sovrapporle
 * al form lasciava due cornici incastrate e poco leggibili.
 */
class Tui {

    private val store = DesktopConfigStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var gui: MultiWindowTextGUI

    private val urlBox = TextBox(TerminalSize(FIELD_WIDTH, 1))
    private val userBox = TextBox(TerminalSize(FIELD_WIDTH, 1))
    private val passBox = TextBox(TerminalSize(FIELD_WIDTH, 1)).setMask('*')
    private val folderBox = TextBox(TerminalSize(FIELD_WIDTH, 1))
    private val favNameBox = TextBox(TerminalSize(FIELD_WIDTH, 1))
    private val parallelCombo = ComboBox<String>((1..8).map { it.toString() })
    private val coversCheck = CheckBox("Scarica le copertine (cover.jpg)")
    private val playlistsCheck = CheckBox("Sincronizza le playlist (.m3u8)")
    private val favoritesCheck = CheckBox("Sincronizza i preferiti (stelle) come playlist")
    private val statusLabel = Label(pad(" "))

    fun run() {
        loadIntoForm()
        // Senza forzarlo, Lanterna ripiega su una finestra Swing quando non trova una console:
        // per un comando da terminale è meglio fallire che aprire una GUI a sorpresa.
        val screen = DefaultTerminalFactory().setForceTextTerminal(true).createScreen()
        screen.startScreen()
        gui = MultiWindowTextGUI(screen, DefaultWindowManager(), EmptySpace(TextColor.ANSI.DEFAULT))
        gui.setTheme(theme())
        try {
            gui.addWindowAndWait(mainWindow())
        } finally {
            scope.cancel()
            screen.stopScreen()
        }
    }

    /**
     * Sfondo del terminale (eredita il tema di sistema) e nero su ciano per l'elemento col focus:
     * il tema di default di Lanterna usa combinazioni a basso contrasto proprio sul focus.
     */
    private fun theme(): SimpleTheme = SimpleTheme.makeTheme(
        false,                       // niente grassetto: a decidere è il contrasto, non il peso
        TextColor.ANSI.WHITE,        // testo
        TextColor.ANSI.DEFAULT,      // sfondo
        TextColor.ANSI.WHITE_BRIGHT, // testo nei campi
        TextColor.ANSI.BLACK_BRIGHT, // sfondo dei campi, per distinguerli dalle etichette
        TextColor.ANSI.BLACK,        // testo dell'elemento col focus
        TextColor.ANSI.CYAN,         // sfondo dell'elemento col focus
        TextColor.ANSI.DEFAULT,      // sfondo dietro le finestre
    )

    private fun loadIntoForm() {
        val (config, password) = store.load()
        urlBox.setText(config.baseUrl)
        userBox.setText(config.username)
        passBox.setText(password)
        folderBox.setText(config.rootFolder)
        favNameBox.setText(config.favoritesPlaylistName)
        parallelCombo.setSelectedIndex(config.parallelism.coerceIn(1, 8) - 1)
        coversCheck.setChecked(config.downloadCovers)
        playlistsCheck.setChecked(config.syncPlaylists)
        favoritesCheck.setChecked(config.syncFavorites)
    }

    private fun readForm() = ServerConfig(
        baseUrl = urlBox.getText().trim(),
        username = userBox.getText().trim(),
        rootFolder = expandHome(folderBox.getText().trim()),
        downloadCovers = coversCheck.isChecked,
        syncPlaylists = playlistsCheck.isChecked,
        syncFavorites = favoritesCheck.isChecked,
        favoritesPlaylistName = favNameBox.getText().trim().ifBlank { "Liked Songs" },
        parallelism = parallelCombo.getSelectedIndex() + 1,
    )

    private fun saveForm(): ServerConfig {
        val config = readForm()
        store.save(config, passBox.getText())
        return config
    }

    // ---------------------------------------------------------------- finestra principale

    private fun mainWindow(): BasicWindow {
        val window = BasicWindow(" NaviSync ")
        window.setHints(listOf(Window.Hint.CENTERED))

        val form = Panel(grid())
        form.addComponent(Label("URL server"))
        form.addComponent(urlBox)
        form.addComponent(Label("Username"))
        form.addComponent(userBox)
        form.addComponent(Label("Password"))
        form.addComponent(passBox)
        form.addComponent(Label("Cartella locale"))
        form.addComponent(folderBox)
        form.addComponent(Label("Nome preferiti"))
        form.addComponent(favNameBox)
        form.addComponent(Label("Download paralleli"))
        form.addComponent(parallelCombo)

        val options = Panel(LinearLayout(Direction.VERTICAL))
        options.addComponent(coversCheck)
        options.addComponent(playlistsCheck)
        options.addComponent(favoritesCheck)

        val actions = Panel(LinearLayout(Direction.HORIZONTAL))
        actions.addComponent(Button("Verifica") { testConnection() })
        actions.addComponent(Button("Salva") { onSave() })
        actions.addComponent(Button("Sync ora") { openSyncWindow() })
        actions.addComponent(Button("Playlist") { openPushWindow() })
        actions.addComponent(Button("Esci") { window.close() })

        val root = Panel(LinearLayout(Direction.VERTICAL))
        root.addComponent(form.withBorder(Borders.singleLine(" Server Navidrome ")))
        root.addComponent(options.withBorder(Borders.singleLine(" Opzioni ")))
        root.addComponent(actions)
        root.addComponent(statusLabel)
        root.addComponent(Label(pad("Tab: campo successivo   Invio: attiva")))
        window.component = root
        return window
    }

    private fun onSave() {
        val config = saveForm()
        setStatus(
            if (config.isComplete) "Salvato in ${store.file.path}"
            else "Salvato, ma URL, username o cartella sono incompleti"
        )
    }

    private fun testConnection() {
        val config = readForm()
        val password = passBox.getText()
        setStatus("Verifica in corso...")
        scope.launch {
            val result = runCatching { SubsonicClient(config.baseUrl, config.username, password).ping() }
            onGui {
                setStatus(
                    if (result.isSuccess) "OK, connessione riuscita"
                    else "Errore: ${result.exceptionOrNull()?.message ?: "connessione fallita"}"
                )
            }
        }
    }

    private fun setStatus(text: String) = statusLabel.setText(pad(text))

    // ---------------------------------------------------------------- finestra di sync

    private fun openSyncWindow() {
        val config = saveForm()
        if (!config.isComplete) {
            MessageDialog.showMessageDialog(
                gui, "Configurazione incompleta",
                "Servono URL server, username e cartella di destinazione.", MessageDialogButton.OK,
            )
            return
        }
        if (SyncBus.running.value) return

        val window = fullScreenWindow(" Sync ")
        val phaseLabel = Label(pad("Avvio..."))
        val fileLabel = Label(pad(" "))
        val bar = ProgressBar(0, 100, BODY_WIDTH)
        bar.setLabelFormat("%2.0f%%")
        val logLabel = Label(logBlock(emptyList()))
        val closeButton = Button("Interrompi") {}

        val root = Panel(LinearLayout(Direction.VERTICAL))
        root.addComponent(phaseLabel)
        root.addComponent(bar)
        root.addComponent(fileLabel)
        root.addComponent(logLabel.withBorder(Borders.singleLine(" Log ")))
        root.addComponent(closeButton)
        window.component = root

        val job = SyncRunner.start(scope, config, passBox.getText())
        closeButton.addListener { if (SyncBus.running.value) job.cancel() else window.close() }

        val render = scope.launch {
            SyncBus.progress.collect { p ->
                onGui {
                    phaseLabel.setText(pad(syncPhaseLabel(p)))
                    bar.setValue(if (p.totalFiles > 0) p.doneFiles * 100 / p.totalFiles else 0)
                    fileLabel.setText(pad(p.currentFile))
                    logLabel.setText(logBlock(p.log))
                }
            }
        }
        val watchRunning = scope.launch {
            SyncBus.running.collect { running ->
                onGui { closeButton.label = if (running) "Interrompi" else "Chiudi" }
            }
        }

        gui.addWindowAndWait(window)
        render.cancel()
        watchRunning.cancel()
    }

    private fun syncPhaseLabel(p: SyncProgress): String {
        val mb = p.bytesDownloaded / (1024 * 1024)
        return when (p.phase) {
            SyncProgress.Phase.IDLE -> "In attesa"
            SyncProgress.Phase.INDEXING -> "Indicizzazione della libreria..."
            SyncProgress.Phase.DOWNLOADING -> "Download ${p.doneFiles}/${p.totalFiles} file - $mb MB"
            SyncProgress.Phase.PLAYLISTS -> "Esporto le playlist..."
            SyncProgress.Phase.DONE -> "Completato - $mb MB scaricati"
            SyncProgress.Phase.FAILED -> "Errore: ${p.error ?: "sync fallito"}"
        }
    }

    // ---------------------------------------------------------------- finestra playlist

    private fun openPushWindow() {
        val config = saveForm()
        if (config.baseUrl.isBlank()) {
            MessageDialog.showMessageDialog(
                gui, "Configurazione incompleta", "Manca l'URL del server.", MessageDialogButton.OK,
            )
            return
        }

        val window = fullScreenWindow(" Carica playlist su Navidrome ")
        val pathBox = TextBox(TerminalSize(FIELD_WIDTH, 1), defaultPlaylistPath(config))
        val statusLine = Label(pad(PUSH_HINT))
        val logLabel = Label(logBlock(emptyList()))

        val actions = Panel(LinearLayout(Direction.HORIZONTAL))
        actions.addComponent(Button("Analizza") {
            val files = collectM3uFiles(pathBox.getText())
            if (files.isEmpty()) {
                statusLine.setText(pad("Nessun .m3u/.m3u8 trovato in quel percorso."))
            } else {
                statusLine.setText(pad("Analizzo ${files.size} file..."))
                PushRunner.analyze(scope, config, passBox.getText(), files)
            }
        })
        actions.addComponent(Button("Carica") {
            val plans = PushBus.plans.value
            when {
                PushBus.running.value -> statusLine.setText(pad("Operazione già in corso."))
                plans.none { !it.skipped } -> statusLine.setText(pad("Niente da caricare: analizza prima le playlist."))
                else -> PushRunner.push(scope, config, passBox.getText(), plans)
            }
        })
        actions.addComponent(Button("Chiudi") { window.close() })

        val form = Panel(grid())
        form.addComponent(Label("Percorso"))
        form.addComponent(pathBox)

        val root = Panel(LinearLayout(Direction.VERTICAL))
        root.addComponent(form)
        root.addComponent(actions)
        root.addComponent(statusLine)
        root.addComponent(logLabel.withBorder(Borders.singleLine(" Esito ")))
        window.component = root

        val renderProgress = scope.launch {
            PushBus.progress.collect { p ->
                onGui {
                    statusLine.setText(pad(pushPhaseLabel(p)))
                    // Durante il push il log rimpiazza il piano: conta cosa è stato fatto davvero.
                    if (p.phase == PushProgress.Phase.PUSHING || p.phase == PushProgress.Phase.DONE) {
                        logLabel.setText(logBlock(p.log))
                    }
                }
            }
        }
        val renderPlans = scope.launch {
            PushBus.plans.collect { plans ->
                if (plans.isNotEmpty()) onGui { logLabel.setText(logBlock(plans.map { planLine(it) })) }
            }
        }

        gui.addWindowAndWait(window)
        listOf<Job>(renderProgress, renderPlans).forEach { it.cancel() }
    }

    private fun planLine(plan: PlaylistPlan): String {
        val action = when {
            plan.skipped -> "SALTATA, abbinamento incompleto"
            plan.isFavorites -> "preferiti"
            plan.existingId != null -> "sostituisci"
            else -> "crea"
        }
        val dup = if (plan.duplicateNames) " [nome duplicato]" else ""
        return "${plan.name}  ${plan.match.matched}/${plan.match.total}  $action$dup"
    }

    private fun pushPhaseLabel(p: PushProgress): String = when (p.phase) {
        PushProgress.Phase.IDLE -> PUSH_HINT
        PushProgress.Phase.ANALYZING -> "Analisi ${p.done}/${p.total}  ${p.current}"
        PushProgress.Phase.PUSHING -> "Caricamento ${p.done}/${p.total}  ${p.current}"
        PushProgress.Phase.DONE -> "Completato."
        PushProgress.Phase.FAILED -> "Errore: ${p.error ?: "operazione fallita"}"
    }

    private fun defaultPlaylistPath(config: ServerConfig): String =
        if (config.rootFolder.isNotBlank()) java.io.File(config.rootFolder, "Playlists").path else ""

    // ---------------------------------------------------------------- utilità

    private fun fullScreenWindow(title: String): BasicWindow {
        val window = BasicWindow(title)
        window.setHints(listOf(Window.Hint.FULL_SCREEN, Window.Hint.MODAL))
        return window
    }

    private fun grid() = GridLayout(2).setHorizontalSpacing(2).setLeftMarginSize(1).setRightMarginSize(1)

    /**
     * Uso Label e non TextBox per log ed esiti: una TextBox in sola lettura si prende comunque il
     * focus col Tab e si colora come se fosse editabile. Righe a larghezza e numero fissi, così il
     * layout non si muove a ogni aggiornamento.
     */
    private fun logBlock(lines: List<String>): String =
        (lines.takeLast(LOG_ROWS) + List(LOG_ROWS) { "" })
            .take(LOG_ROWS)
            .joinToString("\n") { pad(it, BODY_WIDTH) }

    private fun pad(text: String, width: Int = BODY_WIDTH): String =
        if (text.length > width) text.take(width - 1) + "…" else text.padEnd(width)

    /** Lanterna non è thread-safe: ogni modifica ai widget passa dal thread della GUI. */
    private fun onGui(block: () -> Unit) = gui.getGUIThread().invokeLater(block)

    private companion object {
        const val LOG_ROWS = 10
        const val FIELD_WIDTH = 46
        const val BODY_WIDTH = 66
        const val PUSH_HINT = "Indica un file .m3u/.m3u8 o una cartella, poi Analizza."
    }
}
