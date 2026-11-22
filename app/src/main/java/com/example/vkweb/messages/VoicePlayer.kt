package com.example.vkweb.messages

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import com.example.vkweb.R
import com.example.vkweb.config.SafeUrls
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import java.io.IOException
import java.lang.ref.WeakReference

/**
 * Проигрыватель голосовых сообщений и музыки из переписки (3.55: трек играет по адресу потока из ответа API — без
 * страницы и перехода в раздел музыки): одно играет за раз — голосовое останавливает трек и наоборот, остальные карточки показывают покой. Звук берётся прямо из записи
 * (https). На время голосового запрашивается краткий звуковой фокус: музыка на странице ставится на паузу системой.
 * Играет только пока открыт экран переписки ([stop] вызывает экран, когда уходит из виду).
 */
object VoicePlayer {

    /** Что показывать карточке: идёт ли звук, грузится ли, сколько проиграно, не вышло ли. */
    data class State(
        val playing: Boolean = false,
        val loading: Boolean = false,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
        val failed: Boolean = false,
    )

    private val main = Handler(Looper.getMainLooper())
    private val media by lazy {
        Handler(HandlerThread("voice-playback").apply { start() }.looper)
    }

    /** Сторож подготовки текущего адреса ([start]); пустой — до первого запуска. */
    private var prepareWatchdog: Runnable = Runnable {}
    /** Наблюдатель за состоянием: хранится в самой карточке, а здесь — только слабая ссылка на него. */
    private class Observer(val url: String, val onState: (State) -> Unit)

    /**
     * Прокрутка создаёт и теряет карточки, поэтому их нельзя держать сильной ссылкой (в замыкании — вся разметка и экран).
     * Раньше карточка была ключом WeakHashMap, а замыкание-значение ссылалось на неё же — запись не собиралась, и экран
     * переписки жил, пока жил процесс.
     */
    private val observers = ArrayList<WeakReference<Observer>>()
    private class Playback(main: Handler, worker: Handler) {
        val tasks = VoicePlaybackTasks({ worker.post(it) }, { main.post(it) })
        // Только поток voice-playback, включая создание, события MediaPlayer и release.
        var player: MediaPlayer? = null
        var appliedSpeed = 1f
    }

    private var playback: Playback? = null
    private var positionRevision = 0L
    private var url: String? = null
    /** Играет трек (музыка), а не голосовое: другой вид звука и постоянный, а не краткий звуковой фокус. */
    private var music = false

    /** Что играет сейчас (адрес) — или null. */
    val currentUrl: String? get() = url

    /** Играет (или стоит на паузе) трек, а не голосовое. */
    val isMusic: Boolean get() = music && url != null

    /** Состояние звучащего сейчас. */
    val currentState: State get() = state

    /**
     * Слушатель трека ([com.example.vkweb.media.ChatMusic]): состояние при каждом изменении и сигнал «доиграл до конца»
     * (следующий трек очереди). Голосовых не касается.
     */
    var musicListener: ((State) -> Unit)? = null
    var onMusicCompleted: (() -> Unit)? = null

    /** Перемотать звучащее на [ms]. */
    fun seekTo(ms: Int) {
        val active = playback ?: return
        if (state.loading || state.failed) return
        val position = ms.coerceAtLeast(0)
        positionRevision++
        active.tasks.command { runCatching { active.player?.seekTo(position) } }
        state = state.copy(positionMs = position)
        publish()
    }

    /** Остановить голосовое (трек из переписки при этом может играть дальше — в фоне, как музыка). */
    fun stopVoice() {
        if (!music) stop()
    }
    private var state = State()

