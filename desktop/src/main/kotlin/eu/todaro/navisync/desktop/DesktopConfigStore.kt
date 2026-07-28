package eu.todaro.navisync.desktop

import eu.todaro.navisync.domain.ServerConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/**
 * Config su file, equivalente desktop di SettingsStore (DataStore + EncryptedSharedPreferences).
 *
 * Su Linux non c'è un keystore di sistema su cui contare, quindi la password sta nello stesso file,
 * in chiaro: il file viene creato con permessi 0600. Se è definita la variabile d'ambiente
 * NAVISYNC_PASSWORD ha la precedenza e non viene mai scritta su disco (utile per cron e systemd).
 */
class DesktopConfigStore(val file: File = defaultFile()) {

    fun load(): Pair<ServerConfig, String> {
        val props = Properties()
        if (file.exists()) file.inputStream().use { props.load(it) }
        val config = ServerConfig(
            baseUrl = props.getProperty("baseUrl", "").trim(),
            username = props.getProperty("username", "").trim(),
            rootFolder = props.getProperty("rootFolder", "").ifBlank { defaultMusicDir() },
            downloadCovers = props.getProperty("downloadCovers", "true").toBoolean(),
            syncPlaylists = props.getProperty("syncPlaylists", "true").toBoolean(),
            syncFavorites = props.getProperty("syncFavorites", "false").toBoolean(),
            favoritesPlaylistName = props.getProperty("favoritesPlaylistName", "").ifBlank { "Liked Songs" },
            parallelism = props.getProperty("parallelism", "4").toIntOrNull()?.coerceIn(1, 8) ?: 4,
        )
        return config to (envPassword() ?: props.getProperty("password", ""))
    }

    fun save(config: ServerConfig, password: String) {
        val props = Properties()
        props["baseUrl"] = config.baseUrl
        props["username"] = config.username
        props["rootFolder"] = config.rootFolder
        props["downloadCovers"] = config.downloadCovers.toString()
        props["syncPlaylists"] = config.syncPlaylists.toString()
        props["syncFavorites"] = config.syncFavorites.toString()
        props["favoritesPlaylistName"] = config.favoritesPlaylistName
        props["parallelism"] = config.parallelism.toString()
        // Se la password arriva dall'ambiente non la persisto: resta solo lì.
        if (envPassword() == null) props["password"] = password

        file.parentFile?.mkdirs()
        restrictPermissions() // 0600 *prima* di scriverci dentro la password
        file.outputStream().use { props.store(it, "NaviSync — config (contiene la password: tienilo a 0600)") }
    }

    private fun restrictPermissions() {
        val perms = PosixFilePermissions.fromString("rw-------")
        runCatching {
            if (file.exists()) {
                Files.setPosixFilePermissions(file.toPath(), perms)
            } else {
                Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(perms))
            }
        }
    }

    private fun envPassword(): String? = System.getenv("NAVISYNC_PASSWORD")?.ifBlank { null }

    companion object {
        fun defaultFile(): File {
            val base = System.getenv("XDG_CONFIG_HOME")?.ifBlank { null }
                ?: File(System.getProperty("user.home"), ".config").path
            return File(File(base, "navisync"), "config.properties")
        }

        fun defaultMusicDir(): String = File(System.getProperty("user.home"), "Music/NaviSync").path
    }
}
