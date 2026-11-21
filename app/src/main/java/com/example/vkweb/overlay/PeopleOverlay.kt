package com.example.vkweb.overlay

import android.net.Uri
import android.view.View
import com.example.vkweb.R
import com.example.vkweb.config.Tab
import com.example.vkweb.people.PeopleKind
import com.example.vkweb.people.PeoplePanel
import com.example.vkweb.people.PeoplePayload
import com.example.vkweb.web.PageBridge

/** Свои списки «Друзья» и «Сообщества» поверх страниц /friends и /groups. */
class PeopleOverlay(host: OverlayHost, root: View) : PageOverlay(host, R.string.people_fallback) {

    override val panel = PeoplePanel(
        root = root,
        onOpen = { entry -> host.links.openLink(entry.pageUrl) },
        onMessage = { entry -> host.links.openChat(entry.peerId, entry.name, entry.photo) },
        onLoadMore = { host.bridge.people(PageBridge.ACTION_MORE) },
    )

    /** Открыт список друзей или сообществ (null — другая страница). */
    private var kind: PeopleKind? = null

    init {
        host.bridge.on(PeoplePayload.TYPE, PeoplePayload::fromJson, ::onPayload)
    }

    override val logName: String get() = kind?.wire ?: "друзья и сообщества"
    override val isOnPage: Boolean get() = kind != null
    override val isEnabled: Boolean get() = host.settings.nativePeople

    override fun showPanel(): Boolean {
        val k = kind ?: return false
        if (panel.isShown && panel.kind == k) return false
        panel.show(k)
        return true
    }

    override fun requestFresh() = host.bridge.people(PageBridge.ACTION_SYNC)

    override fun loadSaved() {
        val k = kind ?: return
        // Сохранённый список — сразу; свежий дополнит и обновит его, когда придёт.
        host.store.loadPeople(k) { saved ->
            logInfo(if (saved != null) "показано сохранённое" else "сохранённого нет", "записей" to saved?.items?.size)
            if (saved != null && kind == k) panel.showSaved(saved)
        }
    }

    override fun onSectionSelected(tab: Tab) {
        val k = PeopleKind.entries.firstOrNull { it.tab == tab } ?: return
        kind = k
        onPageChanged()
        update()
    }

    override fun onUrlChanged(uri: Uri, isVideoSite: Boolean) {
        val k = if (isVideoSite) null else PeopleKind.fromPath(uri.path)
        if (k != kind) {
            kind = k
            onPageChanged()
        }
        update()
    }

    private fun onPayload(payload: PeoplePayload) {
        if (payload.kind != kind) return
        logDebug(
            "данные от страницы", "список" to payload.listSeen, "записей" to payload.items.size,
            "заявок" to payload.requests.size.takeIf { payload.kind == PeopleKind.FRIENDS },
            "всего по данным VK" to payload.total.takeIf { it > 0 }, "есть ещё" to payload.hasMore,
            "свой" to payload.own,
        )
        panel.update(payload)
        host.prefetchImages(payload.imageUrls())
        // Одни заявки — ещё не свежий список: не снимаем ожидание и не перезаписываем сохранённое.
        if (!payload.listSeen) {
            logDebug("пришли одни заявки, список ещё нет: ожидание продолжается")
            return
        }
        onFreshData()
        // Сохраняем то, что на экране: настоящий порядок, затем то, до чего страница не дошла.
        if (!payload.isEmpty && payload.own) host.store.savePeople(payload.copy(items = panel.entries))
    }
}
