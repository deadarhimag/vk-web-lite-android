package com.example.vkweb.messages

import com.example.vkweb.config.VkIds
import android.annotation.SuppressLint
import androidx.core.widget.doAfterTextChanged
import com.example.vkweb.ui.Notice
import android.graphics.Color
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import com.example.vkweb.Background
import com.example.vkweb.ui.Views
import com.example.vkweb.ui.VideoPlayerActivity
import com.example.vkweb.media.ChatMusic
import com.example.vkweb.media.ChatQueue
import com.example.vkweb.media.MusicSearch
import com.example.vkweb.web.ExternalLinks
import com.example.vkweb.web.FileChooser
import com.example.vkweb.web.InPageNavigation
import com.example.vkweb.web.UrlPolicy
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.web.TrafficCollector
import com.example.vkweb.MainActivity
import com.example.vkweb.R
import com.example.vkweb.ui.EmptyState
import com.example.vkweb.config.AppConfig
import com.example.vkweb.config.HiddenSection
import com.example.vkweb.config.Tab
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.FlowLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.offline.ImageCache
import com.example.vkweb.offline.ListMerge
import com.example.vkweb.offline.NetworkMonitor
import com.example.vkweb.offline.OfflineStore
import com.example.vkweb.outbox.Outbox
import com.example.vkweb.outbox.OutboxItem
import com.example.vkweb.outbox.OutboxLogic
import com.example.vkweb.permissions.NotificationPermission
import com.example.vkweb.settings.AppSettings
import com.example.vkweb.sync.AppVisibility
import com.example.vkweb.sync.DialogNotifyStore
import com.example.vkweb.sync.MessageNotifier
import com.example.vkweb.ui.AppTheme
import com.example.vkweb.ui.ListUpdates
import com.example.vkweb.ui.NavigationController
import com.example.vkweb.web.IdlePolicy
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Облегчённый режим сообщений: страница m.vk.com грузится в невидимом WebView, скрипт [ReaderScript]
 * забирает данные, которые мессенджер VK сам получает от своего API (и, как запасной путь, из вёрстки),
 * а экран показывает их своим интерфейсом. Страница обновляет данные сама — например, при догрузке
 * старых сообщений или приходе новых, — и они приходят сюда без отдельного запроса.
 *
 * Если данные прочитать не удалось (иная вёрстка, нет входа, пусто) — приложение молча
 * показывает саму страницу VK как запасной вариант и уведомляет об этом.
 */
class MessagesActivity : AppCompatActivity(), NavigationController.Listener, Notice.Anchor {

    private val handler = Handler(Looper.getMainLooper())
    private val channel = ReaderChannel(::onPayload)
    private lateinit var script: ReaderScript

    private lateinit var reader: WebView
    private var readerCrashed = false
    private lateinit var toolbar: MaterialToolbar
    private lateinit var progress: LinearProgressIndicator
    private lateinit var dialogsList: RecyclerView
    private lateinit var chatList: RecyclerView
    private lateinit var composeBar: View
    @Suppress("unused") // держит наблюдатель списка; сам по себе не вызывается
    private var bottomGuard: ChatBottomGuard? = null
    private lateinit var input: EditText
    private lateinit var emptyView: View

    /** Разрешение на уведомления (Android 13+): спрашиваем один раз, когда проверка новых сообщений включена. */
    private val notificationPermission = NotificationPermission(this)

    private val dialogsAdapter = DialogsAdapter(::openDialog) { conversations.showMenu(it) }
    private val links = MessageLinks(this)
    private val chatAdapter = ChatAdapter(
        ::onPendingClicked, ::onVideoClicked, ::onCardClicked, links::openLink, ::onMenu, ::jumpTo, ::onVoiceClicked, ::onVoiceSeek,
        ::onSenderClicked, ::onReactionClicked,
    )

    /** Аватар автора в беседе (4.53): его страница — как из списка участников. */
    private fun onSenderClicked(message: ChatMessage) {
        val path = ChatPeople.pathOf(message.senderId) ?: return
        AppLog.i(LogTag.MESSAGES, "аватар автора в беседе — его страница")
        links.openPage(path)
    }

    /** На что отвечаем (полоса «Ответ» над полем ввода); null — обычное сообщение. */
    private var replyTarget: ChatMessage? = null
    private lateinit var replyBar: View

    /** Старые сообщения и переход к цитате (ChatHistoryPager, 4.74). */
    private val pager: ChatHistoryPager by lazy {
        ChatHistoryPager(
            this, handler,
            chatShown = { screen == Screen.Chat && !fellBackToSite },
            historySize = { serverHistory.size },
            shown = { chatAdapter.currentList },
            loadOlder = { channel.loadOlder(reader) },
            scrollAndFlash = { index -> ChatFind.scrollAndFlash(chatList, index) },
        )
    }
    private val share by lazy { com.example.vkweb.web.ShareActions(this) }
    private lateinit var audioBar: ChatAudioBar

    /** Видео и клипы переписки — своим проигрывателем (ChatVideoOpener, 4.71). */
    private val videos: ChatVideoOpener by lazy {
        ChatVideoOpener(
            this, handler, links,
            canFetch = { channel.isSupported && !fellBackToSite && network.isOnline },
            requestFiles = { id, key -> channel.video(reader, id, key) },
            showProgress = { if (it) progress.show() else progress.hide() },
            stopPageAudio = ::stopPageAudio,
        )
    }

    private var selfRequested = false

    /** Сообщения о действиях — над полем ввода, а не поверх него. */
    override fun noticeAnchor(): View? = if (::composeBar.isInitialized && composeBar.isShown) composeBar else null

    /** Кнопка «вниз» со счётчиком новых (3.94). */
    private lateinit var scrollDown: ChatScrollDown

    /** Сообщение с файлами, пока уходит: пузырь «отправляется» до прихода настоящего ([AttachmentPending]). */
    private var attachPending: ChatMessage? = null
    private var attachMaxCmid = 0L
    /** Текст, ушедший с файлами или ответом: поле очищается по подтверждению, только если в нём всё ещё он. */
    private var sentDraft: String? = null
    private val dropAttachPending = Runnable {
        if (attachPending == null) return@Runnable
        AppLog.w(LogTag.MESSAGES, "сообщение с файлами не появилось в истории вовремя — пузырь «отправляется» убран")
        attachPending = null
        if (::attachmentComposer.isInitialized) attachmentComposer.onHistoryTimeout()
        renderChat()
        if (!fellBackToSite) channel.requestHistory(reader)
    }

