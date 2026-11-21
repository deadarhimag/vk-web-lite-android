package com.example.vkweb.files

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException

/**
 * Папка приложения в «Загрузках»: `Download/VkWebLite/<подпапка>` (5.00.12, для сохранённых альбомов). На Android 10+
 * файлы публикуются через MediaStore Downloads - разрешений не нужно, папку видят файловые менеджеры и она остаётся
 * после удаления приложения. На Android 8-9 общей папки без разрешения нет - там это подпапка [AppFolder].
 *
 * [relative] - путь внутри `VkWebLite`, например "Photos/Лето". Все обращения к диску - не из главного потока.
 */
object DownloadsFolder {

    /** Файлы уходят в общие «Загрузки» (Android 10+); false - в папку приложения. */
    val isShared: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Как показать путь человеку: "Загрузки/VkWebLite/Photos/Лето" или "VkWebLite/Photos/Лето" (папка приложения). */
    fun displayPath(relative: String): String =
        if (isShared) "Загрузки/${AppFolder.NAME}/$relative" else "${AppFolder.NAME}/$relative"

    /** Подпапки [relative], в которых уже есть файлы приложения (имена без пути). */
    fun subfolders(context: Context, relative: String): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return File(AppFolder.root(context), relative).list()?.toSet().orEmpty()
        val prefix = relativePath(relative)
        val out = HashSet<String>()
        query(context, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$prefix%")) { _, path ->
            path.removePrefix(prefix).substringBefore('/').takeIf { it.isNotEmpty() }?.let(out::add)
        }
        return out
    }

    /** Имена уже сохранённых файлов в [relative] (готовых, не недописанных). */
    fun names(context: Context, relative: String): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return File(AppFolder.root(context), relative).list()?.toSet().orEmpty()
        val out = HashSet<String>()
        query(context, "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0", arrayOf(relativePath(relative))) { name, _ ->
            out += name
        }
        return out
    }

    /**
     * Опубликовать готовый файл [source] под именем [name] в [relative]; [modifiedMs] - дата (0 - сейчас).
     * @return false - не записалось (место, доступ); недописанное удаляется.
     */
    fun publish(context: Context, relative: String, name: String, mime: String, source: File, modifiedMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = File(AppFolder.root(context), relative).apply { mkdirs() }
            val target = File(dir, name)
            return try {
                source.copyTo(target, overwrite = true)
                if (modifiedMs > 0) target.setLastModified(modifiedMs)
                true
            } catch (e: IOException) {
                target.delete()
                false
            }
        }
        return publishShared(context, relative, name, mime, source, modifiedMs)
    }

    /** Открыть папку [relative] в «Файлах»; false - открыть нечем. */
    fun open(context: Context, relative: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return AppFolder.open(context, relative)
        val flags = if (context is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
        val initial = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:${Environment.DIRECTORY_DOWNLOADS}/${AppFolder.NAME}/$relative",
        )
        val browse = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial).addFlags(flags)
        return try {
            context.startActivity(browse)
            true
        } catch (_: ActivityNotFoundException) {
            AppFolder.openMusicDownloads(context)
        } catch (_: SecurityException) {
            false
        }
    }

    // ------------------------------------------------------------ MediaStore

    private fun relativePath(relative: String): String =
        "${Environment.DIRECTORY_DOWNLOADS}/${AppFolder.NAME}/${relative.trim('/')}/"

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishShared(context: Context, relative: String, name: String, mime: String, source: File, modifiedMs: Long): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(relative))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (modifiedMs > 0) put(MediaStore.MediaColumns.DATE_MODIFIED, modifiedMs / 1000)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            // Сначала файл целиком, затем снять «недописан»: другие приложения не увидят половину
            val output = resolver.openOutputStream(uri, "w") ?: throw IOException("нет потока записи")
            output.use { out -> source.inputStream().use { it.copyTo(out) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, done, null, null) <= 0) throw IOException("не снят признак записи")
            true
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    /** Свои файлы в «Загрузках» по условию: [row] получает имя и относительный путь. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun query(context: Context, selection: String, args: Array<String>, row: (String, String) -> Unit) {
        val projection = arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
        runCatching {
            context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { c ->
                val nameColumn = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val pathColumn = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (c.moveToNext()) row(c.getString(nameColumn).orEmpty(), c.getString(pathColumn).orEmpty())
            }
        }
    }
}
