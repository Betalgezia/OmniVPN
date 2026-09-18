package com.betalgezia.omnivpn.vpn

import com.betalgezia.omnivpn.data.model.Protocol
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionParserTest {
    @Test
    fun parsesVlessRealityUri() {
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443?security=reality&sni=cdn.example.com&fp=chrome&pbk=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA&sid=abcd&type=ws&path=%2Fapi&host=cdn.example.com&flow=xtls-rprx-vision#Reality"
        val node = SubscriptionParser.parse(uri).single()
        assertEquals(Protocol.VLESS, node.protocol)
        assertEquals("Reality", node.name)
        val raw = JSONObject(node.rawConfig!!)
        assertEquals("xtls-rprx-vision", raw.getString("flow"))
        assertEquals("ws", raw.getJSONObject("transport").getString("type"))
        assertEquals("/api", raw.getJSONObject("transport").getString("path"))
        assertEquals("cdn.example.com", raw.getJSONObject("tls").getString("server_name"))
        assertTrue(raw.getJSONObject("tls").getJSONObject("reality").getBoolean("enabled"))
    }

    @Test
    fun preservesPlusInVlessQueryValues() {
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443?security=tls&sni=cdn.example.com&type=ws&path=%2Fa%2Bb&host=cdn.example.com"
        val node = SubscriptionParser.parse(uri).single()
        val transport = JSONObject(node.rawConfig!!).getJSONObject("transport")
        assertEquals("/a+b", transport.getString("path"))
    }


    @Test
    fun defaultsVlessPortTo443() {
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com"
        val node = SubscriptionParser.parse(uri).single()
        assertEquals(443, node.port)
    }

    @Test
    fun defaultsTrojanPortTo443AndPreservesColonInPassword() {
        val uri = "trojan://part%3Asecret@example.com"
        val node = SubscriptionParser.parse(uri).single()
        assertEquals(443, node.port)
        assertEquals("part:secret", node.password)
    }

    @Test
    fun defaultsHysteria2PortTo443() {
        val uri = "hysteria2://secret@example.com"
        val node = SubscriptionParser.parse(uri).single()
        assertEquals(443, node.port)
    }
    @Test
    fun enablesDefaultTlsForVless443WithoutSecurityParameter() {
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443"
        val raw = JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!)
        assertTrue(raw.getJSONObject("tls").getBoolean("enabled"))
    }

    @Test
    fun securityNoneDisablesTlsEvenOn443() {
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443?security=none"
        val raw = JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!)
        assertFalse(raw.has("tls"))
    }
    @Test
    fun doesNotForceTlsForPlainVless() {
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:80?type=ws&path=%2F"
        val raw = JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!)
        assertFalse(raw.has("tls"))
    }


    @Test
    fun dropsVisionFlowWhenTransportIsPresent() {
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443?security=tls&type=xhttp&path=%2F&flow=xtls-rprx-vision"
        val raw = JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!)
        assertFalse(raw.has("flow"))
        assertEquals("xhttp", raw.getJSONObject("transport").getString("type"))
    }

    @Test
    fun normalizesVisionUdp443ToXudp() {
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443?security=tls&flow=xtls-rprx-vision-udp443"
        val raw = JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!)
        assertEquals("xtls-rprx-vision", raw.getString("flow"))
        assertEquals("xudp", raw.getString("packet_encoding"))
    }

    @Test
    fun rejectsInvalidRealityPublicKeyAndShortIdWithoutPoisoningTls() {
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443?security=reality&sni=edge.example.com&pbk=not-a-key&sid=xyz"
        val raw = JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!)
        val tls = raw.getJSONObject("tls")
        assertFalse(tls.has("reality"))
        assertEquals("edge.example.com", tls.getString("server_name"))
    }
    @Test
    fun parsesTrojanUriWithTlsAndGrpc() {
        val uri = "trojan://p%2Bss@example.com:443?sni=trojan.example.com&security=tls&type=grpc&serviceName=proxy#Trojan"
        val node = SubscriptionParser.parse(uri).single()
        assertEquals(Protocol.TROJAN, node.protocol)
        assertEquals("p+ss", node.password)
        val raw = JSONObject(node.rawConfig!!)
        assertEquals("trojan.example.com", raw.getJSONObject("tls").getString("server_name"))
        assertEquals("proxy", raw.getJSONObject("transport").getString("service_name"))
    }

    @Test
    fun securityNoneOverridesOtherVlessTlsParameters() {
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443?security=none&sni=cdn.example.com&fp=chrome&pbk=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA"
        val raw = JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!)
        assertFalse(raw.has("tls"))
    }

    @Test
    fun securityNoneDisablesTrojanDefaultTls() {
        val uri = "trojan://secret@example.com:443?security=none&sni=trojan.example.com"
        val raw = JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!)
        assertFalse(raw.has("tls"))
    }
    @Test
    fun parsesHysteria2Uri() {
        val uri = "hysteria2://p%2Bss@example.com:443?insecure=1&sni=example.com&obfs=salamander&obfs-password=secret&up=20Mbps&down=100Mbps#HY2"
        val node = SubscriptionParser.parse(uri).single()
        assertEquals(Protocol.HYSTERIA2, node.protocol)
        val raw = JSONObject(node.rawConfig!!)
        assertEquals(20, raw.getInt("up_mbps"))
        assertEquals(100, raw.getInt("down_mbps"))
        assertEquals("salamander", raw.getJSONObject("obfs").getString("type"))
        assertTrue(raw.getJSONObject("tls").getBoolean("insecure"))
    }
}
