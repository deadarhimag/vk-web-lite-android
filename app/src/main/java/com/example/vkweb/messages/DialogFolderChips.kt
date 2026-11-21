package com.example.vkweb.messages

import android.view.View
import androidx.core.view.isVisible
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Полоса папок над списком диалогов (3.79): «Все» и папки VK, в которых что-то есть (и «Непрочитанные»). С 4.4 вынесена
 * из MessagesActivity. Какие папки видны и что в них — [DialogFolders] (проверяется тестами); здесь — только чипы.
 */
class DialogFolderChips(
    private val scroll: View,
    private val group: ChipGroup,
    /** Выбрали другую папку: перерисовать список и подняться к началу. */
    private val onPicked: () -> Unit,
) {
    /** Выбранная папка; null — «Все». */
    var selectedId: Long? = null
        private set

    private var shown: List<ChatFolder> = emptyList()

    /** Показать полосу (если [allowed] — на экране список и не запасной показ страницы); вернуть выбранную папку. */
    fun render(folders: List<ChatFolder>, dialogs: List<DialogEntry>, allowed: Boolean): ChatFolder? {
        val visible = DialogFolders.visible(folders, dialogs)
        if (selectedId != null && visible.none { it.id == selectedId }) selectedId = null
        scroll.isVisible = allowed && visible.isNotEmpty()
        if (visible == shown && group.childCount == visible.size + 1) {
            syncChecks()
            return selected()
        }
        shown = visible
        group.setOnCheckedStateChangeListener(null)
        group.removeAllViews()
        val context = group.context
        (listOf<ChatFolder?>(null) + visible).forEach { f ->
            val chip = Chip(context, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId()
                text = f?.name ?: context.getString(R.string.folder_all)
                isCheckable = true
                isCheckedIconVisible = false
                tag = f?.id
            }
            group.addView(chip)
        }
        syncChecks()
        group.setOnCheckedStateChangeListener { g, ids ->
            val chip = ids.firstOrNull()?.let { g.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            val picked = chip.tag as? Long
            if (picked == selectedId) return@setOnCheckedStateChangeListener
            selectedId = picked
            AppLog.i(LogTag.MESSAGES, "папка диалогов", "какая" to (shown.firstOrNull { it.id == picked }?.type?.ifEmpty { "своя" } ?: "все"))
            onPicked()
        }
        return selected()
    }

    private fun selected(): ChatFolder? = shown.firstOrNull { it.id == selectedId }

    /** «Все диалоги» с пустой папки (4.51): снять выбор папки. */
    fun selectAll() {
        if (selectedId == null) return
        selectedId = null
        syncChecks()
        onPicked()
    }

    private fun syncChecks() {
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as Chip
            val should = (chip.tag as? Long) == selectedId
            if (chip.isChecked != should) chip.isChecked = should
        }
    }
}
