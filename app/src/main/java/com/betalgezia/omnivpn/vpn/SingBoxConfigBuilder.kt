package com.betalgezia.omnivpn.vpn

import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Protocol
import org.json.JSONArray
import org.json.JSONObject

/**
 * Result of [SingBoxConfigBuilder.buildProbeConfig]: [json] is the sing-box config to
 * run (null when every node failed to build), [tagsByNodeId] maps each successfully
 * included node's id to its outbound/endpoint tag in that config, and [buildErrors]
 * holds a reason string for every node id that was left out instead.
 */
data class ProbeConfig(
    val json: String?,
    val tagsByNodeId: Map<Long, String>,
    val buildErrors: Map<Long, String>,
    /**
     * Tag of an outbound that cannot possibly work (it dials a closed port on
     * loopback). Probing it is how the caller checks its own instrument: if a
     * request through *this* comes back successful, then the probe never went
     * through the named outbound at all, and every other verdict in the batch is
     * measuring the phone's own connection rather than the server.
     */
    val sentinelTag: String
)

object SingBoxConfigBuilder {
    fun build(node: Node): String {
        val endpointMode = node.protocol == Protocol.AMNEZIAWG
        val proxyTag = if (endpointMode) AWG_TAG else PROXY_TAG
        val route = JSONObject()
            .put("auto_detect_interface", true)
            .put("default_domain_resolver", LOCAL_DNS_TAG)
            .put("final", proxyTag)

        val root = JSONObject()
            // Back to "info" from the "debug" used while chasing the "connects but
            // no traffic passes" symptom. That investigation is done, and debug is
            // not free on a phone: a 4-minute session logged every routing decision
            // and every XtlsPadding/Unpadding block - thousands of lines of per-packet
            // tracing. "info" still carries what diagnosis actually needs (each
            // inbound/outbound connection, DNS exchanges, errors with their cause).
            .put("log", JSONObject().put("level", "info"))
            .put("dns", buildDns())
            .put("route", route.put("rules", JSONArray().apply {
                put(JSONObject()
                    .put("inbound", JSONArray().put(TUN_TAG))
                    .put("action", "sniff"))
                put(JSONObject()
                    .put("inbound", JSONArray().put(TUN_TAG))
                    .put("protocol", "dns")
                    .put("action", "hijack-dns"))
                if (endpointMode) {
                    // A WireGuard/AmneziaWG endpoint (this also covers WARP -
                    // see WarpAccount.toNode, which tags itself AMNEZIAWG)
                    // routes by real destination IP, not by the sniffed
                    // domain the way the vless/trojan/hysteria2 outbounds
                    // above do - it has no notion of "connect to this
                    // hostname". Every connection here started as a fakeip
                    // address (buildDns answers every A/AAAA query from
                    // dns-fakeip), so without this rule the core has a
                    // destination it structurally cannot dial through a
                    // WireGuard peer and, for UDP, refuses outright:
                    // "a resolve action is required before routing to
                    // outbound/wireguard[awg]" - confirmed from an on-device
                    // log where every dropped YouTube/Instagram connection
                    // was exactly this, always UDP/QUIC (TCP mostly slipped
                    // through some other path, which is why only QUIC-heavy
                    // traffic looked broken). "resolve" swaps the fakeip
                    // address for a real one using the domain already
                    // recovered by sniffing, then falls through to the
                    // endpoint below with something it can actually route.
                    // prefer_ipv4 matches buildDns()'s own DNS strategy -
                    // some AmneziaWG/WARP configs carry only an IPv4 tunnel
                    // address, and resolving to an AAAA the endpoint has no
                    // local IPv6 address to send from is its own failure
                    // mode ("missing IPv6 local address", also seen
                    // on-device).
                    put(JSONObject()
                        .put("inbound", JSONArray().put(TUN_TAG))
                        .put("action", "resolve")
                        .put("server", LOCAL_DNS_TAG)
                        .put("strategy", "prefer_ipv4"))
                }
            }))
            .put("inbounds", JSONArray().put(buildTun()))
            .put("outbounds", JSONArray().apply {
                // The proxy outbound (when present) must stay at index 0: callers
                // and tests rely on outbounds[0] being the app-owned proxy tag.
                // "direct"/"block" are appended afterwards so they always exist
                // (e.g. as the AWG/WARP endpoint bootstrap detour) instead of
                // being silently dropped.
                if (!endpointMode) put(buildProxyOutbound(node))
                put(JSONObject().put("type", "direct").put("tag", DIRECT_TAG).put("domain_resolver", LOCAL_DNS_TAG))
                put(JSONObject().put("type", "block").put("tag", BLOCK_TAG))
            })

        // Without this the fakeip table lives only in memory, so every reconnect
        // starts with an empty one while apps still hold DNS answers from the
        // previous session pointing at 198.18.x.x. Those connections then arrive
        // with no domain to recover and die - the core says so itself, once per
        // affected connection: "missing fakeip record, try enable
        // experimental.cache_file" (21 of them in one 4-minute session on-device,
        // right after switching servers). Persisting the table is what makes a
        // reconnect not look like "connected, but nothing loads".
        root.put(
            "experimental",
            JSONObject().put(
                "cache_file",
                JSONObject()
                    .put("enabled", true)
                    .put("path", CACHE_FILE_NAME)
                    .put("store_fakeip", true)
            )
        )

        if (endpointMode) {
            root.put("endpoints", JSONArray().put(buildAmneziaWgEndpoint(node)))
        }
        return root.toString()
    }

