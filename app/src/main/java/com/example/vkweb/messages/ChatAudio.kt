package com.example.vkweb.messages

import android.app.Activity
import android.os.Handler
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.media.ChatMusic
import com.example.vkweb.media.ChatQueue

/**
 * Музыка и голосовые из переписки (с 4.72 — отдельно от MessagesActivity).
 *
 *  - Трек из сообщения: очередь — все треки переписки, адреса потоков одним запросом к странице; играет само приложение
 *    ([ChatMusic]). Нет адреса нажатому — страница нажимает вложение сама (запасной путь, полоса [ChatAudioBar]); не
 *    вышло и так или страница молчит [TRACK_TIMEOUT_MS] — раздел музыки, как раньше.
 *  - Трек не проигрался — один раз свежий адрес по номеру (отчёт 3.58), потом пропуск.
 *  - Голосовые (3.71) — как музыка: очередь из всех голосовых переписки, перемотка, уведомление.
 */
class ChatAudio(
    private val activity: Activity,
    private val handler: Handler,
    private val links: MessageLinks,
    /** Полоса трека над полем ввода; null — экран ещё не построен. */
    private val audioBar: () -> ChatAudioBar?,
    /** Сообщения открытой переписки (с сервера), по порядку. */
    private val history: () -> List<ChatMessage>,
    /** Название открытой переписки — подпись голосовых в уведомлении. */
    private val chatTitle: () -> String,
    /** С перепиской работает страница VK вместо своего экрана: свои запросы ей не шлём. */
    private val onSite: () -> Boolean,
    /** Мост к странице работает. */
    private val channelReady: () -> Boolean,
    private val requestQueue: (cards: List<ChatCard>, wantedId: String, wantedTitle: String) -> Unit,
    private val requestFreshUrl: (trackId: String, key: String, title: String) -> Unit,
    /** Страница сама нажимает вложение-трек (запасной путь). */
    private val pressOnPage: (trackId: String, title: String) -> Unit,
    /** Замолчать музыке, которую играет страница (запасной путь). */
    private val stopPageAudio: () -> Unit,
) {
    /** Трек, который попросили включить: если страница его не нашла, он включается в разделе музыки. */
    private var requestedTrack: ChatCard? = null

    /** Очередь (треки переписки), для которой запрошены адреса. */
    private var requestedQueue: List<ChatCard> = emptyList()

    /** Треки, которым уже запрашивали свежий адрес: второй раз — пропуск, а не бесконечные запросы. */
    private val freshTried = HashSet<String>()

    /** Номер трека, которому запрошен свежий адрес. */
    private var pendingFresh: String? = null

    /** Страница не ответила ни адресом звука, ни нажатием — трек открывается в разделе музыки, как раньше. */
    private val trackTimeout = Runnable {
        val card = requestedTrack ?: return@Runnable
        requestedTrack = null
        if (audioBar()?.isShown == true) return@Runnable
        AppLog.w(LogTag.MESSAGES, "страница не ответила на трек из переписки, открывается раздел музыки")
        links.openCard(card)
    }

    /**
     * Нажали вложение-трек. Как в обычном VK: играет прямо в переписке, без перехода в раздел музыки.
     * @return false — здесь не играем (нет моста или показан сайт): пусть откроется как обычная ссылка.
     */
    fun onTrackCard(card: ChatCard): Boolean {
        if (!channelReady() || onSite()) return false
        // Тот же трек уже звучит из переписки — нажатие ставит на паузу и продолжает, как в разделе «Музыка».
        val playing = ChatMusic.current
        if (playing != null && card.trackId.isNotEmpty() && playing.id == card.trackId) {
            ChatMusic.toggle()
            return true
        }
        // Очередь — все треки переписки: адреса потоков одним запросом (по номерам, нажатый без номера — поиском);
        // трек играет само приложение. Нет адреса — страница нажимает вложение; не вышло и так — раздел музыки.
        val queue = audioCards()
        AppLog.i(LogTag.MESSAGES, "музыка из сообщения: запрошены адреса", "номер" to card.trackId.ifEmpty { "нет" }, "треков в переписке" to queue.size)
        requestedTrack = card
        requestedQueue = queue.ifEmpty { listOf(card) }
        requestQueue(requestedQueue, card.trackId, card.title)
        handler.removeCallbacks(trackTimeout)
        handler.postDelayed(trackTimeout, TRACK_TIMEOUT_MS)
        return true
    }

    /** Адреса очереди пришли: играет приложение с нажатого трека; нажатому адреса нет — страница (как в 3.53). */
    fun onQueue(payload: ReaderPayload.AudioQueue) {
        val card = requestedTrack ?: return
        val cards = requestedQueue
        requestedTrack = null
        requestedQueue = emptyList()
        handler.removeCallbacks(trackTimeout)
        val byId = payload.items.associateBy { it.id }
        val tracks = ArrayList<ChatQueue.Track>()
        var start = -1
        cards.forEach { c ->
            val item = byId[c.trackId] ?: return@forEach
            if (c === card || (c.trackId.isNotEmpty() && c.trackId == card.trackId)) start = tracks.size
            val (artist, name) = ChatQueue.splitTitle(c.title)
            tracks += ChatQueue.Track(
                id = c.trackId, title = item.title.ifEmpty { name }, artist = item.artist.ifEmpty { artist },
                url = item.url, cover = c.cover, durationSec = item.durationSec,
            )
        }
        if (start < 0) {
            AppLog.w(LogTag.MESSAGES, "адреса звука нажатому треку нет, трек нажимает страница", "причина" to payload.reason, "адресов" to payload.items.size)
            playOnPage(card)
            return
        }
        AppLog.i(LogTag.MESSAGES, "трек из переписки играет приложение", "как нашли" to payload.how, "в очереди" to tracks.size, "номер в очереди" to start + 1)
        // Если до этого играла страница (запасной путь) — она замолкает.
        stopPageAudio()
        freshTried.clear()
        ChatMusic.play(activity, tracks, start)
    }

    /**
     * Голосовые переписки играют, как музыка из неё ([ChatMusic]): очередь — все голосовые переписки по порядку (после
     * нажатого сами играют следующие), полоса над полем ввода с перемоткой, уведомление с кнопками, экран блокировки,
     * гарнитура. Раньше голосовое играло только в пузыре, без уведомления и перемотки.
     */
    fun onVoiceClicked(voice: ChatVoice) {
        if (ChatMusic.current?.url == voice.url) {
            ChatMusic.toggle()
            return
        }
        val labels = ChatVoiceQueue.Labels(
            fromMe = activity.getString(R.string.voice_from_me),
            trackTitle = activity.getString(R.string.voice_track_title),
            chatTitle = chatTitle(),
        )
        // Очередь — все голосовые переписки по порядку (с 4.4 — ChatVoiceQueue, проверяется тестом).
        val (tracks, start) = ChatVoiceQueue.build(history(), voice, labels)
        stopPageAudio()
        AppLog.i(LogTag.MESSAGES, "голосовое играет как музыка", "в очереди" to tracks.size, "номер в очереди" to start + 1)
        ChatMusic.play(activity, tracks, start)
    }

    fun onVoiceSeek(voice: ChatVoice, ms: Int) {
        when {
            ChatMusic.current?.url == voice.url -> ChatMusic.seekTo(ms)
            VoicePlayer.currentUrl == voice.url -> VoicePlayer.seekTo(ms)
        }
    }

    /** Для [ChatMusic.onTrackFailed]: трек очереди не проигрался — сначала свежий адрес по номеру, потом пропуск. */
    fun onTrackFailed(track: ChatQueue.Track): Boolean {
        // У голосового свежего адреса по номеру трека нет — пропуск.
        if (track.id.isEmpty() || ChatVoiceQueue.isVoice(track.id) || !freshTried.add(track.id) || onSite()) return false
        AppLog.w(LogTag.MESSAGES, "трек из переписки не проигрался, запрошен свежий адрес")
        pendingFresh = track.id
        val card = audioCards().firstOrNull { it.trackId == track.id }
        requestFreshUrl(track.id, card?.trackKey.orEmpty(), card?.title ?: track.title)
        // Страница не ответила — трек пропускается, очередь не стоит.
        handler.postDelayed({ if (pendingFresh == track.id) { pendingFresh = null; ChatMusic.skipFailed(track.id) } }, TRACK_TIMEOUT_MS)
        return true
    }

    fun onAudioUrl(payload: ReaderPayload.AudioUrl) {
        val id = pendingFresh ?: return
        if (payload.id != id) return
        pendingFresh = null
        if (payload.ok) ChatMusic.replaceUrl(id, payload.url) else ChatMusic.skipFailed(id)
    }

    /** Состояние музыки, которую играет страница (запасной путь). */
    fun onPageAudio(audio: ReaderPayload.Audio) {
        val bar = audioBar() ?: return
        // Трек играет приложение, а страницу никто не просил: её события (в том числе «остановлено» в ответ на нашу же
        // остановку) не должны прятать полосу и управление звучащим треком (код ревью 3.59).
        if (ChatMusic.isActive && requestedTrack == null) return
        when (audio.state) {
            "notfound", "failed" -> {
                bar.hide()
                val card = requestedTrack ?: return
                requestedTrack = null
                // Вложения нет в вёрстке (сообщение далеко вверху) или страница его не включила — как раньше, разделом музыки.
                AppLog.w(LogTag.MESSAGES, "музыка не заиграла в переписке, открывается раздел музыки", "состояние" to audio.state, "подробности" to audio.detail)
                links.openCard(card)
            }
            else -> {
                if (audio.state == "playing") requestedTrack = null
                bar.update(audio)
            }
        }
    }

    /**
     * Экран ушёл: ответы на нажатия (адрес трека, свежий адрес), пришедшие после этого, не должны включать звук поверх
     * других приложений.
     */
    fun cancel() {
        pendingFresh = null
        requestedTrack = null
        handler.removeCallbacks(trackTimeout)
    }

    /** Запасной путь: страница мессенджера нажимает вложение-трек (нет в вёрстке — раздел музыки, [onPageAudio]). */
    private fun playOnPage(card: ChatCard) {
        if (card.trackId.isEmpty()) {
            links.openCard(card)
            return
        }
        requestedTrack = card
        ChatMusic.stop()
        VoicePlayer.stop()
        pressOnPage(card.trackId, card.title)
        handler.removeCallbacks(trackTimeout)
        handler.postDelayed(trackTimeout, TRACK_TIMEOUT_MS)
    }

    /** Треки переписки по порядку (сверху вниз), с пересланными; повторы одного трека — один раз. */
    private fun audioCards(): List<ChatCard> = history()
        .flatMap { m -> m.cards + m.forwards.flatMap { it.cards } }
        .filter { it.kind == "audio" }
        .distinctBy { it.trackId.ifEmpty { "t:" + it.title } }

    private companion object {
        const val TRACK_TIMEOUT_MS = 10_000L
    }
}
