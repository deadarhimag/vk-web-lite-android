package com.example.vkweb.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.webkit.WebView
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Отчёты о сбоях. Два источника:
 *  1. перехват Java-исключений — стек вызовов и последние строки журнала своего процесса;
 *  2. причина прошлого завершения от системы (Android 11+): ловит и то, что Java не видит, —
 *     сбои в машинном коде (например, внутри WebView) и зависания.
 * В отчёт попадают только технические данные. Отправляет его сам пользователь, приложение — нет.
 */
object CrashLog {

    private const val FILE = "last_crash.txt"
    private const val PREFS = "crash_log"
    private const val KEY_LAST_EXIT = "last_exit_timestamp"
    private const val LOG_LINES = "200"
    private const val EVENT_LINES = 150
    private const val LOG_TIMEOUT_SEC = 2L
    private const val TRACE_LIMIT = 30_000
    /** Сколько ждать, пока журнал допишется на диск перед падением. */
    private const val FLUSH_ON_CRASH_MS = 500L

    private const val REPORT_KEEP_CHARS = 200_000

    /** Чьи строки журнала процесса берутся в отчёт: падение, свои теги и системные причины завершения. */
    private val OWN_LOG_MARKERS = listOf("AndroidRuntime", "PageBridge", "MusicService", "FATAL", "libc")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Всё своё — в try, стандартная обработка — в finally: исключение здесь (например, нехватка памяти при
            // записи журнала) иначе оставило бы процесс живым с мёртвым главным потоком (код ревью 3.59).
            try {
                // Запись в журнал событий — до отчёта: тогда она попадёт в его раздел «события приложения».
                AppLog.e(LogTag.APP, "необработанное исключение", error, "поток" to thread.name)
                AppLog.flushBlocking(FLUSH_ON_CRASH_MS)
                File(app.filesDir, FILE).writeText(javaReport(app, thread, error))
            } catch (ignored: Throwable) {
                // Записать не удалось — главное не помешать стандартной обработке падения.
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    /** Есть ли непоказанный отчёт (собирает и системную причину прошлого завершения). */
    fun hasReport(context: Context): Boolean {
        collectExitInfo(context)
        return File(context.filesDir, FILE).isFile
    }

    fun read(context: Context): String = try {
        File(context.filesDir, FILE).readText()
    } catch (e: Exception) {
        ""
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    // ------------------------------------------------------------ системная причина завершения

    private fun collectExitInfo(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_EXIT, 0L)
        val infos = try {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        } catch (e: Exception) {
            null
        } ?: return
        val newest = infos.maxByOrNull { it.timestamp } ?: return
        prefs.edit().putLong(KEY_LAST_EXIT, maxOf(lastSeen, newest.timestamp)).apply()
        if (lastSeen == 0L) return // первый запуск с этой функцией: старые завершения не показываем

        val crash = infos.filter { it.timestamp > lastSeen && it.reason in REPORTED_REASONS }
            .maxByOrNull { it.timestamp } ?: return
        val text = buildString {
            appendLine("Время: " + format(crash.timestamp))
            appendLine("Причина: " + reasonName(crash.reason) + " (" + crash.reason + ")")
            crash.description?.let { appendLine("Описание: $it") }
            appendLine(environment(context))
            // Процесс уже новый, память пуста — события перед сбоем лежат в файле журнала.
            val events = AppLog.readTail(context, EVENT_LINES)
            if (events.isNotEmpty()) {
                appendLine()
                appendLine("----- события приложения перед сбоем -----")
                events.forEach(::appendLine)
            }
            if (crash.reason == ApplicationExitInfo.REASON_ANR) {
                // Для зависания система отдаёт текстовый дамп потоков.
                try {
                    // Не больше TRACE_LIMIT знаков: дамп потоков бывает в несколько мегабайт (код ревью 3.59).
                    crash.traceInputStream?.bufferedReader()?.use { reader ->
                        val buffer = CharArray(TRACE_LIMIT)
                        var total = 0
                        while (total < TRACE_LIMIT) {
                            val n = reader.read(buffer, total, TRACE_LIMIT - total)
                            if (n < 0) break
                            total += n
                        }
                        appendLine()
                        append(String(buffer, 0, total))
                    }
                } catch (e: Exception) { /* дампа нет */ }
            }
        }
        val file = File(context.filesDir, FILE)
        // Если Java-отчёт уже есть (сбой пойман перехватчиком), дополняем его, а не заменяем.
        // Отчёт не растёт без конца, если его не открывают: остаётся конец (самое свежее).
        val existing = if (file.isFile) file.readText().takeLast(REPORT_KEEP_CHARS) + "\n\n----- система -----\n" else ""
        try { file.writeText(existing + text) } catch (e: Exception) { /* нет места */ }
    }

    private val REPORTED_REASONS = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
    )

    private fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "сбой (Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "сбой в машинном коде (часто WebView)"
        ApplicationExitInfo.REASON_ANR -> "приложение не отвечало"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "ошибка запуска"
        else -> "другое"
    }

    // ------------------------------------------------------------ отчёт о Java-исключении

    private fun javaReport(context: Context, thread: Thread, error: Throwable): String {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("Время: " + format(System.currentTimeMillis()))
            appendLine("Причина: необработанное исключение в потоке ${thread.name}")
            appendLine(environment(context))
            appendLine()
            appendLine(stack)
            val events = AppLog.tail(EVENT_LINES)
            if (events.isNotEmpty()) {
                appendLine("----- события приложения -----")
                events.forEach(::appendLine)
                appendLine()
            }
            val log = ownLog()
            if (log.isNotEmpty()) {
                appendLine("----- журнал процесса -----")
                append(log)
            }
        }
    }

    /** Последние строки журнала своего процесса (чужие приложению недоступны). */
    private fun ownLog(): String = try {
        val process = ProcessBuilder("logcat", "-d", "-t", LOG_LINES, "--pid", Process.myPid().toString())
            .redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(LOG_TIMEOUT_SEC, TimeUnit.SECONDS)
        // Только строки самого приложения и падения: консоль страниц (с адресами и текстами) в отчёт не попадает
        // (код ревью 3.59).
        text.lineSequence().filter { line -> OWN_LOG_MARKERS.any { line.contains(it) } }.joinToString("\n").take(TRACE_LIMIT)
    } catch (e: Exception) {
        ""
    }

    private fun environment(context: Context): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) { "?" }
        val webView = try {
            WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" }
        } catch (e: Throwable) { null } ?: "?"
        return "Приложение: $version\n" +
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "WebView: $webView"
    }

    private fun format(time: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time))
}
