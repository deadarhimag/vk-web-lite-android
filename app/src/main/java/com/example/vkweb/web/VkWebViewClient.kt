package com.example.vkweb.web

import android.net.Uri
import android.os.SystemClock
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.vkweb.config.AppConfig
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogFormat
import com.example.vkweb.diag.LogTag
import java.io.ByteArrayInputStream

/** Навигация, блокировка рекламы и ошибки загрузки. Решения принимает [UrlPolicy]. */
class VkWebViewClient(
    private val policy: UrlPolicy,
    private val scripts: PageScripts,
    private val listener: Listener,
    /** Куда вести из скрытых разделов: раздел при запуске из настроек. */
    private val homeUrl: () -> String,
) : WebViewClient() {

    interface Listener {
        fun onOpenExternal(uri: Uri)
        fun onUrlChanged(uri: Uri)
        fun onPageLoadStarted()
        /** Главный документ догрузился (url — его адрес). */
        fun onPageFinished(url: String?)
        fun onMainFrameError()
        /** Запрос данных ответил 401/403: вход/подписи устарели — признак истёкшей сессии. */
        fun onSessionExpired()
        /** WebView упал: Activity должна пересоздать его. */
        fun onWebViewCrashed()
    }

    private var lastHomeRedirectAt = 0L

    /** Путь открытой страницы (для решений в потоке загрузки ресурсов: там адрес WebView читать нельзя). */
    @Volatile private var pagePath: String? = null
    /** Когда началась загрузка страницы; 0 — не началась (переход внутри страницы без загрузки). */
    private var pageStartedAt = 0L

    /** Сколько запросов страницы заблокировано (реклама, трекеры) и с каких хостов: сводка в журнал при загрузке. */
    private val blockedOnPage = HostCounter()

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        // Фреймы (виджеты, плееры) не трогаем: иначе ссылки из них открывались бы в браузере.
        if (!request.isForMainFrame) return false
        val uri = request.url
        if (toMainSite(view, uri)) return true
        return when (policy.decide(uri.scheme, uri.host, uri.path)) {
            Navigation.ALLOW -> false
            Navigation.REDIRECT_HOME -> redirectHome(view)
            Navigation.UPGRADE_HTTPS -> {
                AppLog.d(LogTag.PAGE, "http заменён на https", "хост" to uri.host)
                view.loadUrl(uri.buildUpon().scheme("https").build().toString())
                true
            }
            Navigation.OPEN_EXTERNAL -> {
                AppLog.i(LogTag.PAGE, "открыто во внешнем приложении", "хост" to uri.host)
                listener.onOpenExternal(uri)
                true
            }
            Navigation.IGNORE -> {
                AppLog.d(LogTag.PAGE, "переход проигнорирован", "адрес" to LogFormat.url(uri.toString()))
                true
            }
        }
    }

    /**
     * Сайт VK — одностраничное приложение: переходы через history.pushState
     * не проходят через shouldOverrideUrlLoading, но попадают сюда.
     */
    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        val uri = url?.let(Uri::parse) ?: return
        // Переход внутри сайта видео (без загрузки) на путь основного сайта — туда, где он есть (4.31).
        if (toMainSite(view, uri)) return
        pagePath = uri.path
        listener.onUrlChanged(uri)
        if (policy.isBlockedSection(uri.host, uri.path)) {
            AppLog.i(LogTag.PAGE, "скрытый раздел, переход на домашнюю", "адрес" to LogFormat.url(url))
            redirectHome(view)
        }
    }

    /**
     * Путь основного сайта VK на сайте VK Видео (m.vkvideo.ru/friends, /club1, /clip-1_2) — загрузить его на основном
     * сайте ([SiteDomains], 4.31): на сайте видео таких страниц нет, и свои экраны разделов там не запускаются.
     */
    private fun toMainSite(view: WebView, uri: Uri): Boolean {
        val target = SiteDomains.mainSiteUrl(uri.host, uri.path, uri.encodedQuery) ?: return false
        // Защита от круга: если VK когда-нибудь станет переводить этот путь обратно на сайт видео, второй перевод того же
        // адреса подряд не делается — страница остаётся как есть, а в журнале видно почему (код ревью 4.34).
        val now = SystemClock.elapsedRealtime()
        if (target == lastMainSiteTarget && now - lastMainSiteAt < MAIN_SITE_REPEAT_MS) {
            AppLog.w(LogTag.PAGE, "путь уже переводили на основной сайт — повтор не делается", "адрес" to LogFormat.url(target))
            return false
        }
        lastMainSiteTarget = target
        lastMainSiteAt = now
        AppLog.i(LogTag.PAGE, "путь основного сайта на VK Видео — на основной сайт", "адрес" to LogFormat.url(target))
        view.loadUrl(target)
        return true
    }

    private var lastMainSiteTarget: String? = null
    private var lastMainSiteAt = 0L

    /** Вызывается из фонового потока: счётчики потокобезопасны. */
    /**
     * Страница видна (не под своим экраном) и окно на экране — ей можно грузить файлы видео (4.22, [PageMedia]).
     * Меняется из главного потока, читается из потока запросов.
     */
    @Volatile var pageMediaAllowed = true

    /**
     * Картинки страницы нужны (5.00.12). false - страница альбомов под своим экраном фото: её превью никто не видит,
     * а свой экран грузит картинки сам. Меняется из главного потока, читается из потока запросов.
     */
    @Volatile var pageImagesAllowed = true

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val host = request.url.host
        HostStats.requests.add(host)
        if (!pageMediaAllowed && PageMedia.isVideoFile(host) && !PageMedia.audioMayLoad(pagePath, request.url.path)) {
            HostStats.blocked.add(host)
            BlockedLog.record("главное (видео скрытой страницы)", host, request.url.path, pagePath)
            return BlockedResponse.of(request)
        }
        if (!pageImagesAllowed && !request.isForMainFrame && PageMedia.isUserPhoto(host)) {
            HostStats.blocked.add(host)
            return BlockedResponse.of(request)
        }
        if (!policy.isBlockedResource(host, request.url.path, pagePath)) return null
        blockedOnPage.add(host)
        HostStats.blocked.add(host)
        BlockedLog.record("главное", host, request.url.path, pagePath)
        // Запрос данных (/method/…) получает «успех», а не пустоту или ошибку: скрипты VK не будут слать его снова.
        return BlockedResponse.of(request)
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        // Страница (не загрузка файла) с серверов пользовательских файлов VK или после POST-формы за пределы VK — во
        // весь экран без адресной строки она выглядела бы как сам VK (поддельный вход). Открываем её в браузере
        // (код ревью 3.59). Скачивание файлов с этих серверов страницу не открывает и сюда не попадает.
        val uri = url?.let(Uri::parse)
        if (uri != null && uri.scheme == "https" && !policy.isPageHost(uri.host)) {
            AppLog.w(LogTag.PAGE, "страница не с сайта VK открыта во внешнем браузере", "хост" to uri.host)
            view.stopLoading()
            listener.onOpenExternal(uri)
            if (view.canGoBack()) view.goBack()
            return
        }
        pagePath = uri?.path
        pageStartedAt = SystemClock.elapsedRealtime()
        blockedOnPage.clear()
        AppLog.d(LogTag.PAGE, "загрузка началась", "адрес" to LogFormat.url(url))
        listener.onPageLoadStarted()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        scripts.onPageFinished(view, url)
        logPageFinished(url)
        listener.onPageFinished(url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        HostStats.errors.add(request.url.host)
        val fields = arrayOf<Pair<String, Any?>>(
            "код" to error.errorCode,
            "описание" to error.description,
            "хост" to request.url.host,
        )
        if (request.isForMainFrame) {
            AppLog.w(LogTag.PAGE, "страница не загрузилась", "адрес" to LogFormat.url(request.url.toString()), *fields)
            listener.onMainFrameError()
        } else {
            AppLog.d(LogTag.PAGE, "ресурс не загрузился", *fields)
        }
    }

    /**
     * Ответы сервера с ошибкой. Главная страница и запросы данных (/method/…) — это признак проблем с сессией
     * (401/403 — вход устарел, 429 — слишком часто); остальные ресурсы страницы в журнал не идут.
     */
    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (response.statusCode >= 400) HostStats.errors.add(request.url.host)
        val isApi = request.url.path.orEmpty().contains("/method/")
        if (!request.isForMainFrame && !isApi) return
        AppLog.w(
            LogTag.PAGE, if (request.isForMainFrame) "страница ответила ошибкой" else "запрос данных ответил ошибкой",
            "статус" to response.statusCode,
            "адрес" to LogFormat.url(request.url.toString()),
        )
        // 401/403 на запрос данных — вход или подписи устарели: по этому измеряется срок жизни сессии.
        if (isApi && (response.statusCode == 401 || response.statusCode == 403)) listener.onSessionExpired()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        AppLog.e(LogTag.PAGE, "процесс WebView завершился", "сбой" to detail.didCrash())
        listener.onWebViewCrashed()
        return true
    }

    private fun logPageFinished(url: String?) {
        // Страница VK меняет адрес и без новой загрузки: тогда времени загрузки нет, и показывать его нельзя.
        val took = if (pageStartedAt > 0) SystemClock.elapsedRealtime() - pageStartedAt else null
        pageStartedAt = 0
        val blocked = blockedOnPage.total
        val top = blockedOnPage.top(TOP_BLOCKED_HOSTS).joinToString(",") { "${it.first}:${it.second}" }
        AppLog.i(
            LogTag.PAGE, "страница загружена",
            "адрес" to LogFormat.url(url), "мс" to took,
            "заблокировано" to blocked.takeIf { it > 0 }, "хосты" to top.ifEmpty { null },
        )
    }

    /**
     * Не чаще одного раза за [AppConfig.HOME_REDIRECT_COOLDOWN_MS]: если VK сам перенаправит
     * с домашней страницы (например, без входа), цикла не будет.
     * @return true, если переход перехвачен.
     */
    private fun redirectHome(view: WebView): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHomeRedirectAt < AppConfig.HOME_REDIRECT_COOLDOWN_MS) return false
        lastHomeRedirectAt = now
        view.loadUrl(homeUrl())
        return true
    }

    private companion object {
        /** В пределах этого времени тот же адрес второй раз на основной сайт не переводится (защита от круга, 4.34). */
        const val MAIN_SITE_REPEAT_MS = 5_000L
        const val TOP_BLOCKED_HOSTS = 3
    }
}
