package com.betalgezia.omnivpn.vpn

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Whether traffic actually flows through the tunnel the engine reports as up. */
enum class TrafficStatus { UNKNOWN, CHECKING, OK, NONE }

/**
 * "Connected" used to mean only "sing-box started" - nothing confirmed a
 * handshake or a single byte through the tunnel, so a WARP peer that never
 * completed its handshake (wrong endpoint/obfuscation for the network) still
 * showed "Подключено" in the app, the notification and the tile.
 *
 * This makes a few tiny HTTPS requests from the app's own process. OmniVPN
 * does not exclude itself from its own VPN (only sing-box's sockets are
 * protect()ed), so these go through the TUN exactly like any other app's
 * traffic, DNS included. Any HTTP response at all proves the tunnel carries
 * traffic; only "no response from any URL in any attempt" counts as dead.
 */
object TrafficProbe {

    private val URLS = listOf(
        "https://cp.cloudflare.com/generate_204",
        "https://connectivitycheck.gstatic.com/generate_204"
    )
    private const val ATTEMPTS = 3
    private const val TIMEOUT_MS = 5_000
    private const val PAUSE_MS = 1_500L

    suspend fun check(): Boolean {
        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) delay(PAUSE_MS)
            for (url in URLS) {
                if (requestOnce(url)) return true
            }
        }
        return false
    }

    private suspend fun requestOnce(url: String): Boolean = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.requestMethod = "GET"
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
