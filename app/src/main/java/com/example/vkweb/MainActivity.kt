package com.example.vkweb

import com.example.vkweb.comments.CommentSent
import com.example.vkweb.comments.CommentsHub
import com.example.vkweb.comments.CommentsPage
import android.content.Intent
import com.example.vkweb.ui.Notice
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import com.example.vkweb.web.InPageNavigation
import com.example.vkweb.web.TrafficCollector
import com.example.vkweb.config.AppConfig
import com.example.vkweb.config.HiddenSection
import com.example.vkweb.config.Tab
import com.example.vkweb.crash.CrashLog
import com.example.vkweb.crash.CrashReportActivity
import com.example.vkweb.dev.DumpRecorder
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogFormat
import com.example.vkweb.diag.LogTag
import com.example.vkweb.diag.PageLog
import com.example.vkweb.media.ChatTrackRequest
import com.example.vkweb.media.MusicSearch
import com.example.vkweb.media.MediaState
import com.example.vkweb.media.MusicService
import com.example.vkweb.media.PlaybackHub
import com.example.vkweb.media.PlayerCommand
import com.example.vkweb.messages.MessagesActivity
import com.example.vkweb.offline.ImageCache
import com.example.vkweb.offline.NetworkMonitor
import com.example.vkweb.offline.OfflineStore
import com.example.vkweb.overlay.MusicOverlay
import com.example.vkweb.overlay.OverlayHost
import com.example.vkweb.overlay.OwnerOverlay
import com.example.vkweb.overlay.PageOverlay
import com.example.vkweb.overlay.PeopleOverlay
import com.example.vkweb.permissions.NotificationPermission
import com.example.vkweb.permissions.WebPermissions
import com.example.vkweb.settings.AppSettings
import com.example.vkweb.settings.SettingsOutcome
import com.example.vkweb.sync.AppVisibility
import com.example.vkweb.ui.AppTheme
import com.example.vkweb.ui.FullscreenController
import com.example.vkweb.ui.NavigationController
import com.example.vkweb.ui.SectionLabels
import com.example.vkweb.ui.PageTitle
import com.example.vkweb.web.BackPolicy
import com.example.vkweb.web.DownloadStarter
import com.example.vkweb.web.ExternalLinks
import com.example.vkweb.web.FileChooser
import com.example.vkweb.web.IdlePolicy
import com.example.vkweb.web.LinkRouter
import com.example.vkweb.web.PageBridge
import com.example.vkweb.web.PageScripts
import com.example.vkweb.web.SessionRefresher
import com.example.vkweb.web.StartPolicy
import com.example.vkweb.web.UrlPolicy
import com.example.vkweb.web.VkWebChromeClient
import com.example.vkweb.web.VkWebViewClient
import com.example.vkweb.web.WebLifecycle
import com.example.vkweb.web.WebViewSetup
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Главный экран: страница VK, боковое меню разделов и верхняя панель.
 * Вход — на настоящей странице VK: приложение пароль не видит и не хранит.
 *
 * Activity только связывает компоненты; логика живёт в профильных классах:
 * навигация по сайту — [UrlPolicy]/[VkWebViewClient], меню — [NavigationController],
 * связь со страницей — [PageBridge], свои экраны поверх страниц — [PageOverlay],
 * плеер — [MusicService], настройки — [AppSettings], тема — [AppTheme].
 */
