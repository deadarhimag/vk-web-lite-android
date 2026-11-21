package com.example.vkweb.sync

import com.example.vkweb.config.VkIds
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.messages.PendingReads

/**
 * Кнопка «Прочитано» в уведомлении о сообщении (4.35). Отметка ставится в отложенные ([PendingReads], как у переписки,
 * открытой без сети), уведомление убирается, а фоновая работа ([MessageSyncScheduler.onReadsChanged]) при первой
 * возможности отправляет отметку от имени страницы — тем же путём, что экран сообщений. Открыли приложение раньше —
 * отметку отправит экран сообщений, как только придёт список диалогов.
 */
class MarkReadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val peer = intent.getStringExtra(EXTRA_PEER)?.takeIf { VkIds.isPeer(it) } ?: return
        val app = context.applicationContext
        PendingReads(app).add(peer)
        // 4.48: убирается только уведомление этого диалога, остальные в шторке остаются.
        MessageNotifier(app).cancelDialog(peer)
        AppLog.i(LogTag.SYNC, "уведомление: «Прочитано» — отметка отложена до фоновой работы")
        MessageSyncScheduler.onReadsChanged(app)
    }

    companion object {
        const val ACTION = "com.example.vkweb.sync.MARK_READ"
        const val EXTRA_PEER = "peer"
    }
}
