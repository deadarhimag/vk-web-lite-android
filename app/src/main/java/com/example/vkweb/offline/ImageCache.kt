package com.example.vkweb.offline

import com.example.vkweb.Background
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.widget.ImageView
import com.example.vkweb.R
import com.example.vkweb.config.AppConfig
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Картинки с диска и из сети. Файл всегда сохраняется в том разрешении, в каком пришёл
 * (для фото из сообщений это оригинал), а для показа уменьшается до размера места на экране.
 * Сохранённое доступно без сети. Размер кэша ограничен: старые файлы удаляются первыми.
 * Загружаются только https-адреса доменов VK.
 *
 * Без мигания: если в ImageView уже показана та же картинка (строку списка перерисовали из-за
 * других данных), она не трогается; заглушка ставится, только пока нужной картинки нет в памяти.
 * С диска читается в отдельном потоке, не дожидаясь сетевых загрузок, — сохранённое без сети
 * появляется сразу.
 *
 * В главном потоке нет ни одного обращения к диску: даже проверка «есть ли файл» и имя файла (SHA-1) — в потоке
 * [disk]. Раньше они выполнялись при показе каждой строки списка и давали подёргивания при прокрутке.
 */
class ImageCache private constructor(context: Context) {

    private val appContext = context.applicationContext
    /**
     * Папка картинок — в общей `Android/media/<пакет>/VkWebLite/Images` ([com.example.vkweb.files.AppFolder]). Определяется при первом обращении —
     * в потоке [disk] (обращение к диску).
     */
    private val dir by lazy { com.example.vkweb.files.AppFolder.dir(appContext, com.example.vkweb.files.AppFolder.IMAGES) }
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    // Потоки завершаются после простоя: между открытиями списков пять спящих потоков не держатся.
    private val disk = Background.pool("image-disk", DISK_THREADS)
    private val network = Background.pool("image-net", DOWNLOAD_THREADS)
    /** Заблаговременная загрузка идёт в одном потоке отдельно от показа: не задерживает картинки на экране. */
    private val background = Background.serial("image-prefetch", Thread.MIN_PRIORITY)
    private val main = Handler(Looper.getMainLooper())
    private val locks = Array(64) { Any() }
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private val publicationLock = Any()
    private val legacyMigrationLock = Any()
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** Серверы, к которым не удалось подключиться: их картинки временно не грузим. */
    private val cooldown = HostCooldown()

    init {
        // Папка и уборка недокачанного (процесс мог завершиться посреди загрузки) — в фоне, а не при создании.
        disk.execute {
            dir.mkdirs()
            synchronized(legacyMigrationLock) {
                // Перенести существующий кэш из Android/data после обновления; копия остаётся при любой ошибке.
                com.example.vkweb.files.AppFolder.legacyRoot(appContext)?.let { oldRoot ->
                    File(oldRoot, com.example.vkweb.files.AppFolder.IMAGES).listFiles()?.forEach { old ->
                        if (!old.isFile || old.name.endsWith(PART_SUFFIX)) return@forEach
                        val target = File(dir, old.name)
                        migrateLegacyImage(old, target)
                    }
                }
                // До 4.99 картинки лежали во внутренней памяти: это кэш — старая папка просто удаляется.
                File(appContext.filesDir, "offline_images").takeIf { it.exists() && it.canonicalPath != dir.canonicalPath }?.deleteRecursively()
                dir.listFiles()?.filter { it.name.endsWith(PART_SUFFIX) }?.forEach { it.delete() }
            }
        }
    }

