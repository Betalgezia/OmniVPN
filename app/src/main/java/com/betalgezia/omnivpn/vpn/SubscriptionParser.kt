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
        val remark = rawRemark?.let(::decode)?.takeIf { it.isNotBlank() }
        // vmess:// and ss:// carry base64, which can contain '/' - java.net.URI would read
        // that as the start of a path - so those two are taken apart by hand.
        when (uriPart.substringBefore("://", "").lowercase()) {
            "vmess" -> return parseVmess(uriPart.substringAfter("://"), remark)
            "ss" -> return parseShadowsocks(uriPart.substringAfter("://"), remark)
        }
        val uri = runCatching { URI(escapeForUri(uriPart)) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        return when (scheme) {
            "vless" -> parseVless(uri, remark)
            "trojan" -> parseTrojan(uri, remark)
            "hysteria2", "hy2" -> parseHysteria2(uri, remark)
            "tuic" -> parseTuic(uri, remark)
            else -> null
        }
    }

    /**
     * `ss://` in both shapes in use. SIP002: `ss://userinfo@host:port/?plugin=...`, where
     * userinfo is base64 of `method:password`, or the two percent-encoded as they are (how
     * Shadowsocks 2022 links write it). The older shape: everything after `ss://` is base64
     * of `method:password@host:port`.
     *
     * A link the core couldn't run is skipped rather than kept: an unknown method, a
     * Shadowsocks 2022 key of the wrong size, or a plugin the core doesn't have - the server
     * expects that plugin, so without it the node can only fail.
     */
    private fun parseShadowsocks(body: String, remark: String?): Node? {
        val main = body.substringBefore('?')
        val query = if ('?' in body) parseQuery(body.substringAfter('?')) else emptyMap()
        val credentialsAndAddress = if ('@' in main) {
            val at = main.lastIndexOf('@')
            val userInfo = percentDecode(main.substring(0, at))
            val credentials = if (':' in userInfo) userInfo else decodeBase64Text(userInfo) ?: return null
            // The address may end in the "/" SIP002 puts before "?"; parseHostPort drops it.
            "$credentials@${main.substring(at + 1)}"
        } else {
            // Not trimmed up front: '/' is a base64 character, and can end the string.
            val encoded = percentDecode(main)
            decodeBase64Text(encoded) ?: decodeBase64Text(encoded.trimEnd('/')) ?: return null
        }
        val at = credentialsAndAddress.lastIndexOf('@').takeIf { it > 0 } ?: return null
        val credentials = credentialsAndAddress.substring(0, at)
        val (server, port) = parseHostPort(credentialsAndAddress.substring(at + 1)) ?: return null
        val method = OutboundFieldRules.shadowsocksMethod(credentials.substringBefore(':'))
            ?: return skipped("Shadowsocks method the core doesn't have")
        val password = credentials.substringAfter(':', "")
        if (!OutboundFieldRules.isValidShadowsocksPassword(method, password)) {
            return skipped("Shadowsocks password/key the core won't take for $method")
        }
        val plugin = query["plugin"]?.trim()?.takeIf { it.isNotEmpty() }?.let { spec ->
            OutboundFieldRules.shadowsocksPlugin(spec.substringBefore(';'), spec.substringAfter(';', ""))
                ?: return skipped("Shadowsocks plugin the core doesn't have: ${spec.substringBefore(';')}")
        }
        val raw = JSONObject()
            .put("type", "shadowsocks")
            .put("server", server)
            .put("server_port", port)
            .put("method", method)
            .put("password", password)
            .apply {
                plugin?.let { (name, options) ->
                    put("plugin", name)
                    if (options.isNotEmpty()) put("plugin_opts", options)
                }
            }
        return node(remark ?: "$server:$port", Protocol.SHADOWSOCKS, server, port, password = password, raw = raw.toString())
    }

    /**
     * `vmess://` in v2rayN's format, the one in practical use: base64 of a JSON object with
     * add, port, id, aid, scy, net, type, host, path, tls, sni, alpn, fp. Transports the core
     * has are taken (tcp, ws, grpc, h2/http, httpupgrade, xhttp); a node on mKCP, QUIC or
     * TCP with an HTTP header is skipped, as it could only fail.
     */
    private fun parseVmess(body: String, remark: String?): Node? {
        val json = decodeBase64Text(percentDecode(body))?.trim()?.takeIf { it.startsWith("{") } ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        fun field(key: String): String =
            obj.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()?.trim().orEmpty()
        val server = field("add").takeIf { it.isNotEmpty() } ?: return null
        val port = field("port").toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val uuid = field("id").takeIf { it.isNotEmpty() } ?: return null
        val net = field("net").lowercase().ifEmpty { "tcp" }
        val headerType = field("type").lowercase()
        val host = field("host")
        val path = field("path")
        val tlsOn = field("tls").lowercase() == "tls"
        // Put into the shape a VLESS/Trojan link's query has, so the same transport and
        // TLS builders apply.
        val query = linkedMapOf<String, String>()
        when (net) {
            "tcp" -> if (headerType == "http") return skipped("VMess over TCP with an HTTP header")
            "ws", "websocket", "httpupgrade", "http-upgrade", "h2", "http", "xhttp" -> query["type"] = net
            "grpc" -> {
                query["type"] = "grpc"
                if (path.isNotEmpty()) query["serviceName"] = path
            }
            else -> return skipped("VMess transport the core doesn't have: $net")
        }
        if (path.isNotEmpty() && net != "grpc") query["path"] = path
        if (host.isNotEmpty()) query["host"] = host
        if (net == "xhttp" && headerType.isNotEmpty()) query["mode"] = headerType
        if (tlsOn) {
            // No sni: the host header's name, not the address - behind a CDN `add` is often
            // just an IP of the CDN.
            field("sni").ifEmpty { host.substringBefore(',').trim() }.takeIf { it.isNotEmpty() }?.let { query["sni"] = it }
            field("alpn").takeIf { it.isNotEmpty() }?.let { query["alpn"] = it }
            field("fp").takeIf { it.isNotEmpty() }?.let { query["fp"] = it }
            if (isTrue(field("allowInsecure")) || isTrue(field("insecure"))) query["insecure"] = "1"
        }
        val raw = JSONObject()
            .put("type", "vmess")
            .put("server", server)
            .put("server_port", port)
            .put("uuid", uuid)
            .put("security", OutboundFieldRules.vmessSecurity(field("scy")))
            .put("alter_id", field("aid").toIntOrNull()?.takeIf { it >= 0 } ?: 0)
            .apply {
                buildTls(query, server, defaultEnabled = tlsOn, defaultFingerprint = "", inferFromTlsParams = false)
                    ?.let { put("tls", it) }
                buildTransport(query)?.let { put("transport", it) }
            }
        val name = remark ?: field("ps").takeIf { it.isNotEmpty() } ?: "$server:$port"
        return node(name, Protocol.VMESS, server, port, uuid = uuid, raw = raw.toString())
    }

    /**
     * `tuic://uuid:password@host:port?congestion_control=bbr&udp_relay_mode=native&alpn=h3&sni=...`
     * (TUIC v5, the only version the core speaks). TUIC runs over QUIC, so TLS is always on,
     * without uTLS (it doesn't apply to QUIC), and with ALPN h3 unless the link names one -
     * QUIC can't complete a handshake without an ALPN.
     */
    private fun parseTuic(uri: URI, remark: String?): Node? {
        val server = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: return null
        val userInfo = decode(uri.rawUserInfo)
        val uuid = userInfo.substringBefore(':').trim().takeIf { it.isNotEmpty() } ?: return null
        val password = userInfo.substringAfter(':', "").takeIf { it.isNotEmpty() } ?: return null
        val query = parseQuery(uri.rawQuery)
        val tls = (buildTls(query, server, defaultEnabled = true, defaultFingerprint = "")
            ?: JSONObject().put("enabled", true).put("server_name", server)).apply {
            remove("utls")
            remove("reality")
            if (!has("alpn")) put("alpn", JSONArray().put("h3"))
            if ((query["allow_insecure"] ?: query["allow-insecure"])?.let(::isTrue) == true) put("insecure", true)
            if ((query["disable_sni"] ?: query["disable-sni"])?.let(::isTrue) == true) put("disable_sni", true)
        }
        val raw = JSONObject()
            .put("type", "tuic")
            .put("server", server)
            .put("server_port", port)
            .put("uuid", uuid)
            .put("password", password)
            .put("tls", tls)
            .apply {
                OutboundFieldRules.tuicCongestionControl(
                    query["congestion_control"] ?: query["congestion-control"] ?: query["congestion_controller"]
                )?.let { put("congestion_control", it) }
                OutboundFieldRules.tuicUdpRelayMode(query["udp_relay_mode"] ?: query["udp-relay-mode"])
                    ?.let { put("udp_relay_mode", it) }
            }
        return node(remark ?: "$server:$port", Protocol.TUIC, server, port, uuid = uuid, password = password, raw = raw.toString())
    }

    /** `host:port` or `[ipv6]:port`; null unless both are there and the port is valid. */
    private fun parseHostPort(text: String): Pair<String, Int>? {
        val value = text.trim().trimEnd('/')
        val (host, portText) = if (value.startsWith("[")) {
            val close = value.indexOf(']').takeIf { it > 1 } ?: return null
            value.substring(1, close) to value.substring(close + 1).removePrefix(":")
        } else {
            value.substringBeforeLast(':', "") to value.substringAfterLast(':', "")
        }
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return host.trim().takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }?.let { it to port }
    }

    /** Base64 (standard or URL-safe, padded or not) as UTF-8 text; null if it isn't. */
    private fun decodeBase64Text(value: String): String? {
        val compact = value.filterNot(Char::isWhitespace)
        if (compact.isEmpty()) return null
        return runCatching { Base64Compat.decode(compact).toString(StandardCharsets.UTF_8) }.getOrNull()
    }

    /** Percent-decoding that leaves '+' alone - it is data in base64 and passwords. */
    private fun percentDecode(value: String): String =
        if ('%' !in value) value else decode(value.replace("+", "%2B"))

    // Never logs the link itself (it carries credentials) - only why it was left out.
    private fun skipped(reason: String): Node? {
        runCatching { android.util.Log.w(TAG, "skipped a link: $reason") }
        return null
    }

    private fun parseVless(uri: URI, remark: String?): Node? {
        val server = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = if (uri.port == -1) 443 else uri.port.takeIf { it in 1..65535 } ?: return null
        val uuid = decode(uri.rawUserInfo).substringBefore(":").takeIf { it.isNotBlank() } ?: return null
        val query = parseQuery(uri.rawQuery)
        // Post-quantum VLESS encryption. A server that has it set talks to nobody without
        // it, so it is carried into the config - and a link whose string the core can't
        // parse is skipped: without the string the node can only fail, and with it the
        // core refuses the whole config, failing every node tested alongside it.
        val encryption = query["encryption"]?.trim().orEmpty()
        val usesEncryption = encryption.isNotEmpty() && !encryption.equals("none", ignoreCase = true)
        if (usesEncryption && !OutboundFieldRules.isValidVlessEncryption(encryption)) {
            runCatching { android.util.Log.w(TAG, "parseVless: skipped a node with an encryption string sing-box-lx can't parse") }
            return null
        }
        var flow = query["flow"]?.trim().orEmpty()
        var packetEncoding = query["packetEncoding"] ?: query["packet_encoding"] ?: ""
        if (flow == "xtls-rprx-vision-udp443") {
            flow = "xtls-rprx-vision"
            packetEncoding = "xudp"
        }
        val tls = buildTls(query, server, defaultEnabled = vlessAsksForTls(query), defaultFingerprint = "random", inferFromTlsParams = false)
        val transport = buildTransport(query)
        val visionWithTransport = flow == "xtls-rprx-vision" && transport != null
        val visionValid = flow == "xtls-rprx-vision" && !visionWithTransport && tls != null
        val raw = JSONObject()
            .put("type", "vless")
            .put("server", server)
            .put("server_port", port)
            .put("uuid", uuid)
            .apply {
                if (usesEncryption) put("encryption", encryption)
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

    /**
     * [inferFromTlsParams]: whether sni/alpn/fp/insecure alone turn TLS on. False for VLESS,
     * whose links say TLS only through `security` (see [vlessAsksForTls]).
     */
    private fun buildTls(
        query: Map<String, String>,
        server: String,
        defaultEnabled: Boolean,
        defaultFingerprint: String,
        inferFromTlsParams: Boolean = true
    ): JSONObject? {
        val security = query["security"]?.lowercase()
        if (security == "none") return null
        val reality = security == "reality" || !query["pbk"].isNullOrBlank() || !query["public-key"].isNullOrBlank()
        val impliedByParams = inferFromTlsParams && (
            query["sni"] != null || query["servername"] != null || query["alpn"] != null ||
                query["insecure"] != null || query["allowInsecure"] != null ||
                query["fp"] != null || query["fingerprint"] != null
            )
        val enabled = defaultEnabled || security == "tls" || reality || impliedByParams
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
            // HTTP/2 - what Xray links call type=http (and VMess net=h2). Used to fall through
            // to "no transport", which can only fail against such a server.
            "http", "h2" -> JSONObject().put("type", "http").apply {
                query["path"]?.let { put("path", decodeResidualPercent(it)) }
                query["host"]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.takeIf { it.isNotEmpty() }
                    ?.let { put("host", JSONArray(it)) }
            }
            "xhttp" -> JSONObject().put("type", "xhttp").apply {
                // Xray share links keep host, path and mode at the top level and every other
                // xhttp setting in `extra`, a JSON object; it used to be ignored, losing xmux
                // (connection reuse - speed and stability) and the request-shape settings
                // some servers/CDNs need. A setting also given at the top level wins.
                val extra = parseXhttpExtra(query["extra"])
                fun fromExtra(key: String): Any? = extra?.opt(key)?.takeUnless { it == JSONObject.NULL }
                query["path"]?.let { put("path", it) }
                query["host"]?.let { put("host", it) }
                // Checked, not copied as-is: a value the core rejects would fail every node
                // tested alongside this one (see OutboundFieldRules).
                OutboundFieldRules.xhttpMode(query["mode"])?.let { put("mode", it) }
                OutboundFieldRules.range(
                    "x_padding_bytes",
                    query["xPaddingBytes"] ?: query["x-padding-bytes"] ?: fromExtra("xPaddingBytes")
                )?.let { put("x_padding_bytes", it) }
                val noGrpcHeader = (query["noGRPCHeader"] ?: query["no-grpc-header"])?.let(::isTrue)
                    ?: (fromExtra("noGRPCHeader")?.toString()?.let(::isTrue) ?: false)
                if (noGrpcHeader) put("no_grpc_header", true)
                (fromExtra("xmux") as? JSONObject)
                    ?.let { xmux -> OutboundFieldRules.xmux { key -> xmux.opt(key)?.takeUnless { it == JSONObject.NULL } } }
                    ?.let { put("xmux", it) }
            }
            else -> null
        }
    }

    /**
     * The xhttp `extra` parameter as a JSON object; null if absent, "null" or unreadable.
     *
     * Some generators form-encode it, so its spaces arrive as `+`, and parseQuery keeps
     * `+` literal (it is data in passwords and paths) - leaving `{"a":+1,+"b":+true}`.
     * Valid JSON never has a `+` outside a string except in an exponent, so one there
     * means the whole thing was form-encoded and every `+` in it is a space. Checking that
     * up front, rather than only retrying after a parse error, matters because org.json is
     * lenient: `{"noGRPCHeader":+true}` "parses" with the string "+true" in it.
     */
    private fun parseXhttpExtra(raw: String?): JSONObject? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        val json = if (hasPlusOutsideJsonStrings(text)) text.replace('+', ' ') else text
        return runCatching { JSONObject(json) }.getOrNull()
            ?: runCatching { JSONObject(text.replace('+', ' ')) }.getOrNull()
    }

    private fun hasPlusOutsideJsonStrings(text: String): Boolean {
        var inString = false
        var escaped = false
        for ((index, c) in text.withIndex()) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else if (c == '"') {
                inString = true
            } else if (c == '+' && (index == 0 || text[index - 1] !in "eE")) {
                return true
            }
        }
        return false
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

    /**
     * A VLESS link without `security` means no TLS - that is the XTLS share-link standard,
     * and what v2rayN and other clients do. This used to turn TLS on for any port outside
     * a short list of HTTP ports, and for any sni/fp/alpn parameter (e.g. an `sni` on
     * Cloudflare's plain-HTTP port 2052), so such nodes opened TLS to a server that never
     * spoke it. Now only security=tls|reality asks for it; a Reality key (pbk) still means
     * Reality. Trojan and Hysteria2 keep TLS by default, which is their standard.
     */
    private fun vlessAsksForTls(query: Map<String, String>): Boolean =
        query["security"]?.trim()?.lowercase() in setOf("tls", "reality")

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
    private val VALID_FINGERPRINTS = setOf(
        "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle",
        "chrome_pq", "chrome_pq_psk", "chrome", "firefox", "edge",
        "safari", "360", "qq", "ios", "android", "random", "randomized"
    )
}
