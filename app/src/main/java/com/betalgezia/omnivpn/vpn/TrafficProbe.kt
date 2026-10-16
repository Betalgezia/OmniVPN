package com.betalgezia.omnivpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
    private const val VPN_POLL_MS = 100L

    // Requests run here, not in the caller's scope: HttpURLConnection blocks
    // and ignores cancellation, so a structured scope would make check() wait
    // for the slowest straggler instead of returning on the first answer.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun check(context: Context): Result {
        val started = System.nanoTime()
        val deadline = started + TOTAL_MS * 1_000_000
        val hostAnswered = CompletableDeferred<Unit>()
        val ipAnswered = AtomicBoolean(false)

        // Requests MUST be pinned to the VPN network. Right after the TUN is
        // established, Android takes a moment to move the app's own sockets
        // onto it; an unpinned request made in that window goes straight out
        // over mobile data and gets a 204 even when the proxy is dead - the
        // false "Подключено" seen on 2026-10-14 (probe answered in 96 ms
        // while every real connection through the tunnel timed out).
        val vpn = awaitVpnNetwork(context, deadline)
        if (vpn == null) {
            android.util.Log.w(TAG, "check: no VPN network appeared - reporting NONE")
            return Result.NONE
        }

        val targets = HOST_URLS.map { it to true } + (IP_URL to false)
        val jobs = targets.map { (url, isHost) ->
            scope.launch {
                while (isActive && System.nanoTime() < deadline) {
                    // Re-resolve every attempt: after a hot reload the old VPN
                    // network can linger for a moment before the new one replaces it.
                    val network = currentVpnNetwork(context) ?: vpn
                    if (requestOnce(network, url)) {
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

    /**
     * Polls for the VPN network (only one VPN can be up at a time, so any
     * TRANSPORT_VPN network is ours) until [deadline]. Returns null if none
     * shows up, in which case nothing can have gone through the tunnel.
     */
    private suspend fun awaitVpnNetwork(context: Context, deadline: Long): Network? {
        while (System.nanoTime() < deadline) {
            currentVpnNetwork(context)?.let { return it }
            delay(VPN_POLL_MS)
        }
        return null
    }

    /** The newest TRANSPORT_VPN network, or null. Network ids only grow. */
    private fun currentVpnNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        return cm.allNetworks
            .filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
            .maxByOrNull { it.toString().toLongOrNull() ?: 0L }
    }

    private fun requestOnce(network: Network, url: String): Boolean {
        val started = System.nanoTime()
        return runCatching {
            // network.openConnection binds the socket AND its DNS lookup to
            // the VPN network, so the request can't leak around the tunnel.
            val connection = network.openConnection(URL(url)) as HttpURLConnection
            try {
                connection.connectTimeout = REQUEST_TIMEOUT_MS
                connection.readTimeout = REQUEST_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Connection", "close")
                val code = connection.responseCode
                android.util.Log.i(TAG, "probe $url via vpn net $network -> $code in ${(System.nanoTime() - started) / 1_000_000} ms")
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
