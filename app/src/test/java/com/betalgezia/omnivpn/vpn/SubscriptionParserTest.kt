package com.betalgezia.omnivpn.vpn

import com.betalgezia.omnivpn.data.model.Protocol
import java.util.Base64
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubscriptionParserTest {
    @Test fun parsesVlessTlsAndWs() {
        val nodes = SubscriptionParser.parse("vless://00000000-0000-0000-0000-000000000001@example.com:443?security=tls&sni=cdn.example&type=ws&path=%2Fapi&host=cdn.example#Test")
        assertEquals(1, nodes.size)
        assertEquals(Protocol.VLESS, nodes.single().protocol)
        val raw = JSONObject(nodes.single().rawConfig!!)
        assertEquals("vless", raw.getString("type"))
        assertEquals("cdn.example", raw.getJSONObject("tls").getString("server_name"))
        assertEquals("ws", raw.getJSONObject("transport").getString("type"))
    }

    @Test fun preservesLiteralPlusInPassword() {
        val nodes = SubscriptionParser.parse("trojan://pa+ss@example.com:443#Plus")
        assertEquals("pa+ss", nodes.single().password)
    }

    @Test fun parsesBase64Subscription() {
        val payload = "trojan://pass@example.com:443?security=tls#One\nhysteria2://secret@example.org:443?sni=example.org#Two"
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(payload.toByteArray())
        val nodes = SubscriptionParser.parse(encoded)
        assertEquals(2, nodes.size)
        assertTrue(nodes.all { it.password?.isNotBlank() == true })
    }
}
