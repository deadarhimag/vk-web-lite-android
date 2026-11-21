package com.example.vkweb.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.R
import com.example.vkweb.media.PlaylistEntry
import com.example.vkweb.offline.ImageCache

/** Список плейлистов: обложка, название, число треков (и автор, если страница его показала). */
class PlaylistAdapter(private val onClick: (PlaylistEntry) -> Unit) :
    ListAdapter<PlaylistEntry, PlaylistAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.playlist_cover)
        private val title: TextView = view.findViewById(R.id.playlist_title)
        private val meta: TextView = view.findViewById(R.id.playlist_meta)
        private val image = CoverImage(cover, COVER_DP, COVER_RADIUS_DP)

        fun bind(item: PlaylistEntry) {
            val context = itemView.context
            title.text = item.title.ifEmpty { context.getString(R.string.playlist_default_title) }
            val count = if (item.count > 0) context.resources.getQuantityString(R.plurals.playlist_tracks_count, item.count, item.count) else ""
            val text = when {
                count.isNotEmpty() && item.subtitle.isNotEmpty() -> context.getString(R.string.playlist_meta_separator, count, item.subtitle)
                count.isNotEmpty() -> count
                else -> item.subtitle
            }
            meta.text = text
            meta.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            image.load(item.coverUrl) { Views.musicPlaceholder(cover) }
            itemView.setOnClickListener { onClick(item) }
        }
    }

    private companion object {
        const val COVER_DP = 56
        const val COVER_RADIUS_DP = 8
    }

    private object Diff : DiffUtil.ItemCallback<PlaylistEntry>() {
        override fun areItemsTheSame(a: PlaylistEntry, b: PlaylistEntry) = a.id == b.id
        override fun areContentsTheSame(a: PlaylistEntry, b: PlaylistEntry) = a == b
    }
}
