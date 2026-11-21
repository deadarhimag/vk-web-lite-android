package com.example.vkweb.ui

import android.view.View
import android.view.ViewGroup
import android.widget.Checkable
import android.widget.HorizontalScrollView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup

/**
 * Вкладки внутреннего меню экрана свайпом (4.19): полоса фильтров клипов («Для вас», «Тренды»…), разделы VK Видео,
 * переключатель «Музыка / Плейлисты», папки сообщений. Свайп выбирает соседнюю вкладку так же, как нажатие на неё —
 * дальше работает обычный обработчик вкладки.
 */
object TabSwipe {

    /** Первая видимая полоса вкладок под [root] (сверху вниз): группа чипов или переключатель кнопок, от двух вкладок. */
    fun findRow(root: View): ViewGroup? {
        if (!root.isShown) return null
        if ((root is ChipGroup || root is MaterialButtonToggleGroup) && tabsOf(root as ViewGroup).size >= 2) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findRow(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** Выбрать соседнюю вкладку [row]; false — за краем или выбирать нечего. */
    fun step(row: ViewGroup, direction: SectionSwipe.Direction): Boolean {
        val tabs = tabsOf(row)
        val current = tabs.indexOfFirst { (it as? Checkable)?.isChecked == true }
        val next = SectionSwipe.neighbor(current, tabs.size, direction) ?: return false
        val tab = tabs[next]
        tab.performClick()
        // Выбранная — в видимой части полосы (вкладок бывает больше, чем помещается).
        (row.parent as? HorizontalScrollView)?.let { scroll -> scroll.post { scroll.smoothScrollTo(maxOf(0, tab.left - tab.width / 2), 0) } }
        return true
    }

    private fun tabsOf(row: ViewGroup): List<View> =
        (0 until row.childCount).map(row::getChildAt).filter { it.visibility == View.VISIBLE && it is Checkable }
}
