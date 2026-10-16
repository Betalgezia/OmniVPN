package com.betalgezia.omnivpn.ui.screens

import com.betalgezia.omnivpn.vpn.SpeedMeasuring
import com.betalgezia.omnivpn.vpn.SwitchEvent
import com.betalgezia.omnivpn.vpn.SwitchKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Texts of the switch card (home) and the «Переключения» journal (handoff
// §1.4 and §4a), free of Compose so they are unit-tested (SwitchLogicTest).

/** «1,6» - Mbit/s with one decimal and a comma. */
internal fun mbit(value: Double): String = String.format(Locale.ROOT, "%.1f", value).replace('.', ',')

private fun approx(value: Double?): String = value?.let { "≈${mbit(it)}" } ?: "≈?"

internal fun noticeTitle(event: SwitchEvent): String = when (event.kind) {
    SwitchKind.SLOW, SwitchKind.DEAD -> "Переключились на «${event.to ?: "?"}»"
    SwitchKind.SLOW_STAY -> "Остались на «${event.from}»"
}

internal fun noticeReason(event: SwitchEvent): String = when (event.kind) {
    SwitchKind.SLOW ->
        "«${event.from}» был медленным: ${approx(event.fromMbps)} Мбит/с. Новый сервер ${approx(event.toMbps)} Мбит/с."
    SwitchKind.DEAD -> "«${event.from}» перестал пропускать трафик."
    SwitchKind.SLOW_STAY ->
        "Сервер медленный (${approx(event.fromMbps)} Мбит/с), но другие серверы подписки не быстрее. Похоже, медленная сама сеть."
}

internal fun measuringTitle(m: SpeedMeasuring): String =
    if (m.candidate == null) "Проверяем скорость «${m.from}»" else "«${m.from}» медленный"

internal fun measuringReason(m: SpeedMeasuring): String =
    if (m.candidate == null) {
        "Загрузка идёт медленно — замеряем сам сервер, около 1 МБ."
    } else {
        "Ищем сервер быстрее: замеряем «${m.candidate}», около 1 МБ."
    }

/** Journal row title: «Amsterdam 01 → Frankfurt Reality», or just the name when we stayed. */
internal fun journalRoute(event: SwitchEvent): String =
    if (event.kind == SwitchKind.SLOW_STAY || event.to == null) event.from else "${event.from} → ${event.to}"

internal fun journalReason(event: SwitchEvent): String = when (event.kind) {
    SwitchKind.SLOW -> "Медленный: ${approx(event.fromMbps)} → ${approx(event.toMbps)} Мбит/с"
    SwitchKind.DEAD -> "Не пропускал трафик"
    SwitchKind.SLOW_STAY -> "Медленный, но быстрее нет — остались"
}

/** ЧЧ:ММ in the device's time zone. */
internal fun clockLabel(atMs: Long): String = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(atMs))

/** «только что» (under 45 s), «N мин назад» (under an hour), then ЧЧ:ММ. */
internal fun relativeTime(atMs: Long, nowMs: Long, clock: (Long) -> String = ::clockLabel): String {
    val seconds = (nowMs - atMs) / 1000
    return when {
        seconds < 45 -> "только что"
        seconds < 3_600 -> "${(seconds / 60).coerceAtLeast(1)} мин назад"
        else -> clock(atMs)
    }
}
