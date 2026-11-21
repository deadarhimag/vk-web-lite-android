package com.example.vkweb.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.app.DownloadManager
import android.os.Build
import android.provider.DocumentsContract
import java.io.File

/**
 * Папка данных приложения: `Android/media/<пакет>/VkWebLite` на общем хранилище, если оно доступно, иначе внутренняя
 * папка приложения. Музыкальный кэш на Android 10+ хранится отдельно, в общей `Download/VkWebLite/Music`.
 *
 * Папку данных можно открыть из приложения через поставщик документов ([AppFilesProvider]). Старую папку в Android/data
 * переносим по возможности при запуске. Android может удалить эту app-specific папку при удалении приложения.
 */
object AppFolder {
    const val NAME = "VkWebLite"
    const val MUSIC = "Music"
    const val IMAGES = "Images"
    const val DEV = "Dev"

    @Volatile private var root: File? = null

    /** Корень папки (создаётся при необходимости). Обращается к диску — не из главного потока. */
    fun root(context: Context): File = root ?: synchronized(this) {
        root ?: run {
            val app = context.applicationContext
            val base = runCatching { app.getExternalMediaDirs().firstOrNull() }.getOrNull()
                ?: runCatching { app.getExternalFilesDir(null) }.getOrNull() ?: app.filesDir
            File(base, NAME).apply { mkdirs() }.also { root = it }
        }
    }

    /** Предыдущее расположение в Android/data (4.99.7 и старше); для безопасного переноса при первом запуске. */
    fun legacyRoot(context: Context): File? = runCatching {
        context.applicationContext.getExternalFilesDir(null)?.let { File(it, NAME) }
    }.getOrNull()?.takeIf { it.absolutePath != root(context).absolutePath }

    /** Подпапка [name] (создаётся при необходимости). Не из главного потока. */
    fun dir(context: Context, name: String): File = File(root(context), name).apply { mkdirs() }

    /** Корень уже известен (без обращения к диску); null — ещё не определён. */
    fun knownRoot(): File? = root

    /** Папка лежит на общем хранилище и доступна файловым менеджерам. */
    fun isShared(context: Context): Boolean = root(context).absolutePath.contains("/Android/media/")

    /** Показать папку (или её подпапку [sub]) в «Файлах». false — показать нечем. */
    fun open(context: Context, sub: String? = null): Boolean {
        val authority = AppFilesProvider.authority(context)
        // Начальная папка должна существовать, иначе системный picker может открыть свою папку по умолчанию (обычно Downloads).
        if (sub != null) dir(context, sub)
        val initialUri = DocumentsContract.buildDocumentUri(authority, AppFilesProvider.documentId(sub.orEmpty()))
        // DocumentsProvider открывается через SAF. ACTION_VIEW здесь не подходит: файловые менеджеры могут проигнорировать
        // URI провайдера и показать свою папку по умолчанию.
        val browse = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            .addFlags(if (context is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(browse)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** Папка музыкального кэша в стандартном Download-разделе. */
    fun openMusicDownloads(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return open(context, MUSIC)
        val flags = if (context is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
        val downloads = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(flags)
        try {
            context.startActivity(downloads)
            return true
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
        val initialUri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")
        val browse = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            .addFlags(flags)
        return try {
            context.startActivity(browse)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