    /** Move a legacy cache image only after an atomic copy has been checked byte-for-byte. */
    private fun migrateLegacyImage(source: File, target: File) {
        if (target.exists()) {
            if (sameContents(source, target)) source.delete()
            return
        }
        val temporary = File(target.parentFile, target.name + ".migrate" + PART_SUFFIX)
        runCatching {
            temporary.delete()
            source.copyTo(temporary, overwrite = true)
            if (!sameContents(source, temporary)) { temporary.delete(); return }
            if (!temporary.renameTo(target) && !target.exists()) {
                source.copyTo(target, overwrite = false)
                if (!sameContents(source, target)) target.delete()
            }
            if (target.exists() && sameContents(source, target)) source.delete()
        }.onFailure { temporary.delete() }
        temporary.delete()
    }

    private fun sameContents(first: File, second: File): Boolean = runCatching {
        first.length() == second.length() && digest(first).contentEquals(digest(second))
    }.getOrDefault(false)

    private fun digest(file: File): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    /** Занято на диске, байт; -1 — ещё не считали. Считается один раз, дальше ведётся при загрузках. */
    @Volatile private var usedBytes = -1L

    /** Предел размера кэша на диске (настройка). */
    @Volatile var maxBytes: Long = AppConfig.IMAGE_CACHE_DEFAULT_MB * MB

    /**
     * Показывает картинку в [view], подогнав под [targetPx] по большей стороне.
     * Пока её нет (грузится, нет сети, адрес пуст), в view — [placeholder].
     */
    fun load(view: ImageView, url: String?, targetPx: Int, placeholder: () -> Drawable? = { null }) {
        val key = if (url.isNullOrEmpty() || !isAllowed(url)) null else "${ImageKey.of(url)}@$targetPx:${generation.get()}"
        view.setTag(R.id.image_cache_requested, key)
        if (key != null && view.getTag(R.id.image_cache_shown) == key) return
        val cached = key?.let { memory.get(it) }
        if (cached != null) {
            show(view, key, cached)
            return
        }
        view.setImageDrawable(placeholder())
        view.setTag(R.id.image_cache_shown, null)
        if (key == null || url == null) return
        fetch(url, targetPx, isWanted = { view.getTag(R.id.image_cache_requested) == key }) { bitmap ->
            memory.put(key, bitmap)
            if (view.getTag(R.id.image_cache_requested) == key) show(view, key, bitmap)
        }
    }

    /**
     * Картинка с диска (поток [disk]) или из сети (поток [network]); [done] — в главном потоке, только если она есть.
     * [isWanted] проверяется перед загрузкой из сети: строку уже пролистали — качать незачем.
     */
    private fun fetch(
        url: String, targetPx: Int, isWanted: () -> Boolean = { true }, failed: () -> Unit = {}, done: (Bitmap) -> Unit,
    ) {
        val mine = generation.get()
        disk.execute {
            if (mine != generation.get()) return@execute
            val file = fileFor(url)
            if (file.isFile) {
                deliver(obtain(url, file, mine = mine), targetPx, mine, failed, done)
            } else main.post {
                if (mine == generation.get() && isWanted()) {
                    network.execute { deliver(obtain(url, file, mine = mine), targetPx, mine, failed, done) }
                }
            }
        }
    }

    /**
     * Как [load], но с запасными адресами (5.00.12): первый, который не отдался (сервер VK ответил 424 или не картинкой -
     * в WebView это ERR_BLOCKED_BY_ORB), сменяется следующим. Нужно для фото альбомов: у снимка несколько размеров,
     * и часть из них сервер иногда не отдаёт.
     */
    fun load(view: ImageView, urls: List<String>, targetPx: Int, placeholder: () -> Drawable? = { null }) {
        val list = urls.filter { it.isNotEmpty() && isAllowed(it) }.distinct()
        if (list.size <= 1) { load(view, list.firstOrNull(), targetPx, placeholder); return }
        val token = list.joinToString("|") + "@$targetPx:${generation.get()}"
        view.setTag(R.id.image_cache_requested, token)
        if (view.getTag(R.id.image_cache_shown) == token) return
        list.firstNotNullOfOrNull { memory.get("${ImageKey.of(it)}@$targetPx:${generation.get()}") }?.let { show(view, token, it); return }
        view.setImageDrawable(placeholder())
        view.setTag(R.id.image_cache_shown, null)
        fun attempt(index: Int) {
            val url = list.getOrNull(index) ?: return
            val key = "${ImageKey.of(url)}@$targetPx:${generation.get()}"
            fetch(url, targetPx, isWanted = { view.getTag(R.id.image_cache_requested) == token },
                failed = { if (view.getTag(R.id.image_cache_requested) == token) attempt(index + 1) }) { bitmap ->
                memory.put(key, bitmap)
                if (view.getTag(R.id.image_cache_requested) == token) show(view, token, bitmap)
            }
        }
        attempt(0)
    }

