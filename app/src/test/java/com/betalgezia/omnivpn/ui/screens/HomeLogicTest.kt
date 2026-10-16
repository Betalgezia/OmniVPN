package com.betalgezia.omnivpn.ui.screens

import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Protocol
import com.betalgezia.omnivpn.vpn.ActiveConnection
import com.betalgezia.omnivpn.vpn.TrafficStatus
import com.betalgezia.omnivpn.vpn.VpnState
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeLogicTest {
    private val server = ActiveConnection.Server(
        Node(id = 1, name = "Amsterdam 01", protocol = Protocol.VLESS, server = "1.2.3.4", port = 443, sourceId = 7)
    )

    private fun kind(
        state: VpnState = VpnState.DISCONNECTED,
        searching: Boolean = false,
        warpStarting: Boolean = false,
        active: ActiveConnection = ActiveConnection.None,
        traffic: TrafficStatus = TrafficStatus.UNKNOWN,
        lastError: String? = null
    ) = heroKind(state, searching, warpStarting, active, traffic, lastError)

    @Test
    fun heroFollowsTheTunnel() {
        assertEquals(HeroKind.OFF, kind())
        assertEquals(HeroKind.SEARCH, kind(searching = true))
        assertEquals(HeroKind.CONNECTING, kind(state = VpnState.CONNECTING, active = server))
        assertEquals(HeroKind.ON, kind(state = VpnState.CONNECTED, active = server, traffic = TrafficStatus.OK))
        // Still checking right after connect: connected look, just no pulse yet.
        assertEquals(HeroKind.ON, kind(state = VpnState.CONNECTED, active = server, traffic = TrafficStatus.CHECKING))
        assertEquals(HeroKind.NO_TRAFFIC, kind(state = VpnState.CONNECTED, active = server, traffic = TrafficStatus.NONE))
        assertEquals(HeroKind.NO_DNS, kind(state = VpnState.CONNECTED, active = server, traffic = TrafficStatus.DNS_BROKEN))
        assertEquals(HeroKind.REVOKED, kind(state = VpnState.REVOKED))
    }

    @Test
    fun warpRegistrationAndTheServiceStartGapReadAsConnecting() {
        assertEquals(HeroKind.CONNECTING, kind(warpStarting = true))
        assertEquals(HeroKind.CONNECTING, kind(active = ActiveConnection.Warp))
        // Switching from a server to WARP keeps the connected server shown until the reload.
        assertEquals(HeroKind.ON, kind(state = VpnState.CONNECTED, active = server, traffic = TrafficStatus.OK, warpStarting = true))
    }

    @Test
    fun aFailureStaysVisibleAfterTheServiceStops() {
        assertEquals(HeroKind.ERROR, kind(state = VpnState.ERROR, lastError = "boom"))
        assertEquals(HeroKind.ERROR, kind(lastError = "boom"))
        // A new search outranks the old error.
        assertEquals(HeroKind.SEARCH, kind(searching = true, lastError = "boom"))
    }

    @Test
    fun texts() {
        assertEquals("сервера", serversGenitive(1))
        assertEquals("сервера", serversGenitive(21))
        assertEquals("серверов", serversGenitive(11))
        assertEquals("серверов", serversGenitive(5))
        assertEquals("серверов", serversGenitive(112))

        assertEquals("—", formatMbps(null))
        assertEquals("0,0", formatMbps(0))
        assertEquals("12,4", formatMbps(1_550_000))
        assertEquals("140", formatMbps(17_500_000))

        assertEquals("Сервер не ответил", errorReason(null))
        assertEquals("Нет разрешения на VPN", errorReason("VPN permission is required"))
        assertEquals("sing-box start failed", errorReason("sing-box start failed."))
        assertEquals(88, errorReason("x".repeat(200)).length)

        assertEquals("00:00:07", sessionClock(7))
        assertEquals("01:02:03", sessionClock(3_723))
        assertEquals("00:00:00", sessionClock(-5))
    }
}
