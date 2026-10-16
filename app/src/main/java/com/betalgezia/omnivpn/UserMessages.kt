package com.betalgezia.omnivpn

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Why adding / refreshing a subscription or importing a config failed, in
 * words for the «Добавить сервер» sheet and the snackbar. The repositories
 * throw short English messages (they're also what ends up in logcat).
 */
internal fun addErrorText(t: Throwable): String {
    val msg = t.message.orEmpty()
    return when {
        t is UnknownHostException -> "Не удалось найти сервер подписки — проверьте ссылку и интернет"
        t is SocketTimeoutException -> "Сервер подписки не ответил вовремя"
        t is ConnectException -> "Не удалось подключиться к серверу подписки"
        t is SSLException -> "Не удалось установить защищённое соединение с сервером подписки"
        msg.startsWith("Subscription HTTP ") ->
            "Сервер подписки ответил ошибкой " + msg.removePrefix("Subscription HTTP ").substringBefore(':').trim()
        msg.contains("returned no supported nodes") -> "В подписке нет поддерживаемых серверов"
        msg.contains("contains no supported nodes") -> "Не найдено поддерживаемых серверов — проверьте ссылку или текст"
        msg.contains("Import file is too large") -> "Файл больше 5 МБ"
        msg.contains("too large") -> "Ответ подписки больше 5 МБ"
        msg.contains("redirect", ignoreCase = true) -> "Подписка перенаправляет слишком много раз"
        msg.contains("HTTPS") || msg.contains("Invalid subscription URL") || msg.contains("host is missing") ->
            "Нужна ссылка, начинающаяся с https://"
        msg.contains("Unable to open selected file") -> "Не удалось открыть файл"
        t is IOException && msg.isBlank() -> "Ошибка сети"
        msg.isNotBlank() -> msg
        else -> "Не удалось добавить"
    }
}