    /**
     * A liveness-probe config testing [nodes] as extra, otherwise-unused outbounds/
     * endpoints - no inbounds, no tun, no fakeip. Meant to run through
     * [SingBoxEngine] while nothing else is connected (see NodeHealthChecker) and be
     * queried with CommandClient.urlTestOutbound(tag, url, timeout), which makes an
     * actual HTTP request through the named outbound - unlike a bare TCP connect,
     * this fails for a node whose port is open but whose proxy handshake doesn't
     * actually work (wrong credentials, broken TLS, etc).
     *
     * A node that fails to build its own outbound/endpoint (e.g. missing required
     * field) is reported in [ProbeConfig.buildErrors] and left out of [ProbeConfig.json]
     * instead of aborting the whole batch - matches build()'s per-field validation,
     * just scoped so one bad node doesn't block testing the rest.
     */
    fun buildProbeConfig(nodes: List<Node>): ProbeConfig {
        val tags = mutableMapOf<Long, String>()
        val errors = mutableMapOf<Long, String>()
        val outbounds = JSONArray()
        val endpoints = JSONArray()
        for (node in nodes) {
            if (node.protocol == Protocol.WARP) continue
            val tag = "probe-${node.id}"
            runCatching {
                if (node.protocol == Protocol.AMNEZIAWG) {
                    endpoints.put(buildAmneziaWgEndpoint(node, tag))
                } else {
                    outbounds.put(buildProxyOutbound(node, tag))
                }
            }.onSuccess {
                tags[node.id] = tag
            }.onFailure {
                errors[node.id] = it.message ?: "Invalid configuration"
            }
        }
        if (tags.isEmpty()) return ProbeConfig(null, emptyMap(), errors, SENTINEL_TAG)

        // Control outbound - see ProbeConfig.sentinelTag. A closed port on
        // loopback refuses instantly, so checking it costs nothing.
        outbounds.put(JSONObject()
            .put("type", "vless").put("tag", SENTINEL_TAG)
            .put("server", "127.0.0.1").put("server_port", 1)
            .put("uuid", "00000000-0000-0000-0000-000000000000")
            .put("network", "tcp"))
        outbounds.put(JSONObject().put("type", "direct").put("tag", DIRECT_TAG).put("domain_resolver", LOCAL_DNS_TAG))
        val root = JSONObject()
            .put("log", JSONObject().put("disabled", true))
            .put("dns", buildProbeDns())
            .put("route", JSONObject().put("default_domain_resolver", LOCAL_DNS_TAG))
            .put("outbounds", outbounds)
        if (endpoints.length() > 0) root.put("endpoints", endpoints)
        return ProbeConfig(root.toString(), tags, errors, SENTINEL_TAG)
    }

