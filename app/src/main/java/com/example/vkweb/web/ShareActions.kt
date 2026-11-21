package com.example.vkweb.web

import android.app.Activity
import com.example.vkweb.ui.Notice
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import com.example.vkweb.Background
import com.example.vkweb.R
import com.example.vkweb.config.AppConfig
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.FileShare
import com.example.vkweb.diag.LogTag
import com.example.vkweb.messages.ChatCard
import com.example.vkweb.messages.ChatMessage
import com.example.vkweb.messages.MessageShare
import com.example.vkweb.offline.ImageCache
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

/**
 * «Поделиться» — в другой мессенджер или приложение: сообщение (текст и ссылки вложений, фото — картинками), фото,
 * файл из вложения (скачивается и передаётся самим файлом), ссылка. Файлы отдаются через FileProvider ([FileShare]).
 */
class ShareActions(private val activity: Activity) {
    private val worker = Background.serial("share")
    private val main = Handler(Looper.getMainLooper())

    fun shareMessage(message: ChatMessage) {
        val text = MessageShare.textOf(message)
        val photos = MessageShare.photosOf(message)
        AppLog.i(LogTag.MESSAGES, "поделиться сообщением", "фото" to photos.size, "есть текст" to text.isNotEmpty())
        when {
            photos.isNotEmpty() -> sharePhotos(photos, text)
            text.isNotEmpty() -> FileShare.shareText(activity, text)
            else -> toast(R.string.share_nothing)
        }
    }

    fun shareText(text: String) = FileShare.shareText(activity, text)

    /** Фото — картинками (из кэша, иначе скачиваются), с подписью [text]. */
    fun sharePhotos(urls: List<String>, text: String = "") {
        if (urls.isEmpty()) return
        toast(R.string.share_preparing)
        val cache = ImageCache.get(activity)
        val files = ArrayList<File?>()
        fun next(index: Int) {
            if (index == urls.size) {
                val ready = files.filterNotNull()
                if (ready.isEmpty()) { toast(R.string.share_failed); return }
                val dir = FileShare.newDir(activity)
                worker.execute {
                    val copies = ready.mapIndexedNotNull { i, f -> copy(f, File(dir, "photo_${i + 1}.${extOf(f)}")) }
                    main.post {
                        if (activity.isFinishing || copies.isEmpty()) return@post
                        FileShare.shareMany(activity, copies, "image/*", text)
                    }
                }
                return
            }
            cache.fileOf(urls[index]) { file -> files += file; next(index + 1) }
        }
        next(0)
    }

    /**
     * Сохранить фото в галерею (4.0): копия файла из кэша — в «Картинки/VkWeb» через MediaStore. Только Android 10+:
     * там для этого не нужны разрешения; на старых кнопки нет.
     */
    fun savePhoto(url: String) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        ImageCache.get(activity).fileOf(url) { file ->
            if (file == null) { toast(R.string.photo_save_failed); return@fileOf }
            worker.execute {
                val ok = try {
                    writeToGallery(file)
                } catch (e: RuntimeException) {
                    com.example.vkweb.diag.AppLog.w(com.example.vkweb.diag.LogTag.APP, "фото не сохранено в галерею", "ошибка" to e.javaClass.simpleName)
                    false
                }
                main.post { if (!activity.isFinishing) toast(if (ok) R.string.photo_saved else R.string.photo_save_failed) }
            }
        }
    }

    @android.annotation.TargetApi(android.os.Build.VERSION_CODES.Q)
    private fun writeToGallery(file: File): Boolean {
        val ext = extOf(file)
        val mime = when (ext) { "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"; else -> "image/jpeg" }
        val resolver = activity.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "vk_" + System.currentTimeMillis() + "." + ext)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/VkWeb")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IOException("нет потока")
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: IOException) {
            com.example.vkweb.diag.AppLog.w(com.example.vkweb.diag.LogTag.APP, "фото не сохранено в галерею", "ошибка" to e.javaClass.simpleName)
            resolver.delete(uri, null, null)
            false
        }
    }

    /** Вложение: файл — самим файлом, остальное — ссылкой с названием. */
    fun shareCard(card: ChatCard) {
        val link = MessageShare.linkOf(card)
        if (card.kind == "doc" && link.startsWith("https://")) {
            // Расширение — из названия, иначе из подписи («PDF · 1,5 МБ»): без него получатель не знает тип файла.
            shareRemoteFile(link, MessageShare.fileName(card.title, MessageShare.extensionFrom(card.subtitle)))
            return
        }
        val text = listOf(card.title, link).filter { it.isNotBlank() }.distinct().joinToString("\n")
        if (text.isNotEmpty()) shareText(text) else toast(R.string.share_nothing)
    }

    /**
     * Файл по адресу VK: скачивается с входом пользователя (cookie VK уходят только на домены VK) во временную папку
     * и передаётся самим файлом — как в другом мессенджере «переслать документ».
     */
    fun shareRemoteFile(url: String, name: String) {
        toast(R.string.share_preparing)
        worker.execute {
            val file = try {
                download(url, File(FileShare.newDir(activity), name))
            } catch (e: IOException) {
                AppLog.w(LogTag.MESSAGES, "файл для передачи не скачался", "ошибка" to e.message?.take(100))
                null
            }
            main.post {
                if (activity.isFinishing) return@post
                if (file == null) { toast(R.string.share_failed); return@post }
                val mime = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
                AppLog.i(LogTag.MESSAGES, "файл передаётся", "байт" to file.length(), "тип" to mime)
                FileShare.share(activity, file, mime)
            }
        }
    }

    private fun download(start: String, target: File): File {
        var url = URL(start)
        repeat(MAX_REDIRECTS) {
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (url.protocol != "https") throw IOException("не https")
            if (AppConfig.isImageHost(url.host)) {
                CookieManager.getInstance().getCookie(url.toString())?.let { connection.setRequestProperty("Cookie", it) }
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    url = URL(url, connection.getHeaderField("Location") ?: throw IOException("переадресация без адреса"))
                    return@repeat
                }
                if (code != 200) throw IOException("ответ $code")
                val length = connection.contentLengthLong
                if (length > MAX_BYTES) throw IOException("файл больше ${MAX_BYTES / 1024 / 1024} МБ")
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) throw IOException("файл больше ${MAX_BYTES / 1024 / 1024} МБ")
                            output.write(buffer, 0, n)
                        }
                    }
                }
                return target
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("слишком много переадресаций")
    }

    private fun copy(from: File, to: File): File? = try {
        from.inputStream().use { input -> to.outputStream().use { input.copyTo(it) } }
        to
    } catch (e: IOException) {
        null
    }

    /** Расширение картинки по её первым байтам (в кэше файлы без расширения). */
    private fun extOf(file: File): String {
        val head = ByteArray(12)
        val n = try { file.inputStream().use { it.read(head) } } catch (e: IOException) { 0 }
        return when {
            n >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "jpg"
            n >= 4 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() -> "png"
            n >= 12 && head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() -> "webp"
            n >= 3 && head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() -> "gif"
            else -> "jpg"
        }
    }

    private fun toast(res: Int) = Notice.show(activity, res)

    private companion object {
        const val MAX_REDIRECTS = 5
        const val TIMEOUT_MS = 20_000
        const val MAX_BYTES = 200L * 1024 * 1024
    }
}
