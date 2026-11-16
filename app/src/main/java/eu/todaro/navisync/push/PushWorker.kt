package eu.todaro.navisync.push

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.todaro.navisync.MainActivity
import eu.todaro.navisync.NaviSyncApp
import eu.todaro.navisync.data.store.SettingsStore
import eu.todaro.navisync.data.subsonic.SubsonicClient
import eu.todaro.navisync.data.subsonic.humanMessage
import eu.todaro.navisync.domain.PushProgress
import eu.todaro.navisync.domain.PushReport
import eu.todaro.navisync.sync.PushBus
import eu.todaro.navisync.sync.PushEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Carica su Navidrome le playlist di [PushPayload] in una sola passata (analisi + upload), in
 * background e con notifica. Lo usano sia la scheda "Carica playlist" sia il tasto di Auxio.
 */
class PushWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val store = SettingsStore(appContext)
    private val notifManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val config = store.configFlow.first()
        val playlists = PushPayload.load(applicationContext)
        if (playlists.isEmpty()) return Result.success()

        owner = id
        PushBus.setPlans(emptyList())
        PushBus.setRunning(true)
        notifManager.cancel(RESULT_NOTIF_ID)
        // Come nel SyncWorker: se il foreground service non parte, il lavoro prosegue comunque.
        runCatching { setForeground(foregroundInfo(PushProgress(phase = PushProgress.Phase.ANALYZING))) }

        return try {
            if (config.baseUrl.isBlank() || config.username.isBlank()) {
                throw IllegalStateException("Server non configurato: apri NaviSync e salva URL e credenziali.")
            }
            val client = SubsonicClient(config.baseUrl, config.username, store.readPassword())
            val favorites = if (config.syncFavorites) config.favoritesPlaylistName else null
            val engine = PushEngine(client, favorites)
            val onProgress: suspend (PushProgress) -> Unit = { p ->
                PushBus.update(p)
                runCatching { setForeground(foregroundInfo(p)) }
            }
            val report = engine.sync(playlists, onProgress)
            PushBus.setPlans(report.plans)
            notifyResult(summary(report), report.log.dropLast(1).joinToString("\n"))
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = humanMessage(e)
            PushBus.update(PushProgress(phase = PushProgress.Phase.FAILED, error = message))
            notifyResult("Sync playlist fallito", message)
            Result.failure()
        } finally {
            // Un worker rimpiazzato da uno più recente non deve spegnere lo stato di quello nuovo.
            if (owner == id) PushBus.setRunning(false)
        }
    }

    private fun summary(r: PushReport): String = buildList {
        if (r.created > 0) add("${r.created} create")
        if (r.replaced > 0) add("${r.replaced} aggiornate")
        if (r.skipped > 0) add("${r.skipped} saltate")
        val failed = r.plans.size - r.created - r.replaced - r.skipped
        if (failed > 0) add("$failed in errore")
    }.joinToString(", ").ifEmpty { "niente da fare" }.let { "Playlist: $it" }

    private fun notifyResult(title: String, detail: String) {
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(applicationContext, NaviSyncApp.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(detail.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { notifManager.notify(RESULT_NOTIF_ID, notif) }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(PushProgress(phase = PushProgress.Phase.ANALYZING))

    private fun foregroundInfo(p: PushProgress): ForegroundInfo {
        val indexing = p.phase == PushProgress.Phase.ANALYZING && p.total == 0
        val text = when {
            indexing -> p.current.ifBlank { "Indicizzo la libreria…" }
            p.phase == PushProgress.Phase.ANALYZING -> "Abbino le playlist ${p.done}/${p.total}"
            p.phase == PushProgress.Phase.PUSHING -> "Carico le playlist ${p.done}/${p.total}"
            else -> "Sync playlist…"
        }
        val notif: Notification = NotificationCompat.Builder(applicationContext, NaviSyncApp.CHANNEL_ID)
            .setContentTitle("NaviSync · playlist")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(p.total, p.done, indexing)
            .build()
        return ForegroundInfo(PROGRESS_NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val PROGRESS_NOTIF_ID = 43
        private const val RESULT_NOTIF_ID = 44
        private const val WORK_NAME = "navisync_push"

        /** Id del worker che possiede lo stato "in corso" di [PushBus]. */
        @Volatile
        private var owner: UUID? = null

        /**
         * Avvia il caricamento delle playlist salvate in [PushPayload]. Se ce n'è già uno in corso
         * viene rimpiazzato: vince sempre l'ultima versione delle playlist.
         */
        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<PushWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
