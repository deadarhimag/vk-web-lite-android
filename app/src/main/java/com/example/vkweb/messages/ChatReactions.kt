package com.example.vkweb.messages

import android.app.Activity
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.ui.Notice
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Реакции в переписке (4.61; с 4.70 — отдельно от MessagesActivity): картинки реакций VK (сохранённые и свежие со
 * страницы), нажатие на реакцию под сообщением и выбор реакции из меню сообщения.
 */
class ChatReactions(
    private val activity: Activity,
    /** Список переписки: у него картинки реакций для показа под сообщениями. */
    private val adapter: ChatAdapter,
    /** Можно ли сейчас поставить реакцию (есть сеть, показан свой экран, а не сайт). */
    private val canSend: () -> Boolean,
    /** Поставить реакцию [reactionId] сообщению [cmid] (0 — снять свою); итог — [onReacted]. */
    private val send: (cmid: Long, reactionId: Int) -> Unit,
    /** Попросить у страницы картинки реакций; итог — [onAssets]. */
    private val requestAssets: () -> Unit,
) {
    private val store by lazy { ReactionImages(activity) }
    private var assetsRequested = false

    /** Есть картинки — реакцию можно выбрать из меню сообщения. */
    val canPick: Boolean get() = adapter.reactionImages.isNotEmpty()

    /** Экран открыт: сохранённые картинки (без сети реакции тоже видны). */
    fun restoreSaved() {
        if (adapter.reactionImages.isEmpty()) adapter.reactionImages = store.all()
    }

    /** Пришла история: картинки — раз за открытие экрана, когда страница уже говорит с API. */
    fun onHistory() {
        if (assetsRequested) return
        assetsRequested = true
        requestAssets()
    }

    fun onAssets(payload: ReaderPayload.ReactionAssets) {
        AppLog.i(LogTag.MESSAGES, "картинки реакций", "штук" to payload.images.size)
        store.save(payload.images)
        if (payload.images.isNotEmpty()) adapter.reactionImages = payload.images
    }

    fun onReacted(payload: ReaderPayload.Reacted) {
        if (payload.ok) return
        AppLog.w(LogTag.MESSAGES, "реакция не поставлена", "причина" to payload.reason)
        Notice.show(activity, R.string.reaction_failed)
    }

    /** Реакция под сообщением: своя — снять, чужая — поставить такую же. */
    fun onClicked(message: ChatMessage, reaction: ChatReaction): Unit = react(message, if (reaction.mine) 0 else reaction.id)

    /** Выбор реакции из меню сообщения: картинки VK сеткой; своя есть — «Убрать мою реакцию». */
    fun pick(message: ChatMessage) {
        val images = adapter.reactionImages
        if (images.isEmpty()) {
            Notice.show(activity, R.string.reaction_unavailable)
            return
        }
        val density = activity.resources.displayMetrics.density
        val size = (CELL_DP * density).toInt()
        val pad = (PAD_DP * density).toInt()
        val grid = android.widget.GridLayout(activity).apply {
            columnCount = COLUMNS
            setPadding(pad * 2, pad, pad * 2, 0)
        }
        var dialog: androidx.appcompat.app.AlertDialog? = null
        val cache = com.example.vkweb.offline.ImageCache.get(activity)
        images.entries.sortedBy { it.key }.forEach { (id, url) ->
            val button = android.widget.ImageButton(activity).apply {
                layoutParams = android.widget.GridLayout.LayoutParams().apply { width = size; height = size }
                setPadding(pad, pad, pad, pad)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setBackgroundResource(android.R.color.transparent)
                contentDescription = activity.getString(R.string.reaction_action)
                setOnClickListener {
                    dialog?.dismiss()
                    react(message, id)
                }
            }
            cache.load(button, url, size)
            grid.addView(button)
        }
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.reaction_pick_title)
            .setView(grid)
            .setNegativeButton(R.string.cancel, null)
        if (message.reactions.any { it.mine }) builder.setNeutralButton(R.string.reaction_remove) { _, _ -> react(message, 0) }
        dialog = builder.show()
    }

    private fun react(message: ChatMessage, reactionId: Int) {
        val cmid = message.id.removePrefix("c").toLongOrNull() ?: return
        if (!canSend()) {
            Notice.show(activity, R.string.reaction_failed)
            return
        }
        AppLog.i(LogTag.MESSAGES, if (reactionId > 0) "реакция ставится" else "реакция снимается", "номер" to reactionId)
        send(cmid, reactionId)
    }

    private companion object {
        const val COLUMNS = 6
        const val CELL_DP = 48
        const val PAD_DP = 8
    }
}
