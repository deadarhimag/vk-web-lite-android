package com.example.vkweb.diag

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.vkweb.R
import java.io.File
import java.io.IOException

/**
 * Передача файлов другим приложениям и запись в выбранное пользователем место.
 *  - [share]: файл во временной папке отдаётся через FileProvider (получатель читает его по временной ссылке,
 *    разрешения на память не нужны);
 *  - [writeTo]: текст записывается по адресу, который пользователь выбрал в системном окне «Сохранить как».
 * Во временной папке хранится только последний переданный файл: старые удаляются при создании нового.
 */
object FileShare {

    private const val DIR = "shared"

    /** Пустой файл [name] во временной папке; прежние переданные файлы удаляются. */
    fun newFile(context: Context, name: String): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, name)
    }

    /**
     * Своя временная папка для файлов одной передачи. Прежние удаляются, только когда им больше часа: приложение, в
     * которое передали раньше, может ещё читать свои файлы, а две передачи подряд не должны стирать друг друга
     * (код ревью 3.59).
     */
    fun newDir(context: Context): File {
        val root = File(context.cacheDir, DIR).apply { mkdirs() }
        val now = System.currentTimeMillis()
        root.listFiles()?.forEach { if (now - it.lastModified() > KEEP_SHARED_MS) it.deleteRecursively() }
        return File(root, "s" + now.toString(36) + "_" + (sequence++).toString(36)).apply { mkdirs() }
    }

    private var sequence = 0
    private const val KEEP_SHARED_MS = 60 * 60 * 1000L

    /**
     * Окно «Поделиться» для нескольких файлов ([files] — во временной папке) и текста [text] (может быть пустым):
     * фото в другой мессенджер вместе с подписью.
     */
    fun shareMany(context: Context, files: List<File>, mime: String, text: String) {
        if (files.size == 1) {
            val uri = FileProvider.getUriForFile(context, authority(context), files[0])
            val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (text.isNotEmpty()) send.putExtra(Intent.EXTRA_TEXT, text)
            send.clipData = ClipData.newRawUri(files[0].name, uri)
            context.startActivity(Intent.createChooser(send, context.getString(R.string.share_chooser)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            return
        }
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, authority(context), it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (text.isNotEmpty()) send.putExtra(Intent.EXTRA_TEXT, text)
        val clip = ClipData.newRawUri(files[0].name, uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        send.clipData = clip
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_chooser)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** Текст (сообщение, ссылка) — в другое приложение. */
    fun shareText(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_chooser)))
    }

    /** Показывает системное окно «Поделиться» для [file]. */
    fun share(context: Context, file: File, mime: String, title: String = context.getString(R.string.share_chooser)) {
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Без ClipData часть приложений не получает права на чтение файла (setClipData ничего не возвращает).
        send.clipData = ClipData.newRawUri(file.name, uri)
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** Записывает [text] по адресу [uri] (выбран пользователем). @return false — не удалось. */
    fun writeTo(context: Context, uri: Uri, text: String): Boolean = try {
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } != null
    } catch (e: IOException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun authority(context: Context) = context.packageName + AUTHORITY_SUFFIX

    private const val AUTHORITY_SUFFIX = ".files"
}
