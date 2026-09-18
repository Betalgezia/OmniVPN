package com.betalgezia.omnivpn.vpn

import kotlin.test.Test
import kotlin.test.assertEquals

class VpnEventBusTest {
    @Test
    fun mapsEventsToExpectedState() {
        val bus = VpnEventBus()

        bus.emit(VpnEvent.Connecting)
        assertEquals(VpnState.CONNECTING, bus.state.value)

        bus.emit(VpnEvent.Connected)
        assertEquals(VpnState.CONNECTED, bus.state.value)

        bus.emit(VpnEvent.Error("boom"))
        assertEquals(VpnState.ERROR, bus.state.value)

        bus.emit(VpnEvent.Disconnected)
        assertEquals(VpnState.DISCONNECTED, bus.state.value)

        bus.emit(VpnEvent.Revoked)
        assertEquals(VpnState.REVOKED, bus.state.value)
    }
}
