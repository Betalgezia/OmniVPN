package com.betalgezia.omnivpn.vpn

import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.DnsQuery
import io.nekohasekai.libbox.HTTPHeaders
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Checks the RUNNING VPN engine's own outbound from inside sing-box:
 * CommandClient.getURLViaOutbound(tag, ...) makes the request through exactly
 * that outbound, so Android routing can't get in the way.
 *
 * Replaces TrafficProbe as the primary check (October 2026): TrafficProbe's
 * requests go through Android's VPN network, and right after a hot reload a
 * request bound to that network was answered in 0.1 s while sing-box never
 * saw it and the new server was dead - a false "Подключено". This is the same
 * mechanism the "Full" server test uses, which has never shown that problem.
 *
 * Like NodeHealthChecker, only an exact "204, empty body" counts: a Reality
 * camouflage site answering a wrong-credentials handshake has no reason to
 * produce that.
 */
object CoreProbe {

    sealed interface Outcome {
        data class Ok(val latencyMs: Long) : Outcome
        data class Dead(val reason: String, val timedOut: Boolean) : Outcome
        /** The core can't test this tag (e.g. not an outbound) - use another method. */
        data class Unsupported(val reason: String) : Outcome
    }

    /**
     * One probe and, only if it ran out of time, one more patient attempt -
     * a cold probe pays for the proxy handshake too, which can take seconds.
     */
    suspend fun check(tag: String, firstTimeoutMs: Int = FIRST_TIMEOUT_MS, retryTimeoutMs: Int = RETRY_TIMEOUT_MS): Outcome {
        val client = runCatching { connect() }.getOrElse {
            return Outcome.Unsupported(it.message ?: "command client unavailable")
        }
        try {
            val first = probeOnce(client, tag, firstTimeoutMs)
            if (first !is Outcome.Dead || !first.timedOut || retryTimeoutMs <= 0) return first
            android.util.Log.i(TAG, "check: tag=$tag timed out at ${firstTimeoutMs}ms, retrying at ${retryTimeoutMs}ms")
            return probeOnce(client, tag, retryTimeoutMs)
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private suspend fun probeOnce(client: CommandClient, tag: String, timeoutMs: Int): Outcome {
        val watchdogMs = (timeoutMs + WATCHDOG_GRACE_MS).toLong()
        val started = System.nanoTime()
        val result = withTimeoutOrNull(watchdogMs) {
            runCatching {
                withContext(Dispatchers.IO) {
                    client.getURLViaOutbound(tag, TEST_URL, timeoutMs, MAX_RESPONSE_BYTES, HTTPHeaders())
                }
            }
        } ?: return Outcome.Dead("watchdog fired after ${watchdogMs}ms", timedOut = true).also {
            android.util.Log.w(TAG, "probe: tag=$tag watchdog fired after ${watchdogMs}ms")
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        return result.fold(
            onSuccess = { r ->
                val status = r.status()
                val content = r.content().orEmpty()
                android.util.Log.i(TAG, "probe: tag=$tag status=$status elapsed=${r.elapsedMs()}ms took=${tookMs}ms")
                if (status == EXPECTED_STATUS && content.isEmpty()) {
                    Outcome.Ok(r.elapsedMs().toLong())
                } else {
                    Outcome.Dead("unexpected response (HTTP $status)", timedOut = false)
                }
            },
            onFailure = { error ->
                val reason = error.message ?: "probe failed"
                android.util.Log.w(TAG, "probe: tag=$tag failed after ${tookMs}ms: $reason")
                when {
                    // The core doesn't know the tag as an outbound (e.g. an
                    // endpoint on a build that doesn't expose those here).
                    reason.contains("not found", ignoreCase = true) -> Outcome.Unsupported(reason)
                    reason.contains("deadline exceeded", ignoreCase = true) ||
                        reason.contains("timeout", ignoreCase = true) -> Outcome.Dead(reason, timedOut = true)
                    else -> Outcome.Dead(reason, timedOut = false)
                }
            }
        )
    }

    private suspend fun connect(): CommandClient {
        val ready = CompletableDeferred<Unit>()
        val handler = object : CommandClientHandler {
            override fun connected() {
                ready.complete(Unit)
            }

            override fun disconnected(message: String) {
                ready.completeExceptionally(IllegalStateException(message.ifBlank { "disconnected" }))
            }

            override fun clearLogs() {}
            override fun initializeClashMode(modeList: StringIterator, currentMode: String) {}
            override fun updateClashMode(newMode: String) {}
            override fun setDefaultLogLevel(level: Int) {}
            override fun writeConnectionEvents(events: ConnectionEvents) {}
            override fun writeDNSQuery(query: DnsQuery) {}
            override fun writeGroups(groups: OutboundGroupIterator) {}
            override fun writeLogs(messages: LogIterator) {}
            override fun writeOutbounds(outbounds: OutboundGroupItemIterator) {}
            override fun writeStatus(status: StatusMessage) {}
        }
        val client = withContext(Dispatchers.IO) {
            Libbox.newCommandClient(handler, CommandClientOptions()).also { it.connect() }
        }
        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            runCatching { ready.await() }.isSuccess
        } ?: false
        if (!connected) {
            runCatching { client.disconnect() }
            error("Timed out connecting to the sing-box command socket")
        }
        return client
    }

    private const val TAG = "CoreProbe"
    const val FIRST_TIMEOUT_MS = 5000
    const val RETRY_TIMEOUT_MS = 6000
    private const val WATCHDOG_GRACE_MS = 1500
    private const val CONNECT_TIMEOUT_MS = 3000L
    private const val MAX_RESPONSE_BYTES = 8192
    private const val EXPECTED_STATUS = 204
    private const val TEST_URL = "https://www.gstatic.com/generate_204"
}
