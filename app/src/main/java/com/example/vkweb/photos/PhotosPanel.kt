package com.example.vkweb.photos

import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.R
import com.example.vkweb.ui.EmptyState
import com.example.vkweb.ui.ListUpdates
import com.example.vkweb.ui.LoadMoreTrigger
import com.example.vkweb.ui.OverlayPanel
import com.example.vkweb.ui.PanelFrame

/**
 * Свой экран альбомов и фото (layout/panel_photos.xml, 5.00.12). Сетка: альбомы - по два в ряд (шире экран -
 * больше), фото - квадраты от трёх в ряд. Шапка - число и, в списке альбомов, ход сохранения (кнопка - на альбоме).
 * Данные приходят порциями от страницы ([PhotosPayload]); прокрутка до конца просит следующую.
 */
class PhotosPanel(
    root: View,
    private val onAlbum: (PhotoAlbum) -> Unit,
    private val onPhoto: (photos: List<PhotoItem>, index: Int, view: View) -> Unit,
    private val onLoadMore: (next: Int) -> Unit,
    onSave: (PhotoAlbum) -> Unit,
    onStop: () -> Unit,
    onOpenFolder: () -> Unit,
) : OverlayPanel {

    /** Что показано: список и сколько всего по данным VK. Им же экран запоминает места для возврата "Назад". */
    data class Snapshot(
        val place: PhotoPlace,
        val albums: List<PhotoAlbum>,
        val photos: List<PhotoItem>,
        val total: Int,
        val next: Int,
        val more: Boolean,
    )

    override val frame = PanelFrame(root)
    private val list: RecyclerView = root.findViewById(R.id.photos_list)
    private val adapter = PhotosAdapter(
        onAlbum = { onAlbum(it) },
        onPhoto = { index, view -> onPhoto(photos, index, view) },
        onSave = onSave, onStop = onStop, onOpenFolder = onOpenFolder,
    )
    private val layout = GridLayoutManager(root.context, ALBUM_COLUMNS_MIN)
    private val loadMore = LoadMoreTrigger(list, layout, PRELOAD_DISTANCE) { onLoadMore(next) }

    /** Открытое место; null - ещё ничего не показывали. */
    var place: PhotoPlace? = null
        private set
    private var albums: List<PhotoAlbum> = emptyList()
    private var photos: List<PhotoItem> = emptyList()
    private var total = 0
    private var next = 0
    private var received = false
    private var save: SaveState? = null

    override val isShown: Boolean get() = frame.isShown
    override val hasData: Boolean get() = received
    override val isShowingSaved: Boolean get() = false

    val snapshot: Snapshot? get() = place?.takeIf { received }?.let { Snapshot(it, albums, photos, total, next, loadMore.hasMore) }

    init {
        list.layoutManager = layout
        list.adapter = adapter
        ListUpdates.prepare(list)
        layout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = if (adapter.isFullWidth(position)) layout.spanCount else 1
        }
        // Число колонок - по ширине: на планшете и боком больше
        list.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) applyColumns()
        }
    }

    /** Показать место [target]. Другое место - список очищается, пока не придут его данные. */
    fun show(target: PhotoPlace) {
        if (target.key != place?.key) reset(target)
        frame.show()
        if (!received) frame.showLoading()
    }

    /** Показать запомненное ранее (возврат "Назад" к альбомам): сразу, пока свежее не пришло. */
    fun showCached(s: Snapshot) {
        if (s.place.key != place?.key || received) return
        albums = s.albums
        photos = s.photos
        total = s.total
        next = s.next
        received = true
        loadMore.onLoaded(s.more)
        render()
    }

    override fun hide() {
        if (frame.isShown) frame.hide()
    }

    override fun clear() {
        val current = place ?: return
        reset(current)
        if (frame.isShown) frame.showLoading()
    }

    override fun setBanner(text: Int?) {
        frame.setBanner(text)
        if (text != null && !received) frame.showNothingSaved()
    }

    /** Порция от страницы: первая заменяет список, следующие дополняют недостающим. Неудачная - ждём дальше. */
    fun update(payload: PhotosPayload) {
        if (payload.place.key != place?.key) return
        if (!payload.ok) {
            // Неудачное продолжение - можно попросить снова; неудачную первую порцию решает ожидание экрана
            loadMore.onLoaded(hasMore = received && loadMore.hasMore)
            return
        }
        val first = payload.offset == 0
        albums = if (first) payload.albums else albums + payload.albums.filter { a -> albums.none { it.id == a.id } }
        photos = if (first) payload.photos else photos + payload.photos.filter { p -> photos.none { it.key == p.key } }
        total = payload.total
        next = payload.next
        received = true
        loadMore.onLoaded(payload.more, shown = albums.size + photos.size)
        render()
    }

    /** Ход сохранения этого альбома (null - не начиналось или относится к другому). */
    fun setSave(state: SaveState?) {
        if (state == save) return
        save = state
        if (received) render()
    }

    private fun reset(target: PhotoPlace) {
        place = target
        albums = emptyList()
        photos = emptyList()
        total = 0
        next = 0
        received = false
        save = null
        loadMore.reset()
        applyColumns()
        adapter.submitList(null)
    }

    private fun render() {
        val current = place ?: return
        val rows = ArrayList<PhotosRow>(albums.size + photos.size + 1)
        // Шапка: число; у альбома - сохранение в папку
        val count = if (total > 0) total else albums.size + photos.size
        val subtitle = when (current) {
            is PhotoPlace.Albums -> if (count > 0) PhotosFormat.albums(count) else ""
            is PhotoPlace.Album -> if (count > 0) PhotosFormat.photos(count) else ""
        }
        rows += PhotosRow.Header(subtitle, save = if (current is PhotoPlace.Albums) save else null)
        albums.forEach { rows += PhotosRow.Album(it) }
        photos.forEachIndexed { i, p -> rows += PhotosRow.Photo(p, i) }
        ListUpdates.submitAnchored(list, adapter, rows, PhotosRow::key)
        loadMore.onListChanged()

        // Пусто по-настоящему - так и сказать
        if (albums.isEmpty() && photos.isEmpty()) {
            frame.showEmptyList(
                if (current is PhotoPlace.Albums) R.string.photos_empty_albums else R.string.photos_empty_album,
                R.drawable.ic_section_photos,
            )
        } else {
            frame.showContent()
        }
    }

    private fun applyColumns() {
        val width = list.width.takeIf { it > 0 } ?: list.resources.displayMetrics.widthPixels
        val dp = width / list.resources.displayMetrics.density
        val columns = when (place) {
            is PhotoPlace.Album -> (dp / PHOTO_CELL_DP).toInt().coerceAtLeast(PHOTO_COLUMNS_MIN)
            else -> (dp / ALBUM_CELL_DP).toInt().coerceAtLeast(ALBUM_COLUMNS_MIN)
        }
        if (layout.spanCount != columns) layout.spanCount = columns
    }

    private companion object {
        const val PRELOAD_DISTANCE = 24
        const val ALBUM_COLUMNS_MIN = 2
        const val PHOTO_COLUMNS_MIN = 3
        const val ALBUM_CELL_DP = 180
        const val PHOTO_CELL_DP = 120
    }
}
