package com.betalgezia.omnivpn.vpn

import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Nodes parsed from one config/subscription body. [skipped] counts list entries past
 * [SubscriptionParser.MAX_NODES_PER_LIST] that were never read: non-zero means the list
 * was cut, which callers report rather than show the list as if it were complete.
 */
data class ParsedNodes(val nodes: List<Node>, val skipped: Int = 0)

object SubscriptionParser {
    /**
     * Most nodes taken from one URI list. What a long list costs is the nodes - the
     * database, the server list, a full test that puts every one of them into a single
     * probe config - not its characters, so this is the cap rather than the list length.
     */
    const val MAX_NODES_PER_LIST = 1000

    fun parse(input: String): List<Node> = parseWithCount(input).nodes

    /**
     * There is no cap on the size of the whole list: the old 64 KiB one turned a list of
     * ~190 or more links into zero nodes ("Subscription returned no supported nodes")
     * although every line in it was fine. A subscription body is already capped at 5 MB
     * by SubscriptionRepository, a single line still at [MAX_URI_CHARS_PER_LINE], and the
     * node count at [MAX_NODES_PER_LIST] - the first nodes are kept, the rest counted.
     */
    fun parseWithCount(input: String): ParsedNodes {
        var candidateLines = 0
        var skipped = 0
        var firstFailure: Throwable? = null
        val result = ArrayList<Node>()
        for (rawLine in input.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            candidateLines++
            if (result.size >= MAX_NODES_PER_LIST) {
                skipped++
                continue
            }
            if (line.length > MAX_URI_CHARS_PER_LINE) continue
            runCatching { parseUri(line) }
                .onFailure { if (firstFailure == null) firstFailure = it }
                .getOrNull()?.let(result::add)
        }
        if (skipped > 0) {
            runCatching {
                android.util.Log.w(TAG, "parse: kept the first $MAX_NODES_PER_LIST nodes, $skipped more line(s) not read")
            }
        }
        if (result.isEmpty() && candidateLines > 0) {
            // Never log the line/URI itself - it carries live UUIDs/passwords. Counts and the
            // first exception's type/message are enough to tell "every line failed to parse"
            // (e.g. the URISyntaxException fixed below, from an un-percent-encoded remark)
            // apart from "this wasn't a URI list at all".
            runCatching {
                android.util.Log.w(
                    TAG,
                    "parse: $candidateLines candidate line(s), 0 parsed nodes" +
                        (firstFailure?.let { "; first failure: ${it::class.simpleName}: ${it.message}" } ?: "")
                )
            }
        }
        return ParsedNodes(result, skipped)
    }

