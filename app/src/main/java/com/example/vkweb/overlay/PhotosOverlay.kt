package com.example.vkweb.overlay

import android.net.Uri
import android.view.View
import com.example.vkweb.R
import com.example.vkweb.files.DownloadsFolder
import com.example.vkweb.photos.AlbumSaveProgress
import com.example.vkweb.photos.AlbumSaver
import com.example.vkweb.photos.PhotoPlace
import com.example.vkweb.photos.PhotosAllPayload
import com.example.vkweb.photos.PhotosFormat
import com.example.vkweb.photos.PhotosPanel
import com.example.vkweb.photos.PhotosPayload
import com.example.vkweb.ui.PhotoGallery

/**
 * Свои альбомы и фото поверх страниц /albums, /album и /photos (5.00.12): вместо вёрстки VK со своим оформлением -
 * сетка в общей теме приложения; кнопка на альбоме в списке сохраняет его в «Загрузки» ([AlbumSaver]). Переходы между альбомами идут через страницу
 * (адрес меняется), поэтому "Назад" работает как на сайте; показанное запоминается, и возврат не ждёт сети.
 */
class PhotosOverlay(host: OverlayHost, root: View) : PageOverlay(host, R.string.photos_fallback) {

    private val context = root.context
    private val saver = AlbumSaver.get(context)

    override val panel = PhotosPanel(
        root = root,
        onAlbum = { album ->
            titles[PhotoPlace.Album(album.owner, album.id.toString()).key] = album.title
            host.links.openLink(album.pagePath)
        },
        onPhoto = ::openViewer,
        onLoadMore = { next -> place?.let { request(it, next) } },
        onSave = ::startSave,
        onStop = { saver.cancel() },
        onOpenFolder = ::openFolder,
    )

    /** Открытое место; null - не страница фото. */
    private var place: PhotoPlace? = null

    /** Показанное по местам: возврат к альбомам и назад в альбом - сразу, без ожидания. */
    private val cache = object : LinkedHashMap<String, PhotosPanel.Snapshot>(CACHE_PLACES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PhotosPanel.Snapshot>?) = size > CACHE_PLACES
    }

    /** Названия альбомов (из списка альбомов): заголовок экрана и имя папки при сохранении. */
    private val titles = HashMap<String, String>()

    init {
        host.bridge.on(PhotosPayload.TYPE, PhotosPayload::fromJson, ::onPayload)
        host.bridge.on(PhotosAllPayload.TYPE, PhotosAllPayload::fromJson) { saver.onList(it) }
        saver.listener = ::onSaveProgress
    }

    override val logName: String get() = "фото"
    override val isOnPage: Boolean get() = place != null
    override val isEnabled: Boolean get() = host.settings.nativePhotos

    override val title: String?
        get() = when (val p = place) {
            null -> null
            is PhotoPlace.Albums -> context.getString(R.string.photos_albums_title)
            is PhotoPlace.Album -> titles[p.key] ?: if (p.album.isEmpty()) context.getString(R.string.photos_all_title) else null
        }

    override fun showPanel(): Boolean {
        val p = place ?: return false
        if (panel.isShown && panel.place?.key == p.key) return false
        panel.show(p)
        saver.progress?.let(::onSaveProgress)
        return true
    }

    override fun requestFresh() {
        place?.let { request(it, 0) }
    }

    override fun loadSaved() {
        val p = place ?: return
        cache[p.key]?.let { snapshot ->
            logDebug("показано запомненное", "записей" to snapshot.albums.size + snapshot.photos.size)
            panel.showCached(snapshot)
        }
    }

    override fun onUrlChanged(uri: Uri, isVideoSite: Boolean) {
        val p = if (isVideoSite) null else PhotoPlace.fromPath(uri.path)
        if (p?.key != place?.key) {
            panel.snapshot?.let { cache[it.place.key] = it }
            place = p
            onPageChanged()
        }
        update()
    }

    private fun request(p: PhotoPlace, offset: Int) = when (p) {
        is PhotoPlace.Albums -> host.bridge.photos("albums", p.owner, "", offset.toString())
        is PhotoPlace.Album -> host.bridge.photos("photos", p.owner, p.album, offset.toString())
    }

    private fun onPayload(payload: PhotosPayload) {
        if (payload.place.key != place?.key) return
        logDebug(
            "данные от страницы", "ок" to payload.ok, "с" to payload.offset,
            "записей" to payload.albums.size + payload.photos.size, "всего" to payload.total, "есть ещё" to payload.more,
            "причина" to payload.reason.ifEmpty { null },
        )
        payload.albums.forEach { titles[PhotoPlace.Album(it.owner, it.id.toString()).key] = it.title }
        panel.update(payload)
        if (payload.ok && payload.offset == 0) {
            onFreshData()
            host.onOverlayChanged()
        }
        panel.snapshot?.let { cache[it.place.key] = it }
    }

    /** Просмотр: до [PhotoGallery.MAX_PHOTOS] фото вокруг нажатого, листаются жестом. */
    private fun openViewer(photos: List<com.example.vkweb.photos.PhotoItem>, index: Int, @Suppress("UNUSED_PARAMETER") view: View) {
        if (photos.isEmpty()) return
        val from = (index - PhotoGallery.MAX_PHOTOS / 2).coerceIn(0, (photos.size - PhotoGallery.MAX_PHOTOS).coerceAtLeast(0))
        val window = photos.subList(from, minOf(photos.size, from + PhotoGallery.MAX_PHOTOS))
        host.links.openPhotos(window.map { it.full }, index - from, window.map { it.pagePath })
    }

    /** Сохранить альбом из списка альбомов (5.00.12: кнопка на карточке, а не внутри альбома). */
    private fun startSave(album: com.example.vkweb.photos.PhotoAlbum) {
        val target = PhotoPlace.Album(album.owner, album.id.toString())
        val started = saver.start(target, album.title) { job -> host.bridge.photos("all", target.owner, target.album, job) }
        if (!started) host.showToast(R.string.photos_save_busy)
    }

    /** Ход - в шапке списка альбомов того же владельца; внутри альбома не показывается. */
    private fun onSaveProgress(progress: AlbumSaveProgress) {
        val albums = place as? PhotoPlace.Albums
        val mine = albums != null && progress.placeKey.startsWith("album${albums.owner}_")
        panel.setSave(if (mine) PhotosFormat.save(progress) else null)
    }

    private fun openFolder() {
        val relative = saver.progress?.relative?.takeIf { it.isNotEmpty() } ?: return
        if (!DownloadsFolder.open(context, relative)) host.showToast(R.string.photos_folder_unavailable)
    }

    private companion object {
        const val CACHE_PLACES = 12
    }
}
