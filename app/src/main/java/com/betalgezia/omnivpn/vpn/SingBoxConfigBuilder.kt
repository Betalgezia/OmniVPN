package com.betalgezia.omnivpn.vpn

import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Protocol
import org.json.JSONArray
import org.json.JSONObject

object SingBoxConfigBuilder {
    fun build(node: Node): String {
        val endpointMode = node.protocol == Protocol.AMNEZIAWG
        val proxyTag = if (endpointMode) AWG_TAG else PROXY_TAG
        val route = JSONObject()
            .put("auto_detect_interface", true)
            .put("default_domain_resolver", LOCAL_DNS_TAG)
            .put("final", proxyTag)

        val root = JSONObject()
            // "debug" (was "info") while we're still chasing the "connects but no
            // traffic passes" symptom: at "info" the device logs only showed
            // AndroidLocalDns (the app's own system-DNS bootstrap, bound outside the
            // tunnel) and protect() calls - neither says anything about whether the
            // proxy/endpoint outbound itself ever dialed or completed a handshake. At
            // "debug" libbox additionally logs each inbound connection and its routing
            // decision on the OmniVpnService "[libbox] ..." channel, which is what we
            // actually need to see. Safe to turn back down to "info" once resolved.
            .put("log", JSONObject().put("level", "debug"))
            .put("dns", buildDns())
            .put("route", route.put("rules", JSONArray().apply {
                put(JSONObject()
                    .put("inbound", JSONArray().put(TUN_TAG))
                    .put("action", "sniff"))
                put(JSONObject()
                    .put("inbound", JSONArray().put(TUN_TAG))
                    .put("protocol", "dns")
                    .put("action", "hijack-dns"))
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

        if (endpointMode) {
            root.put("endpoints", JSONArray().put(buildAmneziaWgEndpoint(node)))
        }
        return root.toString()
    }

    private fun buildProxyOutbound(node: Node): JSONObject = when (node.protocol) {
        Protocol.VLESS -> buildVless(node)
        Protocol.TROJAN -> buildTrojan(node)
        Protocol.HYSTERIA2 -> buildHysteria2(node)
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
        .put("strict_route", false)

    private fun buildVless(node: Node): JSONObject {
        require(node.uuid.orEmpty().isNotBlank()) { "VLESS UUID is required" }
        return baseOutbound(node, "vless", setOf("uuid", "flow", "encryption", "network", "tls", "transport", "packet_encoding", "multiplex", "domain_resolver"))
            .apply {
                put("server", node.server.requireServer())
                put("server_port", node.port.requirePort())
                put("uuid", node.uuid)
                if (!has("tls") && !has("transport")) put("network", "tcp")
            }
    }

    private fun buildTrojan(node: Node): JSONObject {
        require(node.password.orEmpty().isNotBlank()) { "Trojan password is required" }
        return baseOutbound(node, "trojan", setOf("password", "network", "tls", "transport", "multiplex", "domain_resolver"))
            .apply {
                put("server", node.server.requireServer())
                put("server_port", node.port.requirePort())
                put("password", node.password)
                if (!has("tls")) put("tls", JSONObject().put("enabled", true).put("server_name", node.server))
            }
    }

    private fun buildHysteria2(node: Node): JSONObject {
        require(node.password.orEmpty().isNotBlank()) { "Hysteria2 password is required" }
        return baseOutbound(node, "hysteria2", setOf("password", "network", "tls", "transport", "multiplex", "domain_resolver"))
            .apply {
                put("server", node.server.requireServer())
                put("server_port", node.port.requirePort())
                put("password", node.password)
                if (!has("tls")) put("tls", JSONObject().put("enabled", true).put("server_name", node.server))
            }
    }

    private fun baseOutbound(node: Node, type: String, allowedFields: Set<String>): JSONObject {
        val outbound = JSONObject()
        parseRawObject(node.rawConfig)?.let { source ->
            require(source.optString("type", type) == type) { "Imported outbound type does not match ${node.protocol}" }
            copyAllowed(source, outbound, allowedFields)
        }
        outbound.put("type", type).put("tag", PROXY_TAG)
        if (type == "vless") sanitizeVless(outbound)
        return outbound
    }

    private fun buildAmneziaWgEndpoint(node: Node): JSONObject {
        val source = parseRawObject(node.rawConfig) ?: error("AmneziaWG requires a wireguard endpoint configuration")
        require(source.optString("type") == "wireguard") { "AmneziaWG raw configuration must have type=wireguard" }

        val endpoint = JSONObject()
        copyAllowed(source, endpoint, AWG_ENDPOINT_FIELDS)
        endpoint.put("type", "wireguard").put("tag", AWG_TAG).put("detour", DIRECT_TAG)

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
        .put("rules", JSONArray().put(JSONObject()
            .put("query_type", JSONArray().apply { put("A"); put("AAAA") })
            .put("action", "route")
            .put("server", LOCAL_DNS_TAG)))
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
    private const val LOCAL_DNS_TAG = "dns-local"
    private const val FAKE_IP_DNS_TAG = "dns-fakeip"

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
