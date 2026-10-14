package com.betalgezia.omnivpn.vpn

import com.betalgezia.omnivpn.data.NodeRepository
import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Protocol
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What the active VPN connection is, if any - tracked separately from
 * [VpnState] because that only reports whether a tunnel is up, not which
 * server it goes to.
 */
sealed interface ActiveConnection {
    data object None : ActiveConnection
    data class Server(val node: Node) : ActiveConnection
    data object Warp : ActiveConnection
}

/**
 * App-wide owner of "what are we connected to" and of every way a tunnel can
 * be brought up. Used to live inside MainViewModel, which meant the Quick
 * Settings tile could start WARP without the app ever finding out (the hero
 * showed a nameless "Подключено"), and the tile's own work ran in a scope the
 * tile cancels as soon as the shade closes.
 *
 * Starting while a tunnel is already up is a switch, not a no-op: the service
 * hot-reloads the new config into the running engine (see
 * OmniVpnService.reloadVpn), so tapping another server just moves to it.
 */
@Singleton
class ConnectionManager @Inject constructor(
    private val vpnController: VpnController,
    private val eventBus: VpnEventBus,
    private val nodeRepository: NodeRepository,
    private val nodeHealthChecker: NodeHealthChecker,
    private val warpSettings: WarpSettings
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _activeConnection = MutableStateFlow<ActiveConnection>(ActiveConnection.None)
    val activeConnection: StateFlow<ActiveConnection> = _activeConnection.asStateFlow()

    // True while the tile is searching for the fastest server.
    private val _tileSearching = MutableStateFlow(false)
    val tileSearching: StateFlow<Boolean> = _tileSearching.asStateFlow()

    // Last tile-started action's failure, shown as the tile subtitle until
    // the next attempt or the next VPN state change.
    private val _tileNotice = MutableStateFlow<String?>(null)
    val tileNotice: StateFlow<String?> = _tileNotice.asStateFlow()

    private var tileJob: Job? = null

    // One-off messages for the user (auto-switch progress, WARP down); the
    // app shows them in its snackbar.
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    // ---- Auto-switch state (all touched on the main thread only) ----
    private var failoverJob: Job? = null
    // nodeId -> when it was last found dead (elapsedRealtime).
    private val deadSince = mutableMapOf<Long, Long>()
    // Switches made in the current round, i.e. since traffic was last OK or
    // the user last picked something by hand.
    private var roundAttempts = 0
    private var roundExhausted = false

    init {
        scope.launch {
            eventBus.state.collect { state ->
                if (state == VpnState.DISCONNECTED || state == VpnState.REVOKED) {
                    _activeConnection.value = ActiveConnection.None
                    resetRound()
                }
                if (state != VpnState.DISCONNECTED && state != VpnState.ERROR) _tileNotice.value = null
            }
        }
        scope.launch {
            eventBus.traffic.collect { traffic ->
                when (traffic) {
                    TrafficStatus.OK -> {
                        roundAttempts = 0
                        roundExhausted = false
                    }
                    TrafficStatus.NONE, TrafficStatus.DNS_BROKEN -> onTunnelDead()
                    else -> Unit
                }
            }
        }
    }

    /** A server picked by the user (list, tile, fastest search). */
    suspend fun connectNode(node: Node): Result<Unit> {
        resetRound()
        return startNode(node)
    }

    private suspend fun startNode(node: Node, quickCheck: Boolean = false): Result<Unit> =
        vpnController.start(node, quickCheck = quickCheck).onSuccess {
            _activeConnection.value = ActiveConnection.Server(node)
        }

    suspend fun connectWarp(endpoint: String? = null, forceNew: Boolean = false): Result<Unit> {
        resetRound()
        return vpnController.startWarp(endpoint = endpoint, forceNew = forceNew).onSuccess {
            _activeConnection.value = ActiveConnection.Warp
        }
    }

    fun stop() {
        cancelTileAction()
        resetRound()
        vpnController.stop()
        _activeConnection.value = ActiveConnection.None
    }

    private fun resetRound() {
        failoverJob?.cancel()
        failoverJob = null
        roundAttempts = 0
        roundExhausted = false
    }

    /**
     * The tunnel stopped passing traffic (connect-time check or the running
     * watchdog). A subscription server is replaced by the next one of the same
     * subscription, at most [MAX_SWITCHES_PER_ROUND] times in a row - if none
     * of those work either, the internet itself is the likelier culprit, so
     * this stops and says so instead of cycling through the whole list. WARP
     * and pasted-in configs are only reported.
     */
    private fun onTunnelDead() {
        if (failoverJob?.isActive == true) return
        val active = _activeConnection.value
        val now = android.os.SystemClock.elapsedRealtime()
        when (active) {
            ActiveConnection.None -> return
            ActiveConnection.Warp -> {
                _notices.tryEmit("WARP не пропускает трафик. Попробуйте «Найти рабочий endpoint» в настройках или другой сервер.")
                return
            }
            is ActiveConnection.Server -> Unit
        }
        val dead = active.node
        deadSince[dead.id] = now
        if (dead.sourceId == null) {
            _notices.tryEmit("«${dead.name}» не пропускает трафик.")
            return
        }
        if (!warpSettings.autoSwitchEnabled || roundExhausted) return
        if (roundAttempts >= MAX_SWITCHES_PER_ROUND) {
            roundExhausted = true
            _notices.tryEmit(
                "Проверено ${roundAttempts + 1} серверов подписки — ни один не пропускает трафик. " +
                    "Похоже, пропал сам интернет."
            )
            return
        }
        failoverJob = scope.launch {
            val all = runCatching { nodeRepository.nodes.first() }.getOrDefault(emptyList())
            val next = pickFailoverCandidate(all, dead, deadSince, now, DEAD_TTL_MS)
            if (next == null) {
                roundExhausted = true
                _notices.tryEmit("«${dead.name}» не пропускает трафик, а других рабочих серверов в подписке не осталось.")
                return@launch
            }
            roundAttempts++
            android.util.Log.i(TAG, "auto-switch #$roundAttempts: ${dead.name} -> ${next.name}")
            _notices.tryEmit("«${dead.name}» не пропускает трафик — переключаюсь на «${next.name}»")
            startNode(next, quickCheck = true).onFailure {
                android.util.Log.e(TAG, "auto-switch to ${next.name} failed", it)
                _notices.tryEmit("Не удалось переключиться на «${next.name}»: ${it.message}")
            }
        }
    }

    /** Subscription servers the fastest-server search may pick from. */
    suspend fun fastestCandidates(): List<Node> =
        nodeRepository.nodes.first().filter { it.id != 0L && it.sourceId != null && it.protocol != Protocol.WARP }

    /**
     * Quick-probes [candidates] (TestMode.QUICK, same as the hero button) and
     * returns the fastest reachable one with its latency, or null. Needs the
     * VPN to be down - the probe shares the one sing-box engine.
     */
    suspend fun findFastest(
        candidates: List<Node>,
        onResult: (Node, NodeHealth) -> Unit = { _, _ -> }
    ): Pair<Node, Long>? {
        var best: Node? = null
        var bestLatencyMs = Long.MAX_VALUE
        // checkAll() serialises onResult, so plain vars are safe here.
        nodeHealthChecker.checkAll(candidates, mode = TestMode.QUICK) { node, health ->
            onResult(node, health)
            if (health is NodeHealth.Reachable && health.latencyMs < bestLatencyMs) {
                best = node
                bestLatencyMs = health.latencyMs
            }
        }
        return best?.let { it to bestLatencyMs }
    }

    /**
     * The Quick Settings tile's "turn on" action: WARP or the fastest
     * subscription server, per [WarpSettings.quickTileStartsWarp]. Runs in
     * this singleton's scope so closing the shade doesn't cancel it. VPN
     * consent must already be granted (the tile checks that first).
     */
    fun startTileAction() {
        if (tileJob?.isActive == true) return
        _tileNotice.value = null
        tileJob = scope.launch {
            if (warpSettings.quickTileStartsWarp) {
                connectWarp().onFailure { _tileNotice.value = shortError(it, "WARP не запустился") }
                return@launch
            }
            _tileSearching.value = true
            try {
                val candidates = fastestCandidates()
                if (candidates.isEmpty()) {
                    _tileNotice.value = "Нет серверов из подписок"
                    return@launch
                }
                val found = findFastest(candidates)
                if (found == null) {
                    _tileNotice.value = "Ни один сервер не ответил"
                    return@launch
                }
                connectNode(found.first).onFailure { _tileNotice.value = shortError(it, "Не удалось подключиться") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "startTileAction: failed", t)
                _tileNotice.value = shortError(t, "Поиск не удался")
            } finally {
                _tileSearching.value = false
            }
        }
    }

    fun cancelTileAction() {
        tileJob?.cancel()
        tileJob = null
        _tileSearching.value = false
    }

    private fun shortError(t: Throwable, fallback: String): String {
        android.util.Log.e(TAG, "tile action failed", t)
        return fallback
    }

    private companion object {
        private const val TAG = "ConnectionManager"
        // Auto-switches in a row before giving up (6 servers tried in total).
        private const val MAX_SWITCHES_PER_ROUND = 5
        // A server found dead isn't retried by auto-switch for this long.
        private const val DEAD_TTL_MS = 10 * 60_000L
    }
}

/**
 * Next server to auto-switch to after [current] stopped passing traffic: the
 * one after it in the same subscription (wrapping around), skipping servers
 * found dead less than [deadTtlMs] ago. Null if nothing is left.
 */
internal fun pickFailoverCandidate(
    all: List<Node>,
    current: Node,
    deadSince: Map<Long, Long>,
    nowMs: Long,
    deadTtlMs: Long
): Node? {
    val group = all.filter { it.sourceId == current.sourceId && it.id != 0L && it.protocol != Protocol.WARP }
    val index = group.indexOfFirst { it.id == current.id }
    val rotated = if (index < 0) group else group.drop(index + 1) + group.take(index)
    return rotated.firstOrNull { node ->
        node.id != current.id && deadSince[node.id]?.let { nowMs - it >= deadTtlMs } ?: true
    }
}
