package com.example.vkweb.messages

import android.app.Activity
import android.widget.TextView
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.ui.Notice
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Присланное через «Поделиться» (4.57): выбор, кому — тот же лист, что у пересылки (поиск, аватары), — затем
 * переписка открывается, текст встаёт в поле ввода, файлы — во вложения. Отправляет человек сам: можно дописать.
 */
class ShareIntoChat(private val activity: Activity, private val host: Host) {

    interface Host {
        /** Известные диалоги (сохранённые или свежие); пусто — список ещё не готов. */
        val dialogs: List<DialogEntry>
        /** Открыть переписку и положить в неё присланное. */
        fun deliver(dialog: DialogEntry, share: ShareContent)
    }

    private var pending: ShareContent? = null
    private var sheetShown = false

    /** Пришло новое «Поделиться»: показать выбор, как только известен список диалогов. */
    fun receive(share: ShareContent) {
        pending = share
        sheetShown = false
        maybeShow()
    }

    /** Список диалогов появился или обновился. */
    fun maybeShow() {
        val share = pending ?: return
        if (sheetShown) return
        val dialogs = host.dialogs
        if (dialogs.isEmpty()) return
        sheetShown = true
        val targets = ForwardTargets.build(dialogs, "", "")
        val sheet = BottomSheetDialog(activity)
        // Содержимое листа: родителя ещё нет — размеры задаёт сам BottomSheetDialog: предупреждение InflateParams отключено.
        @android.annotation.SuppressLint("InflateParams")
        val root = activity.layoutInflater.inflate(R.layout.sheet_forward, null)
        root.findViewById<TextView>(R.id.forward_title).setText(R.string.share_title)
        root.findViewById<TextView>(R.id.forward_preview_author).setText(R.string.share_what)
        root.findViewById<TextView>(R.id.forward_preview_text).text = preview(share)
        var picked = false
        ForwardTargetList.bind(activity, root, targets) { target ->
            picked = true
            pending = null
            sheet.dismiss()
            AppLog.i(LogTag.MESSAGES, "поделиться: выбрана переписка", "кому" to target.kind.name)
            val dialog = dialogs.firstOrNull { it.peerId == target.peerId }
                ?: DialogEntry(peerId = target.peerId, title = target.title, snippet = "", time = "", unread = false, online = false, avatar = target.avatar)
            host.deliver(dialog, share)
        }
        sheet.setOnDismissListener {
            if (picked) return@setOnDismissListener
            pending = null
            AppLog.i(LogTag.MESSAGES, "поделиться: отменено")
            Notice.show(activity, R.string.share_cancelled)
        }
        sheet.setContentView(root)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }

    private fun preview(share: ShareContent): String {
        val files = if (share.uris.isEmpty()) "" else activity.getString(R.string.share_files, share.uris.size)
        return listOf(share.text.take(200), files).filter { it.isNotEmpty() }.joinToString("\n")
    }
}