    private fun buildProbeDns(): JSONObject = JSONObject()
        .put("servers", JSONArray().put(JSONObject().put("type", "local").put("tag", LOCAL_DNS_TAG)))
        .put("final", LOCAL_DNS_TAG)
        .put("strategy", "prefer_ipv4")

    private fun buildProxyOutbound(node: Node, tag: String = PROXY_TAG): JSONObject = when (node.protocol) {
        Protocol.VLESS -> buildVless(node, tag)
        Protocol.TROJAN -> buildTrojan(node, tag)
        Protocol.HYSTERIA2 -> buildHysteria2(node, tag)
        else -> error("Protocol ${node.protocol} is not a proxy outbound")
    }

    private fun buildTun(): JSONObject = JSONObject()
        .put("type", "tun")
        .put("tag", TUN_TAG)
        .put("address", JSONArray().apply {
            put("172.19.0.1/30")
            put("fdfe:dcba:9876::1/126")
        })
        .put("mtu", 1500)
        .put("dns_mode", "hijack")
        .put("dns_address", JSONArray().apply {
            put("172.19.0.2")
            put("fdfe:dcba:9876::2")
        })
        .put("auto_route", true)
        // Was false. With it off, sing-box tolerates routes it can't fully
        // enforce instead of forcing every packet through the tun - on
        // Android that's the documented mechanism behind "unsupported
        // network unreachable" being skipped, which is also the known class
        // of leak for system-level DNS-over-TLS ("Private DNS"): the OS can
        // resolve a blocked domain's real IP via the physical WiFi interface
        // instead of the app's fake-ip/hijack-dns route, and any DPI on that
        // WiFi ISP sees (and blocks) the query/connection before the proxy
        // ever gets a chance. That matches what's been observed: it's WiFi-
        // only (many carriers ignore Private DNS or block DoT, so mobile
        // data isn't affected), and it only breaks blocked domains (an
        // unblocked domain resolves/loads fine either way, so a leak there
        // is invisible). Flipping this to true is the standard sing-box
        // leak-prevention setting; verify by testing with Settings -> Network
        // & internet -> Private DNS set to "Off" on WiFi - if that alone also
        // fixes blocked resources, it confirms this is a Private DNS leak.
        .put("strict_route", true)

    private fun buildVless(node: Node, tag: String = PROXY_TAG): JSONObject {
        require(node.uuid.orEmpty().isNotBlank()) { "VLESS UUID is required" }
        return baseOutbound(node, "vless", setOf("uuid", "flow", "encryption", "network", "tls", "transport", "packet_encoding", "multiplex", "domain_resolver"), tag)
            .apply {
                put("server", node.server.requireServer())
                put("server_port", node.port.requirePort())
                put("uuid", node.uuid)
                if (!has("tls") && !has("transport")) put("network", "tcp")
                enforceBrowserTlsFingerprint(this)
            }
    }

    private fun buildTrojan(node: Node, tag: String = PROXY_TAG): JSONObject {
        require(node.password.orEmpty().isNotBlank()) { "Trojan password is required" }
        return baseOutbound(node, "trojan", setOf("password", "network", "tls", "transport", "multiplex", "domain_resolver"), tag)
            .apply {
                put("server", node.server.requireServer())
                put("server_port", node.port.requirePort())
                put("password", node.password)
                if (!has("tls")) put("tls", JSONObject().put("enabled", true).put("server_name", node.server))
                enforceBrowserTlsFingerprint(this)
            }
    }

    private fun buildHysteria2(node: Node, tag: String = PROXY_TAG): JSONObject {
        require(node.password.orEmpty().isNotBlank()) { "Hysteria2 password is required" }
        return baseOutbound(node, "hysteria2", setOf("password", "network", "tls", "transport", "multiplex", "domain_resolver"), tag)
            .apply {
                put("server", node.server.requireServer())
                put("server_port", node.port.requirePort())
                put("password", node.password)
                if (!has("tls")) put("tls", JSONObject().put("enabled", true).put("server_name", node.server))
            }
    }

