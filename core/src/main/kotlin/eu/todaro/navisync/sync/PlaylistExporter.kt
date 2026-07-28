package eu.todaro.navisync.sync

import eu.todaro.navisync.domain.RemotePlaylist
import eu.todaro.navisync.domain.RemoteSong
import java.io.File

object PlaylistExporter {

    private val INVALID_NAME = Regex("""[\\/:*?"<>|]""")

    /**
     * Scrive ogni playlist come `Playlists/<nome>.m3u8` con percorsi relativi alle tracce locali.
     * Include solo le tracce effettivamente presenti su disco. Ritorna quante playlist sono state scritte.
     */
    fun export(root: File, songs: List<RemoteSong>, playlists: List<RemotePlaylist>): Int {
        val dir = File(root, "Playlists").apply { mkdirs() }
        // Mappa path remoto → file locale, solo per le tracce esistenti.
        val localByRemote = HashMap<String, File>()
        for (s in songs) {
            val f = PathUtils.localFileFor(root, s.path)
            if (f.exists()) localByRemote[s.path] = f
        }

        var written = 0
        for (pl in playlists) {
            val name = pl.name.ifBlank { "playlist" }.replace(INVALID_NAME, "_").take(120)
            val m3u = File(dir, "$name.m3u8")
            val lines = StringBuilder("#EXTM3U\n")
            var entries = 0
            for (remotePath in pl.songPaths) {
                val local = localByRemote[remotePath] ?: continue
                lines.append(PathUtils.relativePath(dir, local)).append('\n')
                entries++
            }
            if (entries > 0) {
                m3u.writeText(lines.toString(), Charsets.UTF_8)
                written++
            }
        }
        return written
    }
}
