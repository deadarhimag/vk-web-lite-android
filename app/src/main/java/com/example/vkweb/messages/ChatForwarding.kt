package com.example.vkweb.messages

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.ui.Notice
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * «Переслать» в переписке (3.94: вынесено из MessagesActivity без изменения поведения). Выбор получателя (3.91) — лист
 * снизу с цитатой пересылаемого, поиском и получателями с фото; своя страница («Избранное») всегда первой.
 */
class ChatForwarding(private val activity: Activity, private val host: Host) {

    interface Host {
        val isOnline: Boolean
        /** Окно чтения умеет отвечать (без него свой номер не спросить). */
        val canAskPage: Boolean
        /** Известные диалоги — из них получатели. */
        val dialogs: List<DialogEntry>
        /** Название открытой переписки — автор пересылаемого, если имени нет. */
        val chatTitle: String
        val currentPeer: String?
        /** Своя переписка показана (не запасная страница): можно перечитать историю. */
        val canRefresh: Boolean
        fun askSelfId(done: (String?) -> Unit)
        fun forward(targetPeer: String, cmid: Long)
        fun requestHistory()
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Свой номер страницы («переслать себе» — первым в списке); пусто — ещё не узнали. */
    private var selfId = ""

    /** Названия переписок, куда пересылали: для подсказки «Переслано: …». */
    private val titles = HashMap<String, String>()

    /** «Переслать»: выбрать переписку и переслать туда сообщение [cmid]. */
    fun forward(cmid: Long, message: ChatMessage) {
        if (!host.isOnline) {
            Notice.show(activity, R.string.forward_offline)
            return
        }
        // Своя страница уже известна по шапке меню (3.94) — спрашивать страницу незачем.
        if (selfId.isEmpty()) com.example.vkweb.account.SelfProfile.load(activity)?.let { selfId = it.id.toString() }
        // Свой номер знает страница: спрашиваем один раз и ждём ответа (он мгновенный), чтобы «Избранное» было первым.
        if (selfId.isNotEmpty() || !host.canAskPage) {
            showTargets(cmid, message)
            return
        }
        host.askSelfId { raw ->
            selfId = ForwardTargets.selfId(raw)
            if (selfId.isEmpty()) AppLog.w(LogTag.MESSAGES, "свой номер страницы неизвестен: «Избранное» не первым")
            if (!activity.isFinishing && !activity.isDestroyed) showTargets(cmid, message)
        }
    }

    private fun showTargets(cmid: Long, message: ChatMessage) {
        val targets = ForwardTargets.build(host.dialogs, selfId, activity.getString(R.string.forward_self))
        if (targets.isEmpty()) {
            Notice.show(activity, R.string.forward_no_chats)
            return
        }
        val dialog = BottomSheetDialog(activity)
        // Содержимое листа: родителя ещё нет — размеры задаёт сам BottomSheetDialog: предупреждение InflateParams отключено.
        @android.annotation.SuppressLint("InflateParams")
        val root = activity.layoutInflater.inflate(R.layout.sheet_forward, null)
        root.findViewById<TextView>(R.id.forward_preview_author).text =
            if (message.out) activity.getString(R.string.voice_from_me) else message.sender.ifEmpty { host.chatTitle }
        root.findViewById<TextView>(R.id.forward_preview_text).text = ForwardTargets.preview(
            message,
            ForwardTargets.Labels(
                photo = activity.getString(R.string.forward_preview_photo), video = activity.getString(R.string.forward_preview_video),
                voice = activity.getString(R.string.forward_preview_voice), attachment = activity.getString(R.string.forward_preview_attachment),
                forwards = activity.getString(R.string.forward_preview_forwards),
            ),
        )
        ForwardTargetList.bind(activity, root, targets) { target ->
            dialog.dismiss()
            AppLog.i(LogTag.MESSAGES, "пересылка сообщения", "номер" to cmid, "себе" to target.isSelf, "кому" to target.kind.name)
            titles[target.peerId] = target.title
            host.forward(target.peerId, cmid)
        }
        dialog.setContentView(root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    /** Ответ страницы на пересылку. */
    fun onForwarded(payload: ReaderPayload.Forwarded) {
        val title = titles[payload.target].orEmpty()
        if (!payload.ok) {
            AppLog.w(LogTag.MESSAGES, "сообщение не переслано", "причина" to payload.reason)
            Notice.show(activity, activity.getString(R.string.forward_failed, payload.reason), long = true)
            return
        }
        Notice.show(activity, activity.getString(R.string.forward_done, title))
        // Переслали в эту же переписку — новое сообщение появится в ней.
        if (payload.target == host.currentPeer) handler.postDelayed({ if (host.canRefresh) host.requestHistory() }, REFRESH_MS)
    }

    fun release() = handler.removeCallbacksAndMessages(null)

    private companion object {
        const val REFRESH_MS = 500L
    }
}