    private fun baseOutbound(node: Node, type: String, allowedFields: Set<String>, tag: String = PROXY_TAG): JSONObject {
        val outbound = JSONObject()
        parseRawObject(node.rawConfig)?.let { source ->
            require(source.optString("type", type) == type) { "Imported outbound type does not match ${node.protocol}" }
            copyAllowed(source, outbound, allowedFields)
        }
        outbound.put("type", type).put("tag", tag)
        if (type == "vless") sanitizeVless(outbound)
        return outbound
    }

    private fun buildAmneziaWgEndpoint(node: Node, tag: String = AWG_TAG): JSONObject {
        val source = parseRawObject(node.rawConfig) ?: error("AmneziaWG requires a wireguard endpoint configuration")
        require(source.optString("type") == "wireguard") { "AmneziaWG raw configuration must have type=wireguard" }

        val endpoint = JSONObject()
        copyAllowed(source, endpoint, AWG_ENDPOINT_FIELDS)
        endpoint.put("type", "wireguard").put("tag", tag).put("detour", DIRECT_TAG)

        if (node.privateKey.orEmpty().isNotBlank()) endpoint.put("private_key", node.privateKey)
        normalizeAwgNumericParameters(endpoint)
        if (node.awg != null) copyAwgParameters(node, endpoint)

        val address = endpoint.optJSONArray("address")
        val peers = endpoint.optJSONArray("peers")
        require(address != null && address.length() > 0) { "AmneziaWG endpoint must contain address" }
        require(peers != null && peers.length() > 0) { "AmneziaWG endpoint must contain at least one peer" }

        val sanitizedPeers = JSONArray()
        for (i in 0 until peers.length()) sanitizedPeers.put(sanitizePeer(peers.getJSONObject(i)))
        endpoint.put("peers", sanitizedPeers)
        endpoint.put("mtu", endpoint.optInt("mtu", 1280).coerceIn(576, 65535))
        return endpoint
    }

    private fun sanitizePeer(source: JSONObject): JSONObject = JSONObject().apply {
        for (key in PEER_FIELDS) if (source.has(key) && !source.isNull(key)) put(key, source.get(key))
        require(optString("public_key").isNotBlank()) { "WireGuard peer public_key is required" }
        require(optString("address").isNotBlank()) { "WireGuard peer address is required" }
        require(optInt("port", 0) in 1..65535) { "WireGuard peer port must be between 1 and 65535" }
        if (has("reserved")) {
            val reserved = optJSONArray("reserved")
            require(reserved != null && reserved.length() == 3) { "WireGuard peer reserved must contain exactly 3 bytes" }
            for (i in 0 until 3) require(reserved.optInt(i, -1) in 0..255) { "WireGuard peer reserved byte is invalid" }
        }
    }

    private fun normalizeAwgNumericParameters(target: JSONObject) {
        for (key in AWG_NUMERIC_FIELDS) {
            if (!target.has(key) || target.isNull(key)) continue
            val value = target.get(key)
            val numericValue = when (value) {
                is Number -> value.toLong()
                is String -> value.trim().toLongOrNull()
                    ?: error("AmneziaWG parameter $key must be an integer")
                else -> error("AmneziaWG parameter $key must be a number")
            }
            require(numericValue in 0L..UINT32_MAX) {
                "AmneziaWG parameter $key is out of uint32 range"
            }
            target.put(key, numericValue)
        }
    }

