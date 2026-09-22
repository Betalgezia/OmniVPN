package com.betalgezia.omnivpn.vpn

import android.content.Context
import com.betalgezia.omnivpn.OmniVpnApplication
import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Protocol
import dagger.hilt.android.qualifiers.ApplicationContext
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.DnsQuery
import io.nekohasekai.libbox.HTTPHeaders
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface NodeHealth {
    data object Unknown : NodeHealth
    data object Checking : NodeHealth
    data class Reachable(val latencyMs: Long) : NodeHealth
    data class Unreachable(val reason: String) : NodeHealth
}

/**
 * Liveness probe for server [Node]s, run through the real sing-box core (see
 * SingBoxConfigBuilder.buildProbeConfig) rather than a bare socket connect: a raw TCP
 * check only proves *something* answers on that port, which is exactly what made the
 * previous version of this class unreliable - a proxy with a dead/misconfigured
 * handshake still passes a TCP connect. CommandClient.getURLViaOutbound() instead
 * makes a real HTTP request through the node's own outbound/endpoint and checks the
 * actual response, so it fails the same way an actual connection attempt would - see
 * runUrlTest's doc for why even that isn't quite as simple as "did a response come
 * back" once Reality-fronted servers are in the mix.
 *
 * This app's sing-box engine is a single, process-global instance (SingBoxEngine),
 * the same one the real VPN connection uses - there is no way to run a probe and a
 * live connection at once. Callers must only invoke this while VpnController's state
 * is disconnected; engine.start() enforces that itself (throws if already running),
 * so a race here fails loudly instead of interfering with a live connection.
 *
 * WARP is deliberately never probed (skipped by SingBoxConfigBuilder.buildProbeConfig)
 * - it isn't reached through the server list this checks in the first place.
 */
