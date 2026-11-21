package com.example.vkweb.dev

import com.example.vkweb.Background
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.vkweb.offline.OfflineStore
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.Writer
import java.time.Instant

/**
 * Собранные для разработки ответы страниц (настройка «Собирать данные страниц», assets/vk_lite/80_dump.js, а с 3.68 —
 * и окно чтения сообщений, и фоновая проверка). Страница присылает уже обезличенные записи; здесь они дописываются по
 * одной строке и потом выгружаются одним JSON того же вида, что vk_dump.json.
 *
 * Хранение — частями (3.69): текущая часть [FILE] до [SEGMENT_BYTES], заполненная становится records.N.jsonl, всего
 * не больше [MAX_SEGMENTS] частей (около [MAX_BYTES]); старейшая часть удаляется целиком. Раньше предел был 10 МБ в одном
 * файле, и при заполнении весь файл читался в память, чтобы оставить новую половину: с окном чтения и списками до
 * 20 элементов 10 МБ хватало бы на несколько часов, а чтение большого файла целиком грозило нехваткой памяти.
 */
class DumpRecorder private constructor(context: Context) {

    private val appContext = context.applicationContext
    /**
     * Папка собранного — в папке приложения ([com.example.vkweb.files.AppFolder], 4.99): оттуда его можно взять и через
     * «Файлы». Определяется при первом обращении (из потока [io]); собранное до 4.99 переносится.
     */
    private val dir by lazy {
        com.example.vkweb.files.AppFolder.dir(appContext, com.example.vkweb.files.AppFolder.DEV).also { target ->
            File(appContext.filesDir, DIR).takeIf { it.isDirectory && it.canonicalPath != target.canonicalPath }?.let { old ->
                old.listFiles()?.forEach { f -> if (!File(target, f.name).exists()) f.copyTo(File(target, f.name)) }
                old.deleteRecursively()
            }
        }
    }
    private val file by lazy { File(dir, FILE) }
    private val io = Background.serial("dump")
    private val main = Handler(Looper.getMainLooper())

    /** Сколько собрано: записей и байт. */
    data class Stats(val records: Int, val bytes: Long, val full: Boolean)

    /**
     * Запись от страницы (сообщение type = [TYPE]) — строкой, как пришла: это уже готовый JSON в одну строку, и его
     * разбор в главном потоке на каждый ответ сервера только подвешивал бы интерфейс.
     */
    fun appendRaw(record: String, window: String? = null) {
        if (record.contains('\n')) return
        // Из какого окна запись (3.68: пишет и окно чтения сообщений, и фоновая проверка): поле в начало объекта.
        val line = if (window != null && record.startsWith("{")) "{\"window\":" + org.json.JSONObject.quote(window) + "," + record.substring(1) else record
        io.execute {
            try {
                if (file.length() >= SEGMENT_BYTES) rotate()
                dir.mkdirs()
                file.appendText(line + "\n")
            } catch (e: IOException) {
                // Нет места — запись пропускается: сбор вспомогательный.
            } catch (e: OutOfMemoryError) {
                // Сбор вспомогательный: из-за него приложение падать не должно.
            }
        }
    }

    /** Заполненные части от старых к новым (records.1.jsonl — самая старая из оставшихся по номеру). */
    private fun segments(): List<File> =
        (dir.listFiles() ?: emptyArray())
            .mapNotNull { f -> SEGMENT_NAME.matchEntire(f.name)?.groupValues?.get(1)?.toLongOrNull()?.let { it to f } }
            .sortedBy { it.first }
            .map { it.second }

