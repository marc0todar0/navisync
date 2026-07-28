package eu.todaro.navisync.data.store

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import eu.todaro.navisync.domain.ServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "navisync_settings")

/** Config persistita (DataStore) + password cifrata (Jetpack Security). */
class SettingsStore(private val context: Context) {

    private object Keys {
        val baseUrl = stringPreferencesKey("base_url")
        val username = stringPreferencesKey("username")
        val rootFolder = stringPreferencesKey("root_folder")
        val downloadCovers = booleanPreferencesKey("download_covers")
        val syncPlaylists = booleanPreferencesKey("sync_playlists")
        val syncFavorites = booleanPreferencesKey("sync_favorites")
        val favoritesName = stringPreferencesKey("favorites_name")
        val parallelism = intPreferencesKey("parallelism")
    }

    val configFlow: Flow<ServerConfig> = context.dataStore.data.map { p ->
        ServerConfig(
            baseUrl = p[Keys.baseUrl] ?: "",
            username = p[Keys.username] ?: "",
            rootFolder = p[Keys.rootFolder] ?: "",
            downloadCovers = p[Keys.downloadCovers] ?: true,
            syncPlaylists = p[Keys.syncPlaylists] ?: true,
            syncFavorites = p[Keys.syncFavorites] ?: false,
            favoritesPlaylistName = p[Keys.favoritesName]?.ifBlank { null } ?: "Liked Songs",
            parallelism = p[Keys.parallelism] ?: 4,
        )
    }

    suspend fun saveConfig(config: ServerConfig) {
        context.dataStore.edit { p ->
            p[Keys.baseUrl] = config.baseUrl
            p[Keys.username] = config.username
            p[Keys.rootFolder] = config.rootFolder
            p[Keys.downloadCovers] = config.downloadCovers
            p[Keys.syncPlaylists] = config.syncPlaylists
            p[Keys.syncFavorites] = config.syncFavorites
            p[Keys.favoritesName] = config.favoritesPlaylistName.trim().ifBlank { "Liked Songs" }
            p[Keys.parallelism] = config.parallelism.coerceIn(1, 8)
        }
    }

    private val securePrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "navisync_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun savePassword(password: String) {
        securePrefs.edit().putString("password", password).apply()
    }

    fun readPassword(): String = securePrefs.getString("password", "") ?: ""
}
