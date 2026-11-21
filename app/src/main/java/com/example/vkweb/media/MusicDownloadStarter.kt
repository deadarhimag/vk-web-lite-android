package com.example.vkweb.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.vkweb.Background
import com.example.vkweb.config.SafeUrls
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Кэш музыки: трек скачивается, при необходимости перекодируется в mp3, получает ID3-теги и ложится в папку приложения
 * — `VkWebLite/Music` ([MusicCache], 4.99). Оттуда его играет приложение; папку можно открыть из приложения.
 *
 *  - [cache] без [CacheBatch] — прослушанный трек (настройка «Кэшировать музыку»), молча;
 *  - [cache] с [CacheBatch] — по просьбе человека: «Кэшировать трек» или весь список; итог сообщает сама просьба.
 *
 * Источник — mp3 или HLS (сегменты скачиваются [HlsPlaylistDownloader]); всё, что не mp3, перекодируется FFmpeg
 * (только в 64-битном процессе: опубликованные библиотеки — arm64-v8a и x86_64).
 *
 * Треки качаются по одному, в порядке просьб; «Остановить кэширование» ([cancelQueued]) снимает с очереди всё, что ещё
 * не начато. Хранится только контекст приложения — экран не удерживается, пока идёт загрузка.
 */
class MusicDownloadStarter(context: Context) {
    private val app: Context = context.applicationContext

    /**
     * Трек — в кэш. [batch] — просьба человека (сообщит итог); null — прослушанный трек (тихо, не больше
     * [MAX_CACHE_ATTEMPTS] неудач за запуск).
     */
    fun cache(download: MusicDownload, batch: CacheBatch? = null) {
        val id = download.id
        val manual = download.manualRequest ?: (batch != null)
        val refreshing = REFRESHING[id]
        if (download.refreshAttempt == 0 && refreshing != null) {
            if (recoveryIsCurrent(refreshing.first, refreshing.second)) { batch?.onAlready(); return }
            REFRESHING.remove(id, refreshing)
        }
        if (MusicCache.isCached(id)) { finishRecovery(download); batch?.onAlready(); return }
        if (!download.ok || SafeUrls.audio(download.url).isEmpty()) { finishRecovery(download); batch?.onFailed(); return }
        if (manual) FAILED.remove(id) else if ((FAILED[id] ?: 0) >= MAX_CACHE_ATTEMPTS) {
            finishRecovery(download); batch?.onFailed(); return
        }
        if (!CACHE_JOBS.add(id)) { batch?.onAlready(); return }
        val generation = download.cacheGeneration ?: MusicCache.currentGeneration()
        val cancelGeneration = download.cancellationGeneration ?: CANCELLED.get()
        val expiresAt = download.streamExpiresAt.takeIf { it > 0L }
            ?: StreamLifetime.limit(Long.MAX_VALUE, System.currentTimeMillis())
        QUEUED.incrementAndGet()
        notifyProgress()
        // Просьба человека — в фоне тоже: сервис держит процесс, пока очередь не опустеет.
        if (manual) MAIN.post { MusicCacheService.ensureRunning(app) }
        CACHE.execute {
            val temp = TempFiles()
            var recovery: MusicDownload? = null
            try {
                if (!recoveryIsCurrent(generation, cancelGeneration)) { batch?.onCancelled(); return@execute }
                if (MusicCache.isCached(id)) { batch?.onAlready(); return@execute }
                if (!StreamLifetime.isFresh(expiresAt, System.currentTimeMillis())) throw MusicRecovery.Expired()
                val fetched = ReadRetry.run({ recoveryIsCurrent(generation, cancelGeneration) }, { temp.deleteAll() }) {
                    fetchMp3(download, temp)
                }
                if (!publishToCache(download, fetched, generation, temp)) throw IllegalStateException("Music cache was cleared during download")
                FAILED.remove(id)
                SAVED.incrementAndGet()
                batch?.onSaved()
                AppLog.i(LogTag.MEDIA, "трек сохранён в кэш", "номер" to id, "источник" to fetched.source, "просьба" to manual)
            } catch (error: Throwable) {
                if (error is VirtualMachineError) throw error
                if (error is ReadRetry.Cancelled || !recoveryIsCurrent(generation, cancelGeneration)) {
                    batch?.onCancelled()
                } else if (MusicRecovery.shouldRefresh(error, download.refreshAttempt)) {
                    val token = RECOVERY_SEQUENCE.incrementAndGet()
                    recovery = download.copy(url = "", streamExpiresAt = 0, refreshAttempt = 1,
                        cacheGeneration = generation, cancellationGeneration = cancelGeneration, manualRequest = manual,
                        recoveryToken = token)
                    REFRESHING[id] = Triple(generation, cancelGeneration, token)
                    AppLog.i(LogTag.MEDIA, "кэш: автоматически обновляем адрес трека", "номер" to id,
                        "HTTP" to (error as? MusicRecovery.HttpFailure)?.status)
                } else {
                    val attempts = (FAILED[id] ?: 0) + 1
                    FAILED[id] = attempts
                    if (error is Mp3Unavailable) MP3_UNAVAILABLE.set(true)
                    finishRecovery(download)
                    batch?.onFailed()
                    AppLog.w(LogTag.MEDIA, "трек не сохранён в кэш", "номер" to id, "попытка" to attempts,
                        "ошибка" to error.javaClass.simpleName, "причина" to (error as? DashAudioPlan.Failure)?.reason)
                }
            } finally {
                temp.deleteAll()
                if (download.refreshAttempt > 0) finishRecovery(download)
                CACHE_JOBS.remove(id)
                fun release() {
                    if (QUEUED.decrementAndGet() == 0 && MusicCacheWorker.pending == 0) SAVED.set(0)
                    notifyProgress()
                }
                val retry = recovery
                if (retry == null) release() else MAIN.post {
                    try {
                        if (recoveryIsCurrent(generation, cancelGeneration)) MusicCacheWorker.recover(app, retry, batch)
                        else { finishRecovery(retry); batch?.onCancelled() }
                    } catch (error: Exception) {
                        finishRecovery(retry, failed = true)
                        batch?.onFailed()
                        AppLog.w(LogTag.MEDIA, "кэш: обновление адреса не запущено", "ошибка" to error.javaClass.simpleName)
                    } finally { release() }
                }
            }
        }
    }

