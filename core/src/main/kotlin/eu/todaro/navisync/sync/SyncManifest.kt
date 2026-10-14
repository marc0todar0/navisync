package eu.todaro.navisync.sync

import java.io.File

/**
 * Registro dei file scaricati da NaviSync: path locale (relativo alla root) → dimensione dichiarata
 * dal server al momento del download. Sta in `<root>/.navisync/manifest.tsv`.
 *
 * Serve a due cose:
 * - se Navidrome ha in indice una dimensione vecchia (file ritaggato sul server senza riscansione),
 *   il file scaricato non combacerà mai e senza registro verrebbe riscaricato a ogni sync;
 * - sapere quali file sono "nostri", così da cancellare solo quelli spariti dal server e mai
 *   file messi a mano nella cartella.
 */
class SyncManifest private constructor(private val root: File, private val file: File) {
    private val sizes = HashMap<String, Long>()

    /** Vero se il manifest non esisteva ancora: primo sync con questa versione. */
    var isNew = false
        private set

    private fun key(f: File): String = f.relativeTo(root).invariantSeparatorsPath

    @Synchronized
    fun sizeFor(f: File): Long? = sizes[key(f)]

    @Synchronized
    fun record(f: File, serverSize: Long) {
        sizes[key(f)] = serverSize
    }

    @Synchronized
    fun forget(f: File) {
        sizes.remove(key(f))
    }

    @Synchronized
    fun files(): List<File> = sizes.keys.map { File(root, it) }

    @Synchronized
    fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.bufferedWriter(Charsets.UTF_8).use { w ->
            for ((path, size) in sizes) w.append(size.toString()).append('\t').append(path).append('\n')
        }
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        fun load(root: File): SyncManifest {
            val m = SyncManifest(root, File(File(root, ".navisync"), "manifest.tsv"))
            m.isNew = !m.file.exists()
            if (!m.isNew) {
                m.file.forEachLine(Charsets.UTF_8) { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@forEachLine
                    val size = line.substring(0, tab).toLongOrNull() ?: return@forEachLine
                    m.sizes[line.substring(tab + 1)] = size
                }
            }
            return m
        }
    }
}
