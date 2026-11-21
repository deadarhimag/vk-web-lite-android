package com.example.vkweb.ui

import android.view.View
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Полоса фильтров-чипов над списком своего экрана (разделы VK Видео, 3.85): подписи, отмеченный и нажатие.
 * Перестраивается, только если изменились подписи; отметка ставится без вызова [onPick] (его зовёт лишь нажатие).
 */
class ChipRow(
    private val scroll: View,
    private val group: ChipGroup,
    private val onPick: (Int) -> Unit,
) {
    private var labels: List<String> = emptyList()

    /** [selected] — номер отмеченного (-1 — никакой). Меньше двух подписей — полоса скрыта. */
    fun render(newLabels: List<String>, selected: Int) {
        scroll.visibility = if (newLabels.size >= 2) View.VISIBLE else View.GONE
        group.setOnCheckedStateChangeListener(null)
        if (newLabels != labels || group.childCount != newLabels.size) {
            labels = newLabels
            group.removeAllViews()
            newLabels.forEachIndexed { i, label ->
                group.addView(Chip(group.context, null, com.google.android.material.R.attr.chipStyle).apply {
                    id = View.generateViewId()
                    text = label
                    isCheckable = true
                    isCheckedIconVisible = false
                    tag = i
                    // Нажатие — всегда выбор (4.40). Раньше по смене отметки: нажатие на отмеченный снимало отметку и
                    // ничего не делало, а повторное нажатие после неудавшегося перехода считалось «тот же» и тоже
                    // ничего не делало — «жал на подменю, и не всегда они открывались». Повтор — открыть заново.
                    setOnClickListener {
                        isChecked = true
                        onPick(i)
                    }
                })
            }
        }
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as Chip
            val should = i == selected
            if (chip.isChecked != should) chip.isChecked = should
        }
        // Отмеченный — в видимой части полосы (разделов больше, чем помещается).
        if (selected >= 0) group.getChildAt(selected)?.let { chip -> scroll.post { scroll.scrollTo(maxOf(0, chip.left - chip.width / 2), 0) } }
    }

    init {
        // Отмеченный чип не снимается нажатием (у полос видео этого не было задано в разметке).
        group.isSelectionRequired = true
        group.isSingleSelection = true
    }
}
