package com.betalgezia.omnivpn

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betalgezia.omnivpn.data.NodeImportService
import com.betalgezia.omnivpn.data.NodeRepository
import com.betalgezia.omnivpn.data.SubscriptionRepository
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
            vpnController.start(node).onFailure { _message.value = it.message ?: "Unable to start VPN" }
            _busy.value = false
        }
    }

    fun stop() { vpnController.stop() }

    fun import(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                if (value.startsWith("https://", ignoreCase = true)) {
                    val id = subscriptionRepository.add("Subscription", value)
                    subscriptionRepository.refresh(Subscription(id=id, name="Subscription", url=value, enabled=true))
                } else {
                    nodeImportService.importText(value)
                }
            }.onSuccess {
                _message.value = if (value.startsWith("https://", ignoreCase = true)) "Subscription added and synced" else "Configuration imported"
            }.onFailure { _message.value = it.message ?: "Import failed" }
            _busy.value = false
        }
    }

    fun refresh(subscription: Subscription) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { subscriptionRepository.refresh(subscription) }
                .onSuccess { _message.value = "Updated ${it.size} nodes" }
                .onFailure { _message.value = it.message ?: "Refresh failed" }
            _busy.value = false
        }
    }

    fun delete(subscription: Subscription) {
        viewModelScope.launch { runCatching { subscriptionRepository.delete(subscription) }.onFailure { _message.value = it.message } }
    }

    fun startWarp() {
        viewModelScope.launch {
            _busy.value = true
            vpnController.startWarp().onFailure { _message.value = it.message ?: "WARP registration failed" }
                .onSuccess { _message.value = "WARP registered" }
            _busy.value = false
        }
    }
}