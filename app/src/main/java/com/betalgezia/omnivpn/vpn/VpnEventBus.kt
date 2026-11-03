package com.betalgezia.omnivpn.vpn

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the home screen shows next to the power button while connected.
 * Rates come from sing-box's own traffic counters (see [RateMeter]), latency
 * from the last successful check through the tunnel (CoreProbe). null = no
 * data yet, shown as «—».
 */
data class TunnelStats(
    val downBps: Long? = null,
    val upBps: Long? = null,
    val latencyMs: Long? = null
)

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

    // Live numbers for the home screen. Same lifetime as [traffic]: only the
    // current tunnel's, reset on every transition except Connected.
    private val _stats = MutableStateFlow(TunnelStats())
    val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    // Why the last start failed, for the hero's "Ошибка подключения". The
    // service follows an Error with Disconnected (it stops itself), so the
    // state alone forgets it at once; this keeps it until the next attempt.
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun emit(event: VpnEvent) {
        when (event) {
            VpnEvent.Connecting -> _state.value = VpnState.CONNECTING
            VpnEvent.Connected -> _state.value = VpnState.CONNECTED
            is VpnEvent.Error -> _state.value = VpnState.ERROR
            VpnEvent.Disconnected -> _state.value = VpnState.DISCONNECTED
            VpnEvent.Revoked -> _state.value = VpnState.REVOKED
        }
        when (event) {
            is VpnEvent.Error -> _lastError.value = event.message
            VpnEvent.Connecting, VpnEvent.Connected -> _lastError.value = null
            else -> Unit
        }
        if (event != VpnEvent.Connected) {
            _traffic.value = TrafficStatus.UNKNOWN
            _stats.value = TunnelStats()
        }
        _events.tryEmit(event)
    }

    fun setTraffic(status: TrafficStatus) {
        _traffic.value = status
    }

    /** Current download/upload rate in bytes/s; null = not measured (no traffic check passed yet). */
    fun setRates(downBps: Long?, upBps: Long?) {
        _stats.update { it.copy(downBps = downBps, upBps = upBps) }
    }

    /** Round trip of the latest successful check through the tunnel. */
    fun setLatency(latencyMs: Long) {
        _stats.update { it.copy(latencyMs = latencyMs) }
    }

    /** A new attempt (or a deliberate stop) makes the old failure reason moot. */
    fun clearError() {
        _lastError.value = null
    }

    // The watchdog's "this tunnel looks slow" hint (best observed Mbit/s);
    // ConnectionManager decides whether a faster server exists.
    private val _slow = MutableSharedFlow<Double>(extraBufferCapacity = 4)
    val slow: SharedFlow<Double> = _slow.asSharedFlow()

    fun reportSlow(bestSeenMbps: Double) {
        _slow.tryEmit(bestSeenMbps)
    }
}