class MainActivity : AppCompatActivity(),
    VkWebViewClient.Listener,
    VkWebChromeClient.Listener,
    NavigationController.Listener,
    PlaybackHub.Target {

    // Регистрация ActivityResult-лаунчеров должна произойти до onStart, поэтому — поля.
    private val urlPolicy = UrlPolicy()
    private val fileChooser = FileChooser(this)
    private val webPermissions = WebPermissions(this) { urlPolicy.isTrustedOrigin(it.scheme, it.host) }
    private val notificationPermission = NotificationPermission(this)
    private val externalLinks = ExternalLinks(this)
    private val bridge = PageBridge(::onMediaState)
    private val settingsLauncher = registerForActivityResult(SettingsOutcome.Contract(), ::onSettingsClosed)

    private val handler = Handler(Looper.getMainLooper())
    private val pauseWebInBackground = Runnable {
        // Таймеры общие для всех WebView процесса: пока виден экран сообщений, усыплять нельзя — его окно замрёт.
        if (IdlePolicy.canPauseWeb(AppVisibility.mainOpen, AppVisibility.messagesOpen, AppVisibility.musicPlaying, AppVisibility.syncRunning, AppVisibility.cacheRunning)) webLifecycle.pause()
    }

    private lateinit var settings: AppSettings
    private lateinit var webView: WebView
    /** WebView упал (процесс отрисовки): его больше нельзя трогать, экран пересоздаётся. */
    private var webViewCrashed = false
    /** После выхода из аккаунта: очистить историю переходов, когда загрузится новая страница. */
    private var clearHistoryOnLoad = false
    private lateinit var webLifecycle: WebLifecycle
    private lateinit var pageScripts: PageScripts
    private lateinit var toolbar: MaterialToolbar
    private lateinit var progress: LinearProgressIndicator
    private lateinit var loadError: com.example.vkweb.ui.LoadErrorScreen
    private lateinit var navigation: NavigationController
    private lateinit var fullscreen: FullscreenController
    private lateinit var links: LinkRouter
    private lateinit var store: OfflineStore
    private lateinit var network: NetworkMonitor
    private lateinit var session: SessionRefresher

    // Свои экраны поверх страниц (данные получают сами, через bridge). Новый экран — строка в [overlays].
    private lateinit var music: MusicOverlay
    private lateinit var clipsOverlay: com.example.vkweb.overlay.ClipsOverlay
    private lateinit var overlays: List<PageOverlay>

    /** Что свои экраны берут у главного экрана. */
    private val overlayHost = object : OverlayHost {
        override val settings: AppSettings get() = this@MainActivity.settings
        override val store: OfflineStore get() = this@MainActivity.store
        override val bridge: PageBridge get() = this@MainActivity.bridge
        override val links: LinkRouter get() = this@MainActivity.links
        override val isOnline: Boolean get() = network.isOnline
        override val returnsToChat: Boolean get() = this@MainActivity.returnsToChat
        override fun hideLoadError() = loadError.hide()
        override fun onOverlayChanged() = updateOverlayControls()
        override fun showToast(message: Int) = Notice.show(this@MainActivity, message, long = true)
        override fun share(text: String, photoUrls: List<String>) {
            val actions = com.example.vkweb.web.ShareActions(this@MainActivity)
            if (photoUrls.isEmpty()) actions.shareText(text) else actions.sharePhotos(photoUrls, text)
        }
        override fun postDelayed(task: Runnable, delayMs: Long) { handler.postDelayed(task, delayMs) }
        override fun cancel(task: Runnable) = handler.removeCallbacks(task)
        override fun refreshSession(): Boolean = session.requestRefresh()
        override fun prefetchImages(urls: List<String>) = ImageCache.get(this@MainActivity).prefetch(urls)
    }

    private var mediaState = MediaState.IDLE
    /** Экран закрыт сразу при создании, чтобы показать отчёт о сбое: остальное не создавалось. */
    private var showingCrashReport = false
    private var currentTab: Tab? = null
    /** Открытый раздел VK из меню (лента, маркет…), если страница — один из них. */
    private var currentSection: HiddenSection? = null
    /**
     * Окно открыто из переписки (видео, клип, запись): «Назад» возвращает в переписку, а не сворачивает приложение
     * и не листает историю страницы. Сбрасывается выбором раздела в меню.
     */
    private var returnsToChat = false

    /** Тема, с которой создан экран. */
    private var appliedTheme = AppTheme.Applied()

    /** Смена темы отложена: пересоздание экрана оборвало бы музыку. */
    private var themeChangePending = false

    /** Запуск открыл свой экран сообщений и закрылся: страницы и окон здесь нет, обращаться к ним нельзя. */
    private var redirectedToMessages = false

    /** Последняя записанная в журнал страница: VK сообщает о переходе дважды, в журнале достаточно одного раза. */
    private var lastLoggedPage = ""

    private val isInForeground: Boolean
        get() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    // ------------------------------------------------------------ жизненный цикл

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = AppSettings(this)
        // Тема задаётся до super.onCreate, иначе окно создастся со стилем из манифеста.
        appliedTheme = AppTheme.applyAndRemember(this, settings)
        super.onCreate(savedInstanceState)
        // Был сбой — сначала отдельный экран с отчётом (без WebView), чтобы его увидели,
        // даже если главный экран снова упадёт при запуске.
        if (CrashLog.hasReport(this)) {
            showingCrashReport = true
            startActivity(Intent(this, CrashReportActivity::class.java))
            finish()
            return
        }
        if (shouldStartInMessages(savedInstanceState)) {
            redirectedToMessages = true
            AppLog.i(LogTag.APP, "старт на сообщениях: открыт свой экран, страница VK не загружается")
            openMessagesAsRoot()
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) com.example.vkweb.ui.SplashHold.hold(this)
        com.example.vkweb.ui.AppShortcuts.refreshOnce(this)
        // Под системными панелями (3.94, обязательно с Android 15): панель приложения — под строкой состояния,
        // страница — над панелью навигации и клавиатурой.
        com.example.vkweb.ui.SystemBars.edgeToEdge(this)
        com.example.vkweb.ui.SystemBars.toolbar(findViewById(R.id.toolbar))
        // Нижний отступ страницы — после меню разделов (4.50): он зависит от нижней панели (setupNavigation).
        // Содержимое главного экрана непрозрачно целиком (панель, страница со своим фоном или свой экран): фон окна под
        // ним всё равно не виден, но заливался на каждом кадре — лишняя перерисовка всего экрана. Окно запуска
        // (заставка) берёт фон из темы отдельно и не меняется. На время полноэкранного видео фон возвращает
        // [FullscreenController].
        window.setBackgroundDrawable(null)

        store = OfflineStore.get(this)
        network = NetworkMonitor(this, ::onOnline, ::onOffline)
        bindViews()
        setupWebView()
        setupBackNavigation()
        PlaybackHub.attach(this)


        // После падения WebView прежнее состояние не восстанавливаем: та же страница уронила бы его снова (код ревью 3.59).
        val afterCrash = savedInstanceState?.getBoolean(STATE_WEB_CRASHED) == true
        val restored = if (afterCrash) false else savedInstanceState?.let { webView.restoreState(it) } != null
        if (!network.isOnline) onOffline()

        // Пришли из меню другого экрана с просьбой открыть раздел — открываем его вместо стартового. После пересоздания
        // экрана (поворот не в счёт — его обрабатываем сами; смерть процесса) просьба уже выполнена: не повторяем.
        val navigated = if (savedInstanceState != null) false else handleNavigationIntent(intent)
        if (!restored && !navigated) loadFull(homeUrl())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNavigationIntent(intent)
    }

    /**
     * Запуск «с нуля» на «Сообщениях» со своим экраном: страница VK под ним не нужна, а «Назад» с него вёл бы на неё,
     * то есть в веб-версию. Поэтому главное окно не создаётся, а экран сообщений становится единственным в задаче.
     */
    private fun shouldStartInMessages(saved: Bundle?): Boolean {
        val asksSection = intent.hasExtra(EXTRA_OPEN_TAB) || intent.hasExtra(EXTRA_OPEN_SECTION) ||
            intent.hasExtra(EXTRA_OPEN_SETTINGS) || intent.hasExtra(EXTRA_OPEN_URL) || intent.hasExtra(EXTRA_PLAY_TITLE) || intent.hasExtra(EXTRA_OPEN_PAGE) ||
            intent.hasExtra(EXTRA_MUSIC_ARTIST)
        return saved == null && !asksSection && startsInMessages
    }

    /** Страница, с которой начинаем и на которую уводит переход в скрытый раздел. */
    private fun homeUrl(): String =
        settings.startSection?.menuPath?.let { AppConfig.SITE_ORIGIN + it }
            ?: StartPolicy.homeTab(settings.startTab, settings.nativeMessages, settings.hiddenTabs).url

    /** Выбор в боковом меню экрана сообщений: раздел, открытый раздел VK или настройки. */
    private fun handleNavigationIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        // Главное окно доступно другим приложениям: всё, что ведёт куда-то (раздел, настройки, путь), принимается только
        // от своего (с секретом этого процесса). Раньше раздел и настройки мог открыть чужой запуск (код ревью 3.59).
        val fromApp = intent.getStringExtra(EXTRA_INTERNAL_TOKEN) == INTERNAL_TOKEN
        val tab = intent.getStringExtra(EXTRA_OPEN_TAB)?.takeIf { fromApp }?.let { name -> Tab.entries.firstOrNull { it.name == name } }
        val section = intent.getStringExtra(EXTRA_OPEN_SECTION)?.takeIf { fromApp }
            ?.let { name -> HiddenSection.entries.firstOrNull { it.name == name } }
        val openSettings = fromApp && intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)
        // Путь на сайте (например, видео из переписки): только путь, чужие адреса не принимаются.
        val openUrl = intent.getStringExtra(EXTRA_OPEN_URL)?.takeIf { fromApp && it.startsWith("/") && !it.startsWith("//") }
        // 4.45: «из переписки» — только по явной отметке. Раньше так считалась любая страница по пути — и страница клипа
        // или видео из своего проигрывателя: «Назад» с неё уводил в «Сообщения», в каком бы разделе ни был человек.
        val fromChat = fromApp && intent.getBooleanExtra(EXTRA_FROM_CHAT, false)
        if (intent.hasExtra(EXTRA_OPEN_URL) && !fromApp) AppLog.w(LogTag.APP, "путь из чужого запуска отклонён")
        // Музыка из переписки: номер трека и название для поиска; чужим запуском не принимается.
        val playRequest = if (fromApp) {
            ChatTrackRequest.from(intent.getStringExtra(EXTRA_PLAY_TRACK), intent.getStringExtra(EXTRA_PLAY_TITLE))
        } else null
        // 4.79: треки исполнителя из переписки (долгое нажатие на трек).
        val musicArtist = intent.getStringExtra(EXTRA_MUSIC_ARTIST)?.takeIf { fromApp }?.let(MusicSearch::clean)?.ifEmpty { null }
        intent.removeExtra(EXTRA_MUSIC_ARTIST)
        // Страница сайта из просмотра фото («Страница фото»): обычный переход, «Назад» — на прежнюю страницу.
        val openPage = intent.getStringExtra(EXTRA_OPEN_PAGE)?.takeIf { fromApp && it.startsWith("/") && !it.startsWith("//") }
        intent.removeExtra(EXTRA_OPEN_PAGE)
        intent.removeExtra(EXTRA_OPEN_URL)
        intent.removeExtra(EXTRA_FROM_CHAT)
        intent.removeExtra(EXTRA_PLAY_TRACK)
        intent.removeExtra(EXTRA_PLAY_TITLE)
        intent.removeExtra(EXTRA_INTERNAL_TOKEN)
        intent.removeExtra(EXTRA_OPEN_TAB)
        intent.removeExtra(EXTRA_OPEN_SECTION)
        intent.removeExtra(EXTRA_OPEN_SETTINGS)
        when {
            tab != null -> onSectionSelected(tab)
            section != null -> onExtraSectionSelected(section)
            openSettings -> onSettingsSelected()
            openUrl != null -> openFromChat(AppConfig.SITE_ORIGIN + openUrl, fromChat)
            playRequest != null -> openTrackFromChat(playRequest)
            musicArtist != null -> openArtistFromChat(musicArtist)
            openPage != null -> {
                AppLog.i(LogTag.PAGE, "страница фото из просмотра", "адрес" to LogFormat.url(openPage))
                open(AppConfig.SITE_ORIGIN + openPage)
            }
            else -> return false
        }
        return true
    }

    override fun onStart() {
        super.onStart()
        AppLog.i(LogTag.APP, "экран открыт", "сеть" to network.isOnline, "играет" to mediaState.playing)
        AppVisibility.mainOpen = true
        windowShown = true
        syncPageMedia()
        // Открыли без сети — когда она появится, это будет «вернулась» (страница не загрузилась, её нужно перезагрузить).
        wasOffline = !network.isOnline
        network.start()
        session.start()
        handler.removeCallbacks(pauseWebInBackground)
        // Таймеры могли остановить другой экран: собственный флаг об этом не знает.
        webLifecycle.forceResume()
        applySettings()
        if (::navigation.isInitialized) navigation.showProfile(com.example.vkweb.account.SelfProfile.load(this))
        updateUnreadBadge()
    }

    /**
     * Число непрочитанных на «Сообщениях» нижней панели (4.50) — по сохранённому списку диалогов: его обновляют экран
     * сообщений и фоновая проверка. Без сети и без страницы.
     */
    private fun updateUnreadBadge() {
        if (!::navigation.isInitialized) return
        val rules = com.example.vkweb.sync.DialogNotifyStore(this).all()
        store.loadDialogs { dialogs ->
            if (isFinishing || isDestroyed) return@loadDialogs
            navigation.setUnreadDialogs(com.example.vkweb.sync.DialogNotifications.badgeCount(dialogs.orEmpty(), rules))
        }
    }

    /** Тему проверяем здесь: в onStart экран ещё не считается видимым и пересоздание отложилось бы. */
    override fun onResume() {
        super.onResume()
        // Файлы кэша музыки могли удалить в «Файлах» или по USB, пока приложение было свёрнуто (4.99).
        com.example.vkweb.media.MusicDownloadStarter.refresh(this)
        checkThemeChange()
    }

    override fun onStop() {
        super.onStop()
        AppVisibility.mainOpen = false
        windowShown = false
        syncPageMedia()
        AppLog.i(LogTag.APP, "экран закрыт", "играет" to mediaState.playing)
        // Плановые загрузки страницы в фоне не нужны: батарея и трафик.
        session.stop()
        // Что приходило от серверов, пока экран был открыт, — в журнал (сводка по серверам и адресам). Упавший WebView
        // не трогаем.
        if (!webViewCrashed) TrafficCollector.collect(webView)
        AppLog.syncToDisk()
        // flush() пишет на диск и ждёт окончания записи: не в главном потоке.
        Thread({ CookieManager.getInstance().flush() }, "cookie-flush").start()
        when {
            mediaState.playing && !settings.backgroundPlayback -> bridge.send(PlayerCommand.PAUSE)
            !mediaState.playing -> schedulePauseInBackground()
        }
    }

    /** Смены размера и темы обрабатываем сами (см. манифест): пересоздание оборвало бы музыку. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        navigation.updateLayout(newConfig.screenWidthDp)
        checkThemeChange()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (webViewCrashed) {
            outState.putBoolean(STATE_WEB_CRASHED, true)
            return
        }
        // Состояние экрана передаётся через Binder (предел около 1 МБ на весь процесс): длинная история страниц могла
        // его превысить и уронить приложение (TransactionTooLargeException). Слишком большое не сохраняем — после
        // убийства процесса откроется стартовая страница.
        val web = Bundle()
        webView.saveState(web)
        val size = parcelSize(web)
        if (size <= MAX_SAVED_WEB_STATE_BYTES) outState.putAll(web)
        else AppLog.w(LogTag.APP, "состояние страницы слишком велико, не сохраняется", "КБ" to size / 1024)
    }

    private fun parcelSize(bundle: Bundle): Int {
        val parcel = android.os.Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }

    override fun onDestroy() {
        // Главного окна нет — и скрытой страницы нет: журнал страниц больше не считает ошибки видео ожидаемыми.
        com.example.vkweb.web.PageMedia.blocked = false
        if (showingCrashReport || redirectedToMessages) { super.onDestroy(); return }
        session.stop()
        network.stop()
        handler.removeCallbacksAndMessages(null)
        PlaybackHub.detach(this)
        fileChooser.cancel()
        clips.release()
        if (CommentsHub.request === commentsRequest) CommentsHub.request = null
        if (CommentsHub.send === commentsSend) CommentsHub.send = null
        bridge.dispose()
        // Музыка играет в этом WebView: без него сервису нечего показывать.
        MusicService.stop(this)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------ настройка экрана

    private fun bindViews() {
        webView = findViewById(R.id.web_view)
        progress = findViewById(R.id.page_progress)
        // Экран ошибки (с 4.6 — ui/LoadErrorScreen.kt).
        loadError = com.example.vkweb.ui.LoadErrorScreen(
            findViewById(R.id.error_view),
            onRetry = {
                webView.settings.cacheMode = if (network.isOnline) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
                webView.reload()
            },
            onCachedCopy = {
                webView.settings.cacheMode = WebSettings.LOAD_CACHE_ONLY
                webView.reload()
            },
        )

        music = MusicOverlay(overlayHost, findViewById(R.id.music_panel))
        clipsOverlay = com.example.vkweb.overlay.ClipsOverlay(overlayHost, findViewById(R.id.clips_panel))
        overlays = listOf(
            music,
            PeopleOverlay(overlayHost, findViewById(R.id.people_panel)),
            com.example.vkweb.overlay.PhotosOverlay(overlayHost, findViewById(R.id.photos_panel)),
            OwnerOverlay(overlayHost, findViewById(R.id.owner_panel)),
            com.example.vkweb.overlay.VideoOverlay(overlayHost, findViewById(R.id.video_panel)),
            clipsOverlay,
        )

        toolbar = findViewById(R.id.toolbar)
        toolbar.setTitle(settings.startSection?.let { SectionLabels.of(it) } ?: settings.startTab.titleRes)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_native_view -> activeOverlay()?.togglePage()
                R.id.action_refresh -> webView.reload()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }

        val drawer = findViewById<DrawerLayout>(R.id.drawer_layout)
        navigation = NavigationController(
            drawer = drawer,
            navView = findViewById<NavigationView>(R.id.nav_view),
            permanentContainer = findViewById(R.id.permanent_nav_container),
            permanentDivider = findViewById(R.id.permanent_nav_divider),
            toolbar = toolbar,
            listener = this,
            bottomBar = findViewById(R.id.main_bottom_nav),
            rail = findViewById(R.id.main_rail),
        )
        // Под нижней панелью разделов (4.50) странице не нужен отступ от панели навигации — он у самой панели.
        com.example.vkweb.ui.SystemBars.bottom(findViewById(R.id.main_column), bar = navigation.bottomInsets)
        navigation.showProfile(com.example.vkweb.account.SelfProfile.load(this))
        fullscreen = FullscreenController(
            this, drawer, rotate = { settings.videoAutoRotate },
            // Во весь экран — только видимая страница (4.38): скрытая под своим экраном его не получает.
            allowed = { !::webClient.isInitialized || webClient.pageMediaAllowed },
        )
    }

    private fun setupWebView() {
        WebViewSetup.configure(webView, settings.hideDeviceInfo)
        // Фоновое обновление сессии работает, только пока WebView не показан пользователю (открыт свой экран).
        session = SessionRefresher(
            webView,
            isPageVisibleToUser = { webViewVisibleToUser() },
            isOnline = { network.isOnline },
            isMusicPlaying = { mediaState.playing },
            // Та же страница, что открыта под своим экраном: после обновления экран остаётся при своей странице.
            refreshUrl = { trustedCurrentUrl() },
            // VK мог перенаправить на другой адрес: свои экраны и меню сверяются с ним.
            onFinished = { webView.url?.let { onUrlChanged(Uri.parse(it)) } },
            // Измеренный срок жизни сессии хранится между запусками.
            savedLifetime = settings.sessionLifetimeSamples,
            onLifetimeChanged = { settings.sessionLifetimeSamples = it },
        )
        session.intervalMs = settings.sessionRefreshIntervalMs
        webLifecycle = WebLifecycle(webView)

        pageScripts = PageScripts(this, urlPolicy)
        pageScripts.install(webView, scriptOptions())
        bridge.install(webView)
        // Собранное для разработки: страница шлёт записи, пока включена настройка (проверяется и здесь).
        val dump = DumpRecorder.get(this)
        bridge.onRaw(DumpRecorder.TYPE) { record -> if (settings.collectDump) dump.appendRaw(record, "главное") }
        clips.install()
        installComments()
        links = LinkRouter(this, urlPolicy, externalLinks, navigate = ::open) { settings.nativeMessages }

        webClient = VkWebViewClient(urlPolicy, pageScripts, this) { homeUrl() }
        webView.webViewClient = webClient
        webView.webChromeClient = VkWebChromeClient(fullscreen, fileChooser, webPermissions, this)
        webView.setDownloadListener(DownloadStarter(this, urlPolicy))
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this) {
            when {
                navigation.handleBack() -> Unit
                fullscreen.isActive -> fullscreen.exit()
                // Страница VK вместо своего экрана — «Назад» возвращает к экрану, а не уводит со страницы.
                overlays.any { it.handleBack() } -> Unit
                // Открыто из переписки: страница видео или клипа закрывается одним «Назад» — к переписке.
                // Музыка из переписки: к переписке, а страница с плеером остаётся жить — трек играет дальше.
                returnsToChat && musicFromChat && currentPath()?.startsWith(ChatTrackRequest.MUSIC_PATH) == true -> {
                    AppLog.i(LogTag.APP, "назад: возврат в переписку, музыка продолжается", "играет" to mediaState.playing)
                    startActivity(Intent(this@MainActivity, MessagesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
                returnsToChat && (BackPolicy.isPlayerPage(currentPath()) || !webView.canGoBack()) -> {
                    AppLog.i(LogTag.APP, "назад: возврат в переписку", "страница" to currentPath()?.replace(Regex("\\d{3,}"), "#"))
                    finish()
                }
                // 4.31: клип — в раздел «Клипы», видео — в «Видео», сообщество, человек, запись — на главную раздела;
                // с главной раздела — закрытие. История страницы не листается (BackPolicy).
                toSectionRoot() -> Unit
                // Не закрываем приложение во время музыки — просто сворачиваем.
                mediaState.playing -> moveTaskToBack(true)
                // 4.0: приложение начинается с «Сообщений» — с главной страницы раздела «Назад» ведёт к ним, а не
                // закрывает приложение (раздел открывали из меню сообщений, и сами сообщения при этом закрывались).
                startsInMessages -> {
                    AppLog.i(LogTag.APP, "назад: к сообщениям")
                    openMessagesAsRoot()
                    finish()
                }
                else -> finish()
            }
        }
    }

    // ------------------------------------------------------------ комментарии к видео и клипам (4.45)

    // Постоянные объекты: при закрытии окна снимаются, только если всё ещё его (как у ClipsHub).
    private val commentsRequest: (String, Int, Long) -> Unit = { id, offset, parent -> bridge.comments(id, offset, parent) }
    private val commentsSend: (String, String, Long) -> Unit = { id, text, replyTo -> bridge.commentSend(id, text, replyTo) }

    /** Проигрыватели видео и клипов просят комментарии у этой страницы: от её имени идут запросы к API. */
    private fun installComments() {
        CommentsHub.request = commentsRequest
        CommentsHub.send = commentsSend
        bridge.on(CommentsPage.TYPE, CommentsPage::fromJson) { CommentsHub.deliver(it) }
        bridge.on(CommentSent.TYPE, CommentSent::fromJson) { CommentsHub.deliver(it) }
    }

    // ------------------------------------------------------------ свой проигрыватель клипов (3.76; с 4.7 — в ClipsLauncher)

    /** Лента клипов от страницы и свой проигрыватель (с 4.7 — clips/ClipsLauncher.kt). */
    private val clips by lazy {
        com.example.vkweb.clips.ClipsLauncher(this, bridge, object : com.example.vkweb.clips.ClipsLauncher.Host {
            override val nativeClips: Boolean get() = settings.nativeClips
            override val sectionListAvailable: Boolean get() = clipsOverlay.isAvailable
            override val sectionListShown: Boolean get() = clipsOverlay.isShown
            override val entryWanted: Boolean get() = playerEntryWanted
            override fun currentPath(): String? = this@MainActivity.currentPath()
            override fun leaveSection() = clipsOverlay.leaveSection()
            override fun back() {
                // Свой проигрыватель закрыт не к списку раздела: как «Назад» со страницы клипа — в раздел «Клипы».
                if (returnsToChat) finish() else if (!toSectionRoot()) open(homeUrl())
            }
        })
    }

    /** Последняя главная страница раздела, где был человек: запасной путь «Назад» (4.0). */
    private var lastSectionRootUrl: String? = null

    /**
     * «Назад» не с главной раздела (4.31, [BackPolicy.decide]): открыть главную раздела. @return false — уже на главной,
     * «Назад» закрывает раздел (это решают следующие ветки обработчика).
     */
    private fun toSectionRoot(): Boolean {
        val current = trustedCurrentUrl()?.let(Uri::parse)
        val step = BackPolicy.decide(
            host = current?.host,
            path = currentPath(),
            sectionPaths = sectionPaths,
            sectionScreenShown = overlays.any { it.isShown && it !is com.example.vkweb.overlay.OwnerOverlay },
            clipsRoot = if (settings.hideClips) null else Tab.CLIPS.url,
            videoRoot = Tab.VIDEO.url,
            lastSectionRoot = lastSectionRootUrl,
            inVideoSection = currentTab == Tab.VIDEO,
        )
        if (step !is BackPolicy.Step.ToSection) return false
        AppLog.i(LogTag.APP, "назад: к главной странице раздела", "адрес" to LogFormat.url(step.url), "со страницы" to currentPath()?.replace(Regex("\\d{3,}"), "#"))
        open(step.url)
        return true
    }

    /** Пути главных страниц разделов (меню и скрытые разделы): с них «Назад» закрывает приложение. */
    private val sectionPaths: Set<String> by lazy {
        Tab.entries.map { it.path.trimEnd('/') }.toSet() + HiddenSection.entries.mapNotNull { it.menuPath?.trimEnd('/') }
    }

    private fun currentPath(): String? = webView.url?.let(Uri::parse)?.path

    /** Адрес открытой страницы, если это страница VK (иначе null). */
    private fun trustedCurrentUrl(): String? = webView.url?.takeIf { url ->
        Uri.parse(url).let { urlPolicy.isTrustedOrigin(it.scheme, it.host) }
    }

    /** Настройки скрипта страницы (реклама, клипы, сбор данных для разработки). */
    private fun scriptOptions() = PageScripts.Options(
        hideAds = settings.hideAds,
        // Пока открыт клип по ссылке, скрытие клипов не действует на странице: иначе стили и фильтр ответов прячут
        // плеер и данные самого клипа (на /clips… у страницы нет пометки «страница клипа»).
        hideClips = settings.hideClips && !urlPolicy.clipSession,
        collectDump = settings.collectDump,
    )

    /** Настройки читаются при каждом возврате на экран: так изменения из «Настроек» применяются сразу. */
    private fun applySettings() {
        AppLog.configure(settings.logLevel)
        webView.settings.textZoom = settings.textZoom
        navigation.applySettings(settings)
        ImageCache.get(this).maxBytes = ImageCache.megabytes(settings.imageCacheMb)
        urlPolicy.hiddenSections = settings.hiddenSections
        urlPolicy.hideClips = settings.hideClips
        overlays.forEach { it.update() }
        urlPolicy.blockAds = settings.hideAds
        urlPolicy.blockTrackers = settings.blockTrackers
        pageScripts.setOptions(webView, scriptOptions())
        com.example.vkweb.media.MusicCacheWorker.refreshDiagnostics()
        if (::session.isInitialized) session.intervalMs = settings.sessionRefreshIntervalMs
        if (!settings.backgroundPlayback) MusicService.stop(this)
    }

    private fun onSettingsClosed(outcome: SettingsOutcome) {
        AppLog.i(
            LogTag.APP, "настройки закрыты",
            "кэш страниц очищен" to outcome.clearCache.takeIf { it },
            "сохранённое удалено" to outcome.storageCleared.takeIf { it },
            "выход из аккаунта" to outcome.signedOut.takeIf { it },
        )
        // Главное окно было открыто только ради настроек и страницы не загружало (старые переходы, смерть процесса):
        // пустым оно оставаться не должно — сообщения своим экраном или стартовая страница.
        if (webView.url == null && !outcome.signedOut) {
            AppLog.w(LogTag.APP, "после настроек страница не загружена — открывается стартовый раздел")
            if (startsInMessages) {
                openMessagesAsRoot()
                finish()
                return
            }
            loadFull(homeUrl())
        }
        if (outcome.clearCache) webView.clearCache(true)
        // Иначе свои экраны записали бы удалённое обратно вместе со следующими свежими данными.
        if (outcome.storageCleared) overlays.forEach { it.onStorageCleared() }
        if (outcome.signedOut) {
            MusicService.stop(this)
            // История очищается, когда загрузится новая страница: очищенная до загрузки, она сохраняла текущую
            // (страницу прежнего аккаунта), и «Назад» со страницы входа вела на неё (код ревью 3.59).
            clearHistoryOnLoad = true
            webView.loadUrl(homeUrl())
        }
    }


    // ------------------------------------------------------------ разделы свайпом (4.13)

    private val sectionSwipe by lazy { com.example.vkweb.ui.SectionSwipeDetector(window.decorView, ::onSectionSwipe) }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        // Касание видимой страницы VK — её следующий переход желанный (4.38, PageIntent).
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN && ::overlays.isInitialized && overlays.none { it.isShown }) {
            markIntent(fromApp = false)
        }
        return sectionSwipe.dispatch(event, ::sectionSwipeAllowed) { super.dispatchTouchEvent(it) }
    }

    /** Свой экран на экране (не страница VK), меню закрыто, настройка включена. */
    private fun sectionSwipeAllowed(): Boolean =
        ::settings.isInitialized && settings.sectionSwipe && ::overlays.isInitialized && ::navigation.isInitialized &&
            overlays.any { it.isShown } && !navigation.isMenuOpen && !(::loadError.isInitialized && loadError.isShown)

    /**
     * 4.19: свайп — к соседней вкладке внутреннего меню экрана (клипы, VK Видео, «Музыка / Плейлисты»), а не к другому
     * разделу бокового меню, как в 4.13. Нет полосы вкладок — свайп ничего не делает.
     */
    private fun onSectionSwipe(direction: com.example.vkweb.ui.SectionSwipe.Direction) {
        val row = com.example.vkweb.ui.TabSwipe.findRow(findViewById(android.R.id.content)) ?: return
        if (com.example.vkweb.ui.TabSwipe.step(row, direction)) {
            AppLog.i(LogTag.APP, "вкладка свайпом", "раздел" to currentTab?.name, "куда" to direction.name)
        }
    }

    // ------------------------------------------------------------ NavigationController.Listener

    override fun onSectionSelected(tab: Tab) {
        leaveChatContext()
        // Свой экран (в т.ч. сохранённое) показываем сразу, не дожидаясь загрузки страницы.
        overlays.forEach { it.onSectionSelected(tab) }
        if (tab == Tab.MESSAGES && settings.nativeMessages) {
            // 4.50: смена раздела, а не новое окно — без анимации открытия.
            startActivity(Intent(this, MessagesActivity::class.java), com.example.vkweb.ui.Transitions.none(this))
        } else {
            open(tab.url)
        }
    }

    override fun onExtraSectionSelected(section: HiddenSection) {
        leaveChatContext()
        section.menuPath?.let { open(AppConfig.SITE_ORIGIN + it) }
    }

    /**
     * Переход из меню. Пока играет музыка, страница не перезагружается: переход делает сама страница (как нажатие
     * ссылки), и её плеер продолжает играть. Раньше любой переход был полной загрузкой — документ вместе с плеером
     * уничтожался, и музыка обрывалась. Тот же раздел, что открыт, во время музыки заново не грузится.
     */
    private fun open(url: String) {
        markIntent()
        // Адрес — путь на том же сайте: переход делает сама страница (как нажатие ссылки), а не полная загрузка. Раньше —
        // только во время музыки; дамп 3.63: каждая смена раздела была полной загрузкой (49 раз: вход, главная 71 КБ,
        // пакет запросов 54 КБ — около 130 КБ и секунды ожидания на каждый переход). Теперь — всегда, если открыт сайт VK
        // и страница загружена; не получилось за [IN_PAGE_TIMEOUT_MS] — полная загрузка, как раньше.
        // Страница с номером (/club1) откроется под коротким именем: свой экран — сразу, без настоящей страницы VK (4.13).
        if (::overlays.isInitialized) overlays.filterIsInstance<com.example.vkweb.overlay.OwnerOverlay>().forEach { it.expectOwner(url) }
        val current = trustedCurrentUrl()
        val pageReady = current != null && !loadError.isShown && !session.isRefreshing &&
            InPageNavigation.sameSite(Uri.parse(current).host, url)
        val inPage = if (pageReady && !com.example.vkweb.clips.ClipPaths.isClip(Uri.parse(url).path)) {
            InPageNavigation.relative(url) { scheme, host -> urlPolicy.isTrustedOrigin(scheme, host) }
        } else {
            null
        }
        if (inPage == null) {
            loadFull(url)
            return
        }
        if (InPageNavigation.pathOf(inPage) == currentPath()) {
            if (mediaState.playing) {
                AppLog.d(LogTag.PAGE, "раздел уже открыт: во время музыки не перезагружаем", "адрес" to LogFormat.url(url))
            } else {
                // Тот же раздел ещё раз — обновить его (как раньше).
                webView.loadUrl(url)
            }
            return
        }
        AppLog.i(LogTag.PAGE, "переход силами страницы", "адрес" to LogFormat.url(url), "играет" to mediaState.playing)
        navigatingInPage = true
        bridge.navigateInPage(inPage)
        handler.removeCallbacks(inPageFallback)
        inPageTarget = url
        handler.postDelayed(inPageFallback, IN_PAGE_TIMEOUT_MS)
    }

    /** Переход силами страницы не состоялся (адрес не сменился) — полная загрузка. */
    private var inPageTarget: String? = null
    private val inPageFallback = Runnable {
        val target = inPageTarget ?: return@Runnable
        inPageTarget = null
        if (!navigatingInPage) return@Runnable
        navigatingInPage = false
        AppLog.w(LogTag.PAGE, "переход силами страницы не состоялся — полная загрузка", "адрес" to LogFormat.url(target))
        webView.loadUrl(target)
    }

    /**
     * Видео, клип или запись из переписки ([fromChat]) или из своего проигрывателя видео и клипов. Всегда полная загрузка
     * страницы: переход «силами страницы» (он нужен, чтобы не прерывать музыку) для клипа не работает — VK в таком случае
     * показывает ленту вместо клипа, а играющий ролик превращал «Назад» в «свернуть приложение».
     *
     * 4.45: возврат в переписку включается только для [fromChat]. Страница из своего проигрывателя флаг не меняет: если
     * сам проигрыватель открыли из переписки, «Назад» по-прежнему ведёт туда, иначе — в раздел.
     */
    private fun openFromChat(url: String, fromChat: Boolean = true) {
        markIntent()
        if (fromChat) {
            returnsToChat = true
            musicFromChat = false
            chatTrack = null
            handler.removeCallbacks(chatTrackCheck)
        }
        navigatingInPage = false
        setClipSession(com.example.vkweb.clips.ClipPaths.isClip(Uri.parse(url).path))
        AppLog.i(
            LogTag.PAGE, if (fromChat) "открыто из переписки" else "открыто из своего проигрывателя",
            "адрес" to LogFormat.url(url), "играет" to mediaState.playing, "назад в переписку" to returnsToChat,
        )
        webView.loadUrl(url)
    }

    /** Трек из переписки, который ещё предстоит включить (после загрузки страницы музыки). */
    private var chatTrack: ChatTrackRequest? = null
    private val chatTrackCheck = Runnable { checkChatTrackStarted() }

    /** Страница музыки открыта ради трека из переписки: «Назад» возвращает в переписку, музыка играет дальше. */
    private var musicFromChat = false

    private fun openTrackFromChat(request: ChatTrackRequest) {
        AppLog.i(LogTag.MEDIA, "трек из переписки", "номер" to request.trackId.ifEmpty { "нет" })
        handler.removeCallbacks(chatTrackCheck)
        openFromChat(AppConfig.SITE_ORIGIN + ChatTrackRequest.MUSIC_PATH)
        chatTrack = request
        musicFromChat = true
    }

    /**
     * Треки исполнителя из переписки (4.79): свой экран музыки сразу в поиске. Страница уже на музыке — ищет сразу (музыка
     * не прерывается); иначе раздел музыки открывается переходом силами страницы, и поиск уйдёт, когда он откроется.
     * «Назад» — в переписку, музыка играет дальше (как у трека из переписки).
     */
    private fun openArtistFromChat(artist: String) {
        AppLog.i(LogTag.MEDIA, "треки исполнителя из переписки", "играет" to mediaState.playing)
        handler.removeCallbacks(chatTrackCheck)
        chatTrack = null
        val onMusic = trustedCurrentUrl() != null && webView.progress >= 100 && !loadError.isShown &&
            currentPath()?.startsWith(ChatTrackRequest.MUSIC_PATH) == true
        music.searchFromChat(artist, onMusic)
        if (!onMusic) open(AppConfig.SITE_ORIGIN + ChatTrackRequest.MUSIC_PATH)
        returnsToChat = true
        musicFromChat = true
        music.update()
    }

    /** Страница музыки загрузилась: один раз (она сообщает о загрузке дважды) даём ей прийти в себя и командуем. */
    private fun onChatTrackPageReady(request: ChatTrackRequest) {
        if (request.started) return
        request.started = true
        handler.postDelayed({
            if (chatTrack !== request) return@postDelayed
            bridge.playFromChat(request.trackId, request.title)
            if (request.trackId.isNotEmpty()) handler.postDelayed(chatTrackCheck, CHAT_TRACK_CHECK_MS) else showPageForChatTrack()
        }, CHAT_TRACK_PAGE_DELAY_MS)
    }

    /** Трек так и не заиграл: показываем саму страницу VK — там результаты поиска, человек выберет сам. */
    private fun checkChatTrackStarted() {
        if (chatTrack == null) return
        if (mediaState.playing) {
            chatTrack = null
            return
        }
        AppLog.w(LogTag.MEDIA, "трек из переписки не заиграл: показана страница VK с поиском")
        Notice.show(this, R.string.chat_track_not_started, long = true)
        showPageForChatTrack()
    }

    private fun showPageForChatTrack() {
        chatTrack = null
        activeOverlay()?.takeIf { it.isShown }?.togglePage()
    }

    /** Клип открыт по ссылке: его страницы не закрываются, и скрипт страницы не прячет клипы, пока он открыт. */
    private fun setClipSession(on: Boolean) {
        if (urlPolicy.clipSession == on) return
        urlPolicy.clipSession = on
        AppLog.d(LogTag.PAGE, if (on) "клип открыт по ссылке: скрытие клипов приостановлено" else "клип закрыт: скрытие клипов по настройке")
        if (::pageScripts.isInitialized) pageScripts.setOptions(webView, scriptOptions())
    }

    /** Ждём перехода силами страницы: если вместо него начнётся полная загрузка, это видно в журнале. */
    private var navigatingInPage = false

    override fun onSettingsSelected() = settingsLauncher.launch(Unit)

    /** Шапка меню — своя страница (3.94). */
    override fun onProfileSelected(profile: com.example.vkweb.account.SelfProfile) {
        leaveChatContext()
        open(AppConfig.SITE_ORIGIN + profile.path)
    }

    // ------------------------------------------------------------ VkWebViewClient.Listener

    override fun onOpenExternal(uri: Uri) = externalLinks.open(uri)

    /** Когда человек или приложение последний раз что-то открыли (4.38, [com.example.vkweb.web.PageIntent]). */
    private var lastIntentAt = 0L

    /** На страницу клипа пришли по воле человека или приложения — свой проигрыватель можно открыть. */
    private var playerEntryWanted = false

    /**
     * Переход по команде приложения ещё не дошёл до страницы: первая смена адреса — его, сколько бы ни грузилась
     * страница (на медленной сети больше [com.example.vkweb.web.PageIntent.WINDOW_MS]; клип из переписки без этого
     * открылся бы страницей, а не своим проигрывателем).
     */
    private var appNavigationPending = false

    /** [fromApp] — команда приложения (меню, свой экран, переписка, «Назад»), иначе — касание страницы. */
    private fun markIntent(fromApp: Boolean = true) {
        lastIntentAt = android.os.SystemClock.elapsedRealtime()
        if (fromApp) appNavigationPending = true
    }

    override fun onUrlChanged(uri: Uri) {
        val wanted = appNavigationPending || com.example.vkweb.web.PageIntent.isWanted(android.os.SystemClock.elapsedRealtime(), lastIntentAt)
        appNavigationPending = false
        // Флаг держится, пока страница на клипах: перелистывание внутри них его не снимает; ушли с клипов — снят.
        if (!BackPolicy.isPlayerPage(uri.path)) playerEntryWanted = false else if (wanted) playerEntryWanted = true
        // Страница сама ушла на клип или видео под своим экраном (4.38): экран, меню и заголовок остаются как были.
        // Свой экран клипов сам держит страницу под собой на клипах (вкладки, листание) — для него это не блуждание.
        val screenShown = ::overlays.isInitialized && overlays.any { it.isShown && it !is com.example.vkweb.overlay.ClipsOverlay }
        if (com.example.vkweb.web.PageIntent.isWander(uri.path, wanted, screenShown)) {
            AppLog.i(LogTag.PAGE, "страница сама ушла на клип или видео — свой экран и меню не меняются", "адрес" to LogFormat.url(uri.toString()))
            lastPageHost = uri.host
            return
        }
        lastPageHost = uri.host
        // Адрес сменился — переход силами страницы состоялся (раньше флаг не сбрасывался нигде, и предупреждение
        // «страница перезагружается во время музыки» ссылалось на давний переход из меню).
        navigatingInPage = false
        inPageTarget = null
        handler.removeCallbacks(inPageFallback)
        // Фоновое обновление само меняет адрес (на /friends): меню и свои экраны при этом не трогаем.
        if (session.isRefreshing) return
        currentTab = if (urlPolicy.isVideoSite(uri.host)) Tab.VIDEO else Tab.fromPath(uri.path)
        val shown = LogFormat.url(uri.toString())
        if (shown != lastLoggedPage) {
            lastLoggedPage = shown
            AppLog.i(LogTag.PAGE, "переход", "адрес" to shown, "раздел" to currentTab?.name)
        }
        // Открытый раздел VK (лента, маркет…) отмечается в меню и называется по-своему, а не заголовком страницы.
        currentSection = if (currentTab == null && !urlPolicy.isVideoSite(uri.host)) {
            settings.openedMenuSections.firstOrNull { it.menuPath != null && it.matches(uri.path.orEmpty()) }
        } else {
            null
        }
        // Клип открыт по ссылке — его страницы не закрываются; ушли в раздел меню — снова по настройке.
        // Просмотрщик клипа сам уходит на /clips… (листание ленты клипов) — это раздел CLIPS, но не уход из клипа:
        // раньше сессия здесь сбрасывалась, и тут же срабатывало «раздел скрыт» — клип из переписки перекидывало на главную.
        if (com.example.vkweb.clips.ClipPaths.isClip(uri.path)) setClipSession(true)
        else if ((currentTab != null && currentTab != Tab.CLIPS) || currentSection != null) setClipSession(false)
        navigation.highlight(currentTab, currentSection)
        currentTab?.let { toolbar.setTitle(it.titleRes) }
        currentSection?.let { toolbar.setTitle(SectionLabels.of(it)) }

        // Главная страница раздела («Сообщества», «Друзья»): к ней возвращает «Назад», если у страницы нет шага назад.
        if (urlPolicy.isTrustedOrigin(uri.scheme, uri.host) && BackPolicy.isSectionRoot(uri.path, sectionPaths) && !uri.path.isNullOrEmpty() && uri.path != "/") {
            lastSectionRootUrl = uri.toString()
        }
        val videoSite = urlPolicy.isVideoSite(uri.host)
        if (!BackPolicy.isPlayerPage(uri.path) && urlPolicy.isTrustedOrigin(uri.scheme, uri.host)) clips.onLeftPlayer()
        overlays.forEach { it.onUrlChanged(uri, videoSite) }
    }

    /** Кнопка «Страница VK / Свой экран» и заголовок — по тому, какой свой экран есть у страницы. */
    private fun updateOverlayControls() {
        if (!::overlays.isInitialized) return
        syncWebViewVisibility()
        val active = activeOverlay()
        toolbar.menu.findItem(R.id.action_native_view)?.apply {
            isVisible = active != null
            setTitle(if (active?.isShown == true) R.string.action_music_show_page else R.string.action_music_show_list)
        }
        if (currentTab == null) overlays.firstNotNullOfOrNull { it.title }?.let { toolbar.title = it }
    }

    /**
     * Страница VK под своим экраном не рисуется: WebView скрыт (INVISIBLE — размер окна сохраняется, вёрстка
     * считается как обычно). Раньше страница рисовалась под непрозрачным экраном: анимации, GIF и превью видео VK
     * тратили процессор и графику впустую. Чего в скрытой странице нет (кадры, пересечения элементов, прокрутка),
     * эмулирует assets/page_visibility.js; страница о скрытии не узнаёт, музыка играет дальше.
     */
    private fun syncWebViewVisibility() {
        if (!::fullscreen.isInitialized) return
        val covered = overlays.any { it.isShown } && !fullscreen.isActive
        syncPageMedia()
        val visibility = if (covered) View.INVISIBLE else View.VISIBLE
        if (webView.visibility == visibility) return
        webView.visibility = visibility
        AppLog.d(LogTag.PAGE, if (covered) "страница скрыта своим экраном (не рисуется)" else "страница показана")
    }

    /** Свой экран открытой страницы (у страницы он не больше одного). */
    private fun activeOverlay(): PageOverlay? = overlays.firstOrNull { it.isAvailable }

    private lateinit var webClient: VkWebViewClient

    /** Окно на экране (между onStart и onStop): иначе поверх — свой проигрыватель или другое приложение. */
    private var windowShown = false

    /**
     * Файлы видео — только видимой странице (4.22, [com.example.vkweb.web.PageMedia]): скрытая своим экраном или под
     * своим проигрывателем клипов и видео она качала ролики впустую — до 10 МБ за раз.
     */
    private fun syncPageMedia() {
        if (!::webClient.isInitialized || !::overlays.isInitialized || !::fullscreen.isInitialized) return
        val allowed = windowShown && (overlays.none { it.isShown } || fullscreen.isActive)
        // Общий признак для журнала — до раннего выхода (код ревью 4.34): новое окно начинает с «разрешено», а признак
        // от прежнего окна мог остаться «закрыто» — и настоящие ошибки видео писались бы как ожидаемые.
        com.example.vkweb.web.PageMedia.blocked = !allowed
        // 5.00.12: под своим экраном фото страница альбома качала все превью впустую (и половина падала с ORB)
        webClient.pageImagesAllowed = !(windowShown && !fullscreen.isActive && overlays.any { it.isShown && it is com.example.vkweb.overlay.PhotosOverlay })
        if (webClient.pageMediaAllowed == allowed) return
        webClient.pageMediaAllowed = allowed
        AppLog.d(LogTag.PAGE, if (allowed) "видео страницы разрешено" else "видео скрытой страницы не загружается")
    }

    // ------------------------------------------------------------ круг перезагрузок (4.0)

    private val reloadLoop = com.example.vkweb.web.ReloadLoop()

    /** Сервер последней открытой страницы: при полной загрузке новой — откуда ушли (4.13, видео или музыка). */
    private var lastPageHost: String? = null

    /** Когда страницу после круга перезагрузок загрузили заново: второй круг вскоре — уже без повтора. */
    private var loopRetriedAt = 0L

    /**
     * Страница перезагружает сама себя по кругу (4 раза за 8 с): остановить и один раз загрузить её заново; второй круг
     * за минуту — экран ошибки с «Повторить», а не трафик. 4.21: без обхода через ленту (4.0–4.20) — он появился из-за
     * перехода из VK Видео: это другой домен, и переход по части ссылки оттуда не работает. Полная загрузка нужного
     * раздела работает — ею переход из VK Видео и делается ([loadFull]).
     */
    private fun breakReloadLoop(url: String) {
        webView.stopLoading()
        val now = android.os.SystemClock.elapsedRealtime()
        if (loopRetriedAt > 0 && now - loopRetriedAt < LOOP_RETRY_COOLDOWN_MS) {
            AppLog.w(LogTag.PAGE, "страница снова грузится по кругу — загрузка остановлена", "адрес" to LogFormat.url(url))
            showLoadError()
            return
        }
        loopRetriedAt = now
        AppLog.w(LogTag.PAGE, "страница грузится по кругу — загружается заново", "адрес" to LogFormat.url(url))
        webView.loadUrl(url)
    }

    /**
     * Полная загрузка раздела — сразу нужной страницей: из VK Видео (другой домен) и когда страница VK ещё не открыта
     * (старт на сообщениях). Через ленту больше не идёт ничего (4.21).
     */
    private fun loadFull(url: String) {
        markIntent()
        AppLog.d(LogTag.PAGE, "полная загрузка раздела", "адрес" to LogFormat.url(url))
        webView.loadUrl(url)
    }

    override fun onPageLoadStarted() {
        val startedUrl = webView.url
        if (startedUrl != null && reloadLoop.onStart(Uri.parse(startedUrl).path, android.os.SystemClock.elapsedRealtime())) {
            breakReloadLoop(startedUrl)
            return
        }
        if (mediaState.playing) {
            // Полная загрузка уничтожит плеер страницы. Если это был переход из меню, страница не смогла сделать его сама.
            // 4.13: играло не обязательно музыка — по отчёту это было видео на странице VK Видео, а «Назад» на другой
            // сайт всегда полная загрузка. Предупреждение — только для музыки на самом сайте VK; уход с видео — запись.
            val videoSite = urlPolicy.isVideoSite(Uri.parse(startedUrl.orEmpty()).host) || urlPolicy.isVideoSite(lastPageHost)
            AppLog.log(
                if (videoSite) com.example.vkweb.diag.LogLevel.INFO else com.example.vkweb.diag.LogLevel.WARN, LogTag.PAGE,
                if (videoSite) "уход со страницы VK Видео — её видео остановится" else "страница перезагружается во время музыки — воспроизведение прервётся",
                // true — переход силами страницы был запрошен, но вместо него началась полная загрузка.
                arrayOf("переход силами страницы запрошен" to navigatingInPage),
            )
        }
        navigatingInPage = false
        // Полная загрузка уничтожила плеер страницы: «играет» больше не правда (отчёт 3.73 — после клипа «играет=true»
        // держалось на всех следующих страницах, и переходы считались переходами во время музыки).
        if (mediaState.playing) onMediaState(mediaState.copy(playing = false))
        // Во время фонового обновления сессии экран ошибки не трогаем: пользователь страницы не видит.
        if (!session.isRefreshing) loadError.hide()
    }

    override fun onPageFinished(url: String?) {
        // 4.54: заставка запуска держится до первой загруженной страницы (не дольше секунды — SplashHold).
        com.example.vkweb.ui.SplashHold.ready = true
        if (clearHistoryOnLoad) {
            clearHistoryOnLoad = false
            webView.clearHistory()
        }
        session.onRefreshLoaded(url)
        chatTrack?.let { request -> if (request.isMusicPage(Uri.parse(url.orEmpty()).path)) onChatTrackPageReady(request) }
    }

    /** Видит ли пользователь сейчас саму страницу VK (а не свой экран поверх неё). */
    private fun webViewVisibleToUser(): Boolean =
        !loadError.isShown && overlays.none { it.isShown }

    override fun onMainFrameError() {
        // Сбой фонового обновления: пользователь страницы не видит, просто попробуем позже.
        if (session.isRefreshing) return
        // Без сети свой экран показывает сохранённое, а не экран ошибки.
        if (overlays.any { it.onMainFrameError() }) {
            AppLog.i(LogTag.PAGE, "страница не загрузилась, показано сохранённое")
            return
        }
        AppLog.w(LogTag.PAGE, "показан экран ошибки загрузки")
        showLoadError()
    }

    /**
     * Экран ошибки (3.94): без сети — так и сказано, страница откроется сама (см. [onOnline]), и можно открыть
     * сохранённую копию; при сети — «не загрузилась» и «Повторить».
     */
    private fun showLoadError() = loadError.show(offline = !network.isOnline)

    /** Сеть пропала: страницы, которые уже открывались, показываются из кэша WebView. */
    private fun onOffline() {
        wasOffline = true
        webView.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        if (::overlays.isInitialized) overlays.forEach { it.onOffline() }
    }

    /** Сеть пропадала с последнего возврата на экран: только тогда «сеть есть» означает «вернулась». */
    private var wasOffline = false

    /**
     * Сеть есть. Монитор сообщает это и при каждом возврате на экран (чтобы снять пометку «нет сети»), поэтому
     * страница перезагружается, только если сеть правда пропадала или показан экран ошибки. Раньше при каждом
     * открытии приложения, пока свой экран ждал свежих данных, только что начавшая грузиться страница грузилась
     * второй раз (в журнале — две загрузки подряд при каждом запуске). Пока играет музыка, страница не
     * перезагружается вовсе: это оборвало бы воспроизведение (свой экран дождётся данных или покажет страницу сам).
     */
    private fun onOnline() {
        if (!network.isOnline) return
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        // Каждый экран снимает пометку «нет сети» (map, а не any: иначе остальные остались бы с пометкой).
        val overlayShown = overlays.map { it.onOnline() }.any { it }
        val reconnected = wasOffline
        wasOffline = false
        if (!loadError.isShown && !(overlayShown && reconnected)) return
        if (mediaState.playing) {
            AppLog.i(LogTag.NET, "сеть вернулась: страница не перезагружается, пока играет музыка")
            return
        }
        loadError.reset()
        webView.reload()
    }

    override fun onSessionExpired() {
        session.onSessionExpiredSignal()
    }

    override fun onWebViewCrashed() {
        // Упавший WebView использовать нельзя: пересоздаём экран целиком.
        AppLog.e(LogTag.APP, "WebView упал, экран пересоздаётся")
        webViewCrashed = true
        MusicService.stop(this)
        recreate()
    }

    // ------------------------------------------------------------ VkWebChromeClient.Listener

    override fun onPageProgress(progress: Int) {
        if (progress >= 100) {
            this.progress.hide()
        } else {
            this.progress.show()
            this.progress.setProgressCompat(progress, true)
        }
    }

    /** Вне разделов (профиль, сообщество, фото) в заголовке — название страницы. */
    override fun onPageTitle(title: String?) {
        if (currentTab != null || currentSection != null) return
        // На своём экране заголовок — из его данных (имя человека).
        if (overlays.any { it.title != null }) return
        toolbar.title = PageTitle.clean(title) ?: getString(R.string.app_name)
    }

    // ------------------------------------------------------------ общие мелочи (4.77: были написаны в нескольких местах)

    /** Человек выбрал другой раздел или страницу: «Назад» больше не ведёт в переписку, клип по ссылке закончен. */
    private fun leaveChatContext() {
        returnsToChat = false
        musicFromChat = false
        setClipSession(false)
    }

    /** Страница нужна сейчас (музыка, команда плеера): не усыплять её в фоне и разбудить, если уже усыплена. */
    private fun wakeWeb() {
        handler.removeCallbacks(pauseWebInBackground)
        webLifecycle.resume()
    }

    /** Приложение начинается с «Сообщений» своим экраном (не с открытого раздела VK). */
    private val startsInMessages: Boolean
        get() = settings.startSection == null && StartPolicy.opensNativeMessages(settings.startTab, settings.nativeMessages)

    /** Экран сообщений — единственным в задаче (поверх него главного окна нет). */
    private fun openMessagesAsRoot() {
        startActivity(Intent(this, MessagesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    // ------------------------------------------------------------ плеер

    private fun onMediaState(state: MediaState) {
        if (state.playing != mediaState.playing) AppLog.d(LogTag.MEDIA, "воспроизведение", "играет" to state.playing)
        mediaState = state
        AppVisibility.musicPlaying = state.playing
        // Звук трека — с серверов видео (4.88): пока музыка страницы играет, поток трека грузится и под своим экраном.
        com.example.vkweb.web.PageMedia.audioPlaying = state.playing
        music.setNowPlaying(state)
        // Музыка из переписки и музыка страницы не звучат вместе: заиграла страница — своя замолкает; пока звучит своя,
        // пауза страницы (мы её и поставили) не должна подменять её уведомление.
        if (state.playing) com.example.vkweb.media.ChatMusic.yieldToPage()
        val chatMusic = com.example.vkweb.media.ChatMusic.isActive
        // Обложка: что дала страница, иначе — из списка треков раздела «Музыка» (4.0).
        val withCover = if (state.cover.isEmpty()) state.copy(cover = MediaState.coverOrEmpty(music.coverFor(state))) else state
        if (settings.backgroundPlayback && !chatMusic) MusicService.update(this, withCover)
        if (state.playing) {
            wakeWeb()
            if (isInForeground && settings.backgroundPlayback) notificationPermission.requestOnce()
        } else if (isInForeground) {
            applyPendingThemeChange()
        } else {
            schedulePauseInBackground()
        }
    }

    override fun onPlayerCommand(command: PlayerCommand) {
        // Команда могла прийти, когда WebView усыплён в фоне.
        wakeWeb()
        bridge.send(command)
    }

    override fun onSeekRequested(seconds: Double) {
        wakeWeb()
        bridge.seek(seconds)
    }

    private fun schedulePauseInBackground() {
        handler.removeCallbacks(pauseWebInBackground)
        handler.postDelayed(pauseWebInBackground, AppConfig.BACKGROUND_PAUSE_DELAY_MS)
    }

    // ------------------------------------------------------------ тема

    private fun checkThemeChange() {
        val wasPending = themeChangePending
        themeChangePending = appliedTheme.changed(resources.configuration, settings)
        if (themeChangePending && !wasPending && mediaState.playing && isInForeground) {
            Notice.show(this, R.string.theme_after_playback)
        }
        applyPendingThemeChange()
    }

    /** Пересоздаём экран с новой темой, только когда это ничего не прервёт. */
    private fun applyPendingThemeChange() {
        if (!themeChangePending || mediaState.playing || !isInForeground || fullscreen.isActive) return
        themeChangePending = false
        recreate()
    }

    companion object {
        /** После повторной загрузки зациклившейся страницы второй круг в это время — без повтора. */
        private const val LOOP_RETRY_COOLDOWN_MS = 60_000L
        private const val STATE_WEB_CRASHED = "web_crashed"
        /** Сколько ждать смены адреса после перехода силами страницы, прежде чем загрузить страницу целиком. */
        private const val IN_PAGE_TIMEOUT_MS = 3_000L
        private const val MAX_SAVED_WEB_STATE_BYTES = 256 * 1024

        /** Страница одного клипа: /clip-1_2 и /clip12_3. */

        /** Открыть раздел (имя [Tab]) — из бокового меню экрана сообщений. */
        const val EXTRA_OPEN_TAB = "nav.open_tab"
        /** Открыть раздел VK, разрешённый в настройках (имя [HiddenSection]). */
        const val EXTRA_OPEN_SECTION = "nav.open_section"
        /** Открыть настройки. */
        const val EXTRA_OPEN_SETTINGS = "nav.open_settings"
        /** Открыть страницу сайта по пути (например, `/video-1_2`). */
        const val EXTRA_OPEN_URL = "nav.open_url"
        /** Вместе с [EXTRA_OPEN_URL]: страницу открыли из переписки — «Назад» возвращает туда (4.45). */
        const val EXTRA_FROM_CHAT = "nav.from_chat"
        /** Секрет процесса: запуск с [EXTRA_OPEN_URL] принимается, только если он пришёл из этого приложения. */
        const val EXTRA_INTERNAL_TOKEN = "nav.internal_token"
        /** Включить трек из переписки: номер «владелец_номер» (может быть пустым — тогда только поиск). */
        const val EXTRA_PLAY_TRACK = "nav.play_track"
        /** Открыть страницу сайта обычным переходом (без возврата в переписку): страница фото из просмотра. */
        const val EXTRA_OPEN_PAGE = "nav.open_page"
        /** Треки исполнителя (4.79): имя для поиска музыки; открывается из переписки, «Назад» — в неё. */
        const val EXTRA_MUSIC_ARTIST = "nav.music_artist"
        /** «Исполнитель — Название»: по нему страница ищет трек, если его нет в её списках. */
        const val EXTRA_PLAY_TITLE = "nav.play_title"
        private const val CHAT_TRACK_PAGE_DELAY_MS = 1200L
        /** Страница ждёт трек (с переходом на поиск) до 20 с: проверяем чуть позже. */
        private const val CHAT_TRACK_CHECK_MS = 22_000L
        val INTERNAL_TOKEN: String = java.util.UUID.randomUUID().toString()
    }
}
