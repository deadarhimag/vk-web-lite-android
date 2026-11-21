package com.example.vkweb.diag

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Сводка сетевого трафика страницы за период (сообщение type = [TYPE], присылается по просьбе приложения: когда экран
 * уходит в фон и когда заканчивается фоновая проверка). Страница считает каждый запрос — сервер и «шаблон» адреса без
 * номеров и ключей, число запросов, байты, ошибки (assets/shared/net_tap.js). В журнале видно, какие серверы отвечали
 * и что приходило. Без зависимостей от Android, кроме записи в журнал: разбор и форматирование проверяются обычными тестами.
 */
object TrafficSummary {
    const val TYPE = "traffic"
    private const val MAX_ITEMS = 40
    private const val MAX_TEXT = 60

    /** [lastErrorStatus] - код последнего ответа с ошибкой (5.00.12), 0 - неизвестен. */
    class Item(
        val host: String, val endpoint: String, val source: String, val count: Int, val bytes: Long, val errors: Int,
        val lastErrorStatus: Int = 0,
    )

    class Summary(val page: String, val items: List<Item>) {
        val requests: Int get() = items.sumOf { it.count }
        val bytes: Long get() = items.sumOf { it.bytes }
        val errors: Int get() = items.sumOf { it.errors }
    }

    fun parse(json: JSONObject): Summary? {
        val array = json.optJSONArray("items") ?: return null
        val items = ArrayList<Item>()
        for (i in 0 until minOf(array.length(), MAX_ITEMS)) {
            val o = array.optJSONObject(i) ?: continue
            val count = o.optInt("n")
            if (count <= 0) continue
            items += Item(
                host = o.optString("h").take(MAX_TEXT),
                endpoint = o.optString("e").take(MAX_TEXT),
                source = o.optString("s").take(8),
                count = count,
                bytes = o.optLong("b").coerceAtLeast(0),
                errors = o.optInt("err").coerceAtLeast(0),
                lastErrorStatus = o.optInt("st").coerceIn(0, 999),
            )
        }
        return if (items.isEmpty()) null else Summary(json.optString("page").take(100), items)
    }

    /** Ответ evaluateJavascript — JSON-строка со строкой JSON (или «null»/пустая строка, если за период ничего не было). */
    fun fromEvaluateResult(result: String?): Summary? {
        if (result.isNullOrBlank() || result == "null") return null
        return try {
            val text = JSONTokener(result).nextValue() as? String ?: return null
            if (text.isBlank()) null else parse(JSONObject(text))
        } catch (e: JSONException) {
            null
        }
    }

    /** «web.api.vk.ru ×40, m.vk.ru ×12»: какие серверы отвечали, по числу запросов. */
    fun hosts(summary: Summary, limit: Int = 6): String =
        summary.items.groupBy { it.host }
            .map { (host, list) -> host to list.sumOf { it.count } }
            .sortedByDescending { it.second }
            .take(limit)
            .joinToString(", ") { "${it.first} ×${it.second}" }

    /** «method/messages.getHistory fetch ×12 45 КБ ошибок 1» — самые частые адреса. */
    fun top(summary: Summary, limit: Int = 5): String =
        summary.items.sortedByDescending { it.count }.take(limit).joinToString("; ") { item ->
            buildString {
                append(item.endpoint).append(' ').append(item.source).append(" ×").append(item.count)
                if (item.bytes > 0) append(' ').append(kilobytes(item.bytes))
                if (item.errors > 0) append(" ошибок ").append(item.errors)
                if (item.errors > 0 && item.lastErrorStatus > 0) append(" (HTTP ").append(item.lastErrorStatus).append(')')
            }
        }

    fun kilobytes(bytes: Long): String = if (bytes < 1024) "$bytes Б" else "${bytes / 1024} КБ"

    fun write(summary: Summary) {
        AppLog.i(
            LogTag.SCRIPT, "трафик страницы",
            "страница" to summary.page.ifEmpty { null }, "запросов" to summary.requests, "байт" to summary.bytes,
            "ошибок" to summary.errors.takeIf { it > 0 }, "серверы" to hosts(summary), "адреса" to top(summary),
        )
    }
}
