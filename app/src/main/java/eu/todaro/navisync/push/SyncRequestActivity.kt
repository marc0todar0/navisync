package eu.todaro.navisync.push

import android.app.Activity
import android.os.Bundle
import eu.todaro.navisync.data.store.SettingsStore
import eu.todaro.navisync.sync.SyncWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Ingresso senza interfaccia per i player: avvia lo stesso sync del pulsante nell'app (download
 * dal server nella cartella locale) e si chiude subito. A fine sync i player ricevono
 * [PlayerBridge.ACTION_SYNC_FINISHED] e ricaricano la libreria.
 *
 * Come [PlaylistPushActivity], va avviata con `startActivityForResult` per poter verificare chi
 * chiama.
 */
class SyncRequestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(handle())
        finish()
    }

    private fun handle(): Int {
        if (callingPackage !in PlayerBridge.ALLOWED_CALLERS) return RESULT_CANCELED
        val config = runBlocking { SettingsStore(this@SyncRequestActivity).configFlow.first() }
        if (!config.isComplete) return PlaylistPushActivity.RESULT_NOT_CONFIGURED
        SyncWorker.enqueue(this)
        return RESULT_OK
    }
}
