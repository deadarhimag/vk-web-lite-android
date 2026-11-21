package com.example.vkweb.messages

import com.example.vkweb.config.VkIds
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.example.vkweb.diag.DiagnosticMessages
import com.example.vkweb.web.MessageInbox
import com.example.vkweb.web.PayloadType
import org.json.JSONObject

/**
 * Двусторонний канал со страницей сообщений: получает данные (через web-message listener,
 * доступный только страницам VK) и отправляет команды scrape/send.
 */
class ReaderChannel(private val onPayload: (ReaderPayload) -> Unit) {

    var isSupported: Boolean = false
        private set

    /** Разбор вне главного потока, повторы и журнал приёма — общее с главным окном ([MessageInbox]). */
    private val inbox = MessageInbox("reader-channel")
    private val main = Handler(Looper.getMainLooper())

    /**
     * Пачка: страница присылает диалоги и историю по нескольку раз за секунду (неполная, потом полная). Экран получает
     * только последнее из пачки ([COALESCE_MS]) — без промежуточных перерисовок, от которых список дёргался.
     * Только главный поток.
     */
    private val pending = LinkedHashMap<String, ReaderPayload>()
    private val flush = Runnable { deliverPending() }

    fun install(webView: WebView): Boolean {
        // Приём — общий с главным окном (BridgeListener, 4.67).
        isSupported = com.example.vkweb.web.BridgeListener.install(webView, BRIDGE_NAME, inbox, ::receive)
        return isSupported
    }

    fun requestDialogs(webView: WebView) = scrape(webView, "dialogs")

    fun requestHistory(webView: WebView) = scrape(webView, "history")

    /**
     * Перейти на [path] («/mail» или «/mail/convo/N») силами мессенджера, без загрузки страницы (3.67). [done]: true —
     * переход начат, данные придут как обычно; false — скрипта нет или переход невозможен, нужна загрузка.
     */
    fun go(webView: WebView, path: String, done: (Boolean) -> Unit) {
        call(webView, "go", q(path)) { done(it == "true") }
    }

    /** Свежие данные открытого запросом с токеном страницы (без перезагрузки). [done]: false — данных входа ещё нет. */
    fun refresh(webView: WebView, done: (Boolean) -> Unit) {
        call(webView, "refresh") { done(it == "true") }
    }

    /**
     * Отправить [text] в переписку [peer] запросом messages.send от имени страницы (4.22, фоновая отправка): не зависит
     * от поля ввода страницы; [randomId] — постоянный номер сообщения очереди (повтор не задвоит). Итог — «sent».
     */
    fun sendApi(webView: WebView, peer: String, text: String, randomId: Int) {
        inbox.forgetLast()
        call(webView, "sendApi", q(peer), q(text), randomId.toString())
    }

    fun send(webView: WebView, text: String) {
        inbox.forgetLast()
        call(webView, "send", q(text))
    }

    /** Просим мессенджер на странице догрузить более старые сообщения; они придут обычным обновлением. */
    fun loadOlder(webView: WebView) {
        call(webView, "loadOlder")
    }

    /** Ответ на сообщение [cmid] открытой переписки текстом [text]; итог — сообщение «replied». */
    /** Недавние стикеры; итог — сообщение «stickers». */
    fun stickers(webView: WebView) {
        inbox.forgetLast()
        call(webView, "stickers")
    }

    /** Отправить стикер [id] в открытую переписку; итог — «stickersent». */
    /** Подсказки стикеров для набранного [text] (ответ — сообщение type = "stickersuggest"). */
    fun stickerSuggest(webView: WebView, text: String) {
        call(webView, "stickerSuggest", q(text))
    }

    fun sendSticker(webView: WebView, id: Long) {
        call(webView, "sendSticker", id.toString())
    }

    /** Действие с беседой [peer]: read, unread, mute, unmute, remove; итог — «convaction» и свежий список (4.0). */
    fun convAction(webView: WebView, action: String, peer: String) {
        inbox.forgetLast()
        call(webView, "convAction", q(action), q(peer))
    }

    /** Удалить сообщение [cmid] открытой переписки — у себя или у всех; итог — «deleted» (3.94). */
    fun deleteMessage(webView: WebView, cmid: Long, forAll: Boolean) {
        inbox.forgetLast()
        call(webView, "deleteMessage", cmid.toString(), forAll.toString())
    }

    /** Изменить текст своего сообщения [cmid]; итог — «edited» (3.94). */
    fun editMessage(webView: WebView, cmid: Long, text: String) {
        inbox.forgetLast()
        call(webView, "editMessage", cmid.toString(), q(text))
    }

    fun reply(webView: WebView, text: String, cmid: Long) {
        inbox.forgetLast()
        call(webView, "reply", q(text), cmid.toString())
    }

    /** Музыка из сообщения: страница сама нажимает вложение-трек [trackId] и играет его; итог — сообщения «audio». */
    fun playAudio(webView: WebView, trackId: String, title: String) {
        inbox.forgetLast()
        call(webView, "playAudio", q(trackId), q(title))
    }

