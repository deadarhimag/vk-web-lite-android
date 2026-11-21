package com.example.vkweb.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.messages.MessagesActivity
import com.example.vkweb.outbox.OutboxItem
import com.example.vkweb.outbox.OutboxState

/**
 * Уведомления об отложенных сообщениях (4.17): «отправлено» — когда сервер принял или сообщение появилось в переписке;
 * «не отправлено» / «не подтверждено» — когда отправить не вышло (тогда решает человек: в переписке у сообщения
 * пометка, нажатие на неё — «Отправить ещё раз» или «Удалить»). По одному уведомлению на сообщение: итог заменяет
 * прежнее. Отдельный канал — его можно выключить, не трогая уведомления о новых сообщениях.
 */
object ScheduledSendNotifier {

    private const val CHANNEL_ID = "scheduled"
    private const val ID_BASE = 3000
    private const val ID_RANGE = 100_000

    fun onDelivered(context: Context, items: List<OutboxItem>) {
        items.forEach { item ->
            AppLog.i(LogTag.OUTBOX, "отложенное сообщение отправлено")
            show(context, item, context.getString(R.string.notif_scheduled_sent))
        }
    }

    /** Сравнить очередь до и после изменения: отложенные, которые стали сомнительными или не ушли. */
    fun onStatesChanged(context: Context, before: List<OutboxItem>, after: List<OutboxItem>) {
        val old = before.associateBy { it.id }
        after.forEach { item ->
            if (item.sendAt <= 0) return@forEach
            val was = old[item.id]?.state ?: return@forEach
            if (was == item.state) return@forEach
            val text = when (item.state) {
                OutboxState.FAILED -> R.string.notif_scheduled_failed
                OutboxState.UNCONFIRMED -> R.string.notif_scheduled_unconfirmed
                else -> return@forEach
            }
            AppLog.w(LogTag.OUTBOX, "отложенное сообщение не отправлено", "состояние" to item.state.name)
            show(context, item, context.getString(text))
        }
    }

    private fun show(context: Context, item: OutboxItem, title: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted || !manager.areNotificationsEnabled()) {
            AppLog.i(LogTag.OUTBOX, "уведомление об отложенном не показано: нет разрешения или уведомления выключены")
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_scheduled), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_scheduled_desc)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        val who = item.title.ifBlank { context.getString(R.string.notif_scheduled_chat) }
        val open = Intent(context, MessagesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MessagesActivity.EXTRA_PEER_ID, item.peerId)
            .putExtra(MessagesActivity.EXTRA_PEER_TITLE, item.title)
        val pending = PendingIntent.getActivity(
            context, item.peerId.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_message)
            .setContentTitle(title)
            .setContentText("$who: ${item.text}")
            .setStyle(Notification.BigTextStyle().bigText("$who: ${item.text}"))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(
                Notification.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_notification_message).setContentTitle(title).build(),
            )
            .build()
        manager.notify(ID_BASE + Math.floorMod(item.id.hashCode(), ID_RANGE), notification)
    }
}
