package com.example.vkweb.sync

import com.example.vkweb.config.VkIds
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.outbox.Outbox
import com.example.vkweb.outbox.OutboxItem
import com.example.vkweb.outbox.OutboxLogic
import java.util.UUID

/**
 * «Ответить» в уведомлении о сообщении (4.36). Ответ ставится в ту же очередь неотправленных, что и сообщение с экрана
 * переписки ([Outbox]); очередь сама ставит фоновую отправку при сети (VkWebApp → [MessageSyncScheduler.onOutboxChanged]),
 * и фоновая работа отправляет его запросом от имени страницы (4.22). Не ушло — сообщение видно в переписке как
 * неотправленное, с повтором, как обычно. Уведомление после ответа убирается: на ответ ждать нечего.
 */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val peer = intent.getStringExtra(EXTRA_PEER)?.takeIf { VkIds.isPeer(it) } ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim().orEmpty()
        val app = context.applicationContext
        // Уведомление убирается только после успешного сохранения ответа.
        if (text.isEmpty()) return
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().take(MAX_TITLE)
        val outbox = Outbox.get(app)
        // Очередь читается с диска не сразу: приёмник живёт, пока она не готова.
        val pending = goAsync()
        outbox.whenReady(onCancelled = { pending.finish() }) {
            if (OutboxLogic.isFull(outbox.items())) {
                AppLog.w(LogTag.OUTBOX, "ответ из уведомления не поставлен: очередь заполнена")
                Toast.makeText(app, R.string.notif_reply_full, Toast.LENGTH_LONG).show()
                pending.finish()
                return@whenReady
            }
            val item = OutboxItem(
                id = UUID.randomUUID().toString(), peerId = peer, text = text.take(MAX_TEXT),
                createdAt = System.currentTimeMillis(), title = title,
            )
            outbox.update { OutboxLogic.enqueue(it, item) }
            outbox.whenPersisted { ok ->
                try {
                    if (ok) {
                        MessageNotifier(app).cancelDialog(peer)
                        AppLog.i(LogTag.OUTBOX, "ответ из уведомления сохранён в очереди")
                    } else {
                        outbox.update { OutboxLogic.discard(it, item.id) }
                        Toast.makeText(app, R.string.notif_reply_save_failed, Toast.LENGTH_LONG).show()
                    }
                } finally { pending.finish() }
            }
        }
    }

    companion object {
        const val ACTION = "com.example.vkweb.sync.REPLY"
        const val EXTRA_PEER = "peer"
        const val EXTRA_TITLE = "title"
        const val KEY_TEXT = "reply_text"
        private const val MAX_TEXT = 4000
        private const val MAX_TITLE = 200
    }
}
