package com.example.vkweb.sync

import com.example.vkweb.messages.ChatMessage
import com.example.vkweb.messages.DialogEntry

/**
 * Цепочка последних сообщений для уведомления диалога (5.00.12). Без зависимостей от Android: проверяется тестами.
 *
 * Раньше уведомление показывало только последнее сообщение (snippet списка диалогов): три сообщения подряд от одного
 * человека превращались в одно. Теперь, когда история диалога загружена заранее, видны все непрочитанные входящие
 * подряд с конца переписки - до своего сообщения или до [MAX] штук.
 */
object NotifyChain {

    /** Одна строка цепочки: автор (в беседе; в личной переписке пусто), текст, время (unix, секунды). */
    data class Line(val sender: String, val text: String, val dateSec: Long)

    /** Больше строк уведомление всё равно не показывает. */
    const val MAX = 8

    /**
     * Строки для [dialog] из его истории [history] (по порядку, старые сначала). Берутся входящие с конца до своего
     * сообщения, не больше числа непрочитанных (неизвестно - одно) и [MAX]. Пусто - показывать нечего (тогда уведомление
     * остаётся с одним последним сообщением из списка).
     */
    fun of(dialog: DialogEntry, history: List<ChatMessage>): List<Line> {
        // Входящие подряд с конца: своё сообщение обрывает цепочку - дальше уже прочитанное
        val tail = ArrayList<ChatMessage>()
        for (m in history.asReversed()) {
            // Неотправленное своё из очереди - не ответ, его не было на сервере
            if (m.isPending) continue
            if (m.out) break
            tail += m
        }
        val limit = dialog.unreadCount.coerceAtLeast(1).coerceAtMost(MAX)
        return tail.take(limit).asReversed()
            .map { Line(it.sender, text(it), it.dateSec) }
            .filter { it.text.isNotBlank() }
    }

    /** Текст строки: сам текст или подпись вложения, как в списке диалогов. */
    fun text(m: ChatMessage): String {
        val own = m.text.replace(WHITESPACE, " ").trim()
        if (own.isNotEmpty()) return own.take(MAX_TEXT)
        m.photos.firstOrNull()?.let { return if (it.sticker) "[Стикер]" else "[Фото]" }
        m.videos.firstOrNull()?.let { return if (it.isClip) "[Клип]" else ("[Видео] " + it.title).trim() }
        if (m.voices.isNotEmpty()) return "[Голосовое сообщение]"
        m.cards.firstOrNull()?.let { c ->
            return when (c.kind) {
                "audio" -> "[Музыка] " + c.title
                "doc" -> "[Файл] " + c.title
                "video_message" -> "[Видеосообщение]"
                else -> c.title
            }.trim()
        }
        if (m.forwards.isNotEmpty()) return "[Пересланные сообщения]"
        return ""
    }

    private const val MAX_TEXT = 500
    private val WHITESPACE = Regex("\\s+")
}
