package com.example.vkweb.messages

import android.view.MenuItem
import androidx.appcompat.widget.SearchView
import com.example.vkweb.R

/**
 * Поиск по диалогам в панели экрана сообщений (3.94): пункт меню раскрывается в поле поиска; каждый символ — новый
 * [query] и [onChange] (список фильтруется [DialogSearch]); свернули — поиск сброшен.
 */
class DialogSearchBar(
    private val item: MenuItem?,
    /** Поле раскрыто (true) или свёрнуто (4.88): «Назад» экрана его сворачивает. */
    private val onExpandChange: (Boolean) -> Unit = {},
    private val onChange: () -> Unit,
) {

    /** Текст поиска; пусто — поиска нет. */
    var query: String = ""
        private set

    /** Пункт виден только на списке диалогов. */
    var isVisible: Boolean
        get() = item?.isVisible == true
        set(value) {
            item?.isVisible = value
        }

    init {
        val context = (item?.actionView as? SearchView)?.context
        if (context != null) {
            SearchFields.setUp(
                item, context.getString(R.string.dialogs_search_hint),
                onSubmit = {},
                onChange = { text ->
                    if (text != query) {
                        query = text
                        onChange()
                    }
                },
                onCollapse = {
                    if (query.isNotEmpty()) {
                        query = ""
                        onChange()
                    }
                },
                onExpandChange = onExpandChange,
            )
        }
    }

    /** Открыт — закрыть (для «Назад»). @return true, если закрыли. */
    fun close(): Boolean = SearchFields.close(item)
}
