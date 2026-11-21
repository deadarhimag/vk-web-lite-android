package com.example.vkweb.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.R
import com.example.vkweb.media.MusicTrack
import com.example.vkweb.offline.ImageCache

/**
 * Список треков. Текущий трек выделен цветом; во время воспроизведения у него также виден значок. [onAdd] (4.45) — кнопка «добавить к себе» у треков, которых
 * ещё нет в музыке человека (в плейлистах); null — кнопки нет («Мои треки»). [onLongPress] (4.79) — долгое нажатие на трек
 * (меню «Треки исполнителя»).
 */
class MusicAdapter(
    private val onClick: (MusicTrack) -> Unit,
    private val onAdd: ((MusicTrack) -> Unit)? = null,
    private val onLongPress: ((MusicTrack) -> Unit)? = null,
) : ListAdapter<MusicTrack, MusicAdapter.Holder>(Diff) {

    private var currentTrackId: String? = null
    private var isPlaying = false

    /** Добавленные к себе в этот раз: кнопка прячется сразу, не дожидаясь свежего списка со страницы. */
    private val addedNow = HashSet<String>()

    /** Трек [id] добавлен к себе (или нажата кнопка — повтор не нужен). */
    fun markAdded(id: String) {
        if (!addedNow.add(id)) return
        currentList.forEachIndexed { index, track -> if (track.id == id) notifyItemChanged(index) }
    }

    /** Добавить не удалось: кнопка возвращается. */
    fun unmarkAdded(id: String) {
        if (!addedNow.remove(id)) return
        currentList.forEachIndexed { index, track -> if (track.id == id) notifyItemChanged(index) }
    }

    /** Текущий трек остаётся выбранным на паузе; [playing] управляет только значком воспроизведения. */
    fun setCurrentTrack(id: String?, playing: Boolean) {
        if (id == currentTrackId && playing == isPlaying) return
        val old = currentTrackId
        currentTrackId = id
        isPlaying = playing
        currentList.forEachIndexed { index, track ->
            // При паузе подсветка остаётся, а значок «сейчас играет» меняется.
            if (track.id == old || track.id == id) notifyItemChanged(index)
        }
    }

    /** Значок «в хранилище» (4.97) — по списку кэша в памяти; перерисовать, когда он изменился. */
    fun refreshCached() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.track_icon)
        private val title: TextView = view.findViewById(R.id.track_title)
        private val artist: TextView = view.findViewById(R.id.track_artist)
        private val duration: TextView = view.findViewById(R.id.track_duration)
        private val playingMark: ImageView = view.findViewById(R.id.track_playing)
        private val add: View = view.findViewById(R.id.track_add)
        private val more: View = view.findViewById(R.id.track_more)
        private val cached: View = view.findViewById(R.id.track_cached)
        private val cover = CoverImage(icon, COVER_DP, COVER_RADIUS_DP)

        fun bind(track: MusicTrack) {
            val current = track.id == currentTrackId
            val playing = current && isPlaying
            title.text = track.title
            artist.text = track.artist
            duration.text = track.durationText
            title.setTypeface(null, if (playing) Typeface.BOLD else Typeface.NORMAL)
            playingMark.visibility = if (playing) View.VISIBLE else View.GONE
            cached.visibility = if (com.example.vkweb.media.MusicCache.isCached(track.id)) View.VISIBLE else View.GONE
            cover.load(track.coverUrl) { Views.musicPlaceholder(icon) }
            itemView.isActivated = current
            itemView.setOnClickListener { onClick(track) }
            itemView.setOnLongClickListener(onLongPress?.let { press -> View.OnLongClickListener { press(track); true } })
            val canAdd = onAdd != null && !track.added && track.id !in addedNow
            add.visibility = if (canAdd) View.VISIBLE else View.GONE
            add.setOnClickListener(if (canAdd) View.OnClickListener { markAdded(track.id); onAdd?.invoke(track) } else null)
            more.visibility = if (onLongPress != null) View.VISIBLE else View.GONE
            more.setOnClickListener(if (onLongPress != null) View.OnClickListener { onLongPress.invoke(track) } else null)
        }
    }

    private companion object {
        const val COVER_DP = 48
        const val COVER_RADIUS_DP = 6
    }

    private object Diff : DiffUtil.ItemCallback<MusicTrack>() {
        override fun areItemsTheSame(a: MusicTrack, b: MusicTrack) = a.id == b.id
        override fun areContentsTheSame(a: MusicTrack, b: MusicTrack) = a == b
    }
}
