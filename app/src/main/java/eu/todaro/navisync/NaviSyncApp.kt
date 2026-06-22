package eu.todaro.navisync

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class NaviSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.sync_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.sync_channel_desc) }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "navisync_sync"
    }
}
