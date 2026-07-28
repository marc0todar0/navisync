package eu.todaro.navisync.domain

/** Connessione + opzioni di sync persistite. La password è salvata cifrata a parte. */
data class ServerConfig(
    val baseUrl: String = "",
    val username: String = "",
    val rootFolder: String = "",
    val downloadCovers: Boolean = true,
    val syncPlaylists: Boolean = true,
    val syncFavorites: Boolean = false,
    val favoritesPlaylistName: String = "Liked Songs",
    val parallelism: Int = 4,
) {
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() && rootFolder.isNotBlank()
}

/** Una traccia come vista sul server, con il path relativo alla music folder. */
data class RemoteSong(
    val id: String,
    val path: String,
    val size: Long,
    val suffix: String,
    val coverArt: String?,
    val albumId: String?,
)

data class RemotePlaylist(
    val id: String,
    val name: String,
    val songPaths: List<String>,
)

/** Stato di avanzamento emesso durante il sync. */
data class SyncProgress(
    val phase: Phase = Phase.IDLE,
    val totalFiles: Int = 0,
    val doneFiles: Int = 0,
    val bytesDownloaded: Long = 0,
    val currentFile: String = "",
    val log: List<String> = emptyList(),
    val error: String? = null,
) {
    enum class Phase { IDLE, INDEXING, DOWNLOADING, PLAYLISTS, DONE, FAILED }
}
