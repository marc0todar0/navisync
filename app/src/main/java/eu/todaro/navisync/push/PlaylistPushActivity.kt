package eu.todaro.navisync.push

import android.app.Activity
import android.os.Bundle
import eu.todaro.navisync.data.store.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Ingresso senza interfaccia per i player: riceve le playlist come JSON (vedi [PushPayload]) in
 * un content URI, le mette in coda al [PushWorker] e si chiude subito. L'esito del caricamento
 * arriva poi con una notifica.
 *
 * Va avviata con `startActivityForResult`: è l'unico modo per sapere con certezza chi chiama, e
 * solo i pacchetti in [ALLOWED_CALLERS] possono far riscrivere le playlist sul server.
 */
class PlaylistPushActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(handle())
        finish()
    }

    private fun handle(): Int {
        if (callingPackage !in ALLOWED_CALLERS) return RESULT_CANCELED
        val uri = intent?.data ?: return RESULT_CANCELED

        val config = runBlocking { SettingsStore(this@PlaylistPushActivity).configFlow.first() }
        if (config.baseUrl.isBlank() || config.username.isBlank()) return RESULT_NOT_CONFIGURED

        val playlists = runCatching {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_BYTES + 1) }
            require(bytes != null && bytes.size <= MAX_BYTES)
            PushPayload.parse(String(bytes, Charsets.UTF_8))
        }.getOrNull() ?: return RESULT_CANCELED
        if (playlists.isEmpty()) return RESULT_CANCELED

        PushPayload.save(this, playlists)
        PushWorker.enqueue(this)
        return RESULT_OK
    }

    companion object {
        /** Risultato: NaviSync non ha ancora server e credenziali salvati. */
        const val RESULT_NOT_CONFIGURED = RESULT_FIRST_USER + 1

        private const val MAX_BYTES = 16 * 1024 * 1024

        private val ALLOWED_CALLERS = setOf(
            "org.oxycblt.auxio.fork",
            "org.oxycblt.auxio.debug",
            "org.oxycblt.auxio",
        )
    }
}
