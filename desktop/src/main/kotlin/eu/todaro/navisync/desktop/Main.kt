package eu.todaro.navisync.desktop

import eu.todaro.navisync.data.subsonic.SubsonicClient
import eu.todaro.navisync.domain.SyncProgress
import eu.todaro.navisync.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        null -> Tui().run()
        "--sync" -> exitProcess(headlessSync())
        "--help", "-h" -> usage()
        else -> {
            System.err.println("Argomento sconosciuto: ${args[0]}")
            usage()
            exitProcess(2)
        }
    }
}

private fun usage() {
    println(
        """
        navisync — sincronizza una libreria Navidrome/Subsonic in una cartella locale.

          navisync            interfaccia a terminale (config, sync, upload playlist)
          navisync --sync     sync headless con la config salvata (per cron/systemd)
          navisync --help     questo messaggio

        Config: ${DesktopConfigStore.defaultFile().path}
        La password può arrivare da NAVISYNC_PASSWORD invece che dal file.
        """.trimIndent()
    )
}

/** Sync senza UI: log su stdout, avanzamento su stderr (così redirigendo stdout resta pulito). */
private fun headlessSync(): Int = runBlocking {
    val (config, password) = DesktopConfigStore().load()
    if (!config.isComplete) {
        System.err.println(
            "Configurazione incompleta (${DesktopConfigStore.defaultFile().path}): " +
                "lancia 'navisync' senza argomenti, compila e salva."
        )
        return@runBlocking 1
    }

    var lastPrinted: String? = null
    var lastDone = -1
    val engine = SyncEngine(
        SubsonicClient(config.baseUrl, config.username, password),
        File(config.rootFolder),
        config,
    )
    try {
        engine.run { p ->
            // Il log del motore è troncato a 300 righe: riparto dall'ultima riga già stampata.
            val start = lastPrinted?.let { seen -> p.log.indexOfLast { it == seen } + 1 } ?: 0
            for (i in start until p.log.size) println(p.log[i])
            p.log.lastOrNull()?.let { lastPrinted = it }

            if (p.phase == SyncProgress.Phase.DOWNLOADING && p.doneFiles != lastDone) {
                lastDone = p.doneFiles
                System.err.print("\r  ${p.doneFiles}/${p.totalFiles} file — ${p.bytesDownloaded / (1024 * 1024)} MB")
                System.err.flush()
            }
        }
        System.err.println()
        0
    } catch (e: Exception) {
        System.err.println("\nErrore: ${e.message ?: e.javaClass.simpleName}")
        1
    }
}
