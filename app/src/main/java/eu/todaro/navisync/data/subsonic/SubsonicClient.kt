package eu.todaro.navisync.data.subsonic

import eu.todaro.navisync.domain.RemotePlaylist
import eu.todaro.navisync.domain.RemoteSong
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class SubsonicException(val code: Int, message: String) : Exception(message)

/**
 * Client per un server Subsonic/Navidrome. Aggiunge i parametri di auth a ogni richiesta
 * tramite interceptor, così anche i download di file e copertine sono già autenticati.
 */
class SubsonicClient(
    rawBaseUrl: String,
    username: String,
    password: String,
    enableLogging: Boolean = false,
) {
    private val baseHttpUrl: HttpUrl = normalize(rawBaseUrl).toHttpUrl()
    private val saltSeed = AtomicLong(System.nanoTime())

    private val authInterceptor = Interceptor { chain ->
        val salt = SubsonicAuth.randomSalt(saltSeed.incrementAndGet())
        val url = chain.request().url.newBuilder()
            .addQueryParameter("u", username)
            .addQueryParameter("t", SubsonicAuth.token(password, salt))
            .addQueryParameter("s", salt)
            .addQueryParameter("v", SubsonicAuth.API_VERSION)
            .addQueryParameter("c", SubsonicAuth.CLIENT_NAME)
            .addQueryParameter("f", "json")
            .build()
        chain.proceed(chain.request().newBuilder().url(url).build())
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .apply {
            if (enableLogging) {
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
        }
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val api: SubsonicApi = Retrofit.Builder()
        .baseUrl(baseHttpUrl)
        .client(http)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(SubsonicApi::class.java)

    /** Verifica connessione + credenziali. Lancia SubsonicException/IOException in caso di errore. */
    suspend fun ping() {
        api.ping().response.check()
    }

    /** Enumera TUTTE le tracce: artisti → album → tracce. [onAlbum] notifica l'avanzamento dell'indicizzazione. */
    suspend fun listSongs(onAlbum: (done: Int, total: Int) -> Unit = { _, _ -> }): List<RemoteSong> {
        val artistsResp = api.getArtists().response.check()
        val artistIds = artistsResp.artists?.index.orEmpty().flatMap { it.artist }.map { it.id }

        // Raccoglie gli album UNICI: lo stesso album collaborativo può comparire sotto più
        // artisti (feat.), quindi deduplico per non enumerarlo più volte.
        val albumIds = LinkedHashSet<String>()
        for (artistId in artistIds) {
            val detail = api.getArtist(artistId).response.check()
            detail.artist?.album.orEmpty().forEach { albumIds.add(it.id) }
        }

        // Deduplico anche per path locale: due tracce diverse non devono mai puntare
        // allo stesso file, altrimenti i download paralleli si sovrascrivono a vicenda.
        val songs = mutableListOf<RemoteSong>()
        val seenPaths = HashSet<String>()
        var done = 0
        for (albumId in albumIds) {
            val album = api.getAlbum(albumId).response.check()
            album.album?.song.orEmpty().forEach { s ->
                val path = s.path ?: return@forEach
                if (!seenPaths.add(path)) return@forEach
                songs.add(
                    RemoteSong(
                        id = s.id,
                        path = path,
                        size = s.size,
                        suffix = s.suffix ?: path.substringAfterLast('.', ""),
                        coverArt = s.coverArt,
                        albumId = s.albumId ?: albumId,
                    )
                )
            }
            done++
            onAlbum(done, albumIds.size)
        }
        return songs
    }

    suspend fun listPlaylists(): List<RemotePlaylist> {
        val resp = api.getPlaylists().response.check()
        val refs = resp.playlists?.playlist.orEmpty()
        return refs.map { ref ->
            val detail = api.getPlaylist(ref.id).response.check()
            val paths = detail.playlist?.entry.orEmpty().mapNotNull { it.path }
            RemotePlaylist(id = ref.id, name = ref.name, songPaths = paths)
        }
    }

    /** Elenco leggero delle playlist (id + nome), senza scaricare le tracce di ciascuna. */
    suspend fun listPlaylistRefs(): List<PlaylistRef> =
        api.getPlaylists().response.check().playlists?.playlist.orEmpty()

    /**
     * Crea (se [existingId] è null) o SOSTITUISCE integralmente (se [existingId] è valorizzato)
     * una playlist sul server. Navidrome, quando riceve un playlistId, rimpiazza l'intero
     * contenuto con i soli [songIds] passati (non fa append). Ritorna l'id della playlist.
     *
     * Nota: i parametri viaggiano in query string (createPlaylist è GET), quindi playlist molto
     * grandi generano URL lunghi; per le dimensioni tipiche non è un problema.
     */
    suspend fun createOrReplacePlaylist(name: String, existingId: String?, songIds: List<String>): String {
        val resp = api.createPlaylist(
            name = if (existingId == null) name else null,
            playlistId = existingId,
            songIds = songIds,
        ).response.check()
        return resp.playlist?.id ?: existingId ?: ""
    }

    fun downloadUrl(id: String): HttpUrl =
        baseHttpUrl.newBuilder().addPathSegments("rest/download.view").addQueryParameter("id", id).build()

    fun coverUrl(id: String, size: Int = 600): HttpUrl =
        baseHttpUrl.newBuilder()
            .addPathSegments("rest/getCoverArt.view")
            .addQueryParameter("id", id)
            .addQueryParameter("size", size.toString())
            .build()

    private fun SubsonicResponse.check(): SubsonicResponse {
        if (!isOk) {
            val e = error
            throw SubsonicException(e?.code ?: -1, e?.message ?: "Errore Subsonic sconosciuto")
        }
        return this
    }

    companion object {
        fun normalize(url: String): String {
            var u = url.trim()
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            if (!u.endsWith("/")) u += "/"
            return u
        }
    }
}