    /** Удалить трек из кэша. */
    fun remove(trackId: String, done: (Boolean) -> Unit) {
        CACHE.execute {
            val ok = MusicCache.remove(trackId)
            MAIN.post { done(ok) }
        }
    }

    /** Очередь кэша: сколько ждёт и сколько сохранено с тех пор, как она начала заполняться. */
    data class Progress(val queued: Int, val saved: Int)

    // ------------------------------------------------------------ получение mp3

    /** Скачанное аудио в mp3 (без своих тегов) и обложка; [source] — для журнала: «mp3» или «перекодировано». */
    private class Fetched(val audio: File, val artwork: ByteArray?, val source: String)

    /** Не mp3 и собрать mp3 на этом устройстве нельзя. */
    private class Mp3Unavailable : IllegalStateException("MP3 conversion is unavailable for this process ABI")

    /** Временные файлы и папки одной задачи: удаляются вместе в конце. */
    private class TempFiles {
        private val files = ArrayList<File>()
        fun <T : File> add(file: T): T = file.also { files += it }
        fun deleteAll() = files.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun fetchMp3(download: MusicDownload, temp: TempFiles): Fetched {
        val artwork = downloadArtwork(download.coverUrl)
        val connection = openConnection(download.url) { SafeUrls.audio(it) }
        val transfer = try {
            val direct = temp.add(File.createTempFile(TEMP_PREFIX, ".bin", app.cacheDir))
            HlsPlaylistDownloader { url, range -> openConnection(url, range) { SafeUrls.audio(it) } }
                .saveResponse(connection, direct, app.cacheDir, allowHls = true)
                .also { it.temporaryDirectory?.let(temp::add) }
        } finally {
            connection.disconnect()
        }
        if (!transfer.isHls && Id3Tag.fileLooksLikeMp3(transfer.playlistOrAudio)) {
            return Fetched(transfer.playlistOrAudio, artwork, "mp3")
        }
        if (!canConvert()) throw Mp3Unavailable()
        val converted = temp.add(File.createTempFile(TEMP_PREFIX, ".mp3", app.cacheDir))
        if (!convertToMp3(transfer.playlistOrAudio, converted)) throw IllegalStateException("MP3 conversion failed")
        return Fetched(converted, artwork, if (transfer.format == "DASH") "DASH → mp3" else if (transfer.isHls) "HLS → mp3" else "перекодировано в mp3")
    }

