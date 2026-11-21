package com.example.vkweb.messages

import com.example.vkweb.config.AppConfig

/**
 * Что уходит в другое приложение, когда делятся сообщением: текст, затем вложения ссылками (ссылки, файлы, записи,
 * музыка названием, видео — страницей), пересланное — с автором. Фото уходят отдельно картинками.
 * Без зависимостей от Android: проверяется обычными тестами.
 */
object MessageShare {

    /** Адрес для другого приложения: путь на сайте — полным адресом VK, внешний адрес — как есть. */
    fun linkOf(card: ChatCard): String = when {
        card.url.isNotEmpty() -> card.url
        card.path.isNotEmpty() -> SITE + card.path
        else -> ""
    }

    fun textOf(message: ChatMessage): String {
        val parts = ArrayList<String>()
        if (message.text.isNotBlank()) parts += message.text.trim()
        parts += attachmentsText(message.videos, message.cards).joinToString("\n")
        message.forwards.forEach { f ->
            val head = if (f.sender.isNotEmpty()) "Переслано от ${f.sender}:" else "Пересланное сообщение:"
            val body = listOf(f.text.trim()).filter { it.isNotEmpty() } + attachmentsText(f.videos, f.cards)
            parts += (listOf(head) + body).joinToString("\n")
        }
        return parts.filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    /** Все фото сообщения и пересланного в нём — по порядку, без повторов. */
    fun photosOf(message: ChatMessage): List<String> =
        (message.photos + message.forwards.flatMap { it.photos }).map { it.url }.distinct()

    private fun attachmentsText(videos: List<ChatVideo>, cards: List<ChatCard>): List<String> {
        val lines = ArrayList<String>()
        videos.forEach { v -> lines += listOf(v.title, SITE + v.pagePath).filter { it.isNotBlank() }.joinToString(" — ") }
        cards.forEach { c ->
            val link = linkOf(c)
            lines += when (c.kind) {
                // У музыки адреса нет — название; у голосового и видеосообщения адрес временный — только название.
                "audio" -> "♪ ${c.title}"
                "video_message" -> c.title
                // Звонок: «Исходящий звонок · Длительность 1:05» (ссылка «присоединиться» — временная, не передаётся).
                "call" -> listOf(c.title, c.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
                else -> listOf(c.title, link).filter { it.isNotBlank() }.distinct().joinToString(" — ")
            }
        }
        return lines.filter { it.isNotBlank() }
    }

    /** Расширение из подписи файла («PDF · 1,5 МБ» → «pdf»); пусто, если его там нет. */
    fun extensionFrom(subtitle: String): String =
        subtitle.substringBefore('·').trim().lowercase().takeIf { Regex("^[a-z0-9]{1,6}$").matches(it) }.orEmpty()

    /** Имя файла для передачи: без символов, недопустимых в именах, не длиннее 80 знаков, с расширением [fallbackExt]. */
    fun fileName(title: String, fallbackExt: String): String {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(80)
        val base = clean.ifEmpty { "file" }
        return if (base.contains('.') || fallbackExt.isEmpty()) base else "$base.$fallbackExt"
    }

    private val SITE = "https://" + AppConfig.MAIN_SITE_HOSTS.first { it == "vk.com" }
}
