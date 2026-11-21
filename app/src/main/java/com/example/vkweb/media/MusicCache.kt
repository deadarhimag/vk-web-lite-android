package com.example.vkweb.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.example.vkweb.files.AppFolder
import java.io.File
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Кэш музыки — mp3 с тегами в `Download/VkWebLite/Music`. На Android 10+ используется MediaStore Downloads, поэтому
 * файлы видны файловым менеджерам и остаются после удаления приложения. На Android 8–9 используется папка приложения.
 * Старые файлы из предыдущих расположений переносятся при первом запуске после обновления.
 *
 * Имя файла — «Исполнитель - Название (владелец_номер).mp3»: по номеру в скобках файл узнаётся при следующем запуске.
 * Файлы прежних версий переносятся сюда при запуске ([warmUp]): 4.90–4.96 — из корня папки приложения (`<номер>.mp3`),
 * 4.97–4.98 — из «Музыка/VkWebLite».
 *
 * Что сохранено — список в памяти: [isCached] и [uriFor] не трогают диск. Ограничения размера нет.
 */
object MusicCache {
    private const val PART = ".part"
    private const val EXTENSION = ".mp3"
    /** Меньше — заведомо не трек (обрыв до первых кадров). */
    private const val MIN_BYTES = 1024L
    private const val MAX_NAME = 120
    /** Номер в конце имени MP3; при конфликте имён система добавляет суффикс вида (1) после номера. */
    private val ID_IN_NAME = Regex("""\((-?\d{1,12}_\d{1,12})\)(?: \(\d{1,4}\))?\.mp3$""")
    private val LEGACY_NAME = Regex("""^(-?\d{1,12}_\d{1,12})\.mp3$""")

    private val lock = Any()
    @Volatile private var generation = 0L
    private data class CachedTrack(val uri: Uri, val file: File?, val size: Long)
    /** Номер трека → файл кэша. */
    private val index = ConcurrentHashMap<String, CachedTrack>()
    @Volatile private var appContext: Context? = null
    @Volatile private var scanned = false
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    // ------------------------------------------------------------ имена (без Android: проверяются тестами)

    /** «Исполнитель - Название (id).mp3» без символов, запрещённых в именах файлов. */
    fun displayName(trackId: String, artist: String, title: String): String {
        val raw = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { "VK track" }
        val safe = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .replace(Regex("[\\x00-\\x1F<>:\"/\\\\|?*()]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim().trimEnd('.', ' ').take(MAX_NAME).ifBlank { "VK track" }
        return "$safe ($trackId)$EXTENSION"
    }

    /** Номер трека по имени файла кэша (нынешнему или 4.90–4.96); null — файл не из кэша. */
    fun idFromName(name: String): String? =
        ID_IN_NAME.find(name)?.groupValues?.get(1) ?: LEGACY_NAME.find(name)?.groupValues?.get(1)

    // ------------------------------------------------------------ что сохранено

    fun isCached(trackId: String): Boolean = trackId.isNotEmpty() && index.containsKey(trackId)

    /** Прочитана ли уже папка: до этого «нет в кэше» может быть неправдой. */
    val isReady: Boolean get() = scanned

    /** Адрес трека для проигрывателя; null — его нет. */
    fun uriFor(trackId: String): String? = index[trackId]?.uri?.toString()

    fun count(): Int = index.size
    fun sizeBytes(): Long = index.values.sumOf { it.size }

    /** Содержимое кэша изменилось: в главном потоке. */
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private fun changed() {
        if (listeners.isEmpty()) return
        main.post { listeners.forEach { it() } }
    }

    /** Папка для промежуточного хранения/миграции; готовые MP3 на Android 10+ публикуются через MediaStore. */
    fun directory(context: Context): File = AppFolder.dir(context, AppFolder.MUSIC)

    fun temporaryFileFor(context: Context, trackId: String): File {
        val safeTrackId = trackId.replace(Regex("[^0-9_-]"), "_")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File(context.cacheDir, "vk-music-$safeTrackId$EXTENSION$PART")
        } else File(directory(context), safeTrackId + EXTENSION + PART)
    }

    /** Прочитать папку в список (один раз за процесс), перенести файлы прежних версий. Не из главного потока. */
    fun warmUp(context: Context) {
        if (scanned) return
        rescan(context)
    }

    /** Перечитать папку (например, после возврата в приложение: файлы могли удалить в «Файлах»). Не из главного потока. */
    fun rescan(context: Context) {
        val app = context.applicationContext
        appContext = app
        synchronized(lock) {
            index.clear()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                migrateToDownloads(app)
                readDownloads(app)
            } else {
                val dir = directory(app)
                migrateOldAppFolder(app, dir)
                migrateLegacyRoot(app, dir)
                migrateSharedMusic(app, dir)
                dir.listFiles()?.forEach { file ->
                    when {
                        file.name.endsWith(PART) -> if (!scanned) file.delete() // незавершённые загрузки прошлых запусков
                        !file.isFile || file.length() < MIN_BYTES -> Unit
                        else -> idFromName(file.name)?.let { index[it] = CachedTrack(Uri.fromFile(file), file, file.length()) }
                    }
                }
            }
            scanned = true
        }
        changed()
    }

