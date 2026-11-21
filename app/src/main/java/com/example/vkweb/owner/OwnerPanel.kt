package com.example.vkweb.owner

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.R
import com.example.vkweb.diag.LogTag
import com.example.vkweb.diag.FlowLog
import com.example.vkweb.diag.AppLog
import com.example.vkweb.offline.ListMerge
import com.example.vkweb.ui.ListUpdates
import com.example.vkweb.ui.LoadMoreTrigger
import com.example.vkweb.ui.OverlayPanel
import com.example.vkweb.ui.PanelFrame

/**
 * Свой экран страницы человека или сообщества поверх страницы VK (layout/panel_owner.xml): шапка и стена.
 * Страница остаётся под ним — из её ответов приходят данные ([OwnerPayload]), прокрутка до конца
 * просит её догрузить следующие записи. Действия, которых здесь нет (добавить в друзья, подписаться,
 * лайк, комментарий), — на странице VK: кнопка на панели или нажатие на пост.
 * Сохранённое показывается сразу; свежее встаёт поверх без скачков: стена не сжимается до первой
 * свежей порции, новые записи появляются сверху, а та, что перед глазами, остаётся на месте.
 */
class OwnerPanel(
    root: View,
    actions: OwnerActions,
    onLoadMore: () -> Unit,
    /**
     * Записи (ключи «владелец_номер»), которые побывали на экране: страница выводит их и у себя, чтобы VK отметил их
     * прочитанными (счётчик новых записей сообщества). Каждая сообщается один раз за показ страницы.
     */
    private val onPostsSeen: (List<String>) -> Unit = {},
) : OverlayPanel {

    private val context: Context = root.context
    override val frame = PanelFrame(root)
    private val list: RecyclerView = root.findViewById(R.id.owner_list)
    private val adapter = OwnerAdapter(actions)
    private val layout = LinearLayoutManager(context)
    private val loadMore = LoadMoreTrigger(list, layout, PRELOAD_DISTANCE, onLoadMore)

    /** Какая страница на экране (путь, как его видит скрипт страницы). */
    var key: String? = null
        private set
    private var payload: OwnerPayload? = null

    val current: OwnerPayload? get() = payload

    override val isShown: Boolean get() = frame.isShown
    override val hasData: Boolean get() = payload?.header != null || (payload != null && OwnerPath.isFeed(key))
    override var isShowingSaved: Boolean = false
        private set

    /** Уже сообщённые увиденные записи этой страницы. */
    private val reportedSeen = HashSet<String>()

    /**
     * Записи, пришедшие от страницы в этом показе: только их страница может вывести у себя. Сохранённые (ещё не
     * догруженные страницей) не сообщаются — иначе они считались бы увиденными и не повторялись (код ревью 3.59).
     */
    private val freshKeys = HashSet<String>()

    init {
        list.layoutManager = layout
        list.adapter = adapter
        ListUpdates.prepare(list)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) reportSeen()
            }
        })
    }

    /** Записи, видимые сейчас (остановилась прокрутка или пришли данные), — те, о которых ещё не сообщали. */
    private fun reportSeen() {
        if (!frame.isShown || isShowingSaved) return
        val first = layout.findFirstVisibleItemPosition()
        val last = layout.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last < first) return
        val fresh = (first..last).mapNotNull { (adapter.currentList.getOrNull(it) as? OwnerRow.Post)?.post?.key }
            .filter { it.isNotEmpty() && it in freshKeys && reportedSeen.add(it) }
        if (fresh.isNotEmpty()) onPostsSeen(fresh)
    }

    /** Показать экран страницы [key]. Другая страница — экран очищается до прихода её данных. */
    fun show(key: String) {
        if (!OwnerPath.sameKey(key, this.key)) reset(key)
        frame.show()
        if (!hasData) frame.showLoading()
    }

    override fun hide() = frame.hide()

    override fun clear() {
        val current = key ?: return
        reset(current)
        if (frame.isShown) frame.showLoading()
    }

    override fun setBanner(text: Int?) {
        frame.setBanner(text)
        if (text != null && !hasData) frame.showNothingSaved()
    }

    /**
     * Свежие данные страницы. Пришла только часть (стена раньше шапки) — недостающее берётся из
     * показанного; записи — свежие сверху, ещё не догруженные из показанного ниже.
     */
    fun update(fresh: OwnerPayload) {
        if (!OwnerPath.sameKey(fresh.key, key)) return
        val old = payload
        val complete = fresh.wallSeen && !fresh.hasMore
        val before = old?.posts.orEmpty()
        val known = before.associateBy { it.key }
        // Те же картинки с другой подписью в адресе не должны «менять» запись при каждом обновлении (см. PostMerge).
        val merged = ListMerge.refresh(before, fresh.posts.map { PostMerge.stabilize(known[it.key], it) }, complete) { it.key }
        FlowLog.merged("страница", "записи", before, merged) { it.key }
        PostMerge.describeChanges(before, merged).takeIf { it.isNotEmpty() }
            ?.let { AppLog.d(LogTag.SCREEN, "страница: изменились поля", "что" to it) }
        payload = fresh.copy(
            header = fresh.header ?: old?.header,
            posts = merged,
            wallSeen = fresh.wallSeen || old?.wallSeen == true,
        )
        if (fresh.header != null || OwnerPath.isFeed(fresh.key)) isShowingSaved = false
        fresh.posts.mapTo(freshKeys) { it.key }
        // Страница хранит не больше MAX_POSTS записей: дальше догружать нечего (код ревью 3.59).
        loadMore.onLoaded(fresh.hasMore && merged.size < MAX_POSTS)
        render()
    }

    /** Лайк записи [key] на экране (сразу по нажатию, затем — по ответу страницы). Записи нет — ничего. */
    fun setLike(key: String, liked: Boolean, likes: Int) {
        val p = payload ?: return
        if (p.posts.none { it.key == key }) return
        payload = p.copy(posts = p.posts.map { if (it.key == key) it.copy(liked = liked, likes = likes.coerceAtLeast(0)) else it })
        render()
    }

    /** Сохранённое без сети или пока свежее не пришло: показывается, только если шапки ещё нет. */
    fun showSaved(saved: OwnerPayload) {
        if (hasData) return
        if (!OwnerPath.sameKey(saved.key, key) && saved.ownerId != OwnerPath.ownerIdOf(key)) return
        val partial = payload
        if (partial != null) {
            // Свежие записи уже пришли (без шапки): из сохранённого берём только шапку и ещё не пришедшие записи —
            // свежие, «есть ещё» и догрузку не затираем (код ревью 3.59).
            val freshKeysNow = partial.posts.mapTo(HashSet()) { it.key }
            payload = partial.copy(header = saved.header, posts = partial.posts + saved.posts.filter { it.key !in freshKeysNow })
            isShowingSaved = true
            render()
            return
        }
        payload = saved.copy(key = key ?: saved.key)
        isShowingSaved = true
        loadMore.onLoaded(hasMore = false) // догружать будет страница, когда пришлёт свежее
        render()
    }

    private fun reset(key: String) {
        this.key = key
        payload = null
        isShowingSaved = false
        loadMore.reset()
        adapter.reset()
        adapter.submitList(null)
        layout.scrollToPosition(0)
        reportedSeen.clear()
        freshKeys.clear()
    }

    private fun render() {
        val p = payload ?: return
        val header = p.header
        // Лента новостей (3.77): шапки нет, только записи.
        if (header == null && !OwnerPath.isFeed(p.key)) return
        frame.showContent()
        adapter.ownerId = header?.ownerId ?: 0
        // Видимые записи — после того как список применён (раньше считались по прежнему списку, код ревью 3.59).
        ListUpdates.submitAnchored(list, adapter, rowsOf(header, p), OwnerRow::key) { list.post { reportSeen() } }
        loadMore.onListChanged()
    }

    private fun rowsOf(header: OwnerHeader?, p: OwnerPayload): List<OwnerRow> {
        val rows = ArrayList<OwnerRow>(p.posts.size + 2)
        if (header != null) rows += OwnerRow.Header(header, noticeOf(header, p), wallTitle = p.posts.isNotEmpty())
        p.posts.forEach { rows += OwnerRow.Post(it) }
        val wallHidden = header != null && (header.deactivated.isNotEmpty() || header.closedProfile)
        if (!wallHidden && (loadMore.hasMore || !p.wallSeen)) rows += OwnerRow.Footer
        return rows
    }

    private fun noticeOf(h: OwnerHeader, p: OwnerPayload): String = when {
        h.deactivated == "deleted" -> context.getString(R.string.owner_deleted)
        h.deactivated.isNotEmpty() -> context.getString(R.string.owner_banned)
        h.closedProfile -> context.getString(R.string.owner_closed_profile)
        !h.isUser && h.closedGroup > 0 && !h.isMember && p.posts.isEmpty() -> context.getString(R.string.owner_closed_group)
        p.wallSeen && p.posts.isEmpty() -> context.getString(R.string.owner_wall_empty)
        else -> ""
    }

    private companion object {
        const val PRELOAD_DISTANCE = 4
        /** Предел записей на странице — как в скрипте страницы (70_owner.js, MAX_POSTS). */
        const val MAX_POSTS = 300
    }
}
