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
    fun parse(input: String): List<Node> {
        val text = decodeSubscriptionBody(input)
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") && !it.startsWith(";") }
            .mapNotNull(::parseLine)
            .toList()
    }

    private fun parseLine(line: String): Node? {
        val uri = line.trim()
        val scheme = uri.substringBefore("://", "").lowercase()
        return runCatching {
            when (scheme) {
                "vless" -> parseVless(uri)
                "trojan" -> parseTrojan(uri)
                "hysteria2", "hy2" -> parseHysteria2(uri)
                else -> null
            }
        }.getOrNull()
    }

    private fun parseVless(raw: String): Node? {
        val u = URI(raw)
        val server = u.host ?: return null
        val port = if (u.port > 0) u.port else 443
        val uuid = decode(u.rawUserInfo?.substringBefore(':').orEmpty())
        if (uuid.isBlank()) return null
        val q = query(u.rawQuery)
        val name = label(u.rawFragment).ifBlank { "$server:$port" }
        val out = JSONObject().put("type", "vless")
            .put("server", server).put("server_port", port).put("uuid", uuid)

        q["flow"]?.trim()?.takeIf { it == "xtls-rprx-vision" && q["type"].isNullOrBlank() }?.let { out.put("flow", it) }
        q["packetEncoding"]?.trim()?.takeIf { it == "xudp" || it == "packetaddr" }?.let { out.put("packet_encoding", it) }
        buildTransport(q)?.let { out.put("transport", it) }
        buildVlessTls(q, server)?.let { out.put("tls", it) }
        return node(name, Protocol.VLESS, server, port, uuid = uuid, raw = out.toString())
    }

    private fun parseTrojan(raw: String): Node? {
        val u = URI(raw)
        val server = u.host ?: return null
        val port = if (u.port > 0) u.port else 443
        val password = decode(u.rawUserInfo.orEmpty())
        if (password.isBlank()) return null
        val q = query(u.rawQuery)
        val name = label(u.rawFragment).ifBlank { "$server:$port" }
        val out = JSONObject().put("type", "trojan")
            .put("server", server).put("server_port", port).put("password", password)
        buildTransport(q)?.let { out.put("transport", it) }
        buildTrojanTls(q, server)?.let { out.put("tls", it) }
        return node(name, Protocol.TROJAN, server, port, password = password, raw = out.toString())
    }

    private fun parseHysteria2(raw: String): Node? {
        val normalized = if (raw.startsWith("hy2://", ignoreCase = true)) "hysteria2://${raw.substring(6)}" else raw
        val u = URI(normalized)
        val server = u.host ?: return null
        val port = if (u.port > 0) u.port else 443
        val password = decode(u.rawUserInfo.orEmpty())
        val q = query(u.rawQuery)
        val name = label(u.rawFragment).ifBlank { "$server:$port" }
        val out = JSONObject().put("type", "hysteria2")
            .put("server", server).put("server_port", port)
        if (password.isNotBlank()) out.put("password", password)
        q["upmbps"]?.toIntOrNull()?.takeIf { it >= 0 }?.let { out.put("up_mbps", it) }
        q["downmbps"]?.toIntOrNull()?.takeIf { it >= 0 }?.let { out.put("down_mbps", it) }
        buildHysteria2Obfs(q)?.let { out.put("obfs", it) }
        out.put("tls", buildHysteria2Tls(q, server))
        return node(name, Protocol.HYSTERIA2, server, port, password = password, raw = out.toString())
    }

    private fun buildVlessTls(q: Map<String, String>, server: String): JSONObject? {
        val security = q["security"]?.lowercase()?.trim().orEmpty()
        if (security == "none") return null
        if (security.isBlank() && q["sni"].isNullOrBlank() && q["fp"].isNullOrBlank() && q["pbk"].isNullOrBlank() && !q.containsKey("allowInsecure")) return null

        val tls = JSONObject().put("enabled", true)
            .put("server_name", q["sni"] ?: q["peer"] ?: server)
        if (isTruthy(q["allowInsecure"] ?: q["insecure"])) tls.put("insecure", true)
        q["alpn"]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.takeIf { it.isNotEmpty() }?.let { tls.put("alpn", JSONArray(it)) }
        q["fp"]?.trim()?.takeIf { it.isNotEmpty() }?.let { tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it)) }

        val pbk = q["pbk"]?.trim().orEmpty()
        if (security == "reality" && pbk.isNotBlank() && validX25519PublicKey(pbk)) {
            val reality = JSONObject().put("enabled", true).put("public_key", pbk)
            q["sid"]?.trim()?.takeIf { it.length <= 32 && it.length % 2 == 0 && it.all(Char::isHexDigit) }?.let { reality.put("short_id", it) }
            tls.put("reality", reality)
        }
        return tls
    }

    private fun buildTrojanTls(q: Map<String, String>, server: String): JSONObject? {
        if (q["security"]?.lowercase()?.trim() == "none") return null
        val hasHints = q["sni"] != null || q["peer"] != null || q["host"] != null || q["fp"] != null || q["alpn"] != null || q["allowInsecure"] != null || q["insecure"] != null || q["security"] != null
        if (!hasHints) return JSONObject().put("enabled", true).put("server_name", server)
        val tls = JSONObject().put("enabled", true).put("server_name", q["sni"] ?: q["peer"] ?: q["host"] ?: server)
        if (isTruthy(q["allowInsecure"] ?: q["insecure"])) tls.put("insecure", true)
        q["alpn"]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.takeIf { it.isNotEmpty() }?.let { tls.put("alpn", JSONArray(it)) }
        q["fp"]?.trim()?.takeIf { it.isNotEmpty() }?.let { tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it)) }
        return tls
    }

    private fun buildHysteria2Tls(q: Map<String, String>, server: String): JSONObject = JSONObject()
        .put("enabled", true)
        .put("server_name", q["sni"]?.takeIf { it.isNotBlank() } ?: server)
        .apply {
            if (isTruthy(q["insecure"] ?: q["allowInsecure"])) put("insecure", true)
            q["alpn"]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.takeIf { it.isNotEmpty() }?.let { put("alpn", JSONArray(it)) }
        }

    private fun buildHysteria2Obfs(q: Map<String, String>): JSONObject? {
        val type = q["obfs"]?.lowercase()?.trim().orEmpty()
        if (type.isEmpty()) return null
        if (type != "salamander" && type != "gecko") return null
        val obfs = JSONObject().put("type", type)
        q["obfs-password"]?.takeIf { it.isNotBlank() }?.let { obfs.put("password", it) }
        q["obfs-min-packet-size"]?.toIntOrNull()?.takeIf { it >= 0 }?.let { obfs.put("min_packet_size", it) }
        q["obfs-max-packet-size"]?.toIntOrNull()?.takeIf { it >= 0 }?.let { obfs.put("max_packet_size", it) }
        return obfs
    }

    private fun buildTransport(q: Map<String, String>): JSONObject? {
        val type = q["type"]?.lowercase()?.trim() ?: return null
        return when (type) {
            "tcp" -> null
            "ws" -> JSONObject().put("type", "ws")
                .apply { q["path"]?.let { put("path", it) }; q["host"]?.takeIf { it.isNotBlank() }?.let { put("headers", JSONObject().put("Host", it)) } }
            "grpc" -> JSONObject().put("type", "grpc")
                .put("service_name", q["serviceName"] ?: q["service_name"] ?: q["path"] ?: "")
            "httpupgrade" -> JSONObject().put("type", "httpupgrade")
                .apply { q["path"]?.let { put("path", it) }; q["host"]?.takeIf { it.isNotBlank() }?.let { put("host", JSONArray(it.split(',').map(String::trim).filter(String::isNotEmpty))) } }
            "xhttp" -> JSONObject().put("type", "xhttp")
                .apply {
                    q["path"]?.let { put("path", it) }
                    q["host"]?.takeIf { it.isNotBlank() }?.let { put("host", it) }
                    q["mode"]?.takeIf { it.isNotBlank() }?.let { put("mode", it) }
                    q["xPaddingBytes"]?.takeIf { it.isNotBlank() }?.let { put("x_padding_bytes", it) }
                    q["noGRPCHeader"]?.let { if (isTruthy(it)) put("no_grpc_header", true) }
                }
            else -> null
        }
    }

    private fun node(name: String, protocol: Protocol, server: String, port: Int, uuid: String? = null, password: String? = null, raw: String): Node =
        Node(name = name, protocol = protocol, server = server, port = port, uuid = uuid, password = password, rawConfig = raw)

    private fun query(raw: String?): Map<String, String> = raw.orEmpty().split('&')
        .mapNotNull { pair -> if (pair.isBlank()) null else { val p = pair.indexOf('='); val k = if (p >= 0) pair.substring(0,p) else pair; val v = if (p >= 0) pair.substring(p+1) else ""; decode(k) to decode(v) } }
        .toMap()

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
    private fun label(fragment: String?): String = decode(fragment.orEmpty())

    private fun decodeSubscriptionBody(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.contains("://")) return trimmed
        val compact = trimmed.replace("\\s".toRegex(), "")
        if (compact.length < 16 || compact.any { it !in BASE64_CHARS }) return trimmed
        val decoded = runCatching {
            val bytes = Base64.decode(compact, Base64.DEFAULT or Base64.NO_WRAP)
            bytes.toString(StandardCharsets.UTF_8)
        }.getOrNull() ?: return trimmed
        return if (decoded.contains("://")) decoded else trimmed
    }

    private fun validX25519PublicKey(value: String): Boolean = runCatching {
        Base64.decode(value, Base64.DEFAULT or Base64.NO_WRAP or Base64.URL_SAFE).size == 32
    }.getOrDefault(false)

    private fun isTruthy(value: String?): Boolean = value?.lowercase()?.trim() in setOf("1", "true", "yes")

    private const val BASE64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=_-"
}