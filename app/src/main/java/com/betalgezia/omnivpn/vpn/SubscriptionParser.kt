package com.betalgezia.omnivpn.vpn

import android.util.Base64
import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object SubscriptionParser {
    fun parse(input: String): List<Node> = input.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(";") }
        .flatMap { line ->
            runCatching { parseUri(line) }.getOrNull()?.let(::listOf) ?: emptyList()
        }

    private fun parseUri(value: String): Node? {
        val normalized = value.trim()
        val uri = runCatching { URI(encodePlus(normalized)) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        return when (scheme) {
            "vless" -> parseVless(uri)
            "trojan" -> parseTrojan(uri)
            "hysteria2", "hy2" -> parseHysteria2(uri)
            else -> null
        }
    }

    private fun parseVless(uri: URI): Node? {
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
        val raw = JSONObject()
            .put("type", "vless")
            .put("server", server)
            .put("server_port", port)
            .put("uuid", uuid)
            .apply {
                if (flow.isNotBlank() && !visionWithTransport && flow == "xtls-rprx-vision") {
                    put("flow", flow)
                }
                if (packetEncoding in VALID_PACKET_ENCODINGS) put("packet_encoding", packetEncoding)
                tls?.let { put("tls", it) }
                transport?.let {
                    put("transport", it)
                    when (it.optString("type")) {
                        "ws", "httpupgrade", "xhttp", "grpc" -> put("network", it.optString("type"))
                    }
                }
            }
        return node(fragment(uri, "$server:$port"), Protocol.VLESS, server, port, uuid = uuid, raw = raw.toString())
    }

    private fun parseTrojan(uri: URI): Node? {
        val server = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: return null
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
                transport?.let {
                    put("transport", it)
                    put("network", it.optString("type"))
                }
            }
        return node(fragment(uri, "$server:$port"), Protocol.TROJAN, server, port, password = password, raw = raw.toString())
    }

    private fun parseHysteria2(uri: URI): Node? {
        val server = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: return null
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
        return node(fragment(uri, "$server:$port"), Protocol.HYSTERIA2, server, port, password = password, raw = raw.toString())
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
                val alpn = values.split(",").map(String::trim).filter(String::isNotBlank)
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
                query["path"]?.let { put("path", it) }
                query["host"]?.let { host -> put("headers", JSONObject().put("Host", host)) }
            }
            "grpc" -> JSONObject().put("type", "grpc").apply {
                query["serviceName"]?.let { put("service_name", it) }
                query["service-name"]?.let { put("service_name", it) }
            }
            "httpupgrade", "http-upgrade" -> JSONObject().put("type", "httpupgrade").apply {
                query["path"]?.let { put("path", it) }
                query["host"]?.let { put("host", it) }
            }
            "xhttp" -> JSONObject().put("type", "xhttp").apply {
                query["path"]?.let { put("path", it) }
                query["host"]?.let { put("host", it) }
                query["mode"]?.let { put("mode", it) }
                (query["xPaddingBytes"] ?: query["x-padding-bytes"])?.let { put("x_padding_bytes", it) }
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

    private fun encodePlus(value: String): String = value.replace("+", "%2B")

    private fun fragment(uri: URI, fallback: String): String = decode(uri.rawFragment).takeIf { it.isNotBlank() } ?: fallback

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
            Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull() ?: return false
        return bytes.size == 32
    }

    private fun isValidRealityShortId(value: String?): Boolean {
        val raw = value?.trim()?.lowercase().orEmpty()
        return raw.isNotEmpty() && raw.length <= 16 && raw.length % 2 == 0 && raw.all { it in "0123456789abcdef" }
    }

    private fun node(name: String, protocol: Protocol, server: String, port: Int, uuid: String? = null, password: String? = null, raw: String): Node =
        Node(name = name, protocol = protocol, server = server, port = port, uuid = uuid, password = password, rawConfig = raw)

    private val VALID_PACKET_ENCODINGS = setOf("xudp")
    private val PLAINTEXT_VLESS_PORTS = setOf(80, 8080, 8880, 2052, 2082, 2086, 2095)
    private val VALID_FINGERPRINTS = setOf(
        "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle",
        "chrome_pq", "chrome_pq_psk", "chrome", "firefox", "edge",
        "safari", "360", "qq", "ios", "android", "random", "randomized"
    )
}
