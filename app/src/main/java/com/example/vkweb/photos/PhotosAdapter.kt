package com.example.vkweb.photos

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.R
import com.example.vkweb.offline.ImageCache
import com.example.vkweb.ui.Views
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

/** Строка экрана фото: шапка (во всю ширину), альбом или фото (ячейки сетки). */
sealed class PhotosRow {
    abstract val key: String

    /** Шапка: число и ход сохранения альбома ([save]; null - не начиналось или не здесь). */
    data class Header(val subtitle: String, val save: SaveState?) : PhotosRow() {
        override val key: String get() = "header"
    }

    data class Album(val album: PhotoAlbum) : PhotosRow() {
        override val key: String get() = "a:${album.owner}_${album.id}"
    }

    data class Photo(val photo: PhotoItem, val index: Int) : PhotosRow() {
        override val key: String get() = "p:${photo.key}"
    }
}

class PhotosAdapter(
    private val onAlbum: (PhotoAlbum) -> Unit,
    private val onPhoto: (index: Int, view: View) -> Unit,
    private val onSave: (PhotoAlbum) -> Unit,
    private val onStop: () -> Unit,
    private val onOpenFolder: () -> Unit,
) : ListAdapter<PhotosRow, RecyclerView.ViewHolder>(Diff) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is PhotosRow.Header -> TYPE_HEADER
        is PhotosRow.Album -> TYPE_ALBUM
        is PhotosRow.Photo -> TYPE_PHOTO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(inflater.inflate(R.layout.item_photos_header, parent, false))
            TYPE_ALBUM -> AlbumHolder(inflater.inflate(R.layout.item_photo_album, parent, false))
            else -> PhotoHolder(inflater.inflate(R.layout.item_photo_thumb, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is PhotosRow.Header -> (holder as HeaderHolder).bind(row)
            is PhotosRow.Album -> (holder as AlbumHolder).bind(row.album)
            is PhotosRow.Photo -> (holder as PhotoHolder).bind(row)
        }
    }

    /** Шапка занимает всю строку сетки. */
    fun isFullWidth(position: Int): Boolean = position in 0 until itemCount && getItem(position) is PhotosRow.Header

    private inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val subtitle: TextView = view.findViewById(R.id.photos_subtitle)
        private val box: View = view.findViewById(R.id.photos_save_box)
        private val status: TextView = view.findViewById(R.id.photos_save_status)
        private val bar: LinearProgressIndicator = view.findViewById(R.id.photos_save_progress)
        private val stop: MaterialButton = view.findViewById(R.id.photos_save_stop)
        private val folder: MaterialButton = view.findViewById(R.id.photos_save_folder)

        init {
            bar.max = PROGRESS_MAX
            stop.setOnClickListener { onStop() }
            folder.setOnClickListener { onOpenFolder() }
        }

        fun bind(row: PhotosRow.Header) {
            // Число фото или альбомов
            subtitle.text = row.subtitle
            subtitle.isVisible = row.subtitle.isNotEmpty()

            // Ход и итог сохранения
            val state = row.save
            box.isVisible = state != null
            if (state == null) return
            status.text = state.text
            bar.isVisible = state.running
            if (state.fraction == null) {
                bar.isIndeterminate = true
            } else {
                bar.isIndeterminate = false
                bar.setProgressCompat((state.fraction * PROGRESS_MAX).toInt(), true)
            }
            stop.isVisible = state.running
            folder.isVisible = !state.running
        }
    }

    private inner class AlbumHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.album_cover)
        private val title: TextView = view.findViewById(R.id.album_title)
        private val count: TextView = view.findViewById(R.id.album_count)
        private val save: MaterialButton = view.findViewById(R.id.album_save)
        private var album: PhotoAlbum? = null

        init {
            Views.clipRounded(cover, Views.dp(view, COVER_RADIUS_DP).toFloat())
            view.setOnClickListener { album?.let(onAlbum) }
            save.setOnClickListener { album?.let(onSave) }
        }

        fun bind(a: PhotoAlbum) {
            album = a
            title.text = a.title
            count.text = PhotosFormat.photos(a.size)
            // Пустой альбом сохранять нечего
            save.isVisible = a.size > 0
            ImageCache.get(cover.context).load(cover, a.cover, Views.dp(cover, ALBUM_TARGET_DP))
        }
    }

    private inner class PhotoHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val image: ImageView = view.findViewById(R.id.photo_thumb)
        private var index = -1

        init {
            image.setOnClickListener { if (index >= 0) onPhoto(index, image) }
        }

        fun bind(row: PhotosRow.Photo) {
            index = row.index
            // Превью, затем запасные размеры: часть адресов сервер VK иногда не отдаёт (424 / ORB)
            val urls = listOf(row.photo.thumb) + row.photo.fallback.reversed() + row.photo.full
            ImageCache.get(image.context).load(image, urls, Views.dp(image, THUMB_TARGET_DP))
        }
    }

    private object Diff : DiffUtil.ItemCallback<PhotosRow>() {
        override fun areItemsTheSame(a: PhotosRow, b: PhotosRow) = a.key == b.key
        override fun areContentsTheSame(a: PhotosRow, b: PhotosRow) = a == b
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ALBUM = 1
        const val TYPE_PHOTO = 2
        const val PROGRESS_MAX = 1000
        const val COVER_RADIUS_DP = 16
        const val ALBUM_TARGET_DP = 240
        const val THUMB_TARGET_DP = 160
    }
}
