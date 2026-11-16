package eu.todaro.navisync.push

import android.content.Context
import eu.todaro.navisync.sync.M3uPlaylist
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Le playlist in attesa di essere caricate, salvate su disco così il [PushWorker] le ritrova anche
 * se il processo viene ricreato. Stesso formato JSON che manda Auxio:
 * `{"version":1,"playlists":[{"name":"…","paths":["…","…"]}]}`.
 */
object PushPayload {
    private const val FILE = "pending_push.json"

    /** Lancia un'eccezione se [json] non rispetta il formato. */
    fun parse(json: String): List<M3uPlaylist> {
        val playlists = JSONObject(json).getJSONArray("playlists")
        return List(playlists.length()) { i ->
            val pl = playlists.getJSONObject(i)
            val paths = pl.getJSONArray("paths")
            M3uPlaylist(
                name = pl.getString("name").trim().ifBlank { "playlist" },
                entries = List(paths.length()) { paths.getString(it) },
            )
        }
    }

    fun encode(playlists: List<M3uPlaylist>): String =
        JSONObject()
            .put("version", 1)
            .put(
                "playlists",
                JSONArray(playlists.map { JSONObject().put("name", it.name).put("paths", JSONArray(it.entries)) }),
            )
            .toString()

    fun save(context: Context, playlists: List<M3uPlaylist>) {
        val file = File(context.filesDir, FILE)
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(encode(playlists), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun load(context: Context): List<M3uPlaylist> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList()
        return runCatching { parse(file.readText(Charsets.UTF_8)) }.getOrDefault(emptyList())
    }
}
