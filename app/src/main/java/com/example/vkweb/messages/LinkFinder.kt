package com.example.vkweb.messages

/**
 * Ссылки в тексте сообщения: чтобы их можно было нажимать. Без зависимостей от Android: проверяется обычными тестами
 * (готовый шаблон ссылок Android в тестах JVM недоступен).
 */
object LinkFinder {

    /** Ссылка: где в тексте ([start, end)) и какой адрес открывать. */
    class Link(val start: Int, val end: Int, val url: String)

    private val URL = Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>\"']+")
    private const val TRAILING = ".,;:!?]}»…\"'"
    private const val MAX_URL = 2000

    fun find(text: String): List<Link> {
        if (text.length < 4) return emptyList()
        val result = ArrayList<Link>()
        for (match in URL.findAll(text)) {
            var end = match.range.last + 1
            // Знаки препинания после ссылки в её состав не входят; скобка — только если для неё нет открывающей внутри ссылки.
            while (end > match.range.first) {
                val c = text[end - 1]
                val unbalanced = c == ')' && text.substring(match.range.first, end).let { s -> s.count { it == ')' } > s.count { it == '(' } }
                if (c in TRAILING || unbalanced) end-- else break
            }
            val raw = text.substring(match.range.first, end)
            val url = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
            if (raw.length > 8 && url.length <= MAX_URL) result += Link(match.range.first, end, url)
        }
        return result
    }
}
