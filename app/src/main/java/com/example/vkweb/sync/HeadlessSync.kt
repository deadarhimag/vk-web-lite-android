package com.example.vkweb.sync

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import com.example.vkweb.web.TrafficCollector
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.messages.DialogEntry
import com.example.vkweb.messages.HistoryMerge
import com.example.vkweb.messages.ReadMarks
import com.example.vkweb.messages.ReaderChannel
import com.example.vkweb.messages.ReaderPayload
import com.example.vkweb.messages.ReaderScript
import com.example.vkweb.offline.OfflineStore
import com.example.vkweb.outbox.Outbox
import com.example.vkweb.settings.AppSettings
import com.example.vkweb.sync.SyncMachine.Command
import com.example.vkweb.web.IdlePolicy

/**
 * Фоновая работа с сообщениями без экрана: невидимое окно открывает страницы мессенджера VK, читает их
 * тем же скриптом, что и экран сообщений ([ReaderScript]), и выполняет команды [SyncMachine]:
 * забрать список диалогов (сохранить для открытия приложения и сообщить о новых), отправить очередь.
 * Всё в главном потоке. Окно создаётся на время работы и уничтожается — между проверками ничего не держится.
 *
 * Ограничение: как именно мессенджер VK ведёт себя в окне без экрана, проверяется только на устройстве
 * (размер окна задан явно: без высоты он не рисует переписку). Любой сбой заканчивается безопасно:
 * очередь не теряется, а сомнительное не отправляется повторно.
 */
