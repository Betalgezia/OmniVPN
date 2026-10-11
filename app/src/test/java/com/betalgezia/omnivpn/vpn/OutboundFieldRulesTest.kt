package com.betalgezia.omnivpn.vpn

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class OutboundFieldRulesTest {
    @Test
    fun rangesAcceptOneNumberOrAnAscendingPair() {
        assertEquals("16", OutboundFieldRules.range("f", "16"))
        assertEquals("16-32", OutboundFieldRules.range("f", " 16-32 "))
        assertEquals("16", OutboundFieldRules.range("f", 16))
        assertNull(OutboundFieldRules.range("f", "32-16"))
        assertNull(OutboundFieldRules.range("f", "abc"))
        assertNull(OutboundFieldRules.range("f", "-5"))
        assertNull(OutboundFieldRules.range("f", 1.5))
    }

    @Test
    fun xhttpModeOnlyTakesTheFourModes() {
        for (mode in listOf("auto", "packet-up", "stream-up", "stream-one")) {
            assertEquals(mode, OutboundFieldRules.xhttpMode(mode))
        }
        assertEquals("packet-up", OutboundFieldRules.xhttpMode(" Packet-Up "))
        assertNull(OutboundFieldRules.xhttpMode("bogus"))
        assertNull(OutboundFieldRules.xhttpMode(""))
    }

    @Test
    fun xmuxKeepsGoodFieldsUnderAnySpelling() {
        val source = mapOf(
            "maxConcurrency" to "16-32",
            "max-connections" to 8,
            "cMaxReuseTimes" to "x",
            "hKeepAlivePeriod" to 0
        )
        val xmux = OutboundFieldRules.xmux { source[it] }!!
        assertEquals("16-32", xmux.getString("max_concurrency"))
        assertEquals("8", xmux.getString("max_connections"))
        assertFalse(xmux.has("c_max_reuse_times"))
        assertEquals(0L, xmux.getLong("h_keep_alive_period"))
    }

    @Test
    fun xmuxWithNothingUsableIsLeftOut() {
        assertNull(OutboundFieldRules.xmux { mapOf("maxConcurrency" to "nope")[it] })
    }

    @Test
    fun sanitizesAnXhttpTransportFromASingBoxImport() {
        val transport = JSONObject().put("type", "xhttp").put("mode", "bogus").put("x_padding_bytes", 500)
            .put("xmux", JSONObject().put("max_concurrency", 4).put("h_keep_alive_period", -1))
        OutboundFieldRules.sanitizeXhttpTransport(transport)
        assertFalse(transport.has("mode"))
        assertEquals("500", transport.getString("x_padding_bytes"))
        assertEquals("4", transport.getJSONObject("xmux").getString("max_concurrency"))
        assertFalse(transport.getJSONObject("xmux").has("h_keep_alive_period"))
    }

    @Test
    fun leavesOtherTransportsAlone() {
        val ws = JSONObject().put("type", "ws").put("mode", "anything")
        OutboundFieldRules.sanitizeXhttpTransport(ws)
        assertEquals("anything", ws.getString("mode"))
    }
}
