package com.betalgezia.omnivpn.vpn

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Whether traffic actually flows through the tunnel the engine reports as up. */
enum class TrafficStatus {
    UNKNOWN,
    CHECKING,
    OK,
    /** Raw IP traffic gets through but name lookups don't - sites won't open. */
    DNS_BROKEN,
    NONE
}

/**
 * "Connected" used to mean only "sing-box started" - nothing confirmed a
 * handshake or a single byte through the tunnel, so a WARP peer that never
 * completed its handshake still showed "Подключено" everywhere.
 *
 * Makes tiny HTTP requests from the app's own process. OmniVPN doesn't exclude
 * itself from its own VPN (only sing-box's sockets are protect()ed), so they go
 * through the TUN like any other app's traffic, DNS included.
 *
 * Tuned for speed (October 2026): plain HTTP instead of HTTPS - the request is
 * encrypted by the tunnel anyway, and skipping TLS saves one or two round
 * trips through the proxy; every URL is tried at once and the first answer
 * wins; and the whole check is capped at [TOTAL_MS] instead of up to a minute
 * of sequential retries. Cleartext is allowed for exactly these hosts in
 * res/xml/network_security_config.xml, nothing else.
 *
 * Any HTTP response counts as traffic. A hostname URL answering means the
 * tunnel works end to end; only the raw-IP URL answering means DNS through
 * the tunnel is the broken part.
 */
object TrafficProbe {

    enum class Result { OK, DNS_BROKEN, NONE }

    private val HOST_URLS = listOf(
        "http://cp.cloudflare.com/generate_204",
        "http://connectivitycheck.gstatic.com/generate_204"
    )
    private const val IP_URL = "http://1.1.1.1/cdn-cgi/trace"

    private const val TOTAL_MS = 7_000L
    private const val REQUEST_TIMEOUT_MS = 3_000
    private const val RETRY_PAUSE_MS = 300L

    // Requests run here, not in the caller's scope: HttpURLConnection blocks
    // and ignores cancellation, so a structured scope would make check() wait
    // for the slowest straggler instead of returning on the first answer.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun check(): Result {
        val started = System.nanoTime()
        val deadline = started + TOTAL_MS * 1_000_000
        val hostAnswered = CompletableDeferred<Unit>()
        val ipAnswered = AtomicBoolean(false)

        val targets = HOST_URLS.map { it to true } + (IP_URL to false)
        val jobs = targets.map { (url, isHost) ->
            scope.launch {
                while (isActive && System.nanoTime() < deadline) {
                    if (requestOnce(url)) {
                        if (isHost) hostAnswered.complete(Unit) else ipAnswered.set(true)
                        return@launch
                    }
                    delay(RETRY_PAUSE_MS)
                }
            }
        }

        val ok = withTimeoutOrNull(TOTAL_MS) { hostAnswered.await() } != null
        jobs.forEach { it.cancel() }
        val result = when {
            ok -> Result.OK
            ipAnswered.get() -> Result.DNS_BROKEN
            else -> Result.NONE
        }
        android.util.Log.i(TAG, "check: $result in ${(System.nanoTime() - started) / 1_000_000} ms")
        return result
    }

    private fun requestOnce(url: String): Boolean {
        val started = System.nanoTime()
        return runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = REQUEST_TIMEOUT_MS
                connection.readTimeout = REQUEST_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Connection", "close")
                val code = connection.responseCode
                android.util.Log.i(TAG, "probe $url -> $code in ${(System.nanoTime() - started) / 1_000_000} ms")
                code > 0
            } finally {
                connection.disconnect()
            }
        }.getOrElse {
            android.util.Log.w(TAG, "probe $url failed after ${(System.nanoTime() - started) / 1_000_000} ms: ${it.message}")
            false
        }
    }

    private const val TAG = "TrafficProbe"
}
