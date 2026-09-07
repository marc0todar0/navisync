package eu.todaro.navisync.sync

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.todaro.navisync.NaviSyncApp
import eu.todaro.navisync.data.store.SettingsStore
import eu.todaro.navisync.data.subsonic.SubsonicClient
import eu.todaro.navisync.data.subsonic.humanMessage
import eu.todaro.navisync.domain.SyncProgress
import kotlinx.coroutines.flow.first
import java.io.File

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val store = SettingsStore(appContext)
    private val notifManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val config = store.configFlow.first()
        if (!config.isComplete) return Result.failure()
        val password = store.readPassword()

        SyncBus.setRunning(true)
        // L'avvio del foreground service può fallire su Android 14/15 (restrizioni FGS):
        // non deve mai far cadere il sync, che può comunque proseguire in background.
        runCatching { setForeground(foregroundInfo(SyncProgress(phase = SyncProgress.Phase.INDEXING))) }

        return try {
            val client = SubsonicClient(config.baseUrl, config.username, password)
            val engine = SyncEngine(client, File(config.rootFolder), config)
            engine.run { progress ->
                SyncBus.update(progress)
                runCatching { setForeground(foregroundInfo(progress)) }
            }
            Result.success()
        } catch (e: Exception) {
            SyncBus.update(
                SyncBus.progress.value.copy(
                    phase = SyncProgress.Phase.FAILED,
                    error = humanMessage(e),
                )
            )
            Result.failure()
        } finally {
            SyncBus.setRunning(false)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(SyncProgress(phase = SyncProgress.Phase.INDEXING))

    private fun foregroundInfo(p: SyncProgress): ForegroundInfo {
        val text = when (p.phase) {
            SyncProgress.Phase.INDEXING -> "Indicizzazione…"
            SyncProgress.Phase.DOWNLOADING -> "Download ${p.doneFiles}/${p.totalFiles}"
            SyncProgress.Phase.PLAYLISTS -> "Esporto playlist…"
            else -> "Sync in corso…"
        }
        val notif: Notification = NotificationCompat.Builder(applicationContext, NaviSyncApp.CHANNEL_ID)
            .setContentTitle("NaviSync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(p.totalFiles, p.doneFiles, p.phase == SyncProgress.Phase.INDEXING)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val NOTIF_ID = 42
        const val WORK_NAME = "navisync_sync"

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                androidx.work.ExistingWorkPolicy.KEEP,
                req,
            )
        }
    }
}
