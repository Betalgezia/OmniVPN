package com.betalgezia.omnivpn.vpn

import java.net.URI
import java.util.Locale

/**
 * A DNS resolver the app can put into a sing-box config. Both kinds are
 * reached through the AmneziaWG/WARP tunnel (the builder sets `detour` to the
 * endpoint tag); [Doh] additionally encrypts the queries themselves, so the
 * Wi-Fi/carrier sees neither the questions nor the answers.
 */
sealed interface DnsEndpoint {
    /** Plain DNS over UDP (port 53 unless the user typed another one). */
    data class Udp(val server: String, val port: Int = 53) : DnsEndpoint

    /** DNS-over-HTTPS (RFC 8484), e.g. https://cloudflare-dns.com/dns-query. */
    data class Doh(val server: String, val port: Int = 443, val path: String = "/dns-query") : DnsEndpoint

    /** The host as the settings summary shows it (without scheme/path). */
    val host: String
        get() = when (this) {
            is Udp -> server
            is Doh -> server
        }

    /** The canonical value stored and shown in the custom-DNS field. */
    val text: String
        get() = when (this) {
            is Udp -> if (port == 53) server else "$server:$port"
            is Doh -> "https://$server" + (if (port == 443) "" else ":$port") + path
        }
}

/** One row of the DNS list. [summary] is the short line under [label]. */
data class DnsPreset(
    val id: String,
    val label: String,
    val summary: String,
    val endpoint: DnsEndpoint
)

/** A titled group of presets: «Обычные», «С обходом геоблокировок (для ИИ)». */
data class DnsGroup(val title: String, val presets: List<DnsPreset>)

object DnsCatalog {
    /** The built-in behaviour: UDP 1.1.1.1 through the tunnel, no DoH. */
    const val DEFAULT_ID = "default"

    /** The user's own address; [DnsSettings.customText] holds it. */
    const val CUSTOM_ID = "custom"

    /** Current endpointMode resolver, kept for the default row and bootstrap. */
    const val DEFAULT_SERVER = "1.1.1.1"

    val groups: List<DnsGroup> = listOf(
        DnsGroup(
            title = "Обычные",
            presets = listOf(
                preset("cloudflare", "Cloudflare", "Без фильтрации", "cloudflare-dns.com"),
                preset("cloudflare-security", "Cloudflare Security", "Защита от угроз", "security.cloudflare-dns.com"),
                preset("cloudflare-family", "Cloudflare Family", "Семейный фильтр", "family.cloudflare-dns.com"),
                preset("google", "Google", "Без фильтрации", "dns.google"),
                preset("quad9", "Quad9", "Без фильтрации", "dns10.quad9.net")
            )
        ),
        DnsGroup(
            title = "С обходом геоблокировок (для ИИ)",
            presets = listOf(
                preset("xbox", "Xbox DNS", "ИИ и игры", "xbox-dns.ru"),
                preset("comss", "Comss", "ИИ-сервисы", "dns.comss.one"),
                preset("dns-ai", "DNS-AI", "ИИ-сервисы", "dns.dns-ai.ru"),
                preset("malw", "Malw", "ИИ-сервисы", "dns.malw.link"),
                preset("zapret", "Zapret", "Обход блокировок", "dns.zapret.moe"),
                preset("geohide-us", "GeoHide US", "США", "us.geohide.ru"),
                preset("geohide-eu", "GeoHide EU", "Европа", "eu.geohide.ru")
            )
        )
    )

    val all: List<DnsPreset> = groups.flatMap { it.presets }

    fun byId(id: String): DnsPreset? = all.firstOrNull { it.id == id }

    private fun preset(id: String, label: String, summary: String, host: String) =
        DnsPreset(id, label, summary, DnsEndpoint.Doh(host))
}

/** «Cloudflare · Без фильтрации», «Свой · dns.example» or «По умолчанию · 1.1.1.1». */
fun dnsSummary(selectedId: String, customText: String): String = when (selectedId) {
    DnsCatalog.DEFAULT_ID -> "По умолчанию · ${DnsCatalog.DEFAULT_SERVER}"
    DnsCatalog.CUSTOM_ID -> parseDnsEndpoint(customText)?.let { "Свой · ${it.host}" } ?: "Свой DNS"
    else -> DnsCatalog.byId(selectedId)?.let { "${it.label} · ${it.summary}" }
        ?: "По умолчанию · ${DnsCatalog.DEFAULT_SERVER}"
}

/**
 * Parses what the user typed into the custom-DNS field. Accepts
 * `https://host/path`, a bare `host/path` (the form the presets use),
 * a bare host (DoH with `/dns-query`) and a plain IPv4 with an optional port
 * for UDP DNS. Returns null for anything else - including `http://`, which
 * would send DNS queries in clear text.
 */
fun parseDnsEndpoint(raw: String): DnsEndpoint? {
    val text = raw.trim()
    if (text.isEmpty() || text.any { it.isWhitespace() }) return null
    if (text.startsWith("https://", ignoreCase = true)) return parseDoh(text)
    if (text.contains("://")) return null

    val colon = text.indexOf(':')
    if (colon > 0 && text.indexOf(':', colon + 1) < 0) {
        val host = text.substring(0, colon)
        if (isIpv4(host)) {
            val port = text.substring(colon + 1).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return DnsEndpoint.Udp(host, port)
        }
    }
    if (isIpv4(text)) return DnsEndpoint.Udp(text)
    // An all-numeric "host" that is not a valid IPv4 is a typo, not a DNS name.
    val hostPart = text.substringBefore('/').substringBefore(':')
    if (hostPart.all { it in '0'..'9' || it == '.' }) return null
    return parseDoh("https://$text")
}

private fun parseDoh(text: String): DnsEndpoint? {
    val uri = runCatching { URI(text) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    if (uri.userInfo != null) return null
    val host = uri.host?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (host.any { it.isWhitespace() }) return null
    if (host.all { it in '0'..'9' || it == '.' } && !isIpv4(host)) return null
    val port = if (uri.port == -1) 443 else uri.port
    if (port !in 1..65535) return null
    val path = buildString {
        append(uri.rawPath?.takeIf { it.isNotBlank() } ?: "/dns-query")
        uri.rawQuery?.takeIf { it.isNotBlank() }?.let { append('?').append(it) }
    }
    return DnsEndpoint.Doh(host.lowercase(Locale.ROOT), port, path)
}

/** True for a dotted-quad IPv4 literal (no ranges, no leading-zero tricks). */
fun isIpv4(text: String): Boolean {
    val parts = text.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() && part.length <= 3 && (part.length == 1 || part[0] != '0') &&
            part.all { it in '0'..'9' } && (part.toIntOrNull() ?: return false) in 0..255
    }
}
