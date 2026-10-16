package com.betalgezia.omnivpn.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    fun trafficStatusSurvivesConnectedButResetsOnEveryOtherTransition() {
        val bus = VpnEventBus()
        assertEquals(TrafficStatus.UNKNOWN, bus.traffic.value)

        bus.emit(VpnEvent.Connected)
        bus.setTraffic(TrafficStatus.NONE)
        assertEquals(TrafficStatus.NONE, bus.traffic.value)

        // A repeated Connected (e.g. after a hot reload) keeps the probe result
        // until the service starts a new probe.
        bus.emit(VpnEvent.Connected)
        assertEquals(TrafficStatus.NONE, bus.traffic.value)

        // Switching servers goes through Connecting: the old result must not
        // stick to the new server.
        bus.emit(VpnEvent.Connecting)
        assertEquals(TrafficStatus.UNKNOWN, bus.traffic.value)

        bus.setTraffic(TrafficStatus.OK)
        bus.emit(VpnEvent.Disconnected)
        assertEquals(TrafficStatus.UNKNOWN, bus.traffic.value)

        bus.setTraffic(TrafficStatus.OK)
        bus.emit(VpnEvent.Error("boom"))
        assertEquals(TrafficStatus.UNKNOWN, bus.traffic.value)
    }

    @Test
    fun statsBelongToTheCurrentTunnelOnly() {
        val bus = VpnEventBus()
        bus.emit(VpnEvent.Connected)
        bus.setLatency(42)
        bus.setRates(1_000, 200)
        assertEquals(TunnelStats(downBps = 1_000, upBps = 200, latencyMs = 42), bus.stats.value)

        // Clearing rates (traffic stopped) keeps the latency.
        bus.setRates(null, null)
        assertEquals(TunnelStats(latencyMs = 42), bus.stats.value)

        // Repeated Connected keeps them; a switch (Connecting) drops them.
        bus.emit(VpnEvent.Connected)
        assertEquals(42L, bus.stats.value.latencyMs)
        bus.emit(VpnEvent.Connecting)
        assertEquals(TunnelStats(), bus.stats.value)
    }

    @Test
    fun lastErrorOutlivesTheFollowingDisconnectUntilTheNextAttempt() {
        val bus = VpnEventBus()
        bus.emit(VpnEvent.Error("sing-box start failed"))
        // The service stops itself right after an error.
        bus.emit(VpnEvent.Disconnected)
        assertEquals("sing-box start failed", bus.lastError.value)

        bus.emit(VpnEvent.Connecting)
        assertNull(bus.lastError.value)

        bus.emit(VpnEvent.Error("again"))
        bus.clearError()
        assertNull(bus.lastError.value)
    }
}
