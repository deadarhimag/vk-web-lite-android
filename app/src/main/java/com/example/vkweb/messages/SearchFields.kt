package com.example.vkweb.messages

import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import com.google.android.material.color.MaterialColors

/**
 * Поле поиска в панели сообщений — в цветах темы (4.31). Подложку задаёт стиль Widget.VkWeb.SearchView, а цвет текста
 * и подсказки AppCompat берёт не из нашей палитры (textColorPrimary и textColorHint базовой темы): в чёрной теме и с
 * цветами обоев они расходились с полем ввода сообщения. Здесь — те же цвета, что у поля ввода.
 */
object SearchFields {

    fun style(view: SearchView) {
        val field = view.findViewById<TextView>(androidx.appcompat.R.id.search_src_text) ?: return
        field.setTextColor(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface))
        field.setHintTextColor(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    /**
     * Пункт меню [item], раскрывающийся в поле поиска (4.67, код ревью 4.64): общее у поиска по диалогам
     * ([DialogSearchBar]) и в переписке ([ChatFind]) — подсказка, цвета, ширина и слушатели. [onSubmit] — кнопка поиска
     * на клавиатуре (клавиатура уже убрана), [onChange] — каждый символ, [onCollapse] — поле свернули,
     * [onExpandChange] — поле раскрыли (true) или свернули (false): экрану — включить или выключить свой «Назад» (4.88).
     * Пункта или поля нет — ничего не делается.
     */
    fun setUp(
        item: MenuItem?,
        hint: CharSequence,
        onSubmit: (String) -> Unit,
        onChange: (String) -> Unit = {},
        onCollapse: () -> Unit,
        imeOptions: Int? = null,
        onExpandChange: (Boolean) -> Unit = {},
    ) {
        val view = item?.actionView as? SearchView ?: return
        view.queryHint = hint
        style(view)
        view.maxWidth = Int.MAX_VALUE
        imeOptions?.let { view.imeOptions = it }
        view.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String?): Boolean {
                // Клавиатура уходит: видно найденное, и экран сразу перестраивается под её отсутствие.
                view.clearFocus()
                onSubmit(text.orEmpty())
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                onChange(newText.orEmpty())
                return true
            }
        })
        item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                onExpandChange(true)
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                onCollapse()
                onExpandChange(false)
                return true
            }
        })
    }

    /** Поле [item] раскрыто — свернуть (для «Назад»). @return true, если свернули. */
    fun close(item: MenuItem?): Boolean {
        if (item == null || !item.isActionViewExpanded) return false
        item.collapseActionView()
        return true
    }
}
