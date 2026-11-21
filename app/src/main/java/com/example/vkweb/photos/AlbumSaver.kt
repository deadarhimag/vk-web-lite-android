package com.example.vkweb.photos

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.example.vkweb.config.AppConfig
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.files.DownloadsFolder
import com.example.vkweb.offline.ImageDownloadRules
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Сохранение всех фото альбома (5.00.12) в папку приложения в «Загрузках»: Download/VkWebLite/Photos/<альбом>
 * ([DownloadsFolder]; на Android 8-9 - папка приложения).
 *
 * Список адресов присылает страница порциями ([PhotosAllPayload]); каждая порция сразу ставится в очередь загрузки, пока
 * подписи адресов свежие. Одно сохранение за раз. Уже сохранённые фото пропускаются - повтор дописывает недостающее.
 * Не отдал крупный размер - пробуются запасные. Состояние приходит слушателю в главном потоке.
 */
class AlbumSaver private constructor(context: Context) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "album-saver").apply { isDaemon = true } }
    private val current = AtomicReference<AlbumSaveProgress?>(null)
    /** Папка альбома внутри Загрузки/VkWebLite ("Photos/Лето"); null - ещё выбирается. */
    @Volatile private var folder: String? = null

    /** Слушатель состояния (экран фото); вызывается в главном потоке. */
    var listener: ((AlbumSaveProgress) -> Unit)? = null
        set(value) {
            field = value
            current.get()?.let { p -> value?.invoke(p) }
        }

    /** Идёт сохранение (или ждёт список). */
    val isRunning: Boolean get() = current.get()?.finished == false

    /** Текущее или последнее сохранение. */
    val progress: AlbumSaveProgress? get() = current.get()

    /**
     * Начать сохранение альбома [place] с названием [title]. [requestList] - попросить страницу прислать адреса под этим
     * номером задания. @return false - уже идёт другое сохранение.
     */
    fun start(place: PhotoPlace.Album, title: String, requestList: (job: String) -> Unit): Boolean {
        if (isRunning) return false
        val job = UUID.randomUUID().toString().take(12)
        val base = AlbumSaveRules.folderName(title, place.owner, place.album)
        val relative = "${AlbumSaveRules.PHOTOS_DIR}/$base"
        val started = AlbumSaveProgress(job, place.key, title, DownloadsFolder.displayPath(relative), relative)
        current.set(started)
        folder = null
        AppLog.i(LogTag.STORE, "сохранение альбома начато", "альбом" to place.key, "в загрузки" to DownloadsFolder.isShared)
        publish(started)
        // Папка выбирается в фоне (обращение к диску): своя - та же, занятая другим альбомом - с номером
        worker.execute {
            val existing = DownloadsFolder.subfolders(app, AlbumSaveRules.PHOTOS_DIR)
            val owners = folderOwners()
            val name = AlbumSaveRules.chooseFolder(base, place.key) { candidate ->
                if (candidate !in existing) null else owners[candidate].orEmpty()
            }
            rememberFolder(name, place.key)
            val chosen = "${AlbumSaveRules.PHOTOS_DIR}/$name"
            folder = chosen
            if (chosen != relative) update(job) { it.copy(folder = DownloadsFolder.displayPath(chosen), relative = chosen) }
        }
        requestList(job)
        return true
    }

    /** Остановить: недокачанное удаляется, сохранённое остаётся. */
    fun cancel() {
        val p = current.get() ?: return
        if (p.finished) return
        update(p.job) { it.copy(finished = true, cancelled = true) }
        AppLog.i(LogTag.STORE, "сохранение альбома остановлено", "сохранено" to p.saved)
    }

    /** Порция адресов от страницы. Чужое (старое) задание отбрасывается. */
    fun onList(payload: PhotosAllPayload) {
        val p = current.get() ?: return
        if (p.job != payload.job || p.finished) return
        if (!payload.ok) {
            finish(p.job, reason = payload.reason.ifEmpty { "список не получен" })
            return
        }
        update(p.job) { it.copy(total = maxOf(it.total, payload.total, it.handled + payload.photos.size), listDone = payload.done) }
        val items = payload.photos
        worker.execute { saveAll(p.job, items) }
        // Конец списка: после всех загрузок порций (очередь одна) - итог
        if (payload.done) worker.execute { finish(p.job) }
    }

    // ------------------------------------------------------------ загрузка

    private fun saveAll(job: String, items: List<PhotoItem>) {
        val relative = folder ?: return
        val existing = DownloadsFolder.names(app, relative)
        for (item in items) {
            if (!alive(job)) return
            if (AlbumSaveRules.alreadySaved(existing, item)) {
                update(job) { it.copy(skipped = it.skipped + 1) }
                continue
            }
            when (saveOne(job, relative, item)) {
                Result.SAVED -> update(job) { it.copy(saved = it.saved + 1) }
                Result.GONE -> update(job) { it.copy(missing = it.missing + 1) }
                Result.FAILED -> update(job) { it.copy(failed = it.failed + 1) }
            }
        }
    }

    private enum class Result { SAVED, GONE, FAILED }

    /** Одно фото: крупный адрес, затем запасные размеры; снимка нет на сервере (404) - остальные не пробуются. */
    private fun saveOne(job: String, relative: String, item: PhotoItem): Result {
        for (url in AlbumSaveRules.candidates(item)) {
            if (!alive(job)) return Result.FAILED
            val result = download(job, url, relative, item)
            if (result != Result.FAILED) return result
        }
        AppLog.d(LogTag.STORE, "фото альбома не сохранено", "фото" to item.key)
        return Result.FAILED
    }

    /** Скачать во временный файл, затем опубликовать в папку альбома в «Загрузках». */
    private fun download(job: String, url: String, relative: String, item: PhotoItem): Result {
        if (!isImageUrl(url)) return Result.FAILED
        val tmp = File(tempDir(), AlbumSaveRules.baseName(item) + AlbumSaveRules.PART)
        var connection: HttpURLConnection? = null
        return try {
            // Перенаправления - сами и только на серверы картинок VK
            var current = url
            var redirects = 0
            var response: HttpURLConnection
            do {
                response = (URL(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                }
                connection = response
                response.connect()
                if (!ImageDownloadRules.isRedirect(response.responseCode)) break
                val next = ImageDownloadRules.redirectTarget(current, response.getHeaderField("Location"))
                response.disconnect()
                if (next == null || ++redirects > ImageDownloadRules.MAX_REDIRECTS) return Result.FAILED
                current = next
            } while (true)

            // Ответ должен быть картинкой разумного размера; удалённый снимок - сразу итог
            val code = response.responseCode
            if (AlbumSaveRules.isGone(code)) return Result.GONE
            if (code !in 200..299) {
                AppLog.d(LogTag.STORE, "фото альбома: сервер ответил ошибкой", "код" to code, "хост" to Uri.parse(current).host)
                return Result.FAILED
            }
            val type = response.contentType
            if (!type.orEmpty().startsWith("image/") || ImageDownloadRules.tooLarge(response.contentLengthLong)) {
                AppLog.d(LogTag.STORE, "фото альбома: по адресу не картинка", "тип" to type)
                return Result.FAILED
            }

            // Во временный файл целиком, затем публикация: недокачанное не выглядит сохранённым
            var total = 0L
            response.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        if (!alive(job)) return Result.FAILED
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > ImageDownloadRules.MAX_BYTES) return Result.FAILED
                        out.write(buffer, 0, read)
                    }
                }
            }
            val name = AlbumSaveRules.baseName(item) + "." + AlbumSaveRules.extension(type)
            val mime = type.orEmpty().substringBefore(';').trim().ifEmpty { "image/jpeg" }
            if (DownloadsFolder.publish(app, relative, name, mime, tmp, item.dateSec * 1000)) Result.SAVED else Result.FAILED
        } catch (e: IOException) {
            AppLog.d(LogTag.STORE, "фото альбома не скачалось", "ошибка" to e.javaClass.simpleName)
            Result.FAILED
        } finally {
            tmp.delete()
            connection?.disconnect()
        }
    }

    private fun tempDir(): File = File(app.cacheDir, TEMP_DIR).apply { mkdirs() }

    // ------------------------------------------------------------ какой альбом в какой папке

    private val ownersFile by lazy { File(app.filesDir, OWNERS_FILE) }

    /** Папка -> ключ альбома, запомненные раньше. */
    private fun folderOwners(): Map<String, String> = runCatching {
        val json = org.json.JSONObject(ownersFile.readText())
        json.keys().asSequence().associateWith { json.optString(it) }
    }.getOrDefault(emptyMap())

    private fun rememberFolder(name: String, key: String) {
        runCatching {
            val json = runCatching { org.json.JSONObject(ownersFile.readText()) }.getOrDefault(org.json.JSONObject())
            json.put(name, key)
            ownersFile.writeText(json.toString())
        }
    }

    private fun isImageUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.scheme == "https" && AppConfig.isImageHost(uri.host?.lowercase().orEmpty())
    }

    // ------------------------------------------------------------ состояние

    private fun alive(job: String): Boolean = current.get()?.let { it.job == job && !it.finished } == true

    private fun finish(job: String, reason: String = "") {
        val p = current.get() ?: return
        if (p.job != job || p.finished) return
        update(job) { it.copy(finished = true, reason = reason) }
        val done = current.get() ?: return
        AppLog.i(
            LogTag.STORE, "сохранение альбома завершено",
            "сохранено" to done.saved, "уже было" to done.skipped, "не удалось" to done.failed, "причина" to reason.ifEmpty { null },
        )
    }

    private fun update(job: String, change: (AlbumSaveProgress) -> AlbumSaveProgress) {
        while (true) {
            val before = current.get() ?: return
            if (before.job != job) return
            val after = change(before)
            if (current.compareAndSet(before, after)) {
                publish(after)
                return
            }
        }
    }

    private fun publish(p: AlbumSaveProgress) = main.post { listener?.invoke(p) }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER = 64 * 1024
        private const val OWNERS_FILE = "album_folders.json"
        private const val TEMP_DIR = "album_save"

        @Volatile private var instance: AlbumSaver? = null

        fun get(context: Context): AlbumSaver =
            instance ?: synchronized(this) { instance ?: AlbumSaver(context).also { instance = it } }
    }
}
