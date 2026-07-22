package eu.todaro.navisync.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.todaro.navisync.data.store.SettingsStore
import eu.todaro.navisync.data.subsonic.SubsonicClient
import eu.todaro.navisync.domain.PushProgress
import eu.todaro.navisync.domain.ServerConfig
import eu.todaro.navisync.sync.M3uParser
import eu.todaro.navisync.sync.PushBus
import eu.todaro.navisync.sync.PushEngine
import eu.todaro.navisync.sync.SyncBus
import eu.todaro.navisync.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface TestState {
    data object Idle : TestState
    data object Testing : TestState
    data object Ok : TestState
    data class Error(val message: String) : TestState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)

    // Form
    var baseUrl by mutableStateOf("")
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var rootFolder by mutableStateOf("")
    var downloadCovers by mutableStateOf(true)
    var syncPlaylists by mutableStateOf(true)
    var syncFavorites by mutableStateOf(false)
    var favoritesName by mutableStateOf("Liked Songs")
    var mirrorMode by mutableStateOf(false)
    var parallelism by mutableStateOf(4)

    var testState by mutableStateOf<TestState>(TestState.Idle)
        private set
    var configured by mutableStateOf(false)
        private set

    val progress = SyncBus.progress
    val running = SyncBus.running

    // Push playlist
    val pushProgress = PushBus.progress
    val pushRunning = PushBus.running
    val pushPlans = PushBus.plans

    init {
        viewModelScope.launch {
            val c = store.configFlow.first()
            baseUrl = c.baseUrl
            username = c.username
            rootFolder = c.rootFolder.ifBlank {
                java.io.File(android.os.Environment.getExternalStorageDirectory(), "Music/NaviSync").path
            }
            downloadCovers = c.downloadCovers
            syncPlaylists = c.syncPlaylists
            syncFavorites = c.syncFavorites
            favoritesName = c.favoritesPlaylistName
            mirrorMode = c.mirrorMode
            parallelism = c.parallelism
            password = store.readPassword()
            configured = c.isComplete
        }
    }

    /** Nome preferiti da usare nel push, solo se la funzione è attiva (altrimenti resta una playlist normale). */
    private fun favoritesNameOrNull(): String? =
        if (syncFavorites) favoritesName.trim().ifBlank { "Liked Songs" } else null

    private fun currentConfig() = ServerConfig(
        baseUrl = baseUrl.trim(),
        username = username.trim(),
        rootFolder = rootFolder.trim(),
        downloadCovers = downloadCovers,
        syncPlaylists = syncPlaylists,
        syncFavorites = syncFavorites,
        favoritesPlaylistName = favoritesName.trim().ifBlank { "Liked Songs" },
        mirrorMode = mirrorMode,
        parallelism = parallelism,
    )

    fun testConnection() {
        testState = TestState.Testing
        viewModelScope.launch {
            testState = try {
                withContext(Dispatchers.IO) {
                    SubsonicClient(baseUrl.trim(), username.trim(), password).ping()
                }
                TestState.Ok
            } catch (e: Exception) {
                TestState.Error(e.message ?: "Connessione fallita")
            }
        }
    }

    fun save(onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            store.saveConfig(currentConfig())
            store.savePassword(password)
            configured = currentConfig().isComplete
            onSaved()
        }
    }

    fun startSync() {
        save()
        SyncWorker.enqueue(getApplication())
    }

    /** Analizza i m3u selezionati (nome, contenuto già letti dalla UI) abbinandoli al server. */
    fun analyzeM3u(files: List<Pair<String, String>>) {
        if (files.isEmpty() || pushRunning.value) return
        val parsed = files.map { (name, content) -> M3uParser.parse(name, content) }
        PushBus.setPlans(emptyList())
        PushBus.setRunning(true)
        viewModelScope.launch {
            try {
                val plans = withContext(Dispatchers.IO) {
                    PushEngine(SubsonicClient(baseUrl.trim(), username.trim(), password), favoritesNameOrNull())
                        .analyze(parsed) { PushBus.update(it) }
                }
                PushBus.setPlans(plans)
            } catch (e: Exception) {
                PushBus.update(
                    PushProgress(phase = PushProgress.Phase.FAILED, error = e.message ?: "Analisi fallita")
                )
            } finally {
                PushBus.setRunning(false)
            }
        }
    }

    /** Carica su Navidrome le playlist non saltate del piano corrente. */
    fun pushPlaylists() {
        val plans = pushPlans.value
        if (plans.none { !it.skipped } || pushRunning.value) return
        PushBus.setRunning(true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PushEngine(SubsonicClient(baseUrl.trim(), username.trim(), password), favoritesNameOrNull())
                        .push(plans) { PushBus.update(it) }
                }
            } catch (e: Exception) {
                PushBus.update(
                    PushProgress(phase = PushProgress.Phase.FAILED, error = e.message ?: "Push fallito")
                )
            } finally {
                PushBus.setRunning(false)
            }
        }
    }
}
