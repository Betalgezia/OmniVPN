package com.betalgezia.omnivpn.ui.screens

import com.betalgezia.omnivpn.vpn.ActiveConnection
import com.betalgezia.omnivpn.vpn.TrafficStatus
import com.betalgezia.omnivpn.vpn.VpnState
import java.util.Locale

// The home screen's state and text rules, kept free of Compose so they are
// unit-tested directly (HomeLogicTest).

/** What the hero shows - one row of the handoff's state table. */
enum class HeroKind { OFF, SEARCH, CONNECTING, ON, NO_TRAFFIC, NO_DNS, ERROR, REVOKED }

internal fun heroKind(
    state: VpnState,
    searching: Boolean,
    warpStarting: Boolean,
    active: ActiveConnection,
    traffic: TrafficStatus,
    lastError: String?
): HeroKind = when {
    searching -> HeroKind.SEARCH
    state == VpnState.CONNECTED -> when (traffic) {
        TrafficStatus.NONE -> HeroKind.NO_TRAFFIC
        TrafficStatus.DNS_BROKEN -> HeroKind.NO_DNS
        else -> HeroKind.ON
    }
    state == VpnState.CONNECTING || warpStarting -> HeroKind.CONNECTING
    // Start handed to the service, which hasn't reported CONNECTING yet.
    state == VpnState.DISCONNECTED && active != ActiveConnection.None -> HeroKind.CONNECTING
    state == VpnState.REVOKED -> HeroKind.REVOKED
    state == VpnState.ERROR || lastError != null -> HeroKind.ERROR
    else -> HeroKind.OFF
}

/** «из 21 сервера», «из 5 серверов». */
internal fun serversGenitive(n: Int): String =
    if (n % 10 == 1 && n % 100 != 11) "сервера" else "серверов"

/** Bytes/s → Mbit/s the way the counters show it: «12,4», «0,0», «140»; null → «—». */
internal fun formatMbps(bytesPerSec: Long?): String {
    if (bytesPerSec == null) return "—"
    val mbps = bytesPerSec * 8 / 1_000_000.0
    return if (mbps >= 99.95) String.format(Locale.ROOT, "%.0f", mbps)
    else String.format(Locale.ROOT, "%.1f", mbps).replace('.', ',')
}

/** The core's failure text, short enough for the hero subtitle. */
internal fun errorReason(raw: String?): String {
    val text = raw?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return "Сервер не ответил"
    return when {
        text.contains("permission", ignoreCase = true) -> "Нет разрешения на VPN"
        text.length > 90 -> text.take(87).trimEnd() + "…"
        else -> text
    }
}

/** ЧЧ:ММ:СС. */
internal fun sessionClock(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
}