    /** Текущая часть заполнена: она становится следующей по номеру, лишние старые части удаляются (поток [io]). */
    private fun rotate() {
        val done = segments()
        val next = (done.lastOrNull()?.let { SEGMENT_NAME.matchEntire(it.name)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L) + 1
        if (!file.renameTo(File(dir, "records.$next.jsonl"))) return
        val all = segments()
        // Текущая (новая, пустая) часть — тоже часть: заполненных остаётся MAX_SEGMENTS - 1.
        all.take((all.size - (MAX_SEGMENTS - 1)).coerceAtLeast(0)).forEach { it.delete() }
    }

    /** Все части по порядку записи: заполненные, затем текущая. */
    private fun allFiles(): List<File> = segments() + listOf(file).filter { it.isFile }

    fun stats(result: (Stats) -> Unit) {
        io.execute {
            val stats = statsBlocking()
            main.post { result(stats) }
        }
    }

    /** Сколько собрано, сразу (читает файлы построчно): вызывать не из главного потока. */
    fun statsBlocking(): Stats {
        val records = allFiles().sumOf { f -> f.useLines { lines -> lines.count { it.isNotBlank() } } }
        val bytes = OfflineStore.sizeOf(dir)
        // «Заполнен» — старые записи уже начали удаляться.
        return Stats(records, bytes, segments().size >= MAX_SEGMENTS - 1)
    }

    /** Размер на диске. Читает папку: вызывать не из главного потока. */
    fun sizeBytes(): Long = OfflineStore.sizeOf(dir)

    /**
     * Выгружает собранное в [output] одним JSON: {app, at, items: [...]} — как vk_dump.json. С 4.11 — и откуда собрано:
     * версия и сборка приложения, Android, WebView, устройство (без личного), а после записей — сводка ([DumpSummary]).
     * [done] (в главном потоке) получает число записей или null при ошибке.
     */
    fun export(output: OutputStream, done: (Int?) -> Unit) {
        io.execute {
            val count = try {
                output.bufferedWriter().use { w -> writeJson(w) }
            } catch (e: IOException) {
                null
            }
            main.post { done(count) }
        }
    }

    fun clear(done: () -> Unit = {}) {
        io.execute {
            dir.deleteRecursively()
            main.post(done)
        }
    }

    /** Пишет JSON потоково, не собирая файлы в памяти. */
    private fun writeJson(w: Writer): Int {
        w.write("{\"app\":\"VkWeb\",\"at\":")
        w.write(JSONObject.quote(Instant.now().toString()))
        w.write(",\"source\":")
        w.write(source().toString())
        w.write(",\"items\":[\n")
        val summary = DumpSummary()
        allFiles().forEach { f ->
            f.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                if (summary.records > 0) w.write(",\n")
                w.write(line)
                summary.add(line)
            }
        }
        w.write("\n],\"summary\":")
        w.write(summary.toJson().toString())
        w.write("}\n")
        return summary.records
    }

    /** Чем собрано: без этого по файлу не понять, какая сборка и какой WebView видели эти ответы. */
    private fun source(): JSONObject {
        val version = com.example.vkweb.diag.AppVersion.of(appContext)
        val webView = try {
            android.webkit.WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" }
        } catch (e: Throwable) {
            null
        }
        return JSONObject()
            .put("version", version.name)
            .put("build", version.build)
            .put("android", android.os.Build.VERSION.SDK_INT)
            .put("webview", webView ?: "?")
            .put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
    }

    companion object {
        /** Тип сообщения страницы (поле type). */
        const val TYPE = "dump"
        private const val DIR = "dev_dump"
        private const val FILE = "records.jsonl"
        private val SEGMENT_NAME = Regex("^records\\.(\\d+)\\.jsonl$")
        /** Одна часть. */
        private const val SEGMENT_BYTES = 8L * 1024 * 1024
        /** Частей всего, с текущей: около 48 МБ — сутки-двое с окном сообщений и фоновой проверкой. */
        private const val MAX_SEGMENTS = 6
        const val MAX_BYTES = SEGMENT_BYTES * MAX_SEGMENTS

        @Volatile private var instance: DumpRecorder? = null

        fun get(context: Context): DumpRecorder =
            instance ?: synchronized(this) { instance ?: DumpRecorder(context.applicationContext).also { instance = it } }
    }
}
