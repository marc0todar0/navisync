package eu.todaro.navisync.push

import android.content.Context
import android.content.Intent

/** Cosa condividono i punti d'ingresso usati dai player (Auxio). */
object PlayerBridge {
    /** I soli pacchetti che possono comandare NaviSync. */
    val ALLOWED_CALLERS = setOf(
        "org.oxycblt.auxio.fork",
        "org.oxycblt.auxio.debug",
        "org.oxycblt.auxio",
    )

    /** Broadcast inviato ai player a fine sync, così ricaricano la libreria. */
    const val ACTION_SYNC_FINISHED = "eu.todaro.navisync.action.SYNC_FINISHED"

    /** Avvisa ogni player noto che i file in locale sono cambiati. */
    fun notifySyncFinished(context: Context) {
        for (pkg in ALLOWED_CALLERS) {
            context.sendBroadcast(Intent(ACTION_SYNC_FINISHED).setPackage(pkg))
        }
    }
}
