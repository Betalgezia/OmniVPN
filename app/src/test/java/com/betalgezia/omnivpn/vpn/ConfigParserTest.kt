package com.betalgezia.omnivpn.vpn

import com.betalgezia.omnivpn.data.model.Protocol
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertEquals(true, raw.getJSONObject("tls").getBoolean("enabled"))
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
                    .put("private_key", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
                    .put("peers", JSONArray().apply {
                        put(JSONObject().put("address", "198.51.100.10").put("port", 51820).put("public_key", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8").put("allowed_ips", JSONArray().put("0.0.0.0/0")))
                        put(JSONObject().put("address", "198.51.100.11").put("port", 51821).put("public_key", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8").put("allowed_ips", JSONArray().put("10.0.0.0/8")))
                    })
            ))
        val node = ConfigParser.parse(json.toString()).single()
        assertEquals(Protocol.AMNEZIAWG, node.protocol)
        val raw = JSONObject(node.rawConfig!!)
        assertEquals(2, raw.getJSONArray("peers").length())
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", raw.getJSONArray("peers").getJSONObject(1).getString("public_key"))
    }

    @Test
    fun parsesMihomoTrojanTlsBooleanAsTlsObject() {
        val yaml = """
            proxies:
              - name: Trojan
                type: trojan
                server: edge.example.com
                port: 443
                password: secret
                tls: true
                sni: trojan.example.com
        """.trimIndent()
        val node = ConfigParser.parse(yaml).single()
        val raw = JSONObject(node.rawConfig!!)
        assertEquals("trojan.example.com", raw.getJSONObject("tls").getString("server_name"))
        assertEquals(true, raw.getJSONObject("tls").getBoolean("enabled"))
    }

    @Test
    fun parsesHysteria2TlsBooleanAsTlsObject() {
        val yaml = """
            proxies:
              - name: Hysteria
                type: hysteria2
                server: edge.example.com
                port: 443
                password: secret
                tls: true
        """.trimIndent()
        val node = ConfigParser.parse(yaml).single()
        val raw = JSONObject(node.rawConfig!!)
        assertEquals("edge.example.com", raw.getJSONObject("tls").getString("server_name"))
        assertEquals(true, raw.getJSONObject("tls").getBoolean("enabled"))
    }

    @Test
    fun preservesSingBoxTlsObject() {
        val json = JSONObject().put("outbounds", JSONArray().put(
            JSONObject().put("type", "vless")
                .put("server", "edge.example.com")
                .put("server_port", 443)
                .put("uuid", "00000000-0000-0000-0000-000000000001")
                .put("tls", JSONObject().put("enabled", true).put("server_name", "direct.example.com"))
        ))
        val node = ConfigParser.parse(json.toString()).single()
        val tls = JSONObject(node.rawConfig!!).getJSONObject("tls")
        assertEquals("direct.example.com", tls.getString("server_name"))
    }

    @Test
    fun parsesAmneziaWireguardConf() {
        val conf = """
            [Interface]
            PrivateKey = AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8
            Address = 10.0.0.2/32, fd00::2/128
            MTU = 1280
            Jc = 4
            Jmin = 10
            Jmax = 20
            S1 = 1
            S2 = 2
            S3 = 3
            S4 = 4
            H1 = 100
            H4 = 400
            I1 = 5
            I5 = 9

            [Peer]
            PublicKey = AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8
            PresharedKey = AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8
            Endpoint = 198.51.100.10:51820
            AllowedIPs = 0.0.0.0/0, ::/0
            PersistentKeepalive = 25
            Reserved = 1, 2, 255
        """.trimIndent()

        val node = ConfigParser.parse(conf).single()
        assertEquals(Protocol.AMNEZIAWG, node.protocol)
        assertEquals("198.51.100.10", node.server)
        assertEquals(51820, node.port)
        val raw = JSONObject(node.rawConfig!!)
        assertEquals(1280, raw.getInt("mtu"))
        assertEquals(4, raw.getInt("jc"))
        assertEquals(20, raw.getInt("jmax"))
        val peer = raw.getJSONArray("peers").getJSONObject(0)
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", peer.getString("pre_shared_key"))
        assertEquals(25, peer.getInt("persistent_keepalive_interval"))
        assertEquals(255, peer.getJSONArray("reserved").getInt(2))
    }

    @Test
    fun preservesFullMihomoXHttpOptions() {
        val yaml = """
            proxies:
              - name: XHTTP
                type: vless
                server: edge.example.com
                port: 443
                uuid: 00000000-0000-0000-0000-000000000001
                tls: true
                network: xhttp
                xhttp-opts:
                  mode: stream-one
                  path: /xhttp
                  host: cdn.example.com
                  session-placement: header
                  session-key: X-Session-Id
                  seq-placement: query
                  seq-key: packet_seq
                  session-table: hex
                  session-length: 32-32
                  uplink-data-placement: header
                  uplink-data-key: X-Data
                  uplink-chunk-size: 3000-4000
                  uplink-http-method: post
                  x-padding-obfs-mode: true
                  x-padding-placement: header
                  x-padding-header: X-Padding
                  x-padding-method: tokenish
                  sc-max-each-post-bytes: 1000000-1000000
                  sc-min-posts-interval-ms: 30-30
                  no-grpc-header: true
                  x-padding-bytes: 100-1000
                  xmux:
                    max-concurrency: 1-1
                    h-max-request-times: 600-900
        """.trimIndent()
        val raw = JSONObject(ConfigParser.parse(yaml).single().rawConfig!!)
        val x = raw.getJSONObject("transport")
        assertEquals("stream-one", x.getString("mode"))
        assertEquals("header", x.getString("session_placement"))
        assertEquals("X-Session-Id", x.getString("session_key"))
        assertEquals("query", x.getString("seq_placement"))
        assertEquals("packet_seq", x.getString("seq_key"))
        assertEquals("32-32", x.getString("session_length"))
        assertEquals("header", x.getString("uplink_data_placement"))
        assertEquals(true, x.getBoolean("x_padding_obfs_mode"))
        assertEquals("tokenish", x.getString("x_padding_method"))
        assertEquals(true, x.getBoolean("no_grpc_header"))
        assertEquals("1-1", x.getJSONObject("xmux").getString("max_concurrency"))
    }
    @Test
    fun dropsVisionFlowWhenMihomoTransportIsPresent() {
        val yaml = """
            proxies:
              - name: XHTTP Vision
                type: vless
                server: edge.example.com
                port: 443
                uuid: 00000000-0000-0000-0000-000000000001
                tls: true
                network: xhttp
                flow: xtls-rprx-vision
                xhttp-opts:
                  path: /xhttp
        """.trimIndent()
        val raw = JSONObject(ConfigParser.parse(yaml).single().rawConfig!!)
        assertEquals("xhttp", raw.getJSONObject("transport").getString("type"))
        assertEquals(false, raw.has("flow"))
    }

    @Test
    fun explicitMihomoTlsFalseWinsOverSni() {
        val yaml = """
            proxies:
              - name: Plain
                type: vless
                server: edge.example.com
                port: 443
                uuid: 00000000-0000-0000-0000-000000000001
                tls: false
                sni: cdn.example.com
        """.trimIndent()
        val raw = JSONObject(ConfigParser.parse(yaml).single().rawConfig!!)
        assertFalse(raw.has("tls"))
    }
    @Test
    fun rejectsInvalidRealityKeyInMihomoImportWithoutRealityBlock() {
        val yaml = """
            proxies:
              - name: Broken Reality
                type: vless
                server: edge.example.com
                port: 443
                uuid: 00000000-0000-0000-0000-000000000001
                tls: true
                reality-opts:
                  public-key: invalid
                  short-id: xyz
        """.trimIndent()
        val raw = JSONObject(ConfigParser.parse(yaml).single().rawConfig!!)
        assertFalse(raw.getJSONObject("tls").has("reality"))
    }

    @Test
    fun normalizesBareWireGuardAddressesToPrefixes() {
        val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        val yaml = """
            proxies:
              - name: WG
                type: wireguard
                server: 198.51.100.10
                port: 51820
                private-key: $key
                peer-public-key: $key
                ip: 10.0.0.2
                ipv6: 2001:db8::2
                allowed-ips: 10.0.0.0, 2001:db8::/32
        """.trimIndent()
        val raw = JSONObject(ConfigParser.parse(yaml).single().rawConfig!!)
        val address = raw.getJSONArray("address")
        assertEquals("10.0.0.2/32", address.getString(0))
        assertEquals("2001:db8::2/128", address.getString(1))
        val allowed = raw.getJSONArray("peers").getJSONObject(0).getJSONArray("allowed_ips")
        assertEquals("10.0.0.0/32", allowed.getString(0))
        assertEquals("2001:db8::/32", allowed.getString(1))
    }
    @Test
    fun canonicalizesUrlSafeWireGuardKeys() {
        val urlSafe = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        val yaml = """
            proxies:
              - name: WG
                type: wireguard
                server: 198.51.100.10
                port: 51820
                private-key: $urlSafe
                peer-public-key: $urlSafe
                ip: 10.0.0.2
        """.trimIndent()
        val node = ConfigParser.parse(yaml).single()
        val raw = JSONObject(node.rawConfig!!)
        assertEquals(urlSafe, raw.getString("private_key"))
        assertEquals(urlSafe, raw.getJSONArray("peers").getJSONObject(0).getString("public_key"))
    }
    @Test
    fun rejectsInvalidWireGuardPresharedKey() {
        val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        val yaml = """
            proxies:
              - name: Broken PSK
                type: wireguard
                server: 198.51.100.10
                port: 51820
                private-key: $key
                peer-public-key: $key
                pre-shared-key: invalid
                ip: 10.0.0.2
        """.trimIndent()
        assertEquals(0, ConfigParser.parse(yaml).size)
    }
    @Test
    fun rejectsInvalidWireGuardPrivateKey() {
        val yaml = """
            proxies:
              - name: Broken WG
                type: wireguard
                server: 198.51.100.10
                port: 51820
                private-key: invalid
                peer-public-key: invalid
                ip: 10.0.0.2
        """.trimIndent()
        assertEquals(0, ConfigParser.parse(yaml).size)
    }

    @Test
    fun rejectsInvalidWireGuardPeerPublicKey() {
        val validPrivate = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        val yaml = """
            proxies:
              - name: Broken WG
                type: wireguard
                server: 198.51.100.10
                port: 51820
                private-key: $validPrivate
                peer-public-key: invalid
                ip: 10.0.0.2
        """.trimIndent()
        assertEquals(0, ConfigParser.parse(yaml).size)
    }
    @Test
    fun normalizesAmneziaConfReservedBytes() {
        val conf = """
            [Interface]
            PrivateKey = AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8
            Endpoint = 198.51.100.10:51820
            AllowedIPs = 0.0.0.0/0
            Reserved = 1,2,3
        """.trimIndent()
        val raw = JSONObject(ConfigParser.parse(conf).single().rawConfig!!)
        val reserved = raw.getJSONArray("peers").getJSONObject(0).getJSONArray("reserved")
        assertEquals(3, reserved.length())
        assertEquals(3, reserved.getInt(2))
    }

    @Test
    fun dropsVisionFlowWhenSingBoxTransportObjectIsPresent() {
        val json = JSONObject().put("outbounds", JSONArray().put(
            JSONObject().put("type", "vless")
                .put("server", "edge.example.com")
                .put("server_port", 443)
                .put("uuid", "00000000-0000-0000-0000-000000000001")
                .put("flow", "xtls-rprx-vision")
                .put("transport", JSONObject().put("type", "xhttp").put("path", "/"))
        ))
        val raw = JSONObject(ConfigParser.parse(json.toString()).single().rawConfig!!)
        assertFalse(raw.has("flow"))
    }

    @Test
    fun sanitizesNestedSingBoxRealityAndUtlsFields() {
        val json = JSONObject().put("outbounds", JSONArray().put(
            JSONObject().put("type", "vless")
                .put("server", "edge.example.com")
                .put("server_port", 443)
                .put("uuid", "00000000-0000-0000-0000-000000000001")
                .put("tls", JSONObject()
                    .put("enabled", true)
                    .put("server_name", "edge.example.com")
                    .put("utls", JSONObject().put("enabled", true).put("fingerprint", "broken"))
                    .put("reality", JSONObject().put("enabled", true).put("public_key", "broken").put("short_id", "xyz"))
                )
        ))
        val raw = JSONObject(ConfigParser.parse(json.toString()).single().rawConfig!!)
        val tls = raw.getJSONObject("tls")
        assertFalse(tls.has("reality"))
        assertEquals("random", tls.getJSONObject("utls").getString("fingerprint"))
    }
    @Test
    fun parsesMihomoWireguardReserved() {
        val yaml = """
            proxies:
              - name: WARP
                type: wireguard
                server: engage.cloudflareclient.com
                port: 2408
                private-key: AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8
                peer-public-key: AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8
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