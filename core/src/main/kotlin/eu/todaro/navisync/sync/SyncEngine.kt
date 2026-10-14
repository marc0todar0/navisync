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
    private val manifest by lazy { SyncManifest.load(root) }

    private companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "alac", "aiff", "ape", "wv", "mpc", "dsf")
    }

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
            var staleSize = 0
            val toDownload = songs.filter { song ->
                val f = PathUtils.localFileFor(root, song.path)
                when {
                    !f.exists() -> true
                    song.size <= 0 || f.length() == song.size -> {
                        manifest.record(f, song.size)
                        false
                    }
                    // Dimensione diversa, ma è la stessa che il server dichiarava quando l'abbiamo
                    // scaricato: è l'indice di Navidrome a essere vecchio, riscaricare non cambierebbe nulla.
                    manifest.sizeFor(f) == song.size -> {
                        staleSize++
                        false
                    }
                    else -> true
                }
            }
            mutex.withLock {
                total = toDownload.size
                done = 0
                phase = SyncProgress.Phase.DOWNLOADING
            }
            addLog("${toDownload.size} file da scaricare (${songs.size - toDownload.size} già presenti).")
            if (staleSize > 0) {
                addLog("$staleSize file con dimensione diversa da quella in indice sul server: non li riscarico (serve una scansione completa su Navidrome).")
            }
            publish(onProgress)

            // 3) Download paralleli
            downloadAll(toDownload, onProgress)
            manifest.save()

            // 3b) File scaricati in passato e non più sul server
            val serverKeys = songs.mapTo(HashSet()) { caseKey(PathUtils.localFileFor(root, it.path)) }
            if (manifest.isNew) adoptExistingFiles(songs, serverKeys)
            removeOrphans(serverKeys)
            manifest.save()

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
                    if (lastError != null) {
                        addLog("Salto ${target.name}: ${humanMessage(lastError)}")
                    } else {
                        manifest.record(target, song.size)
                    }
                    mutex.withLock {
                        done++
                        if (target.exists()) bytes += target.length()
                    }
                    publish(onProgress)
                }
            }
        }.awaitAll()
    }

    /**
     * Primo sync con il manifest: i file scaricati dalle versioni precedenti non sono registrati,
     * quindi considero di NaviSync tutti i file audio già nella cartella. Così anche gli orfani
     * vecchi vengono ripuliti (la root è pensata come cartella dedicata, es. Music/NaviSync).
     */
    private suspend fun adoptExistingFiles(songs: List<RemoteSong>, serverKeys: Set<String>) = withContext(Dispatchers.IO) {
        val audio = AUDIO_EXTENSIONS + songs.map { it.suffix.lowercase() }.filter { it.isNotEmpty() }
        val skipDirs = setOf(File(root, ".navisync"), File(root, "Playlists"))
        var adopted = 0
        root.walkTopDown()
            .onEnter { it !in skipDirs }
            .filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in audio }
            .filter { caseKey(it) !in serverKeys }
            .forEach {
                manifest.record(it, -1)
                adopted++
            }
        if (adopted > 0) addLog("Primo sync con il registro: $adopted file preesistenti non abbinati al server.")
    }

    /**
     * Cancella i file del manifest che il server non ha più (tracce rimosse, o spostate di path
     * dopo una riscansione). Tocca solo file scaricati da NaviSync, mai altro nella cartella.
     */
    private suspend fun removeOrphans(serverKeys: Set<String>) {
        val tracked = manifest.files()
        val orphans = tracked.filter { caseKey(it) !in serverKeys }
        if (orphans.isEmpty()) return
        // Rete di sicurezza: se sparisce più di metà libreria è più facile che il server sia
        // a metà scansione o abbia perso la music folder, che non che l'utente l'abbia svuotata.
        if (orphans.size * 2 > tracked.size) {
            addLog("${orphans.size} file non più sul server su ${tracked.size}: troppi, non cancello nulla.")
            return
        }
        withContext(Dispatchers.IO) {
            for (f in orphans) {
                if (f.exists() && !f.delete()) continue
                manifest.forget(f)
                pruneEmptyDirs(f.parentFile)
            }
        }
        addLog("Rimossi ${orphans.size} file non più presenti sul server.")
    }

    /**
     * Chiave di confronto senza maiuscole: lo storage Android (FUSE/sdcardfs, exFAT) non le distingue,
     * quindi `MBOSS/PARE.mp3` del server e la cartella `Mboss/` già su disco sono lo stesso file.
     * Nel dubbio un file resta: meglio un orfano in più che cancellare una traccia buona.
     */
    private fun caseKey(f: File): String = f.relativeTo(root).invariantSeparatorsPath.lowercase()

    /** Risale da [dir] verso la root togliendo le cartelle rimaste vuote (o con la sola copertina). */
    private fun pruneEmptyDirs(dir: File?) {
        val stop = root.absoluteFile
        var d = dir?.absoluteFile
        while (d != null && d != stop && d.startsWith(stop)) {
            val rest = d.listFiles()?.filter { it.name != "cover.jpg" } ?: return
            if (rest.isNotEmpty()) return
            File(d, "cover.jpg").delete()
            if (!d.delete()) return
            d = d.parentFile
        }
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
