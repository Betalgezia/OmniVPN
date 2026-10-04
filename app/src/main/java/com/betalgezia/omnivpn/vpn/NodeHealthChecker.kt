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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

enum class TestMode {
    /**
     * Opens a socket to the server and nothing more. Seconds for a whole list,
     * and a failure here is conclusive - nothing is listening, so the server is
     * definitely unusable. A pass is *not* conclusive: it only shows the port
     * answers, not that the proxy behind it works.
     */
    QUICK,

    /**
     * Fetches a URL through the node's own outbound in the real core, so a
     * broken handshake or wrong credentials fail the way they would in use.
     * Slow, and it pays a cold handshake per node, so its timings run high.
     */
    FULL
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

    // Singleton-lifetime scope for quick probes that must not pin the caller's
    // scope while a blocking DNS lookup finishes - see quickCheckBounded.
    private val detachedIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        mode: TestMode = TestMode.FULL,
        onResult: suspend (Node, NodeHealth) -> Unit
    ) {
        val testable = nodes.filter { it.protocol != Protocol.WARP }
        if (testable.isEmpty()) return

        if (mode == TestMode.QUICK) {
            quickInParallel(testable, stopOnFirstReachable, onResult)
            return
        }

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

            // One outbound the core refuses fails Libbox.checkConfig() for the whole
            // config, and engine.start() would then pin that node's error on every node
            // in the batch. vetProbeConfig() takes such nodes out first.
            val vetted = try {
                OmniVpnApplication.libboxReady.await()
                vetProbeConfig(testable, probe, onResult)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "checkAll: config pre-check failed, testing the batch as built", t)
                probe
            }
            val vettedJson = vetted.json ?: return@withLock
            val vettedTags = vetted.tagsByNodeId

            runCatching {
                OmniVpnApplication.libboxReady.await()
                // emitEvents=false: this never establishes a tunnel and has nothing
                // to do with the user's real VPN state (see SingBoxEngine.start doc).
                engine.start(vettedJson, ProbePlatformInterface(context), ProbeCommandServerHandler(), emitEvents = false)
            }.onFailure { t ->
                android.util.Log.e(TAG, "checkAll: probe engine failed to start", t)
                for (node in testable) {
                    if (vettedTags.containsKey(node.id)) {
                        onResult(node, NodeHealth.Unreachable(t.message ?: "Probe failed to start"))
                    }
                }
                return@withLock
            }

            try {
                val sentinelClient = connectCommandClient()
                try {
                    // Check the instrument before trusting it - see
                    // ProbeConfig.sentinelTag. Costs one refused connection.
                    val sentinel = probeOnce(sentinelClient, probe.sentinelTag, SENTINEL_TIMEOUT_MS)
                    if (sentinel is ProbeOutcome.Reached) {
                        error(
                            "Test aborted: a request through a deliberately dead outbound succeeded, " +
                                "so probes are not going through the servers at all"
                        )
                    }
                } finally {
                    runCatching { sentinelClient.disconnect() }
                }
                probeInParallel(testable, vettedTags, timeoutMs, stopOnFirstReachable, onResult)
            } finally {
                // NonCancellable: a scan can run for minutes, so the caller's
                // scope may well be cancelled part-way through (screen closed,
                // ViewModel cleared). Stopping the engine is itself a suspending
                // call, so without this it would be skipped the moment the scope
                // dies - leaving the probe instance running and the next real
                // connect failing with "sing-box is already running".
                withContext(NonCancellable) {
                    runCatching { engine.stop(emitDisconnected = false) }
                        .onFailure { android.util.Log.w(TAG, "checkAll: engine.stop failed", it) }
                }
            }
        }
    }

    /**
     * Takes every node whose outbound the core refuses out of [probe], reporting each with
     * the core's own error, so the rest of the batch still gets tested.
     *
     * Libbox.checkConfig() judges the probe config as a whole: one outbound sing-box
     * rejects (an xhttp mode it doesn't know, an encryption string it can't parse, ...)
     * fails all of it, and engine.start() then reported that one node's error against
     * every node in the batch - the whole list "unreachable" with the same text. The core
     * names only the first outbound it trips over, so this checks again after each
     * removal; an error that names no node, by tag or by position, is narrowed down by
     * halving the list. At most [MAX_VET_CHECKS] checks: past that, whatever is left goes
     * to engine.start() as before.
     */
    private suspend fun vetProbeConfig(
        nodes: List<Node>,
        probe: ProbeConfig,
        onResult: suspend (Node, NodeHealth) -> Unit
    ): ProbeConfig {
        val budget = CheckBudget(MAX_VET_CHECKS)
        var current = probe
        var remaining = nodes.filter { probe.tagsByNodeId.containsKey(it.id) }
        while (true) {
            val json = current.json ?: return current
            val error = configError(json, budget) ?: return current
            val named = nodeNamedIn(error, json, current.tagsByNodeId, remaining)
            val rejected = if (named != null) listOf(named to error) else findRejected(remaining, budget)
            // Not any one node's fault (or out of checks): leave it to engine.start().
            if (rejected.isEmpty()) return current
            for ((node, reason) in rejected) {
                android.util.Log.w(TAG, "checkAll: core rejected ${current.tagsByNodeId[node.id]}: $reason")
                onResult(node, NodeHealth.Unreachable(reason))
            }
            val rejectedIds = rejected.mapTo(HashSet()) { it.first.id }
            remaining = remaining.filter { it.id !in rejectedIds }
            current = SingBoxConfigBuilder.buildProbeConfig(remaining)
        }
    }

    /** Halves [nodes] down to the ones the core refuses on their own. */
    private suspend fun findRejected(nodes: List<Node>, budget: CheckBudget): List<Pair<Node, String>> {
        if (nodes.isEmpty()) return emptyList()
        val probe = SingBoxConfigBuilder.buildProbeConfig(nodes)
        val json = probe.json ?: return emptyList()
        val error = configError(json, budget) ?: return emptyList()
        if (nodes.size == 1) return listOf(nodes.single() to error)
        nodeNamedIn(error, json, probe.tagsByNodeId, nodes)?.let { return listOf(it to error) }
        val middle = nodes.size / 2
        return findRejected(nodes.subList(0, middle), budget) + findRejected(nodes.subList(middle, nodes.size), budget)
    }

    /**
     * The node [error] is about, when it says: by tag (`vless[probe-12]`) or by position
     * (`outbound[3]`, `outbounds[3]`, `endpoint[0]`) in [json]'s arrays.
     */
    private fun nodeNamedIn(error: String, json: String, tags: Map<Long, String>, nodes: List<Node>): Node? {
        nodes.firstOrNull { node ->
            val tag = tags[node.id] ?: return@firstOrNull false
            Regex("(?<![\\w-])" + Regex.escape(tag) + "(?![\\w-])").containsMatchIn(error)
        }?.let { return it }
        val position = POSITION_IN_ERROR.find(error) ?: return null
        val array = if (position.groupValues[1].startsWith("endpoint")) "endpoints" else "outbounds"
        val tag = runCatching {
            org.json.JSONObject(json).getJSONArray(array).getJSONObject(position.groupValues[2].toInt()).getString("tag")
        }.getOrNull() ?: return null
        return nodes.firstOrNull { tags[it.id] == tag }
    }

    /** checkConfig's complaint about [json]; null if it passes, or once [budget] is spent. */
    private suspend fun configError(json: String, budget: CheckBudget): String? {
        if (budget.left <= 0) return null
        budget.left--
        return withContext(Dispatchers.IO) {
            runCatching { Libbox.checkConfig(json) }.exceptionOrNull()?.let { it.message ?: it.toString() }
        }
    }

    private class CheckBudget(var left: Int)

    /**
     * Runs the probes a few at a time instead of strictly one after another.
     *
     * Sequential probing made a real list unusable: 26 servers, each costing up to
     * 6s and a further 15s if it needs the slow retry, is minutes of staring at a
     * blocked UI - the on-device run this was measured against got cancelled after
     * 22 seconds.
     *
     * Each worker gets *its own* CommandClient rather than sharing one across
     * threads: libbox's is a native handle whose thread-safety isn't documented,
     * and a client is only a connection to the same command server, so this stays
     * on the documented path instead of gambling on concurrent calls into one.
     */
    private suspend fun probeInParallel(
        testable: List<Node>,
        tags: Map<Long, String>,
        timeoutMs: Int,
        stopOnFirstReachable: Boolean,
        onResult: suspend (Node, NodeHealth) -> Unit
    ) {
        val queue = testable.filter { tags.containsKey(it.id) }
        if (queue.isEmpty()) return
        val nextIndex = java.util.concurrent.atomic.AtomicInteger(0)
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val resultLock = Mutex()

        coroutineScope {
            repeat(minOf(PROBE_CONCURRENCY, queue.size)) {
                launch(Dispatchers.IO) {
                    val client = runCatching { connectCommandClient() }.getOrElse {
                        android.util.Log.w(TAG, "probeInParallel: worker client failed", it)
                        return@launch
                    }
                    try {
                        while (!done.get()) {
                            val index = nextIndex.getAndIncrement()
                            if (index >= queue.size) break
                            val node = queue[index]
                            val health = runUrlTestWithRetry(client, tags.getValue(node.id), timeoutMs)
                            // Serialised so callers see one result at a time even
                            // though several probes are in flight.
                            resultLock.withLock { onResult(node, health) }
                            if (stopOnFirstReachable && health is NodeHealth.Reachable) {
                                done.set(true)
                                break
                            }
                        }
                    } finally {
                        runCatching { client.disconnect() }
                    }
                }
            }
        }
    }

    /**
     * QUICK mode, probed concurrently. Each probe is a bare socket check - no
     * sing-box engine, nothing shared to serialise on - yet this used to run
     * strictly one server after another, so a "quick" test took the SUM of
     * every probe. Under DPI that sum is dominated by servers whose SYN is
     * silently dropped: each burned the full [QUICK_TIMEOUT_MS] before the next
     * probe even started, so a ~20-server subscription with a handful of
     * blocked servers took tens of seconds. Run concurrently, the whole scan
     * takes about as long as its single slowest probe.
     *
     * [onResult] stays serialised ([resultLock]) - the same guarantee
     * [probeInParallel] gives FULL-mode callers, which connectFastest() relies
     * on to track its running best without a lock of its own.
     */
    private suspend fun quickInParallel(
        testable: List<Node>,
        stopOnFirstReachable: Boolean,
        onResult: suspend (Node, NodeHealth) -> Unit
    ) {
        val gate = Semaphore(QUICK_CONCURRENCY)
        val resultLock = Mutex()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        // Sum of every probe's own duration = what the old one-at-a-time loop
        // would have taken; logged next to the real wall time for comparison.
        val probeTotalMs = java.util.concurrent.atomic.AtomicLong(0)
        val started = System.nanoTime()
        coroutineScope {
            for (node in testable) {
                launch {
                    gate.withPermit {
                        if (stopOnFirstReachable && done.get()) return@withPermit
                        val t0 = System.nanoTime()
                        val health = quickCheckBounded(node)
                        probeTotalMs.addAndGet((System.nanoTime() - t0) / 1_000_000)
                        resultLock.withLock { onResult(node, health) }
                        if (stopOnFirstReachable && health is NodeHealth.Reachable) done.set(true)
                    }
                }
            }
        }
        android.util.Log.i(
            TAG,
            "quick: ${testable.size} nodes in ${(System.nanoTime() - started) / 1_000_000} ms " +
                "(one-at-a-time would have been ~${probeTotalMs.get()} ms)"
        )
    }

    /**
     * [quickCheck] with a hard ceiling on its total time. The socket timeouts
     * inside it only bound connect/receive; resolving a hostname happens
     * before that (in InetSocketAddress's constructor) and is a blocking call
     * no coroutine timeout can interrupt. The probe runs in [detachedIoScope],
     * outside this call's structured scope, so giving up on a stalled lookup
     * here doesn't have to wait for it to return - its result is discarded.
     * One node's unexpected failure is reported as that node's result rather
     * than aborting the whole concurrent scan.
     */
    private suspend fun quickCheckBounded(node: Node): NodeHealth {
        val probe = detachedIoScope.async { quickCheck(node) }
        return try {
            withTimeoutOrNull((QUICK_TIMEOUT_MS + QUICK_WATCHDOG_GRACE_MS).toLong()) { probe.await() }
                ?: NodeHealth.Unreachable("Timed out")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            NodeHealth.Unreachable(e.message ?: "Probe failed")
        } finally {
            if (!probe.isCompleted) probe.cancel()
        }
    }

    /**
     * Socket-level reachability, no core involved. TCP protocols get a connect;
     * UDP ones (Hysteria2, AmneziaWG) get a connected-socket probe where only an
     * ICMP port-unreachable is conclusive - those protocols drop unsolicited
     * packets by design, so silence proves nothing and is reported as reachable.
     */
    private suspend fun quickCheck(node: Node): NodeHealth = withContext(Dispatchers.IO) {
        val host = node.server.trim()
        val port = node.port
        if (host.isEmpty() || port !in 1..65535) {
            return@withContext NodeHealth.Unreachable("Invalid server address")
        }
        val start = System.nanoTime()
        fun elapsed() = (System.nanoTime() - start) / 1_000_000
        try {
            when (node.protocol) {
                Protocol.VLESS, Protocol.TROJAN -> {
                    java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), QUICK_TIMEOUT_MS) }
                    NodeHealth.Reachable(elapsed())
                }
                else -> {
                    java.net.DatagramSocket().use { socket ->
                        socket.connect(java.net.InetSocketAddress(host, port))
                        socket.soTimeout = QUICK_TIMEOUT_MS
                        val probe = ByteArray(1)
                        socket.send(java.net.DatagramPacket(probe, probe.size))
                        runCatching { socket.receive(java.net.DatagramPacket(ByteArray(64), 64)) }
                    }
                    NodeHealth.Reachable(elapsed())
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            NodeHealth.Unreachable("Timed out")
        } catch (e: java.net.PortUnreachableException) {
            NodeHealth.Unreachable("Port unreachable")
        } catch (e: java.net.ConnectException) {
            NodeHealth.Unreachable("Connection refused")
        } catch (e: java.net.UnknownHostException) {
            NodeHealth.Unreachable("Unknown host")
        } catch (e: java.io.IOException) {
            NodeHealth.Unreachable(e.message ?: "Unreachable")
        }
    }

    suspend fun check(node: Node, timeoutMs: Int = DEFAULT_TIMEOUT_MS): NodeHealth {
        var result: NodeHealth = NodeHealth.Unreachable("Not tested")
        checkAll(listOf(node), timeoutMs) { _, health -> result = health }
        return result
    }

    /**
     * Like [check], but holds ONE probe tunnel open across [holdMs] and tests
     * traffic twice - immediately after the handshake and again after the hold -
     * returning [NodeHealth.Reachable] only if BOTH pass. This is what
     * [WarpObfuscationTuner] needs: WARP's characteristic DPI failure is a
     * handshake that completes and a tunnel that then goes silent ~15s later
     * (see the notes on WarpAccount.DEFAULT_OBFUSCATION). A plain [check] sees
     * only the first, immediate success and would wrongly call such a profile
     * good - and one of the tuner's own candidates (jc=4/40/70) is exactly a
     * shape those notes record as passing the handshake and dying at ~15s.
     *
     * A candidate that fails the first, immediate probe returns at once (no
     * hold), so dead shapes are still rejected quickly. The reported latency is
     * the first probe's, matching [check]. Runs under the same [probeMutex] and
     * single-engine constraints as [checkAll]; the caller must be disconnected.
     */
    suspend fun checkSustained(
        node: Node,
        firstTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
        holdMs: Long = SUSTAIN_HOLD_MS,
        secondTimeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): NodeHealth = probeMutex.withLock {
        if (node.protocol == Protocol.WARP) {
            return@withLock NodeHealth.Unreachable("WARP is not probed directly")
        }
        val probe = SingBoxConfigBuilder.buildProbeConfig(listOf(node))
        probe.buildErrors[node.id]?.let { return@withLock NodeHealth.Unreachable(it) }
        val tag = probe.tagsByNodeId[node.id]
        if (tag == null || probe.json == null) {
            return@withLock NodeHealth.Unreachable("Probe config could not be built")
        }
        if (engine.isRunning()) {
            return@withLock NodeHealth.Unreachable("Disconnect the VPN before testing servers")
        }

        val startResult = runCatching {
            OmniVpnApplication.libboxReady.await()
            engine.start(probe.json, ProbePlatformInterface(context), ProbeCommandServerHandler(), emitEvents = false)
        }
        if (startResult.isFailure) {
            val t = startResult.exceptionOrNull()
            android.util.Log.e(TAG, "checkSustained: probe engine failed to start", t)
            return@withLock NodeHealth.Unreachable(t?.message ?: "Probe failed to start")
        }

        try {
            // Same sanity check as checkAll: a request through a deliberately
            // dead outbound must NOT succeed, or probes aren't going through the
            // node at all. Costs one refused connection.
            if (probe.sentinelTag.isNotBlank()) {
                val sentinelClient = connectCommandClient()
                val sentinel = runCatching { probeOnce(sentinelClient, probe.sentinelTag, SENTINEL_TIMEOUT_MS) }
                runCatching { sentinelClient.disconnect() }
                if (sentinel.getOrNull() is ProbeOutcome.Reached) {
                    return@withLock NodeHealth.Unreachable("Probe sanity check failed")
                }
            }

            val client = connectCommandClient()
            try {
                val first = probeOnce(client, tag, firstTimeoutMs)
                if (first !is ProbeOutcome.Reached) return@withLock first.toHealth()
                // Hold the one tunnel open, then re-test through it: a shape that
                // dies ~15s in fails here even though the handshake just passed.
                kotlinx.coroutines.delay(holdMs)
                val second = probeOnce(client, tag, secondTimeoutMs)
                return@withLock if (second is ProbeOutcome.Reached) {
                    NodeHealth.Reachable(first.latencyMs)
                } else {
                    NodeHealth.Unreachable("Passed handshake, died after ${holdMs / 1000}s")
                }
            } finally {
                runCatching { client.disconnect() }
            }
        } finally {
            // NonCancellable for the same reason as checkAll: stopping the
            // engine is itself suspending, so a cancelled scope would otherwise
            // skip it and leave the probe instance running.
            withContext(NonCancellable) {
                runCatching { engine.stop(emitDisconnected = false) }
                    .onFailure { android.util.Log.w(TAG, "checkSustained: engine.stop failed", it) }
            }
        }
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
    /**
     * One probe, then a second, more patient one if the first merely ran out of
     * time.
     *
     * Measured on a real subscription: working servers answered in 261, 262, 278,
     * 292, 295, 324, 406, 759, 761, 2204, 3243, 3905 and 4149 ms - a continuum
     * that already reached within 850ms of the old flat 5s deadline - while 11 of
     * 13 "dead" verdicts were `context deadline exceeded` rather than a real
     * protocol failure. In other words the deadline, not the servers, decided a
     * chunk of those results, and a server that was reported dead connected fine
     * by hand moments later. A cold probe pays for the handshake to the proxy
     * *and* the proxy's own connection out to the test URL, so seconds are normal.
     *
     * Only timeouts are retried: a refused connection or EOF is an answer, and
     * repeating it just makes scanning a list of dead servers slower.
     */
    private suspend fun runUrlTestWithRetry(client: CommandClient, tag: String, timeoutMs: Int): NodeHealth {
        val first = probeOnce(client, tag, timeoutMs)
        if (first !is ProbeOutcome.TimedOut) return first.toHealth()
        android.util.Log.i(TAG, "runUrlTest: tag=$tag timed out at ${timeoutMs}ms, retrying at ${RETRY_TIMEOUT_MS}ms")
        return probeOnce(client, tag, RETRY_TIMEOUT_MS).toHealth()
    }

    private sealed interface ProbeOutcome {
        data class Reached(val latencyMs: Long) : ProbeOutcome
        data class TimedOut(val reason: String) : ProbeOutcome
        data class Failed(val reason: String) : ProbeOutcome

        fun toHealth(): NodeHealth = when (this) {
            is Reached -> NodeHealth.Reachable(latencyMs)
            is TimedOut -> NodeHealth.Unreachable(reason)
            is Failed -> NodeHealth.Unreachable(reason)
        }
    }

    private suspend fun probeOnce(client: CommandClient, tag: String, timeoutMs: Int): ProbeOutcome {
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
            return ProbeOutcome.TimedOut("Timed out")
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
                    ProbeOutcome.Reached(elapsed.toLong())
                } else {
                    ProbeOutcome.Failed("Unexpected response (HTTP $status)")
                }
            },
            onFailure = { error ->
                android.util.Log.w(TAG, "runUrlTest: tag=$tag threw", error)
                val reason = error.message ?: "Test failed"
                // libbox surfaces the Go error verbatim; a deadline is the one
                // worth a second, slower look (see runUrlTestWithRetry).
                if (reason.contains("deadline exceeded", ignoreCase = true) ||
                    reason.contains("timeout", ignoreCase = true)
                ) {
                    ProbeOutcome.TimedOut("Timed out")
                } else {
                    ProbeOutcome.Failed(reason)
                }
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
        // First pass stays short so a genuinely dead server is written off
        // quickly; the retry is what gives a slow-but-alive one room (measured
        // successes already reached 4149ms - see runUrlTestWithRetry).
        const val DEFAULT_TIMEOUT_MS = 6000
        const val RETRY_TIMEOUT_MS = 12000
        // How long checkSustained holds one probe tunnel open before re-testing.
        // Sits past WARP's observed ~15s "handshake ok, then goes silent" death
        // (see WarpAccount.DEFAULT_OBFUSCATION notes) so that failure is caught.
        const val SUSTAIN_HOLD_MS = 18_000L
        const val PROBE_CONCURRENCY = 4
        // Upper bound on Libbox.checkConfig() calls vetProbeConfig() makes per test run.
        // A rejected node costs one when the error names it, a few more when it has to be
        // found by halving; a list with more bad nodes than this is not a realistic case.
        const val MAX_VET_CHECKS = 100
        // "outbound[3]" / "outbounds[3]" / "endpoint[0]" - where the core says it failed.
        val POSITION_IN_ERROR = Regex("""\b(outbounds?|endpoints?)\[(\d+)]""")
        const val QUICK_TIMEOUT_MS = 3000
        // Quick probes are bare sockets, so many at once is cheap; the cap only
        // stops a huge subscription from opening hundreds of sockets at once.
        const val QUICK_CONCURRENCY = 32
        // Headroom past QUICK_TIMEOUT_MS before quickCheckBounded gives up on a
        // probe - covers hostname resolution, which the socket timeouts don't.
        const val QUICK_WATCHDOG_GRACE_MS = 1500
        const val SENTINEL_TIMEOUT_MS = 4000
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