    /** Пауза/продолжить ("toggle") или остановить ("stop") музыку, которую играет страница. */
    fun audio(webView: WebView, action: String) {
        call(webView, "audio", q(action))
    }

    /** Переслать сообщение [cmid] открытой переписки в [targetPeer]; итог — сообщение «forwarded». */
    fun forward(webView: WebView, targetPeer: String, cmid: Long) {
        inbox.forgetLast()
        call(webView, "forward", q(targetPeer), "[$cmid]")
    }

    /** Свой номер страницы (для «переслать себе»): [done] получает строку ответа страницы, разбор — [ForwardTargets.selfId]. */
    fun me(webView: WebView, done: (String?) -> Unit) {
        webView.evaluateJavascript("(window.__vkReader&&typeof window.__vkReader.me==='function')?String(window.__vkReader.me()):''") { done(it) }
    }

    /** Файлы видео или клипа [id] (с ключом доступа [key]) для проигрывателя приложения; итог — сообщение «video». */
    fun video(webView: WebView, id: String, key: String) {
        inbox.forgetLast()
        call(webView, "video", q(id), q(key))
    }

    /** Адрес звука трека [trackId] из сообщения (ключ [key], название [title] — для поиска); итог — сообщение «audiourl». */
    fun audioUrl(webView: WebView, trackId: String, key: String, title: String, fresh: Boolean = false) {
        inbox.forgetLast()
        call(webView, "audioUrl", q(trackId), q(key), q(title), fresh.toString())
    }

    /** Открытая переписка прочитана: страница отмечает её (messages.markAsRead) — счётчик непрочитанных на сайте сбрасывается. */
    /** Список диалогов, пока страница стоит на переписке (две колонки, 3.94) — итог обычное сообщение «dialogs». */
    fun refreshDialogs(webView: WebView) {
        call(webView, "dialogs")
    }

    /** Участники беседы [peer] — итог сообщение «members» (4.32). */
    fun members(webView: WebView, peer: String) {
        inbox.forgetLast()
        call(webView, "members", q(peer))
    }

    /** Своя страница (имя, фото) для шапки меню — итог сообщение «self» (3.94). */
    fun requestSelf(webView: WebView) {
        call(webView, "self")
    }

    /**
     * Непрочитанные переписки [peers] — заранее, без отметки о прочтении (4.13): страница запрашивает их историю и
     * присылает её обычными сообщениями «history» с номером переписки; приложение сохраняет.
     */
    fun prefetch(webView: WebView, peers: List<String>) {
        if (peers.isEmpty()) return
        call(webView, "prefetch", org.json.JSONArray(peers).toString())
    }

    fun markRead(webView: WebView) {
        call(webView, "markRead")
    }

    /**
     * Очередь музыки переписки: адреса потоков треков [cards] (по порядку), [wanted] — нажатый (номер; пусто — только
     * название [wantedTitle] для поиска); итог — сообщение «audioqueue».
     */
    fun audioQueue(webView: WebView, cards: List<ChatCard>, wanted: String, wantedTitle: String) {
        inbox.forgetLast()
        val list = org.json.JSONArray()
        cards.forEach { list.put(JSONObject().put("id", it.trackId).put("key", it.trackKey).put("title", it.title)) }
        call(webView, "audioQueue", list.toString(), q(wanted), q(wantedTitle))
    }

    /** Поток разбора. */
    /** Записи дампа разработчика от окна чтения (3.68); null — не собирать. Вызывается не в главном потоке. */
    @Volatile var onDump: ((String) -> Unit)? = null

    private fun receive(raw: String) {
        val type = PayloadType.of(raw)
        if (type == com.example.vkweb.dev.DumpRecorder.TYPE) {
            onDump?.invoke(raw)
            return
        }
        // Записи журнала скрипта и сводка трафика — в журнал, дальше они не идут.
        if (DiagnosticMessages.handle(type, raw)) return
        // Completion events from distinct operations may have identical bodies.
        when (type) {
            "sent", "delivered", "attach", "voiceSent" -> Unit
            else -> if (!inbox.isNew(type, raw)) return
        }
        val payload = ReaderPayload.parse(raw) ?: return
        inbox.received(type.ifEmpty { payload.javaClass.simpleName }, raw.length, itemsOf(payload))
        main.post { accept(type, payload) }
    }

    /** Главный поток: данные — в пачку, события (отправка, недоступно) — сразу, после того, что уже в пачке. */
    private fun accept(type: String, payload: ReaderPayload) {
        if (closed) return
        when (payload) {
            is ReaderPayload.Dialogs, is ReaderPayload.History -> {
                val key = if (payload is ReaderPayload.History) "history:${payload.peerId}" else "dialogs"
                val first = pending.isEmpty()
                pending[key] = payload
                if (first) main.postDelayed(flush, COALESCE_MS)
            }
            else -> {
                deliverPending()
                onPayload(payload)
            }
        }
    }

