package com.example.vkweb.people

import android.content.Context
import androidx.core.widget.doAfterTextChanged
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.R
import com.example.vkweb.diag.FlowLog
import com.example.vkweb.offline.ListMerge
import com.example.vkweb.ui.ListUpdates
import com.example.vkweb.ui.LoadMoreTrigger
import com.example.vkweb.ui.OverlayPanel
import com.example.vkweb.ui.PanelFrame
import com.example.vkweb.ui.EmptyState

/**
 * Свой экран «Друзья» / «Сообщества» поверх страницы VK (layout/panel_people.xml). Страница остаётся
 * под ним: из её ответов приходят данные ([PeoplePayload]), прокрутка до конца просит следующую порцию.
 * Поиск — по уже загруженному списку; пока список неполный, при поиске он догружается сам.
 * Сохранённое показывается сразу; свежее встаёт поверх него без скачков ([ListUpdates]).
 */
class PeoplePanel(
    root: View,
    onOpen: (PeopleEntry) -> Unit,
    onMessage: (PeopleEntry) -> Unit,
    onLoadMore: () -> Unit,
) : OverlayPanel {

    private val context: Context = root.context
    override val frame = PanelFrame(root)
    private val list: RecyclerView = root.findViewById(R.id.people_list)
    private val search: EditText = root.findViewById(R.id.people_search)
    private val groupSeen = GroupSeen(context)
    private val adapter: PeopleAdapter = PeopleAdapter(
        onOpen = { e ->
            // Открыли сообщество — его новые записи считаются прочитанными (VK сам счётчик не сбросит).
            if (e.kind != PeopleKind.FRIENDS && e.counter > 0) {
                groupSeen.markSeen(e.id, e.counter)
                refreshCounters()
            }
            onOpen(e)
        },
        onMessage = onMessage,
        counterOf = { e -> if (e.kind == PeopleKind.FRIENDS) e.counter else groupSeen.shown(e.id, e.counter) },
    )

    @Suppress("NotifyDataSetChanged")
    private fun refreshCounters() {
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }
    private val layout = LinearLayoutManager(context)
    private val loadMore = LoadMoreTrigger(list, layout, PRELOAD_DISTANCE, onLoadMore)

    /** Какой список сейчас на экране; null — ещё ничего не показывали. */
    var kind: PeopleKind? = null
        private set
    private var shownKey: String? = null
    private var items: List<PeopleEntry> = emptyList()
    private var requests: List<PeopleEntry> = emptyList()
    /** «Возможно, вы знакомы»: под списком друзей (3.83). */
    private var suggestions: List<PeopleEntry> = emptyList()
    private var total = 0
    private var query = ""
    /** Пришли ли данные (свежие или сохранённые) — пустой список тоже ответ. */
    private var received = false

    val entries: List<PeopleEntry> get() = items

    override val isShown: Boolean get() = frame.isShown
    override val hasData: Boolean get() = received
    override var isShowingSaved: Boolean = false
        private set

    init {
        list.layoutManager = layout
        list.adapter = adapter
        ListUpdates.prepare(list)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
            }
        })
        // Поиск запускается после короткой паузы в наборе, а не на каждую букву: список — сотни и тысячи людей.
        search.doAfterTextChanged {
            search.removeCallbacks(applyQuery)
            search.postDelayed(applyQuery, SEARCH_DELAY_MS)
        }
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
        }
    }

    /** Показать экран раздела. Другой раздел — список очищается, пока не придут его данные. */
    fun show(kind: PeopleKind) {
        if (kind != this.kind) reset(kind)
        frame.show()
        if (!received) frame.showLoading()
    }

    override fun hide() {
        if (!frame.isShown) return
        hideKeyboard()
        search.clearFocus()
        frame.hide()
    }

    override fun clear() {
        val current = kind ?: return
        reset(current)
        if (frame.isShown) frame.showLoading()
    }

    override fun setBanner(text: Int?) {
        frame.setBanner(text)
        if (text != null && !received) frame.showNothingSaved()
    }

    /**
     * Свежие данные страницы. Тот же список — свежий порядок сверху, ещё не догруженное из сохранённого
     * ниже, строка перед глазами остаётся на месте. Другой список (другая страница) — замена.
     */
    fun update(payload: PeoplePayload) {
        if (payload.kind != kind) return
        val same = payload.key == shownKey
        val complete = payload.isComplete
        shownKey = payload.key
        val before = items
        items = ListMerge.accept(items, payload.items, same, complete) { it.id }
        FlowLog.merged("друзья", kind?.name.orEmpty(), before, items) { it.id }
        val friendIds = items.mapTo(HashSet()) { it.id }
        // Принятая заявка уже в друзьях — второй раз не показываем.
        requests = ListMerge.accept(requests, payload.requests, same, complete) { it.id }
            .filter { it.id !in friendIds }
        // Подсказки приходят одним ответом: пустые в очередном сообщении — «не приходили», прежние остаются.
        if (payload.suggestions.isNotEmpty() || !same) suggestions = payload.suggestions
        val requestIds = requests.mapTo(HashSet()) { it.id }
        suggestions = suggestions.filter { it.id !in friendIds && it.id !in requestIds }
        total = payload.total
        received = true
        // Пока пришли одни заявки, на экране по-прежнему сохранённый список.
        if (payload.listSeen) isShowingSaved = false
        loadMore.onLoaded(payload.hasMore)
        render()
        // Ищут, а список ещё неполный — догружаем, чтобы поиск шёл по всем.
        if (query.isNotBlank()) loadMore.request()
    }

    /** Сохранённый список: если свежий уже показан — только дополняет его снизу недостающим. */
    fun showSaved(payload: PeoplePayload) {
        if (payload.kind != kind || (shownKey != null && shownKey != payload.key)) return
        isShowingSaved = !received
        shownKey = payload.key
        items = ListMerge.appendMissing(items, payload.items) { it.id }
        if (requests.isEmpty()) requests = payload.requests
        if (suggestions.isEmpty()) suggestions = payload.suggestions
        if (total == 0) total = payload.total
        received = true
        render()
    }

    private fun reset(kind: PeopleKind) {
        this.kind = kind
        shownKey = null
        items = emptyList()
        requests = emptyList()
        suggestions = emptyList()
        total = 0
        received = false
        isShowingSaved = false
        loadMore.reset()
        query = ""
        search.setText("")
        search.setHint(if (kind == PeopleKind.FRIENDS) R.string.people_search_friends else R.string.people_search_groups)
        adapter.submitList(null)
    }

    /** Текст читается в момент запуска: пока шла пауза, поле могли очистить (смена раздела). */
    private val applyQuery = Runnable { onQueryChanged(search.text?.toString().orEmpty()) }

    private var memoQuery = ""
    private var memoItems: List<PeopleEntry>? = null
    private var memoRequests: List<PeopleEntry>? = null
    private var memo: List<PeopleEntry> = emptyList()

    private fun onQueryChanged(value: String) {
        if (value == query) return
        query = value
        // Новый поиск — с начала списка, а не с места, где был прежний.
        adapter.submitList(buildRows()) { layout.scrollToPosition(0) }
        updateStatus()
        if (query.isNotBlank()) loadMore.request()
    }

    private fun render() {
        ListUpdates.submitAnchored(list, adapter, buildRows(), PeopleRow::key)
        loadMore.onListChanged()
        updateStatus()
    }

    private fun updateStatus() {
        val currentKind = kind ?: return
        when {
            !received -> Unit
            // 4.51: пока ищем в догружаемом — только пояснение; не нашли — «Сбросить поиск».
            query.isNotBlank() && filtered().isEmpty() && loadMore.hasMore ->
                frame.showEmpty(EmptyState.Content(R.string.people_searching, R.drawable.ic_search))
            query.isNotBlank() && filtered().isEmpty() ->
                frame.showEmpty(
                    EmptyState.Content(
                        R.string.people_not_found, R.drawable.ic_search,
                        action = EmptyState.Action(R.string.empty_reset_search) { search.setText("") },
                    ),
                )
            query.isBlank() && items.isEmpty() && requests.isEmpty() ->
                frame.showEmptyList(
                    if (currentKind == PeopleKind.FRIENDS) R.string.people_friends_empty else R.string.people_groups_empty,
                    if (currentKind == PeopleKind.FRIENDS) R.drawable.ic_section_friends else R.drawable.ic_section_groups,
                )
            else -> frame.showContent()
        }
    }

    /** Найденные: считается один раз на запрос и набор людей (строки списка и статус берут одно и то же). */
    private fun filtered(): List<PeopleEntry> {
        if (memoQuery == query && memoItems === items && memoRequests === requests) return memo
        val q = PeopleFormat.normalize(query)
        memo = if (q.isEmpty()) requests + items else (requests + items).filter { PeopleFormat.matchesNormalized(it, q) }
        memoQuery = query
        memoItems = items
        memoRequests = requests
        return memo
    }

    /** Строки списка: заявки и все друзья с заголовками или найденное. */
    private fun buildRows(): List<PeopleRow> {
        val rows = ArrayList<PeopleRow>(items.size + requests.size + 2)
        if (query.isNotBlank()) {
            val found = filtered()
            if (found.isNotEmpty()) {
                val res = if (loadMore.hasMore) R.string.people_found_partial else R.string.people_found
                rows += PeopleRow.Header("found", context.getString(res, found.size))
                found.forEach { rows += PeopleRow.Item(it) }
            }
            return rows
        }
        if (requests.isNotEmpty()) {
            rows += PeopleRow.Header("requests", context.getString(R.string.people_requests_header, requests.size))
            requests.forEach { rows += PeopleRow.Item(it) }
        }
        val currentKind = kind
        if (items.isNotEmpty() && currentKind != null) {
            rows += PeopleRow.Header("all", allHeader(currentKind))
            items.forEach { rows += PeopleRow.Item(it) }
        }
        // Под списком, когда он догружен: иначе подсказки уезжали бы вниз с каждой порцией.
        if (suggestions.isNotEmpty() && !loadMore.hasMore) {
            rows += PeopleRow.Header("suggestions", context.getString(R.string.people_suggestions_header, suggestions.size))
            suggestions.forEach { rows += PeopleRow.Item(it) }
        }
        return rows
    }

    /** «Все друзья · 128» / «Подписки · 42»; пока число неизвестно и список неполный — без числа. */
    private fun allHeader(kind: PeopleKind): String {
        val count = when {
            total > 0 -> total
            !loadMore.hasMore -> items.size
            else -> 0
        }
        val titleRes = if (kind == PeopleKind.FRIENDS) R.string.people_friends_header else R.string.people_groups_header
        val title = context.getString(titleRes)
        return if (count > 0) "$title · $count" else title
    }

    private fun hideKeyboard() = com.example.vkweb.ui.Views.hideKeyboard(search)

    private companion object {
        const val PRELOAD_DISTANCE = 8
        const val SEARCH_DELAY_MS = 150L
    }
}
