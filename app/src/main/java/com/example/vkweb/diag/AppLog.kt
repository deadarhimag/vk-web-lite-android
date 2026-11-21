package com.example.vkweb.diag

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.Writer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Журнал событий приложения: что происходило и когда (загрузка страниц, фоновое обновление сессии, свои экраны,
 * сеть, сохранённое, ошибки разбора). Нужен, чтобы по присланному файлу было видно, где и почему что-то пошло
 * не так, — без подключения компьютера. Из настроек его можно сохранить в файл и отправить ([FileShare]).
 *
 * Личного в журнале нет: записываются события, счётчики и размеры, адреса очищаются ([LogFormat.url]),
 * тексты сообщений, имена и содержимое страниц не пишутся.
 *
 * Устройство:
 *  - последние [MEMORY_LINES] записей — в памяти (их берёт отчёт о сбое, даже когда диск уже недоступен);
 *  - все записи — в файле `diag/app.log`; при превышении [MAX_FILE_BYTES] файл становится `app.log.1`
 *    (прежний `.1` удаляется): всего хранится не больше двух файлов;
 *  - запись на диск — в отдельном потоке, вызывающий код не ждёт. Строки копятся в открытом файле и сбрасываются
 *    на диск пачкой через [FLUSH_DELAY_MS] (а не открытие-запись-закрытие файла на каждую строку): поток просыпается
 *    реже. Последние строки перед сбоем не теряются — отчёт о сбое берёт их из памяти.
 * Уровень задаёт настройка «Журнал событий» ([configure]); пока журнал выключен, записи отбрасываются сразу.
 */
object AppLog {

    private const val DIR = "diag"
    private const val FILE = "app.log"
    private const val OLD_FILE = "app.log.1"
    private const val MAX_FILE_BYTES = 400_000L
    private const val MEMORY_LINES = 400
    private const val FLUSH_DELAY_MS = 2_000L
    private const val TAIL_BYTES = 96_000L

