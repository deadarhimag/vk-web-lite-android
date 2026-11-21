package com.example.vkweb.messages

import android.app.Activity
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.ui.Notice
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Действия с беседой по долгому нажатию в списке диалогов (4.0): прочитана / непрочитана, уведомления, удалить
 * переписку. С 4.2 вынесено из MessagesActivity, как ChatForwarding и ChatStickers: экран только отдаёт сюда нужное
 * через [Host]. Как меняется список после ответа страницы — [apply], без Android, проверяется тестом.
 */
class ConversationActions(private val activity: Activity, private val host: Host) {

    interface Host {
        /** Окно чтения работает (не запасной показ страницы VK): действия есть. */
        val isAvailable: Boolean
        val isOnline: Boolean
        /** Отправить действие странице (команда convAction окна чтения). */
        fun send(action: String, peer: String)
        /** Список диалогов экрана; после ответа страницы сюда записывается обновлённый. */
        var dialogs: List<DialogEntry>
        /** Беседа удалена: стереть её черновик и сохранённую переписку. */
        fun onRemoved(peer: String)
        /** Список изменился: сохранить и показать. */
        fun onDialogsChanged()
        /** Своя настройка уведомлений диалога изменилась (4.47): перерисовать список. */
        fun onNotifyRulesChanged()
    }

    /** Меню беседы [d]. Без окна чтения — только своя настройка уведомлений: ей страница не нужна (4.47). */
    fun showMenu(d: DialogEntry) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        // 4.47: уведомления этого диалога на телефоне — без сети, не меняя настройку аккаунта VK.
        items += activity.getString(R.string.conv_notify) to { showNotify(d) }
        if (!host.isAvailable) {
            showNotify(d)
            return
        }
        items += if (d.unread) activity.getString(R.string.conv_mark_read) to { run(READ, d) }
        else activity.getString(R.string.conv_mark_unread) to { run(UNREAD, d) }
        items += if (d.muted) activity.getString(R.string.conv_unmute) to { run(UNMUTE, d) }
        else activity.getString(R.string.conv_mute) to { run(MUTE, d) }
        items += activity.getString(R.string.conv_delete) to { confirmDelete(d) }
        MaterialAlertDialogBuilder(activity)
            .setTitle(d.title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    /** Уведомления диалога на этом телефоне (тоже из меню открытой переписки). */
    fun showNotify(d: DialogEntry) {
        DialogNotifyDialog.show(activity, d.peerId, d.title, mutedInVk = d.muted) { host.onNotifyRulesChanged() }
    }

    private fun confirmDelete(d: DialogEntry) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.conv_delete_title)
            .setMessage(activity.getString(R.string.conv_delete_message, d.title))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.conv_delete) { _, _ -> run(REMOVE, d) }
            .show()
    }

    private fun run(action: String, d: DialogEntry) {
        if (!host.isOnline) {
            Notice.show(activity, R.string.conv_offline)
            return
        }
        AppLog.i(LogTag.MESSAGES, "действие с беседой", "действие" to action)
        host.send(action, d.peerId)
    }

    /** Ответ страницы на действие. */
    fun onResult(payload: ReaderPayload.ConvAction) {
        if (!payload.ok) {
            AppLog.w(LogTag.MESSAGES, "действие с беседой не выполнено", "действие" to payload.action, "причина" to payload.reason)
            Notice.show(activity, activity.getString(R.string.conv_failed, payload.reason), long = true)
            return
        }
        // Сразу и в своём списке (свежий от страницы придёт следом): удалённая — пропадает со всем сохранённым.
        host.dialogs = apply(host.dialogs, payload.action, payload.peer)
        if (payload.action == REMOVE) {
            host.onRemoved(payload.peer)
            Notice.show(activity, R.string.conv_deleted)
        }
        host.onDialogsChanged()
    }

    companion object {
        const val READ = ReadMarks.READ
        const val UNREAD = ReadMarks.UNREAD
        const val MUTE = "mute"
        const val UNMUTE = "unmute"
        const val REMOVE = "remove"

        /** Список после выполненного действия [action] с беседой [peer]; неизвестное действие список не меняет. */
        fun apply(dialogs: List<DialogEntry>, action: String, peer: String): List<DialogEntry> = when (action) {
            REMOVE -> dialogs.filterNot { it.peerId == peer }
            READ -> dialogs.map { if (it.peerId == peer) it.copy(unread = false, unreadCount = 0) else it }
            UNREAD -> dialogs.map { if (it.peerId == peer) it.copy(unread = true) else it }
            MUTE -> dialogs.map { if (it.peerId == peer) it.copy(muted = true) else it }
            UNMUTE -> dialogs.map { if (it.peerId == peer) it.copy(muted = false) else it }
            else -> dialogs
        }
    }
}
