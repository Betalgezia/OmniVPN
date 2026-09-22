package com.betalgezia.omnivpn

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betalgezia.omnivpn.data.NodeImportService
import com.betalgezia.omnivpn.data.NodeRepository
import com.betalgezia.omnivpn.data.SubscriptionRepository
import com.betalgezia.omnivpn.data.hasDomainWireguardEndpoint
import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Protocol
import com.betalgezia.omnivpn.data.model.Subscription
import com.betalgezia.omnivpn.vpn.NodeHealth
import com.betalgezia.omnivpn.vpn.NodeHealthChecker
import com.betalgezia.omnivpn.vpn.TestMode
import com.betalgezia.omnivpn.vpn.VpnController
import com.betalgezia.omnivpn.vpn.VpnState
import com.betalgezia.omnivpn.vpn.WarpAccount
import com.betalgezia.omnivpn.vpn.WarpEndpointScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the active VPN connection is, if any - tracked separately from
 * [VpnState] because that only reports whether a tunnel is up, not which
 * server it goes to. Every path that can bring a tunnel up (connect(),
 * connectFastest(), startWarp()) updates this, so the hero connect button
 * always has a name to show once VpnState reports CONNECTING/CONNECTED.
 */
sealed interface ActiveConnection {
    data object None : ActiveConnection
    data class Server(val node: Node) : ActiveConnection
    data object Warp : ActiveConnection
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val nodeRepository: NodeRepository,
    private val nodeImportService: NodeImportService,
    private val subscriptionRepository: SubscriptionRepository,
    private val vpnController: VpnController,
    private val nodeHealthChecker: NodeHealthChecker,
    private val warpEndpointScanner: WarpEndpointScanner
) : ViewModel() {

    init {
        viewModelScope.launch {
            runCatching { nodeRepository.removeLegacyDemoNode() }
        }
    }

    val nodes = nodeRepository.nodes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val subscriptions = subscriptionRepository.subscriptions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val vpnState: StateFlow<VpnState> = vpnController.state

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _nodeHealth = MutableStateFlow<Map<Long, NodeHealth>>(emptyMap())
    val nodeHealth: StateFlow<Map<Long, NodeHealth>> = _nodeHealth.asStateFlow()

    private val _activeConnection = MutableStateFlow<ActiveConnection>(ActiveConnection.None)
    val activeConnection: StateFlow<ActiveConnection> = _activeConnection.asStateFlow()

    // True only while connectFastest() is probing - distinct from _busy (which
    // is also true during a manual "Check all"/WARP endpoint search) so the
    // hero button can show "Searching…" only for its own action.
    private val _searchingFastest = MutableStateFlow(false)
    val searchingFastest: StateFlow<Boolean> = _searchingFastest.asStateFlow()

    // Same idea as _searchingFastest, for the small "full test" icon next to
    // the main screen's "Servers" label (see the September 2026 redesign) -
    // distinct from _busy so that icon's own spinner only shows for its own
    // action, not e.g. while a subscription is being added.
    private val _testingAllFull = MutableStateFlow(false)
    val testingAllFull: StateFlow<Boolean> = _testingAllFull.asStateFlow()

    // Server tests and the WARP endpoint search share this: both drive the one
    // sing-box engine, so they can never overlap anyway, and both can run long
    // enough that the user needs a way out.
    private var testJob: Job? = null

    private val _testMode = MutableStateFlow(TestMode.FULL)
    val testMode: StateFlow<TestMode> = _testMode.asStateFlow()

    fun setTestMode(mode: TestMode) { _testMode.value = mode }

    fun consumeMessage() { _message.value = null }
    fun showMessage(message: String) { _message.value = message }
    fun prepareVpn(): Intent? = vpnController.prepareIntent()

    fun connect(node: Node) {
        viewModelScope.launch {
            _busy.value = true
            vpnController.start(node).onFailure {
                android.util.Log.e(TAG, "connect: failed", it)
                _message.value = it.message ?: "Unable to start VPN"
            }.onSuccess {
                _activeConnection.value = ActiveConnection.Server(node)
            }
            _busy.value = false
        }
    }

    fun stop() {
        vpnController.stop()
        _activeConnection.value = ActiveConnection.None
    }

    fun import(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        val isSubscriptionUrl = value.startsWith("https://", ignoreCase = true)
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                if (isSubscriptionUrl) {
                    val id = subscriptionRepository.add("Subscription", value)
                    val nodes = subscriptionRepository.refresh(Subscription(id=id, name="Subscription", url=value, enabled=true))
                    hasDomainWireguardEndpoint(nodes)
                } else {
                    nodeImportService.importText(value).domainWireguardEndpoint
                }
            }.onSuccess { domainWarning ->
                val base = if (isSubscriptionUrl) "Subscription added and synced" else "Configuration imported"
                _message.value = if (domainWarning) "$base\n$DOMAIN_ENDPOINT_WARNING" else base
            }.onFailure {
                // Logged explicitly: SubscriptionRepository/NodeImportService never call
                // android.util.Log themselves, so without this line an import/subscription
                // failure was visible only as a short-lived toast and left nothing in logcat.
                android.util.Log.e(TAG, "import: failed for ${if (isSubscriptionUrl) "subscription url" else "pasted config"}", it)
                _message.value = it.message ?: "Import failed"
            }
            _busy.value = false
        }
    }

    fun refresh(subscription: Subscription) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { subscriptionRepository.refresh(subscription) }
                .onSuccess { nodes ->
                    val base = "Updated ${nodes.size} nodes"
                    _message.value = if (hasDomainWireguardEndpoint(nodes)) "$base\n$DOMAIN_ENDPOINT_WARNING" else base
                }
                .onFailure {
                    android.util.Log.e(TAG, "refresh: failed for subscription id=${subscription.id}", it)
                    _message.value = it.message ?: "Refresh failed"
                }
            _busy.value = false
        }
    }

    fun delete(subscription: Subscription) {
        viewModelScope.launch {
            runCatching { subscriptionRepository.delete(subscription) }.onFailure {
                android.util.Log.e(TAG, "delete: failed for subscription id=${subscription.id}", it)
                _message.value = it.message
            }
        }
    }

    fun deleteNode(node: Node) {
        viewModelScope.launch {
            runCatching { nodeRepository.delete(node) }
                .onSuccess {
                    _nodeHealth.update { it - node.id }
                    _message.value = "Removed ${node.name}"
                }
                .onFailure {
                    android.util.Log.e(TAG, "deleteNode: failed for node id=${node.id}", it)
                    _message.value = it.message ?: "Unable to remove server"
                }
        }
    }

    fun checkNode(node: Node) = checkNodes(listOf(node))

    fun checkNodes(targets: List<Node>) {
        // Testing runs a real (if TUN-less) sing-box session on the same engine the
        // actual VPN connection uses - see NodeHealthChecker. They can't run at once.
        if (!canTestNow()) {
            _message.value = "Disconnect the VPN before testing servers"
            return
        }
        val testable = targets.filter { it.id != 0L && it.protocol != Protocol.WARP }
        if (testable.isEmpty()) return
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _busy.value = true
            _nodeHealth.update { current -> current + testable.associate { it.id to NodeHealth.Checking } }
            try {
                nodeHealthChecker.checkAll(testable, mode = _testMode.value) { node, health ->
                    _nodeHealth.update { it + (node.id to health) }
                }
            } catch (cancelled: CancellationException) {
                _message.value = "Test cancelled"
                throw cancelled
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "checkNodes: failed", t)
                _message.value = t.message ?: "Server test failed"
            } finally {
                // Cancelling leaves nodes mid-probe: drop their "Testing…" label
                // instead of stranding it, and clear busy here rather than after
                // the call, which a cancellation would skip straight past and
                // leave the whole UI disabled.
                _nodeHealth.update { current -> current.filterValues { it != NodeHealth.Checking } }
                _busy.value = false
            }
        }
    }

    /** Stops an in-flight server test or WARP endpoint search. */
    fun cancelTests() {
        testJob?.cancel()
        testJob = null
    }

    fun checkAllNodes() = checkNodes(nodes.value)

    fun checkSubscriptionNodes(subscription: Subscription) =
        checkNodes(nodes.value.filter { it.sourceId == subscription.id })

    /**
     * Tests every server that came from a subscription - never a manually
     * pasted/imported config, never WARP, see the September 2026 redesign -
     * then connects to whichever one answers fastest: the hero "Connect"
     * button's one-tap promise. Always probes in TestMode.QUICK regardless of
     * the _testMode toggle: that toggle is for the deliberate, thorough
     * per-node "Test" tap, while this action's whole point is to be a fast
     * default. Shares testJob with checkNodes()/findWarpEndpoint() since all
     * three drive the same single sing-box probe engine (see
     * NodeHealthChecker) and can never run at once; canTestNow() applies here
     * for the same reason.
     */
    fun connectFastest() {
        if (!canTestNow()) {
            _message.value = "Disconnect the VPN before searching for the fastest server"
            return
        }
        val candidates = nodes.value.filter { it.id != 0L && it.sourceId != null && it.protocol != Protocol.WARP }
        if (candidates.isEmpty()) {
            _message.value = "No subscription servers yet - add a subscription first"
            return
        }
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _busy.value = true
            _searchingFastest.value = true
            _nodeHealth.update { current -> current + candidates.associate { it.id to NodeHealth.Checking } }
            try {
                // checkAll() serialises onResult even when probes run in parallel
                // (see NodeHealthChecker.probeInParallel), so plain vars here -
                // without a Mutex of their own - are safe to mutate from it.
                var best: Node? = null
                var bestLatencyMs = Long.MAX_VALUE
                nodeHealthChecker.checkAll(candidates, mode = TestMode.QUICK) { node, health ->
                    _nodeHealth.update { it + (node.id to health) }
                    if (health is NodeHealth.Reachable && health.latencyMs < bestLatencyMs) {
                        best = node
                        bestLatencyMs = health.latencyMs
                    }
                }
                val winner = best
                if (winner == null) {
                    _message.value = "No reachable servers found"
                } else {
                    vpnController.start(winner).onFailure {
                        android.util.Log.e(TAG, "connectFastest: connect failed", it)
                        _message.value = it.message ?: "Unable to start VPN"
                    }.onSuccess {
                        _activeConnection.value = ActiveConnection.Server(winner)
                        _message.value = "Connected to ${winner.name} (${bestLatencyMs} ms)"
                    }
                }
            } catch (cancelled: CancellationException) {
                _message.value = "Search cancelled"
                throw cancelled
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "connectFastest: failed", t)
                _message.value = t.message ?: "Search failed"
            } finally {
                _nodeHealth.update { current -> current.filterValues { it != NodeHealth.Checking } }
                _searchingFastest.value = false
                _busy.value = false
            }
        }
    }

    /**
     * Tests every subscription server in TestMode.FULL regardless of the
     * _testMode toggle - the small icon next to the main screen's "Servers"
     * label (see the September 2026 redesign). FULL is hardcoded here for
     * the mirror-image reason connectFastest() hardcodes QUICK: that button's
     * whole point is a fast default, this one's whole point is a thorough
     * check without having to first go into Settings and flip the toggle.
     * Shares testJob/canTestNow() with the other test actions for the same
     * single-engine reason documented on connectFastest().
     */
    fun testAllSubscriptionsFull() {
        if (!canTestNow()) {
            _message.value = "Disconnect the VPN before testing servers"
            return
        }
        val candidates = nodes.value.filter { it.id != 0L && it.sourceId != null && it.protocol != Protocol.WARP }
        if (candidates.isEmpty()) {
            _message.value = "No subscription servers yet - add a subscription first"
            return
        }
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _busy.value = true
            _testingAllFull.value = true
            _nodeHealth.update { current -> current + candidates.associate { it.id to NodeHealth.Checking } }
            try {
                nodeHealthChecker.checkAll(candidates, mode = TestMode.FULL) { node, health ->
                    _nodeHealth.update { it + (node.id to health) }
                }
            } catch (cancelled: CancellationException) {
                _message.value = "Test cancelled"
                throw cancelled
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "testAllSubscriptionsFull: failed", t)
                _message.value = t.message ?: "Server test failed"
            } finally {
                _nodeHealth.update { current -> current.filterValues { it != NodeHealth.Checking } }
                _testingAllFull.value = false
                _busy.value = false
            }
        }
    }

    /**
     * Tries WARP's anycast endpoints until one actually carries traffic and saves
     * it - the endpoint that works is network-dependent and changes, and typing
     * candidates into the override field by hand was the only way to find one.
     */
    fun findWarpEndpoint() {
        // Same single-engine constraint as the server test - see NodeHealthChecker.
        if (!canTestNow()) {
            _message.value = "Disconnect the VPN before searching for a WARP endpoint"
            return
        }
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _busy.value = true
            _message.value = "Trying WARP endpoints…"
            try {
                warpEndpointScanner.findWorkingEndpoint()
                    .onSuccess {
                        _message.value = "WARP endpoint ${it.endpoint} works (${it.latencyMs} ms) and is now saved"
                    }
                    .onFailure {
                        android.util.Log.e(TAG, "findWarpEndpoint: failed", it)
                        _message.value = it.message ?: "No working WARP endpoint found"
                    }
            } catch (cancelled: CancellationException) {
                _message.value = "Endpoint search cancelled"
                throw cancelled
            } finally {
                _busy.value = false
            }
        }
    }

    private fun canTestNow(): Boolean = when (vpnState.value) {
        VpnState.DISCONNECTED, VpnState.ERROR, VpnState.REVOKED -> true
        else -> false
    }

    fun startWarp(endpointOverride: String? = null, forceNew: Boolean = false) {
        viewModelScope.launch {
            _busy.value = true
            // The override was easy to lose track of - no visible confirmation
            // of which endpoint actually got used, so it was unclear from the
            // UI alone whether a typed-in override took effect (see the
            // September 2026 endpoint-rotation debugging session). Echo it
            // back in the result message instead of just "WARP registered".
            val normalizedEndpoint = endpointOverride?.trim()?.takeIf { it.isNotEmpty() }
            // forceNew discards the cached account and registers a fresh one
            // with Cloudflare - see VpnController.startWarp. Surfaced from the
            // UI as a "Reset WARP account" action for the case where the
            // saved account/keys stop working server-side; normal "Get WARP"
            // clicks always reuse the cached account instead of gambling on a
            // new one each time.
            vpnController.startWarp(endpoint = normalizedEndpoint, forceNew = forceNew).onFailure {
                android.util.Log.e(TAG, "startWarp: failed (forceNew=$forceNew)", it)
                _message.value = it.message ?: "WARP registration failed"
            }
                .onSuccess {
                    _activeConnection.value = ActiveConnection.Warp
                    val prefix = if (forceNew) "New WARP account registered" else "WARP registered"
                    _message.value = "$prefix (endpoint: ${normalizedEndpoint ?: WarpAccount.DEFAULT_ENDPOINT})"
                }
            _busy.value = false
        }
    }

    private companion object {
        private const val TAG = "MainViewModel"

        // Shown after import/refresh when an AmneziaWG/WireGuard peer's
        // endpoint is a domain name rather than a literal IP - see
        // ConfigParser.isLiteralIpHost and WarpAccount.DEFAULT_ENDPOINT for
        // the confirmed sing-box-lx bug this warns about.
        private const val DOMAIN_ENDPOINT_WARNING =
            "Warning: this server's endpoint is a domain name, not an IP address. " +
                "A known sing-box bug can prevent the WireGuard handshake from " +
                "completing on some networks. If it won't connect, try editing the " +
                "config to use a literal IP instead."
    }
}
