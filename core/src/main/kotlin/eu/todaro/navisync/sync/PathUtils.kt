package eu.todaro.navisync.sync

import java.io.File

object PathUtils {
    private val INVALID = Regex("""[\\:*?"<>|]""")

    /** Sanifica un path relativo (separato da '/') per il filesystem Android/SD. */
    fun sanitizeRelative(path: String): String {
        return path.split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/") { segment ->
                segment.trim().replace(INVALID, "_").take(200)
            }
    }

    fun localFileFor(root: File, remotePath: String): File =
        File(root, sanitizeRelative(remotePath))

    /** Path relativo (POSIX) da [from] (cartella) a [to] (file), per i .m3u8. */
    fun relativePath(from: File, to: File): String {
        val fromParts = from.canonicalFile.path.split('/').filter { it.isNotEmpty() }
        val toParts = to.canonicalFile.path.split('/').filter { it.isNotEmpty() }
        var common = 0
        while (common < fromParts.size && common < toParts.size && fromParts[common] == toParts[common]) common++
        val ups = List(fromParts.size - common) { ".." }
        val downs = toParts.drop(common)
        return (ups + downs).joinToString("/")
    }
}