    /** Перенести прежний кэш в Download и затем заново прочитать собственные записи MediaStore. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun migrateToDownloads(app: Context) {
        val staging = directory(app)
        // Промежуточная копия остаётся в устойчивой папке приложения: сбой миграции не теряет исходные MP3.
        migrateOldAppFolder(app, staging)
        migrateLegacyRoot(app, staging)
        migrateSharedMusic(app, staging)
        staging.listFiles()?.forEach { file ->
            if (file.isFile && idFromName(file.name) != null && importFileToDownloads(app, file)) file.delete()
            else if (file.name.endsWith(PART)) file.delete()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun readDownloads(app: Context) {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE, MediaStore.MediaColumns.DATA)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
        app.contentResolver.query(collection, projection, selection, arrayOf(downloadRelativePath()), null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val dataColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn).orEmpty()
                val trackId = idFromName(name) ?: continue
                val size = if (cursor.isNull(sizeColumn)) 0L else cursor.getLong(sizeColumn)
                if (size < MIN_BYTES) continue
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                val file = if (dataColumn >= 0) cursor.getString(dataColumn)?.let(::File)?.takeIf { it.isFile } else null
                index[trackId] = CachedTrack(uri, file, size)
            }
        }
    }

    private fun downloadRelativePath(): String = "${Environment.DIRECTORY_DOWNLOADS}/VkWebLite/Music/"

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findDownload(app: Context, name: String): Uri? {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
        return app.contentResolver.query(collection, projection, selection, arrayOf(downloadRelativePath(), name), null)?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }
    }

    /** Создать собственный файл в Download/VkWebLite/Music атомарно для других приложений. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeDownload(app: Context, name: String, source: () -> java.io.InputStream?): Uri? {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, downloadRelativePath())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val written = runCatching {
            val input = source() ?: error("Cache source unavailable")
            val copied = input.use { src ->
                val output = resolver.openOutputStream(uri, "w") ?: error("MediaStore output unavailable")
                output.use { src.copyTo(it) }
            }
            if (copied < MIN_BYTES) error("Incomplete cache file")
            if (resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) <= 0) {
                error("Could not publish cache file")
            }
            true
        }.getOrDefault(false)
        if (!written) runCatching { resolver.delete(uri, null, null) }
        return uri.takeIf { written }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun importFileToDownloads(app: Context, source: File): Boolean {
        val name = source.name
        val existing = findDownload(app, name)
        if (existing != null) {
            val same = runCatching { app.contentResolver.openInputStream(existing)?.use { sameContents(source, it) } == true }.getOrDefault(false)
            return same
        }
        val target = writeDownload(app, name) { source.inputStream() } ?: return false
        val same = runCatching { app.contentResolver.openInputStream(target)?.use { sameContents(source, it) } == true }.getOrDefault(false)
        if (!same) runCatching { app.contentResolver.delete(target, null, null) }
        return same
    }

    /** Перенести прежнюю музыку из Android/data в папку приложения, не теряя исходник при ошибке. */
    private fun migrateOldAppFolder(app: Context, dir: File) {
        val old = AppFolder.legacyRoot(app)?.let { File(it, AppFolder.MUSIC) } ?: return
        old.listFiles()?.forEach { source ->
            if (!source.isFile || source.name.endsWith(PART)) return@forEach
            val target = File(dir, source.name)
            migrateFile(source, target)
        }
    }

