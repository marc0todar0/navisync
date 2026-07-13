package eu.todaro.navisync.sync

import eu.todaro.navisync.domain.RemoteSong
import java.net.URLDecoder

/** Esito dell'abbinamento di una playlist alle canzoni del server. */
data class MatchResult(
    val songIds: List<String>,   // id abbinati, nell'ordine della playlist (solo se completo)
    val matched: Int,
    val total: Int,
    val unmatched: List<String>, // voci grezze che non hanno trovato match
) {
    val complete: Boolean get() = unmatched.isEmpty() && total > 0
}

/**
 * Abbina le voci di un m3u alle canzoni del server per SUFFISSO di path.
 *
 * Ponte: NaviSync scarica ogni traccia in `root/sanitizeRelative(song.path)`, quindi il path
 * relativo su disco coincide con `sanitizeRelative(song.path)`. Le voci del m3u (scritte da Auxio)
 * puntano a quei file: la loro coda di segmenti coincide con la chiave del server. Il confronto
 * per suffisso rende irrilevante il prefisso assoluto del dispositivo.
 */
class PlaylistMatcher(songs: List<RemoteSong>) {

    // Chiave = path relativo sanificato, normalizzato POSIX + lowercase → songId.
    private val byFullPath = HashMap<String, String>()
    // Fallback per suffisso: possono mappare a più id → si accetta solo se univoco.
    private val byAlbumFile = HashMap<String, MutableSet<String>>()
    private val byFile = HashMap<String, MutableSet<String>>()

    init {
        for (s in songs) {
            val segs = normalizedSegments(PathUtils.sanitizeRelative(s.path))
            if (segs.isEmpty()) continue
            val full = segs.joinToString("/")
            byFullPath.putIfAbsent(full, s.id)
            if (segs.size >= 2) {
                byAlbumFile.getOrPut(segs.takeLast(2).joinToString("/")) { HashSet() }.add(s.id)
            }
            byFile.getOrPut(segs.last()) { HashSet() }.add(s.id)
        }
    }

    fun match(entries: List<String>): MatchResult {
        val ids = ArrayList<String>(entries.size)
        val unmatched = ArrayList<String>()
        for (raw in entries) {
            val id = matchOne(raw)
            if (id != null) ids.add(id) else unmatched.add(raw)
        }
        return MatchResult(
            songIds = if (unmatched.isEmpty()) ids else emptyList(),
            matched = ids.size,
            total = entries.size,
            unmatched = unmatched,
        )
    }

    private fun matchOne(raw: String): String? {
        val segs = normalizedSegments(decode(raw))
        if (segs.isEmpty()) return null

        // 1) suffisso full-path più lungo con hit univoco
        for (start in segs.indices) {
            val suffix = segs.subList(start, segs.size).joinToString("/")
            val id = byFullPath[suffix]
            if (id != null) return id
        }
        // 2) fallback album/file, solo se univoco
        if (segs.size >= 2) {
            byAlbumFile[segs.takeLast(2).joinToString("/")]?.let { if (it.size == 1) return it.first() }
        }
        // 3) fallback solo filename, solo se univoco
        byFile[segs.last()]?.let { if (it.size == 1) return it.first() }
        return null
    }

    private fun decode(raw: String): String {
        val noScheme = raw.removePrefix("file://").removePrefix("//")
        return try {
            URLDecoder.decode(noScheme, "UTF-8")
        } catch (_: Exception) {
            noScheme
        }
    }

    /** Normalizza a segmenti POSIX lowercased, scartando vuoti/`.`/`..`. */
    private fun normalizedSegments(path: String): List<String> =
        path.replace('\\', '/').split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { it.lowercase() }
}
