package com.betalgezia.omnivpn.ui.screens

// Text rules of the Servers tab and the «Добавить сервер» sheet, free of
// Compose so they are unit-tested directly (ServersLogicTest).

/** 15 → «15 мин», 60 → «1 ч», 1440 → «24 ч». */
fun frequencyLabel(minutes: Int): String =
    if (minutes < 60) "$minutes мин" else "${minutes / 60} ч"

/** «обновлено только что» / «обновлено 12 мин назад» / «обновлено 3 ч назад» / «… 2 дн назад». */
internal fun updatedAgo(lastUpdatedAt: Long?, nowMs: Long): String {
    if (lastUpdatedAt == null) return "ещё не обновлялась"
    val minutes = ((nowMs - lastUpdatedAt) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> "обновлено только что"
        minutes < 60 -> "обновлено $minutes мин назад"
        minutes < 1_440 -> "обновлено ${minutes / 60} ч назад"
        else -> "обновлено ${minutes / 1_440} дн назад"
    }
}

/** Group subtitle: «4 серв. · обновлено 12 мин назад · авто 1 ч» (auto part only when on). */
internal fun subscriptionMeta(
    servers: Int,
    lastUpdatedAt: Long?,
    nowMs: Long,
    refreshing: Boolean,
    autoUpdateMinutes: Int?
): String = buildString {
    append("$servers серв. · ")
    append(if (refreshing) "обновляется…" else updatedAgo(lastUpdatedAt, nowMs))
    if (autoUpdateMinutes != null) append(" · авто ${frequencyLabel(autoUpdateMinutes)}")
}

private val SUBSCRIPTION_URL = Regex("^https://\\S+\\.\\S+", RegexOption.IGNORE_CASE)

/** The sheet's own check before anything goes to the network; null = fine. */
internal fun subscriptionUrlError(text: String): String? =
    if (SUBSCRIPTION_URL.containsMatchIn(text.trim())) null else "Нужна ссылка, начинающаяся с https://"

internal fun configTextError(text: String): String? =
    if (text.isNotBlank()) null else "Вставьте ссылку vless://, trojan://, hy2:// или JSON/YAML"
