package eu.todaro.navisync.data.subsonic

import retrofit2.http.GET
import retrofit2.http.Query

interface SubsonicApi {
    @GET("rest/ping.view")
    suspend fun ping(): SubsonicEnvelope

    @GET("rest/getMusicFolders.view")
    suspend fun getMusicFolders(): SubsonicEnvelope

    @GET("rest/getArtists.view")
    suspend fun getArtists(): SubsonicEnvelope

    @GET("rest/getArtist.view")
    suspend fun getArtist(@Query("id") id: String): SubsonicEnvelope

    @GET("rest/getAlbum.view")
    suspend fun getAlbum(@Query("id") id: String): SubsonicEnvelope

    @GET("rest/getPlaylists.view")
    suspend fun getPlaylists(): SubsonicEnvelope

    @GET("rest/getPlaylist.view")
    suspend fun getPlaylist(@Query("id") id: String): SubsonicEnvelope

    /** Le tracce con la stella (i preferiti dell'utente). */
    @GET("rest/getStarred2.view")
    suspend fun getStarred2(): SubsonicEnvelope

    /** Mette la stella alle tracce indicate. Retrofit espande [ids] in `id=a&id=b…`. */
    @GET("rest/star.view")
    suspend fun star(@Query("id") ids: List<String>): SubsonicEnvelope

    /** Toglie la stella dalle tracce indicate. */
    @GET("rest/unstar.view")
    suspend fun unstar(@Query("id") ids: List<String>): SubsonicEnvelope

    /**
     * Crea una nuova playlist (con [name]) o ne sostituisce integralmente il contenuto (con [playlistId]).
     * Parametri in query string: Navidrome li legge dall'URL (come tutti gli altri endpoint .view),
     * non dal body form. Retrofit espande la lista [songIds] in `songId=a&songId=b…`.
     */
    @GET("rest/createPlaylist.view")
    suspend fun createPlaylist(
        @Query("name") name: String?,
        @Query("playlistId") playlistId: String?,
        @Query("songId") songIds: List<String>,
    ): SubsonicEnvelope
}
