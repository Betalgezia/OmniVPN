package com.betalgezia.omnivpn.vpn

import org.json.JSONObject

/**
 * Checks for outbound values that sing-box-lx validates when the core starts.
 *
 * A value the core rejects doesn't only break its own node: Libbox.checkConfig() fails
 * the whole config, and the server test puts a whole list into one config (see
 * SingBoxConfigBuilder.buildProbeConfig) - so one malformed link used to mark every
 * server in the list unreachable with that one link's error. The parsers run such values
 * through here and leave out a value the core would refuse rather than pass it along;
 * NodeHealthChecker keeps its own safety net for anything that still gets through.
 *
 * Checked against sing-box-lx v1.14.1-lx.8, the core in app/libs/libbox.aar.
 */
internal object OutboundFieldRules {
    // Anything else: "v2ray-xhttp: unknown mode: ..." at start.
    private val XHTTP_MODES = setOf("auto", "packet-up", "stream-up", "stream-one")

    // What x_padding_bytes and the xmux ranges take: "100-1000" or "16". Anything else
    // fails at start (for x_padding_bytes: `strconv.Atoi: parsing "abc"`).
    private val RANGE = Regex("""(\d{1,9})(?:-(\d{1,9}))?""")

    // xmux's fields, with the spellings they arrive under (sing-box, Mihomo, Xray).
    private val XMUX_RANGE_FIELDS = mapOf(
        "max_concurrency" to listOf("max_concurrency", "max-concurrency", "maxConcurrency"),
        "max_connections" to listOf("max_connections", "max-connections", "maxConnections"),
        "c_max_reuse_times" to listOf("c_max_reuse_times", "c-max-reuse-times", "cMaxReuseTimes"),
        "h_max_request_times" to listOf("h_max_request_times", "h-max-request-times", "hMaxRequestTimes"),
        "h_max_reusable_secs" to listOf("h_max_reusable_secs", "h-max-reusable-secs", "hMaxReusableSecs")
    )
    private val XMUX_KEEP_ALIVE_KEYS = listOf("h_keep_alive_period", "h-keep-alive-period", "hKeepAlivePeriod")

    // VLESS `encryption` (Xray's post-quantum "VLESS Encryption"), the shape the core's own
    // errors spell out: "expected at least method.appearance.rtt.key", "expected
    // native|xorpub|random", "expected 0rtt|1rtt", "empty segment in encryption string",
    // "no encryption keys in encryption string".
    private const val VLESS_ENCRYPTION_METHOD = "mlkem768x25519plus"
    private val VLESS_ENCRYPTION_APPEARANCES = setOf("native", "xorpub", "random")
    private val VLESS_ENCRYPTION_RTTS = setOf("0rtt", "1rtt")
    // A key is base64url: an X25519 public key (32 bytes, 43 chars) or an ML-KEM-768
    // encapsulation key (1184 bytes, 1579 chars) - the two seen in real links.
    private val VLESS_ENCRYPTION_KEY_BYTES = setOf(32, 1184)
    // Xray allows padding settings between the RTT and the keys, e.g. "100-111-1111".
    private val VLESS_ENCRYPTION_PADDING = Regex("""\d+(-\d+)*""")

    /**
     * True if [value] is a VLESS encryption string the core can parse:
     * mlkem768x25519plus.(native|xorpub|random).(0rtt|1rtt)[.padding...].key[.key...].
     * "none" is not an encryption string - callers deal with it before asking.
     */
    fun isValidVlessEncryption(value: String): Boolean {
        val parts = value.trim().split('.')
        if (parts.size < 4 || parts.any { it.isEmpty() }) return false
        if (parts[0] != VLESS_ENCRYPTION_METHOD) return false
        if (parts[1] !in VLESS_ENCRYPTION_APPEARANCES || parts[2] !in VLESS_ENCRYPTION_RTTS) return false
        var keys = 0
        for (part in parts.drop(3)) {
            when {
                isVlessEncryptionKey(part) -> keys++
                VLESS_ENCRYPTION_PADDING.matches(part) -> Unit
                else -> return false
            }
        }
        return keys > 0
    }

