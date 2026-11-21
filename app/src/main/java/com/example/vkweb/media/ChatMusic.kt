package com.example.vkweb.media

import android.content.Context
import androidx.annotation.MainThread
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.messages.VoicePlayer

/**
 * Музыка из переписки с теми же возможностями, что в разделе «Музыка» (3.60): очередь из всех треков переписки
 * («вперёд», «назад», следующий — сам, когда трек доиграл), перемотка, уведомление с кнопками и экран блокировки
 * ([MusicService]), кнопки гарнитуры, игра в фоне (настройка «Фоновое воспроизведение»).
 *
 * Звук играет само приложение по адресам потоков, которые заранее получило окно чтения ([VoicePlayer] в режиме музыки):
 * следующий трек не требует страницы, поэтому играет и после ухода из переписки. Пока музыка звучит, команды
 * уведомления приходят сюда ([PlaybackHub.takeOver]); музыка страницы при этом ставится на паузу.
 */
@MainThread
object ChatMusic : PlaybackHub.Target {

    /** Что показывать полосе в переписке: трек, его место в очереди и состояние проигрывателя. */
    data class Snapshot(
        val track: ChatQueue.Track?,
        val index: Int,
        val size: Int,
        val state: VoicePlayer.State,
    ) {
        val active: Boolean get() = track != null
    }

    // Только applicationContext (play): контекст приложения живёт столько же, сколько процесс, — не утечка (Lint StaticFieldLeak).
    @android.annotation.SuppressLint("StaticFieldLeak")
    private var app: Context? = null
    private const val SPEED_PREFS = "messages_ui"
    private const val SPEED_KEY = "voice_speed"
    private var takenAt = 0L
    private const val TAKE_OVER_GRACE_MS = 3_000L
    private var queue: ChatQueue? = null
    private val listeners = LinkedHashSet<(Snapshot) -> Unit>()

    /** Свои переключения (следующий трек, свежий адрес): промежуточная «остановка» проигрывателя — не конец музыки. */
    private var switching = false

    /** Последнее, что отдано уведомлению: обновлять его на каждом шаге позиции незачем. */
    private var publishedPlaying: Boolean? = null
    private var publishedTrack: String? = null

    /**
     * Трек не проигрался. Экран переписки (если открыт) может достать свежий адрес и вызвать [replaceUrl]; без экрана —
     * трек пропускается. Возврат true — экран взялся.
     */
    var onTrackFailed: ((ChatQueue.Track) -> Boolean)? = null

    val isActive: Boolean get() = queue?.current != null
    val current: ChatQueue.Track? get() = queue?.current

    fun snapshot(): Snapshot {
        val q = queue
        return Snapshot(q?.current, q?.index ?: -1, q?.tracks?.size ?: 0, if (VoicePlayer.isMusic) VoicePlayer.currentState else VoicePlayer.State())
    }

    fun addListener(listener: (Snapshot) -> Unit) {
        listeners += listener
        listener(snapshot())
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners -= listener
    }

    /** Очередь доиграла до конца сама (не остановлена): номер последнего трека. Сбрасывается каждым [play]. */
    private var onFinished: ((String) -> Unit)? = null

    /**
     * Играть очередь [tracks] с трека [startIndex]. Трек, который есть в кэше музыки, играет из файла (4.95) — без сети и
     * без повторной загрузки. [onFinished] — очередь доиграла (например, продолжить страницей).
     */
    fun play(context: Context, tracks: List<ChatQueue.Track>, startIndex: Int, onFinished: ((String) -> Unit)? = null) {
        if (tracks.isEmpty()) return
        app = context.applicationContext
        voiceSpeed = PlaybackSpeed.valid(prefs(context).getFloat(SPEED_KEY, 1f))
        val local = InAppQueue.withCached(tracks, MusicCache::uriFor)
        val fromCache = local.count { it.url.startsWith("file:") || it.url.startsWith("content:") }
        queue = ChatQueue(local, startIndex)
        this.onFinished = onFinished
        if (fromCache > 0) AppLog.i(LogTag.MEDIA, "музыка: треки из кэша", "из кэша" to fromCache, "всего" to local.size)
        takenAt = android.os.SystemClock.elapsedRealtime()
        PlaybackHub.takeOver(this)
        VoicePlayer.musicListener = ::onPlayerState
        VoicePlayer.onMusicCompleted = { next() }
        AppLog.i(LogTag.MEDIA, "музыка из переписки: очередь", "треков" to tracks.size, "с" to startIndex + 1)
        playCurrent()
    }

    // ------------------------------------------------------------ скорость голосовых (4.0)

    /** Скорость голосовых: одна на все, запоминается между запусками. Музыка — всегда 1×. */
    var voiceSpeed: Float = 1f
        private set

    private fun prefs(context: Context) = context.getSharedPreferences(SPEED_PREFS, Context.MODE_PRIVATE)

    /** Следующая скорость голосовых (1× → 1,5× → 2×); играющее голосовое ускоряется сразу. */
    fun cycleVoiceSpeed() {
        val context = app ?: return
        voiceSpeed = PlaybackSpeed.next(voiceSpeed)
        prefs(context).edit().putFloat(SPEED_KEY, voiceSpeed).apply()
        AppLog.i(LogTag.MEDIA, "скорость голосовых", "скорость" to voiceSpeed)
        if (queue?.current?.voice == true) VoicePlayer.speed = voiceSpeed
        notifyListeners()
    }

    private fun playCurrent() {
        val track = queue?.current ?: return stop()
        val context = app ?: return
        VoicePlayer.speed = if (track.voice) voiceSpeed else 1f
        switching = true
        try {
            // Тот же адрес — toggle поставил бы на паузу: сначала остановка.
            if (VoicePlayer.currentUrl == track.url) VoicePlayer.stop()
            VoicePlayer.toggle(context, track.url, isMusic = true)
        } finally {
            switching = false
        }
        notifyListeners()
        publish(force = true)
    }

