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
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag

/**
 * Будильник отложенной отправки (4.17). В назначенное время ставится срочное задание ([JobInfo.Builder.setExpedited]):
 * система запускает его почти сразу и с сетью, фоновая работа ([HeadlessSync]) отправляет то, чьё время пришло.
 *
 * Точно ко времени — только с разрешением «Будильники и напоминания» (Android 12+: [AlarmManager.canScheduleExactAlarms]);
 * без него будильник неточный: система может сдвинуть его на несколько минут, во сне устройства — дольше.
 * Будильники пропадают при перезагрузке, поэтому рядом всегда стоит обычное задание на то же время (оно переживает
 * перезагрузку): если будильник не сработал, сообщение уйдёт с ним, чуть позже.
 */
class ScheduledSendAlarm : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AppLog.i(LogTag.OUTBOX, "время отложенной отправки — срочная отправка")
        scheduleNow(context.applicationContext)
    }

    companion object {
        /** Задание отложенной отправки (и запасное на время, и срочное по будильнику — одно, новое заменяет). */
        const val JOB_ID = 4
        private const val REQUEST_CODE = 43

        /** Можно ли ставить точный будильник (иначе — неточный). */
        fun canBeExact(context: Context): Boolean {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return false
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        }

        /** Поставить будильник и запасное задание на [at] (мс, часы телефона); null — снять. */
        fun arm(context: Context, at: Long?) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val pending = pendingIntent(context)
            if (at == null) {
                alarms.cancel(pending)
                // Запасное задание — только если оно ещё не превратилось в срочное (то уже работает над отправкой).
                scheduler?.getPendingJob(JOB_ID)?.let { if (!it.isExpeditedCompat()) scheduler.cancel(JOB_ID) }
                return
            }
            try {
                if (canBeExact(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } catch (e: SecurityException) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
            val delay = (at - System.currentTimeMillis()).coerceAtLeast(0)
            val backup = JobInfo.Builder(JOB_ID, ComponentName(context, MessageSyncService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setMinimumLatency(delay)
                .build()
            try {
                scheduler?.schedule(backup)
            } catch (e: Exception) {
                AppLog.w(LogTag.OUTBOX, "запасное задание отложенной отправки не поставлено", "ошибка" to e.javaClass.simpleName)
            }
            AppLog.d(LogTag.OUTBOX, "отложенная отправка в расписании", "через, мин" to delay / 60_000, "точно" to canBeExact(context))
        }

        /** Время пришло: срочное задание (без квоты — обычное, без задержки). */
        fun scheduleNow(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val component = ComponentName(context, MessageSyncService::class.java)
            val expedited = JobInfo.Builder(JOB_ID, component).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) expedited.setExpedited(true)
            try {
                scheduler.schedule(expedited.build())
            } catch (e: Exception) {
                try {
                    scheduler.schedule(JobInfo.Builder(JOB_ID, component).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
                } catch (e2: Exception) {
                    AppLog.w(LogTag.OUTBOX, "отложенная отправка не поставлена", "ошибка" to e2.javaClass.simpleName)
                }
            }
        }

        private fun JobInfo.isExpeditedCompat(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isExpedited

        private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE, Intent(context, ScheduledSendAlarm::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
