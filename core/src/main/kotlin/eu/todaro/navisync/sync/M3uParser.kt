package eu.todaro.navisync.sync

/** Una playlist m3u letta da file: nome (dal filename) + voci nell'ordine originale. */
data class M3uPlaylist(
    val name: String,
    val entries: List<String>,
)

/** Parsing puro di file .m3u/.m3u8 (nessuna dipendenza Android). */
object M3uParser {

    fun parse(displayName: String, content: String): M3uPlaylist {
        val name = displayName
            .removeSuffix(".m3u8").removeSuffix(".M3U8")
            .removeSuffix(".m3u").removeSuffix(".M3U")
            .trim()
            .ifBlank { "playlist" }

        val entries = content
            .removePrefix("﻿") // BOM UTF-8 iniziale
            .split(Regex("\\r?\\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }

        return M3uPlaylist(name = name, entries = entries)
    }
}
