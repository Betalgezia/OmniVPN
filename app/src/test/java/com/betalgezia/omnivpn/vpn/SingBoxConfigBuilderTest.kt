package com.betalgezia.omnivpn.vpn

import org.json.JSONArray
import com.betalgezia.omnivpn.data.model.AwgParameters
import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Protocol
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingBoxConfigBuilderTest {
    @Test fun vlessBuildsTunAndDns() {
        val config = JSONObject(SingBoxConfigBuilder.build(Node(name="test", protocol=Protocol.VLESS, server="example.com", port=443, uuid="00000000-0000-0000-0000-000000000001")))
        assertEquals("proxy", config.getJSONObject("route").getString("final"))
        assertEquals("dns-local", config.getJSONObject("route").getString("default_domain_resolver"))
        assertEquals("tun", config.getJSONArray("inbounds").getJSONObject(0).getString("type"))
        assertTrue(config.getJSONObject("dns").getJSONArray("servers").toString().contains("fakeip"))
        assertTrue(config.getJSONObject("dns").getBoolean("reverse_mapping"))
        assertEquals("resolve", config.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getString("action"))
        assertFalse(config.has("endpoints"))
    }

    @Test fun rawConfigIsFilteredAndAppOwnsTag() {
        val raw = JSONObject().put("type","vless").put("tag","attacker-tag").put("server","raw.example").put("server_port",443).put("uuid","raw-uuid").put("tls",JSONObject().put("enabled",true)).put("unknown_field","must-not-survive").toString()
        val config = JSONObject(SingBoxConfigBuilder.build(Node(name="test", protocol=Protocol.VLESS, server="example.com", port=8443, uuid="00000000-0000-0000-0000-000000000002", rawConfig=raw)))
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("proxy", outbound.getString("tag"))
        assertEquals("example.com", outbound.getString("server"))
        assertEquals(8443, outbound.getInt("server_port"))
        assertEquals("00000000-0000-0000-0000-000000000002", outbound.getString("uuid"))
        assertFalse(outbound.has("unknown_field"))
    }

    @Test fun removesVisionWhenNodeCarriesTransport() {
        val raw = JSONObject().put("type","vless").put("server","raw.example").put("server_port",443)
            .put("uuid","raw-uuid").put("flow","xtls-rprx-vision")
            .put("transport",JSONObject().put("type","xhttp").put("path","/"))
            .toString()
        val config = JSONObject(SingBoxConfigBuilder.build(Node(
            name="test", protocol=Protocol.VLESS, server="example.com", port=443,
            uuid="00000000-0000-0000-0000-000000000004", rawConfig=raw
        )))
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)
        assertFalse(outbound.has("flow"))
    }

    @Test fun removesInvalidPacketEncoding() {
        val raw = JSONObject().put("type","vless").put("server","raw.example").put("server_port",443)
            .put("uuid","raw-uuid").put("packet_encoding","none").toString()
        val config = JSONObject(SingBoxConfigBuilder.build(Node(
            name="test", protocol=Protocol.VLESS, server="example.com", port=443,
            uuid="00000000-0000-0000-0000-000000000005", rawConfig=raw
        )))
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)
        assertFalse(outbound.has("packet_encoding"))
    }
    @Test fun awgBuildsEndpointAndRoutesThroughIt() {
        val raw = JSONObject().put("type","wireguard").put("tag","bad-tag").put("address", JSONArray().put("10.0.0.2/32")).put("private_key","base64-private").put("jc",10).put("h1",123).put("peers",JSONArray().put(JSONObject().put("address","203.0.113.10").put("port",51820).put("public_key","base64-public").put("allowed_ips",JSONArray().put("0.0.0.0/0")))).toString()
        val config = JSONObject(SingBoxConfigBuilder.build(Node(name="awg", protocol=Protocol.AMNEZIAWG, server="203.0.113.10", port=51820, privateKey="base64-private", awg=AwgParameters(jc=10,h1=123), rawConfig=raw)))
        assertEquals("awg", config.getJSONObject("route").getString("final"))
        val endpoint = config.getJSONArray("endpoints").getJSONObject(0)
        assertEquals("wireguard", endpoint.getString("type"))
        assertEquals("awg", endpoint.getString("tag"))
        assertEquals(10, endpoint.getInt("jc"))
        assertEquals(123, endpoint.getLong("h1"))
        assertFalse(config.getJSONArray("outbounds").toString().contains("wireguard"))
    }

    @Test fun invalidPortIsRejectedBeforeCore() {
        assertFailsWith<IllegalArgumentException> { SingBoxConfigBuilder.build(Node(name="test", protocol=Protocol.VLESS, server="example.com", port=70000, uuid="00000000-0000-0000-0000-000000000003")) }
    }
}