    private fun parseUri(value: String): Node? {
        val normalized = value.trim()
        // Split the remark (#...) off BEFORE handing anything to java.net.URI. URI's constructor
        // is strict (RFC 3986) and throws URISyntaxException on an unescaped space, emoji, or
        // other raw Unicode - and free VLESS/Trojan/Hysteria2 subscription generators very
        // commonly put a human-readable, NOT percent-encoded remark there (a flag emoji plus a
        // country name is typical, e.g. "#🇹🇷 Turkey"). Left as-is, one
        // badly-encoded remark silently drops just that node (parse()'s runCatching swallows the
        // exception); a whole subscription generated the same way silently drops every node,
        // which is exactly the "Subscription returned no supported nodes" symptom - confirmed via
        // the length/prefix logging added to SubscriptionRepository.refresh(), which showed a
        // legitimate base64-encoded vless:// list, not an empty or garbled response.
        val hashIndex = normalized.indexOf('#')
        val uriPart = if (hashIndex >= 0) normalized.substring(0, hashIndex) else normalized
        val rawRemark = if (hashIndex >= 0) normalized.substring(hashIndex + 1) else null
        val uri = runCatching { URI(escapeForUri(uriPart)) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val remark = rawRemark?.let(::decode)?.takeIf { it.isNotBlank() }
        return when (scheme) {
            "vless" -> parseVless(uri, remark)
            "trojan" -> parseTrojan(uri, remark)
            "hysteria2", "hy2" -> parseHysteria2(uri, remark)
            else -> null
        }
    }

    private fun parseVless(uri: URI, remark: String?): Node? {
        val server = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = if (uri.port == -1) 443 else uri.port.takeIf { it in 1..65535 } ?: return null
        val uuid = decode(uri.rawUserInfo).substringBefore(":").takeIf { it.isNotBlank() } ?: return null
        val query = parseQuery(uri.rawQuery)
        var flow = query["flow"]?.trim().orEmpty()
        var packetEncoding = query["packetEncoding"] ?: query["packet_encoding"] ?: ""
        if (flow == "xtls-rprx-vision-udp443") {
            flow = "xtls-rprx-vision"
            packetEncoding = "xudp"
        }
        val tls = buildTls(query, server, defaultEnabled = isTlsSecurity(query, port), defaultFingerprint = "random")
        val transport = buildTransport(query)
        val visionWithTransport = flow == "xtls-rprx-vision" && transport != null
        val visionValid = flow == "xtls-rprx-vision" && !visionWithTransport && tls != null
        val raw = JSONObject()
            .put("type", "vless")
            .put("server", server)
            .put("server_port", port)
            .put("uuid", uuid)
            .apply {
                if (visionValid) {
                    put("flow", flow)
                }
                if (packetEncoding in VALID_PACKET_ENCODINGS) put("packet_encoding", packetEncoding)
                tls?.let { put("tls", it) }
                // sing-box's outbound "network" field only ever selects "tcp"/"udp"
                // (see the `!has("tls") && !has("transport") -> "tcp"` default in
                // SingBoxConfigBuilder.buildVless); it is NOT where the V2Ray
                // transport kind (ws/grpc/httpupgrade/xhttp) goes - that belongs
                // solely under "transport". Setting network to the transport type
                // made Libbox.checkConfig() reject every gRPC/ws/etc VLESS node
                // with "unknown network: grpc" (confirmed on-device). Only
                // "transport" should be written here.
                transport?.let { put("transport", it) }
            }
        return node(remark ?: "$server:$port", Protocol.VLESS, server, port, uuid = uuid, raw = raw.toString())
    }

    private fun parseTrojan(uri: URI, remark: String?): Node? {
        val server = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = if (uri.port == -1) 443 else uri.port.takeIf { it in 1..65535 } ?: return null
        val password = decode(uri.rawUserInfo).takeIf { it.isNotBlank() } ?: return null
        val query = parseQuery(uri.rawQuery)
        val tls = buildTls(query, server, defaultEnabled = true, defaultFingerprint = "")
        val transport = buildTransport(query)
        val raw = JSONObject()
            .put("type", "trojan")
            .put("server", server)
            .put("server_port", port)
            .put("password", password)
            .apply {
                tls?.let { put("tls", it) }
            }
            .apply {
                // Same fix as parseVless: "network" is tcp/udp only, never the
                // transport kind - don't set it from the transport type here.
                transport?.let { put("transport", it) }
            }
        return node(remark ?: "$server:$port", Protocol.TROJAN, server, port, password = password, raw = raw.toString())
    }

    private fun parseHysteria2(uri: URI, remark: String?): Node? {
        val server = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = if (uri.port == -1) 443 else uri.port.takeIf { it in 1..65535 } ?: return null
        val password = decode(uri.rawUserInfo).takeIf { it.isNotBlank() } ?: return null
        val query = parseQuery(uri.rawQuery)
        val raw = JSONObject()
            .put("type", "hysteria2")
            .put("server", server)
            .put("server_port", port)
            .put("password", password)
            .apply {
                buildTls(query, server, defaultEnabled = true, defaultFingerprint = "")?.let { put("tls", it) }
            }
            .apply {
                parseMbps(query["up"])?.let { put("up_mbps", it) }
                parseMbps(query["down"])?.let { put("down_mbps", it) }
                buildHysteriaObfs(query)?.let { put("obfs", it) }
            }
        return node(remark ?: "$server:$port", Protocol.HYSTERIA2, server, port, password = password, raw = raw.toString())
    }

    private fun buildTls(query: Map<String, String>, server: String, defaultEnabled: Boolean, defaultFingerprint: String): JSONObject? {
        val security = query["security"]?.lowercase()
        if (security == "none") return null
        val reality = security == "reality" || !query["pbk"].isNullOrBlank() || !query["public-key"].isNullOrBlank()
        val enabled = defaultEnabled || security == "tls" || reality || query["sni"] != null || query["servername"] != null || query["alpn"] != null || query["insecure"] != null || query["allowInsecure"] != null || query["fp"] != null || query["fingerprint"] != null
        if (!enabled) return null
        return JSONObject().put("enabled", true).put("server_name", query["sni"] ?: query["servername"] ?: server).apply {
            val insecure = query["insecure"] ?: query["allowInsecure"] ?: query["allow-insecure"]
            if (insecure?.let(::isTrue) == true) put("insecure", true)
            val requestedFingerprint = (query["fp"] ?: query["fingerprint"]).orEmpty().lowercase().trim()
            val fingerprint = requestedFingerprint.takeIf { it in VALID_FINGERPRINTS }
                ?: defaultFingerprint
            if (fingerprint in VALID_FINGERPRINTS) put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint))
            query["alpn"]?.let { values ->
                val alpn = values.split(",").map { decodeResidualPercent(it.trim()) }.filter(String::isNotBlank)
                if (alpn.isNotEmpty()) put("alpn", JSONArray(alpn))
            }
            if (reality) {
                val publicKey = query["pbk"] ?: query["public-key"]
                if (isValidRealityPublicKey(publicKey)) {
                    put("reality", JSONObject().put("enabled", true).put("public_key", publicKey).apply {
                        val shortId = query["sid"] ?: query["short-id"]
                        if (isValidRealityShortId(shortId)) put("short_id", shortId)
                    })
                }
            }
        }
    }

    private fun buildTransport(query: Map<String, String>): JSONObject? {
        return when (query["type"]?.lowercase()) {
            "ws", "websocket" -> JSONObject().put("type", "ws").apply {
                query["path"]?.let { put("path", decodeResidualPercent(it)) }
                query["host"]?.let { host -> put("headers", JSONObject().put("Host", host)) }
            }
            "grpc" -> JSONObject().put("type", "grpc").apply {
                query["serviceName"]?.let { put("service_name", it) }
                query["service-name"]?.let { put("service_name", it) }
            }
            "httpupgrade", "http-upgrade" -> JSONObject().put("type", "httpupgrade").apply {
                query["path"]?.let { put("path", decodeResidualPercent(it)) }
                query["host"]?.let { put("host", it) }
            }
            "xhttp" -> JSONObject().put("type", "xhttp").apply {
                query["path"]?.let { put("path", it) }
                query["host"]?.let { put("host", it) }
                // Both checked, not copied as-is: a value the core rejects would fail every
                // node tested alongside this one (see OutboundFieldRules).
                OutboundFieldRules.xhttpMode(query["mode"])?.let { put("mode", it) }
                OutboundFieldRules.range("x_padding_bytes", query["xPaddingBytes"] ?: query["x-padding-bytes"])
                    ?.let { put("x_padding_bytes", it) }
                (query["noGRPCHeader"] ?: query["no-grpc-header"])?.let { if (isTrue(it)) put("no_grpc_header", true) }
            }
            else -> null
        }
    }

    private fun buildHysteriaObfs(query: Map<String, String>): JSONObject? {
        val type = query["obfs"]?.lowercase()?.takeIf { it == "salamander" || it == "gecko" } ?: return null
        return JSONObject().put("type", type).apply {
            query["obfs-password"]?.let { put("password", it) }
        }
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        raw.split("&").forEach { part ->
            if (part.isBlank()) return@forEach
            val separator = part.indexOf("=")
            val key = if (separator >= 0) part.substring(0, separator) else part
            val value = if (separator >= 0) part.substring(separator + 1) else ""
            result[decode(key)] = decode(value)
        }
        return result
    }

    private fun decode(value: String?): String = value?.let {
        runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrDefault(it)
    }.orEmpty()

    /**
     * Makes a share link acceptable to java.net.URI without changing what it says.
     *
     * URI is strict and throws on characters other clients take as they are - `{ } " | \ ^`
     * and the backtick, `< >`, spaces, control characters, and a `%` that isn't followed by
     * two hex digits - and parseUri() then drops the node without a word. Real lists carry
     * them: `fm={"tcp":...}` on Cloudflare Workers nodes, `host=%`. Each such character
     * becomes its %XX form, which parseQuery()/decode() turn straight back into the same
     * character, so the values that end up in the config are exactly the link's.
     *
     * `+` is escaped as well, as before: URLDecoder would read it as a space, and in these
     * links it is data (passwords, paths, base64). `[`/`]` are left alone - URI accepts
     * them, and they are how an IPv6 host is written.
     */
    private fun escapeForUri(value: String): String {
        val out = StringBuilder(value.length + 16)
        for ((index, c) in value.withIndex()) {
            when {
                c == '+' -> out.append("%2B")
                c == '%' -> if (isPercentEscape(value, index)) out.append(c) else out.append("%25")
                c.code < 0x80 -> if (c.isLetterOrDigit() || c in URI_ASCII_SYMBOLS) out.append(c) else appendPercentEncoded(out, c)
                Character.isISOControl(c) || Character.isSpaceChar(c) -> appendPercentEncoded(out, c)
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private fun isPercentEscape(value: String, index: Int): Boolean =
        index + 2 < value.length && isHexDigit(value[index + 1]) && isHexDigit(value[index + 2])

    private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun appendPercentEncoded(out: StringBuilder, c: Char) {
        for (byte in c.toString().toByteArray(StandardCharsets.UTF_8)) {
            val v = byte.toInt() and 0xFF
            out.append('%').append(HEX_DIGITS[v shr 4]).append(HEX_DIGITS[v and 0x0F])
        }
    }

    private fun decodeResidualPercent(value: String): String {
        var current = value
        repeat(4) {
            if (!Regex("%[0-9A-Fa-f]{2}").containsMatchIn(current)) return current
            val next = runCatching {
                URLDecoder.decode(current.replace("+", "%2B"), StandardCharsets.UTF_8.name())
            }.getOrNull() ?: return current
            if (next == current) return current
            current = next
        }
        return current
    }

    private fun parseMbps(value: String?): Int? {
        val raw = value?.trim()?.lowercase() ?: return null
        val number = raw.removeSuffix("mbps").removeSuffix("mb/s").trim().toDoubleOrNull() ?: return null
        return number.toInt().takeIf { it > 0 }
    }

    private fun isTlsSecurity(query: Map<String, String>, port: Int): Boolean = when (query["security"]?.lowercase()) {
        "none" -> false
        "tls", "reality" -> true
        null, "" -> port !in PLAINTEXT_VLESS_PORTS
        else -> true
    }

    private fun isTrue(value: String): Boolean = value.lowercase() in setOf("true", "1", "yes")

    private fun isValidRealityPublicKey(value: String?): Boolean {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return false
        val padded = raw + "=".repeat((4 - raw.length % 4) % 4)
        val bytes = runCatching {
            Base64Compat.decodeUrl(padded)
        }.getOrNull() ?: return false
        return bytes.size == 32
    }

    private fun isValidRealityShortId(value: String?): Boolean {
        val raw = value?.trim()?.lowercase().orEmpty()
        return raw.isNotEmpty() && raw.length <= 16 && raw.length % 2 == 0 && raw.all { it in "0123456789abcdef" }
    }

    private fun node(name: String, protocol: Protocol, server: String, port: Int, uuid: String? = null, password: String? = null, raw: String): Node =
        Node(name = name, protocol = protocol, server = server, port = port, uuid = uuid, password = password, rawConfig = raw)

    private const val TAG = "SubscriptionParser"
    private const val MAX_URI_CHARS_PER_LINE = 64 * 1024
    // ASCII punctuation java.net.URI accepts unescaped: RFC 2396 marks and reserved
    // characters, plus the brackets RFC 2732 added. '%' and '+' are handled separately.
    private const val URI_ASCII_SYMBOLS = "-_.!~*'();/?:@&=\$,[]"
    private const val HEX_DIGITS = "0123456789ABCDEF"
    private val VALID_PACKET_ENCODINGS = setOf("xudp")
    private val PLAINTEXT_VLESS_PORTS = setOf(80, 8080, 8880, 2052, 2082, 2086, 2095)
    private val VALID_FINGERPRINTS = setOf(
        "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle",
        "chrome_pq", "chrome_pq_psk", "chrome", "firefox", "edge",
        "safari", "360", "qq", "ios", "android", "random", "randomized"
    )
}
