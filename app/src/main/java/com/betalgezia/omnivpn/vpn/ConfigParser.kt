package com.betalgezia.omnivpn.vpn

import android.util.Base64
import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Protocol
import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.charset.StandardCharsets

object ConfigParser {
    fun parse(input: String): List<Node> {
        val text = decodeBase64IfNeeded(input)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.contains("://") && !trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            val uriNodes = SubscriptionParser.parse(trimmed)
            if (uriNodes.isNotEmpty()) return uriNodes
        }
        if (looksLikeWireguardIni(trimmed)) return parseWireguardIni(trimmed)

        return when {
            trimmed.startsWith("{") || trimmed.startsWith("[") -> parseJson(trimmed)
            else -> parseYaml(trimmed)
        }
    }

    private fun looksLikeWireguardIni(text: String): Boolean {
        return text.lineSequence().any { it.trim().equals("[Interface]", ignoreCase = true) } &&
            text.lineSequence().any { it.trim().equals("[Peer]", ignoreCase = true) }
    }

    private fun parseWireguardIni(text: String): List<Node> {
        val sections = mutableListOf<Pair<String, MutableMap<String, String>>>()
        var section: MutableMap<String, String>? = null
        var sectionName = ""

        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                sectionName = trimmed.substring(1, trimmed.length - 1).trim()
                section = if (sectionName.equals("Interface", ignoreCase = true) || sectionName.equals("Peer", ignoreCase = true)) {
                    mutableMapOf()
                } else {
                    null
                }
                if (section != null) sections += sectionName to section!!
                continue
            }
            val target = section ?: continue
            val separator = trimmed.indexOf("=")
            if (separator <= 0) continue
            val key = trimmed.substring(0, separator).trim().lowercase().replace("-", "_")
            val value = trimmed.substring(separator + 1).trim()
            if (value.isNotEmpty()) target[key] = value
        }

        val iface = sections.firstOrNull { it.first.equals("Interface", ignoreCase = true) }?.second ?: return emptyList()
        val peers = sections.filter { it.first.equals("Peer", ignoreCase = true) }.map { it.second }
        val address = iface["address"] ?: iface["addresses"] ?: return emptyList()
        val privateKey = iface["privatekey"] ?: iface["private_key"] ?: return emptyList()

        val root = mutableMapOf<String, Any?>(
            "type" to "wireguard",
            "address" to address,
            "private_key" to privateKey,
            "mtu" to (iface["mtu"] ?: "1408")
        )

        val awgKeys = listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")
        for (key in awgKeys) iface[key]?.let { root[key] = it }

        root["peers"] = peers.mapNotNull { peer ->
            val endpoint = peer["endpoint"] ?: return@mapNotNull null
            val publicKey = peer["publickey"] ?: peer["public_key"] ?: return@mapNotNull null
            mutableMapOf<String, Any?>(
                "address" to endpoint,
                "public_key" to publicKey,
                "allowed_ips" to (peer["allowedips"] ?: peer["allowed_ips"] ?: "0.0.0.0/0, ::/0")
            ).apply {
                peer["presharedkey"]?.let { this["pre_shared_key"] = it }
                peer["pre_shared_key"]?.let { this["pre_shared_key"] = it }
                peer["persistentkeepalive"]?.let { this["persistent_keepalive_interval"] = it }
                peer["persistent_keepalive"]?.let { this["persistent_keepalive_interval"] = it }
                peer["reserved"]?.let { this["reserved"] = it }
            }
        }

        return parseEntry(root)?.let(::listOf) ?: emptyList()
    }
    private fun parseJson(text: String): List<Node> {
        val value = runCatching { JSONObject(text) }.map { jsonToMap(it) }
            .getOrElse { JSONArray(text).let(::jsonToList) }
        return parseRoot(value)
    }

    private fun parseYaml(text: String): List<Node> {
        val options = LoaderOptions()
        options.maxAliasesForCollections = 32
        options.codePointLimit = 2_000_000
        val value = Yaml(SafeConstructor(options)).load<Any?>(text) ?: return emptyList()
        return parseRoot(value)
    }

    private fun parseRoot(root: Any?): List<Node> {
        val maps = when (root) {
            is Map<*, *> -> {
                val outbounds = root["outbounds"] as? List<*>
                val endpoints = root["endpoints"] as? List<*>
                val mihomo = root["proxies"] as? List<*>
                when {
                    outbounds != null -> outbounds.filterIsInstance<Map<*, *>>()
                    endpoints != null -> endpoints.filterIsInstance<Map<*, *>>()
                    mihomo != null -> mihomo.filterIsInstance<Map<*, *>>()
                    root["type"] != null -> listOf(root)
                    else -> emptyList()
                }
            }
            is List<*> -> root.filterIsInstance<Map<*, *>>()
            else -> emptyList()
        }
        return maps.mapNotNull(::parseEntry)
    }

    private fun parseEntry(raw: Map<*, *>): Node? {
        val type = string(raw, "type")?.lowercase()?.trim() ?: return null
        return runCatching {
            when (type) {
                "vless" -> parseVlessMap(raw)
                "trojan" -> parseTrojanMap(raw)
                "hysteria2", "hy2" -> parseHysteria2Map(raw)
                "wireguard", "wg", "awg" -> parseWireguardMap(raw)
                else -> null
            }
        }.getOrNull()
    }

    private fun parseVlessMap(raw: Map<*, *>): Node? {
        val server = string(raw, "server") ?: return null
        val port = int(raw, "server_port", "port") ?: return null
        val uuid = string(raw, "uuid") ?: return null
        val canonical = JSONObject()
            .put("type", "vless")
            .put("server", server)
            .put("server_port", port)
            .put("uuid", uuid)
        val flow = string(raw, "flow")?.trim().orEmpty()
        val transportType = string(raw, "network")?.lowercase()
        val hasTransport = raw["transport"] is Map<*, *> || (transportType != null && transportType != "tcp")
        if (flow == "xtls-rprx-vision" && hasTransport) {
            // Vision is valid only on bare TCP; dropping the flow keeps the node loadable.
        } else if (flow == "xtls-rprx-vision") {
            canonical.put("flow", flow)
        } else if (flow.isNotBlank()) {
            // Unsupported VLESS flow values are ignored instead of poisoning the config.
        }
        val packetEncoding = string(raw, "packet_encoding", "packet-encoding")?.lowercase()
        if (packetEncoding == "xudp") canonical.put("packet_encoding", packetEncoding)
        copy(raw, canonical, setOf("network", "multiplex", "domain_strategy"))
        putTlsMapIfPresent(raw, canonical, "random")
        if (!canonical.has("tls") && isTrue(raw["tls"])) canonical.put("tls", buildTls(raw, server, "random"))
        if (!canonical.has("tls")) buildMihomoTls(raw, server, "random")?.let { canonical.put("tls", it) }
        putMapIfPresent(raw, canonical, "transport")
        if (!canonical.has("transport")) buildMihomoTransport(raw)?.let { canonical.put("transport", it) }
        return node(name(raw, server, port), Protocol.VLESS, server, port, uuid = uuid, raw = canonical.toString())
    }

    private fun parseTrojanMap(raw: Map<*, *>): Node? {
        val server = string(raw, "server") ?: return null
        val port = int(raw, "server_port", "port") ?: return null
        val password = string(raw, "password") ?: return null
        val canonical = JSONObject().put("type", "trojan").put("server", server).put("server_port", port).put("password", password)
        copy(raw, canonical, setOf("network", "multiplex", "domain_strategy"))
        putTlsMapIfPresent(raw, canonical, "")
        if (!canonical.has("tls") && isTrue(raw["tls"])) canonical.put("tls", buildTls(raw, server, ""))
        if (!canonical.has("tls")) buildMihomoTls(raw, server, "")?.let { canonical.put("tls", it) }
        putMapIfPresent(raw, canonical, "transport")
        if (!canonical.has("transport")) buildMihomoTransport(raw)?.let { canonical.put("transport", it) }
        return node(name(raw, server, port), Protocol.TROJAN, server, port, password = password, raw = canonical.toString())
    }

    private fun parseHysteria2Map(raw: Map<*, *>): Node? {
        val server = string(raw, "server") ?: return null
        val port = int(raw, "server_port", "port") ?: return null
        val password = string(raw, "password") ?: ""
        val canonical = JSONObject().put("type", "hysteria2").put("server", server).put("server_port", port)
        if (password.isNotBlank()) canonical.put("password", password)
        int(raw, "up_mbps", "up")?.let { canonical.put("up_mbps", it) }
        int(raw, "down_mbps", "down")?.let { canonical.put("down_mbps", it) }
        copy(raw, canonical, setOf("network", "domain_strategy"))
        putMapIfPresent(raw, canonical, "obfs")
        if (!canonical.has("obfs")) buildHysteriaObfs(raw)?.let { canonical.put("obfs", it) }
        putTlsMapIfPresent(raw, canonical, "")
        if (!canonical.has("tls")) canonical.put("tls", buildTls(raw, server, ""))
        return node(name(raw, server, port), Protocol.HYSTERIA2, server, port, password = password, raw = canonical.toString())
    }

    private fun parseWireguardMap(raw: Map<*, *>): Node? {
        val rootEndpoint = string(raw, "server", "endpoint")
        val sourcePeers = (raw["peers"] as? List<*>)?.filterIsInstance<Map<*, *>>()
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(raw)

        val address = addressList(raw)
        if (address.isEmpty()) return null
        val privateKey = string(raw, "private_key", "private-key") ?: return null

        val peers = sourcePeers.mapNotNull { source ->
            val endpointText = string(source, "address")
                ?: rootEndpoint
                ?: return@mapNotNull null
            val server = normalizeEndpointHost(endpointText) ?: return@mapNotNull null
            val port = int(source, "port")
                ?: int(raw, "server_port", "port")
                ?: endpointPort(endpointText, 51820)
            val publicKey = string(source, "public_key", "public-key")
                ?: string(raw, "peer_public_key", "public_key", "public-key")
                ?: return@mapNotNull null

            JSONObject()
                .put("address", server)
                .put("port", port)
                .put("public_key", publicKey)
                .put("allowed_ips", JSONArray(
                    list(source, "allowed_ips", "allowed-ips")
                        ?: list(raw, "allowed_ips", "allowed-ips")
                        ?: listOf("0.0.0.0/0", "::/0")
                ))
                .apply {
                    string(source, "pre_shared_key", "pre-shared-key")
                        ?.let { put("pre_shared_key", it) }
                    string(raw, "pre_shared_key", "pre-shared-key")
                        ?.takeIf { !has("pre_shared_key") }
                        ?.let { put("pre_shared_key", it) }
                    int(source, "persistent_keepalive_interval", "persistent-keepalive", "keepalive")
                        ?.let { put("persistent_keepalive_interval", it) }
                    int(raw, "persistent_keepalive_interval", "persistent-keepalive", "keepalive")
                        ?.takeIf { !has("persistent_keepalive_interval") }
                        ?.let { put("persistent_keepalive_interval", it) }
                    (reserved(source) ?: reserved(raw))?.let { put("reserved", JSONArray(it)) }
                }
        }
        if (peers.isEmpty()) return null

        val firstPeer = peers.first()
        val server = firstPeer.getString("address")
        val port = firstPeer.getInt("port")
        val canonical = JSONObject()
            .put("type", "wireguard")
            .put("tag", "imported-wg")
            .put("mtu", int(raw, "mtu") ?: 1408)
            .put("address", JSONArray(address))
            .put("private_key", privateKey)
            .put("peers", JSONArray().apply { peers.forEach { put(it) } })
        addAwgFields(raw, canonical)

        return node(
            name(raw, server, port),
            Protocol.AMNEZIAWG,
            server,
            port,
            privateKey = privateKey,
            raw = canonical.toString()
        )
    }

    private fun buildTls(raw: Map<*, *>, server: String, defaultFingerprint: String): JSONObject = JSONObject().put("enabled", true).put("server_name", string(raw, "servername", "sni") ?: server)
        .apply {
            if (isTrue(raw["skip-cert-verify"]) || isTrue(raw["insecure"])) put("insecure", true)
            val requestedFingerprint = string(raw, "client-fingerprint", "fingerprint")?.lowercase()?.trim().orEmpty()
            val fingerprint = requestedFingerprint.takeIf { it in VALID_FINGERPRINTS } ?: defaultFingerprint
            if (fingerprint in VALID_FINGERPRINTS) put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint))
            list(raw, "alpn")?.let { put("alpn", JSONArray(it)) }
            val reality = raw["reality-opts"] as? Map<*, *>
            reality?.let { r ->
                val publicKey = string(r, "public-key")
                if (isValidRealityPublicKey(publicKey)) {
                    put("reality", JSONObject().put("enabled", true).put("public_key", publicKey).apply {
                        val shortId = string(r, "short-id")
                        if (isValidRealityShortId(shortId)) put("short_id", shortId)
                    })
                }
            }
        }

    private fun buildMihomoTls(raw: Map<*, *>, server: String, defaultFingerprint: String): JSONObject? {
        val explicitTls = raw["tls"]
        if (explicitTls != null && isFalse(explicitTls)) return null
        val enabled = isTrue(explicitTls) || raw["servername"] != null || raw["sni"] != null || raw["reality-opts"] != null || raw["skip-cert-verify"] != null
        return if (enabled) buildTls(raw, server, defaultFingerprint) else null
    }

    private fun buildMihomoTransport(raw: Map<*, *>): JSONObject? {
        return when (string(raw, "network")?.lowercase()) {
            "ws" -> (raw["ws-opts"] as? Map<*, *>)?.let { ws -> JSONObject().put("type", "ws").apply { string(ws, "path")?.let { put("path", it) }; (ws["headers"] as? Map<*, *>)?.let { headers -> put("headers", toJsonValue(headers)) } } }
            "grpc" -> (raw["grpc-opts"] as? Map<*, *>)?.let { g -> JSONObject().put("type", "grpc").put("service_name", string(g, "grpc-service-name", "service-name") ?: "") }
            "httpupgrade" -> (raw["http-upgrade-opts"] as? Map<*, *>)?.let { h -> JSONObject().put("type", "httpupgrade").apply { string(h, "path")?.let { put("path", it) }; string(h, "host")?.let { put("host", it) } } }
            "xhttp" -> (raw["xhttp-opts"] as? Map<*, *>)?.let { x -> JSONObject().put("type", "xhttp").apply {
                string(x, "path")?.let { put("path", it) }
                string(x, "host")?.let { put("host", it) }
                string(x, "mode")?.let { put("mode", it) }
                string(x, "x-padding-bytes", "x_padding_bytes", "xPaddingBytes")?.let { put("x_padding_bytes", it) }
                if (isTrue(x["no-grpc-header"]) || isTrue(x["no_grpc_header"]) || isTrue(x["noGRPCHeader"])) put("no_grpc_header", true)
                (x["headers"] as? Map<*, *>)?.let { put("headers", toJsonValue(it)) }
            } }
            else -> null
        }
    }

    private fun buildHysteriaObfs(raw: Map<*, *>): JSONObject? {
        val type = string(raw, "obfs")?.lowercase() ?: return null
        if (type != "salamander" && type != "gecko") return null
        val o = JSONObject().put("type", type)
        string(raw, "obfs-password")?.let { o.put("password", it) }
        int(raw, "obfs-min-packet-size")?.let { o.put("min_packet_size", it) }
        int(raw, "obfs-max-packet-size")?.let { o.put("max_packet_size", it) }
        return o
    }

    private fun addAwgFields(raw: Map<*, *>, target: JSONObject) {
        val keys = listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")
        for (key in keys) raw[key]?.let { target.put(key, it) }
    }

    private fun copy(raw: Map<*, *>, target: JSONObject, fields: Set<String>) {
        for (field in fields) raw[field]?.let { value -> target.put(field, toJsonValue(value)) }
    }

    private fun putMapIfPresent(raw: Map<*, *>, target: JSONObject, field: String) {
        val value = raw[field] as? Map<*, *> ?: return
        target.put(field, toJsonValue(value))
    }

    private fun putTlsMapIfPresent(
        raw: Map<*, *>,
        target: JSONObject,
        defaultFingerprint: String
    ) {
        val value = raw["tls"] as? Map<*, *> ?: return
        val tls = toJsonValue(value) as? JSONObject ?: return
        val reality = tls.optJSONObject("reality")
        if (reality != null) {
            val publicKey = reality.optString("public_key").trim()
            if (!isValidRealityPublicKey(publicKey)) {
                tls.remove("reality")
            } else if (reality.has("short_id") && !isValidRealityShortId(reality.optString("short_id"))) {
                reality.remove("short_id")
            }
        }
        val utls = tls.optJSONObject("utls")
        if (utls != null && utls.optBoolean("enabled", false)) {
            val fingerprint = utls.optString("fingerprint").lowercase().trim()
            if (fingerprint.isEmpty()) {
                if (defaultFingerprint in VALID_FINGERPRINTS) {
                    utls.put("fingerprint", defaultFingerprint)
                }
            } else if (fingerprint !in VALID_FINGERPRINTS) {
                if (defaultFingerprint in VALID_FINGERPRINTS) {
                    utls.put("fingerprint", defaultFingerprint)
                } else {
                    tls.remove("utls")
                }
            }
        }
        target.put("tls", tls)
    }

    private fun isValidRealityPublicKey(value: String?): Boolean {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return false
        val padded = raw + "=".repeat((4 - raw.length % 4) % 4)
        val bytes = runCatching {
            Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull() ?: return false
        return bytes.size == 32
    }

    private fun isValidRealityShortId(value: String?): Boolean {
        val raw = value?.trim()?.lowercase().orEmpty()
        return raw.isNotEmpty() && raw.length <= 16 && raw.length % 2 == 0 && raw.all { it in "0123456789abcdef" }
    }

    private val VALID_FINGERPRINTS = setOf(
        "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle",
        "chrome_pq", "chrome_pq_psk", "chrome", "firefox", "edge",
        "safari", "360", "qq", "ios", "android", "random", "randomized"
    )

    private fun allowedIps(raw: Map<*, *>): JSONArray {
        val values = list(raw, "allowed-ips", "allowed_ips")
        return JSONArray(if (values.isNullOrEmpty()) listOf("0.0.0.0/0", "::/0") else values)
    }

    private fun addressList(raw: Map<*, *>): List<String> = list(raw, "address", "addresses", "ip", "ipv6") ?: emptyList()

    private fun firstPeerPublicKey(raw: Map<*, *>): String? = (raw["peers"] as? List<*>)?.firstNotNullOfOrNull { (it as? Map<*, *>)?.let { p -> string(p, "public_key", "public-key") } }

    private fun reserved(raw: Map<*, *>): List<Int>? {
        val value = raw["reserved"] ?: raw["client_id"] ?: return null
        if (value is List<*>) return value.mapNotNull { (it as? Number)?.toInt() }.takeIf { it.size == 3 && it.all { b -> b in 0..255 } }
        val text = value.toString().trim()
        if (text.contains(",")) {
            val numbers = text.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (numbers.size == 3 && numbers.all { it in 0..255 }) return numbers
        }
        val decoded = runCatching { Base64.decode(text, Base64.DEFAULT or Base64.NO_WRAP) }.getOrNull() ?: return null
        return decoded.takeIf { it.size == 3 }?.map { it.toInt() and 0xff }
    }

    private fun decodeBase64IfNeeded(input: String): String {
        val trimmed = input.trim()
        if (trimmed.contains("://") || trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("proxies:") || trimmed.startsWith("outbounds:") || trimmed.startsWith("endpoints:")) return trimmed
        val compact = trimmed.replace("\\s".toRegex(), "")
        if (compact.length < 16 || compact.any { it !in BASE64_CHARS }) return trimmed
        val bytes = runCatching { Base64.decode(compact, Base64.DEFAULT or Base64.NO_WRAP) }.getOrElse {\n            runCatching { Base64.decode(compact, Base64.URL_SAFE or Base64.NO_WRAP) }.getOrElse { return trimmed }\n        }
        val decoded = bytes.toString(StandardCharsets.UTF_8)
        return if (decoded.contains("://") || decoded.trimStart().startsWith("{") || decoded.contains("proxies:")) decoded else trimmed
    }

    private fun name(raw: Map<*, *>, server: String, port: Int): String = string(raw, "name", "remark", "tag")?.takeIf { it.isNotBlank() } ?: "$server:$port"
    private fun string(raw: Map<*, *>, vararg keys: String): String? = keys.firstNotNullOfOrNull { key -> raw[key]?.toString()?.takeIf { it.isNotBlank() } }
    private fun int(raw: Map<*, *>, vararg keys: String): Int? = keys.firstNotNullOfOrNull { key -> (raw[key] as? Number)?.toInt() ?: raw[key]?.toString()?.toIntOrNull() }
    private fun list(raw: Map<*, *>, vararg keys: String): List<String>? = keys.firstNotNullOfOrNull { key ->
        when (val value = raw[key]) {
            is List<*> -> value.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
            is String -> value.split(',').map(String::trim).filter(String::isNotBlank)
            else -> null
        }.takeIf { !it.isNullOrEmpty() }
    }
    private fun normalizeEndpointHost(value: String): String? {\n        val raw = value.trim()\n        if (raw.startsWith("[")) {\n            val close = raw.indexOf(']')\n            return raw.takeIf { close > 1 }?.substring(1, close)\n        }\n        if (raw.count { it == ':' } > 1) return raw\n        return raw.substringBeforeLast(':').takeIf { it.isNotBlank() } ?: raw.takeIf { it.isNotBlank() }\n    }\n\n    private fun endpointPort(value: String, fallback: Int): Int = value.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 } ?: fallback
    private fun isTrue(value: Any?): Boolean = when (value) { is Boolean -> value; else -> value?.toString()?.lowercase() in setOf("true", "1", "yes") }
    private fun isFalse(value: Any?): Boolean = when (value) { is Boolean -> !value; else -> value?.toString()?.lowercase() in setOf("false", "0", "no") }
    private fun toJsonValue(value: Any?): Any = when (value) { is Map<*, *> -> JSONObject().apply { value.forEach { (k,v) -> if (k != null && v != null) put(k.toString(), toJsonValue(v)) } }; is List<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }; else -> value ?: JSONObject.NULL }
    private fun jsonToMap(value: JSONObject): Map<String, Any?> = value.keys().asSequence().associateWith { key -> jsonToAny(value.opt(key)) }
    private fun jsonToList(value: JSONArray): List<Any?> = (0 until value.length()).map { jsonToAny(value.opt(it)) }
    private fun jsonToAny(value: Any?): Any? = when (value) { is JSONObject -> jsonToMap(value); is JSONArray -> jsonToList(value); JSONObject.NULL -> null; else -> value }
    private fun node(name: String, protocol: Protocol, server: String, port: Int, uuid: String? = null, password: String? = null, privateKey: String? = null, raw: String): Node = Node(name=name, protocol=protocol, server=server, port=port, uuid=uuid, password=password, privateKey=privateKey, rawConfig=raw)
    private const val BASE64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=_-"
}