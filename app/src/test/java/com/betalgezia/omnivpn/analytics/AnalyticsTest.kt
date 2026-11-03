package com.betalgezia.omnivpn.analytics

import com.betalgezia.omnivpn.data.model.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals

class AnalyticsTest {
    @Test
    fun everyProtocolHasAStableUniqueAnalyticsName() {
        val names = Protocol.values().map { Analytics.protocolName(it) }
        assertEquals(Protocol.values().size, names.toSet().size)
        assertEquals("vless", Analytics.protocolName(Protocol.VLESS))
        assertEquals("trojan", Analytics.protocolName(Protocol.TROJAN))
        assertEquals("hysteria2", Analytics.protocolName(Protocol.HYSTERIA2))
        assertEquals("amneziawg", Analytics.protocolName(Protocol.AMNEZIAWG))
        assertEquals("shadowsocks", Analytics.protocolName(Protocol.SHADOWSOCKS))
        assertEquals("vmess", Analytics.protocolName(Protocol.VMESS))
        assertEquals("tuic", Analytics.protocolName(Protocol.TUIC))
    }

    @Test
    fun warpIsDistinctFromAmneziawg() {
        assertEquals(Analytics.WARP_PROTOCOL, Analytics.protocolName(Protocol.WARP))
        assertEquals("warp", Analytics.protocolName(Protocol.WARP))
        // WARP runs on AmneziaWG underneath, but the dashboard separates them.
        assertEquals("amneziawg", Analytics.protocolName(Protocol.AMNEZIAWG))
    }

    @Test
    fun warpHasItsOwnProtocolGroup() {
        assertEquals("warp", Analytics.protocolGroup(Analytics.WARP_PROTOCOL))
        assertEquals("proxy", Analytics.protocolGroup("vless"))
        assertEquals("proxy", Analytics.protocolGroup("amneziawg"))
    }
}
