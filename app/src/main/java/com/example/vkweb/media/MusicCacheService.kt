package com.example.vkweb.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag

/**
 * Кэширование по просьбе человека в фоне (4.98): пока очередь кэша не пуста, сервис держит процесс, процессор и Wi‑Fi,
 * а в шторке — «Кэширование музыки: осталось N» с кнопкой «Остановить». Без него Android останавливал приложение,
 * свёрнутое посреди «Кэшировать весь плейлист». Очередь опустела — сервис уходит сам.
 *
 * Прослушанные треки (настройка «Кэшировать музыку») сервис не запускают: это один трек, пока звучит музыка, и процесс
 * держит [MusicService].
 */
class MusicCacheService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var started = false
    private val progress: (MusicDownloadStarter.Progress) -> Unit = ::onProgress

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppLog.i(LogTag.MEDIA, "кэширование остановлено из уведомления")
            MusicDownloadStarter.cancelQueued()
        }
        // Обязателен при каждом запуске через startForegroundService, иначе Android завершит приложение.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(MusicDownloadStarter.progress()), FOREGROUND_TYPE)
        if (!started) {
            started = true
            acquireLocks()
            MusicDownloadStarter.addProgressListener(progress)
        }
        if (MusicDownloadStarter.progress().queued == 0) finish()
        return START_NOT_STICKY
    }

    private fun onProgress(state: MusicDownloadStarter.Progress) {
        if (state.queued == 0) {
            finish()
            return
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(state))
    }

    /** Android 15: лимит фоновой синхронизации исчерпан — уходим; очередь доделывается, пока жив процесс. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.w(LogTag.MEDIA, "кэширование: системный лимит фоновой работы")
        finish()
    }

    private fun finish() {
        MusicDownloadStarter.removeProgressListener(progress)
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        MusicDownloadStarter.removeProgressListener(progress)
        releaseLocks()
        isRunning = false
        super.onDestroy()
    }

    private fun build(state: MusicDownloadStarter.Progress): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.music_cache_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, MusicCacheService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val total = state.queued + state.saved
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_music)
            .setContentTitle(getString(R.string.music_cache_notification_title))
            .setContentText(getString(R.string.music_cache_notification_text, state.queued, state.saved))
            .setProgress(total.coerceAtLeast(1), state.saved, state.saved == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.music_cache_stop), stop).build())
            .build()
    }

    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VkWebLite:music-cache")
            ?.apply { setReferenceCounted(false); acquire(LOCK_TIMEOUT_MS) }
        @Suppress("DEPRECATION")
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VkWebLite:music-cache")
            ?.apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        private const val CHANNEL_ID = "music_cache"
        private const val NOTIFICATION_ID = 4822
        private const val ACTION_STOP = "com.example.vkweb.MUSIC_CACHE_STOP"
        /** Страховка: процессор не держится дольше часа подряд, даже если очередь «зависла». */
        private const val LOCK_TIMEOUT_MS = 60 * 60 * 1000L
        private val FOREGROUND_TYPE =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

        @Volatile private var isRunning = false

        /** Запустить, если ещё не запущен (из главного потока, по нажатию человека — пока экран на переднем плане). */
        fun ensureRunning(context: Context) {
            if (isRunning) return
            isRunning = true
            runCatching { ContextCompat.startForegroundService(context, Intent(context, MusicCacheService::class.java)) }
                .onFailure {
                    // Android 12+ не даёт запустить из фона: очередь всё равно идёт, пока жив процесс.
                    isRunning = false
                    AppLog.w(LogTag.MEDIA, "сервис кэширования не запущен", "ошибка" to it.javaClass.simpleName)
                }
        }
    }
}
