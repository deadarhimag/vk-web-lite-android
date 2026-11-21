package com.example.vkweb.messages

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.vkweb.settings.AppSettings
import com.example.vkweb.web.BlockedResponse
import com.example.vkweb.web.HostStats
import com.example.vkweb.web.Navigation
import com.example.vkweb.web.UrlPolicy

/**
 * Что делать окну, которое читает мессенджер VK, когда страница загрузилась: внедрить скрипт чтения ещё раз
 * (на старте документа он мог не успеть) и попросить у него данные — диалоги или историю, по адресу страницы.
 * Один на экран сообщений и на фоновую работу: окна у них разные, а порядок чтения один.
 *
 * Как и главное окно, эти окна не загружают рекламу и счётчики ([policy]): раньше блокировка была только в главном,
 * а скрытые окна чтения (в том числе фоновая проверка раз в 15–60 минут) тянули всё, что просит страница VK, —
 * лишний трафик и телеметрия, от которой главное окно закрыто. Уйти со страниц VK окно тоже не может.
 *
 * @param isActive пока вернёт false, страница не читается (например, показан сам сайт вместо своего интерфейса).
 */
class ReaderPageClient(
    private val script: ReaderScript,
    private val channel: ReaderChannel,
    /** Обязательно (4.65): без правил окно чтения могло уйти на любой сайт. */
    private val policy: UrlPolicy,
    private val isActive: () -> Boolean = { true },
    /** Начало и конец загрузки страницы — всегда, даже когда окно не читается (4.19: возврат после входа). */
    private val onStarted: (String?) -> Unit = {},
    private val onFinished: (String?) -> Unit = {},
    private val onRendererGone: () -> Unit,
) : WebViewClient() {

    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
        channel.dispose()
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.destroy()
        onRendererGone()
        return true
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        onStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        onFinished(url)
        if (!isActive()) return
        script.inject(view, view.url ?: url) {
            // Пока шла проверка, окно могло перейти дальше или стать ненужным: просим данные той страницы, что открыта.
            if (!isActive()) return@inject
            val current = view.url ?: url
            when {
                ReaderChannel.isDialogsUrl(current) -> channel.requestDialogs(view)
                ReaderChannel.isHistoryUrl(current) -> channel.requestHistory(view)
            }
        }
    }

    /** Фоновый поток: реклама и счётчики не загружаются. */
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        HostStats.requests.add(request.url.host)
        val p = policy
        if (!p.isBlockedResource(request.url.host, request.url.path)) return null
        HostStats.blocked.add(request.url.host)
        com.example.vkweb.web.BlockedLog.record("чтение", request.url.host, request.url.path, null)
        return BlockedResponse.of(request)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val p = policy
        if (!request.isForMainFrame) return false
        val uri = request.url
        return when (p.decide(uri.scheme, uri.host, uri.path)) {
            // Страницы VK и входа; скрытые разделы главного окна окну чтения не мешают.
            Navigation.ALLOW, Navigation.REDIRECT_HOME -> false
            Navigation.UPGRADE_HTTPS -> {
                view.loadUrl(uri.buildUpon().scheme("https").build().toString())
                true
            }
            // Чужие сайты и схемы окно чтения не открывает: оно невидимо, и человек не знал бы, куда оно ушло.
            Navigation.OPEN_EXTERNAL, Navigation.IGNORE -> true
        }
    }

    companion object {
        /** Правила блокировки для окон чтения — по тем же настройкам, что у главного окна. */
        fun policyFor(settings: AppSettings): UrlPolicy = UrlPolicy().apply {
            blockAds = settings.hideAds
            blockTrackers = settings.blockTrackers
        }
    }
}
