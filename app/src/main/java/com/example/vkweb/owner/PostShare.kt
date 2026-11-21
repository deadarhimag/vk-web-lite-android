package com.example.vkweb.owner

/**
 * Запись стены для другого приложения: начало текста и ссылка на запись на vk.com; у записи, которой поделились, —
 * и ссылка на исходную. Без зависимостей от Android: проверяется обычными тестами.
 */
object PostShare {
    private const val SITE = "https://vk.com"
    private const val MAX_TEXT = 1000

    fun textOf(post: WallPost): String {
        val parts = ArrayList<String>()
        // Без разметки VK ([id1|Имя], [ссылка|текст]): в другом приложении она видна как есть (код ревью 3.59).
        val text = OwnerFormat.plain(post.text).trim().ifEmpty { OwnerFormat.plain(post.repost?.text.orEmpty()).trim() }
        if (text.isNotEmpty()) parts += if (text.length > MAX_TEXT) text.take(MAX_TEXT).trimEnd() + "…" else text
        if (post.url.isNotEmpty()) parts += SITE + post.url
        post.repost?.url?.takeIf { it.isNotEmpty() && it != post.url }?.let { parts += SITE + it }
        return parts.joinToString("\n\n")
    }
}
