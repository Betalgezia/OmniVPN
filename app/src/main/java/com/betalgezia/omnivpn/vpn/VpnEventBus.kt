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

    // Result of the post-connect probe (see TrafficProbe). Only meaningful
    // while state is CONNECTED; every other transition resets it.
    private val _traffic = MutableStateFlow(TrafficStatus.UNKNOWN)

    val events: SharedFlow<VpnEvent> = _events.asSharedFlow()
    val state: StateFlow<VpnState> = _state.asStateFlow()
    val traffic: StateFlow<TrafficStatus> = _traffic.asStateFlow()

    fun emit(event: VpnEvent) {
        when (event) {
            VpnEvent.Connecting -> _state.value = VpnState.CONNECTING
            VpnEvent.Connected -> _state.value = VpnState.CONNECTED
            is VpnEvent.Error -> _state.value = VpnState.ERROR
            VpnEvent.Disconnected -> _state.value = VpnState.DISCONNECTED
            VpnEvent.Revoked -> _state.value = VpnState.REVOKED
        }
        if (event != VpnEvent.Connected) _traffic.value = TrafficStatus.UNKNOWN
        _events.tryEmit(event)
    }

    fun setTraffic(status: TrafficStatus) {
        _traffic.value = status
    }

    // The watchdog's "this tunnel looks slow" hint (best observed Mbit/s);
    // ConnectionManager decides whether a faster server exists.
    private val _slow = MutableSharedFlow<Double>(extraBufferCapacity = 4)
    val slow: SharedFlow<Double> = _slow.asSharedFlow()

    fun reportSlow(bestSeenMbps: Double) {
        _slow.tryEmit(bestSeenMbps)
    }
}
