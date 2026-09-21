package com.betalgezia.omnivpn.vpn

import com.betalgezia.omnivpn.data.model.AwgParameters
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
            // Standard WireGuard hygiene for any peer that might sit behind
            // NAT - unset before. Not expected to fix DPI blocking by
            // itself, but on 2026-09-21 the junk-packet obfuscation above
            // got the very first handshake through (logcat: "received
            // handshake response"), and the tunnel then went quiet and
            // re-handshook every ~15-20s afterward ("stopped hearing back
            // after 15 seconds") - a periodic keepalive is a legitimate,
            // low-risk mitigation for exactly that pattern regardless of
            // whether the cause turns out to be NAT-mapping expiry or
            // something upstream, so it stays even if it isn't the whole
            // fix.
            .put("persistent_keepalive_interval", 25)
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
            rawConfig = toSingBoxEndpoint(),
            awg = DPI_JUNK_OBFUSCATION
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

        // Added after the literal-IP fix above still didn't make "Get WARP"
        // connect: a from-scratch registration (cachedAccount=false, brand
        // new keys/reserved bytes) produced the *exact same* failure on both
        // WiFi and mobile data - "sending handshake initiation" ->
        // "did not complete after 5 seconds, retrying (try 2)", forever,
        // never a single "handshake complete" - confirmed via matched
        // logcat captures on both networks (September 2026). That rules out
        // a stale/cached account and a single bad network; the same
        // symptom on two unrelated networks with a fresh registration
        // points at protocol-level DPI blocking, not an app bug. Multiple
        // independent Russian sources (ntc.party forum threads, a
        // Cloudflare-WARP-through-Amnezia writeup - see the September 2026
        // debugging session) describe exactly this: Roskomnadzor's DPI has
        // fingerprinted and dropped Cloudflare WARP's plain WireGuard
        // handshake since 2022, intensifying October 2024, regardless of
        // which literal IP/domain the peer resolves to.
        //
        // The reported community workaround - still used against
        // Cloudflare's real, unmodified WARP servers, no server-side
        // AmneziaWG support needed - is AmneziaWG's junk-packet obfuscation
        // (Jc/Jmin/Jmax): a handful of random-sized garbage UDP packets
        // sent to the same peer just *before* the real handshake, meant to
        // break DPI's flow-start pattern matching. h1/h2/h3/h4 are set here
        // to 1/2/3/4 - not scrambled values, but WireGuard's own standard
        // message-type bytes (handshake-init/response/cookie/data) - so
        // this is a deliberate no-op for the header: every real packet we
        // send stays byte-identical to plain WireGuard, which is required
        // since Cloudflare's server only understands plain WireGuard and
        // would silently drop anything else. s1-s4 are left at 0 (no
        // padding) for the same reason. Only the junk packets are new; the
        // real handshake sent afterward is unchanged.
        //
        // First round of these values (jc=4/jmin=40/jmax=70, no i1) got the
        // *handshake* through, but the connection then died ~15s later on
        // every network/endpoint tried ("stopped hearing back after 15
        // seconds", forever) - confirmed via matched logcat captures across
        // three literal IPs. Real traffic (background app connections, DNS
        // over TLS) was reaching the tunnel and being timed out, so it was
        // the ongoing data flow being blocked, not just the handshake.
        //
        // What actually held a tunnel open past that point (September 2026,
        // same device/network, verified end-to-end - sites loaded, a real
        // "packet download closed" after 34+ seconds, zero re-handshakes)
        // was a manually-imported AmneziaWG+WARP config using jc=3/jmin=64/
        // jmax=128 instead, plus an i1 decoy packet - AmneziaWG's mechanism
        // for padding the initial packet burst with something that looks
        // like ordinary protocol traffic (here, a large opaque blob) rather
        // than a bare run of same-shaped junk. i1 only shapes the packets
        // sent before the real handshake, same as jc/jmin/jmax - it doesn't
        // touch the real WireGuard packets Cloudflare has to parse, so it's
        // exactly as safe/compatible as the junk packets already were.
        // Adopted verbatim rather than re-derived, since this exact value
        // is the one that was actually confirmed working, not a guess -
        // note it's very likely reused by many AmneziaWG+WARP users found
        // via the same public writeups, so it may itself get fingerprinted
        // and blocked eventually, at which point it'll need regenerating
        // (any sufficiently large opaque byte blob should do).
        private val DPI_JUNK_OBFUSCATION = AwgParameters(
            jc = 3, jmin = 64, jmax = 128,
            h1 = 1, h2 = 2, h3 = 3, h4 = 4,
            i1 = "<b 0xc80000000114feb29a78e08c89b4b17df143c3ca17bf4ee4479f14bdc58899b0148a79770a96d0e94e7bf77f30b3a200447e3a7829c61b791e0c3d90fa9e18ae36fb1ec5c7d6e8db4296027aba6cf75587ab0c98cc0f5dd0f5f936bc9986055fef369a163cbd701834264d08ceebf81b7d31f3de22e7be5f6b2743bc92f0ab9051784ff65da10ada49382c4e71685f0da222d81d324d23e1b2abf15366127b1ff349462a54e0cc62bff6711daff1c43939c4da462a07937fba438cd2943b8eeab5961fce2af97dda102fc7c2417e4c8d6ec21c928ab10376e4dfea4536a9f59f43170f9ff572b5dee9ba2497098c2f1d43adfb000b2564491b3469ccdab78d63ddf335de1d7a338a4e429da8064481466b2439fe7ca1f86c2867696a5ed5446ef1aa7810a7bed49b9f787a145b961303fcfc0af9b9db5ef57ef9b6cb1e9924dfe8294fa4d4cc246a2b74c84a4bcac6b468ae6dc7646b650ba10407bc722c3ae09acca974ff47cabffb08e1041b912108b49f321dfb765fff60183fc5c5e4e73c53570afaf6981631d868e9f3de07a3971467d7ff1f2b8f9cf81432a34b84fc332b26557b60b012de24f1c70d619ecf3afe3b93fcdc8a894143a1d68b6a3df57d29620342cca5610d1e0b0999dbe6a91ec56e31080f574fc9e230ee073fa9bbd1032616c8feca8321d962b3087d0bea46c154fb0eb39d30e0faf2ed7e13763bbaf9ead3b4f95f305cab0d860a5e367eef9da5f2413f35a732a93852109537ccf99744e7d72d214e7e1b5d6b660c51b90224a6043f1155b354985b5f88d6b5362a73c74d41254486647c2d8ad0624cafcbab033d6648dce04602beb9e5867b22c1d8fe3567d7968a6eba1b3d798764325c1fb19800e136c69010906346dd2e80c826f71eb50c0b42610112bc2af4697db407736cb7fab227b3c1bb90545e40820082636b34021264a6c0791faa6f1a79766fab268027b1d9e532fac545990c8fe350cfe97d7c0f93af15f1ab065784215859e35c86a56441cd3dedc5b982566f6b8f8dfa4fa62dbd8032ea822f0efde2492d01aa46fdbf0eb9ce2b27fcafd98c6c356e349aad7965c3112c1d85d627313e567bce5e8f5e450b59bb5a2604d7530909816cddcfab92174f5eaf82af1b1f43bfa445e8a73576e364ab7c9bb9b043ab68c57956b1fb4236bfcfe3052a72c52f497d120cf285238c82835827b6a17ab1177e3b4117b75d4bfb81b90ec8d38780f6f56aa70a86bfbc7a7c8735e26e5e86859f8ca0cffb19ecc472e221f54d82f302beac448d27c41f8fdda93d04b4877f2182550a7db2e2f2fdce19390a9e7fda487bc001d441af03935b059c173295defe037286557312ac47602dec84543bc012fd98b1788ad50461bb56d8f681e90fdab3d69c4c22470f9679f0a4d63fed345a9300c9d7706dcd57a3b77973724e75d24c14e1bd34dcac19390a8fcb7e79e67eec87dae47516499d1a40c84d099b4ccfd27667cbe94ecb89e8f1e608bbca57310c285ac59f936cd4f0bed5200a45f1c783f3b0fc5b229932779259d160c23958d979a9c53b27d13682eafddcead1c817fa8200ffd1a30cda7e0dbeb3e87f36de2ce9966a1f069cfd15d6cd51234d11dcef0830a3c86fefc1492409dbd3433b0301e1f1548e25489b2950686d4ce00b8e2300786780d508b4b45dcada3db9e4>"
        )

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