    private fun safeDecode(file: File, targetPx: Int): Bitmap? = try { decode(file, targetPx) } catch (_: IOException) { null }

    private fun deliver(file: File?, targetPx: Int, mine: Long, failed: () -> Unit = {}, done: (Bitmap) -> Unit) {
        if (mine != generation.get()) return
        val bitmap = file?.let { safeDecode(it, targetPx) }
        if (bitmap == null) { main.post { if (mine == generation.get()) failed() }; return }
        main.post { if (mine == generation.get()) done(bitmap) else bitmap.recycle() }
    }

    fun fileOf(url: String, done: (File?) -> Unit) {
        if (!isAllowed(url)) { done(null); return }
        val mine = generation.get()
        disk.execute {
            val file = fileFor(url)
            if (file.isFile) {
                val ready = obtain(url, file, mine = mine)
                main.post { done(ready.takeIf { mine == generation.get() }) }
            } else network.execute {
                val ready = obtain(url, file, ignoreCooldown = true, mine = mine)
                main.post { done(ready.takeIf { mine == generation.get() }) }
            }
        }
    }

    /**
     * Уже скачанная картинка — только с диска, без сети (4.29: аватар в уведомлении о сообщении). Нет на диске — null.
     * Синхронно: маленькая аватарка читается за миллисекунды, а уведомление показывается сразу, без ожидания загрузки.
     */
    fun cachedOnly(url: String, targetPx: Int): Bitmap? {
        if (url.isBlank() || !isAllowed(url)) return null
        val file = fileFor(url)
        return if (file.isFile) safeDecode(file, targetPx) else null
    }

    /** Для полноэкранного просмотра: картинка большой стороной до [maxPx]. */
    /**
     * [ignoreCooldown] — человек сам попросил повторить: сервер пробуется, даже если недавно не отвечал
     * (иначе кнопка «Повторить» несколько минут сразу отвечала бы отказом).
     */
    fun loadFull(url: String, maxPx: Int, ignoreCooldown: Boolean = false, result: (Bitmap?) -> Unit) {
        if (!isAllowed(url)) { result(null); return }
        val mine = generation.get()
        fun load(file: File) {
            val bitmap = obtain(url, file, ignoreCooldown, mine)?.let { safeDecode(it, maxPx) }
            main.post {
                if (mine == generation.get()) result(bitmap)
                else { bitmap?.recycle(); result(null) }
            }
        }
        disk.execute {
            val file = fileFor(url)
            if (file.isFile) load(file) else network.execute { load(file) }
        }
    }

    /**
     * Заранее скачивает картинки (без разбора), чтобы они были и без сети: аватарки списков, фото записей
     * сообществ. Только по безлимитной сети — на мобильной не тратим трафик; уже скачанное пропускается.
     * Не больше [MAX_PREFETCH] за раз, чтобы большой список не превратился в большую загрузку.
     */
    fun prefetch(urls: List<String>) {
        if (urls.isEmpty()) return
        val candidates = urls.distinct()
        val mine = generation.get()
        // Отбор (проверка сети и файлов на диске) — в фоновом потоке: список бывает в сотни адресов.
        background.execute {
            if (mine != generation.get() || connectivity?.isActiveNetworkMetered != false) return@execute
            var queued = 0
            for (url in candidates) {
                if (queued >= MAX_PREFETCH) break
                if (!isAllowed(url)) continue
                val file = fileFor(url)
                if (file.isFile) continue
                queued++
                background.execute { obtain(url, file, mine = mine) }
            }
            if (queued > 0) AppLog.d(LogTag.STORE, "картинки поставлены на заблаговременную загрузку", "штук" to queued)
        }
    }

