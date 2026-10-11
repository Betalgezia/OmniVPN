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

    // Shadowsocks methods the outbound takes - the core's own enum for it, legacy stream
    // ciphers included.
    private val SHADOWSOCKS_METHODS = setOf(
        "none", "aes-128-gcm", "aes-192-gcm", "aes-256-gcm",
        "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
        "aes-128-ctr", "aes-192-ctr", "aes-256-ctr", "aes-128-cfb", "aes-192-cfb", "aes-256-cfb",
        "rc4-md5", "chacha20-ietf", "xchacha20"
    )
    // Other spellings of the same ciphers that links use (shadowsocks-rust style).
    private val SHADOWSOCKS_METHOD_ALIASES = mapOf(
        "chacha20-poly1305" to "chacha20-ietf-poly1305",
        "xchacha20-poly1305" to "xchacha20-ietf-poly1305"
    )
    // VMess `security`: the core's enum.
    private val VMESS_SECURITIES = setOf("auto", "none", "zero", "aes-128-cfb", "aes-128-gcm", "chacha20-poly1305")
    // TUIC: the core's enums for both.
    private val TUIC_CONGESTION_CONTROLS = setOf("cubic", "new_reno", "bbr")
    private val TUIC_UDP_RELAY_MODES = setOf("native", "quic")

    /** A Shadowsocks method as the core spells it, or null if it has no such cipher. */
    fun shadowsocksMethod(value: Any?): String? = keepIf("method", value) {
        val method = value?.toString()?.trim()?.lowercase() ?: return@keepIf null
        (SHADOWSOCKS_METHOD_ALIASES[method] ?: method).takeIf { it in SHADOWSOCKS_METHODS }
    }

    /**
     * False for a password the core would refuse for [method]. Shadowsocks 2022 takes
     * base64 keys of a fixed size - 16 bytes for 2022-blake3-aes-128-gcm, 32 for the
     * others - one per hop, joined with ':' for a relay; the older methods take any
     * non-empty password, and "none" takes none.
     */
    fun isValidShadowsocksPassword(method: String, password: String): Boolean {
        if (method == "none") return true
        if (password.isEmpty()) return false
        if (!method.startsWith("2022-")) return true
        val keyBytes = if (method == "2022-blake3-aes-128-gcm") 16 else 32
        return password.split(':').all { key ->
            runCatching { Base64Compat.decode(key.trim()) }.getOrNull()?.size == keyBytes
        }
    }

    /**
     * A SIP003 plugin the way the core runs it, as (plugin, plugin_opts). The core has two,
     * built in: obfs-local (simple-obfs is its old name), in http or tls mode, and
     * v2ray-plugin in websocket mode. Null for anything else - the server expects that
     * plugin, so the node could only fail without it.
     */
    fun shadowsocksPlugin(name: String?, options: String?): Pair<String, String>? {
        val plugin = when (name?.trim()?.lowercase()) {
            "obfs-local", "simple-obfs" -> "obfs-local"
            "v2ray-plugin" -> "v2ray-plugin"
            else -> return null
        }
        val opts = options?.trim().orEmpty()
        val settings = opts.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            .associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=', "").trim().lowercase() }
        val supported = when (plugin) {
            // "unknown obfs mode" at start otherwise; without obfs= it uses http.
            "obfs-local" -> settings["obfs"]?.let { it in setOf("http", "tls") } ?: true
            // "v2ray-plugin: unknown mode" otherwise (no quic mode in the core).
            else -> settings["mode"]?.let { it == "websocket" } ?: true
        }
        return (plugin to opts).takeIf { supported }
    }

    /** VMess security as the core takes it; anything it doesn't know becomes "auto". */
    fun vmessSecurity(value: Any?): String =
        value?.toString()?.trim()?.lowercase()?.takeIf { it in VMESS_SECURITIES } ?: "auto"

    fun tuicCongestionControl(value: Any?): String? = keepIf("congestion_control", value) {
        value?.toString()?.trim()?.lowercase()?.replace('-', '_')?.takeIf { it in TUIC_CONGESTION_CONTROLS }
    }

    fun tuicUdpRelayMode(value: Any?): String? = keepIf("udp_relay_mode", value) {
        value?.toString()?.trim()?.lowercase()?.takeIf { it in TUIC_UDP_RELAY_MODES }
    }

    // Hysteria2 bbr_profile: the core's own list, enum:"standard,conservative,aggressive".
    private val HYSTERIA2_BBR_PROFILES = setOf("standard", "conservative", "aggressive")

    fun bbrProfile(value: Any?): String? = keepIf("bbr_profile", value) {
        value?.toString()?.trim()?.lowercase()?.takeIf { it in HYSTERIA2_BBR_PROFILES }
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