    /** mp3 с тегами — в хранилище (через `.part`); false — кэш очистили во время загрузки. */
    private fun publishToCache(download: MusicDownload, fetched: Fetched, generation: Long, temp: TempFiles): Boolean {
        val part = temp.add(MusicCache.temporaryFileFor(app, download.id))
        part.delete()
        FileOutputStream(part).use { out -> writeTagged(out, download, fetched.audio, fetched.artwork) }
        return MusicCache.publish(app, download.id, download.artist to download.title, part, generation)
    }

    private fun writeTagged(output: OutputStream, download: MusicDownload, audio: File, artwork: ByteArray?) {
        Id3Tag.write(output, download.title, download.artist, download.album, artwork)
        audio.inputStream().buffered().use { Id3Tag.copyWithoutTag(it, output) }
    }

    /** Код FFmpeg — только в 64-битном процессе (ABI процесса, а не устройства: 32-битный процесс на 64-битном телефоне). */
    private fun canConvert(): Boolean = android.os.Process.is64Bit()

    private fun convertToMp3(input: File, output: File): Boolean {
        val finished = CountDownLatch(1)
        val successful = AtomicBoolean(false)
        val session = try {
            FFmpegKit.executeWithArgumentsAsync(
                arrayOf(
                    "-y", "-protocol_whitelist", "file,crypto,data", "-i", input.absolutePath,
                    "-map", "0:a:0", "-vn", "-codec:a", "libmp3lame", "-q:a", "2",
                    // Свой тег пишет приложение (Id3Tag): у FFmpeg — ни ID3v2, ни ID3v1.
                    "-id3v2_version", "0", "-write_id3v1", "0",
                    output.absolutePath,
                ),
            ) { result ->
                successful.set(ReturnCode.isSuccess(result.returnCode))
                finished.countDown()
            }
        } catch (error: LinkageError) {
            // Библиотеки FFmpeg нет для этого ABI.
            throw Mp3Unavailable().apply { initCause(error) }
        }
        if (!finished.await(CONVERSION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            FFmpegKit.cancel(session.sessionId)
            return false
        }
        return successful.get() && output.isFile && output.length() > 0L
    }

    // ------------------------------------------------------------ сеть

    private fun openConnection(initialUrl: String, byteRange: LongRange? = null, allow: (String?) -> String): HttpURLConnection {
        var current = allow(initialUrl)
        repeat(MAX_REDIRECTS + 1) {
            if (current.isEmpty()) throw IllegalArgumentException("Untrusted media URL")
            val host = URI(current).host.orEmpty()
            val cooldown = NETWORK_COOLDOWN.remaining(host, System.currentTimeMillis())
            if (cooldown > 0) throw MusicRecovery.HttpFailure(429, (cooldown / 1000 + 1).toString())
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(app))
            connection.setRequestProperty("Accept", "*/*")
            byteRange?.let { connection.setRequestProperty("Range", "bytes=${it.first}-${it.last}") }
            val status = try { connection.responseCode } catch (error: Exception) { connection.disconnect(); throw error }
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                current = if (location.isNullOrBlank()) "" else allow(URI(current).resolve(location).toString())
            } else if (status in 200..299) {
                return connection
            } else {
                val retryAfter = connection.getHeaderField("Retry-After")
                if (ReadRetry.retryableStatus(status)) NETWORK_COOLDOWN.record(host, retryAfter, System.currentTimeMillis())
                connection.disconnect()
                throw MusicRecovery.HttpFailure(status, retryAfter)
            }
        }
        throw IllegalStateException("Too many media redirects")
    }