    /** Сколько занято на диске. Читает папку: вызывать не из главного потока. */
    fun sizeBytes(): Long = synchronized(publicationLock) { countBytes().also { usedBytes = it } }

    /** Удаляет все картинки; [done] — в главном потоке. */
    fun clear(done: () -> Unit = {}) {
        generation.incrementAndGet()
        memory.evictAll()
        disk.execute {
            synchronized(publicationLock) {
                synchronized(legacyMigrationLock) {
                    dir.listFiles()?.filterNot { it.name.endsWith(PART_SUFFIX) }?.forEach { it.delete() }
                    com.example.vkweb.files.AppFolder.legacyRoot(appContext)?.let { oldRoot ->
                        File(oldRoot, com.example.vkweb.files.AppFolder.IMAGES).deleteRecursively()
                    }
                }
                usedBytes = 0
            }
            main.post(done)
        }
    }

    // ------------------------------------------------------------ внутреннее

    private fun show(view: ImageView, key: String, bitmap: Bitmap) {
        view.setImageBitmap(bitmap)
        view.setTag(R.id.image_cache_shown, key)
    }

    /** Файл с картинкой: с диска или скачанный сейчас (если есть сеть). */
    private fun obtain(url: String, file: File, ignoreCooldown: Boolean = false, mine: Long): File? {
        if (mine != generation.get()) return null
        val lock = locks[(ImageKey.of(url).hashCode() and Int.MAX_VALUE) % locks.size]
        synchronized(lock) {
            if (mine != generation.get()) return null
            if (file.isFile) { file.setLastModified(System.currentTimeMillis()); return file }
            return if (download(url, file, ignoreCooldown, mine)) file else null
        }
    }

