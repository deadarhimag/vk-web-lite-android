package com.example.vkweb.web

import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import java.util.concurrent.ConcurrentHashMap

/**
 * Что именно заблокировано (3.68): раньше в журнале были только серверы и счётчики («заблокировано=6 хосты=…»), и
 * заблокированные отметки просмотра (statEvents на страницах сообществ) долго оставались незамеченными. Теперь каждый
 * новый вид заблокированного запроса — сервер, путь (номера скрыты) и страница, где он был, — один раз в журнал.
 * Вызывается из фоновых потоков загрузки.
 */
object BlockedLog {
    private const val MAX_KINDS = 300
    private val seen = ConcurrentHashMap.newKeySet<String>()

    fun record(window: String, host: String?, path: String?, pagePath: String?) {
        if (seen.size >= MAX_KINDS) return
        val template = template(path)
        val key = "$window $host $template"
        if (!seen.add(key)) return
        AppLog.d(
            LogTag.PAGE, "заблокирован запрос",
            "окно" to window, "сервер" to host, "путь" to template,
            "страница" to pagePath?.replace(NUMBERS, "#"),
        )
    }

    /** Путь без номеров и ключей: /method/statEvents.addWeb, /rtrg, /counter/# … (не длиннее 80 знаков). */
    fun template(path: String?): String =
        path.orEmpty().replace(NUMBERS, "#").replace(KEYS, "…").take(MAX_PATH).ifEmpty { "/" }

    private val NUMBERS = Regex("-?\\d{3,}")
    private val KEYS = Regex("[0-9a-fA-F]{16,}|[A-Za-z0-9_-]{32,}")
    private const val MAX_PATH = 80
}
