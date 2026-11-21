package com.example.vkweb.web

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.vkweb.config.AppConfig
import com.example.vkweb.messages.MessagesActivity
import com.example.vkweb.ui.PhotoViewerActivity

/**
 * Куда ведут нажатия на своих экранах: страницы сайта — в это же окно, остальные адреса —
 * во внешние приложения, «Написать» — в свою переписку (или на страницу диалога), фото — в просмотр.
 */
class LinkRouter(
    private val context: Context,
    private val policy: UrlPolicy,
    private val externalLinks: ExternalLinks,
    /**
     * Открыть адрес сайта в окне страницы. Не `webView.loadUrl` напрямую: пока играет музыка, главный экран переходит
     * силами страницы, и плеер не обрывается (раньше нажатие на сообщество в списке перезагружало страницу и музыка
     * прерывалась).
     */
    private val navigate: (String) -> Unit,
    /** Включён ли свой интерфейс сообщений (читается при каждом нажатии: настройка могла измениться). */
    private val nativeMessages: () -> Boolean,
) {

    /** Путь на сайте (/wall1_2) или полный адрес. */
    fun openLink(target: String) {
        if (target.startsWith("/")) {
            navigate(AppConfig.SITE_ORIGIN + target)
            return
        }
        val uri = Uri.parse(target)
        val web = uri.scheme == "https" || uri.scheme == "http"
        if (web && policy.isTrustedOrigin(uri.scheme, uri.host)) navigate(target) else externalLinks.open(uri)
    }

    /** Переписка с [peerId] (у сообществ — отрицательный): свой экран или страница диалога VK. */
    fun openChat(peerId: String, title: String, avatar: String) {
        if (!nativeMessages()) {
            navigate(AppConfig.SITE_ORIGIN + CONVO_PATH + peerId)
            return
        }
        context.startActivity(
            Intent(context, MessagesActivity::class.java)
                .putExtra(MessagesActivity.EXTRA_PEER_ID, peerId)
                .putExtra(MessagesActivity.EXTRA_PEER_TITLE, title)
                .putExtra(MessagesActivity.EXTRA_PEER_AVATAR, avatar)
        )
    }

    fun openPhoto(url: String) = PhotoViewerActivity.open(context, url)

    /** Несколько фото одной записи или сообщения: откроется [index]-е, остальные можно листать жестом. */
    fun openPhotos(urls: List<String>, index: Int, pages: List<String> = emptyList()) =
        PhotoViewerActivity.open(context, urls, index, pages)

    private companion object {
        const val CONVO_PATH = "/mail/convo/"
    }
}
