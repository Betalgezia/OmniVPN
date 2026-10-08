package com.betalgezia.omnivpn.vpn

import com.betalgezia.omnivpn.data.model.Protocol
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
    fun parsesAmneziaWireguardConfWithDomainEndpoint() {
        // Regression guard for the import-time warning: a domain-name peer
        // endpoint (as opposed to a literal IP) hits a confirmed sing-box-lx
        // bug where the handshake never completes - see
        // ConfigParser.isLiteralIpHost and WarpAccount.DEFAULT_ENDPOINT.
        // Node.server must keep carrying the raw hostname unresolved so the
        // import layer can detect and warn about it.
        val conf = """
            [Interface]
            PrivateKey = AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8
            Endpoint = engage.cloudflareclient.com:2408
            AllowedIPs = 0.0.0.0/0, ::/0
        """.trimIndent()

        val node = ConfigParser.parse(conf).single()
        assertEquals(Protocol.AMNEZIAWG, node.protocol)
        assertEquals("engage.cloudflareclient.com", node.server)
        assertEquals(2408, node.port)
        assertFalse(ConfigParser.isLiteralIpHost(node.server))
    }

    @Test
    fun isLiteralIpHostDistinguishesIpsFromHostnames() {
        assertEquals(true, ConfigParser.isLiteralIpHost("198.51.100.10"))
        assertEquals(true, ConfigParser.isLiteralIpHost("162.159.192.1"))
        assertEquals(true, ConfigParser.isLiteralIpHost("2606:4700:d0::a29f:c001"))
        assertEquals(true, ConfigParser.isLiteralIpHost("::1"))
        assertFalse(ConfigParser.isLiteralIpHost("engage.cloudflareclient.com"))
        assertFalse(ConfigParser.isLiteralIpHost("vpn.example.org"))
        assertFalse(ConfigParser.isLiteralIpHost(""))
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
    fun dropsMihomoXhttpValuesTheCoreWouldRefuse() {
        val yaml = """
            proxies:
              - name: X
                type: vless
                server: edge.example.com
                port: 443
                uuid: 00000000-0000-0000-0000-000000000001
                tls: true
                network: xhttp
                xhttp-opts:
                  mode: bogus
                  path: /x
                  x-padding-bytes: abc
                  xmux:
                    max-concurrency: 16
                    max-connections: nope
        """.trimIndent()
        val x = JSONObject(ConfigParser.parse(yaml).single().rawConfig!!).getJSONObject("transport")
        assertFalse(x.has("mode"))
        assertFalse(x.has("x_padding_bytes"))
        assertEquals("16", x.getJSONObject("xmux").getString("max_concurrency"))
        assertFalse(x.getJSONObject("xmux").has("max_connections"))
    }

    @Test
    fun sanitizesXhttpTransportInSingBoxJson() {
        val json = JSONObject().put("type", "vless").put("server", "edge.example.com").put("server_port", 443)
            .put("uuid", "00000000-0000-0000-0000-000000000001")
            .put("transport", JSONObject().put("type", "xhttp").put("path", "/x").put("mode", "bogus"))
        val x = JSONObject(ConfigParser.parse(json.toString()).single().rawConfig!!).getJSONObject("transport")
        assertEquals("/x", x.getString("path"))
        assertFalse(x.has("mode"))
    }

    @Test
    fun carriesValidVlessEncryptionFromYamlAndSkipsAnInvalidOne() {
        val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 2 })
        fun yaml(encryption: String) = """
            proxies:
              - name: E
                type: vless
                server: edge.example.com
                port: 443
                uuid: 00000000-0000-0000-0000-000000000001
                tls: true
                encryption: $encryption
        """.trimIndent()
        val raw = JSONObject(ConfigParser.parse(yaml("mlkem768x25519plus.random.1rtt.$key")).single().rawConfig!!)
        assertEquals("mlkem768x25519plus.random.1rtt.$key", raw.getString("encryption"))
        assertEquals(0, ConfigParser.parse(yaml("mlkem768x25519plus.random.1rtt")).size)
    }

    @Test
    fun checksHysteria2BbrProfileAndChromeParrotOnImport() {
        fun imported(profile: String, parrot: Any) = JSONObject(
            ConfigParser.parse(
                JSONObject().put("type", "hysteria2").put("server", "example.com").put("server_port", 443)
                    .put("password", "secret").put("bbr_profile", profile).put("disable_chrome_parrot", parrot)
                    .toString()
            ).single().rawConfig!!
        )
        assertEquals("conservative", imported("Conservative", true).getString("bbr_profile"))
        assertTrue(imported("conservative", "true").getBoolean("disable_chrome_parrot"))
        assertFalse(imported("zzz", false).has("bbr_profile"))
        assertFalse(imported("zzz", false).has("disable_chrome_parrot"))
    }

    @Test
    fun parsesMihomoShadowsocksWithObfs() {
        val yaml = """
            proxies:
              - name: SS
                type: ss
                server: 1.2.3.4
                port: 8388
                cipher: aes-256-gcm
                password: pass
                plugin: obfs
                plugin-opts:
                  mode: http
                  host: bing.com
        """.trimIndent()
        val node = ConfigParser.parse(yaml).single()
        assertEquals(Protocol.SHADOWSOCKS, node.protocol)
        assertEquals("SS", node.name)
        val raw = JSONObject(node.rawConfig!!)
        assertEquals("aes-256-gcm", raw.getString("method"))
        assertEquals("pass", raw.getString("password"))
        assertEquals("obfs-local", raw.getString("plugin"))
        assertEquals("obfs=http;obfs-host=bing.com", raw.getString("plugin_opts"))
    }

    @Test
    fun parsesMihomoShadowsocksWithV2rayPluginAndSkipsUnsupportedPlugins() {
        val v2ray = """
            proxies:
              - name: SS
                type: ss
                server: example.com
                port: 443
                cipher: chacha20-ietf-poly1305
                password: pass
                plugin: v2ray-plugin
                plugin-opts:
                  mode: websocket
                  tls: true
                  host: cdn.example.com
                  path: /ws
        """.trimIndent()
        val raw = JSONObject(ConfigParser.parse(v2ray).single().rawConfig!!)
        assertEquals("v2ray-plugin", raw.getString("plugin"))
        assertEquals("mode=websocket;tls;host=cdn.example.com;path=/ws", raw.getString("plugin_opts"))
        val shadowTls = """
            proxies:
              - name: SS
                type: ss
                server: example.com
                port: 443
                cipher: chacha20-ietf-poly1305
                password: pass
                plugin: shadow-tls
                plugin-opts:
                  host: cdn.example.com
                  password: x
        """.trimIndent()
        assertEquals(0, ConfigParser.parse(shadowTls).size)
    }

    @Test
    fun parsesSingBoxShadowsocks() {
        val json = JSONObject().put("type", "shadowsocks").put("tag", "s").put("server", "example.com").put("server_port", 8388)
            .put("method", "2022-blake3-aes-256-gcm").put("password", java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 3 }))
            .put("plugin", "obfs-local").put("plugin_opts", "obfs=tls;obfs-host=bing.com")
        val raw = JSONObject(ConfigParser.parse(json.toString()).single().rawConfig!!)
        assertEquals("2022-blake3-aes-256-gcm", raw.getString("method"))
        assertEquals("obfs=tls;obfs-host=bing.com", raw.getString("plugin_opts"))
    }

    @Test
    fun parsesMihomoVmessOverWebsocketWithTls() {
        val yaml = """
            proxies:
              - name: VM
                type: vmess
                server: example.com
                port: 443
                uuid: 00000000-0000-0000-0000-000000000001
                alterId: 0
                cipher: auto
                tls: true
                servername: cdn.example.com
                network: ws
                ws-opts:
                  path: /ws
                  headers:
                    Host: cdn.example.com
        """.trimIndent()
        val node = ConfigParser.parse(yaml).single()
        assertEquals(Protocol.VMESS, node.protocol)
        val raw = JSONObject(node.rawConfig!!)
        assertEquals("auto", raw.getString("security"))
        assertEquals("cdn.example.com", raw.getJSONObject("tls").getString("server_name"))
        assertEquals("ws", raw.getJSONObject("transport").getString("type"))
        assertEquals("/ws", raw.getJSONObject("transport").getString("path"))
    }

    @Test
    fun parsesMihomoVmessH2AndSkipsTcpWithAnHttpHeader() {
        val h2 = """
            proxies:
              - name: VM
                type: vmess
                server: example.com
                port: 443
                uuid: 00000000-0000-0000-0000-000000000001
                cipher: auto
                tls: true
                network: h2
                h2-opts:
                  host:
                    - a.example.com
                  path: /h2
        """.trimIndent()
        val transport = JSONObject(ConfigParser.parse(h2).single().rawConfig!!).getJSONObject("transport")
        assertEquals("http", transport.getString("type"))
        assertEquals("a.example.com", transport.getJSONArray("host").getString(0))
        val tcpHttp = """
            proxies:
              - name: VM
                type: vmess
                server: example.com
                port: 443
                uuid: 00000000-0000-0000-0000-000000000001
                cipher: auto
                network: http
                http-opts:
                  path:
                    - /
        """.trimIndent()
        assertEquals(0, ConfigParser.parse(tcpHttp).size)
    }

    @Test
    fun parsesSingBoxVmess() {
        val json = JSONObject().put("type", "vmess").put("server", "example.com").put("server_port", 443)
            .put("uuid", "00000000-0000-0000-0000-000000000001").put("security", "aes-128-gcm").put("alter_id", 0)
            .put("tls", JSONObject().put("enabled", true).put("server_name", "example.com"))
            .put("transport", JSONObject().put("type", "grpc").put("service_name", "svc"))
        val raw = JSONObject(ConfigParser.parse(json.toString()).single().rawConfig!!)
        assertEquals("aes-128-gcm", raw.getString("security"))
        assertEquals("svc", raw.getJSONObject("transport").getString("service_name"))
        assertTrue(raw.getJSONObject("tls").getBoolean("enabled"))
    }

    @Test
    fun parsesMihomoTuic() {
        val yaml = """
            proxies:
              - name: T
                type: tuic
                server: example.com
                port: 443
                uuid: 00000000-0000-0000-0000-000000000001
                password: pass
                alpn: [h3]
                congestion-controller: bbr
                udp-relay-mode: native
                reduce-rtt: true
                sni: sni.example.com
                skip-cert-verify: true
        """.trimIndent()
        val node = ConfigParser.parse(yaml).single()
        assertEquals(Protocol.TUIC, node.protocol)
        val raw = JSONObject(node.rawConfig!!)
        assertEquals("bbr", raw.getString("congestion_control"))
        assertEquals("native", raw.getString("udp_relay_mode"))
        assertTrue(raw.getBoolean("zero_rtt_handshake"))
        val tls = raw.getJSONObject("tls")
        assertEquals("sni.example.com", tls.getString("server_name"))
        assertTrue(tls.getBoolean("insecure"))
        assertEquals("h3", tls.getJSONArray("alpn").getString(0))
        assertFalse(tls.has("utls"))
    }

    @Test
    fun skipsTuicV4AndKeepsSingBoxTuic() {
        val v4 = """
            proxies:
              - name: T4
                type: tuic
                server: example.com
                port: 443
                token: secret
        """.trimIndent()
        assertEquals(0, ConfigParser.parse(v4).size)
        val json = JSONObject().put("type", "tuic").put("server", "example.com").put("server_port", 443)
            .put("uuid", "00000000-0000-0000-0000-000000000001").put("password", "pass")
            .put("congestion_control", "cubic")
            .put("tls", JSONObject().put("enabled", true).put("server_name", "example.com").put("alpn", JSONArray().put("h3")))
        val raw = JSONObject(ConfigParser.parse(json.toString()).single().rawConfig!!)
        assertEquals("cubic", raw.getString("congestion_control"))
        assertEquals("h3", raw.getJSONObject("tls").getJSONArray("alpn").getString(0))
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

    @Test
    fun parsesBase64EncodedSubscriptionWithUnEncodedRemarks() {
        // Reproduces a real subscription that came back with 0 nodes (SubscriptionRepository's
        // "Subscription returned no supported nodes"): the response body is the whole vless://
        // list, base64-encoded, and each line's remark (flag emoji + country name) isn't
        // percent-encoded - which used to make java.net.URI throw for every single line (see
        // SubscriptionParserTest#parsesUriWithUnEncodedEmojiAndSpacesInRemark). This goes through
        // ConfigParser.parse(), the actual entry point SubscriptionRepository.refresh() calls, to
        // cover decodeBase64IfNeeded's own sanity check as well as the URI fix.
        val list = listOf(
            "vless://00000000-0000-0000-0000-000000000001@edge1.example.com:443?security=tls&sni=cdn.example.com#🇹🇷 Turkey",
            "vless://00000000-0000-0000-0000-000000000002@edge2.example.com:443?security=tls&sni=cdn.example.com#🇸🇪 Sweden"
        ).joinToString("\n")
        val body = java.util.Base64.getEncoder().encodeToString(list.toByteArray(Charsets.UTF_8))
        val nodes = ConfigParser.parse(body)
        assertEquals(2, nodes.size)
        assertEquals("🇹🇷 Turkey", nodes[0].name)
        assertEquals("🇸🇪 Sweden", nodes[1].name)
    }
}