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
        // xtls-rprx-vision is only valid on bare TCP, so a Reality/Vision URI
        // must not also carry a transport (ws/grpc/xhttp/...); that combination
        // is intentionally sanitized away elsewhere (see
        // SingBoxConfigBuilderTest#removesVisionWhenNodeCarriesTransport).
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443?security=reality&sni=cdn.example.com&fp=chrome&pbk=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA&sid=abcd&flow=xtls-rprx-vision#Reality"
        val node = SubscriptionParser.parse(uri).single()
        assertEquals(Protocol.VLESS, node.protocol)
        assertEquals("Reality", node.name)
        val raw = JSONObject(node.rawConfig!!)
        assertEquals("xtls-rprx-vision", raw.getString("flow"))
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
    fun vlessWithoutSecurityHasNoTlsEvenOn443() {
        // No `security` means no TLS for VLESS (the XTLS share-link standard, v2rayN).
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443"
        val raw = JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!)
        assertFalse(raw.has("tls"))
    }

    @Test
    fun vlessWithoutSecurityIgnoresPortAndSni() {
        // The two such links in Relay's sample: TLS used to come on because of the port,
        // and because of an sni on Cloudflare's plain-HTTP port 2052.
        val grpc = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:2085?type=grpc&serviceName=s#a"
        val ws = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:2052?type=ws&path=%2F&sni=cdn.example.com#b"
        for (uri in listOf(grpc, ws)) {
            assertFalse(JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!).has("tls"), uri)
        }
    }

    @Test
    fun vlessRealityKeyAloneStillMeansReality() {
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443?sni=cdn.example.com&pbk=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA#r"
        val tls = JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!).getJSONObject("tls")
        assertTrue(tls.getJSONObject("reality").getBoolean("enabled"))
    }

    @Test
    fun trojanStillDefaultsToTls() {
        val raw = JSONObject(SubscriptionParser.parse("trojan://secret@example.com:443#t").single().rawConfig!!)
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
    fun decodesMultiplyEncodedWebsocketPath() {
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443?security=tls&type=ws&path=%2F%252Fassignment"
        val raw = JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!)
        assertEquals("//assignment", raw.getJSONObject("transport").getString("path"))
    }

    @Test
    fun decodesMultiplyEncodedAlpn() {
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443?security=tls&alpn=http%252F1.1%2Ch2"
        val tls = JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!).getJSONObject("tls")
        val alpn = tls.getJSONArray("alpn")
        assertEquals("http/1.1", alpn.getString(0))
        assertEquals("h2", alpn.getString(1))
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
    fun skipsASingleLineOverThePerLineLimit() {
        val huge = "vless://" + "a".repeat(64 * 1024)
        assertEquals(0, SubscriptionParser.parse(huge).size)
    }

    @Test
    fun parsesAListLongerThan64KiB() {
        // Used to come back empty: the whole list was rejected past 64 KiB of text,
        // which is only ~190 links in Relay's export format.
        val lines = (1..300).map { i ->
            "vless://00000000-0000-0000-0000-${i.toString().padStart(12, '0')}@edge$i.example.com:443" +
                "?security=tls&sni=cdn.example.com&type=ws&path=%2F${"p".repeat(200)}#node-$i"
        }
        val list = lines.joinToString("\n")
        assertTrue(list.length > 64 * 1024)
        val parsed = SubscriptionParser.parseWithCount(list)
        assertEquals(300, parsed.nodes.size)
        assertEquals(0, parsed.skipped)
    }

    @Test
    fun keepsTheFirstNodesOfAnOverlongListAndCountsTheRest() {
        val extra = 7
        val list = (1..SubscriptionParser.MAX_NODES_PER_LIST + extra).joinToString("\n") { i ->
            "trojan://secret$i@host$i.example.com:443#n$i"
        }
        val parsed = SubscriptionParser.parseWithCount(list)
        assertEquals(SubscriptionParser.MAX_NODES_PER_LIST, parsed.nodes.size)
        assertEquals("n1", parsed.nodes.first().name)
        assertEquals(extra, parsed.skipped)
    }

    @Test
    fun configParserPassesTheCutCountThroughForBase64Lists() {
        val list = (1..SubscriptionParser.MAX_NODES_PER_LIST + 3).joinToString("\n") { i ->
            "trojan://secret$i@host$i.example.com:443#n$i"
        }
        val body = java.util.Base64.getEncoder().encodeToString(list.toByteArray())
        val parsed = ConfigParser.parseWithCount(body)
        assertEquals(SubscriptionParser.MAX_NODES_PER_LIST, parsed.nodes.size)
        assertEquals(3, parsed.skipped)
    }
    @Test
    fun invalidVlessFingerprintFallsBackToRandom() {
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443?security=tls&fp=not-a-real-fingerprint"
        val raw = JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!)
        val utls = raw.getJSONObject("tls").getJSONObject("utls")
        assertEquals("random", utls.getString("fingerprint"))
    }
    @Test
    fun visionFlowIsDroppedWhenTlsIsExplicitlyDisabled() {
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443?security=none&flow=xtls-rprx-vision"
        val raw = JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!)
        assertFalse(raw.has("flow"))
        assertFalse(raw.has("tls"))
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

    @Test
    fun parsesUriWithUnEncodedEmojiAndSpacesInRemark() {
        // java.net.URI's constructor is strict (RFC 3986) and throws URISyntaxException on a
        // raw, un-percent-encoded space or emoji anywhere in the string it's given - including
        // the fragment. Free subscription generators routinely emit remarks like this without
        // encoding them. Before the fix, feeding the WHOLE line (remark included) to URI() meant
        // a node like this failed to parse at all (silently, via parse()'s runCatching), and a
        // subscription made up entirely of such lines came back with zero nodes even though the
        // config values themselves were perfectly valid - this reproduces that real-world case.
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443?security=tls&sni=cdn.example.com#🇹🇷 Turkey - Fast"
        val node = SubscriptionParser.parse(uri).single()
        assertEquals(Protocol.VLESS, node.protocol)
        assertEquals("🇹🇷 Turkey - Fast", node.name)
    }

    @Test
    fun parsesLinkWithJsonInAQueryValue() {
        // Cloudflare Workers nodes carry fm={"tcp":...}: java.net.URI rejected the raw
        // braces and quotes, and the node silently disappeared from the list.
        val uri = """vless://00000000-0000-0000-0000-000000000000@example.com:443?type=ws&security=tls&sni=example.com&path=%2F&fm={"tcp":1}#t"""
        val node = SubscriptionParser.parse(uri).single()
        assertEquals("t", node.name)
        assertEquals("/", JSONObject(node.rawConfig!!).getJSONObject("transport").getString("path"))
    }

    @Test
    fun parsesLinkWithALonePercentSign() {
        val uri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?type=ws&security=tls&sni=example.com&path=%2F&host=%#t"
        val node = SubscriptionParser.parse(uri).single()
        val headers = JSONObject(node.rawConfig!!).getJSONObject("transport").getJSONObject("headers")
        assertEquals("%", headers.getString("Host"))
    }

    @Test
    fun escapedCharactersReachTheConfigUnchanged() {
        val uri = "trojan://p{a}ss|w^rd@example.com:443?security=tls&sni=example.com&type=ws&path=/a b\"c#t"
        val node = SubscriptionParser.parse(uri).single()
        assertEquals("p{a}ss|w^rd", node.password)
        assertEquals("/a b\"c", JSONObject(node.rawConfig!!).getJSONObject("transport").getString("path"))
    }

    @Test
    fun ipv6HostStillParses() {
        val uri = "vless://00000000-0000-0000-0000-000000000000@[2001:db8::1]:8443?security=tls&sni=example.com#v6"
        assertEquals(8443, SubscriptionParser.parse(uri).single().port)
    }

    @Test
    fun dropsXhttpValuesTheCoreWouldRefuse() {
        // sing-box-lx fails the WHOLE config over either value ("unknown mode: bogus",
        // strconv.Atoi: parsing "abc") - every server tested alongside this node, too.
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443?security=tls&sni=edge.example.com&type=xhttp&path=%2Fx&mode=bogus&xPaddingBytes=abc"
        val transport = JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!).getJSONObject("transport")
        assertEquals("xhttp", transport.getString("type"))
        assertFalse(transport.has("mode"))
        assertFalse(transport.has("x_padding_bytes"))
    }

    @Test
    fun keepsValidXhttpModeAndPadding() {
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443?security=tls&sni=edge.example.com&type=xhttp&path=%2Fx&mode=stream-one&xPaddingBytes=100-1000"
        val transport = JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!).getJSONObject("transport")
        assertEquals("stream-one", transport.getString("mode"))
        assertEquals("100-1000", transport.getString("x_padding_bytes"))
    }

    @Test
    fun carriesVlessEncryption() {
        // The two shapes seen in real lists: an X25519 key (43 chars) with "native", and
        // an ML-KEM-768 key (1579 chars) with "xorpub" - both with Vision.
        assertEquals(1579, base64Url(1184).length)
        val x25519 = "mlkem768x25519plus.native.0rtt.${base64Url(32)}"
        val mlkem = "mlkem768x25519plus.xorpub.0rtt.${base64Url(1184)}"
        for (encryption in listOf(x25519, mlkem)) {
            val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443?security=tls&sni=edge.example.com&flow=xtls-rprx-vision&encryption=$encryption#e"
            val raw = JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!)
            assertEquals(encryption, raw.getString("encryption"))
            assertEquals("xtls-rprx-vision", raw.getString("flow"))
        }
    }

    @Test
    fun skipsVlessLinkWithAnEncryptionStringTheCoreCannotParse() {
        val key = base64Url(32)
        val bad = listOf(
            "mlkem768x25519plus.native.0rtt",
            "mlkem768x25519plus.native.2rtt.$key",
            "mlkem768x25519plus.plain.0rtt.$key",
            "x25519.native.0rtt.$key",
            "mlkem768x25519plus.native..0rtt.$key",
            "mlkem768x25519plus.native.0rtt.${base64Url(31)}"
        )
        for (encryption in bad) {
            val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443?security=tls&encryption=$encryption#e"
            assertEquals(0, SubscriptionParser.parse(uri).size, encryption)
        }
    }

    @Test
    fun acceptsXrayPaddingSegmentsBeforeTheKey() {
        val encryption = "mlkem768x25519plus.random.1rtt.100-111-1111.75-0-111.${base64Url(32)}"
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443?security=tls&encryption=$encryption#e"
        assertEquals(encryption, JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!).getString("encryption"))
    }

    @Test
    fun encryptionNoneIsNotCarried() {
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443?security=tls&encryption=none#e"
        assertFalse(JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!).has("encryption"))
    }

    // ---- Shadowsocks ----

    @Test
    fun parsesSip002ShadowsocksWithBase64Userinfo() {
        val userInfo = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("aes-256-gcm:pass".toByteArray())
        val node = SubscriptionParser.parse("ss://$userInfo@1.2.3.4:8388#SS").single()
        assertEquals(Protocol.SHADOWSOCKS, node.protocol)
        assertEquals("SS", node.name)
        assertEquals("1.2.3.4", node.server)
        assertEquals(8388, node.port)
        val raw = JSONObject(node.rawConfig!!)
        assertEquals("shadowsocks", raw.getString("type"))
        assertEquals("aes-256-gcm", raw.getString("method"))
        assertEquals("pass", raw.getString("password"))
        assertFalse(raw.has("plugin"))
    }

    @Test
    fun parsesShadowsocksUserinfoInStandardBase64WithSlashAndPlus() {
        // Plain base64 has '/' and '+', which java.net.URI would read as a path and a space.
        val userInfo = java.util.Base64.getEncoder().encodeToString("chacha20-ietf-poly1305:~~~???".toByteArray())
        assertTrue('/' in userInfo && '+' in userInfo)
        val raw = JSONObject(SubscriptionParser.parse("ss://$userInfo@example.com:443#x").single().rawConfig!!)
        assertEquals("chacha20-ietf-poly1305", raw.getString("method"))
        assertEquals("~~~???", raw.getString("password"))
    }

    @Test
    fun parsesPlainSip002Shadowsocks2022OnIpv6() {
        val key = java.util.Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val encodedKey = java.net.URLEncoder.encode(key, "UTF-8")
        val node = SubscriptionParser.parse("ss://2022-blake3-aes-128-gcm:$encodedKey@[2001:db8::1]:443#v6").single()
        assertEquals("2001:db8::1", node.server)
        val raw = JSONObject(node.rawConfig!!)
        assertEquals("2022-blake3-aes-128-gcm", raw.getString("method"))
        assertEquals(key, raw.getString("password"))
    }

    @Test
    fun parsesLegacyAllBase64Shadowsocks() {
        val body = java.util.Base64.getEncoder().encodeToString("aes-128-gcm:pa@ss@example.com:8388".toByteArray())
        val node = SubscriptionParser.parse("ss://$body#old").single()
        assertEquals("example.com", node.server)
        assertEquals(8388, node.port)
        assertEquals("pa@ss", JSONObject(node.rawConfig!!).getString("password"))
        assertEquals("old", node.name)
    }

    @Test
    fun parsesShadowsocksPluginsTheCoreHas() {
        val userInfo = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("aes-128-gcm:pass".toByteArray())
        val obfs = JSONObject(SubscriptionParser.parse(
            "ss://$userInfo@example.com:8388/?plugin=simple-obfs%3Bobfs%3Dhttp%3Bobfs-host%3Dwww.bing.com#o"
        ).single().rawConfig!!)
        assertEquals("obfs-local", obfs.getString("plugin"))
        assertEquals("obfs=http;obfs-host=www.bing.com", obfs.getString("plugin_opts"))
        val v2ray = JSONObject(SubscriptionParser.parse(
            "ss://$userInfo@example.com:8388?plugin=v2ray-plugin%3Bmode%3Dwebsocket%3Bhost%3Dcdn.example.com%3Btls#v"
        ).single().rawConfig!!)
        assertEquals("v2ray-plugin", v2ray.getString("plugin"))
        assertEquals("mode=websocket;host=cdn.example.com;tls", v2ray.getString("plugin_opts"))
    }

    @Test
    fun skipsShadowsocksLinksTheCoreCouldNotRun() {
        fun ss(credentials: String, query: String = "") =
            "ss://" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(credentials.toByteArray()) + "@example.com:8388$query#x"
        val shortKey = java.util.Base64.getEncoder().encodeToString(ByteArray(8))
        val bad = listOf(
            ss("aes-128-gcmx:pass"),
            ss("aes-256-gcm:"),
            ss("2022-blake3-aes-256-gcm:$shortKey"),
            ss("aes-128-gcm:pass", "?plugin=kcptun%3Bmode%3Dfast"),
            ss("aes-128-gcm:pass", "?plugin=obfs-local%3Bobfs%3Dwebsocket"),
            ss("aes-128-gcm:pass", "?plugin=v2ray-plugin%3Bmode%3Dquic")
        )
        for (link in bad) assertEquals(0, SubscriptionParser.parse(link).size, link)
    }

    @Test
    fun acceptsShadowsocksMethodAliasAndCase() {
        val userInfo = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("CHACHA20-POLY1305:pass".toByteArray())
        val raw = JSONObject(SubscriptionParser.parse("ss://$userInfo@example.com:8388#x").single().rawConfig!!)
        assertEquals("chacha20-ietf-poly1305", raw.getString("method"))
    }

    // ---- VMess ----

    private fun vmess(json: JSONObject, urlSafe: Boolean = false): String =
        "vmess://" + (if (urlSafe) java.util.Base64.getUrlEncoder().withoutPadding() else java.util.Base64.getEncoder())
            .encodeToString(json.toString().toByteArray())

    @Test
    fun parsesV2rayNVmessOverWebsocketAndTls() {
        val link = vmess(JSONObject()
            .put("v", "2").put("ps", "vm").put("add", "104.16.1.1").put("port", "443")
            .put("id", "00000000-0000-0000-0000-000000000001").put("aid", "0").put("scy", "auto")
            .put("net", "ws").put("type", "none").put("host", "cdn.example.com").put("path", "/ws")
            .put("tls", "tls").put("sni", ""))
        val node = SubscriptionParser.parse(link).single()
        assertEquals(Protocol.VMESS, node.protocol)
        assertEquals("vm", node.name)
        assertEquals("00000000-0000-0000-0000-000000000001", node.uuid)
        val raw = JSONObject(node.rawConfig!!)
        assertEquals("vmess", raw.getString("type"))
        assertEquals("auto", raw.getString("security"))
        assertEquals(0, raw.getInt("alter_id"))
        // Behind a CDN `add` is an IP of the CDN; the name to present is the host's.
        assertEquals("cdn.example.com", raw.getJSONObject("tls").getString("server_name"))
        val transport = raw.getJSONObject("transport")
        assertEquals("ws", transport.getString("type"))
        assertEquals("/ws", transport.getString("path"))
        assertEquals("cdn.example.com", transport.getJSONObject("headers").getString("Host"))
    }

    @Test
    fun parsesVmessGrpcWithNumbersAndNoTls() {
        val link = vmess(JSONObject()
            .put("add", "example.com").put("port", 8443).put("id", "00000000-0000-0000-0000-000000000002")
            .put("aid", 2).put("scy", "weird").put("net", "grpc").put("path", "svc").put("tls", ""), urlSafe = true)
        val raw = JSONObject(SubscriptionParser.parse(link).single().rawConfig!!)
        assertEquals(8443, raw.getInt("server_port"))
        assertEquals(2, raw.getInt("alter_id"))
        assertEquals("auto", raw.getString("security"))
        assertFalse(raw.has("tls"))
        assertEquals("grpc", raw.getJSONObject("transport").getString("type"))
        assertEquals("svc", raw.getJSONObject("transport").getString("service_name"))
    }

    @Test
    fun parsesVmessHttp2Transport() {
        val link = vmess(JSONObject()
            .put("add", "example.com").put("port", "443").put("id", "00000000-0000-0000-0000-000000000003")
            .put("net", "h2").put("host", "a.example.com, b.example.com").put("path", "/h2").put("tls", "tls"))
        val transport = JSONObject(SubscriptionParser.parse(link).single().rawConfig!!).getJSONObject("transport")
        assertEquals("http", transport.getString("type"))
        assertEquals("/h2", transport.getString("path"))
        assertEquals(2, transport.getJSONArray("host").length())
    }

    @Test
    fun skipsVmessTheCoreCouldNotRun() {
        val base = JSONObject().put("add", "example.com").put("port", "443").put("id", "00000000-0000-0000-0000-000000000004")
        val bad = listOf(
            vmess(JSONObject(base.toString()).put("net", "tcp").put("type", "http")),
            vmess(JSONObject(base.toString()).put("net", "kcp")),
            vmess(JSONObject(base.toString()).put("port", "0")),
            "vmess://not-base64-json"
        )
        for (link in bad) assertEquals(0, SubscriptionParser.parse(link).size, link)
    }

    @Test
    fun aRemarkAfterHashNamesAVmessNode() {
        val link = vmess(JSONObject().put("ps", "inside").put("add", "example.com").put("port", "443")
            .put("id", "00000000-0000-0000-0000-000000000005")) + "#outside"
        assertEquals("outside", SubscriptionParser.parse(link).single().name)
    }

    // ---- TUIC ----

    @Test
    fun parsesTuicV5() {
        val node = SubscriptionParser.parse(
            "tuic://00000000-0000-0000-0000-000000000006:p%40ss@example.com:443?congestion_control=bbr&udp_relay_mode=native&alpn=h3&sni=sni.example.com&allow_insecure=1#t"
        ).single()
        assertEquals(Protocol.TUIC, node.protocol)
        assertEquals("p@ss", node.password)
        val raw = JSONObject(node.rawConfig!!)
        assertEquals("tuic", raw.getString("type"))
        assertEquals("00000000-0000-0000-0000-000000000006", raw.getString("uuid"))
        assertEquals("bbr", raw.getString("congestion_control"))
        assertEquals("native", raw.getString("udp_relay_mode"))
        val tls = raw.getJSONObject("tls")
        assertTrue(tls.getBoolean("enabled"))
        assertEquals("sni.example.com", tls.getString("server_name"))
        assertTrue(tls.getBoolean("insecure"))
        assertEquals("h3", tls.getJSONArray("alpn").getString(0))
    }

    @Test
    fun tuicDefaultsAlpnDropsUnknownValuesAndNeverUsesUtls() {
        val raw = JSONObject(SubscriptionParser.parse(
            "tuic://00000000-0000-0000-0000-000000000007:pass@example.com:443?congestion_control=hybla&udp_relay_mode=foo&fp=chrome#t"
        ).single().rawConfig!!)
        assertFalse(raw.has("congestion_control"))
        assertFalse(raw.has("udp_relay_mode"))
        val tls = raw.getJSONObject("tls")
        assertEquals("h3", tls.getJSONArray("alpn").getString(0))
        assertFalse(tls.has("utls"))
        assertEquals("new_reno", JSONObject(SubscriptionParser.parse(
            "tuic://00000000-0000-0000-0000-000000000007:pass@example.com:443?congestion_control=new-reno#t"
        ).single().rawConfig!!).getString("congestion_control"))
    }

    @Test
    fun skipsTuicWithoutAPassword() {
        assertEquals(0, SubscriptionParser.parse("tuic://00000000-0000-0000-0000-000000000008@example.com:443#t").size)
    }

    @Test
    fun vlessHttpTransportIsBuiltNow() {
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443?security=tls&sni=edge.example.com&type=http&host=edge.example.com&path=%2Fh2#h"
        val transport = JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!).getJSONObject("transport")
        assertEquals("http", transport.getString("type"))
        assertEquals("edge.example.com", transport.getJSONArray("host").getString(0))
    }

    private fun base64Url(size: Int): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(size) { (it * 7).toByte() })

    @Test
    fun readsXhttpExtra() {
        val uri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?type=xhttp&security=tls&sni=example.com&path=%2Fx&mode=auto&extra=%7B%22xmux%22%3A%7B%22maxConcurrency%22%3A%2216-32%22%7D%2C%22noGRPCHeader%22%3Atrue%7D#t"
        val transport = JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!).getJSONObject("transport")
        assertEquals("16-32", transport.getJSONObject("xmux").getString("max_concurrency"))
        assertTrue(transport.getBoolean("no_grpc_header"))
        assertEquals("/x", transport.getString("path"))
        assertEquals("auto", transport.getString("mode"))
    }

    @Test
    fun readsXhttpExtraWhoseSpacesArriveAsPlus() {
        // 2 of the 3 real links with xPaddingBytes in `extra` form-encode its spaces.
        val uri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?type=xhttp&security=tls&sni=example.com&path=%2Fx" +
            "&extra=%7B%22xPaddingBytes%22%3A+%22100-1000%22%2C+%22noGRPCHeader%22%3A+true%2C+%22xmux%22%3A+%7B%22hKeepAlivePeriod%22%3A+0%7D%7D#t"
        val transport = JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!).getJSONObject("transport")
        assertEquals("100-1000", transport.getString("x_padding_bytes"))
        assertTrue(transport.getBoolean("no_grpc_header"))
        assertEquals(0L, transport.getJSONObject("xmux").getLong("h_keep_alive_period"))
    }

    @Test
    fun aLoneFormEncodedBooleanInExtraIsNotMisread() {
        // org.json alone would accept {"noGRPCHeader":+true} with the string "+true" in it.
        val uri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?type=xhttp&security=tls&sni=example.com&extra=%7B%22noGRPCHeader%22%3A+true%7D#t"
        assertTrue(JSONObject(SubscriptionParser.parse(uri).single().rawConfig!!).getJSONObject("transport").getBoolean("no_grpc_header"))
    }

    @Test
    fun topLevelXhttpSettingsWinOverExtraAndNullExtraIsIgnored() {
        val withBoth = "vless://00000000-0000-0000-0000-000000000000@example.com:443?type=xhttp&security=tls&sni=example.com&xPaddingBytes=200-300&extra=%7B%22xPaddingBytes%22%3A%22100-1000%22%7D#t"
        assertEquals("200-300", JSONObject(SubscriptionParser.parse(withBoth).single().rawConfig!!).getJSONObject("transport").getString("x_padding_bytes"))
        val nullExtra = "vless://00000000-0000-0000-0000-000000000000@example.com:443?type=xhttp&security=tls&sni=example.com&path=%2Fx&extra=null#t"
        val transport = JSONObject(SubscriptionParser.parse(nullExtra).single().rawConfig!!).getJSONObject("transport")
        assertFalse(transport.has("xmux"))
        assertEquals("/x", transport.getString("path"))
    }

    @Test
    fun fallsBackToHostPortNameWhenRemarkIsBlank() {
        val uri = "vless://00000000-0000-0000-0000-000000000001@edge.example.com:443"
        val node = SubscriptionParser.parse(uri).single()
        assertEquals("edge.example.com:443", node.name)
    }
}
