package com.example.vkweb.ui

import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.media.MusicPayload
import com.example.vkweb.media.MusicTrack

/**
 * Один список треков на экране музыки: «Мои треки», треки открытого плейлиста или результаты поиска (4.79). Каркас (сохранённое, свежее,
 * догрузка) — [RefreshableList]; здесь только то, что свойственно трекам: какой список показан и играющий трек.
 */
class TrackListView(
    list: RecyclerView,
    onPlay: (MusicTrack) -> Unit,
    onLoadMore: () -> Unit,
    name: String,
    onAdd: ((MusicTrack) -> Unit)? = null,
    onLongPress: ((MusicTrack) -> Unit)? = null,
) {
    private val adapter = MusicAdapter(onPlay, onAdd, onLongPress)
    private val base = RefreshableList(list, adapter, MusicTrack::id, name, onLoadMore)

    /** Какой список показан (страница музыки): обновлять на месте можно только тот же. */
    var shownKey: String? = null
        private set

    val current: List<MusicTrack> get() = base.current
    val isShowingSaved: Boolean get() = base.isShowingSaved
    val isLoaded: Boolean get() = base.isLoaded

    var isVisible: Boolean
        get() = base.isVisible
        set(value) { base.isVisible = value }

    /** @return показанный теперь список (см. [current]). */
    fun update(payload: MusicPayload): List<MusicTrack> {
        val same = payload.listKey == shownKey
        shownKey = payload.listKey
        // Сократить список можно только по явному «весь список» от страницы: отсутствие «есть ещё» этого не значит.
        return base.refresh(payload.tracks, payload.hasMore, sameList = same, complete = payload.complete)
    }

    /** Сохранённый список: если свежий уже показан — только дополняет его снизу недостающим. */
    fun showSaved(payload: MusicPayload) {
        if (shownKey != null && shownKey != payload.listKey) return
        shownKey = payload.listKey
        base.showSaved(payload.tracks)
    }

    fun clear() {
        shownKey = null
        base.clear()
    }

    fun setCurrentTrack(id: String?, playing: Boolean) = adapter.setCurrentTrack(id, playing)

    /** Кэш изменился: перерисовать значки «в хранилище» (4.97). */
    fun refreshCached() = adapter.refreshCached()

    /** «Добавить к себе» (4.45): кнопка у трека прячется сразу ([added]) или возвращается после ошибки. */
    fun setAdded(id: String, added: Boolean) = if (added) adapter.markAdded(id) else adapter.unmarkAdded(id)

    /** Трек списка, который играет страница (по названию и исполнителю): для выделения и обложки. */
    fun trackFor(title: String, artist: String): MusicTrack? = if (title.isEmpty()) null else current.firstOrNull {
        it.title.equals(title, ignoreCase = true) && (artist.isEmpty() || it.artist.equals(artist, ignoreCase = true))
    }

    fun trackForId(id: String): MusicTrack? = current.firstOrNull { it.id == id }
}
