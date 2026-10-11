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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    init {
        scope.launch {
            eventBus.state.collect { state ->
                if (state == VpnState.DISCONNECTED || state == VpnState.REVOKED) {
                    _activeConnection.value = ActiveConnection.None
                }
                if (state != VpnState.DISCONNECTED && state != VpnState.ERROR) _tileNotice.value = null
            }
        }
    }

    suspend fun connectNode(node: Node): Result<Unit> =
        vpnController.start(node).onSuccess {
            _activeConnection.value = ActiveConnection.Server(node)
        }

    suspend fun connectWarp(endpoint: String? = null, forceNew: Boolean = false): Result<Unit> =
        vpnController.startWarp(endpoint = endpoint, forceNew = forceNew).onSuccess {
            _activeConnection.value = ActiveConnection.Warp
        }

    fun stop() {
        cancelTileAction()
        vpnController.stop()
        _activeConnection.value = ActiveConnection.None
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
    }
}
