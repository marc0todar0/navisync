package eu.todaro.navisync.sync

import eu.todaro.navisync.data.subsonic.SubsonicClient
import eu.todaro.navisync.data.subsonic.humanMessage
import eu.todaro.navisync.domain.RemoteSong
import eu.todaro.navisync.domain.ServerConfig
import eu.todaro.navisync.domain.SyncProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.File

/**
 * Sync idempotente: indicizza il server, scarica solo i file mancanti/diversi,
 * esporta le playlist e (opzionale) le copertine.
 */
class SyncEngine(
    private val client: SubsonicClient,
    private val root: File,
    private val config: ServerConfig,
) {
    private val mutex = Mutex()
    private var phase = SyncProgress.Phase.IDLE
    private var total = 0
    private var done = 0
    private var bytes = 0L
    private var current = ""
    private val log = ArrayList<String>()

    private suspend fun publish(onProgress: suspend (SyncProgress) -> Unit) {
        val snapshot = mutex.withLock {
            SyncProgress(phase, total, done, bytes, current, ArrayList(log))
        }
        onProgress(snapshot)
    }

    private suspend fun addLog(line: String) = mutex.withLock {
        log.add(line)
        if (log.size > 300) log.removeAt(0)
    }

    suspend fun run(onProgress: suspend (SyncProgress) -> Unit) {
        try {
            root.mkdirs()

            // 1) Indicizzazione
            mutex.withLock { phase = SyncProgress.Phase.INDEXING }
            addLog("Indicizzo la libreria…")
            publish(onProgress)
            val rawSongs = client.listSongs { d, t ->
                // callback non-suspend: aggiorno contatori best-effort
                current = "Indicizzo album $d/$t"
            }
            // Deduplico per PATH LOCALE FINALE: path diversi sul server possono collassare sullo
            // stesso file dopo la sanificazione (es. cartelle che differiscono per uno spazio).
            // Senza questo, due download paralleli scrivono lo stesso file -> "renamed=false".
            val byLocal = LinkedHashMap<String, RemoteSong>()
            for (s in rawSongs) byLocal.putIfAbsent(PathUtils.localFileFor(root, s.path).path, s)
            val songs = byLocal.values.toList()
            val collapsed = rawSongs.size - songs.size
            addLog(
                "Trovate ${songs.size} tracce sul server." +
                    if (collapsed > 0) " ($collapsed duplicati sullo stesso file uniti.)" else ""
            )

            // 2) Diff
            val toDownload = songs.filter { song ->
                val f = PathUtils.localFileFor(root, song.path)
                !f.exists() || (song.size > 0 && f.length() != song.size)
            }
            mutex.withLock {
                total = toDownload.size
                done = 0
                phase = SyncProgress.Phase.DOWNLOADING
            }
            addLog("${toDownload.size} file da scaricare (${songs.size - toDownload.size} già presenti).")
            publish(onProgress)

            // 3) Download paralleli
            downloadAll(toDownload, onProgress)

            // 4) Copertine
            if (config.downloadCovers) {
                downloadCovers(songs, onProgress)
            }

            // 5) Playlist (+ preferiti come playlist speciale, se abilitati)
            if (config.syncPlaylists || config.syncFavorites) {
                mutex.withLock { phase = SyncProgress.Phase.PLAYLISTS }
                publish(onProgress)
                val playlists = ArrayList<eu.todaro.navisync.domain.RemotePlaylist>()
                if (config.syncPlaylists) playlists += client.listPlaylists()
                if (config.syncFavorites) {
                    val starredPaths = client.listStarredSongs().map { it.path }
                    playlists += eu.todaro.navisync.domain.RemotePlaylist(
                        id = "starred",
                        name = config.favoritesPlaylistName,
                        songPaths = starredPaths,
                    )
                    addLog("Preferiti: ${starredPaths.size} tracce sul server.")
                }
                val written = PlaylistExporter.export(root, songs, playlists)
                addLog("Esportate $written playlist (.m3u8).")
            }

            mutex.withLock { phase = SyncProgress.Phase.DONE }
            addLog("Sync completato.")
            publish(onProgress)
        } catch (e: Exception) {
            mutex.withLock { phase = SyncProgress.Phase.FAILED }
            addLog("Errore: ${humanMessage(e)}")
            val snap = mutex.withLock {
                SyncProgress(phase, total, done, bytes, current, ArrayList(log), error = humanMessage(e))
            }
            onProgress(snap)
            throw e
        }
    }

    private suspend fun downloadAll(
        songs: List<RemoteSong>,
        onProgress: suspend (SyncProgress) -> Unit,
    ) = coroutineScope {
        val sem = Semaphore(config.parallelism.coerceIn(1, 8))
        songs.map { song ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val target = PathUtils.localFileFor(root, song.path)
                    mutex.withLock { current = target.name }
                    publish(onProgress)
                    var lastError: Exception? = null
                    for (attempt in 1..3) {
                        try {
                            downloadTo(client.downloadUrl(song.id), target)
                            lastError = null
                            break
                        } catch (e: Exception) {
                            lastError = e
                        }
                    }
                    if (lastError != null) addLog("Salto ${target.name}: ${humanMessage(lastError)}")
                    mutex.withLock {
                        done++
                        if (target.exists()) bytes += target.length()
                    }
                    publish(onProgress)
                }
            }
        }.awaitAll()
    }

    private suspend fun downloadCovers(
        songs: List<RemoteSong>,
        onProgress: suspend (SyncProgress) -> Unit,
    ) {
        // Una copertina per cartella album.
        val byFolder = LinkedHashMap<File, RemoteSong>()
        for (s in songs) {
            if (s.coverArt == null) continue
            val folder = PathUtils.localFileFor(root, s.path).parentFile ?: continue
            byFolder.putIfAbsent(folder, s)
        }
        withContext(Dispatchers.IO) {
            for ((folder, song) in byFolder) {
                val cover = File(folder, "cover.jpg")
                if (cover.exists()) continue
                try {
                    downloadTo(client.coverUrl(song.coverArt!!), cover)
                } catch (_: Exception) {
                    // copertina non critica
                }
            }
        }
        publish(onProgress)
    }

    private fun downloadTo(url: HttpUrl, target: File) {
        target.parentFile?.mkdirs()
        // .part univoco per traccia: due download non condividono mai lo stesso temporaneo.
        val part = File.createTempFile(".navisync_", ".part", target.parentFile)
        try {
            val req = Request.Builder().url(url).build()
            client.http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val body = resp.body ?: throw IllegalStateException("Risposta vuota")
                part.outputStream().use { output ->
                    body.byteStream().use { input -> input.copyTo(output, 64 * 1024) }
                }
            }
            if (!part.exists() || part.length() == 0L) {
                throw IllegalStateException("download vuoto")
            }
            if (target.exists()) target.delete()
            // renameTo su storage FUSE Android è inaffidabile: se fallisce, copio i byte;
            // se il part è già sparito è perché il rename ha di fatto spostato il file (ok).
            if (!part.renameTo(target) && part.exists()) {
                part.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
            }
        } finally {
            part.delete() // rimuove il temporaneo se ancora presente
        }
    }
}
