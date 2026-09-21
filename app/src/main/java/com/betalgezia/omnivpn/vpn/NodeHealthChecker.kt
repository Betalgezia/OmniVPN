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
 * handshake still passes a TCP connect. CommandClient.urlTestOutbound() instead makes
 * a real HTTP request through the node's own outbound/endpoint, so it fails the same
 * way an actual connection attempt would.
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

    suspend fun checkAll(
        nodes: List<Node>,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
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
                        onResult(node, runUrlTest(client, tag, timeoutMs))
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

    // The native timeoutMs param is trusted to bound urlTestOutbound on its own, but
    // this adds a hard client-side ceiling too (timeoutMs + a grace window) so a
    // native call that doesn't honor it can't hang the whole batch indefinitely -
    // if it fires, that's reported as Unreachable, never silently ignored.
    //
    // Both `error` non-blank AND `delay <= 0` are treated as failure, not just
    // `error`: URLTestOutboundResult's exact semantics for a failed test aren't
    // documented in this AAR, so this doesn't assume a "success" reading (positive
    // delay, blank error) is the only way sing-box could signal a timeout/failure.
    private suspend fun runUrlTest(client: CommandClient, tag: String, timeoutMs: Int): NodeHealth {
        val watchdogMs = (timeoutMs + WATCHDOG_GRACE_MS).toLong()
        val outcome = withTimeoutOrNull(watchdogMs) {
            runCatching { withContext(Dispatchers.IO) { client.urlTestOutbound(tag, TEST_URL, timeoutMs) } }
        }
        if (outcome == null) {
            android.util.Log.w(TAG, "runUrlTest: tag=$tag watchdog fired after ${watchdogMs}ms")
            return NodeHealth.Unreachable("Timed out")
        }
        return outcome.fold(
            onSuccess = { result ->
                val error = result.error
                val delay = result.delay
                android.util.Log.d(TAG, "runUrlTest: tag=$tag delay=$delay error=${error.orEmpty()}")
                if (!error.isNullOrBlank() || delay <= 0) {
                    NodeHealth.Unreachable(error?.takeIf { it.isNotBlank() } ?: "Test failed (delay=$delay)")
                } else {
                    NodeHealth.Reachable(delay.toLong())
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
        // Same default sing-box GUI clients use for outbound latency/availability
        // testing: small, globally anycast, no TLS-handshake surprises.
        const val TEST_URL = "https://www.gstatic.com/generate_204"
    }
}
