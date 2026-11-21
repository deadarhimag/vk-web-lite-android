package com.example.vkweb.video

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.R
import com.example.vkweb.offline.ImageCache
import com.example.vkweb.ui.ChipRow
import com.example.vkweb.ui.ListUpdates
import com.example.vkweb.ui.LoadMoreTrigger
import com.example.vkweb.ui.OverlayPanel
import com.example.vkweb.ui.PanelFrame
import com.example.vkweb.ui.Views

/**
 * Свой каталог VK Видео (layout/panel_video.xml, 3.78): ролики главной и подборок списком — превью 16:9 с
 * длительностью, название, автор и просмотры. Нажатие — проигрыватель приложения ([onOpen]).
 */
class VideoPanel(
    root: View,
    private val onOpen: (VideoItem) -> Unit,
    onLoadMore: () -> Unit,
    /** Выбран раздел каталога (адрес его страницы). */
    private val onSection: (String) -> Unit,
) : OverlayPanel {

    override val frame = PanelFrame(root)
    private val list: RecyclerView = root.findViewById(R.id.video_list)
    private val layout = LinearLayoutManager(root.context)
    private val adapter = Adapter()
    private val loadMore = LoadMoreTrigger(list, layout, PRELOAD_DISTANCE, onLoadMore)

    // Разделы (3.85): запоминаются между страницами каталога — пока грузится выбранный, полоса остаётся на месте.
    private var sections: List<VideoSection> = emptyList()
    private val sectionRow = ChipRow(root.findViewById(R.id.video_sections_scroll), root.findViewById(R.id.video_sections)) { i ->
        sections.getOrNull(i)?.let { pickSection(it) }
    }
    private var subs: List<VideoSection> = emptyList()
    private val subRow = ChipRow(root.findViewById(R.id.video_subsections_scroll), root.findViewById(R.id.video_subsections)) { i ->
        subs.getOrNull(i)?.let { pickSection(it) }
    }

    var key: String? = null
        private set
    private var items: List<VideoItem> = emptyList()
    private var received = false

    override val isShown: Boolean get() = frame.isShown
    override val hasData: Boolean get() = received && items.isNotEmpty()
    override val isShowingSaved: Boolean get() = false

    init {
        list.layoutManager = layout
        list.adapter = adapter
        ListUpdates.prepare(list)
    }

    fun show(key: String) {
        if (key != this.key) reset(key)
        frame.show()
        if (!hasData) frame.showLoading()
    }

    override fun hide() = frame.hide()

    override fun clear() {
        val k = key ?: return
        reset(k)
        if (frame.isShown) frame.showLoading()
    }

    override fun setBanner(text: Int?) = frame.setBanner(text)

    fun update(catalog: VideoCatalog) {
        if (catalog.key != key) return
        if (catalog.sections.isNotEmpty()) renderSections(catalog.sections)
        // Страница присылает каталог целиком, догруженное — в конце: прежние ролики остаются на своих местах.
        items = catalog.items
        received = true
        loadMore.onLoaded(catalog.hasMore)
        if (items.isEmpty()) {
            frame.showEmptyList(R.string.video_list_empty, R.drawable.ic_section_video)
            return
        }
        frame.showContent()
        ListUpdates.submitAnchored(list, adapter, items, VideoItem::id)
        loadMore.onListChanged()
    }

    private fun renderSections(list: List<VideoSection>) {
        sections = list
        val open = list.indexOfFirst { it.selected }
        sectionRow.render(list.map { it.title }, open)
        subs = list.getOrNull(open)?.subs.orEmpty()
        subRow.render(subs.map { it.title }, subs.indexOfFirst { it.selected })
    }

    private fun pickSection(section: VideoSection) {
        // Отметка — сразу, список — когда страница раздела пришлёт свой каталог.
        renderSections(sections.map { s ->
            val inside = s.subs.any { it.url == section.url }
            s.copy(selected = s.url == section.url || inside, subs = s.subs.map { it.copy(selected = it.url == section.url) })
        })
        frame.showLoading()
        onSection(section.url)
    }

    private fun reset(key: String) {
        this.key = key
        items = emptyList()
        received = false
        loadMore.reset()
        adapter.submitList(null)
        layout.scrollToPosition(0)
    }

    private inner class Adapter : ListAdapter<VideoItem, Holder>(Diff) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    }

    private inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.video_card_cover)
        private val duration: TextView = view.findViewById(R.id.video_card_duration)
        private val avatar: ImageView = view.findViewById(R.id.video_card_avatar)
        private val title: TextView = view.findViewById(R.id.video_card_title)
        private val meta: TextView = view.findViewById(R.id.video_card_meta)
        private val images = ImageCache.get(view.context)
        private var item: VideoItem? = null

        init {
            Views.clipRounded(cover, Views.dp(cover, COVER_RADIUS_DP).toFloat())
            Views.clipCircle(avatar)
            view.setOnClickListener { item?.let(onOpen) }
        }

        fun bind(v: VideoItem) {
            item = v
            // Превью — всегда 16:9 по ширине списка: место под картинку есть до её загрузки, список не прыгает.
            val width = (list.width - list.paddingLeft - list.paddingRight).takeIf { it > 0 } ?: itemView.resources.displayMetrics.widthPixels
            val coverWidth = width - Views.dp(itemView, ITEM_PADDING_DP) * 2
            cover.layoutParams = cover.layoutParams.apply { height = coverWidth * 9 / 16 }
            images.load(cover, v.cover.ifEmpty { null }, coverWidth)
            duration.text = if (v.live) itemView.context.getString(R.string.video_live) else VideoFormat.duration(v.durationSec)
            duration.visibility = if (duration.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            avatar.visibility = if (v.avatar.isEmpty()) View.GONE else View.VISIBLE
            if (v.avatar.isNotEmpty()) images.load(avatar, v.avatar, Views.dp(avatar, AVATAR_DP))
            title.text = v.title
            meta.text = listOf(v.author, if (v.views > 0) VideoFormat.views(v.views) else "").filter { it.isNotEmpty() }.joinToString(" · ")
            itemView.contentDescription = listOf(v.title, duration.text.toString(), meta.text.toString()).filter { it.isNotBlank() }.joinToString(", ")
        }
    }

    private object Diff : DiffUtil.ItemCallback<VideoItem>() {
        override fun areItemsTheSame(a: VideoItem, b: VideoItem) = a.id == b.id
        override fun areContentsTheSame(a: VideoItem, b: VideoItem) = a == b
    }

    private companion object {
        const val PRELOAD_DISTANCE = 4
        const val COVER_RADIUS_DP = 12
        const val AVATAR_DP = 32
        const val ITEM_PADDING_DP = 12
    }
}
