package com.example.vkweb.messages

import androidx.appcompat.app.AppCompatActivity
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.outbox.Outbox
import com.example.vkweb.outbox.OutboxItem
import com.example.vkweb.outbox.OutboxLogic
import com.example.vkweb.outbox.OutboxState
import com.example.vkweb.ui.Notice
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Что человек делает с неотправленным (с 4.73 — отдельно от MessagesActivity): меню неотправленного сообщения
 * (повторить, удалить с «Вернуть», отложенное — отправить сейчас) и отложенная отправка с выбором времени (4.17).
 * Саму очередь ведёт [ChatOutboxDriver].
 */
class OutboxActions(
    private val activity: AppCompatActivity,
    private val outbox: () -> Outbox,
    private val driver: () -> ChatOutboxDriver,
    private val onCheckDelivery: () -> Unit = {},
) {

    /** Нажали на неотправленное сообщение: что с ним можно сделать — зависит от того, где оно. */
    fun onPendingClicked(message: ChatMessage) {
        if (AttachmentPending.isPendingId(message.id)) {
            Notice.show(activity, R.string.message_status_sending)
            return
        }
        val item = outbox().items().firstOrNull { OutboxLogic.messageId(it) == message.id } ?: return
        val builder = MaterialAlertDialogBuilder(activity).setTitle(R.string.outbox_action_title).setNegativeButton(
            R.string.cancel, null,
        )
        when (item.state) {
            OutboxState.QUEUED -> if (item.isScheduled(System.currentTimeMillis())) {
                // Отложенное (4.17): отправить сейчас или удалить.
                builder
                    .setMessage(activity.getString(R.string.schedule_pending_message, OutboxLogic.scheduleLabel(item.sendAt, System.currentTimeMillis())))
                    .setPositiveButton(R.string.schedule_send_now) { _, _ ->
                        AppLog.i(LogTag.OUTBOX, "отложенное отправлено сейчас по решению человека")
                        outbox().update { items -> items.map { if (it.id == item.id) it.copy(sendAt = 0) else it } }
                    }
                    .setNeutralButton(R.string.outbox_discard_queued) { _, _ -> discardWithUndo(item) }
            } else builder
                .setMessage(R.string.outbox_action_queued_message)
                .setPositiveButton(R.string.outbox_discard_queued) { _, _ -> discardWithUndo(item) }
            OutboxState.SENDING -> {
                Notice.show(activity, R.string.message_status_sending)
                return
            }
            OutboxState.UNCONFIRMED -> builder
                .setMessage(if (item.confirmationBlocked) R.string.outbox_action_manual_message else R.string.outbox_action_unconfirmed_message)
                .setPositiveButton(R.string.check_conversation) { _, _ -> onCheckDelivery() }
                .setNeutralButton(R.string.outbox_retry) { _, _ ->
                    MaterialAlertDialogBuilder(activity)
                        .setMessage(R.string.outbox_retry_warning)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.outbox_retry) { _, _ -> driver().retry(item) }
                        .show()
                }
                .setNegativeButton(R.string.outbox_discard) { _, _ -> discardWithUndo(item) }
            OutboxState.FAILED -> builder
                .setMessage(R.string.outbox_action_failed_message)
                .setPositiveButton(R.string.outbox_retry) { _, _ -> driver().retry(item) }
                .setNeutralButton(R.string.outbox_discard) { _, _ -> discardWithUndo(item) }
        }
        builder.show()
    }

    /**
     * Отложенная отправка (4.17): выбор времени, текст [value] — в очередь со временем отправки. [onQueued] — текст в
     * очереди (поле ввода можно очистить). Без разрешения на точные будильники (Android 12+) — подсказка: система может
     * сдвинуть отправку на несколько минут.
     */
    fun schedule(value: String, chatTitle: String, onQueued: () -> Unit) {
        val peer = driver().currentPeerId ?: return
        ScheduleSendDialog.show(activity) { at ->
            if (driver().currentPeerId != peer) return@show
            if (!driver().submit(value, at, chatTitle, onCommitted = committed@ { ok ->
                if (!ok) {
                    Notice.show(activity, R.string.outbox_save_failed, long = true)
                    return@committed
                }
                onQueued()
                val label = OutboxLogic.scheduleLabel(at, System.currentTimeMillis())
                if (!com.example.vkweb.sync.ScheduledSendAlarm.canBeExact(activity) && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    Notice.show(activity, activity.getString(R.string.schedule_done_inexact, label), long = true, action = activity.getString(R.string.schedule_allow_exact)) {
                        try {
                            activity.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${activity.packageName}")),
                            )
                        } catch (e: android.content.ActivityNotFoundException) {
                            Notice.show(activity, R.string.schedule_allow_exact_failed)
                        }
                    }
                } else {
                    Notice.show(activity, activity.getString(R.string.schedule_done, label))
                }
            })) {
                Notice.show(activity, R.string.outbox_full)
            }
        }
    }

    /** Удалить неотправленное — с «Вернуть» на полосе внизу (3.94): случайное нажатие не стоит текста. */
    private fun discardWithUndo(item: OutboxItem) {
        driver().discard(item)
        Notice.withAction(activity, R.string.outbox_discarded, R.string.outbox_undo) { driver().undoDiscard(item) }
    }
}
