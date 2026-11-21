package com.example.vkweb.messages

import android.app.Activity
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.ui.Notice

/**
 * Поиск в открытой переписке (4.0; с 4.5 — отдельно от MessagesActivity). С 4.10 — поле в панели, как поиск по
 * диалогам ([DialogSearchBar]), а не отдельное окно: окно выглядело чужим (простое поле без оформления темы, длинная
 * подсказка обрезалась), а клавиатура принадлежала ему — после её закрытия экран переписки мог остаться поднятым,
 * потому что сам экран об уходе клавиатуры не узнавал. Найти — кнопкой поиска на клавиатуре; повторно тот же
 * запрос — следующее найденное (как «Дальше» во всплывающей строке). Что считается совпадением — [ChatSearch].
 */
class ChatFind(
    private val activity: Activity,
    private val item: MenuItem?,
    private val list: RecyclerView,
    /** Поле раскрыто (true) или свёрнуто (4.88): «Назад» экрана его сворачивает. */
    private val onExpandChange: (Boolean) -> Unit = {},
    /** Сообщения на экране (в порядке показа). */
    private val items: () -> List<ChatMessage>,
) {
    private var query = ""
    private var results: List<String> = emptyList()
    private var at = 0

    /** Пункт виден только в открытой переписке; спрятали — поле закрывается. */
    var isVisible: Boolean
        get() = item?.isVisible == true
        set(value) {
            if (!value) close()
            item?.isVisible = value
        }

    init {
        // Ищем по нажатию «Найти», а не по каждой букве: иначе переписка прыгала бы при наборе.
        SearchFields.setUp(
            item, activity.getString(R.string.chat_find),
            onSubmit = { text ->
                val value = text.trim()
                if (value.isNotEmpty() && value == query && results.size > 1) next() else run(value)
            },
            onCollapse = { reset() },
            imeOptions = EditorInfo.IME_ACTION_SEARCH,
            onExpandChange = onExpandChange,
        )
    }

    /** Открыли переписку: найденное в прошлой к ней не относится. */
    fun reset() {
        results = emptyList()
        at = 0
    }

    /** Поле открыто — закрыть (для «Назад»). @return true, если закрыли. */
    fun close(): Boolean = SearchFields.close(item)

    /** Найти в загруженных; первое — ближайшее к низу. Следующие — «Дальше» или тот же запрос ещё раз. */
    private fun run(text: String) {
        query = text
        if (query.isEmpty()) return
        val shown = items()
        results = ChatSearch.find(shown, query).map { shown[it].id }
        at = 0
        AppLog.i(LogTag.MESSAGES, "поиск в переписке", "найдено" to results.size)
        if (results.isEmpty()) {
            Notice.show(activity, R.string.chat_find_none)
            return
        }
        showFound()
    }

    private fun next() {
        at = (at + 1) % results.size
        showFound()
    }

    private fun showFound() {
        val id = results.getOrNull(at) ?: return
        val index = items().indexOfFirst { it.id == id }
        if (index < 0) return
        scrollAndFlash(list, index)
        val text = activity.getString(R.string.chat_find_result, at + 1, results.size)
        if (results.size > 1) {
            Notice.show(activity, text, long = true, action = activity.getString(R.string.chat_find_next)) { next() }
        } else {
            Notice.show(activity, text)
        }
    }

    companion object {
        private const val FLASH_MS = 900L

        /** Сообщение [index] — на четверть экрана от верха и коротко подсвечено (поиск и переход к ответу). */
        fun scrollAndFlash(list: RecyclerView, index: Int) {
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, list.height / 4)
            list.post { list.findViewHolderForAdapterPosition(index)?.itemView?.let(::flash) }
        }

        private fun flash(view: View) {
            view.animate().cancel()
            view.alpha = 0.35f
            view.animate().alpha(1f).setDuration(FLASH_MS).start()
        }
    }
}