    private fun download(url: String, target: File, ignoreCooldown: Boolean, mine: Long): Boolean {
        if (mine != generation.get()) return false
        val host = Uri.parse(url).host.orEmpty()
        if (!ignoreCooldown && cooldown.isBlocked(host, SystemClock.elapsedRealtime())) return false
        // Своё имя на каждую загрузку: два потока, качающие одну картинку, не пишут в один файл.
        val tmp = try {
            dir.mkdirs()
            File.createTempFile(target.name, PART_SUFFIX, dir)
        } catch (e: IOException) {
            AppLog.d(LogTag.STORE, "картинка не скачалась: нет места", "ошибка" to e.message)
            return false
        }
        var connection: HttpURLConnection? = null
        var connected = false
        return try {
            // 4.65: перенаправления — сами и только на серверы картинок VK (ImageDownloadRules); раньше HttpURLConnection
            // шёл за ними куда угодно.
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
                connected = true
                cooldown.onSuccess(host)
                if (!ImageDownloadRules.isRedirect(response.responseCode)) break
                val next = ImageDownloadRules.redirectTarget(current, response.getHeaderField("Location"))
                response.disconnect()
                if (next == null || ++redirects > ImageDownloadRules.MAX_REDIRECTS) {
                    AppLog.d(LogTag.STORE, "картинка не скачалась: перенаправление не на сервер картинок VK", "хост" to host)
                    tmp.delete()
                    return false
                }
                current = next
            } while (true)
            if (response.responseCode !in 200..299) {
                AppLog.d(LogTag.STORE, "картинка не скачалась", "код" to response.responseCode, "хост" to host)
                tmp.delete()
                return false
            }
            if (!response.contentType.orEmpty().startsWith("image/")) {
                AppLog.d(LogTag.STORE, "по адресу не картинка", "тип" to response.contentType, "хост" to host)
                tmp.delete()
                return false
            }
            if (ImageDownloadRules.tooLarge(response.contentLengthLong)) {
                AppLog.d(LogTag.STORE, "картинка не скачалась: слишком большая", "КБ" to response.contentLengthLong / 1024, "хост" to host)
                tmp.delete()
                return false
            }
            if (!copyLimited(response, tmp, mine)) {
                AppLog.d(LogTag.STORE, "картинка не скачалась: больше предела", "хост" to host)
                tmp.delete()
                return false
            }
            // Другой поток мог успеть первым: тогда его файл уже на месте, наш лишний.
            synchronized(publicationLock) {
                if (mine != generation.get()) false
                else if (target.isFile) true
                else tmp.renameTo(target).also { if (it) onStored(target.length()) }
            }
        } catch (e: IOException) {
            tmp.delete()
            if (!connected && cooldown.onConnectFailure(host, SystemClock.elapsedRealtime())) {
                AppLog.w(
                    LogTag.STORE, "сервер картинок не отвечает — картинки с него временно не грузим",
                    "хост" to host, "минут" to HostCooldown.DEFAULT_COOLDOWN_MS / 60_000, "ошибка" to e.message,
                )
            } else {
                AppLog.d(LogTag.STORE, "картинка не скачалась", "ошибка" to e.message, "хост" to host)
            }
            false
        } finally {
            tmp.delete()
            connection?.disconnect()
        }
    }

    /** Скопировать ответ в [target], не больше [ImageDownloadRules.MAX_BYTES]; false — больше предела (4.65). */
    private fun copyLimited(connection: HttpURLConnection, target: File, mine: Long): Boolean {
        var total = 0L
        connection.inputStream.use { input ->
            target.outputStream().use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var n = input.read(buffer)
                while (n >= 0) {
                    if (mine != generation.get()) return false
                    total += n
                    if (total > ImageDownloadRules.MAX_BYTES) return false
                    out.write(buffer, 0, n)
                    n = input.read(buffer)
                }
            }
        }
        return true
    }

    private fun decode(file: File, targetPx: Int): Bitmap? {
        // 4.65: без цели цикл ниже не кончался бы (sample переполнялся до нуля — деление на ноль в потоке загрузки).
        if (targetPx <= 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return decodeSvg(file, targetPx)
        var sample = 1
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        while (largest / (sample * 2) >= targetPx) sample *= 2
        // Огромный снимок не должен ронять приложение: при нехватке памяти освобождаем кэш в памяти и повторяем
        // с вдвое меньшим разрешением (раньше OutOfMemoryError в потоке загрузки завершал процесс).
        repeat(OOM_RETRIES) {
            try {
                val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
                // Степень двойки даёт до двух целей по стороне (до 4× памяти): большое — ещё и уменьшаем до цели, иначе
                // просмотр фото выходил за свой бюджет памяти, а огромный кадр не рисовался вовсе (код ревью 3.59).
                val side = maxOf(decoded.width, decoded.height)
                if (side <= targetPx * DOWNSCALE_OVER) return decoded
                val scale = targetPx.toFloat() / side
                val scaled = Bitmap.createScaledBitmap(decoded, maxOf(1, (decoded.width * scale).toInt()), maxOf(1, (decoded.height * scale).toInt()), true)
                if (scaled !== decoded) decoded.recycle()
                return scaled
            } catch (e: OutOfMemoryError) {
                memory.evictAll()
                sample *= 2
                AppLog.w(LogTag.STORE, "не хватило памяти для картинки, разрешение уменьшено", "в раз" to sample)
            }
        }
        return null
    }

    /**
     * SVG (4.63: картинки реакций VK приходят так) — рисуется в квадрат [targetPx] с сохранением пропорций. Не SVG
     * (или битый, или слишком большой файл) — null, как и раньше для нераспознанного.
     */
    private fun decodeSvg(file: File, targetPx: Int): Bitmap? {
        if (file.length() !in 1..SVG_MAX_BYTES || targetPx <= 0) return null
        val head = file.inputStream().use { input -> ByteArray(256).let { b -> String(b, 0, maxOf(0, input.read(b)), Charsets.UTF_8) } }
        if (!head.contains("<svg") && !head.trimStart().startsWith("<?xml")) return null
        return try {
            val svg = com.caverock.androidsvg.SVG.getFromString(file.readText())
            // Без viewBox размер документа не масштабируется — задаём его по собственным размерам.
            if (svg.documentViewBox == null && svg.documentWidth > 0 && svg.documentHeight > 0) {
                svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
            }
            val side = targetPx.coerceAtMost(SVG_MAX_PX)
            svg.setDocumentWidth(side.toFloat())
            svg.setDocumentHeight(side.toFloat())
            val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            svg.renderToCanvas(android.graphics.Canvas(bitmap))
            bitmap
        } catch (e: Exception) {
            AppLog.w(LogTag.STORE, "SVG не нарисован", "ошибка" to e.javaClass.simpleName)
            null
        }
    }

    /** Новый файл на диске: учесть размер, при превышении предела удалить самые старые. */
    @Synchronized
    private fun onStored(bytes: Long) {
        if (usedBytes < 0) usedBytes = countBytes() else usedBytes += bytes
        if (usedBytes <= maxBytes) return
        val files = storedFiles().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        val before = total
        for (file in files) {
            if (total <= maxBytes * TRIM_TO_PERCENT / 100) break
            total -= file.length()
            file.delete()
        }
        usedBytes = total
        AppLog.i(LogTag.STORE, "кэш картинок сокращён", "было, КБ" to before / 1024, "стало, КБ" to total / 1024)
    }

    private fun storedFiles(): List<File> = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(PART_SUFFIX) }.orEmpty()

    private fun countBytes(): Long = storedFiles().sumOf { it.length() }

    /** Имя файла — по ключу картинки ([ImageKey]), а не по адресу: смена подписи или сервера не плодит копии. */
    private fun fileFor(url: String) = File(dir, OfflineStore.sha1(ImageKey.of(url)))

    private fun isAllowed(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: return false
        val allowed = uri.scheme == "https" && AppConfig.isImageHost(host)
        // Отказ молчаливый (карточка остаётся без картинки), поэтому чужой хост — в журнал, по разу на хост.
        if (!allowed && rejectedHosts.add(host)) AppLog.w(LogTag.STORE, "картинка не загружается: хост не в списке", "хост" to host)
        return allowed
    }

    private val rejectedHosts = java.util.Collections.synchronizedSet(HashSet<String>())

    companion object {
        private const val MB = 1024L * 1024L
        private const val DISK_THREADS = 2
        private const val DOWNLOAD_THREADS = 3
        /** Подключение — недолго: недоступный сервер не должен держать поток загрузки ([HostCooldown]). */
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val TRIM_TO_PERCENT = 90
        private const val PART_SUFFIX = ".part"
        private const val MAX_PREFETCH = 60
        /** SVG больше — не картинка реакции, рисовать не берёмся. */
        private const val SVG_MAX_BYTES = 512L * 1024
        private const val SVG_MAX_PX = 512
        private const val OOM_RETRIES = 3
        /** Во сколько раз сторона может превысить цель, прежде чем картинку уменьшат до цели. */
        private const val DOWNSCALE_OVER = 1.25f
        private val MEMORY_BYTES = (Runtime.getRuntime().maxMemory() / 8).toInt()

        @Volatile private var instance: ImageCache? = null

        fun get(context: Context): ImageCache =
            instance ?: synchronized(this) { instance ?: ImageCache(context.applicationContext).also { instance = it } }

        fun megabytes(mb: Int): Long = mb * MB
    }
}