    /** Последние открытые переписки этого сеанса (в памяти): повторное открытие — без пустого экрана. */
    private val recentHistory = object : LinkedHashMap<String, List<ChatMessage>>(RECENT_CHATS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ChatMessage>>?) = size > RECENT_CHATS
    }

    private fun rememberHistory(peer: String, items: List<ChatMessage>) {
        if (peer.isNotEmpty() && items.isNotEmpty()) recentHistory[peer] = items
    }

    /** Экран виден (между onStart и onStop). */
    private var visible = false

    /** Все известные переписки (сохранённые и свежие) — для «Переслать», даже если экран открыт ради одной переписки. */
    private var knownDialogs: List<DialogEntry> = emptyList()

    /** Последнее входящее, после которого переписка отмечена прочитанной (не отмечать на каждое обновление). */
    private var markedReadUpTo: String? = null


    /** Музыка и голосовые из переписки (ChatAudio, 4.72). */
    private val chatAudio: ChatAudio by lazy {
        ChatAudio(
            this, handler, links,
            audioBar = { if (::audioBar.isInitialized) audioBar else null },
            history = { serverHistory },
            chatTitle = { toolbar.title?.toString().orEmpty() },
            onSite = { fellBackToSite },
            channelReady = { channel.isSupported },
            requestQueue = { cards, wanted, title -> channel.audioQueue(reader, cards, wanted, title) },
            requestFreshUrl = { id, key, title -> channel.audioUrl(reader, id, key, title, fresh = true) },
            pressOnPage = { id, title -> channel.playAudio(reader, id, title) },
            stopPageAudio = ::stopPageAudio,
        )
    }

    /** Музыку играет сама страница (запасной путь) — замолчать: перед своим треком, голосовым или видео. */
    private fun stopPageAudio() {
        if (::audioBar.isInitialized && audioBar.isShown && !audioBar.isNative) channel.audio(reader, "stop")
    }

    // ---- вложения
    /** Выбор файла на странице VK (режим «страница VK»): без этого кнопка «прикрепить» на сайте не работала бы. */
    private val fileChooser = FileChooser(this)
    private lateinit var attachmentSender: AttachmentSender
    private lateinit var attachmentComposer: AttachmentComposer

    private var screen: Screen = Screen.Dialogs
        set(value) {
            field = value
            updateHeaderTap()
            updateBackEnabled()
        }

    /**
     * Шапка открытой переписки нажимается (4.31: к собеседнику или участникам) — с 4.33 это видно: отклик на нажатие,
     * а TalkBack называет действие. В списке диалогов шапка не нажимается.
     */
    private fun updateHeaderTap() {
        if (!::toolbar.isInitialized) return
        val chat = screen == Screen.Chat
        toolbar.isClickable = chat
        toolbar.foreground = if (chat) {
            val ripple = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            androidx.core.content.ContextCompat.getDrawable(this, ripple.resourceId)
        } else {
            null
        }
        val label = when {
            !chat -> null
            VkIds.isChat(currentPeer) -> getString(R.string.chat_header_members)
            else -> getString(R.string.chat_header_page)
        }
        androidx.core.view.ViewCompat.replaceAccessibilityAction(
            toolbar, androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, label, null,
        )
    }
    private var fellBackToSite = false
    private var retryingNative = false

    /** На сайт ушли из-за страницы входа: после входа вернуться в свой интерфейс. */
    private var fellBackForLogin = false

    /**
     * Ждём данные ограниченное время (правило то же, что у своих экранов главного окна, см. PageOverlay):
     *  - нет сети — страница и не могла загрузиться: остаёмся на сохранённом, ждём сеть;
     *  - на экране сохранённое — оставляем его с плашкой «обновить не удалось» («Обновить» — повторить);
     *  - показать нечего — сбой разбора: показываем саму страницу VK.
     */
    /**
     * Обновление списка диалогов потягиванием (4.52) — то же, что «Обновить» в панели. Без сети — сразу «нет сети».
     * Индикатор убирается, когда пришло свежее или ждать перестали (таймаут, сеть пропала).
     */
    private val pull by lazy {
        com.example.vkweb.ui.PullRefresh(findViewById(R.id.dialogs_refresh)) {
            if (!network.isOnline) {
                Notice.show(this, R.string.pull_offline)
                false
            } else {
                AppLog.i(LogTag.MESSAGES, "обновление потягиванием")
                reloadCurrent()
                true
            }
        }
    }

    private val readTimeout = Runnable {
        dialogsSkeleton.hide()
        if (pull.isRefreshing) {
            pull.done()
            Notice.show(this, R.string.pull_failed)
        }
        AppLog.w(
            LogTag.MESSAGES, "данные не пришли вовремя", "мс" to AppConfig.MESSAGES_READ_TIMEOUT_MS,
            "экран" to screen.name, "сеть" to network.isOnline, "сохранённое" to isShowingSaved(),
        )
        when {
            !network.isOnline -> showOffline(true)
            isShowingSaved() -> {
                AppLog.w(LogTag.MESSAGES, "оставлено сохранённое с пометкой «обновить не удалось»")
                progress.hide()
                setBanner(R.string.stale_banner)
            }
            LoginPages.isLogin(reader.url) -> fallbackToSite(R.string.lite_login_needed)
            else -> fallbackToSite(R.string.lite_read_failed)
        }
    }

    private lateinit var store: OfflineStore
    private lateinit var outbox: Outbox
    private lateinit var network: NetworkMonitor
    /** Плашка внизу: «нет сети» или «обновить не удалось». */
    private lateinit var banner: TextView
    private var currentPeer: String? = null
    /** Экран открыт ради одной переписки (из списка друзей): «Назад» из неё закрывает экран. */
    private var openedForPeer = false
    /** Следующую свежую порцию показать в настоящем порядке (после кнопки «Обновить»). */
    private var resortDialogs = false
    /** В списке диалогов сохранённое: первая свежая порция покажется в настоящем порядке. */
    private var dialogsFromSaved = false
    /** В переписке сохранённое, свежего ещё не было. */
    private var historyFromSaved = false
    /**
     * Сообщения открытой переписки, как их дала страница (без неотправленных из очереди — те добавляются при показе).
     */
    private var serverHistory: List<ChatMessage> = emptyList()
    private var queueCommitPending = false
    private val outboxListener: () -> Unit = { onOutboxChanged() }
    private val outboxTick: Runnable = Runnable { outboxDriver.tick() }

    /** Очередь неотправленных открытой переписки: когда нажимать «Отправить», как дождаться подтверждения. */
    private val outboxDriver: ChatOutboxDriver by lazy { ChatOutboxDriver(outbox, outboxHost) }

    private val outboxHost: ChatOutboxDriver.Host = object : ChatOutboxDriver.Host {
        override val peerId: String? get() = currentPeer
        // Пока уходят файлы, текст из очереди не отправляется: он ушёл бы вместе с ними.
        override val isChatOpen: Boolean get() = screen == Screen.Chat && !fellBackToSite && !readerCrashed && !attachmentComposer.isBusy && !voiceSending
        override val isOnline: Boolean get() = network.isOnline
        override val serverHistory: List<ChatMessage> get() = this@MessagesActivity.serverHistory
        override fun sendToPage(text: String) = channel.send(reader, text)
        override fun requestHistory() = channel.requestHistory(reader)
        override fun reloadPage() = this@MessagesActivity.reloadPage()
        override fun scheduleTick(delayMs: Long) {
            handler.removeCallbacks(outboxTick)
            handler.postDelayed(outboxTick, delayMs)
        }
        override fun cancelTick() = handler.removeCallbacks(outboxTick)
        override fun log(message: String, vararg fields: Pair<String, Any?>) = AppLog.i(LogTag.OUTBOX, message, *fields)
    }
    /** Автообновление и свежая история после отправки (ChatAutoRefresh, 4.74). */
    private val refresher: ChatAutoRefresh by lazy {
        ChatAutoRefresh(
            handler,
            isVisible = { visible },
            baseMs = { settings.messagesRefreshSeconds * 1000L },
            canRefresh = {
                // Перезагрузка страницы оборвала бы загрузку вложений.
                // Музыку играет сама страница — перезагрузка оборвала бы её, а полоса осталась бы «играет» (код ревью 3.59).
                val pageAudio = ::audioBar.isInitialized && audioBar.isShown && !audioBar.isNative
                val busy = outboxDriver.hasInFlight() || attachmentComposer.isBusy || pageAudio
                !fellBackToSite && network.isOnline && !busy && !banner.isVisible
            },
            screenName = { screen.name },
            // Запросом с токеном страницы, без её перезагрузки (3.67); данных входа ещё нет — перезагрузка, как раньше.
            refresh = { channel.refresh(reader) { ok -> if (!ok && !isFinishing && !isDestroyed) reloadPage() } },
            onSite = { fellBackToSite },
            requestHistory = { channel.requestHistory(reader) },
        )
    }

    /** Приложение ушло с экрана и ничего не играет: окно чтения не должно работать впустую. */
    private val pauseReader = Runnable {
        if (!readerCrashed && IdlePolicy.canPauseWeb(AppVisibility.mainOpen, AppVisibility.messagesOpen, AppVisibility.musicPlaying, AppVisibility.syncRunning, AppVisibility.cacheRunning)) reader.pauseTimers()
    }
    private lateinit var settings: AppSettings
    private lateinit var drawer: DrawerLayout
    private lateinit var navigation: NavigationController

    private enum class Screen { Dialogs, Chat }

    /** Тема, с которой создан экран: после настроек сверяется с выбранной. */
    private var appliedTheme = AppTheme.Applied()

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = AppSettings(this)
        appliedTheme = AppTheme.applyAndRemember(this, settings)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_messages)
        // 4.54: на холодном старте заставка ждёт сохранённый список (или переписку), но не дольше секунды.
        if (savedInstanceState == null) com.example.vkweb.ui.SplashHold.hold(this)
        com.example.vkweb.ui.AppShortcuts.refreshOnce(this)
        // Под системными панелями (3.94): поле ввода — над панелью навигации и клавиатурой.
        com.example.vkweb.ui.SystemBars.edgeToEdge(this)
        com.example.vkweb.ui.SystemBars.toolbar(findViewById(R.id.messages_toolbar))
        store = OfflineStore.get(this)
        outbox = Outbox.get(this)
        network = NetworkMonitor(this, ::onOnline) { showOffline(true) }
        ImageCache.get(this).maxBytes = ImageCache.megabytes(settings.imageCacheMb)
        bindViews()
        setupNavigation()
        setupPanes()
        dialogsAdapter.drafts = drafts.all
        setupReaderWebView()
        showDialogs()
        // Папки — сразу из сохранённого (3.93): страница присылает их позже диалогов, и полоса появлялась с опозданием.
        folders = savedFolders.value
        if (screen == Screen.Dialogs) renderFolderChips()
        showSavedDialogs()
        loadDialogs()
        // После пересоздания просьба из intent («открыть переписку» из уведомления или списка друзей) уже выполнена:
        // открывается та переписка, что была на экране (человек мог перейти в другую или вернуться к списку).
        if (savedInstanceState == null) openRequestedPeer(intent) else restoreOpenChat(savedInstanceState)
        receiveShare(intent)
        // Разрешения — после того, как экран показан (onStart): сначала уведомления, и только если их разрешили —
        // вопрос о батарее (раньше оба окна появлялись сразу друг за другом, ещё до списка диалогов).
    }

    /** «Поделиться» (4.57): выбор, кому — как только известен список диалогов. */
    private val shareIntoChat by lazy {
        ShareIntoChat(this, object : ShareIntoChat.Host {
            override val dialogs: List<DialogEntry> get() = allDialogs.ifEmpty { knownDialogs }
            override fun deliver(dialog: DialogEntry, share: ShareContent) {
                if (fellBackToSite) {
                    Notice.show(this@MessagesActivity, R.string.share_open_failed, long = true)
                    return
                }
                if (screen != Screen.Chat || currentPeer != dialog.peerId) openDialog(dialog)
                // Поле ввода и вложения — после открытия (открытие кладёт в поле черновик этой переписки).
                handler.post {
                    if (share.text.isNotEmpty()) {
                        val before = input.text?.toString().orEmpty()
                        input.setText(if (before.isBlank()) share.text else before + "\n" + share.text)
                        input.setSelection(input.text?.length ?: 0)
                    }
                    if (share.uris.isNotEmpty()) attachmentComposer.addShared(share.uris.map(android.net.Uri::parse))
                    input.requestFocus()
                }
            }
        })
    }

    private fun receiveShare(intent: Intent?) {
        intent ?: return
        val text = intent.getStringExtra(EXTRA_SHARE_TEXT)
        val uris = intent.getStringArrayListExtra(EXTRA_SHARE_URIS)
        if (text == null && uris == null) return
        // Один раз: пересоздание окна (тема) не должно предлагать то же снова.
        intent.removeExtra(EXTRA_SHARE_TEXT)
        intent.removeExtra(EXTRA_SHARE_URIS)
        val share = ShareContent.of(text, null, uris.orEmpty()) ?: return
        shareIntoChat.receive(share)
    }

    /** Открыли из списка друзей («Написать»): сразу переписка, «Назад» вернёт к друзьям. */
    private fun openRequestedPeer(intent: Intent?) {
        val peer = intent?.getStringExtra(EXTRA_PEER_ID)?.takeIf { VkIds.isPeer(it) } ?: return
        // Эта переписка уже открыта (нажали уведомление о ней): не открываем её заново — список не пустеет, только
        // свежие сообщения.
        if (screen == Screen.Chat && currentPeer == peer && !fellBackToSite) {
            channel.requestHistory(reader)
            return
        }
        openedForPeer = true
        openDialog(
            DialogEntry(
                peerId = peer,
                title = intent.getStringExtra(EXTRA_PEER_TITLE).orEmpty(),
                snippet = "",
                time = "",
                unread = false,
                online = false,
                avatar = intent.getStringExtra(EXTRA_PEER_AVATAR).orEmpty(),
            )
        )
    }

    /** Когда экран вернулся на передний план из другого своего экрана ([onRestart]); 0 — не возвращался. */
    private var restartedAt = 0L

    override fun onRestart() {
        super.onRestart()
        restartedAt = android.os.SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        if (readerCrashed) { recreate(); return }
        // Свой признак: внутри onStart() состояние жизненного цикла ещё CREATED (ON_START приходит после него), и
        // проверка isAtLeast(STARTED) отключала здесь автообновление и «прочитано» (код ревью 3.59).
        visible = true
        // Полоса музыки показывает то, что звучит из переписки (и после возврата из фона); сбой трека — свежий адрес
        // через окно чтения, пока экран виден.
        if (::audioBar.isInitialized) audioBar.attach()
        ChatMusic.onTrackFailed = chatAudio::onTrackFailed
        AppVisibility.messagesOpen = true
        AppVisibility.openChatPeer = if (screen == Screen.Chat) currentPeer else null
        // Настройки уведомлений диалогов могли поменять на экране настроек.
        applyNotifyRules()
        // 4.61: картинки реакций — сохранённые (без сети реакции тоже видны).
        reactions.restoreSaved()
        // Таймеры общие для процесса: главное окно могло их усыпить. Без этого окно чтения замерло бы,
        // и история переписки не пришла бы.
        handler.removeCallbacks(pauseReader)
        reader.onResume()
        reader.resumeTimers()
        // Экран сообщений открыт — уведомление о них больше не нужно.
        MessageNotifier(this).cancel()
        network.start()
        applyNavigationSettings()
        outbox.addListener(outboxListener)
        outbox.addScheduledDeliveredListener(scheduledDelivered)
        outbox.whenReady { onOutboxChanged() }
        // Вернулись на экран — обновлять снова с частотой из настройки.
        refresher.onStart()
        if (settings.messageSyncMinutes > 0 && settings.notifyMessages) {
            // 4.88: сначала уведомления, и только с ними — вопрос о батарее (уведомления опаздывали на часы из-за
            // ограничений батареи, отчёт 3.65; без разрешения на уведомления снимать их незачем). Вопрос о батарее
            // задаётся один раз за всё время (BatteryExemption).
            notificationPermission.requestOnce(R.string.notif_permission_messages) {
                if (!isFinishing && visible) com.example.vkweb.sync.BatteryExemption.maybeAsk(this)
            }
        }
        // Тема системы сменилась, пока шла запись голосового или отправка файлов.
        applyPendingThemeChange()
        // Вернулись в открытую переписку — то, что пришло, пока экрана не было видно, теперь прочитано.
        markReadIfShown()
    }

    /** Нажали уведомление, когда экран уже открыт: перейти в нужный диалог. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequestedPeer(intent)
        receiveShare(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        navigation.updateLayout(newConfig.screenWidthDp)
        updatePanes()
        // uiMode в configChanges: смену темы системы (тема «Как в системе», ночной режим по расписанию) экран получает
        // сам. Без пересоздания уже созданные строки оставались в старой теме, а новые рисовались в новой.
        checkThemeChange()
    }

    /** Тема системы сменилась, а экран ещё в старой: пересоздать, когда это ничего не оборвёт. */
    private var themeChangePending = false

    private fun checkThemeChange() {
        if (!::settings.isInitialized) return
        themeChangePending = appliedTheme.changed(resources.configuration, settings)
        applyPendingThemeChange()
    }

    /**
     * Пересоздание оборвало бы запись голосового и отправку файлов — тогда позже: при возврате на экран
     * ([onStart]) или при возврате к списку диалогов ([showDialogs]). Открытая переписка переживает пересоздание
     * ([onSaveInstanceState]).
     */
    private fun applyPendingThemeChange() {
        if (!themeChangePending) return
        val busy = (::voice.isInitialized && voice.isRecording) ||
            (::attachmentComposer.isInitialized && attachmentComposer.isBusy)
        if (busy) return
        themeChangePending = false
        AppLog.i(LogTag.APP, "тема системы сменилась — экран сообщений пересоздаётся")
        saveDraft()
        recreate()
    }

    // ------------------------------------------------------------ открытая переписка после пересоздания

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Пересоздание (тема, настройки, смерть процесса в фоне) раньше всегда возвращало к списку диалогов.
        // Страница VK вместо своего интерфейса не сохраняется: после пересоздания — снова свой интерфейс.
        val peer = currentPeer
        if (screen == Screen.Chat && (!fellBackToSite || retryingNative) && peer != null) {
            outState.putString(STATE_PEER, peer)
            outState.putString(STATE_PEER_TITLE, toolbar.title?.toString().orEmpty())
            outState.putBoolean(STATE_OPENED_FOR_PEER, openedForPeer)
        }
    }

    /** @return true — переписка из сохранённого состояния открыта. */
    private fun restoreOpenChat(saved: Bundle): Boolean {
        val peer = saved.getString(STATE_PEER)?.takeIf { VkIds.isPeer(it) } ?: return false
        val known = (allDialogs.ifEmpty { knownDialogs }).firstOrNull { it.peerId == peer }
        AppLog.i(LogTag.MESSAGES, "после пересоздания экрана — снова открытая переписка")
        openedForPeer = saved.getBoolean(STATE_OPENED_FOR_PEER)
        openDialog(
            known ?: DialogEntry(
                peerId = peer,
                title = saved.getString(STATE_PEER_TITLE).orEmpty(),
                snippet = "",
                time = "",
                unread = false,
                online = false,
            )
        )
        return true
    }

    // ------------------------------------------------------------ черновики (3.94)

    private val drafts by lazy { Drafts(this) }

    /** Текст поля — черновик открытой переписки (пустой — черновика нет). Отправляемый ответ черновиком не считается. */
    private fun saveDraft() {
        val peer = currentPeer ?: return
        if (screen != Screen.Chat || fellBackToSite || !::input.isInitialized) return
        var text = input.text?.toString().orEmpty()
        val sending = sentDraft
        if (sending != null && text.trim() == sending.trim()) text = ""
        // Текст изменяемого сообщения — не черновик: черновик сохранён, когда изменение началось.
        if (editing.isEditing) return
        if (drafts.put(peer, text)) dialogsAdapter.drafts = drafts.all
    }

    /** Открыли переписку: в поле — её черновик (или пусто, а не текст из прошлой переписки). */
    private fun restoreDraft(peer: String) {
        val text = drafts.get(peer)
        input.setText(text)
        input.setSelection(input.text?.length ?: 0)
    }

    // ------------------------------------------------------------ две колонки (3.94)

    private lateinit var panes: MessagesPanes
    private lateinit var chatPlaceholder: View
    private val twoPane: Boolean get() = ::panes.isInitialized && panes.isTwoPane

    private fun setupPanes() {
        chatPlaceholder = findViewById(R.id.chat_placeholder)
        panes = MessagesPanes(
            pane = findViewById(R.id.messages_dialogs_pane),
            paneFrame = findViewById(R.id.messages_dialogs_pane_frame),
            paneDivider = findViewById(R.id.messages_dialogs_pane_divider),
            folders = findViewById(R.id.dialogs_folders_scroll),
            // Рамка потягивания (4.52) переезжает вместе со списком.
            dialogsList = findViewById(R.id.dialogs_refresh),
            empty = emptyView,
        )
        // Заголовок колонки — под строкой состояния, как и панель справа; список — над панелью навигации.
        com.example.vkweb.ui.SystemBars.paneTop(findViewById(R.id.messages_dialogs_pane_title))
        com.example.vkweb.ui.SystemBars.bottom(findViewById(R.id.messages_dialogs_pane_frame), sides = false, bar = navigation.bottomInsets)
        updatePanes()
    }

    /** Когда список слева запрашивался в последний раз (мс, часы устройства). */
    private var sidePaneRefreshedAt = 0L

    /**
     * Две колонки, открыта переписка: страница стоит на ней, и список слева сам не обновляется. При свежей истории (её
     * присылает и долгий опрос — значит, что-то пришло) — не чаще раза в [SIDE_PANE_REFRESH_MS] запросить и список.
     */
    private fun refreshSidePaneDialogs() {
        if (!twoPane || screen != Screen.Chat || fellBackToSite || !network.isOnline) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - sidePaneRefreshedAt < SIDE_PANE_REFRESH_MS) return
        sidePaneRefreshedAt = now
        channel.refreshDialogs(reader)
    }

    /** Ширина экрана или закреплённость меню сменились: одна или две колонки. */
    private fun updatePanes() {
        if (!::panes.isInitialized) return
        val density = resources.displayMetrics.density
        val navDp = if (navigation.isPinned) (resources.getDimension(R.dimen.nav_width) / density).toInt() + 1 else 0
        val paneDp = (resources.getDimension(R.dimen.messages_pane_width) / density).toInt() + 1
        val split = !fellBackToSite && MessagesPanes.shouldSplit(settings.messagesTwoPane, resources.configuration.screenWidthDp, navDp, paneDp)
        if (!panes.set(split)) return
        AppLog.i(LogTag.MESSAGES, "раскладка сообщений", "колонок" to if (split) 2 else 1)
        syncPaneVisibility()
    }

    /**
     * Нижняя панель разделов (4.50) — не в открытой переписке на одну колонку: там внизу поле ввода. В двух колонках
     * список рядом, и панель остаётся.
     */
    private fun updateBottomBar() {
        if (!::navigation.isInitialized) return
        navigation.bottomBarSuppressed = screen == Screen.Chat && !twoPane && !fellBackToSite
    }

    /** Плавная смена списка и переписки внутри колонки сообщений: растворение без сдвига (4.83). */
    private fun switchScreens() {
        if (fellBackToSite) return
        com.example.vkweb.ui.Motion.crossFade(findViewById(R.id.messages_column), chatList, dialogsList)
    }

    /** Что видно при текущей раскладке и экране (переписка или список) — без перезагрузки данных. */
    private fun syncPaneVisibility() {
        updateBottomBar()
        if (fellBackToSite) return
        val chat = screen == Screen.Chat
        dialogsList.isVisible = twoPane || !chat
        chatList.isVisible = chat
        composeBar.isVisible = chat
        chatPlaceholder.isVisible = twoPane && !chat
        if (!chat) toolbar.title = if (twoPane) null else getString(R.string.tab_messages)
        if (::search.isInitialized) search.isVisible = twoPane || !chat
        dialogsAdapter.selectedPeer = if (twoPane && chat) currentPeer else null
        if (chat && !twoPane) {
            // Стало уже — поиск по списку в переписке не нужен (как при обычном открытии переписки).
            closeSearch()
            emptyView.isVisible = false
            findViewById<View>(R.id.dialogs_folders_scroll).isVisible = false
        } else {
            renderDialogs()
        }
    }

    // ------------------------------------------------------------ боковое меню

    private fun setupNavigation() {
        drawer = findViewById(R.id.messages_drawer)
        navigation = NavigationController(
            drawer = drawer,
            navView = findViewById<NavigationView>(R.id.messages_nav),
            permanentContainer = findViewById(R.id.messages_permanent_nav),
            permanentDivider = findViewById(R.id.messages_permanent_divider),
            toolbar = toolbar,
            listener = this,
            bottomBar = findViewById(R.id.messages_bottom_nav),
            rail = findViewById(R.id.messages_rail),
        )
        // Поле ввода — над панелью навигации и клавиатурой; под нижней панелью разделов (4.50) — без отступа от панели
        // навигации: он у самой панели.
        com.example.vkweb.ui.SystemBars.bottom(findViewById(R.id.messages_column), bar = navigation.bottomInsets)
        navigation.highlight(Tab.MESSAGES)
        navigation.showProfile(com.example.vkweb.account.SelfProfile.load(this))
        // 4.88: обработчик включён, только когда «Назад» есть что делать внутри экрана (переписка, меню, поиск).
        // Иначе «Назад» — системное: работает анимация жеста (predictive back), а экран, открытый первым, Android
        // сворачивает, а не закрывает — следующий запуск без холодного старта. Раньше обработчик был включён всегда и
        // заканчивался finish().
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) = updateBackEnabled()
            override fun onDrawerClosed(drawerView: View) = updateBackEnabled()
        })
        backCallback = onBackPressedDispatcher.addCallback(this) {
            when {
                navigation.handleBack() -> Unit
                closeSearch() -> Unit
                // Вернулись в переписку из видео, фото, музыки или страницы — второе «Назад», пришедшее, пока закрывался
                // прежний экран, переписку не закрывает (отчёт 4.33: из страницы собеседника «Назад» вернуло в
                // переписку, и через секунду она закрылась к списку диалогов). 4.38.
                screen == Screen.Chat && !fellBackToSite && android.os.SystemClock.elapsedRealtime() - restartedAt < BACK_ECHO_MS ->
                    AppLog.i(LogTag.MESSAGES, "назад сразу после возврата в переписку — пропущено")
                screen == Screen.Chat && !fellBackToSite -> {
                    AppLog.i(LogTag.MESSAGES, "назад: из переписки к списку диалогов")
                    onBack()
                }
                else -> {
                    // Состояние сменилось мимо updateBackEnabled — отдать «Назад» системе.
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    updateBackEnabled()
                }
            }
        }
        updateBackEnabled()
    }

    private lateinit var backCallback: OnBackPressedCallback

    /** Поле поиска по диалогам или в переписке раскрыто: «Назад» его сворачивает. */
    private var dialogSearchOpen = false
    private var chatFindOpen = false

    /** «Назад» нужен экрану: открыта переписка (к списку), меню или поле поиска (закрыть). */
    private fun updateBackEnabled() {
        if (!::backCallback.isInitialized) return
        backCallback.isEnabled = (screen == Screen.Chat && !fellBackToSite) ||
            (::navigation.isInitialized && navigation.isMenuOpen) || dialogSearchOpen || chatFindOpen
    }

    /** Настройки читаются при каждом возврате на экран: так изменения из «Настроек» применяются сразу. */
    private fun applyNavigationSettings() {
        navigation.applySettings(settings)
        updatePanes()
        navigation.highlight(Tab.MESSAGES)
    }

    // ------------------------------------------------------------ разделы свайпом (4.13)

    private val sectionSwipe by lazy { com.example.vkweb.ui.SectionSwipeDetector(window.decorView, ::onSectionSwipe) }
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean =
        sectionSwipe.dispatch(event, ::sectionSwipeAllowed) { super.dispatchTouchEvent(it) }

    /** Только список диалогов: в переписке движение вбок — ответ на сообщение. */
    private fun sectionSwipeAllowed(): Boolean =
        ::settings.isInitialized && settings.sectionSwipe && ::navigation.isInitialized && screen == Screen.Dialogs &&
            !fellBackToSite && !navigation.isMenuOpen

    /** 4.19: свайп по списку — к соседней папке («Все», «Непрочитанные», папки VK). */
    private fun onSectionSwipe(direction: com.example.vkweb.ui.SectionSwipe.Direction) {
        val row = findViewById<com.google.android.material.chip.ChipGroup>(R.id.dialogs_folders)
        if (!row.isShown) return
        if (com.example.vkweb.ui.TabSwipe.step(row, direction)) AppLog.i(LogTag.APP, "папка свайпом", "куда" to direction.name)
    }

    override fun onSectionSelected(tab: Tab) {
        if (tab == Tab.MESSAGES) {
            // Явный выбор «Сообщений» — к списку диалогов, а не обратно к друзьям.
            openedForPeer = false
            if (screen == Screen.Chat && !fellBackToSite) onBack()
            return
        }
        openInMain { putExtra(MainActivity.EXTRA_OPEN_TAB, tab.name) }
    }

    override fun onExtraSectionSelected(section: HiddenSection) =
        openInMain { putExtra(MainActivity.EXTRA_OPEN_SECTION, section.name) }

    /** «Сообщения» нажаты ещё раз на нижней панели (4.50): со списка — к его началу, из переписки — к списку. */
    override fun onSectionReselected(tab: Tab) {
        if (tab == Tab.MESSAGES && screen == Screen.Dialogs && !fellBackToSite) {
            dialogsList.smoothScrollToPosition(0)
            return
        }
        onSectionSelected(tab)
    }

    /**
     * Настройки — прямо отсюда (3.93). Раньше экран сообщений закрывался, а настройки открывало главное окно без
     * загруженной страницы: «Назад» из настроек возвращало в него — заголовок «Сообщения» и чёрный пустой экран.
     */
    /** Шапка меню — своя страница (3.94): открывается на сайте в главном окне, как «Страница фото». */
    override fun onProfileSelected(profile: com.example.vkweb.account.SelfProfile) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_PAGE, profile.path)
                .putExtra(MainActivity.EXTRA_INTERNAL_TOKEN, MainActivity.INTERNAL_TOKEN),
        )
    }

    override fun onSettingsSelected() {
        settingsBefore = settingsSnapshot()
        settingsLauncher.launch(Unit)
    }

    /** Все настройки до открытия экрана настроек: что-то сменилось — экран пересоздаётся и читает их заново. */
    private var settingsBefore: String? = null

    private fun settingsSnapshot(): String = com.example.vkweb.settings.SettingsSnapshot.of(
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(applicationContext).all,
    )

    private val settingsLauncher = registerForActivityResult(com.example.vkweb.settings.SettingsOutcome.Contract()) { outcome: com.example.vkweb.settings.SettingsOutcome ->
        val fresh = AppSettings(this)
        AppLog.i(LogTag.APP, "настройки закрыты (из сообщений)", "выход из аккаунта" to outcome.signedOut.takeIf { it })
        when {
            // Выход из аккаунта или свой экран сообщений выключен — дальше главное окно (вход, страница сообщений VK).
            outcome.signedOut || !fresh.nativeMessages -> openInMain { }
            // Тема, цвета обоев или другие настройки сменились — экран пересоздаётся и читает их заново (раньше это
            // делало закрытие экрана перед настройками).
            appliedTheme.changed(resources.configuration, fresh) ||
                (settingsBefore != null && settingsBefore != settingsSnapshot()) -> recreate()
            else -> {
                settings = fresh
                if (outcome.clearCache) reader.clearCache(true)
                if (outcome.storageCleared) {
                    allDialogs = emptyList()
                    renderDialogs()
                }
                if (screen == Screen.Dialogs) loadDialogs()
            }
        }
    }

    /** Остальные разделы живут на главном экране: возвращаемся к нему с просьбой открыть нужное. */
    private fun openInMain(extras: Intent.() -> Unit) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_INTERNAL_TOKEN, MainActivity.INTERNAL_TOKEN)
            .apply(extras)
        // 4.50: смена раздела, а не новое окно — без анимации.
        startActivity(intent, com.example.vkweb.ui.Transitions.none(this))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onStop() {
        // 4.59: свернули во время записи голосового — запись отменяется (удержание кнопки прервано).
        if (::voice.isInitialized) voice.release()
        saveDraft()
        visible = false
        ChatMusic.onTrackFailed = null
        // Ответы на нажатия (адрес трека, файлы видео), пришедшие после ухода с экрана, не должны включать звук или
        // открывать окна поверх других приложений.
        chatAudio.cancel()
        videos.cancel()
        // Автообновление и обновление после отправки — для открытого экрана; не успело — повторится в onStart.
        refresher.stop()
        // Голосовое играет, пока экран виден: ушли из переписки — оно не должно звучать в фоне.
        VoicePlayer.stopVoice()
        // Музыка из переписки — как музыка раздела: в фоне играет дальше (уведомление с кнопками), если это разрешено.
        if (!settings.backgroundPlayback) ChatMusic.stop()
        if (::audioBar.isInitialized) {
            // Трек нажимала страница (запасной путь): у неё нет уведомления — без экрана её не остановить.
            if (audioBar.isShown && !audioBar.isNative) {
                channel.audio(reader, "stop")
                audioBar.hide()
            }
            audioBar.detach()
        }
        AppVisibility.messagesOpen = false
        AppVisibility.openChatPeer = null
        if (!readerCrashed) {
            reader.onPause()
            handler.postDelayed(pauseReader, AppConfig.BACKGROUND_PAUSE_DELAY_MS)
        }
        outbox.removeListener(outboxListener)
        outbox.removeScheduledDeliveredListener(scheduledDelivered)
        outboxDriver.stop()
        network.stop()
        if (!readerCrashed) TrafficCollector.collect(reader)
        AppLog.syncToDisk()
        super.onStop()
    }

    override fun onDestroy() {
        rememberInterruptedVoice()
        if (::voice.isInitialized) voice.destroy()
        if (::attachmentComposer.isInitialized) attachmentComposer.cancel()
        fileChooser.cancel()
        handler.removeCallbacksAndMessages(null)
        if (::stickers.isInitialized) stickers.release()
        if (forwardingLazy.isInitialized()) forwarding.release()
        channel.dispose()
        (reader.parent as? ViewGroup)?.removeView(reader)
        if (!readerCrashed) reader.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------ разметка

    private fun bindViews() {
        toolbar = findViewById(R.id.messages_toolbar)
        // 4.31: шапка открытой переписки — к собеседнику (в беседе — список участников, ChatPeople).
        toolbar.setOnClickListener { if (screen == Screen.Chat) openChatPeople() }
        updateHeaderTap()
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_reload -> { reloadCurrent(); true }
                R.id.action_open_site -> { fallbackToSite(null); true }
                R.id.action_chat_notify -> { openChatNotify(); true }
                else -> false
            }
        }
        setupDialogSearch()
        progress = findViewById(R.id.messages_progress)
        banner = findViewById(R.id.messages_offline)
        emptyView = findViewById(R.id.messages_empty)

        dialogsList = findViewById<RecyclerView>(R.id.dialogs_list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = dialogsAdapter
            ListUpdates.prepare(this)
        }
        // Жест потягивания — сразу, а не при первом обращении (4.52).
        pull.done()
        chatList = findViewById<RecyclerView>(R.id.chat_list).apply {
            layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
            adapter = chatAdapter
            // 4.82: строки переписки без анимации: любое обновление (отметки прочтения, реакции, свежая история поверх
            // сохранённой) двигало строки от старых мест к новым, и сообщения то «долетали сверху», то улетали вверх и
            // съезжали обратно. Новые снизу по-прежнему приезжают плавной прокруткой (ChatScrollPolicy).
            itemAnimator = null
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    // Подходим к началу — просим страницу догрузить историю заранее, пока человек ещё листает.
                    if (dy < 0 && nearTop(recyclerView)) pager.requestOlder()
                }

                /**
                 * Уже наверху и тянет дальше: список не двигается, и onScrolled не приходит. Раньше повторить запрос
                 * отсюда было нельзя — если первая попытка не принесла сообщений, догрузка «застревала».
                 */
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    // Человек сам листает - после отправки список его больше не тянет вниз
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) followSentUntil = 0L
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING && !recyclerView.canScrollVertically(-1)) pager.requestOlder()
                }
            })
            addItemDecoration(ChatDayDecoration(this, chatAdapter))
        }
        // 4.86: человек внизу — после любого обновления список не покажет кадр, где последнее сообщение уехало вниз.
        bottomGuard = ChatBottomGuard(chatList)
        // Поле поиска в переписке — с первого нажатия (обработчики ставятся при создании ChatFind).
        setFindVisible(false)
        // Потянуть сообщение вправо — ответить на него (3.94); можно там же, где пункт меню «Ответить».
        ChatSwipeReply(
            chatList, chatAdapter,
            canReply = { m -> m.isConfirmed && screen == Screen.Chat && !fellBackToSite && composeBar.isShown && input.isEnabled },
            onReply = { m -> setReplyTarget(m) },
        )
        scrollDown = ChatScrollDown(
            list = chatList,
            container = findViewById(R.id.chat_scroll_down),
            button = findViewById(R.id.chat_scroll_down_button),
            counter = findViewById(R.id.chat_scroll_down_count),
            isChatShown = { screen == Screen.Chat && !fellBackToSite },
        )
        audioBar = ChatAudioBar(
            findViewById(android.R.id.content),
            onPageToggle = { channel.audio(reader, "toggle") },
            onPageStop = { channel.audio(reader, "stop") },
        )
        replyBar = findViewById(R.id.compose_reply)
        findViewById<View>(R.id.compose_reply_cancel).setOnClickListener { setReplyTarget(null) }
        findViewById<View>(R.id.compose_reply_quote).setOnClickListener {
            replyTarget?.let { jumpTo(ChatReply(cmidOf(it) ?: 0, it.sender, it.text)) }
        }
        composeBar = findViewById(R.id.compose_bar)
        input = findViewById(R.id.compose_input)
        findViewById<View>(R.id.compose_send).setOnClickListener { sendCurrent() }
        // Долгое нажатие — отложить (4.17).
        findViewById<View>(R.id.compose_send).setOnLongClickListener { scheduleCurrent(); true }
        setupStickers()
        findViewById<View>(R.id.compose_sticker).setOnClickListener { stickers.requestRecent() }
        setupVoice()
        attachmentComposer = AttachmentComposer(
            activity = this,
            scroll = findViewById(R.id.attachments_scroll),
            bar = findViewById(R.id.attachments_bar),
            sendButton = findViewById(R.id.compose_send),
            attachButton = findViewById(R.id.compose_attach),
            handler = handler,
            sender = { attachmentSender },
            statusRow = findViewById(R.id.attachment_status),
            statusText = findViewById(R.id.attachment_status_text),
            cancelButton = findViewById(R.id.attachment_status_cancel),
            isOnline = { network.isOnline },
            isSiteMode = { fellBackToSite },
            onFilesChanged = { updateMicButton() },
            // Пока уходят файлы, очередь неотправленных ждёт; закончили — она продолжает.
            onBusyChanged = { busy -> if (!busy) outboxDriver.onOutboxChanged() },
            onDelivered = {
                clearSentDraft()
                refresher.refreshAfterSend()
                // Настоящее сообщение страница запрашивает сразу; если и оно не пришло — пузырь не висит вечно.
                handler.removeCallbacks(dropAttachPending)
                handler.postDelayed(dropAttachPending, ATTACH_PENDING_MAX_MS)
            },
            onStarted = { text, names ->
                attachPending = AttachmentPending.message(text, names, System.currentTimeMillis())
                attachMaxCmid = AttachmentPending.maxCmid(serverHistory)
                sentDraft = text
                renderChat()
            },
            onFailed = {
                attachPending = null
                sentDraft = null
                renderChat()
            },
        )

        // Страница, из которой читаем данные. В режиме чтения она невидима, но с настоящим размером:
        // мессенджер VK рисует и догружает переписку, только когда у него есть высота.
        reader = findViewById(R.id.reader_web)
        reader.visibility = View.INVISIBLE
        findViewById<View>(R.id.messages_web_mode_retry).setOnClickListener {
            if (!network.isOnline) { Notice.show(this, R.string.pull_offline); return@setOnClickListener }
            if (fellBackForLogin) reader.loadUrl(ReaderChannel.DIALOGS_URL)
            else {
                retryingNative = true
                recreate()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupReaderWebView() {
        // Общая настройка окна чтения — та же, что у фоновой работы (ReaderWindow, 4.66).
        script = ReaderWindow.setUp(
            reader, channel, settings, dumpLabel = "сообщения", isActive = { !fellBackToSite && !readerCrashed },
            onStarted = ::onReaderPageStarted, onFinished = ::onReaderPageFinished,
            onRendererGone = {
                readerCrashed = true
                handler.removeCallbacksAndMessages(null)
                if (!isFinishing && !isDestroyed) recreate()
            },
        )
        // Выбор файла на самой странице VK (режим «страница VK»): система открывает выбор, страница получает файлы.
        reader.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
                fileChooser.open(callback, params)
        }
        attachmentSender = AttachmentSender(this, channel, reader) { currentPeer }
    }

    // ------------------------------------------------------------ загрузка

    private fun loadDialogs() {
        if (!channel.isSupported) { fallbackToSite(R.string.lite_unsupported); return }
        if (!network.isOnline) { showOffline(true); return }
        showOffline(false)
        armTimeout()
        navigateReader(ReaderChannel.DIALOGS_PATH, ReaderChannel.DIALOGS_URL)
    }

    /**
     * Окно чтения — на [path] силами самого мессенджера (3.67): раньше каждое открытие переписки и возврат к списку
     * загружали страницу заново (документ, скрипты, запросы VK). Страница ещё не загружена, скрипта нет или переход не
     * состоялся за [READER_GO_TIMEOUT_MS] — загрузка [url], как раньше.
     */
    private fun navigateReader(path: String, url: String) {
        handler.removeCallbacks(readerGoFallback)
        readerGoTarget = null
        val ready = reader.progress >= 100 && (ReaderChannel.isDialogsUrl(reader.url) || ReaderChannel.isHistoryUrl(reader.url))
        if (!ready) {
            readerGoRequested = null
            reader.loadUrl(url)
            return
        }
        val requested = path
        channel.go(reader, path) { started ->
            if (isFinishing || isDestroyed) return@go
            // Пока ждали ответа, попросили другое место: его переход уже идёт своим путём.
            if (readerGoRequested != requested) return@go
            if (!started) {
                AppLog.d(LogTag.MESSAGES, "переход без загрузки невозможен — загрузка страницы", "куда" to path.replace(Regex("\\d{3,}"), "#"))
                reader.loadUrl(url)
                return@go
            }
            AppLog.d(LogTag.MESSAGES, "переход без загрузки страницы", "куда" to path.replace(Regex("\\d{3,}"), "#"))
            readerGoTarget = path to url
            handler.postDelayed(readerGoFallback, READER_GO_TIMEOUT_MS)
        }
        readerGoRequested = requested
    }

    private var readerGoRequested: String? = null
    private var readerGoTarget: Pair<String, String>? = null

    private val readerGoFallback = Runnable {
        val (path, url) = readerGoTarget ?: return@Runnable
        readerGoTarget = null
        val now = reader.url?.let { android.net.Uri.parse(it).path?.trimEnd('/') }
        if (now == path) return@Runnable
        AppLog.w(LogTag.MESSAGES, "переход без загрузки не состоялся — загрузка страницы", "куда" to path.replace(Regex("\\d{3,}"), "#"))
        reader.loadUrl(url)
    }

    /** Сохранённый список — сразу, пока страница грузится (или если сети нет). */
    private fun showSavedDialogs() {
        store.loadDialogs { saved ->
            if (!saved.isNullOrEmpty() && knownDialogs.isEmpty()) knownDialogs = saved
            // 4.56: сохранённого нет, свежее ещё не пришло — силуэты строк, пока ждём (не пустой экран).
            if (saved.isNullOrEmpty() && allDialogs.isEmpty() && screen == Screen.Dialogs && network.isOnline && !fellBackToSite) {
                dialogsSkeleton.show()
            }
            // 4.54: заставка запуска держится до этого момента — дальше список уже на экране (или сохранённого нет).
            com.example.vkweb.ui.SplashHold.ready = true
            shareIntoChat.maybeShow()
            if (saved.isNullOrEmpty() || screen != Screen.Dialogs) return@loadDialogs
            // Свежие данные главнее: сохранённые лишь дополняют то, что уже пришло.
            if (allDialogs.isEmpty()) dialogsFromSaved = true
            allDialogs = ListMerge.appendMissing(allDialogs, saved) { it.peerId }
            renderDialogs()
            emptyView.isVisible = false
        }
    }

    private fun showSavedHistory(peerId: String) {
        store.loadHistory(peerId) { saved ->
            // Есть ли сохранённая переписка и сколько в ней — по этому в отчёте видно, работает ли кэш сообщений.
            AppLog.i(
                LogTag.MESSAGES, if (saved == null) "переписка открыта: сохранённой нет" else "переписка открыта: показана сохранённая",
                "сообщений" to saved?.items?.size,
            )
            com.example.vkweb.ui.SplashHold.ready = true
            if (saved == null || screen != Screen.Chat || currentPeer != peerId) return@loadHistory
            // Если свежие уже пришли, сохранённые только дополняют их более старыми сообщениями.
            if (serverHistory.isEmpty()) historyFromSaved = true
            serverHistory = HistoryMerge.messages(saved.items, serverHistory)
            rememberHistory(peerId, serverHistory)
            handler.removeCallbacks(renderHeldFirst)
            renderChat()
            // Без сети свежих не будет: прочитанной переписку делает открытие сохранённой (отметка — до сети, 4.13).
            if (!network.isOnline) markReadIfShown()
        }
    }

    /** Сеть появилась — обновляем то, что на экране, если оно сохранённое («нет сети» или «обновить не удалось»). */
    private fun onOnline() {
        if (!network.isOnline) return
        flushPendingReads()
        if (fellBackToSite) { reader.reload(); return }
        if (!banner.isVisible) {
            // Связь не пропадала (или пропала на миг): страница в порядке, неотправленное уходит сразу.
            outboxDriver.onOutboxChanged()
            return
        }
        showOffline(false)
        when (screen) {
            Screen.Dialogs -> loadDialogs()
            // Страница жила без сети: перезагружаем её, и неотправленное уйдёт, когда придёт свежая история.
            Screen.Chat -> currentPeer?.let {
                outboxDriver.onPageReloading()
                armTimeout()
                reader.loadUrl(ReaderChannel.peerUrl(it))
            }
        }
    }

    private fun showOffline(offline: Boolean) {
        if (offline) {
            pull.done()
            dialogsSkeleton.hide()
        }
        if (!offline) {
            setBanner(null)
            return
        }
        AppLog.i(LogTag.MESSAGES, "нет сети, показано сохранённое", "экран" to screen.name)
        handler.removeCallbacks(readTimeout)
        progress.hide()
        setBanner(R.string.offline_banner)
    }

    private fun setBanner(@StringRes text: Int?) {
        if (text != null) banner.setText(text)
        banner.isVisible = text != null
    }

    /** На экране сохранённое, свежего от страницы ещё не было. */
    private fun isShowingSaved(): Boolean = when (screen) {
        Screen.Dialogs -> dialogsFromSaved && allDialogs.isNotEmpty()
        Screen.Chat -> historyFromSaved && serverHistory.isNotEmpty()
    }

    /**
     * Нажатие на шапку переписки: страница собеседника (4.31) или выбор участника беседы. Беседа — полный список
     * участников от VK (4.32): запрашивается у страницы и запоминается на сеанс; не пришёл за [MEMBERS_WAIT_MS] или
     * VK его не отдал — список тех, кто писал в загруженном.
     */
    private fun openChatPeople() {
        val peer = currentPeer ?: return
        when (val target = ChatPeople.target(peer, chatAdapter.currentList)) {
            is ChatPeople.Target.Page -> links.openPage(target.path)
            else -> {
                if (!VkIds.isChat(peer)) {
                    Notice.show(this, R.string.chat_people_unknown)
                    return
                }
                chatMembers[peer]?.let { showChatPeople(peer, it); return }
                // Список уже запрошен: повторное нажатие, пока ждём ответ, не шлёт второй запрос (код ревью 4.34).
                if (membersWaiting == peer) return
                if (fellBackToSite || !network.isOnline) { showChatPeople(peer, null); return }
                membersWaiting = peer
                channel.members(reader, peer)
                handler.removeCallbacks(membersTimeout)
                handler.postDelayed(membersTimeout, MEMBERS_WAIT_MS)
            }
        }
    }

    /** Участники бесед за сеанс: список меняется редко, второй раз VK не спрашиваем. */
    private val chatMembers = HashMap<String, List<ChatPeople.Person>>()
    private var membersWaiting: String? = null
    private val membersTimeout = Runnable {
        val peer = membersWaiting ?: return@Runnable
        membersWaiting = null
        AppLog.i(LogTag.MESSAGES, "участники беседы не пришли вовремя — показаны писавшие")
        showChatPeople(peer, null)
    }

    private fun onMembers(payload: ReaderPayload.Members) {
        if (payload.ok && payload.people.isNotEmpty()) chatMembers[payload.peerId] = payload.people
        AppLog.i(LogTag.MESSAGES, "участники беседы", "получено" to payload.ok, "людей" to payload.people.size)
        if (membersWaiting != payload.peerId) return
        membersWaiting = null
        handler.removeCallbacks(membersTimeout)
        showChatPeople(payload.peerId, chatMembers[payload.peerId])
    }

    /** Список на выбор: полный от VK ([all]) — писавшие первыми; нет — только писавшие. Ушли из беседы — не показываем. */
    private fun showChatPeople(peer: String, all: List<ChatPeople.Person>?) {
        if (screen != Screen.Chat || currentPeer != peer || isFinishing) return
        val people = ChatPeople.merge(all, ChatPeople.members(chatAdapter.currentList))
        if (people.isEmpty()) {
            Notice.show(this, R.string.chat_people_unknown)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (all.isNullOrEmpty()) R.string.chat_people_title else R.string.chat_members_title)
            // 4.33: с аватарами, как в списке диалогов (ChatPeopleList).
            .setAdapter(ChatPeopleList(this, people)) { _, index -> links.openPage(people[index].path) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openDialog(dialog: DialogEntry) {
        // Показывается сама страница VK: своей переписки нет — открываем переписку на странице (код ревью 3.59).
        if (fellBackToSite) {
            if (VkIds.isPeer(dialog.peerId)) reader.loadUrl(ReaderChannel.peerUrl(dialog.peerId))
            return
        }
        // Недописанное в прежней переписке остаётся у неё (3.94).
        if (screen == Screen.Chat) saveDraft()
        refresher.onUserActivity()
        if (::voice.isInitialized) voice.release()
        screen = Screen.Chat
        // 4.57: последние переписки — ярлыками на значке приложения.
        com.example.vkweb.ui.AppShortcuts.chatOpened(this, dialog.peerId, dialog.title, dialog.avatar)
        historyFromSaved = false
        outboxDriver.onChatOpened()
        serverHistory = emptyList()
        markedReadUpTo = null
        attachPending = null
        sentDraft = null
        hideComposeExtras()
        handler.removeCallbacks(dropAttachPending)
        pager.reset()
        setReplyTarget(null)
        toolbar.title = dialog.title
        chatSubtitle.clear()
        chatAdapter.outReadCmid = 0
        setCanWrite(true)
        // null очищает список сразу; пустой список ListAdapter применил бы позже, в фоне, —
        // и на мгновение была бы видна чужая переписка, а сохранённое нового собеседника пропускалось.
        chatAdapter.submitList(null)
        currentPeer = dialog.peerId
        updateHeaderTap()
        restoreDraft(dialog.peerId)
        showChat()
        // Переписка, открытая в этом сеансе, — сразу из памяти: раньше она на мгновение пустела и подгружалась заново
        // (сначала с диска, потом со страницы — отчёт 3.60).
        recentHistory[dialog.peerId]?.let { cached ->
            serverHistory = cached
            historyFromSaved = true
            renderChat()
        }
        showSavedHistory(dialog.peerId)
        if (!network.isOnline) { showOffline(true); return }
        showOffline(false)
        armTimeout()
        navigateReader(ReaderChannel.peerPath(dialog.peerId), ReaderChannel.peerUrl(dialog.peerId))
    }

    private fun reloadCurrent() {
        if (!network.isOnline) { showOffline(true); return }
        refresher.onUserActivity()
        // Явное «Обновить» — единственный случай, когда список диалогов можно переставить на глазах.
        if (screen == Screen.Dialogs) resortDialogs = true
        armTimeout()
        reloadPage()
    }

    /** Перезагрузка страницы мессенджера: пока она грузится, поле ввода недоступно — отправлять нельзя. */
    private fun reloadPage() {
        outboxDriver.onPageReloading()
        reader.reload()
    }

    /** Свежее пришло: ждать больше нечего, плашка «обновить не удалось» не нужна. */
    private fun onFreshData() {
        handler.removeCallbacks(readTimeout)
        pull.done()
        dialogsSkeleton.hide()
        progress.hide()
        if (network.isOnline) setBanner(null)
    }

    private fun armTimeout() {
        progress.show()
        handler.removeCallbacks(readTimeout)
        handler.postDelayed(readTimeout, AppConfig.MESSAGES_READ_TIMEOUT_MS)
        loginChecks = 0
        handler.removeCallbacks(loginCheck)
        handler.postDelayed(loginCheck, LOGIN_CHECK_MS)
    }

    /** Сколько раз уже посмотрели, не ушла ли страница на вход. */
    private var loginChecks = 0

    /**
     * Страница ушла на вход VK (после выхода из аккаунта, истёкший вход): данных не будет — сразу страница входа с
     * подсказкой «нужно войти», а не ожидание срока и «не удалось прочитать» (4.0, по отчёту с телефона).
     */
    private val loginCheck = object : Runnable {
        override fun run() {
            if (fellBackToSite || isFinishing) return
            if (LoginPages.isLogin(reader.url)) {
                AppLog.i(LogTag.MESSAGES, "страница ушла на вход VK — показана страница входа")
                handler.removeCallbacks(readTimeout)
                fallbackToSite(R.string.lite_login_needed)
                return
            }
            if (++loginChecks < LOGIN_CHECK_TIMES) handler.postDelayed(this, LOGIN_CHECK_MS)
        }
    }

    // ------------------------------------------------------------ данные со страницы

    private fun onPayload(payload: ReaderPayload) {
        if (fellBackToSite) {
            // Ушли на сайт ради входа, и вход состоялся (пришёл список диалогов): обратно в свой интерфейс — а не
            // оставаться на сайте с его рекламой и окнами «откройте приложение» (4.0, по отчёту с телефона).
            if (fellBackForLogin && payload is ReaderPayload.Dialogs && payload.items.isNotEmpty() && !LoginPages.isLogin(reader.url)) {
                fellBackForLogin = false
                AppLog.i(LogTag.MESSAGES, "вход выполнен — возврат в свой интерфейс сообщений")
                recreate()
            }
            return
        }
        when (payload) {
            is ReaderPayload.Dialogs -> onDialogsPayload(payload)
            is ReaderPayload.History -> onHistoryPayload(payload)
            is ReaderPayload.Attach -> {
                if (payload.stage == "reset") {
                    // VK owns already selected/uploaded files. A new document clears its composer state.
                    outboxDriver.onPageReloading()
                    attachmentComposer.onPageStage("error", getString(R.string.attach_not_confirmed))
                    if (screen == Screen.Chat) attachmentComposer.onComposerResetting()
                    if (!readerCrashed) reader.loadUrl(
                        if (screen == Screen.Chat) currentPeer?.let { ReaderChannel.peerUrl(it) } ?: ReaderChannel.DIALOGS_URL
                        else ReaderChannel.DIALOGS_URL,
                    )
                } else attachmentComposer.onPageStage(payload.stage, payload.detail)
            }
            is ReaderPayload.Delivered -> {
                // Вложения в очереди не стоят: их итог — в onSent/onAttachmentSent.
                if (!attachmentComposer.isBusy) {
                    outboxDriver.onDelivered(payload.peerId, payload.text, payload.cmid)
                    refresher.refreshAfterSend()
                }
            }
            is ReaderPayload.Sent -> {
                AppLog.i(LogTag.MESSAGES, "кнопка «Отправить» на странице", "нажата" to payload.ok)
                onSent(payload.ok)
            }
            is ReaderPayload.Audio -> chatAudio.onPageAudio(payload)
            is ReaderPayload.AudioUrl -> chatAudio.onAudioUrl(payload)
            is ReaderPayload.AudioQueue -> chatAudio.onQueue(payload)
            is ReaderPayload.Replied -> onReplied(payload)
            is ReaderPayload.Stickers -> stickers.showRecent(payload)
            is ReaderPayload.StickerSuggest -> stickers.showSuggest(payload)
            is ReaderPayload.StickerSent -> {
                if (!payload.ok) {
                    AppLog.w(LogTag.MESSAGES, "стикер не отправлен", "причина" to payload.reason)
                    Notice.show(this, R.string.sticker_send_failed)
                } else {
                    AppLog.i(LogTag.MESSAGES, "стикер отправлен")
                }
            }
            is ReaderPayload.Forwarded -> forwarding.onForwarded(payload)
            is ReaderPayload.VoiceSent -> onVoiceSent(payload)
            is ReaderPayload.VoiceStage -> if (voiceSending && payload.operation == voiceOperation) {
                voiceSends.change { VoiceSendRules.sending(it, payload.operation, payload.documentId) }
                renderChat()
            }
            is ReaderPayload.Typing -> onTyping(payload)
            is ReaderPayload.ReactionAssets -> reactions.onAssets(payload)
            is ReaderPayload.Reacted -> reactions.onReacted(payload)
            is ReaderPayload.Edited -> editing.onEdited(payload)
            is ReaderPayload.ConvAction -> when (readMarks.onResult(payload.action, payload.peer, payload.ok)) {
                ReadMarks.Result.READ_OK, ReadMarks.Result.READ_FAILED -> onPendingReadResult(payload)
                ReadMarks.Result.UNREAD_OK, ReadMarks.Result.UNREAD_FAILED -> onScheduledUnreadResult(payload)
                ReadMarks.Result.OTHER -> conversations.onResult(payload)
            }
            is ReaderPayload.Deleted -> editing.onDeleted(payload)
            is ReaderPayload.Members -> onMembers(payload)
            is ReaderPayload.Self -> {
                if (com.example.vkweb.account.SelfProfile.save(this, payload.profile)) navigation.showProfile(payload.profile)
            }
            is ReaderPayload.Video -> videos.onSources(payload)
            ReaderPayload.OlderDone -> pager.onStartReached()
            is ReaderPayload.Unavailable -> {
                AppLog.w(LogTag.MESSAGES, "страница сообщений недоступна для чтения", "причина" to payload.reason)
                fallbackToSite(reasonText(payload.reason))
            }
        }
    }

    /** Список диалогов от страницы (до 4.74 — внутри [onPayload]). */
    private fun onDialogsPayload(payload: ReaderPayload.Dialogs) {
        // Своя страница для шапки меню — раз за открытие экрана, когда страница точно вошла (3.94).
        if (!selfRequested) {
            selfRequested = true
            channel.requestSelf(reader)
        }
        AppLog.d(
            LogTag.MESSAGES, "диалоги от страницы", "штук" to payload.items.size,
            "на экране" to allDialogs.size, "папок" to payload.folders.size.takeIf { it > 0 },
        )
        if (payload.folders.isNotEmpty() && payload.folders != folders) {
            folders = payload.folders
            savedFolders.update(folders)
        }
        val shown = allDialogs
        // Сохраняем настоящий порядок — он покажется при следующем открытии.
        val ordered = HistoryMerge.dialogs(shown, payload.items)
        if (!HistoryMerge.sameDialogs(ordered, shown)) refresher.onChange()
        FlowLog.merged("диалоги", "список", shown, ordered) { it.peerId }
        if (payload.items.isNotEmpty()) {
            store.saveDialogs(ordered)
            knownDialogs = ordered
        }
        if (screen == Screen.Dialogs) {
            // Пока смотрят, ничего не переставляем: строки обновляются на местах, новые — снизу.
            // Настоящий порядок — при первой свежей порции поверх сохранённого и после «Обновить»;
            // строка перед глазами при этом остаётся на месте экрана.
            // Страница присылает список частями (сначала 9, потом 22): порядок по первой неполной части ставил
            // свежие наверх, а остальные — как были, и список переставлялся дважды (отчёт 3.59). Настоящий
            // порядок — по полной порции.
            val fullPortion = payload.items.size >= minOf(shown.size, FULL_DIALOGS_PORTION)
            val reorder = resortDialogs || (dialogsFromSaved && fullPortion) || shown.isEmpty()
            val display = if (reorder) ordered else ListMerge.stable(shown, payload.items) { it.peerId }
            if (payload.items.isNotEmpty() && (reorder || !dialogsFromSaved)) {
                resortDialogs = false
                dialogsFromSaved = false
            }
            showDialogsData(display)
        } else if (twoPane && payload.items.isNotEmpty()) {
            // Две колонки, открыта переписка: список слева обновляется на местах, без перестановок (3.94).
            allDialogs = ListMerge.stable(allDialogs, payload.items) { it.peerId }
            renderDialogs()
        }
        flushPendingReads()
        prefetchUnread(payload.items)
    }

    /** История переписки от страницы — открытой или загруженной заранее (до 4.74 — внутри [onPayload]). */
    private fun onHistoryPayload(payload: ReaderPayload.History) {
        // 4.61: картинки реакций — раз за открытие экрана, когда страница уже говорит с API.
        reactions.onHistory()
        refreshSidePaneDialogs()
        AppLog.d(
            LogTag.MESSAGES, "переписка от страницы", "сообщений" to payload.items.size,
            // Свой список, а не список адаптера: тот обновляется позже (разница считается в фоне).
            "было" to serverHistory.size,
        )
        val forCurrent = screen == Screen.Chat && (payload.peerId.isEmpty() || payload.peerId == currentPeer)
        // Порции приходят частями (сначала бывает одно последнее сообщение): сливаем с показанным,
        // чтобы список не сжимался и не прыгал, и сохраняем уже объединённое.
        val merged = if (forCurrent) HistoryMerge.messages(serverHistory, payload.items) else payload.items
        if (forCurrent) FlowLog.merged("переписка", "история", serverHistory, merged) { it.id }
        if (forCurrent && merged != serverHistory) {
            refresher.onChange()
            // Те же сообщения, но с другими полями — «дыхание» списка: в журнале видно, что именно меняется.
            val changes = HistoryMerge.describeChanges(serverHistory, merged)
            if (changes.isNotEmpty()) AppLog.d(LogTag.MESSAGES, "переписка: изменились поля", "что" to changes)
        }
        val peer = payload.peerId.ifEmpty { currentPeer.orEmpty() }
        // На диск — только подтверждённые сервером; черновые (догадки по вёрстке) живут лишь на экране.
        val toSave = HistoryMerge.persistable(merged)
        if (peer.isNotEmpty() && toSave.isNotEmpty() && !forCurrent) {
            // Не открытая переписка (загружена заранее, 4.13): дописываем к сохранённой, а не заменяем её, и
            // название — из списка, а не с панели (там название открытой).
            saveOtherHistory(peer, payload.title, toSave)
        } else if (peer.isNotEmpty() && toSave.isNotEmpty()) {
            store.saveHistory(peer, HistoryMerge.chatTitle(payload.title, toolbar.title?.toString().orEmpty()), toSave)
        } else {
            AppLog.d(LogTag.MESSAGES, "переписка не сохранена", "собеседник известен" to peer.isNotEmpty(), "сообщений" to toSave.size)
        }
        if (forCurrent) {
            if (payload.items.isNotEmpty()) historyFromSaved = false
            serverHistory = merged
            rememberHistory(peer, merged)
            onFreshData()
            // Свежая история — повод проверить, дошло ли отправленное, и отправить следующее.
            outboxDriver.onFreshHistory()
            if (holdTinyFirstPortion(merged)) {
                AppLog.d(LogTag.MESSAGES, "переписка: первая порция слишком мала — ждём полную", "сообщений" to merged.size)
            } else {
                handler.removeCallbacks(renderHeldFirst)
                renderChat()
            }
            markReadIfShown()
            applyChatMeta(payload)
        }
    }

    /**
     * Нажатие на вложение. Музыка — как в обычном VK: играет прямо в переписке (вложение нажимает сама страница
     * мессенджера), без перехода в раздел музыки. Остальное открывается как раньше.
     */
    private fun onCardClicked(card: ChatCard) {
        // Видеосообщение («кружок»): файл mp4 уже в сообщении — играет здесь же, один раз.
        if (card.kind == "video_message") {
            val file = ReaderPayload.videoUrlOrEmpty(card.url)
            if (file.isNotEmpty()) {
                VideoPlayerActivity.open(this, file, "", "", card.title, loop = false, fromChat = true)
                return
            }
        }
        // Музыка играет в переписке (ChatAudio); не вышло — открывается как обычная ссылка.
        if (card.kind == "audio" && chatAudio.onTrackCard(card)) return
        links.openCard(card)
    }

    /**
     * Действия (нажатие на сообщение; долгое нажатие на вложение): «Ответить» и «Переслать» (в другую переписку VK) —
     * у подтверждённых сервером; «Поделиться» (в другой мессенджер: текст, ссылки вложений, фото картинками);
     * «Копировать текст»; ссылки из текста. Файл — поделиться самим файлом; фото — картинкой; остальное — ссылкой.
     */
    private fun onMenu(target: ChatAdapter.MenuTarget) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        val m = target.message
        if (m.id.startsWith("v:")) { showVoiceStatus(m.id.removePrefix("v:")); return }
        if (m.isConfirmed) {
            items += getString(R.string.reply_action) to { setReplyTarget(m) }
            items += getString(R.string.forward_action) to { forward(m) }
            // 4.61: реакция — если VK прислал картинки реакций.
            if (reactions.canPick && input.isEnabled) items += getString(R.string.reaction_action) to { reactions.pick(m) }
            // 3.94: изменить своё (сутки, без вложений) и удалить.
            val nowSec = System.currentTimeMillis() / 1000
            if (MessageEditing.canEdit(m, nowSec) && input.isEnabled) items += getString(R.string.edit_action) to { editing.start(m) }
            if (MessageEditing.canDelete(m)) items += getString(R.string.delete_action) to { editing.confirmDelete(m) }
        }
        when (target) {
            is ChatAdapter.MenuTarget.Photo -> items += getString(R.string.share_photo) to { share.sharePhotos(listOf(target.url)) }
            is ChatAdapter.MenuTarget.Card -> {
                // 4.79: трек — «Треки исполнителя» (по пункту на каждого: «A feat. B» — двое) в своём экране музыки.
                if (target.card.kind == "audio") {
                    MusicSearch.artistsOf(ChatQueue.splitTitle(target.card.title).first).forEach { artist ->
                        items += getString(R.string.music_artist_tracks, artist) to { links.openArtist(artist) }
                    }
                }
                val label = if (target.card.kind == "doc") R.string.share_file else R.string.share_link
                items += getString(label) to { share.shareCard(target.card) }
            }
            is ChatAdapter.MenuTarget.Message -> Unit
        }
        items += getString(R.string.share_message) to { share.shareMessage(m) }
        if (m.text.isNotBlank()) {
            items += getString(R.string.copy_text) to { copyText(m.text) }
            // 4.2: часть текста — в окне, где его можно выделить.
            items += getString(R.string.select_text) to { SelectableTextDialog.show(this, m.text) }
        }
        // Ссылки текста — и пунктами меню: так их открывают и с экранным чтецом (нажатие на текст теперь открывает меню).
        LinkFinder.find(m.text).take(MENU_LINKS_MAX).forEach { link ->
            val label = link.url.removePrefix("https://").removePrefix("http://").take(MENU_LINK_CHARS)
            items += getString(R.string.open_link, label) to { links.openLink(link.url) }
        }
        MaterialAlertDialogBuilder(this)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    // ------------------------------------------------------------ пересылка (с 3.94 — в ChatForwarding)

    /** Отдельно от [forwarding]: в onDestroy освобождается только созданная (а не создаётся ради освобождения). */
    private val forwardingLazy = lazy {
        ChatForwarding(this, object : ChatForwarding.Host {
            override val isOnline: Boolean get() = network.isOnline
            override val canAskPage: Boolean get() = channel.isSupported
            override val dialogs: List<DialogEntry> get() = allDialogs.ifEmpty { knownDialogs }
            override val chatTitle: String get() = toolbar.title?.toString().orEmpty()
            override val currentPeer: String? get() = this@MessagesActivity.currentPeer
            override val canRefresh: Boolean get() = !fellBackToSite
            override fun askSelfId(done: (String?) -> Unit) = channel.me(reader, done)
            override fun forward(targetPeer: String, cmid: Long) = channel.forward(reader, targetPeer, cmid)
            override fun requestHistory() = channel.requestHistory(reader)
        })
    }
    private val forwarding by forwardingLazy

    private fun forward(m: ChatMessage) {
        val cmid = cmidOf(m) ?: return
        forwarding.forward(cmid, m)
    }

    // ------------------------------------------------------------ видео и клипы в переписке

    /** Нажали видео в сообщении (ссылка для списка переписки — он создаётся раньше [videos]). */
    private fun onVideoClicked(video: ChatVideo): Unit = videos.onClicked(video)

    private fun copyText(text: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.copy_text), text))
        // С Android 13 система сама показывает, что скопировано.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            Notice.show(this, R.string.copied)
        }
    }

    // ------------------------------------------------------------ стикеры (3.73, 3.92; с 3.94 — в ChatStickers)

    private lateinit var stickers: ChatStickers

    private fun setupStickers() {
        stickers = ChatStickers(
            activity = this,
            input = input,
            suggestList = findViewById(R.id.sticker_suggest),
            host = object : ChatStickers.Host {
                override val canSend: Boolean get() = !fellBackToSite && screen == Screen.Chat
                override val hasFiles: Boolean get() = ::attachmentComposer.isInitialized && attachmentComposer.hasFiles
                override fun requestRecent() = channel.stickers(reader)
                override fun requestSuggest(text: String) = channel.stickerSuggest(reader, text)
                override fun send(stickerId: Long) = channel.sendSticker(reader, stickerId)
            },
        )
    }

    // ------------------------------------------------------------ данные беседы (3.72)

    // ------------------------------------------------------------ реакции (4.61)

    /** Реакции: картинки, нажатие, выбор (ChatReactions, 4.70). */
    private val reactions: ChatReactions by lazy {
        ChatReactions(
            this, chatAdapter,
            canSend = { network.isOnline && !fellBackToSite },
            send = { cmid, reactionId -> channel.react(reader, cmid, reactionId) },
            requestAssets = { channel.reactionAssets(reader) },
        )
    }

    /** Реакция под сообщением (ссылка для списка переписки — он создаётся раньше [reactions]). */
    private fun onReactionClicked(message: ChatMessage, reaction: ChatReaction): Unit = reactions.onClicked(message, reaction)

    // ------------------------------------------------------------ «печатает…» (4.60)

    /** Подпись под именем: присутствие и «печатает…» (ChatSubtitle, 4.67). */
    private val chatSubtitle: ChatSubtitle by lazy {
        ChatSubtitle(this, handler, show = { toolbar.subtitle = it }, chatShown = { screen == Screen.Chat })
    }

    private fun onTyping(payload: ReaderPayload.Typing) {
        if (screen != Screen.Chat || fellBackToSite || payload.peer.isEmpty() || payload.peer != currentPeer) return
        chatSubtitle.onTyping(VkIds.isChat(payload.peer), payload.names, payload.voice)
    }

    /** Подсказка поля ввода по умолчанию (на время «писать нельзя» она заменяется). */
    private var defaultInputHint: CharSequence? = null

    /**
     * Что VK присылает о беседе и раньше не использовалось (разбор дампа 3.70): в сети ли собеседник и когда был — под
     * именем; прочитал ли он свои сообщения — «✓✓» у них; можно ли писать — иначе поле ввода выключено.
     */
    private fun applyChatMeta(payload: ReaderPayload.History) {
        val peer = payload.peerId.toLongOrNull() ?: 0L
        val presence = if (VkIds.isUser(peer)) {
            com.example.vkweb.people.PeopleFormat.presence(
                com.example.vkweb.people.PeopleEntry(
                    id = peer, kind = com.example.vkweb.people.PeopleKind.FRIENDS, name = "",
                    online = payload.online, mobile = payload.mobile, lastSeen = payload.lastSeen,
                ),
                System.currentTimeMillis() / 1000, java.time.ZoneId.systemDefault(),
            ).ifEmpty { null }
        } else {
            null
        }
        // 4.60: пока печатают — подпись «печатает…»; присутствие вернётся, когда она уйдёт.
        chatSubtitle.setPresence(presence)
        if (payload.outReadCmid > 0) chatAdapter.outReadCmid = payload.outReadCmid
        setCanWrite(payload.canWrite)
    }

    // ------------------------------------------------------------ голосовые (4.59)

    private lateinit var voice: VoiceComposer
    /** Голосовое ушло странице и ждёт итога «voiceSent». */
    private var voiceSending = false
    private var voiceOperation = ""
    private val voiceSends by lazy { VoiceSendStore(this) }

    private fun rememberInterruptedVoice() {
        if (voiceSending && voiceOperation.isNotEmpty()) {
            voiceSends.change { VoiceSendRules.finish(it, voiceOperation, ok = false, uncertain = true) }
        }
    }
    private val voiceSendTimeout = Runnable {
        if (voiceSending) {
            attachmentSender.cancel()
            onVoiceSent(ReaderPayload.VoiceSent(false, "таймаут передачи голосового", uncertain = true))
        }
    }

    private fun setupVoice() {
        voice = VoiceComposer(
            activity = this,
            micButton = findViewById(R.id.compose_mic),
            // Тип — явно: иначе Kotlin выводит его из поля ввода (EditText) и для кнопок, и findViewById отдаёт кнопки
            // как EditText — сбой при открытии экрана (отчёт 4.61, ArrayStoreException).
            composeViews = listOf<View>(findViewById<View>(R.id.compose_attach), input, findViewById<View>(R.id.compose_sticker)),
            recordingPanel = findViewById(R.id.compose_recording),
            timeLabel = findViewById(R.id.compose_recording_time),
            handler = handler,
        ) { file, durationMs -> sendVoice(file, durationMs) }
        // Пустое поле и нет файлов — микрофон на месте «Отправить»; что-то написали — снова «Отправить».
        input.doAfterTextChanged { updateMicButton() }
        updateMicButton()
    }

    /** Микрофон вместо «Отправить»: Android 10+, поле пустое, файлов нет, своя переписка, писать можно. */
    private fun updateMicButton() {
        if (!::voice.isInitialized) return
        val mic = voice.isAvailable && input.isEnabled && input.text.isNullOrBlank() && !fellBackToSite &&
            !(::attachmentComposer.isInitialized && attachmentComposer.hasFiles) && !voiceSending
        if (voice.isRecording) return
        findViewById<View>(R.id.compose_mic).isVisible = mic
        findViewById<View>(R.id.compose_send).isVisible = !mic
        findViewById<View>(R.id.compose_attach).isEnabled = input.isEnabled && !voiceSending && !(::attachmentComposer.isInitialized && attachmentComposer.isBusy)
    }

    private fun sendVoice(file: java.io.File, durationMs: Long) {
        val peer = currentPeer
        if (peer == null || fellBackToSite || !network.isOnline) {
            file.delete()
            if (peer != null && !fellBackToSite) {
                voiceSends.change { it + VoiceSendEntry(java.util.UUID.randomUUID().toString(), peer, durationMs,
                    System.currentTimeMillis(), VoiceSendPhase.FAILED) }
                renderChat()
            }
            Notice.show(this, if (network.isOnline) R.string.voice_send_failed else R.string.pull_offline)
            return
        }
        voiceSending = true
        updateMicButton()
        Notice.show(this, R.string.voice_sending)
        AppLog.i(LogTag.MESSAGES, "голосовое: отправка", "мс" to durationMs)
        // Постоянный номер сообщения: повтор на стороне VK не задвоит.
        val randomId = (System.currentTimeMillis() % Int.MAX_VALUE).coerceAtLeast(1)
        handler.removeCallbacks(voiceSendTimeout)
        handler.postDelayed(voiceSendTimeout, 150_000L)
        voiceOperation = attachmentSender.sendVoice(file, peer, randomId) { reason ->
            onVoiceSent(ReaderPayload.VoiceSent(false, reason))
        }
        voiceSends.change { it + VoiceSendEntry(voiceOperation, peer, durationMs, System.currentTimeMillis()) }
        renderChat()
    }

    private fun onVoiceSent(payload: ReaderPayload.VoiceSent) {
        if (!voiceSending) return
        if (payload.operation.isNotEmpty() && payload.operation != voiceOperation) return
        voiceSends.change { VoiceSendRules.finish(it, voiceOperation, payload.ok, payload.uncertain, payload.documentId) }
        handler.removeCallbacks(voiceSendTimeout)
        voiceSending = false
        voiceOperation = ""
        updateMicButton()
        if (payload.ok) {
            AppLog.i(LogTag.MESSAGES, "голосовое отправлено")
            refresher.refreshAfterSend()
        } else {
            AppLog.w(LogTag.MESSAGES, "голосовое не отправлено", "причина" to payload.reason)
            Notice.show(this, if (payload.uncertain) R.string.voice_send_unconfirmed else R.string.voice_send_failed, long = true)
        }
        outboxDriver.onOutboxChanged()
        renderChat()
    }

    private fun voiceStatusCards(peer: String): List<ChatMessage> = voiceSends.all().filter { it.peer == peer }.map { entry ->
        val description = when (entry.phase) {
            VoiceSendPhase.UPLOADING -> R.string.voice_card_uploading
            VoiceSendPhase.SENDING -> R.string.voice_card_sending
            VoiceSendPhase.ACCEPTED -> R.string.voice_card_accepted
            VoiceSendPhase.REVIEW -> R.string.voice_card_review
            VoiceSendPhase.FAILED -> R.string.voice_card_failed
        }
        ChatMessage(id = "v:${entry.id}", out = true, time = "", dateSec = entry.createdAt / 1000,
            text = getString(R.string.voice_card_title, com.example.vkweb.ui.PlayerFormat.timeSec(entry.durationMs / 1000)) + "\n" + getString(description),
            status = when (entry.phase) {
                VoiceSendPhase.UPLOADING, VoiceSendPhase.SENDING -> MessageStatus.SENDING
                VoiceSendPhase.ACCEPTED -> MessageStatus.SENT
                VoiceSendPhase.REVIEW -> MessageStatus.UNCONFIRMED
                VoiceSendPhase.FAILED -> MessageStatus.FAILED
            }, deliveryNeedsReview = entry.phase == VoiceSendPhase.REVIEW)
    }

    private fun showVoiceStatus(id: String) {
        val entry = voiceSends.all().firstOrNull { it.id == id } ?: return
        val peer = currentPeer
        val body = when (entry.phase) {
            VoiceSendPhase.UPLOADING -> R.string.voice_card_uploading
            VoiceSendPhase.SENDING -> R.string.voice_card_sending
            VoiceSendPhase.ACCEPTED -> R.string.voice_card_accepted
            VoiceSendPhase.REVIEW -> R.string.voice_card_review
            VoiceSendPhase.FAILED -> R.string.voice_card_failed
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.voice_card_heading).setMessage(body)
            .setNegativeButton(R.string.close, null)
        if (!entry.active) {
            dialog.setPositiveButton(R.string.check_conversation) { _, _ -> if (currentPeer == peer) checkConversation() }
                .setNeutralButton(R.string.voice_card_hide) { _, _ ->
                    voiceSends.change { entries -> entries.filterNot { it.id == id } }
                    if (screen == Screen.Chat) renderChat()
                }
        }
        dialog.show()
    }

    private fun setCanWrite(allowed: Boolean) {
        if (defaultInputHint == null) defaultInputHint = input.hint
        if (input.isEnabled == allowed) return
        input.isEnabled = allowed
        input.hint = if (allowed) defaultInputHint else getString(R.string.compose_not_allowed)
        findViewById<View>(R.id.compose_send).isEnabled = allowed
        findViewById<View>(R.id.compose_attach).isEnabled = allowed
        // Стикер — тоже сообщение (код ревью 3.81: в беседе «только чтение» панель стикеров открывалась, отправка падала).
        findViewById<View>(R.id.compose_sticker).isEnabled = allowed
        findViewById<View>(R.id.compose_mic).isEnabled = allowed
        updateMicButton()
        if (!allowed) AppLog.i(LogTag.MESSAGES, "писать в беседу нельзя — поле ввода выключено")
    }

    // ------------------------------------------------------------ голосовые как музыка (3.71)

    // С 4.72 — в ChatAudio. Ссылки для списка переписки: он создаётся раньше [chatAudio].
    private fun onVoiceClicked(voice: ChatVoice): Unit = chatAudio.onVoiceClicked(voice)

    private fun onVoiceSeek(voice: ChatVoice, ms: Int): Unit = chatAudio.onVoiceSeek(voice, ms)

    private fun showDialogsData(items: List<DialogEntry>) {
        onFreshData()
        allDialogs = items
        renderDialogs()
        shareIntoChat.maybeShow()
    }

    // ------------------------------------------------------------ папки (3.79)

    /** Весь список диалогов (без фильтра папки): с ним сливаются свежие порции и сохранённое. */
    private var allDialogs: List<DialogEntry> = emptyList()
    private var folders: List<ChatFolder> = emptyList()

    /** Сохранённые папки (общее хранилище PrefsValue, 4.72): страница присылает их позже диалогов. */
    private val savedFolders: com.example.vkweb.offline.PrefsValue<List<ChatFolder>> by lazy {
        com.example.vkweb.offline.PrefsValue(this, FOLDERS_PREFS, FOLDERS_KEY, DialogFolders::decode, DialogFolders::encode)
    }

    /** Полоса папок над списком (с 4.4 — в DialogFolderChips). */
    private val folderChips by lazy {
        DialogFolderChips(findViewById(R.id.dialogs_folders_scroll), findViewById(R.id.dialogs_folders)) {
            renderDialogs()
            dialogsList.scrollToPosition(0)
        }
    }

    /** Полосу папок показывать: на экране список (или две колонки), а не запасной показ страницы. */
    private val folderChipsAllowed: Boolean get() = (screen == Screen.Dialogs || twoPane) && !fellBackToSite

    /** Силуэты строк при первой загрузке списка (4.56). */
    private val dialogsSkeleton by lazy { findViewById<com.example.vkweb.ui.SkeletonView>(R.id.dialogs_skeleton) }

    /** Силуэты вот-вот появятся (показ с задержкой): пустой экран пока не показываем. */
    private fun dialogsSkeletonPending(): Boolean = dialogsSkeleton.isPending

    /** Пустой список диалогов (4.51): внутри рамки messages_empty, которую MessagesPanes переносит в колонку. */
    private val emptyState by lazy { EmptyState(emptyView, emptyView) }

    private fun renderFolderChips(): ChatFolder? = folderChips.render(folders, allDialogs, folderChipsAllowed)

    // ------------------------------------------------------------ поиск по диалогам (3.94)

    private lateinit var search: DialogSearchBar
    private val dialogQuery: String get() = if (::search.isInitialized) search.query else ""

    private fun setupDialogSearch() {
        search = DialogSearchBar(
            toolbar.menu.findItem(R.id.action_search),
            onExpandChange = { open -> dialogSearchOpen = open; updateBackEnabled() },
        ) {
            renderDialogs()
            dialogsList.scrollToPosition(0)
        }
    }

    /** Поиск открыт — «Назад» сначала закрывает его. */
    private fun closeSearch(): Boolean = (::search.isInitialized && search.close()) || (::chatList.isInitialized && chatFind.close())

    private fun renderDialogs() {
        val folder = renderFolderChips()
        val list = DialogSearch.filter(DialogFolders.filter(allDialogs, folder), dialogQuery)
        ListUpdates.submitAnchored(dialogsList, dialogsAdapter, list, DialogEntry::peerId)
        // 4.56: силуэты строк — пока ждём первый список; пришло что угодно (или пусто по-настоящему) — убираются.
        if (allDialogs.isNotEmpty()) dialogsSkeleton.hide()
        // 4.51: пустой список — значок, заголовок и кнопка, которая что-то меняет.
        if (dialogsSkeleton.isShown || dialogsSkeletonPending()) {
            emptyState.hide()
        } else if (list.isEmpty() && (allDialogs.isNotEmpty() || !dialogsFromSaved)) {
            emptyState.show(
                when {
                    dialogQuery.isNotBlank() && allDialogs.isNotEmpty() -> EmptyState.Content(
                        R.string.dialogs_search_none, R.drawable.ic_search, R.string.dialogs_search_none_body,
                        action = EmptyState.Action(R.string.empty_reset_search) { closeSearch() },
                    )
                    folder != null && allDialogs.isNotEmpty() -> EmptyState.Content(
                        R.string.folder_empty_title, R.drawable.ic_section_messages,
                        action = EmptyState.Action(R.string.folder_show_all) { folderChips.selectAll() },
                    )
                    else -> EmptyState.Content(
                        R.string.dialogs_empty_title, R.drawable.ic_section_messages,
                        action = EmptyState.Action(R.string.empty_refresh) { reloadCurrent() },
                    )
                },
            )
        } else {
            emptyState.hide()
        }
        // 4.50: число непрочитанных на «Сообщениях» нижней панели.
        if (::navigation.isInitialized) {
            navigation.setUnreadDialogs(com.example.vkweb.sync.DialogNotifications.badgeCount(allDialogs, dialogsAdapter.notifyRules))
        }
    }

    /** До какого момента (elapsedRealtime) после отправки держать список у последнего сообщения; 0 - не держать. */
    private var followSentUntil = 0L

    /** Только показ: «свежие данные пришли» сообщает обработка данных страницы, а не любая перерисовка. */
    private fun showHistoryData(items: List<ChatMessage>) {
        val layout = chatList.layoutManager as LinearLayoutManager
        val previous = chatAdapter.currentList
        // 4.85: на экране уже ровно это (первый ответ страницы после сохранённой — «без изменений»): ничего не делать.
        // Раньше список всё равно «прокручивался к последнему», и вместе с перерисовкой отметок прочтения это давало
        // кадр со сдвигом всей переписки вниз на 100+ пикселей (запись 4.84) — рывок при открытии.
        if (items.isNotEmpty() && items == previous) return
        // Последнее сообщение видно целиком: список, прижатый к низу, и так останется внизу — прокрутка не нужна.
        val lastFullyShown = previous.isNotEmpty() && layout.findLastCompletelyVisibleItemPosition() == previous.size - 1
        val firstVisible = layout.findFirstVisibleItemPosition()
        val anchorId = previous.getOrNull(firstVisible)?.id
        val anchorOffset = (layout.findViewByPosition(firstVisible)?.top ?: 0) - chatList.paddingTop
        val wasAtBottom = previous.isEmpty() || layout.findLastVisibleItemPosition() >= previous.size - 2
        val newAtEnd = items.lastOrNull()?.id != previous.lastOrNull()?.id
        val prepended = previous.isNotEmpty() && items.firstOrNull()?.id != previous.firstOrNull()?.id

        // Обложки видео и клипов последних сообщений грузятся заранее (по безлимитной сети): карточка появляется с картинкой.
        ImageCache.get(this).prefetch(items.takeLast(PREFETCH_MESSAGES).flatMap { m -> m.videos.map { it.coverUrl } }.filter { it.isNotEmpty() })

        // Новые входящие снизу, пока человек читает выше: счётчик на кнопке «вниз».
        if (newAtEnd && !wasAtBottom && previous.isNotEmpty()) {
            val known = previous.mapTo(HashSet()) { it.id }
            scrollDown.addUnseen(items.count { !it.out && it.id !in known })
        }
        chatAdapter.submitList(items) {
            // Разделители дней зависят от соседей: у прежнего первого сообщения после догрузки сверху он мог исчезнуть.
            // Только тогда — пересчёт всех отступов на каждое обновление сдвигал переписку (4.0, «прыжки»).
            if (prepended) chatList.post { if (!chatList.isComputingLayout) chatList.invalidateItemDecorations() }
            scrollDown.update()
            // Ожидание старых снимается, только когда они правда пришли (список вырос сверху), а не на любое обновление
            // (статус отправки, новое сообщение внизу): иначе следующий запрос уходил раньше, чем страница отвечала.
            pager.onListUpdated(grewAtTop = items.firstOrNull()?.id != previous.firstOrNull()?.id) { action -> chatList.post { action() } }
            if (items.isEmpty()) return@submitList
            val anchorIndex = anchorId?.let { id -> items.indexOfFirst { it.id == id } } ?: -1
            val idle = chatList.scrollState == RecyclerView.SCROLL_STATE_IDLE
            // Правило — ChatScrollPolicy (4.67): там же, почему каждый случай таков.
            val following = followSentUntil > android.os.SystemClock.elapsedRealtime()
            val action = ChatScrollPolicy.decide(
                previousEmpty = previous.isEmpty(), newAtEnd = newAtEnd, wasAtBottom = wasAtBottom, idle = idle,
                anchorFound = anchorIndex >= 0, prepended = prepended, followSent = following,
            )
            // 4.13: как список повёл себя при обновлении — по журналу видно, откуда «прыжок» (только когда что-то
            // добавилось или ушло: обновления на местах не пишутся).
            if (previous.size != items.size || prepended || action == ChatScrollPolicy.Action.FOLLOW_SENT) {
                AppLog.d(LogTag.MESSAGES, "переписка: прокрутка", "как" to action.label, "было" to previous.size, "стало" to items.size)
            }
            when (action) {
                ChatScrollPolicy.Action.FIRST_PAGE -> chatList.scrollToPosition(items.size - 1)
                // После раскладки: высота нового пузыря уже известна, последнее встаёт целиком над полем ввода
                ChatScrollPolicy.Action.FOLLOW_SENT -> chatList.post {
                    if (chatList.scrollState == RecyclerView.SCROLL_STATE_IDLE && chatAdapter.itemCount > 0) {
                        chatList.scrollToPosition(chatAdapter.itemCount - 1)
                    }
                }
                ChatScrollPolicy.Action.STAY_AT_BOTTOM -> if (!lastFullyShown) chatList.scrollToPosition(items.size - 1)
                ChatScrollPolicy.Action.NEW_AT_BOTTOM -> if (ChatScrollPolicy.smooth(items.size - previous.size)) {
                    chatList.smoothScrollToPosition(items.size - 1)
                } else {
                    chatList.scrollToPosition(items.size - 1)
                }
                ChatScrollPolicy.Action.KEEP_ROW_AFTER_OLDER -> {
                    val nowFirst = layout.findFirstVisibleItemPosition()
                    if (items.getOrNull(nowFirst)?.id != anchorId) layout.scrollToPositionWithOffset(anchorIndex, anchorOffset)
                }
                ChatScrollPolicy.Action.KEEP_ROW -> chatList.post {
                    val now = (layout.findViewByPosition(anchorIndex)?.top ?: return@post) - chatList.paddingTop
                    if (now != anchorOffset && chatList.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
                        layout.scrollToPositionWithOffset(anchorIndex, anchorOffset)
                    }
                }
                ChatScrollPolicy.Action.NONE -> Unit
            }
        }
    }

    private fun nearTop(list: RecyclerView): Boolean {
        val first = (list.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: return false
        return pager.isNearTop(first)
    }

    // ------------------------------------------------------------ отправка и очередь неотправленных

    /**
     * Сообщение сразу попадает в очередь и показывается в переписке с пометкой; уходит, когда есть сеть и страница
     * готова ([ChatOutboxDriver]). Так нажатие «Отправить» не теряется ни без сети, ни пока страница ещё грузится.
     */
    private fun sendCurrent() {
        val value = input.text?.toString()?.trim().orEmpty()
        if (currentPeer == null || attachmentComposer.isBusy) return
        if (voiceSending && attachmentComposer.hasFiles) { Notice.show(this, R.string.voice_sending); return }
        if (attachmentComposer.hasFiles) {
            // Вложения уходят через композер страницы, а ответ — запросом: вместе их не отправить. Говорим об этом,
            // а не оставляем полосу «Ответ», будто ответ ушёл (код ревью 3.51).
            if (replyTarget != null) {
                Notice.show(this, R.string.reply_with_files, long = true)
                setReplyTarget(null)
            }
            attachmentComposer.send(value)
            return
        }
        if (value.isEmpty()) return
        if (editing.isEditing) {
            editing.send(value)
            return
        }
        val target = replyTarget
        if (target != null) {
            sendReply(value, target)
            return
        }
        // Отправили — ждём ответа: обновлять снова часто.
        refresher.onUserActivity()
        if (queueCommitPending) return
        val submittedPeer = currentPeer
        queueCommitPending = true
        // 5.00.12: после отправки список едет к последнему, пока сообщение не встанет на место
        followSentUntil = android.os.SystemClock.elapsedRealtime() + FOLLOW_SENT_MS
        if (!outboxDriver.submit(value, onCommitted = { ok ->
            queueCommitPending = false
            if (!isDestroyed) {
                if (ok && currentPeer == submittedPeer && input.text?.toString()?.trim() == value) input.setText("")
                if (!ok) Notice.show(this, R.string.outbox_save_failed, long = true)
            }
        })) {
            queueCommitPending = false
            Notice.show(this, R.string.outbox_full)
            return
        }
    }

    /**
     * Отложенная отправка (4.17): текст уходит в очередь со временем отправки. Только текст: вложения и ответ на
     * сообщение уходят иначе (композером страницы и запросом), в очереди их нет.
     */
    private fun scheduleCurrent() {
        val value = input.text?.toString()?.trim().orEmpty()
        if (currentPeer == null) return
        if (attachmentComposer.hasFiles || attachmentComposer.isBusy || editing.isEditing || replyTarget != null) {
            Notice.show(this, R.string.schedule_text_only, long = true)
            return
        }
        if (value.isEmpty()) {
            Notice.show(this, R.string.schedule_need_text)
            return
        }
        val scheduledPeer = currentPeer
        outboxActions.schedule(value, toolbar.title?.toString().orEmpty()) {
            if (currentPeer == scheduledPeer && input.text?.toString()?.trim() == value) input.setText("")
        }
    }

    /**
     * Ответ на сообщение уходит запросом от имени страницы (в композере страницы так не ответить), без очереди
     * неотправленных: без сети — подсказка, текст остаётся в поле.
     */
    private fun sendReply(value: String, target: ChatMessage) {
        val cmid = cmidOf(target) ?: return
        if (!network.isOnline) {
            Notice.show(this, R.string.reply_offline)
            return
        }
        AppLog.i(LogTag.MESSAGES, "ответ на сообщение", "номер" to cmid)
        refresher.onUserActivity()
        sentDraft = value
        channel.reply(reader, value, cmid)
    }

    private fun onReplied(payload: ReaderPayload.Replied) {
        if (!payload.ok) {
            AppLog.w(LogTag.MESSAGES, "ответ не отправлен", "причина" to payload.reason)
            Notice.show(this, getString(R.string.reply_failed, payload.reason), long = true)
            return
        }
        clearSentDraft()
        setReplyTarget(null)
        refresher.refreshAfterSend()
    }

    /** Подтверждение пришло поздно, а человек уже пишет другое (или открыл другую переписку) — его текст не стираем. */
    private fun clearSentDraft() {
        val sent = sentDraft
        sentDraft = null
        if (sent == null || input.text?.toString()?.trim() == sent.trim()) input.setText("")
    }

    /** Номер сообщения в беседе («c123» → 123); у черновых и неотправленных его нет. */
    private fun cmidOf(m: ChatMessage): Long? = m.id.takeIf { m.isConfirmed }?.drop(1)?.toLongOrNull()

    // ------------------------------------------------------------ поиск в переписке (4.0; с 4.5 — в ChatFind)

    // Поле в панели (4.10): создаётся сразу, чтобы пункт меню раскрывался в поле с первого нажатия.
    private val chatFind by lazy {
        ChatFind(
            this, toolbar.menu.findItem(R.id.action_find), chatList,
            onExpandChange = { open -> chatFindOpen = open; updateBackEnabled() },
        ) { chatAdapter.currentList }
    }

    private fun setFindVisible(visible: Boolean) {
        chatFind.isVisible = visible
        // 4.47: «Уведомления» в меню — только в открытой переписке (там же, где поиск по ней).
        toolbar.menu.findItem(R.id.action_chat_notify)?.isVisible = visible
    }

    /** Уведомления открытой переписки на этом телефоне (4.47). */
    private fun openChatNotify() {
        val peer = currentPeer ?: return
        val dialog = allDialogs.firstOrNull { it.peerId == peer }
            ?: DialogEntry(peerId = peer, title = toolbar.title?.toString().orEmpty(), snippet = "", time = "", unread = false, online = false)
        conversations.showNotify(dialog)
    }

    /** Свои настройки уведомлений диалогов — в список (приглушённый счётчик у тех, кто «не зовёт»). */
    private fun applyNotifyRules() {
        dialogsAdapter.notifyRules = DialogNotifyStore(this).all()
        if (::navigation.isInitialized) {
            navigation.setUnreadDialogs(com.example.vkweb.sync.DialogNotifications.badgeCount(allDialogs, dialogsAdapter.notifyRules))
        }
    }

    // ------------------------------------------------------------ действия с беседой (4.0; с 4.2 — в ConversationActions)

    private val conversations by lazy {
        ConversationActions(this, object : ConversationActions.Host {
            override val isAvailable: Boolean get() = !fellBackToSite && channel.isSupported
            override val isOnline: Boolean get() = network.isOnline
            override fun send(action: String, peer: String) = channel.convAction(reader, action, peer)
            override var dialogs: List<DialogEntry>
                get() = allDialogs
                set(value) { allDialogs = value }
            override fun onRemoved(peer: String) {
                drafts.put(peer, "")
                recentHistory.remove(peer)
            }
            override fun onDialogsChanged() {
                store.saveDialogs(allDialogs)
                renderDialogs()
            }
            override fun onNotifyRulesChanged() = applyNotifyRules()
        })
    }

    // ------------------------------------------------------------ изменение и удаление (3.94; с 4.3 — в ChatEditing)

    private val editing by lazy {
        ChatEditing(this, object : ChatEditing.Host {
            override val input: EditText get() = this@MessagesActivity.input
            override val isOnline: Boolean get() = network.isOnline
            override val currentPeer: String? get() = this@MessagesActivity.currentPeer
            override val chatShown: Boolean get() = screen == Screen.Chat
            override val history: List<ChatMessage> get() = serverHistory
            override val replyTarget: ChatMessage? get() = this@MessagesActivity.replyTarget
            override fun clearReply() = setReplyTarget(null)
            override fun showBar(title: CharSequence, text: CharSequence) {
                replyBar.isVisible = true
                findViewById<TextView>(R.id.compose_reply_sender).text = title
                findViewById<TextView>(R.id.compose_reply_text).text = text
            }
            override fun hideBar() {
                replyBar.isVisible = false
            }
            override fun saveDraft() = this@MessagesActivity.saveDraft()
            override fun restoreDraft() = this@MessagesActivity.restoreDraft(this@MessagesActivity.currentPeer.orEmpty())
            override fun sendEdit(cmid: Long, text: String) = channel.editMessage(reader, cmid, text)
            override fun sendDelete(cmid: Long, forAll: Boolean) = channel.deleteMessage(reader, cmid, forAll)
            override fun historyChanged(items: List<ChatMessage>) = updateShownHistory(items)
        })
    }

    /** Изменили или удалили сообщение: история на экране, в памяти сеанса и на диске — сразу, без ожидания страницы. */
    private fun updateShownHistory(items: List<ChatMessage>) {
        val peer = currentPeer ?: return
        if (items == serverHistory) return
        serverHistory = items
        rememberHistory(peer, items)
        val toSave = HistoryMerge.persistable(items)
        if (toSave.isNotEmpty()) store.saveHistory(peer, toolbar.title?.toString().orEmpty(), toSave)
        renderChat()
    }

    private fun setReplyTarget(m: ChatMessage?) {
        // Ответ и изменение — не вместе: полоса над полем одна.
        if (editing.isEditing) editing.cancel()
        replyTarget = m
        if (!::replyBar.isInitialized) return
        replyBar.isVisible = m != null
        if (m == null) return
        // В личной переписке у сообщений нет имени автора: чужое — собеседник (заголовок экрана), своё — «вы».
        val who = when {
            m.out -> getString(R.string.reply_you)
            m.sender.isNotEmpty() -> m.sender
            else -> toolbar.title?.toString().orEmpty()
        }
        findViewById<TextView>(R.id.compose_reply_sender).text = getString(R.string.reply_to, who)
        findViewById<TextView>(R.id.compose_reply_text).text = MessageShare.textOf(m).replace('\n', ' ').ifEmpty { "…" }
        input.requestFocus()
    }

    /** Перейти к сообщению, на которое ответили (ChatHistoryPager; ссылка для списка переписки — он создаётся раньше). */
    private fun jumpTo(reply: ChatReply): Unit = pager.jumpTo(reply)

    /** Ответ страницы на нажатие «Отправить» (решает [ChatOutboxDriver.onSent]); историю перечитываем вскоре после него. */
    private fun onSent(ok: Boolean) {
        // «Отправить» нажато страницей после загрузки вложений: у такого сообщения нет записи в очереди неотправленных.
        if (attachmentComposer.isBusy) {
            attachmentComposer.onSent(ok)
            return
        }
        outboxDriver.onSent(ok)
        if (ok) refresher.refreshAfterSend()
    }

    /** Очередь изменилась (сообщение добавлено, отправлено, доставлено, удалено): обновить всё, что её показывает. */
    private fun onOutboxChanged() {
        dialogsAdapter.pendingPeers = OutboxLogic.pendingPeers(outbox.items())
        if (screen == Screen.Chat) renderChat()
        outboxDriver.onOutboxChanged()
    }

    /** Показать переписку: сообщения сервера и ниже неотправленные с пометкой. */
    private fun renderChat() {
        val peer = currentPeer ?: return
        voiceSends.change { VoiceSendRules.reconcileHistory(it, peer, serverHistory) }
        attachPending?.let { if (AttachmentPending.arrived(serverHistory, attachMaxCmid)) {
            AppLog.i(LogTag.MESSAGES, "сообщение с файлами пришло — показано как обычное")
            attachPending = null
            attachmentComposer.onHistoryArrived()
            handler.removeCallbacks(dropAttachPending)
        } }
        showHistoryData(OutboxLogic.visibleMessages(outboxDriver.withAccepted(serverHistory, peer), outbox.items(), peer) + listOfNotNull(attachPending) + voiceStatusCards(peer))
    }

    /**
     * Переписка на экране — отмечаем её прочитанной (сайт и приложения VK сбрасывают счётчик): когда пришло новое
     * входящее и экран виден. Страница скрыта, сама она этого не делала.
     */
    private fun markReadIfShown() {
        if (screen != Screen.Chat || fellBackToSite || !visible) return
        val lastIncoming = serverHistory.lastOrNull { !it.out && it.isConfirmed }?.id ?: return
        if (lastIncoming == markedReadUpTo) return
        markedReadUpTo = lastIncoming
        val peer = currentPeer ?: return
        if (!network.isOnline) {
            // 4.13: без сети отметка уходит позже — когда сеть вернётся (переписка могла быть загружена заранее).
            pendingReads.add(peer)
            AppLog.i(LogTag.MESSAGES, "переписка открыта без сети — отметка о прочтении отложена до сети")
            allDialogs = ConversationActions.apply(allDialogs, ConversationActions.READ, peer)
            if (allDialogs.isNotEmpty()) store.saveDialogs(allDialogs)
            return
        }
        AppLog.d(LogTag.MESSAGES, "переписка отмечена прочитанной")
        channel.markRead(reader)
        pendingReads.remove(peer)
    }

    // ------------------------------------------------------------ заранее и отложенное прочтение (4.13)

    private val pendingReads by lazy { PendingReads(this) }

    /** Отметки, отправленные странице и ждущие ответа «convaction» — общее с фоновой работой (ReadMarks, 4.67). */
    private val readMarks: ReadMarks by lazy { ReadMarks(pendingReads) }

    /**
     * Отложенное ушло, пока открыт этот экран (4.18): переписка — непрочитанной, на сервере VK. Ушедшее без человека
     * сообщение так видно и в списке, и на сайте VK; прочитанной она станет, когда её откроют.
     */
    private val scheduledDelivered: (List<com.example.vkweb.outbox.OutboxItem>) -> Unit = { items ->
        if (!fellBackToSite && channel.isSupported) {
            readMarks.takeUnreads(items.map { it.peerId }).forEach { peer ->
                AppLog.i(LogTag.MESSAGES, "отложенное ушло — переписка отмечается непрочитанной")
                channel.convAction(reader, ConversationActions.UNREAD, peer)
            }
        }
    }

    private fun onScheduledUnreadResult(payload: ReaderPayload.ConvAction) {
        if (!payload.ok) {
            AppLog.w(LogTag.MESSAGES, "переписка с отложенным не отмечена непрочитанной", "причина" to payload.reason)
            return
        }
        AppLog.i(LogTag.MESSAGES, "переписка с отложенным отмечена непрочитанной")
        allDialogs = ConversationActions.apply(allDialogs, ConversationActions.UNREAD, payload.peer)
        if (allDialogs.isNotEmpty()) store.saveDialogs(allDialogs)
        renderDialogs()
        // Открытая переписка, где оно ушло, не отмечается прочитанной сразу снова: только по новому входящему.
    }

    /** Что уже загружено заранее в этом сеансе: переписка и её последнее сообщение в списке. */
    private val prefetchedKeys = HashSet<String>()

    /** Страница отвечает и сеть есть: отправить отложенные отметки о прочтении. */
    private fun flushPendingReads() {
        if (!network.isOnline || fellBackToSite || !channel.isSupported) return
        val peers = readMarks.takeReads()
        if (peers.isEmpty()) return
        AppLog.i(LogTag.MESSAGES, "отложенные отметки о прочтении отправлены", "переписок" to peers.size)
        peers.forEach { peer -> channel.convAction(reader, ConversationActions.READ, peer) }
    }

    private fun onPendingReadResult(payload: ReaderPayload.ConvAction) {
        // Принятая снята из отложенных в ReadMarks.onResult.
        if (payload.ok) {
            AppLog.i(LogTag.MESSAGES, "отложенная отметка о прочтении принята")
        } else {
            // Не вышло — останется до следующего ответа страницы (повтор без всплывающих сообщений).
            AppLog.w(LogTag.MESSAGES, "отложенная отметка о прочтении не принята", "причина" to payload.reason)
        }
    }

    /**
     * Непрочитанные переписки из списка — заранее, без отметки о прочтении (настройка): потом их можно открыть сразу и
     * без сети, а прочитанными они станут, когда их откроют. Каждая — один раз на её последнее сообщение.
     */
    private fun prefetchUnread(items: List<DialogEntry>) {
        if (!settings.prefetchUnread || !network.isOnline || fellBackToSite || !channel.isSupported) return
        val peers = items.asSequence()
            .filter { it.unread && it.peerId != currentPeer }
            .filter { (it.peerId + "|" + it.time + "|" + it.snippet.hashCode()) !in prefetchedKeys }
            .take(PREFETCH_PER_PORTION)
            .toList()
        if (peers.isEmpty()) return
        peers.forEach { prefetchedKeys += it.peerId + "|" + it.time + "|" + it.snippet.hashCode() }
        AppLog.i(LogTag.MESSAGES, "непрочитанные переписки загружаются заранее (без отметки о прочтении)", "штук" to peers.size)
        channel.prefetch(reader, peers.map { it.peerId })
    }

    /** История не открытой переписки: дописать к сохранённой (старое не теряется), название — из списка диалогов. */
    private fun saveOtherHistory(peer: String, pageTitle: String, items: List<ChatMessage>) {
        val listTitle = knownDialogs.firstOrNull { it.peerId == peer }?.title ?: allDialogs.firstOrNull { it.peerId == peer }?.title.orEmpty()
        store.loadHistory(peer) { saved ->
            val all = HistoryMerge.persistable(HistoryMerge.messages(saved?.items ?: emptyList(), items))
            store.saveHistory(peer, HistoryMerge.chatTitle(pageTitle, listTitle.ifEmpty { saved?.title.orEmpty() }), all)
            rememberHistory(peer, all)
        }
    }

    /** Первая порция открытой переписки не показана сразу: ждём полную, не дольше [TINY_FIRST_WAIT_MS]. */
    private var firstPortionHeld = false
    private val renderHeldFirst = Runnable {
        if (screen != Screen.Chat) return@Runnable
        AppLog.d(LogTag.MESSAGES, "переписка: полная порция не пришла — показана первая")
        renderChat()
    }

    /**
     * Страница присылает переписку частями: сначала одно-два последних сообщения, через полсекунды — 30. Если на экране
     * ещё ничего нет (сохранённой не было), первая крошечная порция не показывается сразу: иначе одно сообщение, а за ним
     * целая переписка — заметный прыжок при входе (отчёт 4.12).
     */
    private fun holdTinyFirstPortion(merged: List<ChatMessage>): Boolean {
        if (firstPortionHeld || chatAdapter.currentList.isNotEmpty() || merged.size >= TINY_FIRST_PORTION) return false
        firstPortionHeld = true
        handler.removeCallbacks(renderHeldFirst)
        handler.postDelayed(renderHeldFirst, TINY_FIRST_WAIT_MS)
        return true
    }

    /** Меню неотправленного и отложенная отправка (OutboxActions, 4.73). */
    private val outboxActions: OutboxActions by lazy { OutboxActions(this, { outbox }, { outboxDriver }, ::checkConversation) }

    private fun checkConversation() {
        if (!network.isOnline) { Notice.show(this, R.string.pull_offline); return }
        refresher.refreshAfterSend()
        channel.requestHistory(reader)
    }

    /** Нажали на неотправленное (ссылка для списка переписки — он создаётся раньше [outboxActions]). */
    private fun onPendingClicked(message: ChatMessage) {
        if (message.id.startsWith("v:")) showVoiceStatus(message.id.removePrefix("v:"))
        else outboxActions.onPendingClicked(message)
    }

    // ------------------------------------------------------------ переключение экранов

    private fun showDialogs() {
        AppVisibility.openChatPeer = null
        if (screen == Screen.Chat) saveDraft()
        screen = Screen.Dialogs
        // Ушли из переписки, пока ждали файлы видео, — видео уже не нужно.
        videos.cancel()
        toolbar.setTitle(R.string.tab_messages)
        chatSubtitle.clear()
        if (::navigation.isInitialized) navigation.controlsToolbar = true
        // Переписка → список — растворение (4.83).
        if (!twoPane && chatList.isVisible) switchScreens()
        updateBottomBar()
        dialogsList.isVisible = true
        renderFolderChips()
        chatList.isVisible = false
        composeBar.isVisible = false
        setFindVisible(false)
        if (::panes.isInitialized) {
            chatPlaceholder.isVisible = twoPane
            dialogsAdapter.selectedPeer = null
            // Название раздела уже в заголовке колонки слева.
            if (twoPane) toolbar.title = null
        }
        if (::search.isInitialized) search.isVisible = !fellBackToSite
        scrollDown.reset()
        hideComposeExtras()
        // Тема системы сменилась, пока шла запись голосового или отправка файлов, — теперь можно.
        applyPendingThemeChange()
    }

    /** Полоса «Ответ» и выбранные файлы — часть поля ввода: вне переписки их не видно (код ревью 3.59). */
    private fun hideComposeExtras() {
        rememberInterruptedVoice()
        handler.removeCallbacks(voiceSendTimeout)
        voiceSending = false
        voiceOperation = ""
        if (::stickers.isInitialized) stickers.hideSuggest()
        // Страница сейчас уйдёт на другой адрес — музыка, которую играла она, оборвётся.
        if (::audioBar.isInitialized && audioBar.isShown && !audioBar.isNative) audioBar.hide()
        setReplyTarget(null)
        if (::attachmentComposer.isInitialized) attachmentComposer.reset()
        attachPending = null
        handler.removeCallbacks(dropAttachPending)
    }

    private fun showChat() {
        AppVisibility.openChatPeer = currentPeer
        firstPortionHeld = false
        handler.removeCallbacks(renderHeldFirst)
        scrollDown.reset()
        setFindVisible(true)
        chatFind.reset()
        chatPlaceholder.isVisible = false
        if (twoPane) {
            // Две колонки: список слева остаётся (и поиск по нему), открытая переписка в нём выделена.
            dialogsAdapter.selectedPeer = currentPeer
        } else {
            // Поиск — по списку диалогов; в переписке его нет (открытый закрывается, список остаётся целым).
            closeSearch()
            if (::search.isInitialized) search.isVisible = false
            dialogsList.isVisible = false
            findViewById<View>(R.id.dialogs_folders_scroll).isVisible = false
            emptyView.isVisible = false
        }
        if (!twoPane) dialogsSkeleton.hide()
        // Список → переписка — растворение без сдвига (4.83; в двух колонках оба видны, менять нечего).
        if (!twoPane) switchScreens()
        chatList.isVisible = true
        composeBar.isVisible = true
        navigation.controlsToolbar = false
        updateBottomBar()
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationContentDescription(R.string.navigate_back)
        toolbar.setNavigationOnClickListener { onBack() }
    }

    /** Из переписки — к списку диалогов; если экран открыт ради одной переписки или показан сайт — закрыть. */
    private fun onBack() {
        if (screen != Screen.Chat || openedForPeer || fellBackToSite) {
            finish()
            return
        }
        refresher.onUserActivity()
        showDialogs()
        loadDialogs()
    }

    // ------------------------------------------------------------ запасной показ страницы

    /** Показывает саму страницу VK во весь экран. Необратимо до перезахода. */
    private fun fallbackToSite(messageRes: Int?) {
        if (::voice.isInitialized) {
            voice.release()
            findViewById<View>(R.id.compose_mic).isVisible = false
            findViewById<View>(R.id.compose_send).isVisible = true
        }
        dialogsSkeleton.hide()
        fellBackForLogin = messageRes == R.string.lite_login_needed || LoginPages.isLogin(reader.url)
        findViewById<View>(R.id.messages_web_mode).isVisible = true
        findViewById<TextView>(R.id.messages_web_mode_text).setText(if (fellBackForLogin) R.string.web_mode_login else R.string.web_mode_active)
        findViewById<TextView>(R.id.messages_web_mode_retry).setText(if (fellBackForLogin) R.string.web_mode_sign_in else R.string.web_mode_retry)
        if (fellBackToSite) return
        AppLog.w(
            LogTag.MESSAGES, "показана страница VK вместо своего интерфейса",
            "сообщение" to messageRes?.let { getString(it) },
        )
        fellBackToSite = true
        updateBackEnabled()
        handler.removeCallbacks(readTimeout)
        progress.hide()
        // Страница во весь экран: колонки списка нет (список возвращается на место и скрывается ниже).
        if (::panes.isInitialized) {
            panes.set(false)
            chatPlaceholder.isVisible = false
        }

        closeSearch()
        if (::search.isInitialized) search.isVisible = false
        setFindVisible(false)
        scrollDown.reset()
        findViewById<View>(R.id.dialogs_folders_scroll).isVisible = false
        dialogsList.isVisible = false
        chatList.isVisible = false
        composeBar.isVisible = false
        emptyView.isVisible = false
        hideComposeExtras()
        reader.isVisible = true
        toolbar.setTitle(R.string.tab_messages)
        // Место слева снова у меню («назад» из переписки здесь не нужен).
        navigation.controlsToolbar = true
        updateBottomBar()

        messageRes?.let { Notice.show(this, it) }
        // На странице входа — остаёмся на ней: перезагрузка сбросила бы начатый вход (4.0).
        if (!ReaderChannel.isDialogsUrl(reader.url) && !ReaderChannel.isHistoryUrl(reader.url) && !LoginPages.isLogin(reader.url)) {
            reader.loadUrl(ReaderChannel.DIALOGS_URL)
        }
    }

    // ------------------------------------------------------------ возврат после входа (4.19)

    /** Возврат в свой интерфейс после входа на странице VK (LoginReturn, 4.72). */
    private val loginReturn: LoginReturn by lazy {
        LoginReturn(
            handler,
            onSite = { fellBackToSite },
            waitingForLogin = { fellBackForLogin },
            setPageVisible = { reader.isVisible = it },
            showProgress = { if (it) progress.show() else progress.hide() },
            onLoggedIn = {
                fellBackForLogin = false
                recreate()
            },
        )
    }

    private fun onReaderPageStarted(url: String?): Unit = loginReturn.onPageStarted(url)

    private fun onReaderPageFinished(url: String?) {
        loginReturn.onPageFinished(url)
        if (::attachmentComposer.isInitialized) attachmentComposer.onComposerReady()
    }

    private fun reasonText(reason: String): Int = when (reason) {
        "login" -> R.string.lite_login_needed
        else -> R.string.lite_read_failed
    }

    companion object {
        /** Сколько держать страницу VK скрытой после входа, если свой интерфейс не открылся (4.19). */
        /** Непрочитанных переписок, загружаемых заранее за одну порцию списка (4.13). */
        private const val PREFETCH_PER_PORTION = 8
        /** Первая порция меньше этого — «крошечная»: ждём полную (4.13). */
        private const val TINY_FIRST_PORTION = 4
        private const val TINY_FIRST_WAIT_MS = 600L
        private const val READER_GO_TIMEOUT_MS = 3_500L
        private const val SIDE_PANE_REFRESH_MS = 15_000L
        private const val LOGIN_CHECK_MS = 1_000L
        private const val LOGIN_CHECK_TIMES = 9
        /** Номера голосовых в очереди музыки переписки — чтобы не путать с треками. */
        private const val FOLDERS_PREFS = "messages_ui"
        private const val FOLDERS_KEY = "folders"
        /** С этого номера — беседы (peer_id 2e9+), ниже — люди и (отрицательные) сообщества. */
        /** Сколько ждать список участников беседы, прежде чем показать писавших (4.32). */
        private const val MEMBERS_WAIT_MS = 2_500L
        /** Окно после возврата в переписку, в котором «Назад» считается вторым нажатием по прежнему экрану (4.38). */
        private const val BACK_ECHO_MS = 2_000L

        /** Открытая переписка в сохранённом состоянии экрана (4.88): пересоздание возвращает в неё. */
        private const val STATE_PEER = "messages.state.peer"
        private const val STATE_PEER_TITLE = "messages.state.peer_title"
        private const val STATE_OPENED_FOR_PEER = "messages.state.opened_for_peer"
        /** Открыть переписку сразу: id собеседника (у сообществ — отрицательный), имя и аватар для заголовка. */
        const val EXTRA_PEER_ID = "messages.peer_id"
        /** «Поделиться» из других приложений (4.57, [ShareReceiverActivity]): текст и адреса файлов. */
        const val EXTRA_SHARE_TEXT = "messages.share_text"
        const val EXTRA_SHARE_URIS = "messages.share_uris"
        const val EXTRA_PEER_TITLE = "messages.peer_title"
        const val EXTRA_PEER_AVATAR = "messages.peer_avatar"

        private const val PREFETCH_MESSAGES = 60
        /** Сколько после отправки держать список у последнего: ответ сервера и история приходят за 0,5-1 с. */
        private const val FOLLOW_SENT_MS = 5_000L
        /** Сколько ждать файлы видео от страницы, прежде чем открыть его страницей. */
        /** Ссылок из текста в меню сообщения — не больше; длина подписи ссылки. */
        private const val MENU_LINKS_MAX = 3
        /** Сколько последних переписок держать в памяти для мгновенного повторного открытия. */
        private const val RECENT_CHATS = 8
        /** Порция диалогов, по которой можно ставить настоящий порядок: столько присылает страница целиком. */
        private const val FULL_DIALOGS_PORTION = 20
        /** Сколько ждать сообщение с файлами в истории после отправки, прежде чем убрать пузырь «отправляется». */
        private const val ATTACH_PENDING_MAX_MS = 45_000L
        /** Сколько ждать ответа страницы на трек из переписки (адрес звука или нажатие вложения). */
        private const val MENU_LINK_CHARS = 40
    }
}
