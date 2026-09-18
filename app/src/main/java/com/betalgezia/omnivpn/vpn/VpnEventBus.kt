package com.betalgezia.omnivpn.vpn

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnEventBus @Inject constructor() {

    private val _events = MutableSharedFlow<VpnEvent>(
        replay = 0,
        extraBufferCapacity = 32
    )

    private val _state = MutableStateFlow(VpnState.DISCONNECTED)

    val events: SharedFlow<VpnEvent> = _events.asSharedFlow()
    val state: StateFlow<VpnState> = _state.asStateFlow()

    fun emit(event: VpnEvent) {
        when (event) {
            VpnEvent.Connecting -> _state.value = VpnState.CONNECTING
            VpnEvent.Connected -> _state.value = VpnState.CONNECTED
            is VpnEvent.Error -> _state.value = VpnState.ERROR
            VpnEvent.Disconnected -> _state.value = VpnState.DISCONNECTED
            VpnEvent.Revoked -> _state.value = VpnState.REVOKED
        }
        _events.tryEmit(event)
    }
}
