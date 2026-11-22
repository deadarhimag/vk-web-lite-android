package com.example.vkweb.web

import com.example.vkweb.Background
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.example.vkweb.config.AppConfig
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.DiagnosticMessages
import com.example.vkweb.diag.LogTag
import com.example.vkweb.media.MediaState
import com.example.vkweb.media.PlayerCommand
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Канал между страницей (assets/vk_lite/) и приложением: в одну сторону — сообщения страницы
 * (состояние плеера, данные своих экранов), в другую — команды (плеер, догрузка, «прислать заново»).
 * Сообщения своих экранов разбираются по полю type: экран регистрирует свой тип через [on],
 * поэтому новый экран подключается без правок здесь. Сообщения без известного типа — состояние плеера.
 * Используется addWebMessageListener, а не addJavascriptInterface: объект виден только
 * страницам из [AppConfig.SCRIPT_ORIGIN_RULES] и не доступен рекламным iframe.
 */
class PageBridge(
    private val onMediaState: (MediaState) -> Unit,
    /** Окно кэша музыки (4.99.7): сообщения без своего обработчика пропускаются молча, а не в журнал как неизвестные. */
    private val quiet: Boolean = false,
) {

    /** Разбор и обработка сообщений одного типа: разбор — в фоновом потоке, обработка — в главном. */
    private class Route<T : Any>(val parse: (JSONObject) -> T?, val handle: (T) -> Unit) {
        /** Разобрать (фоновый поток). @return действие для главного потока или null — сообщение не разобрано. */
        fun prepare(json: JSONObject): (() -> Unit)? {
            val value = parse(json) ?: return null
            return { handle(value) }
        }
    }

    private val routes = ConcurrentHashMap<String, Route<*>>()
    private val rawRoutes = ConcurrentHashMap<String, (String) -> Unit>()

    /**
     * Входящие: разбор в отдельном потоке (данные стены, списков и музыки бывают в сотни килобайт), отбрасывание повторов
     * и журнал приёма — общее с окнами чтения сообщений ([MessageInbox]). Память о прошлых сообщениях сбрасывается при
     * каждой команде странице ([call]): после просьбы «прислать заново» ответ должен дойти, даже если он такой же.
     */
    private val inbox = MessageInbox("page-bridge")
    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null

    /** Экран закрыт ([dispose]): сообщения больше не доставляются, команды не отправляются. */
    @Volatile private var disposed = false

    fun dispose() {
        disposed = true
        webView = null
    }

    /** Сообщения типа [type] разбираются [parse] (null — битое, пропускается) и передаются [handle]. */
    fun <T : Any> on(type: String, parse: (JSONObject) -> T?, handle: (T) -> Unit) {
        routes[type] = Route(parse, handle)
    }

    /** Сообщения типа [type] передаются строкой, без разбора и без отбрасывания повторов (поток сбора данных страниц). */
    fun onRaw(type: String, handle: (String) -> Unit) {
        rawRoutes[type] = handle
    }

    fun install(webView: WebView): Boolean {
        this.webView = webView
        // Приём — общий с окнами чтения сообщений (BridgeListener, 4.67).
        if (!BridgeListener.install(webView, AppConfig.JS_BRIDGE_NAME, inbox, ::route)) {
            Log.w(TAG, "WebView слишком старый: управление плеером и свои экраны недоступны")
            AppLog.e(LogTag.BRIDGE, "WebView не поддерживает мост: свои экраны и управление плеером недоступны")
            return false
        }
        return true
    }

    /** Поток разбора: тип, повтор, разбор; обработка — в главном потоке. */
    private fun route(raw: String) {
        val quickType = PayloadType.of(raw)
        // Записи журнала скрипта и сводка трафика — в журнал, дальше они не идут.
        if (DiagnosticMessages.handle(quickType, raw)) return
        rawRoutes[quickType]?.let { handle ->
            main.post { handle(raw) }
            return
        }
        if (!inbox.isNew(quickType, raw)) return
        val json = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            AppLog.w(LogTag.BRIDGE, "сообщение страницы не разобрано как JSON", "байт" to raw.length)
            return
        }
        val type = json.optString("type")
        inbox.received(type, raw.length, json.optJSONArray("items")?.length())
        val action: (() -> Unit)? = try {
            val route = routes[type]
            when {
                route != null -> route.prepare(json) ?: run {
                    AppLog.w(LogTag.BRIDGE, "сообщение отклонено при разборе", "тип" to type, "байт" to raw.length)
                    null
                }
                type == MediaState.TYPE -> MediaState.fromJson(raw)?.let { state -> { onMediaState(state) } }
                quiet -> null
                else -> {
                    AppLog.w(LogTag.BRIDGE, "сообщение неизвестного типа", "тип" to type, "байт" to raw.length)
                    null
                }
            }
        } catch (e: JSONException) {
            // Битое сообщение — пропускаем: следующее придёт при следующем изменении.
            // Текст ошибки JSON содержит само сообщение страницы (тексты, имена) — в журнал только вид (код ревью 3.59).
            AppLog.w(LogTag.BRIDGE, "ошибка разбора сообщения", "тип" to type, "ошибка" to e.javaClass.simpleName)
            null
        }
        // Экран закрыт — то, что разобрано в фоне после этого, ему не доставляется (иначе, например, «играет» снова
        // поднимало сервис плеера после его остановки — код ревью 3.59).
        action?.let { main.post { if (!disposed) it() } }
    }

    // ------------------------------------------------------------ команды странице

    fun send(command: PlayerCommand) {
        // Звук трека идёт с серверов видео (4.88): после команды плеера его файлам можно грузиться и под своим экраном.
        PageMedia.noteAudioCommand()
        call("command", command.js)
    }

    /** Перейти на [url] силами страницы, не перезагружая её (плеер VK продолжает играть). */
    fun navigateInPage(url: String) = call("command", "navigate", url)

    /** Перемотать звучащий трек на [seconds] секунд. */
    fun seek(seconds: Double) {
        PageMedia.noteAudioCommand()
        call("command", "seek", seconds.toString())
    }

    /** Свой экран музыки: включить трек, догрузить, прислать список заново. */
    fun music(action: String, trackId: String? = null) =
        if (trackId == null) call("music", action) else call("music", action, trackId)

    /** Identifies the lookup and explicitly invalidates cached addresses selected for recovery. */
    fun resolveMusic(ids: List<String>, operation: String, force: List<String>) = call(
        "music", "resolve", ids.joinToString(","), JSONObject().put("operation", operation)
            .put("force", org.json.JSONArray(force)).toString(),
    )

    /** Потоки треков [ids] (до [com.example.vkweb.media.MusicStreams.BATCH]) для кэша: ответ — «musicStreams» (4.97). */
    fun musicStreams(ids: List<String>) = call("music", "streams", ids.joinToString(","))

    /** Включить трек из списка [from]: «my» («Мои треки») или «playlist» — страница при необходимости перейдёт сама. */
    fun playTrack(trackId: String, from: String) {
        PageMedia.noteAudioCommand()
        call("music", ACTION_PLAY, trackId, from)
    }

    /** Музыка из переписки: включить трек [trackId] (пусто — нет номера), не найден на странице — поиск по [title]. */
    fun playFromChat(trackId: String, title: String) = call("music", "chat", trackId, title)

    /** Вкладка «Плейлисты» открылась: прислать плейлисты, при необходимости найти их страницу. */
    fun playlists() = call("music", ACTION_PLAYLISTS)

    /** Открыть плейлист [id] ([url] — его адрес, если известен): переход делает сама страница, музыка не прерывается. */
    fun openPlaylist(id: String, url: String) = call("music", ACTION_PLAYLIST, id, url)

    /** Вернуть страницу на главную музыки («Мои треки»). */
    fun musicHome() = call("music", ACTION_HOME)

    /** Поиск музыки (4.79): страница ищет [query] своим поиском; результаты приходят обычными данными музыки. */
    fun musicSearch(query: String) = call("music", ACTION_SEARCH, "", query)

    /**
     * Догрузить: [target] — «my» (по умолчанию), «playlist» (треки открытого плейлиста), «search» (результаты поиска)
     * или «playlists» (список плейлистов).
     */
    fun musicMore(target: String) = call("music", ACTION_MORE, target)

    /** Свои списки друзей и сообществ: [ACTION_MORE] или [ACTION_SYNC]. */
    fun people(action: String) = call("people", action)

    /**
     * Свои альбомы и фото (5.00.12): "albums" - альбомы владельца [owner] с [offset]; "photos" - фото альбома [album]
     * (пусто - все фото); "all" - все адреса альбома для сохранения, [offset] тогда - номер задания.
     */
    fun photos(action: String, owner: Long, album: String, offset: String) = call("photos", action, owner.toString(), album, offset)

    /** Свой каталог VK Видео: [ACTION_MORE] или [ACTION_SYNC]. */
    fun vkvideo(action: String) = call("vkvideo", action)

    /** Все качества файла ролика каталога [id] (ответ — сообщение type = "videofiles"). */
    fun vkvideoFiles(id: String) = call("vkvideo", "files", id)

    /** Лента клипов для своего проигрывателя: «more», «sync», «silence» (страница молчит), «release». */
    fun clips(action: String) = call("clips", action)

    /** «Нравится» клипу [id] («владелец_номер»): поставить или снять; итог — сообщение «clipLike» (4.0). */
    fun clipsLike(id: String, on: Boolean) = call("clips", "like", id + ":" + if (on) "1" else "0")

    /** Вкладка раздела клипов [index] (нажимает её страница; лента придёт заново). */
    fun clipsTab(index: Int) = call("clips", "tab", index.toString())

    /** Свой экран страницы человека/сообщества: [ACTION_MORE] или [ACTION_SYNC]. */
    fun owner(action: String) = call("owner", action)

    fun refreshOwner(operation: String, key: String) = call("owner", "refresh", operation, key)

    /** Лайк записи «владелец_номер» на своём экране страницы: поставить ([on]) или снять. Итог — сообщение type = "like". */
    fun ownerLike(postKey: String, on: Boolean) = call("owner", "like", postKey, if (on) "1" else "0")

    /** Треки записи: адреса потоков ([csv] — «владелец_номер_ключ» через запятую); итог — сообщение «postaudio». */
    fun ownerAudio(wanted: String, csv: String) = call("owner", "audio", wanted, csv)

    /** Файлы ролика из записи «владелец_номер» ([key] — ключ доступа), 4.14; итог — сообщение «postvideo». */
    fun ownerVideo(id: String, key: String) = call("owner", "video", id, key)

    /** Записи «владелец_номер», которые человек увидел на своём экране: страница выводит их и у себя (прочитанные). */
    fun ownerSeen(postKeys: List<String>) = call("owner", "seen", postKeys.joinToString(" "))

    /**
     * Комментарии к видео или клипу «владелец_номер» с [offset] (4.45); [parent] ≠ 0 — ветка ответов на этот комментарий
     * (4.46). Итог — сообщение «comments».
     */
    fun comments(id: String, offset: Int, parent: Long = 0) = call("comments", "get", id, offset.toString(), parent.toString())

    /** Написать комментарий (4.45), [replyTo] ≠ 0 — ответ (4.46); итог — «commentSent». Текст в журнал не идёт. */
    fun commentSend(id: String, text: String, replyTo: Long = 0) = call("comments", "send", id, text, replyTo.toString())

    /** Добавить трек «владелец_номер» к себе (4.45); итог — сообщение «musicAction». */
    fun musicAdd(trackId: String) = call("music", ACTION_ADD, trackId)

    /** Добавить к себе плейлист «владелец_номер» ([url] — его адрес, в нём ключ доступа); итог — «musicAction». */
    fun musicFollow(playlistId: String, url: String) = call("music", ACTION_FOLLOW, playlistId, url)

    private fun call(function: String, vararg args: String) = send(function, args, retried = false)

    private fun send(function: String, args: Array<out String>, retried: Boolean) {
        // Ответ на команду может совпасть с прежним сообщением — он всё равно должен дойти.
        inbox.forgetLast()
        val list = args.joinToString(",") { JSONObject.quote(it) }
        val view = webView
        if (view == null) {
            AppLog.w(LogTag.BRIDGE, "команда странице не отправлена: окно не готово", "команда" to describe(function, args))
            return
        }
        // Ответ на команду: «null» — на странице нет скрипта приложения (страница ещё грузится или скрипт не встал), и
        // команда не выполнилась; иначе — то, что вернула функция (true/false).
        view.evaluateJavascript("(window.__vkLite ? (window.__vkLite.protocol===1 && typeof window.__vkLite.$function==='function' ? window.__vkLite.$function($list) : '__unsupported__') : '__no_script__')") { result ->
            when {
                // Страница ещё грузится (отчёт 3.64: свой экран открыт сразу при запуске) — повтор, когда скрипт встанет.
                result == "\"__no_script__\"" && !retried && view.progress < 100 && args.firstOrNull() in RETRIED_ACTIONS -> {
                    AppLog.d(LogTag.BRIDGE, "страница ещё грузится — команда повторится", "команда" to describe(function, args))
                    view.postDelayed({ if (webView === view) send(function, args, retried = true) }, NO_SCRIPT_RETRY_MS)
                }
                result == "\"__no_script__\"" ->
                    AppLog.w(LogTag.BRIDGE, "команда не выполнена: на странице нет скрипта приложения", "команда" to describe(function, args), "страница" to maskedUrl(view.url))
                result == "\"__unsupported__\"" ->
                    AppLog.w(LogTag.BRIDGE, "несовместимый контракт или отсутствует функция страницы", "команда" to describe(function, args))
                function == "music" && result == "false" ->
                    AppLog.w(LogTag.BRIDGE, "команда музыки вернула false", "команда" to describe(function, args))
                else -> AppLog.d(LogTag.BRIDGE, "команда выполнена", "команда" to describe(function, args), "ответ" to result)
            }
        }
    }

    /** Адрес для журнала: сервер и путь, номера скрыты, без параметров. */
    private fun maskedUrl(url: String?): String? = url?.let {
        val uri = android.net.Uri.parse(it)
        uri.host.orEmpty() + uri.path.orEmpty().replace(Regex("\\d{3,}"), "#")
    }

    /** Команда для журнала: имя и действие без номеров треков и текстов. */
    private fun describe(function: String, args: Array<out String>): String =
        function + if (function == "music" && args.isNotEmpty()) ":" + args[0] else ""

    companion object {
        private const val NO_SCRIPT_RETRY_MS = 1_500L
        /** Догрузить следующую порцию. */
        const val ACTION_MORE = "more"
        /** Прислать данные заново (свой экран открылся снова). */
        const val ACTION_SYNC = "sync"
        const val ACTION_PLAY = "play"
        const val ACTION_PLAYLISTS = "playlists"
        const val ACTION_PLAYLIST = "playlist"
        const val ACTION_HOME = "home"
        const val ACTION_SEARCH = "search"

        /** Команды, которые повторяются, если страница ещё грузится: данные экрана и поиск (4.79). */
        private val RETRIED_ACTIONS = setOf(ACTION_SYNC, ACTION_SEARCH)
        const val ACTION_ADD = "add"
        const val ACTION_FOLLOW = "follow"

        private const val TAG = "PageBridge"
    }
}
