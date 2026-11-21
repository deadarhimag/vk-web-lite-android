package com.example.vkweb.messages

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isVisible

/**
 * Две колонки на широком экране (3.94): слева — список диалогов (с папками и текстом «пусто»), справа — переписка.
 * Виды не дублируются: при включении список, папки и пустой текст переносятся в левую колонку, при выключении —
 * возвращаются на прежние места (в тот же родитель, на ту же позицию, с теми же параметрами разметки).
 * Когда включать — [shouldSplit]; что показывать — решает MessagesActivity по [isTwoPane].
 */
class MessagesPanes(
    private val pane: ViewGroup,
    private val paneFrame: FrameLayout,
    private val paneDivider: View,
    private val folders: View,
    private val dialogsList: View,
    private val empty: View,
) {
    var isTwoPane = false
        private set

    private class Slot(val view: View, val parent: ViewGroup, val index: Int, val params: ViewGroup.LayoutParams)

    // Места в обычной разметке — до любых переносов.
    private val slots = listOf(folders, dialogsList, empty).map { v ->
        val parent = v.parent as ViewGroup
        Slot(v, parent, parent.indexOfChild(v), v.layoutParams)
    }

    /** @return true, если раскладка сменилась (экран должен заново выставить видимость). */
    fun set(twoPane: Boolean): Boolean {
        if (twoPane == isTwoPane) return false
        isTwoPane = twoPane
        slots.forEach { (it.view.parent as? ViewGroup)?.removeView(it.view) }
        if (twoPane) {
            // Папки — под заголовком колонки, список и «пусто» — в её рамке.
            pane.addView(folders, 1, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            paneFrame.addView(dialogsList, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            paneFrame.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            // По возрастанию позиции: каждая вставка ставит вид туда, где он был.
            slots.sortedBy { it.index }.forEach { it.parent.addView(it.view, it.index.coerceAtMost(it.parent.childCount), it.params) }
        }
        pane.isVisible = twoPane
        paneDivider.isVisible = twoPane
        return true
    }

    companion object {
        /** Справа должно остаться не меньше этого — иначе переписка слишком узкая. */
        const val MIN_CHAT_DP = 480

        /**
         * Две колонки, если включены в настройках и после закреплённого меню ([navDp], 0 — меню выезжает) и колонки
         * списка ([paneDp]) справа остаётся не меньше [MIN_CHAT_DP].
         */
        fun shouldSplit(enabled: Boolean, screenWidthDp: Int, navDp: Int, paneDp: Int): Boolean =
            enabled && screenWidthDp - navDp - paneDp >= MIN_CHAT_DP
    }
}
