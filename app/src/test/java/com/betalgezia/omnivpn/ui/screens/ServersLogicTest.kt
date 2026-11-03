package com.betalgezia.omnivpn.ui.screens

import com.betalgezia.omnivpn.addErrorText
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServersLogicTest {
    private val now = 1_000_000_000L

    @Test
    fun updatedAgoReadsLikeTheHandoff() {
        assertEquals("ещё не обновлялась", updatedAgo(null, now))
        assertEquals("обновлено только что", updatedAgo(now - 30_000, now))
        assertEquals("обновлено 12 мин назад", updatedAgo(now - 12 * 60_000, now))
        assertEquals("обновлено 3 ч назад", updatedAgo(now - 3 * 3_600_000, now))
        assertEquals("обновлено 2 дн назад", updatedAgo(now - 49 * 3_600_000, now))
        // A clock that went backwards never gives "−5 мин".
        assertEquals("обновлено только что", updatedAgo(now + 300_000, now))
    }

    @Test
    fun groupMeta() {
        assertEquals(
            "4 серв. · обновлено 12 мин назад · авто 1 ч",
            subscriptionMeta(4, now - 12 * 60_000, now, refreshing = false, autoUpdateMinutes = 60)
        )
        assertEquals("4 серв. · обновляется…", subscriptionMeta(4, now, now, refreshing = true, autoUpdateMinutes = null))
        assertEquals("15 мин", frequencyLabel(15))
        assertEquals("24 ч", frequencyLabel(1440))
    }

    @Test
    fun sheetChecksBeforeTheNetwork() {
        assertNull(subscriptionUrlError("https://sub.example.com/u/7c1e2f"))
        assertNull(subscriptionUrlError("  HTTPS://sub.example.com  "))
        assertEquals("Нужна ссылка, начинающаяся с https://", subscriptionUrlError("http://sub.example.com"))
        assertEquals("Нужна ссылка, начинающаяся с https://", subscriptionUrlError("https://localhost"))
        assertNull(configTextError("vless://x@1.2.3.4:443"))
        assertEquals("Вставьте ссылку vless://, trojan://, hy2:// или JSON/YAML", configTextError("   "))
    }

    @Test
    fun addErrorsInWords() {
        assertEquals("Сервер подписки ответил ошибкой 404", addErrorText(IllegalStateException("Subscription HTTP 404: not found")))
        assertEquals("В подписке нет поддерживаемых серверов", addErrorText(IllegalArgumentException("Subscription returned no supported nodes")))
        assertEquals(
            "Не найдено поддерживаемых серверов — проверьте ссылку или текст",
            addErrorText(IllegalArgumentException("Configuration contains no supported nodes"))
        )
        assertEquals("Нужна ссылка, начинающаяся с https://", addErrorText(IllegalArgumentException("Subscription URL must use HTTPS")))
        assertEquals("Файл больше 5 МБ", addErrorText(IllegalArgumentException("Import file is too large")))
        assertEquals("Ответ подписки больше 5 МБ", addErrorText(IllegalArgumentException("Subscription response is too large")))
        assertEquals(
            "Не удалось найти сервер подписки — проверьте ссылку и интернет",
            addErrorText(UnknownHostException("sub.example.com"))
        )
        assertEquals("Сервер подписки не ответил вовремя", addErrorText(SocketTimeoutException("Read timed out")))
    }
}
