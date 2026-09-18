package com.betalgezia.omnivpn.vpn

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<VpnEvent>(
        replay = 0,
        extraBufferCapacity = 32
    )

    val events: SharedFlow<VpnEvent> = _events.asSharedFlow()

    fun emit(event: VpnEvent) {
        _events.tryEmit(event)
    }
}
