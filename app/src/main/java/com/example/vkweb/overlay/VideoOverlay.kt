package com.example.vkweb.overlay

import android.net.Uri
import android.view.View
import com.example.vkweb.R
import com.example.vkweb.config.Tab
import com.example.vkweb.ui.VideoPlayerActivity
import com.example.vkweb.video.VideoCatalog
import com.example.vkweb.video.VideoFiles
import com.example.vkweb.video.VideoItem
import com.example.vkweb.video.VideoPanel
import com.example.vkweb.web.PageBridge

/**
 * Свой каталог VK Видео поверх главной и подборок m.vkvideo.ru (3.78). Ролик открывается проигрывателем приложения
 * ([VideoPlayerActivity]: файл, иначе встроенный проигрыватель VK, иначе страница ролика) — без перехода страницы
 * между сайтами vk и vkvideo.
 */
class VideoOverlay(host: OverlayHost, root: View) : PageOverlay(host, R.string.video_fallback) {

    private val context = root.context

    override val panel = VideoPanel(
        root, ::open,
        onLoadMore = { host.bridge.vkvideo(PageBridge.ACTION_MORE) },
        onSection = { url ->
            logInfo("раздел каталога", "адрес" to url.substringAfter("://").substringAfter('/'))
            host.links.openLink(url)
        },
    )

    /** Путь открытой страницы каталога или null. */
    private var key: String? = null

    init {
        host.bridge.on(VideoCatalog.TYPE, VideoCatalog::fromJson, ::onCatalog)
        host.bridge.on(VideoFiles.TYPE, VideoFiles::fromJson, ::onFiles)
    }

    /** Ролик, для которого ждём все качества файла (3.86); не пришли быстро — играет то, что есть. */
    private var pendingOpen: VideoItem? = null
    private val filesTimeout = Runnable { pendingOpen?.let { pendingOpen = null; play(it, null) } }

    override val logName: String get() = "видео"
    override val isOnPage: Boolean get() = key != null
    override val isEnabled: Boolean get() = host.settings.nativeVideo
    override val title: String? get() = if (panel.isShown) context.getString(R.string.video_catalog_title) else null

    override fun showPanel(): Boolean {
        val k = key ?: return false
        if (panel.isShown && panel.key == k) return false
        panel.show(k)
        return true
    }

    override fun requestFresh() = host.bridge.vkvideo(PageBridge.ACTION_SYNC)

    override fun loadSaved() = Unit

    /**
     * Раздел «Видео» из меню (4.0, по отчёту с телефона): страница сначала открывает m.vk.ru/video и лишь потом уходит на
     * m.vkvideo.ru — на полсекунды показывалась настоящая страница VK между своими экранами. Теперь свой каталог
     * открывается сразу, а промежуточный адрес его не закрывает.
     */
    override fun onSectionSelected(tab: Tab) {
        if (tab != Tab.VIDEO) return
        if (key != MAIN_KEY) {
            key = MAIN_KEY
            onPageChanged()
        }
        update()
    }

    override fun onUrlChanged(uri: Uri, isVideoSite: Boolean) {
        var k = if (isVideoSite && com.example.vkweb.web.SiteDomains.isVideoCatalog(uri.path)) {
            uri.path.orEmpty().trimEnd('/').ifEmpty { "/" }
        } else {
            null
        }
        // По пути на m.vkvideo.ru: раздел «Видео» на m.vk.ru — ещё не уход из каталога.
        if (k == null && key == MAIN_KEY && !isVideoSite && uri.path.orEmpty().trimEnd('/') == VK_VIDEO_PATH) k = MAIN_KEY
        if (k != key) {
            key = k
            onPageChanged()
        }
        update()
    }

    private fun onCatalog(catalog: VideoCatalog) {
        if (catalog.key != key) return
        logDebug("данные от страницы", "роликов" to catalog.items.size, "есть ещё" to catalog.hasMore)
        panel.update(catalog)
        if (catalog.items.isNotEmpty()) onFreshData()
        host.onOverlayChanged()
    }

    private fun open(item: VideoItem) {
        if (item.live || (item.mp4.isEmpty() && item.hls.isEmpty())) { play(item, null); return }
        pendingOpen = item
        host.cancel(filesTimeout)
        host.postDelayed(filesTimeout, FILES_TIMEOUT_MS)
        host.bridge.vkvideoFiles(item.id)
    }

    private fun onFiles(files: VideoFiles) {
        val item = pendingOpen?.takeIf { it.id == files.id } ?: return
        pendingOpen = null
        host.cancel(filesTimeout)
        play(item, files)
    }

    private fun play(item: VideoItem, files: VideoFiles?) {
        logInfo(
            "ролик открыт проигрывателем приложения", "файл" to item.mp4.isNotEmpty(), "поток" to item.hls.isNotEmpty(),
            "качеств" to files?.files?.size,
        )
        VideoPlayerActivity.open(
            context, item.mp4.ifEmpty { item.hls }, item.player, item.pagePath, item.title, loop = false,
            files = files?.files.orEmpty(), hls = files?.hls?.ifEmpty { null } ?: item.hls,
        )
    }

    private companion object {
        const val FILES_TIMEOUT_MS = 1500L
        const val MAIN_KEY = "/"
        const val VK_VIDEO_PATH = "/video"
    }
}