    private fun copyAwgParameters(node: Node, target: JSONObject) {
        val awg = node.awg ?: return
        if (awg.jc > 0) target.put("jc", awg.jc)
        if (awg.jmin > 0) target.put("jmin", awg.jmin)
        if (awg.jmax > 0) target.put("jmax", awg.jmax)
        if (awg.s1 > 0) target.put("s1", awg.s1)
        if (awg.s2 > 0) target.put("s2", awg.s2)
        if (awg.s3 > 0) target.put("s3", awg.s3)
        if (awg.s4 > 0) target.put("s4", awg.s4)
        if (awg.h1 > 0) target.put("h1", awg.h1)
        if (awg.h2 > 0) target.put("h2", awg.h2)
        if (awg.h3 > 0) target.put("h3", awg.h3)
        if (awg.h4 > 0) target.put("h4", awg.h4)
        listOf("i1" to awg.i1, "i2" to awg.i2, "i3" to awg.i3, "i4" to awg.i4, "i5" to awg.i5)
            .forEach { (key, value) -> if (!value.isNullOrBlank()) target.put(key, value) }
    }

    /**
     * Makes sure a TCP-TLS outbound presents a browser's TLS ClientHello (uTLS)
     * rather than Go's.
     *
     * Without this, an imported Trojan/VLESS node that didn't specify a
     * fingerprint handshakes with crypto/tls' own ClientHello, whose JA3/JA4 is
     * both distinctive and well-published - it marks the connection as "not a
     * browser" on the very first packet, before any of the protocol's own
     * obfuscation gets a chance to matter. Reality already requires uTLS; this
     * extends the same treatment to plain-TLS nodes.
     *
     * Deliberately not applied to Hysteria2: it is QUIC, where sing-box uses its
     * own TLS stack and uTLS does not apply.
     */
    private fun enforceBrowserTlsFingerprint(outbound: JSONObject) {
        val tls = outbound.optJSONObject("tls") ?: return
        if (!tls.optBoolean("enabled", false)) return
        val utls = tls.optJSONObject("utls")
        if (utls == null) {
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", DEFAULT_TLS_FINGERPRINT))
            return
        }
        utls.put("enabled", true)
        if (utls.optString("fingerprint").lowercase().trim() !in TLS_FINGERPRINTS) {
            utls.put("fingerprint", DEFAULT_TLS_FINGERPRINT)
        }
    }

    private fun sanitizeVless(outbound: JSONObject) {
        val flow = outbound.optString("flow")
        val hasTransportObject = outbound.optJSONObject("transport") != null
        val network = outbound.optString("network").lowercase()
        if (flow == "xtls-rprx-vision" && (hasTransportObject || (network.isNotEmpty() && network != "tcp"))) {
            outbound.remove("flow")
        }
        if (outbound.has("packet_encoding") && outbound.optString("packet_encoding") != "xudp") {
            outbound.remove("packet_encoding")
        }
    }

    private fun copyAllowed(source: JSONObject, target: JSONObject, allowedFields: Set<String>) {
        for (key in allowedFields) if (source.has(key) && !source.isNull(key)) target.put(key, source.get(key))
    }

    private fun parseRawObject(rawConfig: String?): JSONObject? {
        val raw = rawConfig?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val obj = runCatching { JSONObject(raw) }.getOrElse {
            error("rawConfig must contain one sing-box outbound/endpoint JSON object: ${it.message}")
        }
        require(!obj.has("outbounds") && !obj.has("inbounds")) {
            "rawConfig must be one entry, not a full sing-box document"
        }
        return obj
    }

