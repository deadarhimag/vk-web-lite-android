package com.example.vkweb.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.settings.AppSettings

/**
 * Страховка периодической проверки сообщений (3.66). Отчёт 3.65: между 09:54 и 16:13 система ни разу не запустила
 * проверку (приложение без исключения из оптимизации батареи, им не пользовались — система отложила задание на 6 часов);
 * сообщение пришло утром, а уведомление появилось только при открытии приложения.
 *
 * Будильник «и во сне» ([AlarmManager.setAndAllowWhileIdle], без особых прав) раз в период проверяет: давно ли была
 * проверка. Если дольше периода с запасом — ставится срочное задание ([JobInfo.Builder.setExpedited]), которое система
 * запускает почти сразу и с сетью. Будильники у неиспользуемых приложений система тоже ограничивает, но мягче заданий
 * (не реже раза в час), поэтому опоздание — не больше часа, а не полдня.
 */
class SyncAlarm : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val minutes = AppSettings(app).messageSyncMinutes
        if (minutes <= 0) return
        arm(app, minutes)
        val sinceMs = System.currentTimeMillis() - lastCheckAt(app)
        val periodMs = minutes * 60_000L
        if (AppVisibility.anyOpen || sinceMs < periodMs + LATE_GRACE_MS) {
            AppLog.d(LogTag.SYNC, "страховка: проверка идёт по расписанию", "с прошлой, мин" to sinceMs / 60_000)
            return
        }
        AppLog.i(LogTag.SYNC, "страховка: система давно не запускала проверку — срочная проверка", "с прошлой, мин" to sinceMs / 60_000)
        scheduleCatchUp(app)
    }

    companion object {
        /** Срочная проверка вместо опоздавшей периодической. */
        const val CATCH_UP_ID = 3

        private const val PREFS = "sync_runtime"
        private const val KEY_LAST_CHECK = "last_check_at"
        private const val REQUEST_CODE = 41
        /** Опоздание периодической проверки, после которого вмешивается страховка. */
        private const val LATE_GRACE_MS = 15 * 60_000L

        /** Проверка запустилась (или не нужна — приложение открыто): страховке пока делать нечего. */
        fun markChecked(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        }

        fun lastCheckAt(context: Context): Long =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0L)

        /** Поставить будильник на [minutes] минут вперёд (0 — снять). */
        fun arm(context: Context, minutes: Int) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = pendingIntent(context)
            if (minutes <= 0) {
                alarms.cancel(pending)
                return
            }
            if (lastCheckAt(context) == 0L) markChecked(context)
            val at = SystemClock.elapsedRealtime() + minutes * 60_000L
            try {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            } catch (e: SecurityException) {
                AppLog.w(LogTag.SYNC, "страховка проверки не поставлена: нет права на будильник")
            }
        }

        private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE, Intent(context, SyncAlarm::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun scheduleCatchUp(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(CATCH_UP_ID) != null) return
            val builder = JobInfo.Builder(CATCH_UP_ID, ComponentName(context, MessageSyncService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setExpedited(true)
            val result = try {
                scheduler.schedule(builder.build())
            } catch (e: Exception) {
                // Квота срочных заданий исчерпана и т. п. — обычное одноразовое задание.
                try {
                    scheduler.schedule(
                        JobInfo.Builder(CATCH_UP_ID, ComponentName(context, MessageSyncService::class.java))
                            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build(),
                    )
                } catch (e2: Exception) {
                    AppLog.w(LogTag.SYNC, "срочная проверка не поставлена", "ошибка" to e2.javaClass.simpleName)
                    return
                }
            }
            if (result != JobScheduler.RESULT_SUCCESS) AppLog.w(LogTag.SYNC, "система отказала в срочной проверке")
        }
    }
}
