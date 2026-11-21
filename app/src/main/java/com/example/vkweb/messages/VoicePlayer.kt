package com.example.vkweb.messages

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.View
import com.example.vkweb.R
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
    private var player: MediaPlayer? = null
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
        val p = player ?: return
        if (state.loading || state.failed) return
        runCatching { p.seekTo(ms.coerceAtLeast(0)) }
        state = state.copy(positionMs = ms.coerceAtLeast(0))
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
            player?.let { p -> if (state.playing) applySpeed(p) }
        }

    /** Скорость, уже выставленная текущему проигрывателю: повторный вызов дорог (до трети секунды на главном потоке). */
    private var appliedSpeed = 1f

    private fun applySpeed(p: MediaPlayer) {
        if (speed == appliedSpeed) return
        runCatching { p.playbackParams = android.media.PlaybackParams().setSpeed(speed) }
            .onSuccess { appliedSpeed = speed }
            .onFailure { AppLog.w(LogTag.MESSAGES, "скорость не применилась", "скорость" to speed, "ошибка" to it.javaClass.simpleName) }
    }

    /** Звук отняли на время (звонок, навигатор): по возвращении фокуса — продолжить (4.0; раньше оставалось на паузе). */
    private var pausedByFocus = false
    private var audio: AudioManager? = null
    private var focus: AudioFocusRequest? = null

    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            state = state.copy(positionMs = runCatching { p.currentPosition }.getOrDefault(state.positionMs))
            publish()
            if (state.playing) main.postDelayed(this, TICK_MS)
        }
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
        val p = player
        if (target == url && p != null && !state.loading && !state.failed) {
            if (state.playing) pause() else resume()
            return
        }
        start(context.applicationContext, target, isMusic)
    }

    fun stop() {
        pausedByFocus = false
        main.removeCallbacks(tick)
        main.removeCallbacks(prepareWatchdog)
        runCatching { player?.release() }
        player = null
        url = null
        state = State()
        abandonFocus()
        publish()
    }

    private fun start(context: Context, target: String, isMusic: Boolean) {
        stop()
        url = target
        music = isMusic
        state = State(loading = true)
        publish()
        audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val p = MediaPlayer()
        player = p
        appliedSpeed = 1f
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val host = com.example.vkweb.config.SafeUrls.hostOf(target).orEmpty()
        // 4.88: адрес, который не отвечает, раньше «загружался» без конца — звука нет, ошибки нет. Теперь через
        // PREPARE_TIMEOUT_MS это ошибка: музыка из переписки запрашивает свежий адрес (с другого сервера звука VK).
        prepareWatchdog = Runnable {
            if (player === p && state.loading) {
                AppLog.w(LogTag.MESSAGES, if (music) "трек из переписки долго не начинается" else "голосовое долго не начинается",
                    "мс" to PREPARE_TIMEOUT_MS, "сервер" to host)
                fail()
            }
        }
        main.postDelayed(prepareWatchdog, PREPARE_TIMEOUT_MS)
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(contentType()).build(),
            )
            // Экран погас — процессор не засыпает посреди голосового (4.0; дальше блокировки держит служба плеера).
            p.setWakeMode(context, android.os.PowerManager.PARTIAL_WAKE_LOCK)
            p.setOnPreparedListener {
                if (player !== p) return@setOnPreparedListener
                main.removeCallbacks(prepareWatchdog)
                AppLog.d(LogTag.MESSAGES, if (music) "трек из переписки готов" else "голосовое готово",
                    "мс" to android.os.SystemClock.elapsedRealtime() - startedAt, "сервер" to host)
                requestFocus()
                p.start()
                applySpeed(p)
                state = State(playing = true, durationMs = p.duration)
                publish()
                main.postDelayed(tick, TICK_MS)
            }
            p.setOnCompletionListener {
                if (player !== p) return@setOnCompletionListener
                // Трек доиграл — следующий трек очереди сам остановит этот и включит свой (иначе «остановка» между
                // треками читалась бы как конец музыки).
                val next = if (music) onMusicCompleted else null
                if (next != null) next() else stop()
            }
            p.setOnErrorListener { _, what, extra ->
                AppLog.w(LogTag.MESSAGES, if (music) "трек из переписки не проигрался" else "голосовое не проигралось", "код" to what, "доп" to extra)
                if (player === p) fail()
                true
            }
            // Трек из кэша на Android 10+ — content:// из хранилища (4.97): его открывают через ContentResolver.
            if (target.startsWith("content://")) p.setDataSource(context, android.net.Uri.parse(target)) else p.setDataSource(target)
            p.prepareAsync()
        } catch (e: IOException) {
            AppLog.w(LogTag.MESSAGES, "голосовое не открылось", "ошибка" to e.javaClass.simpleName)
            fail()
        } catch (e: IllegalStateException) {
            fail()
        }
    }

    private fun fail() {
        main.removeCallbacks(tick)
        main.removeCallbacks(prepareWatchdog)
        runCatching { player?.release() }
        player = null
        state = State(failed = true)
        abandonFocus()
        publish()
    }

    private fun pause() {
        runCatching { player?.pause() }
        main.removeCallbacks(tick)
        state = state.copy(playing = false)
        publish()
    }

    private fun resume() {
        pausedByFocus = false
        requestFocus()
        runCatching { player?.start() }
        player?.let { applySpeed(it) }
        state = state.copy(playing = true)
        publish()
        main.postDelayed(tick, TICK_MS)
    }

    private fun requestFocus() {
        val manager = audio ?: return
        abandonFocus() // продолжение после паузы запрашивает фокус заново: прежний запрос не должен остаться висеть
        val request = AudioFocusRequest.Builder(if (music) AudioManager.AUDIOFOCUS_GAIN else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(contentType()).build())
            .setOnAudioFocusChangeListener { change ->
                main.post {
                    when (change) {
                        // Позвонили или включили другое — не звучать поверх. На время (звонок) — продолжим сами.
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (state.playing) { pause(); pausedByFocus = true }
                        AudioManager.AUDIOFOCUS_LOSS -> { if (state.playing) pause(); pausedByFocus = false }
                        AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus && player != null && !state.playing) resume()
                    }
                }
            }
            .build()
        focus = request
        manager.requestAudioFocus(request)
    }

    private fun contentType() = if (music) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH

    private fun abandonFocus() {
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
