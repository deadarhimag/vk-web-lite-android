package com.example.vkweb.messages

/**
 * Изменение и удаление сообщений (3.94). Без зависимостей от Android: проверяется обычными тестами.
 *  - Изменить можно своё подтверждённое сообщение с текстом и без вложений (фото, видео, файлы, голосовые не передаются
 *    при изменении — VK удалил бы их), не старше суток: так разрешает VK.
 *  - Удалить у себя — любое подтверждённое; у всех — своё, не старше суток.
 */
object MessageEditing {

    const val LIMIT_SEC = 24 * 60 * 60L

    /** Номер в беседе («c123» → 123); у черновых и неотправленных его нет. */
    fun cmidOf(m: ChatMessage): Long? = m.id.takeIf { m.isConfirmed && it.startsWith("c") }?.drop(1)?.toLongOrNull()

    private fun fresh(m: ChatMessage, nowSec: Long) = ChatDays.isKnown(m.dateSec) && nowSec - m.dateSec in 0 until LIMIT_SEC

    fun canEdit(m: ChatMessage, nowSec: Long): Boolean =
        m.out && cmidOf(m) != null && m.text.isNotBlank() && fresh(m, nowSec) &&
            m.photos.isEmpty() && m.videos.isEmpty() && m.cards.isEmpty() && m.voices.isEmpty()

    fun canDelete(m: ChatMessage): Boolean = cmidOf(m) != null

    fun canDeleteForAll(m: ChatMessage, nowSec: Long): Boolean = m.out && cmidOf(m) != null && fresh(m, nowSec)

    /** Удалённое сообщение — из показанной истории. */
    fun removed(items: List<ChatMessage>, cmid: Long): List<ChatMessage> = items.filterNot { cmidOf(it) == cmid }

    /** Изменённый текст — в показанной истории. */
    fun edited(items: List<ChatMessage>, cmid: Long, text: String): List<ChatMessage> =
        items.map { if (cmidOf(it) == cmid) it.copy(text = text) else it }
}