    private fun downloadArtwork(url: String): ByteArray? {
        val safeUrl = SafeUrls.image(url).takeIf { it.isNotEmpty() } ?: return null
        var connection: HttpURLConnection? = null
        return try {
            val imageConnection = openConnection(safeUrl) { SafeUrls.image(it) }
            connection = imageConnection
            if (imageConnection.contentLengthLong > MAX_COVER_BYTES) return null
            val bytes = imageConnection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_COVER_BYTES) return null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / MAX_COVER_SIDE)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            try {
                ByteArrayOutputStream().use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)) null else out.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    // ------------------------------------------------------------

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_REDIRECTS = 5
        private const val MAX_COVER_BYTES = 4 * 1024 * 1024
        private const val MAX_COVER_SIDE = 512
        private const val CONVERSION_TIMEOUT_MS = 10 * 60 * 1000L
        /** Столько неудач на прослушанный трек за запуск — дальше он сам не качается (по просьбе — всегда). */
        private const val MAX_CACHE_ATTEMPTS = 2
        private const val TEMP_PREFIX = "vk-music-"

        private val MAIN = Handler(Looper.getMainLooper())
        /** Кэш — по одному треку, с низким приоритетом. */
        private val CACHE = Background.serial("music-cache", Thread.MIN_PRIORITY)
        private val CACHE_JOBS: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val FAILED = ConcurrentHashMap<String, Int>()
        private val REFRESHING = ConcurrentHashMap<String, Triple<Long, Long, Long>>()
        private val RECOVERY_SEQUENCE = AtomicLong()
        private val NETWORK_COOLDOWN = RetryAfterGate()
        private val CANCELLED = AtomicLong()
        private val QUEUED = AtomicInteger()
        private val MP3_UNAVAILABLE = AtomicBoolean(false)
        private val prepared = AtomicBoolean(false)

        private val SAVED = AtomicInteger()

        private val progressListeners = java.util.concurrent.CopyOnWriteArraySet<(Progress) -> Unit>()
        private val cancelListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

        /** Очередь: треки кэша и треки, для которых страница кэша ещё ищет поток ([MusicCacheWorker.pending]). */
        fun progress() = Progress(QUEUED.get() + MusicCacheWorker.pending, SAVED.get())

        /** Очередь изменилась — в главном потоке ([MusicCacheService]: уведомление). */
        fun addProgressListener(listener: (Progress) -> Unit) { progressListeners += listener }
        fun removeProgressListener(listener: (Progress) -> Unit) { progressListeners -= listener }

        /** «Остановить кэширование» (из меню или уведомления) — в главном потоке: экран снимает свои части списка. */
        fun addCancelListener(listener: () -> Unit) { cancelListeners += listener }
        fun removeCancelListener(listener: () -> Unit) { cancelListeners -= listener }

        /** Очередь изменилась (и у страницы кэша): слушателям — в главном потоке. */
        fun notifyProgress() {
            if (progressListeners.isEmpty()) return
            MAIN.post {
                val state = progress()
                progressListeners.forEach { it(state) }
            }
        }

        /** Треки в очереди кэша или в работе. */
        val queued: Int get() = QUEUED.get()

        /** Была ли неудача из-за 32-битного процесса (mp3 не собрать) — для итога просьбы. */
        fun takeMp3Unavailable(): Boolean = MP3_UNAVAILABLE.getAndSet(false)

        /** Перечитать папку музыки (файлы могли удалить по USB или другим приложением): в фоне. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            CACHE.execute { if (MusicCache.isReady) runCatching { MusicCache.rescan(app) } }
        }

        /** «Остановить кэширование»: всё, что ещё не начато, снимается с очереди (идущая загрузка доделывается). */
        fun recoveryIsCurrent(cache: Long, cancellation: Long): Boolean = MusicRecovery.isCurrent(
            cache, cancellation, MusicCache.currentGeneration(), CANCELLED.get(),
        )
        fun cancellationGeneration(): Long = CANCELLED.get()

        fun finishRecovery(download: MusicDownload, failed: Boolean = false) {
            val cache = download.cacheGeneration ?: return
            val cancellation = download.cancellationGeneration ?: return
            if (REFRESHING.remove(download.id, Triple(cache, cancellation, download.recoveryToken)) && failed) {
                FAILED.merge(download.id, 1) { previous, increment -> previous + increment }
            }
        }

        fun cancelQueued() {
            CANCELLED.incrementAndGet()
            MAIN.post { cancelListeners.forEach { it() } }
        }

        /**
         * При запуске процесса, в фоне: папка музыки — в список кэша ([MusicCache.warmUp], с переносом файлов прежних
         * версий); временные файлы прошлых запусков — удалить.
         */
        fun prepare(context: Context) {
            if (!prepared.compareAndSet(false, true)) return
            val app = context.applicationContext
            CACHE.execute {
                app.cacheDir.listFiles()?.filter { it.name.startsWith(TEMP_PREFIX) || it.name.startsWith("vk-hls-") || it.name.startsWith("vk-attachment-") }
                    ?.forEach { runCatching { it.deleteRecursively() } }
                runCatching { MusicCache.warmUp(app) }
                    .onFailure { AppLog.w(LogTag.MEDIA, "кэш музыки не прочитан", "ошибка" to it.javaClass.simpleName) }
                AppLog.i(LogTag.MEDIA, "кэш музыки", "треков" to MusicCache.count(), "байт" to MusicCache.sizeBytes())
            }
        }

    }
}
