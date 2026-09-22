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
import com.betalgezia.omnivpn.vpn.VpnController
import com.betalgezia.omnivpn.vpn.VpnState
import com.betalgezia.omnivpn.vpn.WarpAccount
import com.betalgezia.omnivpn.vpn.WarpEndpointScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    fun consumeMessage() { _message.value = null }
    fun showMessage(message: String) { _message.value = message }
    fun prepareVpn(): Intent? = vpnController.prepareIntent()

    fun connect(node: Node) {
        viewModelScope.launch {
            _busy.value = true
            vpnController.start(node).onFailure {
                android.util.Log.e(TAG, "connect: failed", it)
                _message.value = it.message ?: "Unable to start VPN"
            }
            _busy.value = false
        }
    }

    fun stop() { vpnController.stop() }

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
        viewModelScope.launch {
            _busy.value = true
            _nodeHealth.update { current -> current + testable.associate { it.id to NodeHealth.Checking } }
            runCatching {
                nodeHealthChecker.checkAll(testable) { node, health ->
                    _nodeHealth.update { it + (node.id to health) }
                }
            }.onFailure {
                android.util.Log.e(TAG, "checkNodes: failed", it)
                _message.value = it.message ?: "Server test failed"
            }
            _busy.value = false
        }
    }

    fun checkAllNodes() = checkNodes(nodes.value)

    fun checkSubscriptionNodes(subscription: Subscription) =
        checkNodes(nodes.value.filter { it.sourceId == subscription.id })

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
        viewModelScope.launch {
            _busy.value = true
            _message.value = "Trying WARP endpoints…"
            warpEndpointScanner.findWorkingEndpoint()
                .onSuccess {
                    _message.value = "WARP endpoint ${it.endpoint} works (${it.latencyMs} ms) and is now saved"
                }
                .onFailure {
                    android.util.Log.e(TAG, "findWarpEndpoint: failed", it)
                    _message.value = it.message ?: "No working WARP endpoint found"
                }
            _busy.value = false
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
