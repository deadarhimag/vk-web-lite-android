package com.example.vkweb.sync

import com.example.vkweb.messages.DialogEntry

/**
 * Сгруппированные уведомления о сообщениях (4.48): своё уведомление у каждого диалога и, когда их два и больше, —
 * сводка группы (Android сворачивает диалоги под неё и разворачивает по нажатию). Что показать, что убрать и кто
 * звучит — решается здесь, без Android: проверяется обычными тестами ([MessageNotifier] только выполняет план).
 *
 * Правила:
 *  - уведомление диалога ставится заново, только если в нём новое ([fresh]). Смахнутое человеком без нового не
 *    возвращается, уже показанное без нового не трогается — не звучит и не прыгает в шторке;
 *  - диалог больше не в списке для уведомления (прочитан, выключен, отмечен «Прочитано») — его уведомление убирается;
 *  - звук один на проверку: при группе дети молчат, звучит сводка — и только если новое пришло в «звучащий» диалог
 *    ([DialogNotifications.sounds]). Одиночное уведомление звучит само, по своему каналу;
 *  - сводка в обычном канале, если хоть один показанный диалог в нём; иначе в тихом (сводка в тихом канале увела бы
 *    всю группу в раздел «Без звука» шторки).
 */
object NotifyGroup {

    /** Поставить уведомление диалога: в обычном канале ([loud]) или в тихом. */
    data class Post(val dialog: DialogEntry, val loud: Boolean)

    /**
     * Сводка группы. [dialogs] — все показанные диалоги в порядке списка (строки сводки); [loudChannel] — в каком
     * канале; [alerts] — звучать ли ей (пришло новое в «звучащий» диалог).
     */
    data class Summary(val dialogs: List<DialogEntry>, val loudChannel: Boolean, val alerts: Boolean)

    data class Plan(
        val post: List<Post>,
        /** Диалоги, чьи уведомления убрать. */
        val cancel: Set<String>,
        /** null — сводки нет (показан один диалог или ни одного): убрать её. */
        val summary: Summary?,
        /** Новые уведомления диалогов молчат: звучит сводка. */
        val childrenSilent: Boolean,
    )

    /**
     * @param unread диалоги, о которых сейчас можно сообщать (уже без выключенных и отмеченных «Прочитано»), по порядку списка;
     * @param fresh те из них, где новое;
     * @param shown показанные сейчас уведомления диалогов: номер диалога → в обычном ли канале;
     * @param rules свои настройки диалогов.
     */
    fun plan(
        unread: List<DialogEntry>,
        fresh: List<DialogEntry>,
        shown: Map<String, Boolean>,
        rules: Map<String, DialogNotify>,
    ): Plan {
        val unreadPeers = unread.map { it.peerId }.toSet()
        val freshPeers = fresh.map { it.peerId }.filter { it in unreadPeers }.toSet()
        val cancel = shown.keys - unreadPeers
        val post = unread.filter { it.peerId in freshPeers }.map { Post(it, DialogNotifications.sounds(it, rules[it.peerId])) }
        val postedLoud = post.associate { it.dialog.peerId to it.loud }
        // Что будет в шторке после плана и в каком канале: поставленное сейчас — по новому каналу, остальное — как было.
        val visible = unread.filter { it.peerId in freshPeers || it.peerId in shown }
        if (visible.size < 2) return Plan(post, cancel, summary = null, childrenSilent = false)
        val loudChannel = visible.any { postedLoud[it.peerId] ?: shown[it.peerId] ?: false }
        val alerts = post.any { it.loud }
        return Plan(post, cancel, Summary(visible, loudChannel, alerts), childrenSilent = true)
    }

    /** После того как убрали уведомление диалога: сводка нужна, только если осталось два и больше. */
    fun keepsSummary(remaining: Int): Boolean = remaining >= 2
}