    private fun buildDns(): JSONObject = JSONObject()
        .put("servers", JSONArray().apply {
            put(JSONObject().put("type", "local").put("tag", LOCAL_DNS_TAG))
            put(JSONObject().put("type", "fakeip").put("tag", FAKE_IP_DNS_TAG)
                .put("inet4_range", "198.18.0.0/15").put("inet6_range", "fc00::/18"))
        })
        // A/AAAA queries must answer from fakeip, not local. "local" resolves
        // via the platform interface, which on Android *always* goes out over
        // the raw underlying network (see AndroidPlatformInterface/libbox
        // docs: "there is no other way to obtain upstream DNS servers") -
        // it never touches the tun, regardless of auto_route/strict_route.
        // Routing every real query there (the previous code) meant every
        // domain the user visits was resolved in clear text on the physical
        // WiFi network *before* the app ever attempted to proxy anything -
        // classic sing-box tun+sniff+hijack-dns setups exist specifically to
        // avoid this by answering with a throwaway fakeip address instead;
        // the real domain is recovered afterwards by the "sniff" route rule
        // above (from the TLS ClientHello/SNI) and only ever leaves the
        // device inside the proxied connection. This is very likely why
        // blocked domains failed specifically: their DNS query (or, for
        // domain-based blocking, the resulting behavior) was visible to the
        // WiFi ISP outside the tunnel while unblocked domains resolved fine
        // either way, so the leak was invisible for them.
        // "final" (the default/fallback server) must stay a real resolver -
        // libbox rejects a config where the default server is the fakeip
        // server itself ("initialize DNS server[1]: default server cannot be
        // fakeip", confirmed on-device). fakeip only kicks in through the
        // rule below, for the A/AAAA queries the tun's hijack-dns actually
        // needs faked; anything else (e.g. the DNS server's own bootstrap)
        // still falls through to dns-local.
        .put("rules", JSONArray().apply {
            put(JSONObject()
                .put("query_type", JSONArray().apply { put("A"); put("AAAA") })
                .put("action", "route")
                .put("server", FAKE_IP_DNS_TAG))
            // HTTPS/SVCB (type 65) used to fall through to "final" = dns-local,
            // which resolves on the physical interface, outside the tunnel (see
            // the note above) - and Chrome and the Android resolver query it for
            // virtually every connection, so a DPI box on the path got a
            // plaintext list of every domain visited, and got to answer it.
            //
            // Refused rather than forwarded over DoH-through-the-tunnel, which
            // was the first attempt here: a client that gets no HTTPS record
            // immediately falls back to plain A/AAAA (fakeip, answered on-device,
            // never leaves it), whereas a forwarded query stalls for the full DNS
            // timeout whenever that resolver isn't reachable through the proxy in
            // use - which showed up as pages hanging. Refusing closes the same
            // leak without ever being able to stall. The cost is ECH, which needs
            // the HTTPS record; fakeip plus TLS sniffing covers the routing that
            // record would otherwise inform.
            put(JSONObject()
                .put("query_type", JSONArray().apply { put("HTTPS"); put("SVCB") })
                .put("action", "reject"))
        })
        .put("final", LOCAL_DNS_TAG)
        .put("strategy", "prefer_ipv4")
        .put("reverse_mapping", true)

    private fun String.requireServer(): String = trim().also { require(it.isNotEmpty()) { "Server is required" } }
    private fun Int.requirePort(): Int = also { require(it in 1..65535) { "Port must be between 1 and 65535" } }

    private const val TUN_TAG = "tun-in"
    private const val PROXY_TAG = "proxy"
    private const val AWG_TAG = "awg"
    private const val DIRECT_TAG = "direct"
    private const val BLOCK_TAG = "block"
    private const val CACHE_FILE_NAME = "cache.db"
    private const val SENTINEL_TAG = "probe-sentinel"
    private const val LOCAL_DNS_TAG = "dns-local"
    private const val FAKE_IP_DNS_TAG = "dns-fakeip"

    private const val DEFAULT_TLS_FINGERPRINT = "chrome"
    // Kept in step with ConfigParser.VALID_FINGERPRINTS: a fingerprint that
    // survived import must not be second-guessed and downgraded here.
    private val TLS_FINGERPRINTS = setOf(
        "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle",
        "chrome_pq", "chrome_pq_psk", "chrome", "firefox", "edge",
        "safari", "360", "qq", "ios", "android", "random", "randomized"
    )

    private val AWG_ENDPOINT_FIELDS = setOf(
        "system", "name", "mtu", "address", "private_key", "listen_port", "workers",
        "udp_timeout", "udp_mapping", "udp_filtering", "udp_nat_max", "peers",
        "jc", "jmin", "jmax", "s1", "s2", "s3", "s4",
        "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5",
        "detour"
    )
    private val AWG_NUMERIC_FIELDS = setOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4")
    private const val UINT32_MAX = 4_294_967_295L

    private val PEER_FIELDS = setOf(
        "address", "port", "public_key", "pre_shared_key", "allowed_ips",
        "persistent_keepalive_interval", "reserved"
    )
}
