package eu.todaro.navisync.desktop

import eu.todaro.navisync.data.subsonic.SubsonicClient
import eu.todaro.navisync.data.subsonic.humanMessage
import eu.todaro.navisync.domain.PlaylistPlan
import eu.todaro.navisync.domain.PushProgress
import eu.todaro.navisync.domain.ServerConfig
import eu.todaro.navisync.domain.SyncProgress
import eu.todaro.navisync.sync.M3uParser
import eu.todaro.navisync.sync.PushBus
import eu.todaro.navisync.sync.PushEngine
import eu.todaro.navisync.sync.SyncBus
import eu.todaro.navisync.sync.SyncEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Equivalente desktop di SyncWorker: nessun WorkManager, solo una coroutine che pubblica
 * su [SyncBus] esattamente come su Android, così la UI resta uguale nella sostanza.
 */
object SyncRunner {

    fun start(scope: CoroutineScope, config: ServerConfig, password: String): Job =
        scope.launch(Dispatchers.IO) {
            SyncBus.setRunning(true)
            try {
                val client = SubsonicClient(config.baseUrl, config.username, password)
                SyncEngine(client, File(config.rootFolder), config).run { SyncBus.update(it) }
            } catch (e: CancellationException) {
                SyncBus.update(SyncBus.progress.value.copy(phase = SyncProgress.Phase.FAILED, error = "interrotto"))
                throw e
            } catch (e: Exception) {
                SyncBus.update(
                    SyncBus.progress.value.copy(
                        phase = SyncProgress.Phase.FAILED,
                        error = humanMessage(e),
                    )
                )
            } finally {
                SyncBus.setRunning(false)
            }
        }
}

/** Analisi e caricamento delle playlist, ricalcati su MainViewModel.analyzeM3u / pushPlaylists. */
object PushRunner {

    fun analyze(
        scope: CoroutineScope,
        config: ServerConfig,
        password: String,
        files: List<Pair<String, String>>,
    ): Job = scope.launch(Dispatchers.IO) {
        PushBus.setPlans(emptyList())
        PushBus.setRunning(true)
        try {
            val parsed = files.map { (name, content) -> M3uParser.parse(name, content) }
            val plans = engine(config, password).analyze(parsed) { PushBus.update(it) }
            PushBus.setPlans(plans)
        } catch (e: Exception) {
            PushBus.update(PushProgress(phase = PushProgress.Phase.FAILED, error = "Analisi fallita — ${humanMessage(e)}"))
        } finally {
            PushBus.setRunning(false)
        }
    }

    fun push(
        scope: CoroutineScope,
        config: ServerConfig,
        password: String,
        plans: List<PlaylistPlan>,
    ): Job = scope.launch(Dispatchers.IO) {
        PushBus.setRunning(true)
        try {
            engine(config, password).push(plans) { PushBus.update(it) }
        } catch (e: Exception) {
            PushBus.update(PushProgress(phase = PushProgress.Phase.FAILED, error = "Push fallito — ${humanMessage(e)}"))
        } finally {
            PushBus.setRunning(false)
        }
    }

    private fun engine(config: ServerConfig, password: String) = PushEngine(
        SubsonicClient(config.baseUrl, config.username, password),
        // Come su Android: il nome mappa alle stelle solo se il sync dei preferiti è attivo.
        if (config.syncFavorites) config.favoritesPlaylistName.trim().ifBlank { "Liked Songs" } else null,
    )
}

/** Raccoglie i .m3u/.m3u8 da un file singolo o da una cartella. */
fun collectM3uFiles(path: String): List<Pair<String, String>> {
    val target = File(expandHome(path.trim()))
    val files = when {
        target.isDirectory -> target.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in setOf("m3u", "m3u8") }
            .sortedBy { it.name.lowercase() }
        target.isFile -> listOf(target)
        else -> emptyList()
    }
    return files.mapNotNull { f ->
        runCatching { f.name to f.readText(Charsets.UTF_8) }.getOrNull()
    }
}

fun expandHome(path: String): String =
    if (path == "~" || path.startsWith("~/")) System.getProperty("user.home") + path.removePrefix("~") else path
