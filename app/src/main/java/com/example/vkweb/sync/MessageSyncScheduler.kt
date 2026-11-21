package com.example.vkweb.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.outbox.OutboxItem
import com.example.vkweb.outbox.OutboxState

/**
 * Расписание фоновой работы с сообщениями ([MessageSyncService]) в системном планировщике:
 *  - [PERIODIC_ID]: проверка новых сообщений раз в N минут (не реже, чем позволяет система — 15), только при сети;
 *  - [OUTBOX_ID]: одноразовая отправка очереди, как только появится сеть (если очереди есть что отправлять);
 *  - [READS_ID]: одноразовая отправка отметок «прочитано» из уведомления (4.35), как только появится сеть.
 * Планировщик сам переживает закрытие приложения и перезагрузку телефона, поэтому ничего держать в памяти не нужно.
 */
object MessageSyncScheduler {

    const val PERIODIC_ID = 1
    const val OUTBOX_ID = 2
    /** 3 — срочная проверка ([SyncAlarm.CATCH_UP_ID]), 4 — отложенная отправка ([ScheduledSendAlarm.JOB_ID]). */
    const val READS_ID = 5

    /**
     * Включить проверку каждые [minutes] минут; 0 — выключить. Прежнее расписание не сбрасывается, если не изменилось.
     */
    fun applyPeriodic(context: Context, minutes: Int) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        // Страховка от отложенных системой заданий (3.66): будильник ставится заново при каждом запуске процесса.
        SyncAlarm.arm(context, minutes)
        if (minutes <= 0) {
            if (scheduler.getPendingJob(PERIODIC_ID) != null) {
                scheduler.cancel(PERIODIC_ID)
                AppLog.i(LogTag.SYNC, "периодическая проверка выключена")
            }
            return
        }
        val interval = maxOf(minutes * 60_000L, JobInfo.getMinPeriodMillis())
        // Окно запуска — конец периода. Без него задание допустимо в любой момент и система запускает его сразу после
        // постановки, то есть при каждом открытии приложения, а не раз в период.
        val flex = JobInfo.getMinFlexMillis()
        val pending = scheduler.getPendingJob(PERIODIC_ID)
        if (pending != null && pending.intervalMillis == interval && pending.flexMillis == flex && !pending.isRequireBatteryNotLow) return
        if (pending != null) {
            AppLog.i(
                LogTag.SYNC, "расписание заменено",
                "было, мин" to pending.intervalMillis / 60_000, "окно было, мин" to pending.flexMillis / 60_000,
            )
        }
        val job = JobInfo.Builder(PERIODIC_ID, component(context))
            .setPeriodic(interval, flex)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            // Без «батарея не разряжена»: при низком заряде проверка не шла вовсе, а уведомление о сообщении важнее
            // восьми секунд работы (3.66).
            .setPersisted(true)
            .build()
        schedule(scheduler, job, "периодическая проверка", "минут" to interval / 60_000, "окно, мин" to flex / 60_000)
    }

    /** Состояние расписания для отчёта: что поставлено и когда система должна его запускать. */
    fun describe(context: Context): String {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return "нет планировщика"
        val periodic = scheduler.getPendingJob(PERIODIC_ID)
            ?.let { "каждые ${it.intervalMillis / 60_000} мин, окно ${it.flexMillis / 60_000} мин" } ?: "не поставлена"
        val outbox = if (scheduler.getPendingJob(OUTBOX_ID) != null) "ждёт сети" else "нет"
        val last = SyncAlarm.lastCheckAt(context)
        val ago = if (last > 0) "${(System.currentTimeMillis() - last) / 60_000} мин назад" else "не было"
        val catchUp = if (scheduler.getPendingJob(SyncAlarm.CATCH_UP_ID) != null) "; срочная проверка ждёт сети" else ""
        return "проверка: $periodic; последняя: $ago; отправка очереди: $outbox$catchUp"
    }

    /** Очередь изменилась: поставить одноразовую отправку, если есть что отправлять, иначе убрать. */
    fun onOutboxChanged(context: Context, items: List<OutboxItem>, minLatencyMs: Long = 0) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val now = System.currentTimeMillis()
        // Отложенное (4.17) — своим будильником на своё время; отправка очереди — только для того, чьё время пришло.
        ScheduledSendAlarm.arm(context, com.example.vkweb.outbox.OutboxLogic.nextScheduledAt(items, now))
        val needsSending = com.example.vkweb.outbox.OutboxLogic.hasDueWork(items, now)
        if (!needsSending) {
            if (scheduler.getPendingJob(OUTBOX_ID) != null) scheduler.cancel(OUTBOX_ID)
            return
        }
        if (scheduler.getPendingJob(OUTBOX_ID) != null) return
        val job = JobInfo.Builder(OUTBOX_ID, component(context))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .apply { if (minLatencyMs > 0) setMinimumLatency(minLatencyMs) }
            .build()
        schedule(scheduler, job, "отправка очереди при появлении сети", "сообщений" to items.size)
    }

    /** «Прочитано» из уведомления (4.35): одноразовая фоновая работа при сети — отметки уйдут от имени страницы. */
    fun onReadsChanged(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(READS_ID) != null) return
        val job = JobInfo.Builder(READS_ID, component(context))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        schedule(scheduler, job, "отметки «прочитано» из уведомления")
    }

    private fun schedule(scheduler: JobScheduler, job: JobInfo, what: String, vararg fields: Pair<String, Any?>) {
        val result = try {
            scheduler.schedule(job)
        } catch (e: IllegalArgumentException) {
            AppLog.e(LogTag.SYNC, "не удалось поставить в расписание: $what", e)
            return
        } catch (e: SecurityException) {
            AppLog.e(LogTag.SYNC, "нет права поставить в расписание: $what", e)
            return
        }
        AppLog.i(
            LogTag.SYNC,
            if (result == JobScheduler.RESULT_SUCCESS) "поставлено в расписание: $what" else "система отказала: $what",
            *fields,
        )
    }

    private fun component(context: Context) = ComponentName(context, MessageSyncService::class.java)
}