class HeadlessSync(
    context: Context,
    private val includeDialogs: Boolean,
    /** Показывать ли уведомление о новых сообщениях. */
    private val notify: Boolean,
    private val onFinished: () -> Unit,
) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val store = OfflineStore.get(app)
    private val outbox = Outbox.get(app)
    private val notifier = MessageNotifier(app)
    private val notifyRules = DialogNotifyStore(app)
    private val channel = ReaderChannel(::onPayload)
    private val machine = SyncMachine(outbox, includeDialogs)

    private var webView: WebView? = null
    private var finished = false
    private var commandGeneration = 0L
    private var startedAt = 0L

    private val stepTimeout = Runnable {
        AppLog.w(LogTag.SYNC, "страница не ответила вовремя, переходим дальше", "мс" to STEP_TIMEOUT_MS)
        execute(machine.onStepTimeout(now()))
    }
    private val totalTimeout = Runnable {
        AppLog.w(LogTag.SYNC, "фоновая работа не уложилась во время, завершаем", "мс" to TOTAL_TIMEOUT_MS)
        finish()
    }

    /** Начать. Очередь читается с диска, затем выполняются шаги. */
    fun start() {
        outbox.addScheduledDeliveredListener(scheduledDelivered)
        startedAt = now()
        AppVisibility.syncRunning = true
        AppLog.i(LogTag.SYNC, "фоновая работа начата", "диалоги" to includeDialogs, "уведомления" to notify)
        handler.postDelayed(totalTimeout, TOTAL_TIMEOUT_MS)
        outbox.whenReady {
            if (finished) return@whenReady
            execute(machine.start(now()))
        }
    }

    /** Прервать (система остановила работу): окно уничтожается, владельцу сообщать не нужно — он уже знает. */
    fun cancel() {
        AppLog.i(LogTag.SYNC, "фоновая работа прервана системой")
        finish(notifyOwner = false)
    }

    // ------------------------------------------------------------ выполнение команд

    private fun execute(command: Command) {
        if (finished) return
        if (command != Command.Wait) commandGeneration++
        when (command) {
            is Command.Load -> {
                armStepTimeout()
                load(command.url)
            }
            is Command.Send -> {
                armStepTimeout()
                // 4.22: в фоне — запросом API от имени страницы, а не нажатием в её поле ввода. Отчёт 4.19: отложенное
                // в спящем телефоне пять раз подряд «не нажато» — поле страницы в скрытом окне не готово, — и FAILED.
                AppLog.i(LogTag.OUTBOX, "отправка из очереди в фоне", "как" to "запрос API")
                val mine = commandGeneration
                outbox.whenPersisted { ok ->
                    if (!finished && mine == commandGeneration) {
                        if (ok && outbox.items().any { it.id == command.itemId && it.state == com.example.vkweb.outbox.OutboxState.SENDING }) {
                            val view = webView
                            if (view != null) {
                                machine.onDispatched(command.itemId)
                                channel.sendApi(view, command.peerId, command.text, command.randomId)
                            } else finish()
                        }
                        else {
                            AppLog.w(LogTag.OUTBOX, "отправка отменена: очередь не записана на диск")
                            finish()
                        }
                    }
                }
            }
            is Command.Recheck -> {
                armStepTimeout()
                handler.postDelayed({ if (!finished) webView?.let(channel::requestHistory) }, command.delayMs)
            }
            Command.Wait -> Unit
            // 4.15: перед завершением — новые непрочитанные заранее (если есть и включено), затем завершение.
            Command.Finish -> if (!startPrefetch()) finishWhenMarked()
        }
    }

    private fun armStepTimeout() {
        handler.removeCallbacks(stepTimeout)
        handler.postDelayed(stepTimeout, STEP_TIMEOUT_MS)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun load(url: String) {
        val view = webView ?: createWebView()?.also { webView = it } ?: return
        view.loadUrl(url)
    }

    /**
     * Окно создаётся в фоне, когда системный компонент WebView может быть недоступен (обновляется, отключён):
     * тогда конструктор бросает исключение, и без защиты приложение упало бы без ведома пользователя.
     */
    private fun createWebView(): WebView? = try {
        buildWebView()
    } catch (e: Exception) {
        AppLog.e(LogTag.SYNC, "не удалось создать окно WebView, работа отменена", e)
        finish()
        null
    }

    private fun buildWebView(): WebView {
        val view = WebView(app)
        // Общая настройка окна чтения — та же, что у экрана сообщений (ReaderWindow, 4.66).
        com.example.vkweb.messages.ReaderWindow.setUp(view, channel, AppSettings(app), dumpLabel = "фон", isActive = { !finished },
            onRendererGone = { webView = null; finish() })
        // Мессенджер VK рисует и догружает переписку, только когда у окна есть размер: даём его явно.
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WINDOW_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(WINDOW_HEIGHT, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT)
        view.onResume()
        view.resumeTimers()
        return view
    }

    // ------------------------------------------------------------ непрочитанные заранее (4.15)

    /**
     * Новые непрочитанные этой проверки — загрузить и сохранить без отметки о прочтении (как экран сообщений с 4.13),
     * чтобы их можно было открыть потом, в том числе без сети: экран сообщений грузит заранее, только пока открыт, а новые
     * сообщения чаще всего приходят, когда приложение закрыто. Прочитанными они станут при открытии.
     */
    private var prefetchPeers: List<String> = emptyList()
    private var prefetchTitles: Map<String, String> = emptyMap()
    private var prefetching = false
    private val prefetchWaiting = HashSet<String>()
    private val prefetchDone = Runnable {
        AppLog.i(LogTag.SYNC, "загрузка заранее закончена по времени", "не пришло" to prefetchWaiting.size)
        showChains()
        finishWhenMarked()
    }

    // ------------------------------------------------------------ цепочка сообщений в уведомлении (5.00.12)

    /** Что показано в уведомлениях этой проверки: по ним цепочки дополняются, когда придёт история. */
    private var notified: List<DialogEntry> = emptyList()
    private var notifiedRules: Map<String, DialogNotify> = emptyMap()
    private var notifiedPreview = true

    /** Цепочки последних сообщений по диалогам, собранные из загруженного заранее. */
    private val chains = HashMap<String, List<NotifyChain.Line>>()

    /** Дополнить уведомления цепочками (один раз за проверку, молча). */
    private fun showChains() {
        if (chains.isEmpty() || !notify) return
        notifier.updateChains(notified, HashMap(chains), notifiedRules, notifiedPreview)
        chains.clear()
    }

    // ------------------------------------------------------------ отложенное — непрочитанным (4.18)

    private val scheduledDelivered: (List<com.example.vkweb.outbox.OutboxItem>) -> Unit = { items ->
        val view = webView
        if (view != null && !finished) {
            readMarks.takeUnreads(items.map { it.peerId }).forEach { peer ->
                AppLog.i(LogTag.SYNC, "отложенное ушло — переписка отмечается непрочитанной")
                channel.convAction(view, ReadMarks.UNREAD, peer)
            }
        }
    }

    // ------------------------------------------------------------ отложенные отметки «прочитано» (4.35)

    /**
     * Отметки «прочитано», ждущие отправки ([PendingReads]): «Прочитано» в уведомлении и переписки, открытые без сети.
     * Отправляются перед завершением, тем же запросом страницы, что у экрана сообщений; подтверждённая — снимается,
     * неподтверждённая остаётся до следующего раза.
     */
    private val pendingReads = com.example.vkweb.messages.PendingReads(app)

    /** Отметки, ждущие ответа страницы — общее с экраном сообщений (ReadMarks, 4.67). */
    private val readMarks = ReadMarks(pendingReads)

    private fun sendPendingReads() {
        val view = webView ?: return
        val peers = readMarks.takeReads()
        if (peers.isEmpty()) return
        AppLog.i(LogTag.SYNC, "отложенные отметки «прочитано» отправлены", "переписок" to peers.size)
        peers.forEach { peer -> channel.convAction(view, ReadMarks.READ, peer) }
    }

    private val markDone = Runnable {
        AppLog.w(LogTag.SYNC, "отметки не подтверждены вовремя", "непрочитана" to readMarks.unreadsWaiting, "прочитано" to readMarks.readsWaiting)
        readMarks.forget()
        finish()
    }

    private fun onMarkedUnread(payload: ReaderPayload.ConvAction) {
        when (readMarks.onResult(payload.action, payload.peer, payload.ok)) {
            ReadMarks.Result.READ_OK -> AppLog.i(LogTag.SYNC, "отложенная отметка «прочитано» принята")
            ReadMarks.Result.READ_FAILED ->
                AppLog.w(LogTag.SYNC, "отложенная отметка «прочитано» не принята — останется до следующего раза", "причина" to payload.reason)
            ReadMarks.Result.UNREAD_OK -> AppLog.i(LogTag.SYNC, "переписка с отложенным отмечена непрочитанной")
            ReadMarks.Result.UNREAD_FAILED ->
                AppLog.w(LogTag.SYNC, "переписка с отложенным не отмечена непрочитанной", "причина" to payload.reason)
            ReadMarks.Result.OTHER -> return
        }
        if (!readMarks.isWaiting && waitingMarksToFinish) {
            handler.removeCallbacks(markDone)
            finish()
        }
    }

    /** Завершение ждёт отметок «непрочитана» (не дольше [MARK_WAIT_MS]): иначе окно закрылось бы раньше ответа API. */
    private var waitingMarksToFinish = false

    private fun finishWhenMarked() {
        sendPendingReads()
        if (!readMarks.isWaiting) {
            finish()
            return
        }
        waitingMarksToFinish = true
        handler.removeCallbacks(stepTimeout)
        // Запрос мог оборваться переходом окна к следующей переписке: повторяем (отметка повторно ничего не портит).
        webView?.let { view -> readMarks.unreadsInFlight().forEach { peer -> channel.convAction(view, ReadMarks.UNREAD, peer) } }
        handler.removeCallbacks(markDone)
        handler.postDelayed(markDone, MARK_WAIT_MS)
    }

    /** Начать загрузку заранее: false — нечего, выключено или окна нет (тогда — сразу завершение). */
    private fun startPrefetch(): Boolean {
        if (prefetching || prefetchPeers.isEmpty() || !AppSettings(app).prefetchUnread) return false
        val view = webView ?: return false
        prefetching = true
        prefetchWaiting += prefetchPeers
        handler.removeCallbacks(stepTimeout)
        AppLog.i(LogTag.SYNC, "новые непрочитанные загружаются заранее (без отметки о прочтении)", "штук" to prefetchPeers.size)
        channel.prefetch(view, prefetchPeers)
        handler.postDelayed(prefetchDone, PREFETCH_BASE_MS + PREFETCH_PER_PEER_MS * prefetchPeers.size)
        return true
    }

    private fun onPrefetched(payload: ReaderPayload.History) {
        val peer = payload.peerId
        if (!prefetchWaiting.remove(peer)) return
        val items = HistoryMerge.persistable(payload.items)
        // Последние входящие подряд - в уведомление этого диалога
        notified.firstOrNull { it.peerId == peer }?.let { dialog ->
            val chain = NotifyChain.of(dialog, payload.items)
            if (chain.size > 1) chains[peer] = chain
        }
        if (items.isNotEmpty()) {
            store.loadHistory(peer) { saved ->
                // Дописываем к сохранённой: старое, загруженное раньше, не теряется.
                val all = HistoryMerge.persistable(HistoryMerge.messages(saved?.items ?: emptyList(), items))
                val title = HistoryMerge.chatTitle(payload.title, prefetchTitles[peer] ?: saved?.title.orEmpty())
                store.saveHistory(peer, title, all)
            }
        }
        AppLog.d(LogTag.SYNC, "переписка загружена заранее", "сообщений" to items.size, "осталось" to prefetchWaiting.size)
        if (prefetchWaiting.isEmpty()) {
            handler.removeCallbacks(prefetchDone)
            showChains()
            // Записи на диск идут своим потоком: даём им начаться до закрытия окна.
            handler.postDelayed({ finishWhenMarked() }, SAVE_GRACE_MS)
        }
    }

    // ------------------------------------------------------------ данные со страницы

    private fun onPayload(payload: ReaderPayload) {
        if (finished) return
        when (payload) {
            is ReaderPayload.Dialogs -> onDialogs(payload)
            is ReaderPayload.History -> {
                if (prefetching) {
                    onPrefetched(payload)
                    return
                }
                AppLog.d(LogTag.SYNC, "история от страницы", "сообщений" to payload.items.size)
                execute(machine.onHistory(payload.peerId, payload.items, now()))
            }
            // Вложения в фоновой проверке не отправляются.
            is ReaderPayload.Attach, is ReaderPayload.Audio, is ReaderPayload.Replied, is ReaderPayload.Forwarded,
            is ReaderPayload.Video, is ReaderPayload.AudioUrl, is ReaderPayload.AudioQueue, ReaderPayload.OlderDone,
            is ReaderPayload.Stickers, is ReaderPayload.StickerSent, is ReaderPayload.StickerSuggest, is ReaderPayload.Self, is ReaderPayload.Members,
            is ReaderPayload.Deleted, is ReaderPayload.Edited, is ReaderPayload.VoiceSent, is ReaderPayload.VoiceStage, is ReaderPayload.Typing,
            is ReaderPayload.ReactionAssets, is ReaderPayload.Reacted -> Unit
            is ReaderPayload.ConvAction -> onMarkedUnread(payload)
            // Сервер принял сообщение (успешный ответ отправки): ждать его появления в истории незачем.
            is ReaderPayload.Delivered -> {
                AppLog.i(LogTag.OUTBOX, "ответ отправки в фоне: сервер принял сообщение")
                execute(machine.onDelivered(payload.peerId, payload.text, now(), payload.cmid))
            }
            is ReaderPayload.Sent -> {
                AppLog.i(LogTag.OUTBOX, "результат отправки в фоне", "нажато" to payload.ok)
                execute(machine.onSent(payload.ok, now()))
            }
            is ReaderPayload.Unavailable -> {
                val login = payload.reason == "login"
                AppLog.w(LogTag.SYNC, "страница недоступна для чтения", "причина" to payload.reason)
                execute(machine.onUnavailable(login, now()))
            }
        }
    }

    /**
     * Список диалогов: сохраняем для открытия приложения, находим новые сообщения, показываем уведомление.
     * Дальше идём, только когда всё записано, — иначе завершение работы могло бы оборвать запись.
     */
    private fun onDialogs(payload: ReaderPayload.Dialogs) {
        if (payload.items.isEmpty()) {
            execute(machine.onDialogs(payload.items, now()))
            return
        }
        store.loadSyncState { saved ->
            if (finished) return@loadSyncState
            // Свои настройки диалогов (4.47) — свежие на каждую проверку: их могли поменять между проверками.
            val rules = notifyRules.all()
            val result = NewMessages.detect(payload.items, saved ?: SyncState(), rules)
            store.saveSyncState(result.state)
            AppLog.i(
                LogTag.SYNC, "диалоги получены",
                "всего" to payload.items.size, "непрочитанных" to result.unread.size, "новых" to result.fresh.size,
            )
            // «Прочитано» из уведомления, ещё не отправленное (4.35, MarkedReads): с новыми сообщениями — снимается.
            val marked = MarkedReads.apply(result.unread, result.fresh, pendingReads.all())
            marked.drop.forEach { peer ->
                pendingReads.remove(peer)
                AppLog.i(LogTag.SYNC, "после «Прочитано» пришли новые сообщения — отметка снята")
            }
            prefetchPeers = result.fresh.take(PREFETCH_MAX).map { it.peerId }
            prefetchTitles = result.fresh.associate { it.peerId to it.title }
            if (notify) {
                // Отмеченное «Прочитано», но ещё не отправленное, в уведомление не возвращается.
                // 4.48: шторка приводится к списку на каждой проверке (по уведомлению на диалог): новое ставится,
                // прочитанное в другом месте — убирается, остальное не трогается.
                val preview = AppSettings(app).notifyPreview
                notifier.show(marked.unread, result.fresh.filter { it in marked.unread }, rules, preview)
                notified = marked.unread
                notifiedRules = rules
                notifiedPreview = preview
            }
            store.loadDialogs { old ->
                if (finished) return@loadDialogs
                store.saveDialogs(HistoryMerge.dialogs(old.orEmpty(), payload.items))
                execute(machine.onDialogs(payload.items, now()))
            }
        }
    }

    // ------------------------------------------------------------ завершение

    private fun finish(notifyOwner: Boolean = true) {
        if (finished) return
        machine.cancelPendingDispatch()
        finished = true
        channel.dispose()
        outbox.removeScheduledDeliveredListener(scheduledDelivered)
        AppVisibility.syncRunning = false
        handler.removeCallbacksAndMessages(null)
        val view = webView
        webView = null
        AppLog.i(LogTag.SYNC, "фоновая работа завершена", "мс" to now() - startedAt)
        if (view == null) {
            if (notifyOwner) onFinished()
            return
        }
        view.stopLoading()
        // Что приходило от серверов за проверку — в журнал, и только потом окно закрывается (ответ приходит в колбэке;
        // если страница не отвечает, окно закрывается по таймеру, а не висит).
        var closed = false
        val close = Runnable {
            if (closed) return@Runnable
            closed = true
            // Окно сбросило общий сон таймеров (resumeTimers): если приложение не на экране, возвращаем его.
            if (IdlePolicy.canPauseWeb(AppVisibility.mainOpen, AppVisibility.messagesOpen, AppVisibility.musicPlaying, cacheRunning = AppVisibility.cacheRunning)) view.pauseTimers()
            view.destroy()
            if (notifyOwner) onFinished()
        }
        TrafficCollector.collect(view) { close.run() }
        handler.postDelayed(close, TRAFFIC_WAIT_MS)
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val TRAFFIC_WAIT_MS = 800L
        /** Новых непрочитанных за одну проверку, загружаемых заранее (4.15). */
        const val PREFETCH_MAX = 8
        const val PREFETCH_BASE_MS = 5_000L
        /** Страница грузит по одной с паузой 0,7 с: на каждую — с запасом. */
        const val PREFETCH_PER_PEER_MS = 2_500L
        const val SAVE_GRACE_MS = 300L
        /** Ответ на отметку «непрочитана» (4.18). */
        const val MARK_WAIT_MS = 6_000L
        const val STEP_TIMEOUT_MS = 30_000L
        const val TOTAL_TIMEOUT_MS = 3 * 60_000L
        const val WINDOW_WIDTH = 1080
        const val WINDOW_HEIGHT = 1920
    }
}
