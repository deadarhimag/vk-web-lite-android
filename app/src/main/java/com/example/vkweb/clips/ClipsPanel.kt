package com.example.vkweb.clips

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.R
import com.example.vkweb.offline.ImageCache
import com.example.vkweb.ui.ListUpdates
import com.example.vkweb.ui.LoadMoreTrigger
import com.example.vkweb.ui.OverlayPanel
import com.example.vkweb.ui.PanelFrame

/**
 * Раздел клипов своим экраном (layout/panel_clips.xml, 3.84): вкладки страницы фильтрами и лента сеткой превью 9:16.
 * Отчёт 3.83: «не открывается общий список клипов с его фильтрами» — раздел сразу открывал проигрыватель.
 * Нажатие превью — свой проигрыватель с этого ролика ([onOpen]); вкладка — [onTab] (её нажимает страница).
 */
class ClipsPanel(
    root: View,
    private val onOpen: (ClipItem) -> Unit,
    private val onTab: (Int) -> Unit,
    onLoadMore: () -> Unit,
    /** Экран показан/скрыт: под ним страница клипов должна молчать, пока он виден. */
    private val onShownChanged: (Boolean) -> Unit,
) : OverlayPanel {

    private val context = root.context
    override val frame = PanelFrame(root)
    private val tabsRow = com.example.vkweb.ui.ChipRow(root.findViewById(R.id.clips_tabs_scroll), root.findViewById(R.id.clips_tabs)) { i ->
        frame.showLoading()
        onTab(i)
    }
    private val grid: RecyclerView = root.findViewById(R.id.clips_grid)
    private val layout = GridLayoutManager(context, COLUMNS)
    private val adapter = Adapter()
    private val loadMore = LoadMoreTrigger(grid, layout, PRELOAD_DISTANCE, onLoadMore)

    private var items: List<ClipItem> = emptyList()
    private var shownGeneration = -1
    private var received = false

    override val isShown: Boolean get() = frame.isShown
    override val hasData: Boolean get() = received && items.isNotEmpty()
    override val isShowingSaved: Boolean get() = false

    init {
        grid.layoutManager = layout
        grid.adapter = adapter
        ListUpdates.prepare(grid)
    }

    fun show() {
        val was = frame.isShown
        frame.show()
        if (!hasData) frame.showLoading()
        if (!was) onShownChanged(true)
    }

    override fun hide() {
        if (!frame.isShown) return
        frame.hide()
        onShownChanged(false)
    }

    override fun clear() {
        items = emptyList()
        received = false
        shownGeneration = -1
        loadMore.reset()
        adapter.submitList(null)
        if (frame.isShown) frame.showLoading()
    }

    override fun setBanner(text: Int?) = frame.setBanner(text)

    /** Лента и вкладки из [ClipsHub]. */
    fun update(list: List<ClipItem>, tabs: List<ClipTab>, hasMore: Boolean, generation: Int) {
        renderTabs(tabs)
        val fresh = generation != shownGeneration
        shownGeneration = generation
        items = list
        received = true
        loadMore.onLoaded(hasMore)
        if (items.isEmpty()) {
            frame.showEmptyList(R.string.clips_list_empty, R.drawable.ic_section_clips)
            adapter.submitList(emptyList())
            return
        }
        frame.showContent()
        if (fresh) {
            // Другая вкладка — другая лента: с начала, без попытки удержать прежнюю строку.
            adapter.submitList(items) { layout.scrollToPosition(0) }
        } else {
            ListUpdates.submitAnchored(grid, adapter, items, ClipItem::id)
        }
        loadMore.onListChanged()
    }

    /** Вкладки ленты — той же полосой чипов, что подборки VK Видео ([ChipRow]; код ревью 4.43). */
    private fun renderTabs(tabs: List<ClipTab>) = tabsRow.render(tabs.map { it.label }, tabs.indexOfFirst { it.selected })

    private inner class Adapter : ListAdapter<ClipItem, Holder>(Diff) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_clip, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    }

    private inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.clip_card_cover)
        private val likes: TextView = view.findViewById(R.id.clip_card_likes)
        private val author: TextView = view.findViewById(R.id.clip_card_author)
        private val images = ImageCache.get(view.context)
        private var item: ClipItem? = null

        init {
            view.setOnClickListener { item?.let(onOpen) }
            val heart = ContextCompat.getDrawable(view.context, R.drawable.ic_post_like)?.mutate()?.apply {
                setTint(view.context.getColor(R.color.media_on))
                val size = (view.resources.displayMetrics.density * HEART_DP).toInt()
                setBounds(0, 0, size, size)
            }
            likes.setCompoundDrawablesRelative(heart, null, null, null)
        }

        fun bind(c: ClipItem) {
            item = c
            // Превью 9:16 по ширине колонки: место под картинку есть до её загрузки, сетка не прыгает.
            val width = ((grid.width - grid.paddingLeft - grid.paddingRight).takeIf { it > 0 }
                ?: itemView.resources.displayMetrics.widthPixels) / COLUMNS
            cover.layoutParams = cover.layoutParams.apply { height = width * 16 / 9 }
            images.load(cover, c.cover.ifEmpty { null }, width)
            likes.text = ClipsFormat.count(c.likes)
            likes.visibility = if (c.likes > 0) View.VISIBLE else View.GONE
            author.text = c.author
            author.visibility = if (c.author.isEmpty()) View.GONE else View.VISIBLE
            itemView.contentDescription = listOf(c.author, c.text.take(80)).filter { it.isNotBlank() }.joinToString(", ")
                .ifEmpty { itemView.context.getString(R.string.clips_card_description) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ClipItem>() {
        override fun areItemsTheSame(a: ClipItem, b: ClipItem) = a.id == b.id
        override fun areContentsTheSame(a: ClipItem, b: ClipItem) = a == b
    }

    private companion object {
        const val COLUMNS = 3
        const val PRELOAD_DISTANCE = 9
        const val HEART_DP = 14
    }
}

/** Подписи клипов: «7», «12K», «1.2M» — как в проигрывателе. */
object ClipsFormat {
    fun count(n: Int): String = when {
        n >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fM", n / 1_000_000.0)
        n >= 10_000 -> "${n / 1000}K"
        else -> n.toString()
    }
}