    fun toggle() {
        val track = queue?.current ?: return
        val context = app ?: return
        if (VoicePlayer.currentUrl != track.url) {
            playCurrent()
            return
        }
        VoicePlayer.toggle(context, track.url, isMusic = true)
    }

    fun next() {
        val q = queue ?: return
        if (q.next() == null) {
            AppLog.i(LogTag.MEDIA, "музыка из переписки: очередь доиграла")
            finish(q.current?.id.orEmpty())
            return
        }
        playCurrent()
    }

    fun previous() {
        val q = queue ?: return
        val position = VoicePlayer.currentState.positionMs
        if (q.previousRestarts(position)) {
            seekTo(0)
            return
        }
        q.previous()
        playCurrent()
    }

    fun seekTo(ms: Int) {
        if (!isActive) return
        VoicePlayer.seekTo(ms)
        publish(force = true)
    }

    /** Свежий адрес трека [id] (прежний не проигрался): играть его. */
    fun replaceUrl(id: String, url: String) {
        val q = queue ?: return
        if (!q.replaceUrl(id, url)) return
        if (q.current?.id == id) playCurrent()
    }

    /** Трек [id] так и не проигрался: убрать его, играть следующий. */
    fun skipFailed(id: String) {
        val q = queue ?: return
        if (q.current?.id != id) return
        if (q.dropCurrent() == null) finish(id) else playCurrent()
    }

    /** Очередь кончилась сама: остановиться и сообщить [onFinished] (после остановки — он может включить новую). */
    private fun finish(lastId: String) {
        val callback = onFinished
        stop()
        callback?.invoke(lastId)
    }

    /** Закончить музыку из переписки: звук, уведомление, управление кнопками. */
    fun stop() {
        val wasActive = queue != null
        silence()
        if (wasActive) app?.let { MusicService.stop(it) }
        clearPublished()
    }

    /** Музыку включили на странице (раздел «Музыка»): своя замолкает, уведомление дальше ведёт страница. */
    fun yieldToPage() {
        if (!isActive) return
        // Сразу после того как мы поставили страницу на паузу, от неё ещё могут прийти «играет» — это не новое включение.
        if (android.os.SystemClock.elapsedRealtime() - takenAt < TAKE_OVER_GRACE_MS) return
        AppLog.i(LogTag.MEDIA, "музыка из переписки остановлена: заиграла музыка страницы")
        // Уведомление не убираем: его дальше ведёт страница.
        silence()
        clearPublished()
    }

    /** Общее у [stop] и [yieldToPage] (4.66): очередь забыта, свой звук остановлен, проигрыватель отдан. */
    private fun silence() {
        queue = null
        onFinished = null
        VoicePlayer.musicListener = null
        VoicePlayer.onMusicCompleted = null
        if (VoicePlayer.isMusic) {
            switching = true
            try { VoicePlayer.stop() } finally { switching = false }
        }
        PlaybackHub.release(this)
    }

    /** Показанное о треке забыто — подписчики (полоса в переписке, уведомление) узнают сразу. */
    private fun clearPublished() {
        publishedPlaying = null
        publishedTrack = null
        notifyListeners()
    }

    private fun onPlayerState(state: VoicePlayer.State) {
        val track = queue?.current ?: return
        if (state.failed) {
            AppLog.w(LogTag.MEDIA, "трек из переписки не проигрался", "номер" to track.id.ifEmpty { "нет" })
            val handled = onTrackFailed?.invoke(track) == true
            if (!handled) skipFailed(track.id)
            notifyListeners()
            return
        }
        // Проигрыватель остановлен не нами (включили голосовое): музыка из переписки закончилась.
        if (!switching && !state.playing && !state.loading && state.positionMs == 0 && VoicePlayer.currentUrl == null) {
            stop()
            return
        }
        notifyListeners()
        publish(force = false)
    }

    // ------------------------------------------------------------ команды уведомления, гарнитуры, экрана блокировки

    override fun onPlayerCommand(command: PlayerCommand) {
        when (command) {
            PlayerCommand.PLAY -> if (!VoicePlayer.currentState.playing) toggle()
            PlayerCommand.PAUSE -> if (VoicePlayer.currentState.playing) toggle()
            PlayerCommand.NEXT -> next()
            PlayerCommand.PREVIOUS -> previous()
        }
    }

    override fun onSeekRequested(seconds: Double) = seekTo((seconds * 1000).toInt())

    // ------------------------------------------------------------

    /**
     * Уведомление с кнопками в шторке и на экране блокировки — всегда, пока звучит музыка или голосовое из переписки
     * (3.71; раньше — только при включённом «Фоновом воспроизведении»). Сама настройка решает лишь, играть ли после ухода
     * с экрана переписки (MessagesActivity.onStop).
     */
    private fun publish(force: Boolean) {
        val context = app ?: return
        val track = queue?.current ?: return
        val state = VoicePlayer.currentState
        val playing = state.playing || state.loading
        if (!force && publishedPlaying == playing && publishedTrack == track.id + track.url) return
        publishedPlaying = playing
        publishedTrack = track.id + track.url
        val duration = if (state.durationMs > 0) state.durationMs / 1000.0 else track.durationSec.toDouble()
        MusicService.update(
            context,
            MediaState(
                playing = playing,
                title = track.title,
                artist = track.artist,
                position = state.positionMs / 1000.0,
                duration = duration,
                cover = MediaState.coverOrEmpty(track.cover),
                fromChat = true,
            ),
        )
    }

    private fun notifyListeners() {
        val s = snapshot()
        listeners.toList().forEach { it(s) }
    }
}
