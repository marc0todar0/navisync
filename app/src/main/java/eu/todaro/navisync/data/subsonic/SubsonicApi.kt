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
}
