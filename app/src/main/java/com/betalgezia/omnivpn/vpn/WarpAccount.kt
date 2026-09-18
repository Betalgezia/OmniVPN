package com.betalgezia.omnivpn.vpn

import android.util.Base64
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
        val bytes = runCatching { Base64.decode(raw, Base64.DEFAULT or Base64.NO_WRAP) }.getOrNull() ?: return null
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
        const val DEFAULT_ENDPOINT = "engage.cloudflareclient.com:2408"

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