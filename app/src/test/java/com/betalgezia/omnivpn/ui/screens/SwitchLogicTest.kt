package com.betalgezia.omnivpn.ui.screens

import com.betalgezia.omnivpn.vpn.SpeedMeasuring
import com.betalgezia.omnivpn.vpn.SwitchEvent
import com.betalgezia.omnivpn.vpn.SwitchKind
import kotlin.test.Test
import kotlin.test.assertEquals

class SwitchLogicTest {
    private val slow = SwitchEvent(SwitchKind.SLOW, "Amsterdam 01", "Frankfurt Reality", 1.6, 12.4, atMs = 0)
    private val dead = SwitchEvent(SwitchKind.DEAD, "Warsaw", "Amsterdam 01", atMs = 0)
    private val stay = SwitchEvent(SwitchKind.SLOW_STAY, "Amsterdam 01", null, 1.6, null, atMs = 0)

    @Test
    fun cardTextsMatchTheHandoff() {
        assertEquals("Переключились на «Frankfurt Reality»", noticeTitle(slow))
        assertEquals("«Amsterdam 01» был медленным: ≈1,6 Мбит/с. Новый сервер ≈12,4 Мбит/с.", noticeReason(slow))
        assertEquals("Переключились на «Amsterdam 01»", noticeTitle(dead))
        assertEquals("«Warsaw» перестал пропускать трафик.", noticeReason(dead))
        assertEquals("Остались на «Amsterdam 01»", noticeTitle(stay))
        assertEquals(
            "Сервер медленный (≈1,6 Мбит/с), но другие серверы подписки не быстрее. Похоже, медленная сама сеть.",
            noticeReason(stay)
        )
        val measuring = SpeedMeasuring("Amsterdam 01", "Frankfurt Reality")
        assertEquals("«Amsterdam 01» медленный", measuringTitle(measuring))
        assertEquals("Ищем сервер быстрее: замеряем «Frankfurt Reality», около 1 МБ.", measuringReason(measuring))
    }

    @Test
    fun journalTexts() {
        assertEquals("Amsterdam 01 → Frankfurt Reality", journalRoute(slow))
        assertEquals("Медленный: ≈1,6 → ≈12,4 Мбит/с", journalReason(slow))
        assertEquals("Не пропускал трафик", journalReason(dead))
        assertEquals("Amsterdam 01", journalRoute(stay))
        assertEquals("Медленный, но быстрее нет — остались", journalReason(stay))
    }

    @Test
    fun relativeTimes() {
        val at = 1_000_000L
        val clock: (Long) -> String = { "12:34" }
        assertEquals("только что", relativeTime(at, at + 44_000, clock))
        assertEquals("1 мин назад", relativeTime(at, at + 45_000, clock))
        assertEquals("12 мин назад", relativeTime(at, at + 12 * 60_000 + 10_000, clock))
        assertEquals("12:34", relativeTime(at, at + 3_600_000, clock))
    }
}
