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

    @Test fun awgNeverRacesItsOwnDnsAndIsolatesItsCacheFromOtherProtocols() {
        // Same regression guard, several rounds in - each earlier version
        // locked in an approach that on-device logs (or, twice, adversarial
        // review before it reached the device again) proved incomplete, so
        // all of them are worth naming here instead of just quietly
        // replacing them:
        //
        // v1 asserted a "resolve" route rule existed (server=dns-local,
        // strategy=prefer_ipv4, no disable_cache). That fixed the original
        // bug - AmneziaWG/WARP (a WireGuard endpoint, which routes by real IP
        // only) got fed a fakeip address and sing-box refused UDP outright:
        // "a resolve action is required before routing to outbound/
        // wireguard[awg]". But a fresh log showed that resolve rule still
        // handed fakeip addresses to the endpoint for the exact domains
        // Instagram/YouTube hammer with many parallel connections at once
        // (scontent-*.cdninstagram.com, i.instagram.com, youtubei.
        // googleapis.com, redirector.googlevideo.com) - a race between this
        // resolve query and the app's own hijacked query for the same domain
        // (both answered by dns-fakeip, back when buildDns() still routed
        // endpointMode's own A/AAAA queries there), decided by whichever
        // server answered faster (fakeip, ~0ms, almost always).
        //
        // v2 added disable_cache to that resolve rule, on the theory that
        // removing it from the shared cache slot would remove the race. A
        // third log showed the opposite: the fakeip rate on those same
        // domains went from 28% to 95%, because disable_cache also removed
        // the accidental mitigation v1 had been relying on - once some
        // connection won the race for a domain, the real answer got cached
        // and reused by every other parallel connection to the same host.
        // Without that, every connection had to win its own independent race
        // against a burst of competing fakeip-bound queries, and almost never
        // did.
        //
        // v3 (never shipped - caught by review) removed the resolve rule
        // entirely and made buildDns() stop *declaring* a fakeip server for
        // endpointMode, on the reasoning that nothing routes to it, so why
        // declare it. Two problems, stacked:
        //  1. This app's experimental.cache_file has no cache_id and is
        //     shared by every protocol on the device, so a VLESS/Trojan/
        //     Hysteria2 session (store_fakeip is still true for those - see
        //     fakeipMappingsSurviveAReconnect) can persist a fakeip mapping
        //     for a domain, and a requesting app's own DNS/QUIC-layer cache
        //     can independently hand out a fakeip address it learned in a
        //     previous session - either way a fakeip destination can reach
        //     the tun for an endpointMode connection this session's own DNS
        //     never produced.
        //  2. Whether "resolve" can even recognize such an address as fake
        //     at all, with no fakeip server in the session's dns.servers, is
        //     unverified (sing-box-lx isn't vendored here to check) - so v3
        //     risked silently reopening the original bug for exactly the
        //     traffic this whole fix is about, with nothing to show for it
        //     in an "info"-level log.
        //
        // v4 (shipped, tested) kept what v3 got right - endpointMode's own
        // A/AAAA queries never route to fakeip (asserted below: dns.rules has
        // no A/AAAA->fakeip rule), so there is exactly one query per domain
        // and nothing left to race - and answered v3's open question by
        // restoring the fakeip server declaration (matching the shape already
        // proven on-device in v1/v2 to make "resolve" work) plus a CIDR-match
        // reject backstop after it. Shipped without a cache_id, though -
        // still one shared cache_file bucket for every protocol - and a log
        // of a live VLESS -> AmneziaWG switch (no disconnect) showed why that
        // still mattered: "dns: exchanged A g.whatsapp.net. 60 IN A
        // 198.18.0.25" - a fakeip answer for a query that this session's own
        // dns.rules never route to fakeip at all, served straight out of
        // VLESS's still-warm cache, bypassing dns.rules entirely because nothing
        // told the two sessions apart. Every connection that then tried to use
        // that address died immediately with "missing fakeip record, try
        // enable experimental.cache_file" - a check that happens *before*
        // route.rules runs, so neither the resolve rule nor the CIDR backstop
        // ever got a chance at any of them (340 of 1196 connections in that
        // log, the same handful of addresses over and over, never
        // recovering). The v4 code had also set store_fakeip to
        // `!endpointMode`, which likely starved that same reverse-map lookup
        // for this session's own legitimate use of it too, on top of the
        // cross-protocol leak.
        //
        // This version fixes both with the field sing-box documents for
        // exactly this - cache_id, "enable[s] separate storage for different
        // configurations sharing the same file path" - so endpointMode gets
        // its own bucket a vless/trojan/hysteria2 session can never write to
        // or read from, and vice versa. With that isolation in place there is
        // no contamination risk left to justify store_fakeip being off, so it
        // is unconditionally true again, matching every other protocol.
        val raw = JSONObject().put("type","wireguard").put("address", JSONArray().put("10.0.0.2/32"))
            .put("private_key","base64-private")
            .put("peers", JSONArray().put(JSONObject().put("address","203.0.113.10").put("port",51820)
                .put("public_key","base64-public").put("allowed_ips", JSONArray().put("0.0.0.0/0"))))
            .toString()
        val config = JSONObject(SingBoxConfigBuilder.build(Node(
            name = "awg", protocol = Protocol.AMNEZIAWG, server = "203.0.113.10", port = 51820,
            privateKey = "base64-private", rawConfig = raw
        )))

        val routeRules = (0 until config.getJSONObject("route").getJSONArray("rules").length())
            .map { config.getJSONObject("route").getJSONArray("rules").getJSONObject(it) }

        val resolveRule = requireNotNull(
            routeRules.firstOrNull { it.optString("action") == "resolve" }
        ) { "endpointMode must keep a resolve rule as first-attempt fixup for stale/foreign fakeip destinations" }
        assertEquals("dns-local", resolveRule.getString("server"))
        assertEquals("prefer_ipv4", resolveRule.getString("strategy"))
        // Not v2's disable_cache: with the fakeip server never routed to for
        // endpointMode (asserted below), there's no live fakeip answer in
        // this session to race against, so caching this resolve's real
        // answer is safe again (and helps the next connection to the same
        // domain).
        assertFalse(resolveRule.has("disable_cache") && resolveRule.getBoolean("disable_cache"))

        val rejectRule = requireNotNull(
            routeRules.firstOrNull { it.optString("action") == "reject" && it.has("ip_cidr") }
        ) { "endpointMode must keep a CIDR backstop for any fakeip destination resolve doesn't catch" }
        val rejectedCidrs = (0 until rejectRule.getJSONArray("ip_cidr").length())
            .map { rejectRule.getJSONArray("ip_cidr").getString(it) }
            .toSet()
        assertEquals(setOf("198.18.0.0/15", "fc00::/18"), rejectedCidrs)
        // The backstop must come after resolve, so a successfully fixed-up
        // destination never reaches it.
        assertTrue(routeRules.indexOf(resolveRule) < routeRules.indexOf(rejectRule))

        val dns = config.getJSONObject("dns")
        // The fakeip server is declared for endpointMode too now (see
        // buildDns()'s comment for why: resolve's ability to recognize a
        // fakeip address at all may depend on it) - what actually prevents
        // the v1/v2 race is that nothing routes a query to it, checked next.
        assertTrue(
            dns.getJSONArray("servers").toString().contains("fakeip"),
            "endpointMode must still declare a fakeip DNS server, for resolve's sake"
        )
        val dnsRules = dns.getJSONArray("rules")
        assertFalse(
            (0 until dnsRules.length()).any { dnsRules.getJSONObject(it).optString("server") == "dns-fakeip" },
            "endpointMode's dns.rules must not route anything to fakeip - this is what removes the race"
        )
        // A/AAAA now falls through to "final" = dns-local, same as every
        // other query type this config doesn't special-case.
        assertEquals("dns-local", dns.getString("final"))
        // The HTTPS/SVCB leak-avoidance reject is unrelated to fakeip and
        // must survive untouched for endpointMode too.
        val httpsRule = (0 until dnsRules.length()).map { dnsRules.getJSONObject(it) }
            .first { it.optJSONArray("query_type")?.toString()?.contains("HTTPS") == true }
        assertEquals("reject", httpsRule.getString("action"))

        // cache_id is the fix for a real on-device failure: a live VLESS ->
        // AmneziaWG switch (no disconnect) fed this session a DNS answer
        // straight out of VLESS's still-warm cache ("g.whatsapp.net -> fakeip
        // 198.18.0.25"), bypassing dns.rules entirely, because both sessions
        // shared one cache_file store with no cache_id to tell them apart.
        // endpointMode gets its own id so it can never read back an entry a
        // vless/trojan/hysteria2 session wrote (or vice versa). store_fakeip
        // is unconditionally true again too - "missing fakeip record" (the
        // same log) is exactly the reverse-map lookup the resolve rule above
        // depends on, and there is no contamination risk left to justify
        // leaving storage off now that the two protocols can't share a bucket.
        val cache = config.getJSONObject("experimental").getJSONObject("cache_file")
        assertTrue(cache.getBoolean("enabled"))
        assertEquals("endpoint", cache.getString("cache_id"))
        assertTrue(cache.getBoolean("store_fakeip"))

        // vless/trojan/hysteria2 are untouched: no resolve rule, no CIDR
        // backstop, still get fakeip+sniff exactly as before endpointMode
        // existed at all.
        val vlessConfig = JSONObject(SingBoxConfigBuilder.build(Node(
            name = "v", protocol = Protocol.VLESS, server = "example.com", port = 443,
            uuid = "00000000-0000-0000-0000-000000000001"
        )))
        val vlessRules = vlessConfig.getJSONObject("route").getJSONArray("rules")
        assertFalse((0 until vlessRules.length()).any { vlessRules.getJSONObject(it).optString("action") == "resolve" })
        assertFalse((0 until vlessRules.length()).any { vlessRules.getJSONObject(it).has("ip_cidr") })
        assertTrue(vlessConfig.getJSONObject("dns").getJSONArray("servers").toString().contains("fakeip"))
        // Different cache_id than the AWG config above - the whole point is
        // that these two never resolve to the same cache_file bucket.
        assertEquals(
            "default",
            vlessConfig.getJSONObject("experimental").getJSONObject("cache_file").getString("cache_id")
        )
    }

    @Test fun invalidPortIsRejectedBeforeCore() {
        assertFailsWith<IllegalArgumentException> { SingBoxConfigBuilder.build(Node(name="test", protocol=Protocol.VLESS, server="example.com", port=70000, uuid="00000000-0000-0000-0000-000000000003")) }
    }

    @Test fun httpsQueriesAreRefusedSoTheyNeverLeaveTheDevice() {
        val config = JSONObject(SingBoxConfigBuilder.build(Node(name="test", protocol=Protocol.VLESS, server="example.com", port=443, uuid="00000000-0000-0000-0000-000000000001")))
        val dns = config.getJSONObject("dns")
        val rules = (0 until dns.getJSONArray("rules").length()).map { dns.getJSONArray("rules").getJSONObject(it) }

        // Refused, not forwarded: a forwarded query stalls for the full DNS
        // timeout whenever the upstream isn't reachable through the proxy in
        // use, which showed up as pages hanging. Refusing makes clients fall
        // straight back to A/AAAA, which fakeip answers on-device.
        val httpsRule = rules.first { it.optJSONArray("query_type")?.toString()?.contains("HTTPS") == true }
        assertEquals("reject", httpsRule.getString("action"))

        // A/AAAA must stay on fakeip - answered on-device, never leaks.
        val addressRule = rules.first { it.getJSONArray("query_type").toString().contains("\"A\"") }
        assertEquals("dns-fakeip", addressRule.getString("server"))

        // No upstream resolver reachable only through the tunnel: nothing left
        // that can stall when the proxy is down.
        assertFalse(dns.getJSONArray("servers").toString().contains("dns-remote"))
    }

    @Test fun fakeipMappingsSurviveAReconnect() {
        // Regression guard for "connected, but nothing loads" right after
        // switching servers: apps still hold DNS answers pointing at 198.18.x.x
        // from the previous session, and with an in-memory-only fakeip table the
        // new engine has no record for them. The core names this itself, once per
        // broken connection - "missing fakeip record, try enable
        // experimental.cache_file" - 21 times in one measured session.
        val config = JSONObject(SingBoxConfigBuilder.build(Node(name="test", protocol=Protocol.VLESS, server="example.com", port=443, uuid="00000000-0000-0000-0000-000000000001")))
        val cache = config.getJSONObject("experimental").getJSONObject("cache_file")
        assertTrue(cache.getBoolean("enabled"))
        assertTrue(cache.getBoolean("store_fakeip"))
        assertTrue(cache.getString("path").isNotBlank())
    }

    @Test fun probeConfigCarriesASentinelThatCannotSucceed() {
        val node = Node(id=1, name="a", protocol=Protocol.VLESS, server="one.example", port=443, uuid="00000000-0000-0000-0000-000000000001")
        val probe = SingBoxConfigBuilder.buildProbeConfig(listOf(node))
        val config = JSONObject(requireNotNull(probe.json))

        val sentinel = (0 until config.getJSONArray("outbounds").length())
            .map { config.getJSONArray("outbounds").getJSONObject(it) }
            .first { it.getString("tag") == probe.sentinelTag }
        // Loopback, port 1: refused instantly, so checking it is nearly free -
        // and if a request through it ever *succeeds*, the probe isn't going
        // through the named outbound at all and no verdict can be trusted.
        assertEquals("127.0.0.1", sentinel.getString("server"))
        assertEquals(1, sentinel.getInt("server_port"))
        assertFalse(probe.tagsByNodeId.values.contains(probe.sentinelTag))
    }

    @Test fun trojanWithoutAFingerprintStillHandshakesAsABrowser() {
        // Regression guard: this used to fall through to Go's own ClientHello,
        // whose JA3/JA4 identifies the connection as non-browser immediately.
        val config = JSONObject(SingBoxConfigBuilder.build(Node(name="t", protocol=Protocol.TROJAN, server="example.com", port=443, password="secret")))
        val utls = config.getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls").getJSONObject("utls")
        assertTrue(utls.getBoolean("enabled"))
        assertEquals("chrome", utls.getString("fingerprint"))
    }

    @Test fun anImportedFingerprintIsKeptRatherThanOverridden() {
        val raw = JSONObject().put("type","vless").put("server","raw.example").put("server_port",443)
            .put("uuid","raw-uuid")
            .put("tls", JSONObject().put("enabled", true).put("server_name","example.com")
                .put("utls", JSONObject().put("enabled", true).put("fingerprint","firefox")))
            .toString()
        val config = JSONObject(SingBoxConfigBuilder.build(Node(name="v", protocol=Protocol.VLESS, server="example.com", port=443, uuid="00000000-0000-0000-0000-000000000009", rawConfig=raw)))
        val utls = config.getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls").getJSONObject("utls")
        assertEquals("firefox", utls.getString("fingerprint"))
    }

    @Test fun hysteria2IsLeftAloneBecauseUtlsDoesNotApplyToQuic() {
        val config = JSONObject(SingBoxConfigBuilder.build(Node(name="h", protocol=Protocol.HYSTERIA2, server="example.com", port=443, password="secret")))
        val tls = config.getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls")
        assertFalse(tls.has("utls"))
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
