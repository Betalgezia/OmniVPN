package com.betalgezia.omnivpn

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betalgezia.omnivpn.data.NodeImportService
import com.betalgezia.omnivpn.data.NodeRepository
import com.betalgezia.omnivpn.data.SubscriptionRepository
import com.betalgezia.omnivpn.data.hasDomainWireguardEndpoint
import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Subscription
import com.betalgezia.omnivpn.vpn.VpnController
import com.betalgezia.omnivpn.vpn.VpnState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val nodeRepository: NodeRepository,
    private val nodeImportService: NodeImportService,
    private val subscriptionRepository: SubscriptionRepository,
    private val vpnController: VpnController
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
                .onSuccess { _message.value = "Removed ${node.name}" }
                .onFailure {
                    android.util.Log.e(TAG, "deleteNode: failed for node id=${node.id}", it)
                    _message.value = it.message ?: "Unable to remove server"
                }
        }
    }

    fun startWarp() {
        viewModelScope.launch {
            _busy.value = true
            vpnController.startWarp().onFailure {
                android.util.Log.e(TAG, "startWarp: failed", it)
                _message.value = it.message ?: "WARP registration failed"
            }
                .onSuccess { _message.value = "WARP registered" }
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