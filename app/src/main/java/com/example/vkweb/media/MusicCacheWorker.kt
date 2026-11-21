package com.example.vkweb.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.vkweb.dev.DumpRecorder
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.settings.AppSettings
import com.example.vkweb.sync.AppVisibility
import com.example.vkweb.web.BlockedResponse
import com.example.vkweb.web.PageBridge
import com.example.vkweb.web.PageScripts
import com.example.vkweb.web.UrlPolicy
import com.example.vkweb.web.WebViewSetup

/**
 * Отдельная невидимая страница для кэша музыки (4.99.7): потоки треков, у которых адреса нет, ищутся не в странице
 * человека, а здесь. Окно открывает свою страницу музыки VK («Мои треки» или нужный плейлист) и по очереди «открывает»
 * треки в своём плеере — без звука; плеер грузит плейлист HLS, адрес уходит в кэш ([MusicDownloadStarter]), трек
 * ставится на паузу, дальше следующий. Музыка, которую слушает человек, и его страница при этом не трогаются.
 *
 * Очередь управляемая: порциями по [GROUP] треков одной страницы; «Остановить кэширование» её очищает
 * ([MusicDownloadStarter.cancelQueued]); страница молчит дольше [SILENCE_MS] — оставшиеся в порции «не удалось», окно
 * перезагружается. Без работы [IDLE_MS] окно уничтожается. Всё — в главном потоке.
 */
object MusicCacheWorker {

    /** Трек, его страница ([pageUrl]) и просьба, которой он принадлежит. */
    private class Job(val track: MusicTrack, val pageUrl: String, observer: CacheBatch? = null) {
        val cacheGeneration = MusicCache.currentGeneration()
        val cancellationGeneration = MusicDownloadStarter.cancellationGeneration()
        fun isCurrent() = MusicDownloadStarter.recoveryIsCurrent(cacheGeneration, cancellationGeneration)
        var recovery: MusicDownload? = null
        private val followers = ArrayList<CacheBatch>().apply { if (observer != null) add(observer) }
        val batch = CacheBatch(1) { result ->
            recovery?.let { MusicDownloadStarter.finishRecovery(it, failed = result.failed > 0) }
            followers.toList().forEach { complete(it, result) }
        }
        fun follow(observer: CacheBatch?) {
            if (observer == null) return
            if (batch.isFinished) complete(observer, batch) else followers += observer
        }
        private fun complete(observer: CacheBatch, result: CacheBatch) {
            when {
                result.saved > 0 -> observer.onSaved()
                result.already > 0 -> observer.onAlready()
                result.cancelled > 0 -> observer.onCancelled()
                else -> observer.onFailed()
            }
        }
    }

    private val epoch = MusicResolveEpoch()
    private val windowRecovery = com.example.vkweb.web.WindowRecoveryBudget()
    private var restoringWindow = false
    private val restoreWindow = Runnable { restoringWindow = false; pump() }

    private const val GROUP = 20
    private const val SILENCE_MS = 45_000L
    private const val PAGE_LOAD_MS = 40_000L
    /** Страница загрузилась — подождать, пока VK разложит список и плеер (как у фонового окна сообщений). */
    private const val PAGE_SETTLE_MS = 3_000L
    private const val IDLE_MS = 60_000L
    private const val WIDTH = 1080
    private const val HEIGHT = 2000

    /** Страница «Мои треки». */
    const val MY_PAGE = "https://m.vk.ru/audio"

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Job>()
    private val inFlight = LinkedHashMap<String, Job>()
    private var app: Context? = null
    private var view: WebView? = null
    private var bridge: PageBridge? = null
    private var pageScripts: PageScripts? = null

    @Volatile var diagnosticState: String = "окно кэша ещё не создавалось"
        private set

    private fun diagnose(stage: String) {
        diagnosticState = "этап=$stage, время=${System.currentTimeMillis()}, окно=${view != null}, готово=$pageReady, очередь=${queue.size}, поиск=${inFlight.size}"
        AppLog.d(LogTag.MEDIA, "кэш: диагностика", "состояние" to diagnosticState)
        bridge?.music("diagnostics", stage)
    }

    /** Применяется и к уже работающему окну при возврате из настроек. */
    fun refreshDiagnostics() {
        val page = view ?: return
        val context = app ?: return
        pageScripts?.setOptions(page, workerOptions(context))
    }

    private fun workerOptions(context: Context) = PageScripts.Options(
        hideAds = true, hideClips = true, collectDump = AppSettings(context).collectDump, cacheWorker = true,
    )
    private var loadedUrl: String? = null
    private var pageReady = false
    private val policy = UrlPolicy()

