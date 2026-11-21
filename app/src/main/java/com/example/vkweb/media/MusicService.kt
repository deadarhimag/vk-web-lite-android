package com.example.vkweb.media

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.settings.AppSettings

/**
 * Foreground-сервис воспроизведения: держит процесс живым, показывает медиа-уведомление
 * и принимает кнопки гарнитуры через [MediaSession]. Само аудио играет WebView в Activity.
 *
 * Пока звучит музыка (и несколько секунд между треками), сервис держит процессор и Wi-Fi ([PlaybackLocks]):
 * следующий трек и куски текущего страница VK догружает скриптом, и при выключенном экране без этого телефон
 * засыпал между треками — плейлист останавливался. На паузе блокировки сразу отпускаются: батарея не тратится.
 */
class MusicService : Service() {

    private lateinit var session: MediaSession
    private lateinit var notification: PlaybackNotification
    private lateinit var locks: PlaybackLocks
    private var state = MediaState.IDLE
    /** Когда пришло [state]: от этого момента экран блокировки сам досчитывает позицию. */
    private var stateAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val stopWhenIdle = Runnable { stopSelf() }
    private val stopWhenEnded = Runnable { stopSelf() }

    /** Наушники отключили: музыка не должна внезапно заиграть из динамика. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && state.playing) {
                AppLog.i(LogTag.MEDIA, "наушники отключены — пауза")
                PlaybackHub.send(PlayerCommand.PAUSE)
            }
        }
    }
    private var noisyRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        locks = PlaybackLocks(this)
        session = MediaSession(this, TAG).apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = PlaybackHub.send(PlayerCommand.PLAY)
                override fun onPause() = PlaybackHub.send(PlayerCommand.PAUSE)
                override fun onStop() = PlaybackHub.send(PlayerCommand.PAUSE)
                override fun onSkipToNext() = PlaybackHub.send(PlayerCommand.NEXT)
                override fun onSkipToPrevious() = PlaybackHub.send(PlayerCommand.PREVIOUS)
                override fun onSeekTo(pos: Long) = PlaybackHub.seek(pos / 1000.0)
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    if (action == CUSTOM_ACTION_CLOSE) closePlayer()
                }
            })
            isActive = true
        }
        notification = PlaybackNotification(this, session.sessionToken)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            publish()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_UPDATE -> {
                state = MediaState.readFrom(intent)
                stateAt = SystemClock.elapsedRealtime()
                if (state.ended) {
                    // Доиграло до конца: убираем плеер, но с небольшой задержкой, чтобы не мигать
                    // между треками плейлиста. Следующий трек отменит остановку. Блокировки на это время
                    // остаются: страница как раз загружает следующий трек.
                    handler.removeCallbacks(stopWhenIdle)
                    handler.removeCallbacks(stopWhenEnded)
                    handler.postDelayed(stopWhenEnded, ENDED_GRACE_MS)
                    // startForegroundService обязывает вызвать startForeground: иначе Android завершит приложение.
                    if (!isForeground) publish()
                    return START_NOT_STICKY
                }
                handler.removeCallbacks(stopWhenEnded)
            }
            ACTION_COMMAND -> intent.commandOrNull()?.let(PlaybackHub::send)
            ACTION_DISMISS -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CLOSE_PLAYER -> {
                closePlayer()
                return START_NOT_STICKY
            }
        }
        publish()
        return START_NOT_STICKY
    }

    /**
     * Крестик в уведомлении и на экране блокировки: закрыть плеер — пауза и убрать уведомление. Приложение остаётся как
     * было (раньше крестик ещё и закрывал приложение целиком: `finishAndRemoveTask`). Включить музыку заново можно в
     * приложении: сервис запустится снова.
     */
    private fun closePlayer() {
        PlaybackHub.send(PlayerCommand.PAUSE)
        stopSelf()
    }