    /**
     * Скорость (4.0; голосовые — 1,5× и 2×). Задаётся до запуска и на ходу; на паузе только запоминается: у MediaPlayer
     * смена скорости на паузе сама запускает звук.
     */
    var speed: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            val active = playback ?: return
            if (state.playing) active.tasks.command { applySpeed(active, value) }
        }

    private fun applySpeed(active: Playback, value: Float) {
        val p = active.player ?: return
        if (value == active.appliedSpeed) return
        runCatching { p.playbackParams = android.media.PlaybackParams().setSpeed(value) }
            .onSuccess { active.appliedSpeed = value }
            .onFailure { AppLog.w(LogTag.MESSAGES, "скорость не применилась", "скорость" to value, "ошибка" to it.javaClass.simpleName) }
    }

    /** Звук отняли на время (звонок, навигатор): по возвращении фокуса — продолжить (4.0; раньше оставалось на паузе). */
    private var pausedByFocus = false
    private var audio: AudioManager? = null
    private var focus: AudioFocusRequest? = null
    private var focusRevision = 0L
    private var focusLossWhileStarting: Int? = null

    private val tick = object : Runnable {
        override fun run() {
            val active = playback ?: return
            val revision = positionRevision
            active.tasks.command {
                val position = runCatching { active.player?.currentPosition }.getOrNull()
                active.tasks.result {
                    if (position != null && revision == positionRevision) {
                        state = state.copy(positionMs = position)
                        publish()
                    }
                    if (playback === active && state.playing) scheduleTick()
                }
            }
        }
    }

    private fun scheduleTick() {
        main.removeCallbacks(tick)
        main.postDelayed(tick, TICK_MS)
    }

    /** Карточка [owner] голосового [forUrl] следит за состоянием; сразу получает текущее. */
    fun observe(owner: View, forUrl: String, callback: (State) -> Unit) {
        val observer = Observer(forUrl, callback)
        owner.setTag(R.id.voice_observer, observer) // живёт, пока жива карточка
        // Мёртвые ссылки убираются и здесь: если голосовое не играют, publish() не вызывается, а карточки создаются при прокрутке.
        if (observers.size >= PRUNE_AT) observers.removeAll { it.get() == null }
        observers += WeakReference(observer)
        callback(stateFor(forUrl))
    }

    fun stateFor(forUrl: String): State = if (forUrl == url) state else State()

    /** Нажатие на кнопку карточки: начать, поставить на паузу или продолжить. */
    fun toggle(context: Context, target: String, isMusic: Boolean = false) {
        pausedByFocus = false
        if (target == url && playback != null && !state.loading && !state.failed) {
            if (state.playing) pause() else resume()
            return
        }
        start(context.applicationContext, target, isMusic)
    }

    fun stop() {
        pausedByFocus = false
        closePlayback()
        url = null
        state = State()
        abandonFocus()
        publish()
    }

    private fun closePlayback() {
        main.removeCallbacks(tick)
        main.removeCallbacks(prepareWatchdog)
        val active = playback ?: return
        playback = null
        active.tasks.close {
            val p = active.player ?: return@close
            active.player = null
            val startedAt = SystemClock.elapsedRealtime()
            runCatching { p.release() }
                .onFailure { AppLog.w(LogTag.MEDIA, "ошибка освобождения аудиоплеера", "тип" to it.javaClass.simpleName) }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed >= 100) AppLog.d(LogTag.MEDIA, "аудиоплеер освобождён в фоне", "мс" to elapsed)
        }
    }

    private fun start(context: Context, target: String, isMusic: Boolean) {
        stop()
        url = target
        music = isMusic
        state = State(loading = true)
        publish()
        audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val active = Playback(main, media)
        playback = active
        val startedAt = SystemClock.elapsedRealtime()
        val host = SafeUrls.hostOf(target).orEmpty()
        // 4.88: адрес, который не отвечает, раньше «загружался» без конца — звука нет, ошибки нет. Теперь через
        // PREPARE_TIMEOUT_MS это ошибка: музыка из переписки запрашивает свежий адрес (с другого сервера звука VK).
        prepareWatchdog = Runnable {
            if (playback === active && state.loading) {
                AppLog.w(LogTag.MESSAGES, if (music) "трек из переписки долго не начинается" else "голосовое долго не начинается",
                    "мс" to PREPARE_TIMEOUT_MS, "сервер" to host)
                fail()
            }
        }
        main.postDelayed(prepareWatchdog, PREPARE_TIMEOUT_MS)
        active.tasks.command { prepare(active, context, target, isMusic, host, startedAt) }
    }

    private fun prepare(active: Playback, context: Context, target: String, isMusic: Boolean, host: String, startedAt: Long) {
        try {
            val p = MediaPlayer()
            active.player = p
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(contentType(isMusic)).build(),
            )
            // Экран погас — процессор не засыпает посреди голосового (4.0; дальше блокировки держит служба плеера).
            p.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            p.setOnPreparedListener {
                active.tasks.result {
                    requestFocus()
                    startPrepared(active, host, startedAt, isMusic)
                }
            }
            p.setOnCompletionListener {
                active.tasks.result {
                    // Следующий трек сам остановит этот, без промежуточного сигнала об окончании очереди.
                    val next = if (isMusic) onMusicCompleted else null
                    if (next != null) next() else stop()
                }
            }
            p.setOnErrorListener { _, what, extra ->
                active.tasks.result {
                    AppLog.w(LogTag.MESSAGES, if (isMusic) "трек из переписки не проигрался" else "голосовое не проигралось", "код" to what, "доп" to extra)
                    fail()
                }
                true
            }
            // Трек из кэша на Android 10+ — content:// из хранилища (4.97): его открывают через ContentResolver.
            if (target.startsWith("content://")) p.setDataSource(context, android.net.Uri.parse(target)) else p.setDataSource(target)
            p.prepareAsync()
        } catch (e: IOException) {
            reportFailure(active, e)
        } catch (e: IllegalStateException) {
            reportFailure(active, e)
        } catch (e: IllegalArgumentException) {
            reportFailure(active, e)
        } catch (e: SecurityException) {
            reportFailure(active, e)
        }
    }

    private fun startPrepared(active: Playback, host: String, startedAt: Long, isMusic: Boolean) {
        val initialSpeed = speed
        active.tasks.command {
            val p = active.player ?: return@command
            val duration = try {
                p.start()
                applySpeed(active, initialSpeed)
                p.duration
            } catch (e: IllegalStateException) {
                reportFailure(active, e)
                return@command
            }
            active.tasks.result {
                main.removeCallbacks(prepareWatchdog)
                AppLog.d(LogTag.MESSAGES, if (isMusic) "трек из переписки готов" else "голосовое готово",
                    "мс" to SystemClock.elapsedRealtime() - startedAt, "сервер" to host)
                state = State(playing = true, durationMs = duration)
                val lostFocus = focusLossWhileStarting
                focusLossWhileStarting = null
                if (lostFocus != null) {
                    onFocusChange(lostFocus)
                } else {
                    val currentSpeed = speed
                    if (currentSpeed != initialSpeed) active.tasks.command { applySpeed(active, currentSpeed) }
                    publish()
                    if (playback === active) scheduleTick()
                }
            }
        }
    }

    private fun reportFailure(active: Playback, error: Throwable) {
        active.tasks.result {
            AppLog.w(LogTag.MESSAGES, "аудио не открылось", "ошибка" to error.javaClass.simpleName)
            fail()
        }
    }

    private fun fail() {
        closePlayback()
        state = State(failed = true)
        abandonFocus()
        publish()
    }

    private fun pause() {
        val active = playback ?: return
        active.tasks.command { runCatching { active.player?.pause() } }
        main.removeCallbacks(tick)
        state = state.copy(playing = false)
        publish()
    }

    private fun resume() {
        val active = playback ?: return
        pausedByFocus = false
        requestFocus()
        val currentSpeed = speed
        active.tasks.command {
            runCatching { active.player?.start() }
                .onSuccess { applySpeed(active, currentSpeed) }
                .onFailure { reportFailure(active, it) }
        }
        state = state.copy(playing = true)
        publish()
        if (playback === active) scheduleTick()
    }

    private fun requestFocus() {
        val manager = audio ?: return
        abandonFocus() // продолжение после паузы запрашивает фокус заново: прежний запрос не должен остаться висеть
        val revision = focusRevision
        val request = AudioFocusRequest.Builder(if (music) AudioManager.AUDIOFOCUS_GAIN else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(contentType(music)).build())
            .setOnAudioFocusChangeListener { change ->
                main.post {
                    if (revision == focusRevision) onFocusChange(change)
                }
            }
            .build()
        focus = request
        val result = manager.requestAudioFocus(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            AppLog.w(LogTag.MEDIA, "аудиофокус не получен", "код" to result, "музыка" to music)
        }
    }

    private fun onFocusChange(change: Int) {
        if (state.loading) {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> focusLossWhileStarting = change
                AudioManager.AUDIOFOCUS_GAIN -> focusLossWhileStarting = null
            }
            return
        }
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (state.playing) { pausedByFocus = true; pause() }
            AudioManager.AUDIOFOCUS_LOSS -> { pausedByFocus = false; if (state.playing) pause() }
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus && playback != null && !state.playing) resume()
        }
    }

    private fun contentType(isMusic: Boolean) = if (isMusic) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH

    private fun abandonFocus() {
        focusRevision++
        focusLossWhileStarting = null
        focus?.let { audio?.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun publish() {
        if (music) musicListener?.invoke(state)
        observers.removeAll { it.get() == null }
        observers.toList().forEach { ref -> ref.get()?.let { it.onState(stateFor(it.url)) } }
    }

    private const val TICK_MS = 250L

    /** Адрес не начал звучать за это время — ошибка (4.88), а не бесконечная загрузка. */
    private const val PREPARE_TIMEOUT_MS = 10_000L
    private const val PRUNE_AT = 48
}
