package eu.todaro.navisync.data.subsonic

import com.squareup.moshi.Json

/** Wrapper Subsonic: { "subsonic-response": { ... } }. */
data class SubsonicEnvelope(
    @Json(name = "subsonic-response") val response: SubsonicResponse = SubsonicResponse(),
)

data class SubsonicResponse(
    val status: String = "",
    val version: String = "",
    val error: SubsonicError? = null,
    val musicFolders: MusicFolders? = null,
    val artists: ArtistsContainer? = null,
    val artist: ArtistDetail? = null,
    val album: AlbumDetail? = null,
    val playlists: PlaylistsContainer? = null,
    val playlist: PlaylistDetail? = null,
    val starred2: Starred2? = null,
) {
    val isOk: Boolean get() = status == "ok"
}

data class SubsonicError(val code: Int = 0, val message: String? = null)

data class MusicFolders(val musicFolder: List<MusicFolder> = emptyList())
data class MusicFolder(val id: Long = 0, val name: String = "")

data class ArtistsContainer(val index: List<ArtistIndex> = emptyList())
data class ArtistIndex(val name: String = "", val artist: List<ArtistRef> = emptyList())
data class ArtistRef(val id: String = "", val name: String = "", val albumCount: Int = 0)

data class ArtistDetail(
    val id: String = "",
    val name: String = "",
    val album: List<AlbumRef> = emptyList(),
)

data class AlbumRef(val id: String = "", val name: String = "", val songCount: Int = 0)

data class AlbumDetail(
    val id: String = "",
    val name: String = "",
    val song: List<SongDto> = emptyList(),
)

data class SongDto(
    val id: String = "",
    val path: String? = null,
    val size: Long = 0,
    val suffix: String? = null,
    val coverArt: String? = null,
    val albumId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

/** Risposta di getStarred2: le tracce con la stella (i "preferiti"). */
data class Starred2(val song: List<SongDto> = emptyList())

data class PlaylistsContainer(val playlist: List<PlaylistRef> = emptyList())
data class PlaylistRef(val id: String = "", val name: String = "", val songCount: Int = 0)
data class PlaylistDetail(
    val id: String = "",
    val name: String = "",
    val entry: List<SongDto> = emptyList(),
)