    private fun isVlessEncryptionKey(part: String): Boolean {
        if (!part.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) return false
        val bytes = runCatching { Base64Compat.decodeUrl(part) }.getOrNull() ?: return false
        return bytes.size in VLESS_ENCRYPTION_KEY_BYTES
    }

    fun xhttpMode(value: Any?): String? = keepIf("mode", value) {
        value?.toString()?.trim()?.lowercase()?.takeIf { it in XHTTP_MODES }
    }

    /**
     * A range the way the core takes it - "from-to" or one number, as a string. A JSON
     * number is accepted too (Xray configs allow either) and written as a string; a range
     * that runs backwards is not accepted.
     */
    fun range(field: String, value: Any?): String? = keepIf(field, value) {
        val text = when (value) {
            is Int, is Long -> value.toString()
            is Number -> value.toDouble().takeIf { it >= 0 && it % 1.0 == 0.0 }?.toLong()?.toString()
            is String -> value.trim()
            else -> null
        }
        text?.let(RANGE::matchEntire)?.let { match ->
            val from = match.groupValues[1].toLong()
            val to = match.groupValues[2].ifEmpty { match.groupValues[1] }.toLong()
            text.takeIf { from <= to }
        }
    }

    /**
     * sing-box `xmux` built from whichever spelling [lookup] finds, keeping only values the
     * core accepts. [lookup] reads one key from the source (a Map or a JSONObject). Null
     * when nothing usable is left.
     */
    fun xmux(lookup: (String) -> Any?): JSONObject? {
        val out = JSONObject()
        for ((target, keys) in XMUX_RANGE_FIELDS) {
            keys.firstNotNullOfOrNull(lookup)?.let { range("xmux.$target", it) }?.let { out.put(target, it) }
        }
        XMUX_KEEP_ALIVE_KEYS.firstNotNullOfOrNull(lookup)?.let { value ->
            keepIf("xmux.h_keep_alive_period", value) { wholeNumber(value)?.takeIf { it >= 0 } }
        }?.let { out.put("h_keep_alive_period", it) }
        return out.takeIf { it.length() > 0 }
    }

    /**
     * Applies the same checks to an xhttp transport that arrived as a ready sing-box
     * object (a sing-box JSON import): drops a bad mode or x_padding_bytes, rebuilds xmux.
     */
    fun sanitizeXhttpTransport(transport: JSONObject) {
        if (transport.optString("type") != "xhttp") return
        if (transport.has("mode")) {
            val mode = xhttpMode(transport.opt("mode"))
            if (mode == null) transport.remove("mode") else transport.put("mode", mode)
        }
        if (transport.has("x_padding_bytes")) {
            val padding = range("x_padding_bytes", transport.opt("x_padding_bytes"))
            if (padding == null) transport.remove("x_padding_bytes") else transport.put("x_padding_bytes", padding)
        }
        transport.optJSONObject("xmux")?.let { source ->
            val xmux = xmux { key -> source.opt(key)?.takeUnless { it == JSONObject.NULL } }
            if (xmux == null) transport.remove("xmux") else transport.put("xmux", xmux)
        }
    }

    private fun wholeNumber(value: Any?): Long? = when (value) {
        is Int, is Long -> (value as Number).toLong()
        is Number -> value.toDouble().takeIf { it % 1.0 == 0.0 }?.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    // Values here are transport settings, never credentials, so they are safe to log.
    private inline fun <T : Any> keepIf(field: String, value: Any?, check: () -> T?): T? {
        val kept = check()
        if (kept == null && value != null && value.toString().isNotBlank()) {
            runCatching { android.util.Log.w(TAG, "dropped $field=$value: not accepted by sing-box-lx") }
        }
        return kept
    }

    private const val TAG = "OutboundFieldRules"
}
