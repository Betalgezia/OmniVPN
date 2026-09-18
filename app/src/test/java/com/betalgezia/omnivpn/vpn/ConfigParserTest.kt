package com.betalgezia.omnivpn.vpn

import com.betalgezia.omnivpn.data.model.Protocol
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ConfigParserTest {
    @Test
    fun parsesMihomoVlessYaml() {
        val yaml = """
            proxies:
              - name: CDN
                type: vless
                server: edge.example.com
                port: 443
                uuid: 00000000-0000-0000-0000-000000000001
                tls: true
                servername: cdn.example.com
                network: ws
                ws-opts:
                  path: /api
                  headers:
                    Host: cdn.example.com
        """.trimIndent()
        val node = ConfigParser.parse(yaml).single()
        assertEquals(Protocol.VLESS, node.protocol)
        val raw = JSONObject(node.rawConfig!!)
        assertEquals("cdn.example.com", raw.getJSONObject("tls").getString("server_name"))
        assertEquals("/api", raw.getJSONObject("transport").getString("path"))
        assertEquals("cdn.example.com", raw.getJSONObject("transport").getJSONObject("headers").getString("Host"))
    }

    @Test
    fun parsesSingBoxWireguardEndpointAndKeepsAllPeers() {
        val json = JSONObject()
            .put("endpoints", JSONArray().put(
                JSONObject().put("type", "wireguard")
                    .put("mtu", 1280)
                    .put("address", JSONArray().put("10.0.0.2/32"))
                    .put("private_key", "local-private")
                    .put("peers", JSONArray().apply {
                        put(JSONObject().put("address", "198.51.100.10").put("port", 51820).put("public_key", "peer-a").put("allowed_ips", JSONArray().put("0.0.0.0/0")))
                        put(JSONObject().put("address", "198.51.100.11").put("port", 51821).put("public_key", "peer-b").put("allowed_ips", JSONArray().put("10.0.0.0/8")))
                    })
            ))
        val node = ConfigParser.parse(json.toString()).single()
        assertEquals(Protocol.AMNEZIAWG, node.protocol)
        val raw = JSONObject(node.rawConfig!!)
        assertEquals(2, raw.getJSONArray("peers").length())
        assertEquals("peer-b", raw.getJSONArray("peers").getJSONObject(1).getString("public_key"))
    }

    @Test
    fun parsesMihomoWireguardReserved() {
        val yaml = """
            proxies:
              - name: WARP
                type: wireguard
                server: engage.cloudflareclient.com
                port: 2408
                private-key: local-private
                peer-public-key: peer-public
                ip: 172.16.0.2
                ipv6: 2606:4700:4700::1001
                reserved: [1, 2, 255]
        """.trimIndent()
        val node = ConfigParser.parse(yaml).single()
        val peer = JSONObject(node.rawConfig!!).getJSONArray("peers").getJSONObject(0)
        assertNotNull(peer.getJSONArray("reserved"))
        assertEquals(255, peer.getJSONArray("reserved").getInt(2))
    }
}