package com.example.vkweb.ui

import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.media.PlaylistEntry
import com.example.vkweb.media.PlaylistsPayload

/** Список плейлистов на экране музыки. Каркас — [RefreshableList]; здесь — признак «страница искала и не нашла». */
class PlaylistListView(
    list: RecyclerView,
    onOpen: (PlaylistEntry) -> Unit,
    onLoadMore: () -> Unit,
) {
    private val base = RefreshableList(list, PlaylistAdapter(onOpen), PlaylistEntry::id, "плейлисты", onLoadMore)

    val current: List<PlaylistEntry> get() = base.current
    val isShowingSaved: Boolean get() = base.isShowingSaved
    val isLoaded: Boolean get() = base.isLoaded

    /** Страница искала и не нашла. */
    var none = false
        private set

    var isVisible: Boolean
        get() = base.isVisible
        set(value) { base.isVisible = value }

    /** @return показанный теперь список. */
    fun update(payload: PlaylistsPayload): List<PlaylistEntry> {
        none = payload.none && payload.items.isEmpty() && current.isEmpty()
        return base.refresh(payload.items, payload.hasMore, sameList = true, complete = payload.complete)
    }

    fun showSaved(items: List<PlaylistEntry>) = base.showSaved(items)

    fun clear() {
        none = false
        base.clear()
    }
}
