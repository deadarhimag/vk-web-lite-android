package com.example.vkweb.diag

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import java.util.Collections

/**
 * Замер подвисаний интерфейса. Если между кадрами прошло больше [STALL_MS], главный поток был занят: в журнал пишется,
 * сколько, на каком экране и **чем занят был поток** — пока подвисание идёт, второй поток снимает стек главного
 * ([StallReport]). Раз в минуту — сводка: сколько было подвисаний разной длины и самое долгое.
 *
 * Раньше порог был 700 мс, и «микрофризы» в 100–500 мс не были видны вовсе.
 * Работает только при уровне журнала «Подробно»: непрерывные кадры и второй поток — для диагностики, а не для обычной работы.
 */
object FreezeWatch : Choreographer.FrameCallback, Application.ActivityLifecycleCallbacks {

    private const val STALL_MS = 100L
    /** Подвисание начато: снимать стек главного потока каждые [SAMPLE_EVERY_MS], начиная с [SAMPLE_AFTER_MS] без кадра. */
    private const val SAMPLE_AFTER_MS = 50L
    private const val SAMPLE_EVERY_MS = 40L
    private const val MAX_SAMPLES = 8
    /** Не чаще одной записи за это время: серия подвисаний не должна забить журнал (она всё равно попадает в сводку). */
    private const val LOG_GAP_MS = 500L
    private const val SUMMARY_MS = 60_000L

    private var resumed = 0
    @Volatile private var lastFrameMs = 0L
    private var lastLoggedMs = 0L
    private var lastSummaryMs = 0L
    private var screen = ""

    private var over100 = 0
    private var over250 = 0
    private var over500 = 0
    private var worst = 0L

    private val samples: MutableList<String> = Collections.synchronizedList(ArrayList())
    @Volatile private var watching = false
    private var watchdog: Thread? = null

    fun install(application: Application) = application.registerActivityLifecycleCallbacks(this)

    override fun doFrame(frameTimeNanos: Long) {
        val now = SystemClock.uptimeMillis()
        val gap = if (lastFrameMs == 0L) 0L else now - lastFrameMs
        if (gap >= STALL_MS) {
            count(gap)
            if (now - lastLoggedMs >= LOG_GAP_MS) {
                lastLoggedMs = now
                val stack = synchronized(samples) { StallReport.summarize(samples) }
                AppLog.w(LogTag.APP, "интерфейс не отвечал", "мс" to gap, "экран" to screen, "стек" to stack.ifEmpty { "не успели снять" })
            }
        }
        samples.clear()
        if (now - lastSummaryMs >= SUMMARY_MS) {
            writeSummary()
            lastSummaryMs = now
        }
        lastFrameMs = now
        framePosted = false
        if (resumed > 0 && AppLog.isDebug) postFrame() else lastFrameMs = 0
    }

    /** Одна цепочка кадров: при переходах между экранами прежняя ещё шла, и цепочки множились (код ревью 3.59). */
    private var framePosted = false

    private fun postFrame() {
        if (framePosted) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun count(gap: Long) {
        if (gap >= 100) over100++
        if (gap >= 250) over250++
        if (gap >= 500) over500++
        if (gap > worst) worst = gap
    }

    private fun writeSummary() {
        if (over100 == 0) return
        AppLog.i(
            LogTag.APP, "интерфейс: подвисания за минуту",
            ">100 мс" to over100, ">250 мс" to over250, ">500 мс" to over500, "самое долгое, мс" to worst, "экран" to screen,
        )
        over100 = 0; over250 = 0; over500 = 0; worst = 0
    }

    /** Второй поток: пока кадра нет дольше порога, снимает стек главного потока. Работает, пока экран на виду. */
    private fun startWatchdog() {
        if (watching) return
        watching = true
        val main = Looper.getMainLooper().thread
        watchdog = Thread({
            while (watching) {
                try { Thread.sleep(SAMPLE_EVERY_MS) } catch (e: InterruptedException) { break }
                val last = lastFrameMs
                if (last != 0L && SystemClock.uptimeMillis() - last >= SAMPLE_AFTER_MS && samples.size < MAX_SAMPLES) {
                    samples += StallReport.describe(main.stackTrace)
                }
            }
        }, "freeze-watch").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    private fun stopWatchdog() {
        watching = false
        watchdog?.interrupt()
        watchdog = null
    }

    override fun onActivityResumed(activity: Activity) {
        screen = activity.javaClass.simpleName
        resumed++
        if (resumed == 1 && AppLog.isDebug) {
            lastFrameMs = 0
            lastSummaryMs = SystemClock.uptimeMillis()
            postFrame()
            startWatchdog()
        }
    }

    override fun onActivityPaused(activity: Activity) {
        resumed = (resumed - 1).coerceAtLeast(0)
        if (resumed == 0) {
            writeSummary()
            stopWatchdog()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
