package com.example.vkweb.sync

import android.app.job.JobParameters
import android.app.job.JobService
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.outbox.Outbox
import com.example.vkweb.outbox.OutboxState
import com.example.vkweb.settings.AppSettings

/**
 * Задание системного планировщика ([MessageSyncScheduler]): проверить новые сообщения и отправить очередь.
  * Система вызывает его в главном потоке, когда есть сеть, — окно для чтения страниц создаётся прямо здесь
  * ([HeadlessSync]).
 *
 * Не запускается поверх открытого приложения (оно само обновляется и отправляет). Если при этом в очереди
 * остались неотправленные, просит систему повторить позже — иначе после ухода из приложения они бы ждали
 * следующего изменения очереди.
 */
class MessageSyncService : JobService() {

    private var sync: HeadlessSync? = null

    /** Задание, которое сейчас выполняется: устаревший обратный вызов не должен запускать работу для остановленного. */
    private var current: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        // Одна работа за раз на процесс. Флаг ставится сразу, а не когда прочитается очередь: иначе два задания,
        // пришедшие подряд, оба прошли бы проверку.
        if (running) {
            AppLog.d(LogTag.SYNC, "задание пропущено: другая работа ещё идёт", "номер" to params.jobId)
            // Одноразовое задание очереди после отказа удаляется системой: ставим его заново, чуть позже (код ревью 3.59).
            if (params.jobId == MessageSyncScheduler.OUTBOX_ID) {
                val outbox = Outbox.get(this)
                outbox.whenReady { MessageSyncScheduler.onOutboxChanged(applicationContext, outbox.items(), RETRY_LATER_MS) }
            }
            return false
        }
        running = true
        current = params
        if (params.jobId != MessageSyncScheduler.OUTBOX_ID) {
            logConditions(params)
            SyncAlarm.markChecked(this)
            // Периодическая пришла — срочная страховочная больше не нужна.
            if (params.jobId == MessageSyncScheduler.PERIODIC_ID) getSystemService(android.app.job.JobScheduler::class.java)?.cancel(SyncAlarm.CATCH_UP_ID)
        }
        val settings = AppSettings(this)
        val outbox = Outbox.get(this)
        val includeDialogs = settings.messageSyncMinutes > 0
        outbox.whenReady {
            if (current !== params) return@whenReady
            val now = System.currentTimeMillis()
            val hasOutboxWork = com.example.vkweb.outbox.OutboxLogic.hasDueWork(outbox.items(), now)
            // 4.35: «Прочитано» из уведомления — отметкам нужна страница, даже если периодическая проверка выключена.
            val hasReads = com.example.vkweb.messages.PendingReads(this).all().isNotEmpty()
            // 4.17: отложенное в переписку, которая не открыта на экране, само не уйдёт — экран отправляет только открытую.
            val dueElsewhere = (com.example.vkweb.outbox.OutboxLogic.duePeers(outbox.items(), now) - setOfNotNull(AppVisibility.openChatPeer)).isNotEmpty()
            when {
                AppVisibility.anyOpen && dueElsewhere -> run(params, settings, includeDialogs = false)
                // Открытое приложение само обновляет всё, что показывает: второе скрытое окно только жгло бы батарею.
                AppVisibility.anyOpen -> skip(params, "приложение открыто", reschedule = hasOutboxWork || hasReads)
                !includeDialogs && !hasOutboxWork && !hasReads -> skip(params, "нечего делать", reschedule = false)
                else -> run(params, settings, includeDialogs || hasReads)
            }
        }
        // Работа идёт в главном потоке и завершается сама через jobFinished.
        return true
    }

    /** Когда и в каких условиях система запустила проверку: по отчёту видно, почему между проверками был разрыв. */
    private fun logConditions(params: JobParameters) {
        val last = SyncAlarm.lastCheckAt(this)
        val power = getSystemService(android.os.PowerManager::class.java)
        val battery = getSystemService(android.os.BatteryManager::class.java)
        val usage = getSystemService(android.app.usage.UsageStatsManager::class.java)
        AppLog.i(
            LogTag.SYNC, "проверка запущена системой",
            "задание" to when (params.jobId) {
                SyncAlarm.CATCH_UP_ID -> "срочное (страховка)"
                ScheduledSendAlarm.JOB_ID -> "отложенная отправка"
                MessageSyncScheduler.READS_ID -> "отметки «прочитано» из уведомления"
                else -> "периодическое"
            },
            "с прошлой, мин" to if (last > 0) (System.currentTimeMillis() - last) / 60_000 else "нет",
            "заряд, %" to battery?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY),
            "сон устройства" to power?.isDeviceIdleMode,
            "экономия" to power?.isPowerSaveMode,
            "группа" to if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) usage?.appStandbyBucket?.let { com.example.vkweb.diag.ReportFormat.standbyBucket(it) } else null,
        )
    }

    private fun skip(params: JobParameters, reason: String, reschedule: Boolean) {
        AppLog.d(LogTag.SYNC, "задание пропущено: $reason", "номер" to params.jobId, "повторить позже" to reschedule)
        finish(params, reschedule)
    }

    private fun finish(params: JobParameters, reschedule: Boolean) {
        running = false
        current = null
        sync = null
        jobFinished(params, reschedule)
        // В очереди осталось неотправленное (страница отказала, ждём повтора): без нового задания оно ждало бы, пока
        // откроют приложение — одноразовое задание уже закончилось (код ревью 3.59). Не раньше, чем через полминуты.
        if (!reschedule) {
            val outbox = Outbox.get(this)
            val app = applicationContext
            // Чуть позже: сразу после jobFinished система ещё может числить закончившееся задание поставленным.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ outbox.whenReady {
                val items = outbox.items()
                val now = System.currentTimeMillis()
                val wait = com.example.vkweb.outbox.OutboxLogic.nextRetryDelay(items, now)
                MessageSyncScheduler.onOutboxChanged(app, items, maxOf(RETRY_LATER_MS, wait))
            } }, RESCHEDULE_DELAY_MS)
        }
    }

    private fun run(params: JobParameters, settings: AppSettings, includeDialogs: Boolean) {
        sync = HeadlessSync(
            context = this,
            includeDialogs = includeDialogs,
            notify = settings.notifyMessages,
        ) {
            finish(params, reschedule = false)
        }.also { it.start() }
    }

    /** Система остановила задание (условия пропали): свернуть работу и попросить повторить. */
    override fun onStopJob(params: JobParameters): Boolean {
        sync?.cancel()
        sync = null
        current = null
        running = false
        return true
    }

    private companion object {
        /** Одна фоновая работа за раз на процесс. */
        @Volatile var running = false

        /** Повтор неотправленного фоном — не чаще. */
        const val RETRY_LATER_MS = 30_000L
        const val RESCHEDULE_DELAY_MS = 1_000L
    }
}
