package com.betalgezia.omnivpn.vpn

import com.betalgezia.omnivpn.data.model.AwgParameters
import org.json.JSONObject

/**
 * The subset of AmneziaWG obfuscation that is safe to vary against
 * Cloudflare's *vanilla* WireGuard WARP servers, plus an optional endpoint.
 *
 * WARP's server only understands plain WireGuard, so the real handshake and
 * data packets must stay byte-identical to WireGuard. Only the pre-handshake
 * decoy traffic can change:
 *  - [jc]/[jmin]/[jmax] - the count and size range of junk packets sent
 *    before the handshake (AmneziaWG's Jc/Jmin/Jmax), and
 *  - [i1] - one "CPS" decoy packet that imitates another protocol (here a
 *    QUIC initial), sent before the handshake.
 *
 * s1-s4 (packet padding) and h1-h4 (header type bytes) are deliberately NOT
 * part of this - they alter the real WireGuard packets and would make
 * Cloudflare drop the handshake. [toAwgParameters] pins them to 0 and
 * 1/2/3/4 (WireGuard's own message-type bytes = a no-op) respectively. See
 * [WarpAccount.DEFAULT_OBFUSCATION] and the September 2026 WARP debugging
 * notes on WarpAccount for how these values were arrived at.
 *
 * [endpoint] is the Cloudflare anycast IP:port this profile was found to
 * work on (null = use the account's own endpoint). [tunedLatencyMs] is the
 * probe latency recorded when a tuner run selected this profile (null for
 * the built-in default), shown in the UI as "подобранные · N мс".
 */
data class AwgObfuscation(
    val jc: Int,
    val jmin: Int,
    val jmax: Int,
    val i1: String?,
    val endpoint: String? = null,
    val tunedLatencyMs: Int? = null
) {
    fun toAwgParameters(): AwgParameters = AwgParameters(
        jc = jc, jmin = jmin, jmax = jmax,
        // Never touch the real WireGuard packets - see the class doc.
        s1 = 0, s2 = 0, s3 = 0, s4 = 0,
        h1 = 1, h2 = 2, h3 = 3, h4 = 4,
        i1 = i1
    )

    fun toJson(): String = JSONObject().apply {
        put("jc", jc); put("jmin", jmin); put("jmax", jmax)
        if (!i1.isNullOrBlank()) put("i1", i1)
        if (!endpoint.isNullOrBlank()) put("endpoint", endpoint)
        if (tunedLatencyMs != null) put("tunedLatencyMs", tunedLatencyMs)
    }.toString()

    companion object {
        fun fromJson(value: String): AwgObfuscation? = runCatching {
            val j = JSONObject(value)
            AwgObfuscation(
                jc = j.getInt("jc"),
                jmin = j.getInt("jmin"),
                jmax = j.getInt("jmax"),
                i1 = j.optString("i1").takeIf { it.isNotBlank() },
                endpoint = j.optString("endpoint").takeIf { it.isNotBlank() },
                tunedLatencyMs = if (j.has("tunedLatencyMs")) j.getInt("tunedLatencyMs") else null
            )
        }.getOrNull()
    }
}