    private val io = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "app-log").apply { isDaemon = true } }
    private val memory = ArrayDeque<String>()

    @Volatile private var dir: File? = null
    @Volatile private var minLevel: LogLevel? = LogLevel.INFO

    /** Размер текущего файла в байтах; ведётся только из потока [io]. */
    private var fileBytes = 0L

    /** Открытый файл журнала (поток [io]); null — закрыт. */
    private var writer: Writer? = null

    /** Сброс на диск уже запланирован (поток [io]). */
    private var flushScheduled = false

    /** Вызвать один раз при запуске процесса (VkWebApp). */
    fun init(context: Context) {
        val folder = File(context.applicationContext.filesDir, DIR)
        dir = folder
        io.execute {
            folder.mkdirs()
            fileBytes = File(folder, FILE).length()
        }
    }

    /** Применить настройку: null — журнал выключен. */
    fun configure(level: LogLevel?) {
        minLevel = level
    }

    /** Включён ли журнал (выбран любой уровень, кроме «Выключен»). */
    val isEnabled: Boolean get() = minLevel != null

    /** Выбран уровень «Подробно»: можно включать дорогие замеры ([FreezeWatch]). */
    val isDebug: Boolean get() = minLevel == LogLevel.DEBUG

    /** Подробная запись: пишется только на уровне «Подробно». */
    fun d(tag: LogTag, message: String, vararg fields: Pair<String, Any?>) = log(LogLevel.DEBUG, tag, message, fields)

    /** Обычное событие. */
    fun i(tag: LogTag, message: String, vararg fields: Pair<String, Any?>) = log(LogLevel.INFO, tag, message, fields)
    /** Что-то пошло не так, но приложение справилось. */
    fun w(tag: LogTag, message: String, vararg fields: Pair<String, Any?>) = log(LogLevel.WARN, tag, message, fields)

    /** Ошибка: часть работы не выполнена. */
    fun e(tag: LogTag, message: String, vararg fields: Pair<String, Any?>) = log(LogLevel.ERROR, tag, message, fields)

    /** Запись об исключении: тип и сообщение (стек — в отчёте о сбое, здесь он не нужен). */
    fun e(tag: LogTag, message: String, error: Throwable, vararg fields: Pair<String, Any?>) =
        log(
            LogLevel.ERROR, tag, message,
            arrayOf(*fields, "ошибка" to "${error.javaClass.simpleName}: ${error.message}"),
        )

    fun log(level: LogLevel, tag: LogTag, message: String, fields: Array<out Pair<String, Any?>> = emptyArray()) {
        if (!LogFormat.allows(minLevel, level)) return
        val line = LogFormat.line(System.currentTimeMillis(), level, tag, message, fields.toList())
        synchronized(memory) {
            memory.addLast(line)
            while (memory.size > MEMORY_LINES) memory.removeFirst()
        }
        write(line)
    }

    /** Сбросить накопленное на диск, не дожидаясь (экран ушёл в фон: процесс могут завершить в любой момент). */
    fun syncToDisk() {
        io.execute { flushNow() }
    }

    /** Сбросить накопленное на диск и подождать не дольше [timeoutMs] (перед падением процесса). */
    fun flushBlocking(timeoutMs: Long) {
        try {
            io.submit { flushNow() }.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // Не успели — главное не помешать обработке падения.
        }
    }

    /** Последние записи из памяти, старые сверху. */
    fun tail(lines: Int = MEMORY_LINES): List<String> = synchronized(memory) { memory.toList().takeLast(lines) }

    /**
     * Журнал целиком (прошлый и текущий файл) — читает диск, вызывать не из главного потока.
     * Сначала сбрасывает очередь записи, чтобы свежие записи не потерялись.
     */
    fun readAll(): String {
        flush()
        val folder = dir ?: return tail().joinToString("\n")
        return listOf(File(folder, OLD_FILE), File(folder, FILE))
            .filter { it.isFile }
            .joinToString("") { runCatching { it.readText() }.getOrDefault("") }
    }

    /**
     * Последние [lines] строк с диска без инициализации (для отчёта о сбое при запуске следующего процесса:
     * память уже пуста, а события перед сбоем лежат в файле).
     */
    fun readTail(context: Context, lines: Int): List<String> {
        val folder = File(context.applicationContext.filesDir, DIR)
        // Читаем только конец каждого файла (до TAIL_BYTES): весь журнал — до 800 КБ — ради последних строк не нужен,
        // а это выполняется при запуске приложения.
        return listOf(File(folder, OLD_FILE), File(folder, FILE))
            .filter { it.isFile }
            .flatMap { runCatching { tailLines(it) }.getOrDefault(emptyList()) }
            .takeLast(lines)
    }

    private fun tailLines(file: File): List<String> =
        java.io.RandomAccessFile(file, "r").use { f ->
            val start = (f.length() - TAIL_BYTES).coerceAtLeast(0)
            f.seek(start)
            val bytes = ByteArray((f.length() - start).toInt())
            f.readFully(bytes)
            val lines = String(bytes, Charsets.UTF_8).lines()
            // Первая строка при чтении с середины файла обрезана.
            if (start > 0) lines.drop(1) else lines
        }

    /** Занято на диске, байт. Читает папку: вызывать не из главного потока. */
    fun sizeBytes(): Long = dir?.let { folder ->
        listOf(FILE, OLD_FILE).sumOf { File(folder, it).takeIf(File::isFile)?.length() ?: 0L }
    } ?: 0L

    /** Удаляет журнал (файлы и память); [done] вызывается из потока записи. */
    fun clear(done: () -> Unit = {}) {
        synchronized(memory) { memory.clear() }
        io.execute {
            closeWriter()
            dir?.let { folder -> listOf(FILE, OLD_FILE).forEach { File(folder, it).delete() } }
            fileBytes = 0
            done()
        }
    }

    // ------------------------------------------------------------ запись

    private fun write(line: String) {
        val folder = dir ?: return
        io.execute {
            try {
                if (fileBytes >= MAX_FILE_BYTES) rotate(folder)
                val text = line + "\n"
                openWriter(folder).write(text)
                // Размер — в байтах UTF-8: кириллица занимает два байта, и счёт в символах вдвое занижал размер файла.
                fileBytes += text.toByteArray(Charsets.UTF_8).size
                if (!flushScheduled) {
                    flushScheduled = true
                    io.schedule({ flushNow() }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
                }
            } catch (e: IOException) {
                // Нет места или файл занят: журнал вспомогательный, приложению это не мешает.
                closeWriter()
            }
        }
    }

    /** Поток [io]. */
    private fun openWriter(folder: File): Writer = writer ?: run {
        folder.mkdirs()
        FileOutputStream(File(folder, FILE), true).bufferedWriter(Charsets.UTF_8).also { writer = it }
    }

    /** Поток [io]. */
    private fun flushNow() {
        flushScheduled = false
        try { writer?.flush() } catch (e: IOException) { closeWriter() }
    }

    /** Поток [io]. */
    private fun closeWriter() {
        try { writer?.close() } catch (e: IOException) { /* уже закрыт */ }
        writer = null
    }

    /** Поток [io]. */
    private fun rotate(folder: File) {
        closeWriter()
        val old = File(folder, OLD_FILE)
        old.delete()
        File(folder, FILE).renameTo(old)
        fileBytes = 0
    }

    /** Дождаться, пока записанное ранее попадёт на диск. */
    private fun flush() {
        try {
            io.submit { flushNow() }.get()
        } catch (e: Exception) {
            // Прервали — читаем то, что успело записаться.
        }
    }
}
