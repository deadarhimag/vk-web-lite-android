package com.example.vkweb.messages

/**
 * Поиск в открытой переписке (4.0): по тексту загруженных сообщений (и подписям вложений), без регистра и «ё/е».
 * Совпадения — от новых к старым: первое найденное — ближайшее к низу переписки. Без зависимостей от Android.
 */
object ChatSearch {

    /** Номера сообщений в [items] с [query], от последнего к первому. */
    fun find(items: List<ChatMessage>, query: String): List<Int> {
        val q = DialogSearch.normalize(query)
        if (q.length < MIN_CHARS) return emptyList()
        return items.indices.reversed().filter { i -> q in DialogSearch.normalize(textOf(items[i])) }
    }

    private fun textOf(m: ChatMessage): String = buildString {
        append(m.text)
        m.cards.forEach { append(' ').append(it.title).append(' ').append(it.subtitle) }
        m.forwards.forEach { append(' ').append(it.text) }
    }

    const val MIN_CHARS = 2
}
