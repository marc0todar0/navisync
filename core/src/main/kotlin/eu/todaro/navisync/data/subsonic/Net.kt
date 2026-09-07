package eu.todaro.navisync.data.subsonic

import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * DNS di sistema che ricorda l'ultima risoluzione riuscita per ciascun host e la riusa
 * se un lookup successivo fallisce.
 *
 * Serve perché un'operazione come l'analisi delle playlist fa centinaia di richieste su
 * minuti: su rete mobile/VPN basta un buco DNS di pochi secondi (tipico quando il nome
 * punta a un IP privato raggiungibile solo dietro VPN) per far fallire tutto, anche se
 * un attimo prima il "Verifica" — una richiesta sola — era andato a buon fine.
 */
class LastKnownGoodDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    private val cache = ConcurrentHashMap<String, List<InetAddress>>()

    override fun lookup(hostname: String): List<InetAddress> = try {
        delegate.lookup(hostname).also { if (it.isNotEmpty()) cache[hostname] = it }
    } catch (e: UnknownHostException) {
        cache[hostname] ?: throw e
    }
}

/**
 * Ritenta le richieste GET fallite per errori di rete transitori (DNS, connessione, timeout)
 * con backoff esponenziale. Sta come application interceptor sopra quello di auth, così ogni
 * tentativo rigenera salt e token.
 */
class RetryInterceptor(
    private val maxAttempts: Int = 4,
    private val baseDelayMs: Long = 500,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 1
        while (true) {
            try {
                return chain.proceed(request)
            } catch (e: IOException) {
                // Solo richieste idempotenti: qui l'API Subsonic è tutta GET.
                if (attempt >= maxAttempts || !isTransient(e) || chain.call().isCanceled()) throw e
                try {
                    Thread.sleep(baseDelayMs shl (attempt - 1))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
                attempt++
            }
        }
    }

    private fun isTransient(e: IOException): Boolean = when (e) {
        is UnknownHostException, is ConnectException, is SocketTimeoutException, is SocketException -> true
        else -> false
    }
}

/** Messaggio d'errore comprensibile, con il perché e cosa controllare. */
fun humanMessage(e: Throwable): String = when (e) {
    is UnknownHostException ->
        "DNS: il nome del server non è stato risolto (${e.message ?: "host sconosciuto"}). " +
            "Se il server sta in LAN o dietro VPN, controlla che la VPN sia attiva."
    is ConnectException ->
        "Connessione rifiutata o irraggiungibile: il nome si risolve ma il server non risponde " +
            "su quella rete (${e.message ?: "connect failed"})."
    is SocketTimeoutException -> "Timeout: il server non ha risposto in tempo."
    is SubsonicException -> "Il server ha risposto con un errore (${e.code}): ${e.message}"
    else -> e.message ?: e.javaClass.simpleName
}
