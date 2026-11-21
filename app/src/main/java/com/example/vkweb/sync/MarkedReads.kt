package com.example.vkweb.sync

import com.example.vkweb.messages.DialogEntry

/**
 * «Прочитано» из уведомления и следующая проверка (4.35). Без зависимостей от Android: проверяется обычными тестами.
 *
 * Отметка ждёт отправки ([com.example.vkweb.messages.PendingReads]), а проверка тем временем видит диалоги:
 *  - переписка, отмеченная прочитанной, в уведомление не возвращается;
 *  - но если в ней после нажатия пришли новые сообщения ([fresh]), отметка снимается: она прочитала бы и их, а человек
 *    их не видел, — и переписка снова в уведомлении.
 */
object MarkedReads {

    data class Result(
        /** Отметки, которые снять (в переписке новое). */
        val drop: Set<String>,
        /** Отметки, которые отправить. */
        val keep: Set<String>,
        /** Непрочитанные для уведомления. */
        val unread: List<DialogEntry>,
    )

    fun apply(unread: List<DialogEntry>, fresh: List<DialogEntry>, marked: Set<String>): Result {
        val freshPeers = fresh.map { it.peerId }.toSet()
        val drop = marked intersect freshPeers
        val keep = marked - drop
        return Result(drop, keep, unread.filter { it.peerId !in keep })
    }
}
