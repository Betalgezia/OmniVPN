package com.betalgezia.omnivpn.ui.screens

import com.betalgezia.omnivpn.vpn.AwgObfuscation
import com.betalgezia.omnivpn.vpn.WarpAccount

// Text rules of the Settings pages, free of Compose so they are unit-tested
// directly (SettingsLogicTest).

/** Auto-update choices on the «Подписки» page (minutes). */
val AUTO_UPDATE_FREQUENCIES = listOf(15, 30, 60, 180, 360, 720, 1440)

internal fun autoUpdateSummary(enabled: Boolean, minutes: Int): String =
    if (enabled) "Каждые ${frequencyLabel(minutes)}" else "Выключено — только вручную"

private val WARP_ENDPOINT = Regex("^\\d{1,3}(\\.\\d{1,3}){3}:\\d{2,5}$")

/** «Свой endpoint» takes a literal IPv4:port, like the default 162.159.192.1:2408. */
internal fun isWarpEndpoint(text: String): Boolean = WARP_ENDPOINT.matches(text.trim())

/** Settings → Cloudflare WARP: «IPv6 вкл · стандартный endpoint» / «… · endpoint 162.159.192.7:2408». */
internal fun warpSummary(ipv6: Boolean, endpoint: String): String =
    "IPv6 " + (if (ipv6) "вкл" else "выкл") + " · " +
        if (endpoint == WarpAccount.DEFAULT_ENDPOINT) "стандартный endpoint" else "endpoint $endpoint"

/** «Стандартные» / «Подобранные · 52 мс». */
internal fun obfuscationSummary(custom: Boolean, tunedLatencyMs: Int?): String = when {
    !custom -> "Стандартные"
    tunedLatencyMs != null -> "Подобранные · $tunedLatencyMs мс"
    else -> "Подобранные"
}

/**
 * The «Сейчас» chips. Only jc/jmin/jmax and the i1 decoy ever change (s1–s4
 * and h1–h4 stay WireGuard's own, see AwgObfuscation); every decoy the app
 * uses imitates a QUIC initial.
 */
internal fun obfuscationChips(obf: AwgObfuscation): List<String> = buildList {
    add("jc ${obf.jc}")
    add("jmin ${obf.jmin}")
    add("jmax ${obf.jmax}")
    add(if (obf.i1.isNullOrBlank()) "i1 нет" else "i1 QUIC")
    add("s1–s4 0")
    add("h1–h4 стд")
    obf.endpoint?.let { add(it) }
}

/** One tuner candidate in a line: «jc 4 · 40–70 · QUIC · 162.159.192.1». */
internal fun tuneCandidateLabel(obf: AwgObfuscation): String = buildString {
    append("jc ${obf.jc} · ${obf.jmin}–${obf.jmax} · ")
    append(if (obf.i1.isNullOrBlank()) "без i1" else "QUIC")
    obf.endpoint?.let { append(" · ").append(it.substringBeforeLast(':')) }
}
