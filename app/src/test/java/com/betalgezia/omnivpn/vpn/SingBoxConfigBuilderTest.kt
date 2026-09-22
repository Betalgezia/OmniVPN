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
    @Test fun `inbounds must be array`() {
        val config = JSONObject(
            SingBoxConfigBuilder.build(
                Node(
                    name = "test",
                    protocol = Protocol.VLESS,
                    server = "example.com",
                    port = 443,
                    uuid = "00000000-0000-0000-0000-000000000001"
                )
            )
        )
        assertTrue(config.get("inbounds") is JSONArray)
    }

    @Test fun directOutboundIsNonEmptyAndUsedForDnsBootstrap() {
        val config = JSONObject(
            SingBoxConfigBuilder.build(
                Node(
                    name = "test",
                    protocol = Protocol.VLESS,
                    server = "196.196.206.38",
                    port = 443,
                    uuid = "00000000-0000-0000-0000-000000000001"
                )
            )
        )
        val outbounds = config.getJSONArray("outbounds")
        val direct = (0 until outbounds.length())
            .map { outbounds.getJSONObject(it) }
            .first { it.getString("tag") == "direct" }
        assertEquals("dns-local", direct.getString("domain_resolver"))
        val rules = config.getJSONObject("route").getJSONArray("rules")
        assertEquals("sniff", rules.getJSONObject(0).getString("action"))
        assertEquals("dns", rules.getJSONObject(1).getString("protocol"))
        assertEquals("hijack-dns", rules.getJSONObject(1).getString("action"))
    }

    @Test fun vlessBuildsTunAndDns() {
        val config = JSONObject(SingBoxConfigBuilder.build(Node(name="test", protocol=Protocol.VLESS, server="example.com", port=443, uuid="00000000-0000-0000-0000-000000000001")))
        assertEquals("proxy", config.getJSONObject("route").getString("final"))
        assertEquals("dns-local", config.getJSONObject("route").getString("default_domain_resolver"))
        assertEquals("tun", config.getJSONArray("inbounds").getJSONObject(0).getString("type"))
        assertTrue(config.getJSONObject("dns").getJSONArray("servers").toString().contains("fakeip"))
        assertTrue(config.getJSONObject("dns").getBoolean("reverse_mapping"))
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
        assertEquals("direct", endpoint.getString("detour"))
        assertEquals(10, endpoint.getInt("jc"))
        assertEquals(123, endpoint.getLong("h1"))
        assertFalse(config.getJSONArray("outbounds").toString().contains("wireguard"))
    }

    @Test fun awgNormalizesStringNumericParametersAndUsesDirectBootstrapDns() {
        val raw = JSONObject()
            .put("type", "wireguard")
            .put("address", JSONArray().put("10.0.0.2/32"))
            .put("private_key", "base64-private")
            .put("jc", "7")
            .put("h1", "123")
            .put(
                "peers",
                JSONArray().put(
                    JSONObject()
                        .put("address", "203.0.113.10")
                        .put("port", 4500)
                        .put("public_key", "base64-public")
                        .put("allowed_ips", JSONArray().put("0.0.0.0/0"))
                )
            )
            .toString()

        val config = JSONObject(
            SingBoxConfigBuilder.build(
                Node(
                    name = "awg",
                    protocol = Protocol.AMNEZIAWG,
                    server = "203.0.113.10",
                    port = 4500,
                    privateKey = "base64-private",
                    rawConfig = raw
                )
            )
        )

        val endpoint = config.getJSONArray("endpoints").getJSONObject(0)
        assertEquals(7L, endpoint.getLong("jc"))
        assertEquals(123L, endpoint.getLong("h1"))
        assertEquals("direct", endpoint.getString("detour"))
        // Rule 0 is the tun-wide sniff (see directOutboundIsNonEmptyAndUsedForDnsBootstrap
        // / the dedicated "sniff precedes DNS hijack" coverage); the DNS hijack is rule 1.
        assertEquals(
            "hijack-dns",
            config.getJSONObject("route").getJSONArray("rules").getJSONObject(1).getString("action")
        )
    }

    @Test fun invalidPortIsRejectedBeforeCore() {
        assertFailsWith<IllegalArgumentException> { SingBoxConfigBuilder.build(Node(name="test", protocol=Protocol.VLESS, server="example.com", port=70000, uuid="00000000-0000-0000-0000-000000000003")) }
    }

    @Test fun probeConfigHasNoTunAndUniqueTagsPerNode() {
        val vless = Node(id=1, name="a", protocol=Protocol.VLESS, server="one.example", port=443, uuid="00000000-0000-0000-0000-000000000001")
        val trojan = Node(id=2, name="b", protocol=Protocol.TROJAN, server="two.example", port=443, password="secret")
        val probe = SingBoxConfigBuilder.buildProbeConfig(listOf(vless, trojan))

        assertTrue(probe.buildErrors.isEmpty())
        assertEquals(setOf(1L, 2L), probe.tagsByNodeId.keys)
        assertEquals(2, probe.tagsByNodeId.values.toSet().size, "tags must be unique per node")

        val config = JSONObject(requireNotNull(probe.json))
        assertFalse(config.has("inbounds"))
        assertFalse(config.has("tun"))
        assertFalse(config.getJSONObject("dns").getJSONArray("servers").toString().contains("fakeip"))
        assertEquals("dns-local", config.getJSONObject("route").getString("default_domain_resolver"))
        val outbounds = (0 until config.getJSONArray("outbounds").length())
            .map { config.getJSONArray("outbounds").getJSONObject(it) }
        assertTrue(outbounds.any { it.getString("tag") == "direct" })
        assertTrue(outbounds.any { it.getString("tag") == probe.tagsByNodeId.getValue(1) })
        assertTrue(outbounds.any { it.getString("tag") == probe.tagsByNodeId.getValue(2) })
    }

    @Test fun probeConfigSkipsWarpEntirely() {
        val warp = Node(id=1, name="warp", protocol=Protocol.WARP, server="162.159.192.1", port=2408)
        val probe = SingBoxConfigBuilder.buildProbeConfig(listOf(warp))

        assertTrue(probe.tagsByNodeId.isEmpty())
        assertTrue(probe.buildErrors.isEmpty())
        assertEquals(null, probe.json)
    }

    @Test fun probeConfigReportsOneBadNodeWithoutDroppingTheRest() {
        val broken = Node(id=1, name="broken", protocol=Protocol.VLESS, server="example.com", port=443, uuid="")
        val ok = Node(id=2, name="ok", protocol=Protocol.TROJAN, server="two.example", port=443, password="secret")
        val probe = SingBoxConfigBuilder.buildProbeConfig(listOf(broken, ok))

        assertTrue(probe.buildErrors.containsKey(1))
        assertEquals(setOf(2L), probe.tagsByNodeId.keys)
        val config = JSONObject(requireNotNull(probe.json))
        assertFalse(config.getJSONArray("outbounds").toString().contains("example.com"))
    }

    @Test fun probeConfigBuildsAwgEndpointWithDirectDetour() {
        val raw = JSONObject().put("type","wireguard").put("address", JSONArray().put("10.0.0.2/32"))
            .put("private_key","base64-private")
            .put("peers", JSONArray().put(JSONObject().put("address","203.0.113.10").put("port",51820)
                .put("public_key","base64-public").put("allowed_ips", JSONArray().put("0.0.0.0/0"))))
            .toString()
        val awg = Node(id=5, name="awg", protocol=Protocol.AMNEZIAWG, server="203.0.113.10", port=51820, privateKey="base64-private", rawConfig=raw)
        val probe = SingBoxConfigBuilder.buildProbeConfig(listOf(awg))

        assertTrue(probe.buildErrors.isEmpty())
        val config = JSONObject(requireNotNull(probe.json))
        val endpoint = config.getJSONArray("endpoints").getJSONObject(0)
        assertEquals(probe.tagsByNodeId.getValue(5), endpoint.getString("tag"))
        assertEquals("direct", endpoint.getString("detour"))
        assertTrue(config.getJSONArray("outbounds").toString().contains("\"direct\""))
    }
}