@Singleton
class NodeHealthChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: SingBoxEngine
) {
    private val probeMutex = Mutex()

    /**
     * @param stopOnFirstReachable stop as soon as one node passes, leaving the rest
     * unreported. For picking *a* working candidate out of an ordered list (see
     * WarpEndpointScanner) rather than grading every node, which is the difference
     * between one probe and a dozen timeouts.
     */
    suspend fun checkAll(
        nodes: List<Node>,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        stopOnFirstReachable: Boolean = false,
        onResult: suspend (Node, NodeHealth) -> Unit
    ) {
        val testable = nodes.filter { it.protocol != Protocol.WARP }
        if (testable.isEmpty()) return

        probeMutex.withLock {
            val probe = SingBoxConfigBuilder.buildProbeConfig(testable)
            android.util.Log.i(TAG, "checkAll: nodes=${testable.size} tags=${probe.tagsByNodeId} buildErrors=${probe.buildErrors}")
            for (node in testable) {
                probe.buildErrors[node.id]?.let { onResult(node, NodeHealth.Unreachable(it)) }
            }
            val tags = probe.tagsByNodeId
            if (tags.isEmpty() || probe.json == null) return@withLock

            if (engine.isRunning()) {
                for (node in testable) {
                    if (tags.containsKey(node.id)) {
                        onResult(node, NodeHealth.Unreachable("Disconnect the VPN before testing servers"))
                    }
                }
                return@withLock
            }

            runCatching {
                OmniVpnApplication.libboxReady.await()
                // emitEvents=false: this never establishes a tunnel and has nothing
                // to do with the user's real VPN state (see SingBoxEngine.start doc).
                engine.start(probe.json, ProbePlatformInterface(context), ProbeCommandServerHandler(), emitEvents = false)
            }.onFailure { t ->
                android.util.Log.e(TAG, "checkAll: probe engine failed to start", t)
                for (node in testable) {
                    if (tags.containsKey(node.id)) {
                        onResult(node, NodeHealth.Unreachable(t.message ?: "Probe failed to start"))
                    }
                }
                return@withLock
            }

            try {
                val client = connectCommandClient()
                try {
                    for (node in testable) {
                        val tag = tags[node.id] ?: continue
                        val health = runUrlTest(client, tag, timeoutMs)
                        onResult(node, health)
                        if (stopOnFirstReachable && health is NodeHealth.Reachable) break
                    }
                } finally {
                    runCatching { client.disconnect() }
                }
            } finally {
                runCatching { engine.stop(emitDisconnected = false) }
                    .onFailure { android.util.Log.w(TAG, "checkAll: engine.stop failed", it) }
            }
        }
    }

    suspend fun check(node: Node, timeoutMs: Int = DEFAULT_TIMEOUT_MS): NodeHealth {
        var result: NodeHealth = NodeHealth.Unreachable("Not tested")
        checkAll(listOf(node), timeoutMs) { _, health -> result = health }
        return result
    }

    // Was CommandClient.urlTestOutbound() - dropped after confirming on-device (see
    // PR discussion) that it reports a VLESS+Reality server as reachable even when
    // it's genuinely dead. Reality's whole design is to be indistinguishable from an
    // innocuous "camouflage" site (here play-apps-features.googleusercontent.com, a
    // real Google domain) to anyone without the right key/short_id: with the wrong
    // credentials, the TLS handshake still completes perfectly against that
    // camouflage site, so any check that only asks "did an HTTP response come back"
    // (which is what urlTestOutbound appears to do - it never surfaced an error for
    // this server) reports success regardless of whether the real proxy ever ran at
    // all. getURLViaOutbound() exposes the actual HTTP status/body instead of just a
    // latency number, so this can demand the *specific* response TEST_URL is defined
    // to give (a bare "204 No Content", nothing else) - a Reality camouflage site
    // has no reason to ever produce that exact response, so this closes the gap
    // urlTestOutbound had. Confirmed >0 args are (tag, url, timeoutMs, maxBytes,
    // headers): only doc for this call is the AAR's method signature, so both ints
    // are sized generously enough that a swapped reading of the two is still sane.
    private suspend fun runUrlTest(client: CommandClient, tag: String, timeoutMs: Int): NodeHealth {
        val watchdogMs = (timeoutMs + WATCHDOG_GRACE_MS).toLong()
        val outcome = withTimeoutOrNull(watchdogMs) {
            runCatching {
                withContext(Dispatchers.IO) {
                    client.getURLViaOutbound(tag, TEST_URL, timeoutMs, MAX_RESPONSE_BYTES, HTTPHeaders())
                }
            }
        }
        if (outcome == null) {
            android.util.Log.w(TAG, "runUrlTest: tag=$tag watchdog fired after ${watchdogMs}ms")
            return NodeHealth.Unreachable("Timed out")
        }
        return outcome.fold(
            onSuccess = { result ->
                val status = result.status()
                val elapsed = result.elapsedMs()
                val content = result.content().orEmpty()
                android.util.Log.d(
                    TAG,
                    "runUrlTest: tag=$tag status=$status elapsedMs=$elapsed contentLen=${content.length} " +
                        "remoteAddr=${result.remoteAddr()}"
                )
                if (status == EXPECTED_STATUS && content.isEmpty()) {
                    NodeHealth.Reachable(elapsed.toLong())
                } else {
                    NodeHealth.Unreachable("Unexpected response (HTTP $status)")
                }
            },
            onFailure = {
                android.util.Log.w(TAG, "runUrlTest: tag=$tag threw", it)
                NodeHealth.Unreachable(it.message ?: "Test failed")
            }
        )
    }

    private suspend fun connectCommandClient(): CommandClient {
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
        val client = Libbox.newCommandClient(handler, CommandClientOptions())
        client.connect()
        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            runCatching { ready.await() }.isSuccess
        } ?: false
        if (!connected) {
            runCatching { client.disconnect() }
            error("Timed out connecting to the sing-box command socket")
        }
        return client
    }

    private companion object {
        const val TAG = "NodeHealthChecker"
        const val DEFAULT_TIMEOUT_MS = 5000
        const val CONNECT_TIMEOUT_MS = 3000L
        const val WATCHDOG_GRACE_MS = 1500
        const val MAX_RESPONSE_BYTES = 8192
        // generate_204 is defined to answer with exactly this status and an empty
        // body - nothing else, ever - which is what makes it useful here: it's a
        // response shape a Reality camouflage site (or anything else that isn't
        // the real destination) has no legitimate reason to reproduce.
        const val EXPECTED_STATUS = 204
        const val TEST_URL = "https://www.gstatic.com/generate_204"
    }
}