    private val silence = Runnable {
        diagnose("worker-silence")
        AppLog.w(LogTag.MEDIA, "кэш: страница кэша не ответила", "треков" to inFlight.size)
        val failed = inFlight.values.toList()
        inFlight.clear()
        epoch.invalidate()
        failed.forEach { it.batch.onFailed() }
        // Страница могла сбиться (перезагрузка, вход): следующая порция откроет её заново.
        loadedUrl = null
        pump()
    }
    private val pageTimeout = Runnable {
        diagnose("page-timeout")
        val url = loadedUrl
        AppLog.w(LogTag.MEDIA, "кэш: страница кэша не загрузилась")
        val stuck = queue.filter { it.pageUrl == url }
        queue.removeAll(stuck)
        stuck.forEach { it.batch.onFailed() }
        loadedUrl = null
        pump()
    }
    private val settle = Runnable {
        pageReady = true
        pump()
    }
    private val idle = Runnable { destroy() }

    init {
        MusicDownloadStarter.addCancelListener(::cancelAll)
    }

    /** Есть ли работа (для «Остановить кэширование» в меню). */
    val isBusy: Boolean get() = queue.isNotEmpty() || inFlight.isNotEmpty()

    /** Треков ждёт потока (для уведомления кэширования); читается и из других потоков. */
    @Volatile var pending: Int = 0
        private set

    private fun changed() {
        pending = queue.size + inFlight.size
        diagnosticState = "время=${System.currentTimeMillis()}, окно=${view != null}, готово=$pageReady, очередь=${queue.size}, поиск=${inFlight.size}"
        MusicDownloadStarter.notifyProgress()
    }

    /** Треки [tracks] со страницы [pageUrl] — найти потоки и сохранить в кэш. */
    fun enqueue(context: Context, tracks: List<MusicTrack>, pageUrl: String, batch: CacheBatch) {
        if (tracks.isEmpty()) return
        app = context.applicationContext
        pruneInvalid()
        val queued = HashMap<String, Job>()
        queue.forEach { queued[it.track.id] = it }
        inFlight.forEach { (id, job) -> queued[id] = job }
        var added = 0
        tracks.forEach { track ->
            val existing = queued[track.id]
            if (existing != null) existing.follow(batch) else {
                val job = Job(track, pageUrl, batch)
                queue.addLast(job); queued[track.id] = job
                added++
            }
        }
        if (added == 0) return
        AppLog.i(LogTag.MEDIA, "кэш: в очередь страницы кэша", "треков" to added, "в очереди" to queue.size)
        // Долгая работа — под уведомлением кэширования (держит процесс в фоне).
        MusicCacheService.ensureRunning(context)
        pump()
    }

    /** One automatic re-resolution, using the same observers and original cancellation/cache generations. */
    fun recover(context: Context, download: MusicDownload, observer: CacheBatch?) {
        val cache = download.cacheGeneration
        val cancellation = download.cancellationGeneration
        if (cache == null || cancellation == null || !MusicDownloadStarter.recoveryIsCurrent(cache, cancellation)) {
            MusicDownloadStarter.finishRecovery(download)
            observer?.onCancelled(); return
        }
        app = context.applicationContext
        pruneInvalid()
        restartReads()
        val existing = queue.firstOrNull { it.track.id == download.id }
        val job = if (existing != null) {
            queue.remove(existing); existing.follow(observer); existing
        } else Job(MusicTrack(download.id, download.title, download.artist, 0, download.coverUrl,
            album = download.album), MusicRecovery.page(download.pageUrl), observer)
        job.recovery = download
        queue.addFirst(job)
        // A paused VK player may keep its expired manifest in memory. Recreate its document, not the visible page.
        loadedUrl = null
        pageReady = false
        MusicCacheService.ensureRunning(context)
        pump()
    }

    private fun restartReads() {
        handler.removeCallbacks(silence)
        epoch.invalidate()
        bridge?.music("resolveStop")
        val old = inFlight.values.toList()
        inFlight.clear()
        old.asReversed().forEach { queue.addFirst(it) }
    }

    private fun pruneInvalid() {
        val stale = (queue + inFlight.values).filterNot { it.isCurrent() }.toSet()
        if (stale.isEmpty()) return
        queue.removeAll(stale)
        stale.forEach { job -> if (inFlight[job.track.id] === job) inFlight.remove(job.track.id) }
        stale.forEach { it.batch.onCancelled() }
        changed()
    }