    /** Atomic best-effort copy; keep the source if a same-name destination differs or the copy is incomplete. */
    private fun migrateFile(source: File, target: File) {
        if (target.exists()) {
            if (sameContents(source, target)) source.delete()
            return
        }
        val temporary = File(target.parentFile, target.name + ".migrate" + PART)
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
        return file.inputStream().buffered().use { digest(it).second }
    }

    private fun digest(input: java.io.InputStream): Pair<Long, ByteArray> {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            digest.update(buffer, 0, count)
        }
        return total to digest.digest()
    }

    private fun sameContents(file: File, input: java.io.InputStream): Boolean = runCatching {
        val (size, hash) = digest(input)
        file.length() == size && digest(file).contentEquals(hash)
    }.getOrDefault(false)

    private fun migrateMediaFile(app: Context, uri: Uri, target: File): Boolean {
        if (target.exists()) {
            val same = runCatching {
                app.contentResolver.openInputStream(uri)?.use { sameContents(target, it) } == true
            }.getOrDefault(false)
            if (same) runCatching { app.contentResolver.delete(uri, null, null) }
            return same
        }
        val temporary = File(target.parentFile, target.name + ".migrate" + PART)
        val copied = runCatching {
            temporary.delete()
            app.contentResolver.openInputStream(uri)?.use { input -> temporary.outputStream().use { input.copyTo(it) } }
                ?: return@runCatching false
            if (temporary.length() < MIN_BYTES) return@runCatching false
            val verified = app.contentResolver.openInputStream(uri)?.use { sameContents(temporary, it) } == true
            verified && temporary.renameTo(target)
        }.getOrDefault(false)
        if (copied) runCatching { app.contentResolver.delete(uri, null, null) } else temporary.delete()
        return copied
    }

    /** 4.90–4.96: `VkWebLite/<номер>.mp3` в корне папки приложения → `VkWebLite/Music/<Исполнитель - Название (номер)>.mp3`. */
    private fun migrateLegacyRoot(app: Context, dir: File) {
        listOfNotNull(AppFolder.legacyRoot(app), AppFolder.root(app)).distinctBy { it.absolutePath }.forEach { root ->
            root.listFiles()?.forEach file@{ file ->
                if (file.isFile && file.name.endsWith(PART)) { file.delete(); return@file }
                val id = LEGACY_NAME.find(file.name)?.groupValues?.get(1) ?: return@file
                val (artist, title) = Id3Tag.readTitleArtist(file)
                val target = File(dir, displayName(id, artist, title))
                migrateFile(file, target)
            }
        }
    }

    /** 4.97–4.98: «Музыка/VkWebLite» → Android/media (свои файлы; на Android 8–9 — если есть разрешение на запись). */
    private fun migrateSharedMusic(app: Context, dir: File) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                app.contentResolver.query(
                    collection, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME),
                    "${MediaStore.Audio.Media.RELATIVE_PATH} = ?", arrayOf(Environment.DIRECTORY_MUSIC + "/VkWebLite/"), null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1).orEmpty()
                        if (idFromName(name) == null) continue
                        val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                        val target = File(dir, name)
                        migrateMediaFile(app, uri, target)
                    }
                }
            } else if (app.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                @Suppress("DEPRECATION")
                val shared = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "VkWebLite")
                shared.listFiles()?.forEach { file ->
                    if (idFromName(file.name) == null) return@forEach
                    migrateFile(file, File(dir, file.name))
                }
                if (shared.listFiles().isNullOrEmpty()) shared.delete()
            }
        }
    }

    /** Clear leftovers from the pre-4.99 shared Music folder so a later rescan cannot restore deleted cache files. */
    private fun clearLegacySharedMusic(app: Context) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                app.contentResolver.query(
                    collection, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME),
                    "${MediaStore.Audio.Media.RELATIVE_PATH} = ?", arrayOf(Environment.DIRECTORY_MUSIC + "/VkWebLite/"), null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        if (idFromName(cursor.getString(1).orEmpty()) == null) continue
                        val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                        app.contentResolver.delete(uri, null, null)
                    }
                }
            } else if (app.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                @Suppress("DEPRECATION")
                val shared = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "VkWebLite")
                shared.listFiles()?.filter { idFromName(it.name) != null }?.forEach { it.delete() }
                if (shared.listFiles().isNullOrEmpty()) shared.delete()
            }
        }
    }

    // UI reads this while migration/publication may hold the filesystem lock.
    fun currentGeneration(): Long = generation

    /**
     * Готовый mp3 с тегами ([temporary], `.part`) — под имя трека, если кэш не очищали с начала загрузки.
     * [names] — (исполнитель, название). Не из главного потока.
     */
    fun publish(context: Context, trackId: String, names: Pair<String, String>, temporary: File, expectedGeneration: Long): Boolean {
        appContext = context.applicationContext
        synchronized(lock) {
            if (generation != expectedGeneration) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val name = displayName(trackId, names.first, names.second)
                // Сначала полностью записать новое MediaStore-значение; только после успеха убрать прежний кэш.
                val uri = writeDownload(context.applicationContext, name) { temporary.inputStream() } ?: return false
                val file = mediaFile(context, uri)
                index.put(trackId, CachedTrack(uri, file, temporary.length()))?.let { old ->
                    if (old.uri != uri && old.uri.scheme == "content") runCatching { context.contentResolver.delete(old.uri, null, null) }
                    else if (old.file != null && old.file != file) old.file.delete()
                }
                temporary.delete()
            } else {
                val target = File(directory(context), displayName(trackId, names.first, names.second))
                index.remove(trackId)?.file?.takeIf { it != target }?.delete()
                if (target.exists()) target.delete()
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
                index[trackId] = CachedTrack(Uri.fromFile(target), target, target.length())
            }
        }
        changed()
        return true
    }

    /** Удалить трек из кэша. Не из главного потока. */
    fun remove(trackId: String): Boolean {
        val removed = synchronized(lock) {
            val cached = index[trackId] ?: return@synchronized true
            val deleted = if (cached.uri.scheme == "content") {
                val app = appContext ?: return@synchronized false
                runCatching {
                    app.contentResolver.delete(cached.uri, null, null) > 0 ||
                        app.contentResolver.query(cached.uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { !it.moveToFirst() } == true
                }.getOrDefault(false)
            } else cached.file?.let { !it.exists() || it.delete() } ?: true
            deleted.also { if (it) index.remove(trackId) }
        }
        changed()
        return removed
    }

    /** Файл (или папку) удалили в «Файлах» через [com.example.vkweb.files.AppFilesProvider]: забыть их треки. */
    fun onFileDeleted(file: File) {
        val path = file.absolutePath
        val before = index.size
        index.entries.removeIf { (_, cached) -> cached.file?.let { it.absolutePath == path || it.absolutePath.startsWith("$path/") } == true }
        if (index.size != before) changed()
    }

    /** Удалить весь кэш музыки и остановить загрузки в полёте. Не из главного потока. */
    fun clear(context: Context) {
        appContext = context.applicationContext
        synchronized(lock) {
            generation++
            index.clear()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                deleteDownloads(context.applicationContext)
                directory(context).listFiles()?.filter { idFromName(it.name) != null || it.name.endsWith(PART) }?.forEach { it.deleteRecursively() }
            } else directory(context).listFiles()?.forEach { it.deleteRecursively() }
            AppFolder.legacyRoot(context)?.let { File(it, AppFolder.MUSIC).deleteRecursively() }
            clearLegacySharedMusic(context.applicationContext)
        }
        changed()
    }

    private fun mediaFile(context: Context, uri: Uri): File? {
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.let(::File)?.takeIf { it.isFile } else null
            }
        }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deleteDownloads(app: Context) {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        app.contentResolver.query(collection, projection, selection, arrayOf(downloadRelativePath()), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (idFromName(cursor.getString(1).orEmpty()) == null) continue
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                app.contentResolver.delete(uri, null, null)
            }
        }
    }
}