    /** Экран закрыт: всё, что ещё в пути или в пачке, ему уже не доставляется (код ревью 3.59). */
    fun dispose() {
        closed = true
        main.removeCallbacks(flush)
        pending.clear()
    }

    @Volatile private var closed = false

    private fun deliverPending() {
        main.removeCallbacks(flush)
        if (closed) return
        if (pending.isEmpty()) return
        val batch = ArrayList(pending.values)
        pending.clear()
        batch.forEach(onPayload)
    }

    /**
     * Передать странице кусок файла для вложения (base64). [done] — страница приняла кусок: можно слать следующий
     * (иначе очередь из сотен килобайт забила бы канал связи со страницей).
     */
    fun beginTransfer(webView: WebView, operation: String, peer: String) {
        call(webView, "beginTransfer", q(operation), q(peer))
    }

    fun cancelTransfer(webView: WebView, operation: String) {
        call(webView, "cancelTransfer", q(operation))
    }

    fun stageFile(webView: WebView, index: Int, name: String, mime: String, total: Int, part: Int, base64: String, operation: String, done: (Boolean) -> Unit) {
        call(webView, "stage", index.toString(), q(name), q(mime), total.toString(), part.toString(), q(base64), q(operation)) { done(it == "true") }
    }

    /** Попросить страницу подставить переданные файлы в композер, дождаться загрузки и отправить сообщение с [text]. */
    /** Картинки реакций (4.61): итог — «reactionAssets». */
    fun reactionAssets(webView: WebView) {
        call(webView, "reactionAssets")
    }

    /** Поставить реакцию [reactionId] на сообщение [cmid] открытой переписки; 0 — снять свою. Итог — «reacted». */
    fun react(webView: WebView, cmid: Long, reactionId: Int) {
        if (cmid <= 0 || reactionId < 0) return
        call(webView, "react", cmid.toString(), reactionId.toString())
    }

    /** Голосовое (4.59): запись уже передана stageFile; страница загружает и отправляет её — итог «voiceSent». */
    fun sendVoice(webView: WebView, peer: String, randomId: Long, operation: String) {
        if (!VkIds.isPeer(peer)) return
        inbox.forgetLast()
        call(webView, "sendVoice", q(peer), randomId.toString(), q(operation))
    }

    fun attachAndSend(webView: WebView, text: String, operation: String) {
        inbox.forgetLast()
        call(webView, "attachAndSend", q(text), q(operation))
    }

    /**
     * Команда странице (4.66, код ревью 4.64): вызов window.__vkReader.[name] с уже записанными на JS аргументами
     * [args] ([q] — строка). Раньше около тридцати таких строк собирались вручную, и у части не было проверки, что функция
     * есть на странице. [done] — ответ страницы (false — функции нет).
     */
    private fun call(webView: WebView, name: String, vararg args: String, done: ((String?) -> Unit)? = null) {
        if (closed) { done?.invoke("false"); return }
        webView.evaluateJavascript(ReaderCall.script(name, *args), done?.let { d -> android.webkit.ValueCallback<String> { d(it) } })
    }

    /** Строка для JS (в кавычках, с экранированием). */
    private fun q(value: String): String = JSONObject.quote(value)

    /** Число сообщений или диалогов в порции — для журнала. */
    private fun itemsOf(payload: ReaderPayload): Int? = when (payload) {
        is ReaderPayload.Dialogs -> payload.items.size
        is ReaderPayload.History -> payload.items.size
        else -> null
    }

    private fun scrape(webView: WebView, kind: String) {
        // Ответ на явный запрос должен дойти, даже если совпадает с прежним (после перезагрузки страницы ждём именно его).
        inbox.forgetLast()
        call(webView, "scrape", q(kind))
    }

    companion object {
        const val BRIDGE_NAME = "vkReaderBridge"

        /** Сколько ждать следующую порцию пачки, прежде чем показать последнюю. */
        const val COALESCE_MS = 180L

        // Открываем на том же домене, что и остальное приложение; VK сам приведёт com↔ru.
        const val DIALOGS_URL = "https://m.vk.com/mail"

        fun peerUrl(peerId: String): String = "https://m.vk.com/mail/convo/$peerId"

        fun peerPath(peerId: String): String = "/mail/convo/$peerId"

        const val DIALOGS_PATH = "/mail"

        private val CONVO = Regex("^/mail/convo/(-?\\d+)")

        fun isDialogsUrl(url: String?): Boolean {
            val uri = url?.let(Uri::parse) ?: return false
            return uri.path?.trimEnd('/') == "/mail"
        }

        fun isHistoryUrl(url: String?): Boolean {
            val uri = url?.let(Uri::parse) ?: return false
            return CONVO.containsMatchIn(uri.path.orEmpty())
        }
    }
}