    /** «Остановить кэширование»: очередь и порция в работе снимаются. */
    private fun cancelAll() {
        if (!isBusy) return
        handler.removeCallbacks(silence)
        handler.removeCallbacks(restoreWindow)
        restoringWindow = false
        epoch.invalidate()
        bridge?.music("resolveStop")
        val cancelled = (queue + inFlight.values).toList()
        queue.clear()
        inFlight.clear()
        cancelled.forEach { it.batch.onCancelled() }
        changed()
        AppLog.i(LogTag.MEDIA, "кэш: очередь страницы кэша остановлена")
        scheduleIdle()
    }

    private fun pump() {
        pruneInvalid()
        changed()
        if (restoringWindow) return
        if (inFlight.isNotEmpty()) return
        val next = queue.firstOrNull()
        if (next == null) {
            scheduleIdle()
            return
        }
        handler.removeCallbacks(idle)
        val page = ensureView() ?: return
        refreshDiagnostics()
        page.resumeTimers()
        if (loadedUrl != next.pageUrl) {
            load(page, next.pageUrl)
            return
        }
        if (!pageReady) return
        val group = ArrayList<Job>()
        while (group.size < GROUP && queue.firstOrNull()?.pageUrl == next.pageUrl) group += queue.removeFirst()
        group.forEach { inFlight[it.track.id] = it }
        changed()
        AppLog.d(LogTag.MEDIA, "кэш: страница кэша ищет потоки", "треков" to group.size, "осталось" to queue.size)
        diagnose("resolve-batch")
        val operation = epoch.start(group.map { it.track.id })
        bridge?.resolveMusic(group.map { it.track.id }, operation, group.filter { it.recovery != null }.map { it.track.id })
        handler.removeCallbacks(silence)
        handler.postDelayed(silence, SILENCE_MS)
    }

    /** Потоки от страницы кэша: найденные — в кэш, без потока — «не удалось». */
    private fun onStreams(streams: MusicStreams) {
        val context = app ?: return
        if (!streams.byPlay || streams.operation != epoch.token) return
        val starter = MusicDownloadStarter(context)
        var accepted = false
        streams.urls.forEach { (id, url) ->
            if (epoch.take(streams.operation, id)) inFlight.remove(id)?.let { job ->
                accepted = true
                val recovery = job.recovery
                if (!job.isCurrent() || recovery != null && !MusicDownloadStarter.recoveryIsCurrent(
                        recovery.cacheGeneration!!, recovery.cancellationGeneration!!)) job.batch.onCancelled()
                else {
                    val download = recovery?.copy(url = url, streamExpiresAt = StreamLifetime.limit(Long.MAX_VALUE, System.currentTimeMillis()))
                        ?: MusicDownload.of(job.track, url, job.pageUrl).copy(cacheGeneration = job.cacheGeneration,
                            cancellationGeneration = job.cancellationGeneration)
                    starter.cache(download, job.batch)
                }
            }
        }
        streams.missing.forEach { id ->
            if (epoch.take(streams.operation, id)) {
                accepted = true
                inFlight.remove(id)?.batch?.onFailed()
            }
        }
        if (!accepted) return
        changed()
        handler.removeCallbacks(silence)
        if (inFlight.isEmpty()) pump() else handler.postDelayed(silence, SILENCE_MS)
    }

    private fun load(page: WebView, url: String) {
        loadedUrl = url
        pageReady = false
        handler.removeCallbacks(settle)
        handler.removeCallbacks(pageTimeout)
        handler.postDelayed(pageTimeout, PAGE_LOAD_MS)
        AppLog.d(LogTag.MEDIA, "кэш: страница кэша открывает список")
        page.loadUrl(url)
    }

