package com.betalgezia.omnivpn.vpn

import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Protocol
import org.json.JSONArray
import org.json.JSONObject

data class WarpAccount(
    val privateKey: String,
    val peerPublicKey: String,
    val clientV4: String,
    val clientV6: String,
    val clientId: String?,
    val accountId: String,
    val deviceId: String,
    val token: String,
    val license: String?,
    val warpPlus: Boolean,
    val endpoint: String,
    val createdAt: String
) {
    fun toSingBoxEndpoint(): String {
        val parts = splitEndpoint(endpoint)
        val peer = JSONObject()
            .put("address", parts.first)
            .put("port", parts.second)
            .put("public_key", peerPublicKey)
            .put("allowed_ips", JSONArray().apply { put("0.0.0.0/0"); put("::/0") })
        reservedBytes()?.let { bytes ->
            peer.put("reserved", JSONArray().apply { bytes.forEach { put(it) } })
        }
        return JSONObject()
            .put("type", "wireguard")
            .put("tag", "warp")
            .put("mtu", 1280)
            .put("address", JSONArray().apply { put(normalizeAddress(clientV4, 32)); put(normalizeAddress(clientV6, 128)) })
            .put("private_key", privateKey)
            .put("peers", JSONArray().put(peer))
            .toString()
    }

    fun toNode(): Node {
        val parts = splitEndpoint(endpoint)
        return Node(
            name = if (warpPlus) "WARP+" else "WARP",
            protocol = Protocol.AMNEZIAWG,
            server = parts.first,
            port = parts.second,
            privateKey = privateKey,
            rawConfig = toSingBoxEndpoint()
        )
    }

    private fun reservedBytes(): List<Int>? {
        val raw = clientId ?: return null
        val bytes = runCatching { Base64Compat.decode(raw) }.getOrNull() ?: return null
        return bytes.takeIf { it.size == 3 }?.map { it.toInt() and 0xff }
    }

    fun toJson(): String = JSONObject()
        .put("privateKey", privateKey)
        .put("peerPublicKey", peerPublicKey)
        .put("clientV4", clientV4)
        .put("clientV6", clientV6)
        .put("clientId", clientId)
        .put("accountId", accountId)
        .put("deviceId", deviceId)
        .put("token", token)
        .put("license", license)
        .put("warpPlus", warpPlus)
        .put("endpoint", endpoint)
        .put("createdAt", createdAt)
        .toString()

    companion object {
        // Was "engage.cloudflareclient.com:2408". sing-box (and every sing-box-lx
        // build we've tested: v1.14.1-lx.3, v1.14.0-lx.34/35) recreates a
        // domain-name WireGuard/AmneziaWG peer's UDP socket on every handshake
        // retry instead of reusing it - confirmed on-device via matched
        // router tcpdump + phone logcat: with the domain endpoint, every ~5s
        // retry used a brand-new source port and the read goroutine died
        // within single-digit ms with "use of closed network connection",
        // even though Cloudflare's server answered every handshake correctly.
        // The underlying sing-box regression (SagerNet/sing-box#3486 /
        // #4366 - FQDN peer endpoint refresh tied to handshake retry) isn't
        // in any release we can build against yet. A literal IP peer never
        // takes that resolver code path at all: same device, same network,
        // same minute, switching just this string to a literal IP produced
        // zero closed-socket errors and a working tunnel (confirmed
        // end-to-end: sites load). 162.159.192.1 is the address every one of
        // our captures already resolved "engage.cloudflareclient.com" to.
        const val DEFAULT_ENDPOINT = "162.159.192.1:2408"

        fun fromJson(value: String): WarpAccount {
            val j = JSONObject(value)
            return WarpAccount(
                privateKey = j.getString("privateKey"),
                peerPublicKey = j.getString("peerPublicKey"),
                clientV4 = j.getString("clientV4"),
                clientV6 = j.getString("clientV6"),
                clientId = j.optString("clientId").takeIf { it.isNotBlank() },
                accountId = j.getString("accountId"),
                deviceId = j.getString("deviceId"),
                token = j.getString("token"),
                license = j.optString("license").takeIf { it.isNotBlank() },
                warpPlus = j.optBoolean("warpPlus", false),
                endpoint = j.optString("endpoint", DEFAULT_ENDPOINT),
                createdAt = j.getString("createdAt")
            )
        }

        /**
         * True if [endpoint]'s host is a literal IP (v4 or v6), not a hostname.
         * Used to self-heal accounts cached before [DEFAULT_ENDPOINT] switched
         * from a domain to a literal IP - see the comment on that constant.
         * The `[host]:port` bracket form (see [splitEndpoint]) is only ever
         * used for an IPv6 literal here, so its presence alone is decisive -
         * an IPv6 host's hex digits (a-f) would otherwise be indistinguishable
         * from a hostname. Unbracketed, only an IPv4 dotted-decimal host
         * qualifies; a DNS hostname can never be all digits and dots.
         */
        fun isLiteralIpEndpoint(endpoint: String): Boolean {
            val raw = endpoint.trim()
            if (raw.startsWith("[")) return runCatching { splitEndpoint(raw) }.isSuccess
            val host = runCatching { splitEndpoint(raw).first }.getOrNull() ?: return false
            return host.isNotEmpty() && host.all { it.isDigit() || it == '.' }
        }

        private fun splitEndpoint(value: String): Pair<String, Int> {
            val raw = value.trim()
            require(raw.isNotEmpty()) { "WARP endpoint is empty" }
            if (raw.startsWith("[")) {
                val close = raw.indexOf(']')
                require(close > 0 && close + 2 <= raw.length && raw[close + 1] == ':') { "WARP endpoint must be host:port" }
                val port = raw.substring(close + 2).toIntOrNull() ?: error("WARP endpoint port is invalid")
                require(port in 1..65535) { "WARP endpoint port is invalid" }
                return raw.substring(1, close) to port
            }
            val colon = raw.lastIndexOf(':')
            require(colon > 0 && colon < raw.length - 1) { "WARP endpoint must be host:port" }
            val port = raw.substring(colon + 1).toIntOrNull() ?: error("WARP endpoint port is invalid")
            require(port in 1..65535) { "WARP endpoint port is invalid" }
            return raw.substring(0, colon) to port
        }

        private fun normalizeAddress(address: String, prefix: Int): String {
            val raw = address.trim()
            require(raw.isNotEmpty()) { "WARP client address is missing" }
            return if ('/' in raw) raw else "$raw/$prefix"
        }
    }
}