    /** Приложение смахнули из недавних: WebView с музыкой уничтожен, сервис больше не нужен. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(stopWhenIdle)
        handler.removeCallbacks(stopWhenEnded)
        locks.release()
        setNoisyReceiver(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        session.release()
        super.onDestroy()
    }

    private var isForeground = false

    /** Обложка текущего трека (4.0): адрес и загруженная картинка; пока грузится — без неё. */
    private var coverUrl = ""
    private var coverBitmap: android.graphics.Bitmap? = null

    private fun loadCoverIfNeeded() {
        if (state.cover == coverUrl) return
        coverUrl = state.cover
        coverBitmap = null
        if (coverUrl.isEmpty()) return
        val url = coverUrl
        com.example.vkweb.offline.ImageCache.get(this).loadFull(url, COVER_PX) { bitmap ->
            // Пока грузилась, трек мог смениться, а сервис — остановиться.
            if (url != coverUrl || !isRunning || bitmap == null) return@loadFull
            coverBitmap = bitmap
            publish()
        }
    }

    private fun publish() {
        loadCoverIfNeeded()
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, state.artist)
        coverBitmap?.let { metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        // Нажатие на медиаплеер в шторке и на экране блокировки — туда, откуда играет.
        session.setSessionActivity(notification.openIntent(state.fromChat))
        if (state.duration > 0) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, (state.duration * 1000).toLong())
        session.setMetadata(metadata.build())
        val actions = if (state.duration > 0) SUPPORTED_ACTIONS or PlaybackState.ACTION_SEEK_TO else SUPPORTED_ACTIONS
        val position = if (state.duration > 0) (state.position * 1000).toLong() else PlaybackState.PLAYBACK_POSITION_UNKNOWN
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(
                        CUSTOM_ACTION_CLOSE, getString(R.string.action_close), android.R.drawable.ic_menu_close_clear_cancel
                    ).build()
                )
                // Позиция на момент события; дальше система досчитывает её сама (скорость 1 при игре, 0 на паузе).
                .setState(
                    if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    position,
                    if (state.playing) 1f else 0f,
                    if (stateAt > 0) stateAt else SystemClock.elapsedRealtime(),
                )
                .build()
        )
        // Обязателен при каждом запуске через startForegroundService, иначе Android завершит приложение.
        ServiceCompat.startForeground(
            this, PlaybackNotification.NOTIFICATION_ID, notification.build(state, coverBitmap), FOREGROUND_TYPE
        )
        isForeground = true
        handler.removeCallbacks(stopWhenIdle)
        if (state.playing) {
            locks.acquire()
            setNoisyReceiver(true)
        } else {
            // Пауза человеком: процессор и Wi-Fi больше не нужны.
            locks.release()
            setNoisyReceiver(false)
            handler.postDelayed(stopWhenIdle, AppSettings(this).pausedTimeoutMs)
        }
    }

    private fun setNoisyReceiver(enabled: Boolean) {
        if (enabled == noisyRegistered) return
        if (enabled) {
            ContextCompat.registerReceiver(
                this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } else {
            try { unregisterReceiver(noisyReceiver) } catch (e: IllegalArgumentException) { /* уже снят */ }
        }
        noisyRegistered = enabled
    }

    private fun Intent.commandOrNull(): PlayerCommand? =
        getStringExtra(EXTRA_COMMAND)?.let { name -> PlayerCommand.entries.firstOrNull { it.name == name } }

    companion object {
        private const val TAG = "MusicService"

        /** Сколько ждать следующий трек после конца текущего, прежде чем закрыть плеер в шторке. */
        private const val ENDED_GRACE_MS = 30_000L

        /** Обложка для системы: больше незачем (её всё равно уменьшают), меньше — мутно на экране блокировки. */
        private const val COVER_PX = 512

        const val CUSTOM_ACTION_CLOSE = "com.example.vkweb.custom.CLOSE"
        const val ACTION_UPDATE = "com.example.vkweb.action.UPDATE"
        const val ACTION_COMMAND = "com.example.vkweb.action.COMMAND"
        const val ACTION_DISMISS = "com.example.vkweb.action.DISMISS"
        const val ACTION_CLOSE_PLAYER = "com.example.vkweb.action.CLOSE_PLAYER"
        private const val EXTRA_COMMAND = "command"

        private const val SUPPORTED_ACTIONS =
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS

        private val FOREGROUND_TYPE =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0

        /** Меняется только на главном потоке (onCreate/onDestroy). */
        var isRunning = false
            private set

        /** Передать сервису состояние страницы. На паузе сервис сам не запускается. */
        fun update(context: Context, state: MediaState) {
            if (!state.playing && !isRunning) return
            val intent = state.writeTo(Intent(context, MusicService::class.java).setAction(ACTION_UPDATE))
            try {
                if (isRunning) context.startService(intent) else ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                // Android 12+ запрещает запуск foreground-сервиса из фона.
                Log.w(TAG, "Не удалось запустить сервис", e)
                AppLog.w(LogTag.MEDIA, "сервис воспроизведения не запущен системой", "ошибка" to e.javaClass.simpleName)
            }
        }

        fun stop(context: Context) {
            if (isRunning) context.stopService(Intent(context, MusicService::class.java))
        }

        fun commandIntent(context: Context, command: PlayerCommand): PendingIntent =
            servicePendingIntent(
                context,
                REQUEST_COMMAND_BASE + command.ordinal,
                Intent(context, MusicService::class.java)
                    .setAction(ACTION_COMMAND)
                    .putExtra(EXTRA_COMMAND, command.name),
            )

        fun actionIntent(context: Context, action: String): PendingIntent =
            servicePendingIntent(
                context,
                action.hashCode(),
                Intent(context, MusicService::class.java).setAction(action),
            )

        private fun servicePendingIntent(context: Context, requestCode: Int, intent: Intent) =
            PendingIntent.getService(
                context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        private const val REQUEST_COMMAND_BASE = 100
    }
}

/**
 * Процессор и Wi-Fi на время звучания. Звук играет WebView, а следующий трек и куски текущего загружает скрипт
 * страницы: при выключенном экране телефон мог уснуть в паузе между треками, и плейлист останавливался.
 *
 * Держатся только пока сервис в состоянии «играет» (плюс несколько секунд после конца трека) и с запасным
 * сроком [TIMEOUT_MS]: если что-то пойдёт не так, система снимет их сама, и батарея не будет тратиться впустую.
 */
private class PlaybackLocks(context: Context) {

    private val wake: PowerManager.WakeLock? = context.getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VkWeb:playback")
        ?.apply { setReferenceCounted(false) }

    // Обычный режим (радио не засыпает, но и не форсируется): потоковому звуку хватает, а «высокая производительность»
    // держит радио в самом прожорливом режиме всё время игры.
    @Suppress("DEPRECATION")
    private val wifi: WifiManager.WifiLock? = try {
        context.applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL, "VkWeb:playback")
            ?.apply { setReferenceCounted(false) }
    } catch (e: RuntimeException) {
        null
    }

    private var held = false

    /** Взять или продлить срок (каждое новое состояние «играет» продлевает его). */
    fun acquire() {
        try {
            wake?.acquire(TIMEOUT_MS)
            if (wifi?.isHeld == false) wifi.acquire()
            if (!held) AppLog.d(LogTag.MEDIA, "процессор и Wi-Fi удерживаются на время воспроизведения")
            held = true
        } catch (e: RuntimeException) {
            AppLog.w(LogTag.MEDIA, "не удалось удержать процессор на время воспроизведения", "ошибка" to e.javaClass.simpleName)
        }
    }

    fun release() {
        try {
            if (wake?.isHeld == true) wake.release()
            if (wifi?.isHeld == true) wifi.release()
        } catch (e: RuntimeException) {
            // Уже отпущено системой по сроку.
        }
        if (held) AppLog.d(LogTag.MEDIA, "процессор и Wi-Fi отпущены")
        held = false
    }

    private companion object {
        /** Запасной срок: обычный трек короче, а каждое новое состояние «играет» продлевает его. */
        const val TIMEOUT_MS = 3 * 60 * 60_000L
    }
}
