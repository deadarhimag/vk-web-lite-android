package com.example.vkweb.messages

import android.app.Activity
import android.widget.EditText
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.ui.Notice
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Изменение и удаление своих сообщений в открытой переписке (3.94). С 4.3 вынесено из MessagesActivity по образцу
 * [ChatForwarding] и [ConversationActions]: экран отдаёт поле ввода, полосу над ним, черновики и историю через [Host].
 * Что можно изменить и удалить, как меняется история — [MessageEditing] (без Android, проверяется тестами).
 */
class ChatEditing(private val activity: Activity, private val host: Host) {

    interface Host {
        val input: EditText
        val isOnline: Boolean
        val currentPeer: String?
        /** На экране — переписка (а не список диалогов). */
        val chatShown: Boolean
        /** Сообщения сервера в открытой переписке. */
        val history: List<ChatMessage>
        /** Сообщение, на которое сейчас отвечают. */
        val replyTarget: ChatMessage?
        /** Убрать ответ (полоса над полем одна: ответ и изменение — не вместе). */
        fun clearReply()
        fun showBar(title: CharSequence, text: CharSequence)
        fun hideBar()
        fun saveDraft()
        /** В поле — черновик открытой переписки. */
        fun restoreDraft()
        fun sendEdit(cmid: Long, text: String)
        fun sendDelete(cmid: Long, forAll: Boolean)
        /** Изменили или удалили: показать и сохранить новую историю. */
        fun historyChanged(items: List<ChatMessage>)
    }

    /** Своё сообщение, которое сейчас изменяют: поле ввода — его текст, «Отправить» — изменить. */
    var target: ChatMessage? = null
        private set

    val isEditing: Boolean get() = target != null

    /** Переписка, в которой просили удалить: номера сообщений у каждой свои — чужой ответ к другой не применять. */
    private var deleteRequestPeer: String? = null

    fun start(m: ChatMessage) {
        host.clearReply()
        // Недописанное в поле не пропадает: оно — черновик, вернётся после изменения.
        host.saveDraft()
        target = m
        host.showBar(activity.getString(R.string.edit_title), m.text.replace('\n', ' '))
        val input = host.input
        input.setText(m.text)
        input.setSelection(input.text?.length ?: 0)
        input.requestFocus()
    }

    /** Изменение отменили (или переписка сменилась): в поле — снова черновик, а не текст изменявшегося сообщения. */
    fun cancel() {
        val was = target ?: return
        target = null
        if (host.input.text?.toString() == was.text) host.restoreDraft()
    }

    /** «Отправить» во время изменения. */
    fun send(value: String) {
        val m = target ?: return
        val cmid = MessageEditing.cmidOf(m) ?: return
        if (!host.isOnline) {
            Notice.show(activity, R.string.edit_offline)
            return
        }
        if (value == m.text) {
            cancel()
            host.hideBar()
            return
        }
        AppLog.i(LogTag.MESSAGES, "изменение сообщения", "номер" to cmid)
        host.sendEdit(cmid, value)
    }

    fun onEdited(payload: ReaderPayload.Edited) {
        if (!payload.ok) {
            AppLog.w(LogTag.MESSAGES, "сообщение не изменено", "причина" to payload.reason)
            Notice.show(activity, activity.getString(R.string.edit_failed, payload.reason), long = true)
            return
        }
        val m = target
        if (m != null && MessageEditing.cmidOf(m) == payload.cmid) {
            target = null
            host.restoreDraft()
            host.hideBar()
        }
        host.historyChanged(MessageEditing.edited(host.history, payload.cmid, payload.text))
    }

    /** «Удалить»: у всех — своё свежее; иначе — только у себя. */
    fun confirmDelete(m: ChatMessage) {
        val cmid = MessageEditing.cmidOf(m) ?: return
        // В «Избранном» (переписка с собой) «у всех» не бывает: VK отвечает отказом.
        val selfChat = com.example.vkweb.account.SelfProfile.load(activity)?.id?.toString() == host.currentPeer
        val forAll = !selfChat && MessageEditing.canDeleteForAll(m, System.currentTimeMillis() / 1000)
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.delete_title)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_for_me) { _, _ -> delete(cmid, forAll = false) }
        if (forAll) builder.setNeutralButton(R.string.delete_for_all) { _, _ -> delete(cmid, forAll = true) }
        builder.show()
    }

    private fun delete(cmid: Long, forAll: Boolean) {
        if (!host.isOnline) {
            Notice.show(activity, R.string.delete_offline)
            return
        }
        AppLog.i(LogTag.MESSAGES, "удаление сообщения", "номер" to cmid, "у всех" to forAll)
        deleteRequestPeer = host.currentPeer
        host.sendDelete(cmid, forAll)
    }

    fun onDeleted(payload: ReaderPayload.Deleted) {
        val requestedIn = deleteRequestPeer
        deleteRequestPeer = null
        if (!payload.ok) {
            AppLog.w(LogTag.MESSAGES, "сообщение не удалено", "причина" to payload.reason)
            Notice.show(activity, activity.getString(R.string.delete_failed, payload.reason), long = true)
            return
        }
        if (target?.let { MessageEditing.cmidOf(it) } == payload.cmid) {
            cancel()
            host.hideBar()
        }
        Notice.show(activity, R.string.message_deleted)
        // Пока шёл запрос, открыли другую переписку: в ней удалённого нет — её список не трогаем (страница пришлёт свежий).
        if (requestedIn == null || requestedIn != host.currentPeer || !host.chatShown) return
        if (host.replyTarget?.let { MessageEditing.cmidOf(it) } == payload.cmid) host.clearReply()
        host.historyChanged(MessageEditing.removed(host.history, payload.cmid))
    }
}