    private fun pageLoaded(url: String?) {
        val target = loadedUrl ?: return
        val loaded = url?.let(Uri::parse) ?: return
        if (!policy.isPageHost(loaded.host)) return
        diagnose("page-loaded")
        handler.removeCallbacks(pageTimeout)
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, PAGE_SETTLE_MS)
        if (loaded.path != Uri.parse(target).path) AppLog.d(LogTag.MEDIA, "кэш: страница кэша открылась по другому адресу")
    }

    private fun scheduleIdle() {
        handler.removeCallbacks(idle)
        if (view != null) handler.postDelayed(idle, IDLE_MS)
    }

    private fun ensureView(): WebView? {
        view?.let { return it }
        val context = app ?: return null
        return try {
            build(context).also { check(view === it) { "Cache WebView invalidated during setup" } }
        } catch (e: Exception) {
            AppLog.e(LogTag.MEDIA, "кэш: страницу кэша не создать", e)
            bridge?.dispose(); bridge = null; pageScripts = null
            val broken = view; view = null
            runCatching { broken?.destroy() }
            AppVisibility.cacheRunning = false
            loadedUrl = null; pageReady = false
            val delay = if (isBusy) windowRecovery.nextDelay(android.os.SystemClock.elapsedRealtime()) else null
            if (delay != null) {
                restoringWindow = true
                handler.removeCallbacks(restoreWindow)
                handler.postDelayed(restoreWindow, delay)
            } else {
                val failed = (queue + inFlight.values).toList()
                queue.clear(); inFlight.clear(); epoch.invalidate()
                failed.forEach { it.batch.onFailed() }
            }
            changed()
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun build(context: Context): WebView {
        val page = WebView(context)
        view = page // Retain it for cleanup even if setup throws before build() returns.
        val settings = AppSettings(context)
        WebViewSetup.configure(page, settings.hideDeviceInfo)
        val scripts = PageScripts(context, policy)
        scripts.install(page, workerOptions(context))
        pageScripts = scripts
        // Свой мост: состояние плеера и списки этой страницы приложению не нужны — только потоки.
        val channel = PageBridge(onMediaState = {}, quiet = true)
        channel.onRaw(DumpRecorder.TYPE) { record ->
            if (settings.collectDump) DumpRecorder.get(context).appendRaw(record, "кэш музыки")
        }
        channel.on(MusicStreams.TYPE, MusicStreams::fromJson, ::onStreams)
        bridge = channel
        channel.install(page)
        page.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (!request.isForMainFrame) return
                AppLog.w(LogTag.MEDIA, "кэш: ошибка загрузки страницы", "код" to error.errorCode)
                diagnose("page-network-error")
            }

            override fun onRenderProcessGone(dead: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                if (view !== dead) {
                    channel.dispose()
                    runCatching { dead.destroy() }
                    return true
                }
                AppLog.w(LogTag.MEDIA, "кэш: процесс WebView завершён", "авария" to detail.didCrash())
                pageScripts = null
                handler.removeCallbacks(silence)
                handler.removeCallbacks(pageTimeout)
                handler.removeCallbacks(settle)
                val interrupted = inFlight.values.toList()
                inFlight.clear()
                epoch.invalidate()
                interrupted.asReversed().forEach { queue.addFirst(it) }
                bridge?.dispose()
                bridge = null
                view = null
                loadedUrl = null
                pageReady = false
                AppVisibility.cacheRunning = false
                (dead.parent as? android.view.ViewGroup)?.removeView(dead)
                dead.destroy()
                pruneInvalid()
                val delay = if (isBusy) windowRecovery.nextDelay(android.os.SystemClock.elapsedRealtime()) else null
                if (delay != null) {
                    restoringWindow = true
                    handler.removeCallbacks(restoreWindow)
                    handler.postDelayed(restoreWindow, delay)
                    AppLog.i(LogTag.MEDIA, "кэш: окно восстановится автоматически", "задержка мс" to delay)
                } else if (isBusy) {
                    val failed = queue.toList()
                    queue.clear()
                    failed.forEach { it.batch.onFailed() }
                    AppLog.w(LogTag.MEDIA, "кэш: повторные сбои окна, автоматическое восстановление остановлено")
                }
                changed()
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val host = request.url.host
                // Картинки и счётчики окну кэша не нужны — меньше трафика.
                if (policy.isBlockedResource(host, request.url.path) || host.orEmpty().endsWith("vkuserphoto.ru")) {
                    return BlockedResponse.of(request)
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !policy.isPageHost(request.url.host)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                pageReady = false
                restartReads()
                diagnose("page-started")
            }

            override fun onPageFinished(view: WebView, url: String?) {
                scripts.onPageFinished(view, url)
                pageLoaded(url)
            }
        }
        page.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        page.layout(0, 0, WIDTH, HEIGHT)
        page.onResume()
        page.resumeTimers()
        AppVisibility.cacheRunning = true
        AppLog.i(LogTag.MEDIA, "кэш: страница кэша открыта")
        return page
    }

    private fun destroy() {
        if (isBusy) return
        val page = view ?: return
        view = null
        pageScripts = null
        bridge?.dispose()
        bridge = null
        loadedUrl = null
        pageReady = false
        handler.removeCallbacks(settle)
        handler.removeCallbacks(pageTimeout)
        AppVisibility.cacheRunning = false
        runCatching {
            page.stopLoading()
            page.destroy()
        }
        diagnose("closed")
        AppLog.i(LogTag.MEDIA, "кэш: страница кэша закрыта")
    }
}
