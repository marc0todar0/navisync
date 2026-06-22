package eu.todaro.navisync.data.subsonic

import java.security.MessageDigest

/** Genera i parametri di autenticazione Subsonic (token + salt) per ogni richiesta. */
object SubsonicAuth {
    const val API_VERSION = "1.16.1"
    const val CLIENT_NAME = "navisync"

    private const val HEX = "0123456789abcdef"

    fun randomSalt(seed: Long): String {
        // Salt deterministico-per-chiamata: niente Math.random richiesto, basta variare il seed.
        var x = seed xor 0x5DEECE66DL
        val sb = StringBuilder()
        repeat(12) {
            x = (x * 0x5DEECE66DL + 0xBL) and 0xFFFFFFFFFFFFL
            sb.append(HEX[((x ushr 16) and 0xF).toInt()])
        }
        return sb.toString()
    }

    fun token(password: String, salt: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest((password + salt).toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(HEX[(b.toInt() ushr 4) and 0xF])
            sb.append(HEX[b.toInt() and 0xF])
        }
        return sb.toString()
    }
}
