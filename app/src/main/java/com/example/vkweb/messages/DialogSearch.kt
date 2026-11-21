package com.example.vkweb.messages

/**
 * Поиск по диалогам (3.94): по названию беседы или имени, среди уже загруженных. Регистр и «ё/е» не важны, слова
 * запроса ищутся в любом порядке («петров иван» найдёт «Иван Петров»). Без зависимостей от Android: проверяется тестами.
 */
object DialogSearch {

    fun filter(items: List<DialogEntry>, query: String): List<DialogEntry> {
        val words = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return items
        return items.filter { entry ->
            val title = normalize(entry.title)
            words.all { it in title }
        }
    }

    fun normalize(text: String): String =
        text.lowercase().replace('ё', 'е').replace(Regex("\\s+"), " ").trim()
}
