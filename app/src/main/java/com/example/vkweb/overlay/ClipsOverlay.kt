package com.example.vkweb.overlay

import android.net.Uri
import android.view.View
import com.example.vkweb.R
import com.example.vkweb.clips.ClipItem
import com.example.vkweb.clips.ClipsActivity
import com.example.vkweb.clips.ClipsHub
import com.example.vkweb.clips.ClipsPanel
import com.example.vkweb.web.PageBridge

/**
 * Раздел «Клипы» своим экраном (3.84): общий список клипов сеткой и вкладки страницы фильтрами. Отчёт 3.83: раздел
 * сразу открывал проигрыватель, общего списка с фильтрами не было видно.
 *
 * Страница клипов VK — сама лента: открыв /clips, она тут же показывает первый ролик и меняет адрес на /clip-…
 * Поэтому экран держится, пока человек в разделе: от /clips и по всем /clip-… после него. Клип, открытый ссылкой
 * (из переписки, со стены), — сразу проигрыватель, как раньше. Пока экран виден, ролики страницы под ним молчат.
 */
class ClipsOverlay(host: OverlayHost, root: View) : PageOverlay(host, R.string.clips_list_fallback) {

    private val context = root.context

    override val panel = ClipsPanel(
        root,
        onOpen = ::open,
        onTab = { index -> logInfo("вкладка", "номер" to index + 1); host.bridge.clipsTab(index) },
        onLoadMore = { host.bridge.clips(PageBridge.ACTION_MORE) },
        onShownChanged = { shown -> host.bridge.clips(if (shown) "silence" else "release") },
    )

    /** Человек в разделе клипов (вошёл через /clips). */
    private var inSection = false

    private val feedListener: () -> Unit = { onFeed() }

    init {
        ClipsHub.addListener(feedListener)
    }

    override val logName: String get() = "клипы"
    override val isOnPage: Boolean get() = inSection
    override val isEnabled: Boolean get() = host.settings.nativeClips
    override val title: String? get() = if (panel.isShown) context.getString(R.string.clips_section_title) else null

    override fun showPanel(): Boolean {
        if (panel.isShown) return false
        panel.show()
        return true
    }

    override fun requestFresh() = host.bridge.clips(PageBridge.ACTION_SYNC)

    override fun loadSaved() = Unit

    override fun onUrlChanged(uri: Uri, isVideoSite: Boolean) {
        val path = uri.path.orEmpty()
        val now = when {
            isVideoSite -> false
            // 4.39: вкладки ленты (/clips_trends, /clips/subscriptions) — тоже раздел, а клип под ними — тоже клип.
            com.example.vkweb.clips.ClipPaths.isFeed(path) -> true
            com.example.vkweb.clips.ClipPaths.isClip(path) -> inSection
            else -> false
        }
        if (now != inSection) {
            inSection = now
            onPageChanged()
            // Список чистится при входе в раздел, а не при смене вкладки: вкладку меняет сам экран.
            if (now) panel.clear()
        }
        update()
    }

    /** Из проигрывателя открыта страница клипа (комментарии): её и показать, а не список. */
    fun leaveSection() {
        if (!inSection) return
        inSection = false
        onPageChanged()
        update()
    }

    private fun onFeed() {
        if (!inSection) return
        panel.update(ClipsHub.items, ClipsHub.tabs, ClipsHub.hasMore, ClipsHub.generation)
        if (ClipsHub.items.isNotEmpty()) onFreshData()
        logDebug("лента от страницы", "роликов" to ClipsHub.items.size, "вкладок" to ClipsHub.tabs.size)
        host.onOverlayChanged()
    }

    private fun open(clip: ClipItem) {
        logInfo("клип открыт своим проигрывателем", "номер в ленте" to ClipsHub.items.indexOfFirst { it.id == clip.id } + 1)
        ClipsActivity.open(context, clip.id)
    }

}
