package com.example.vkweb.messages

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap

/** Выбранный для отправки файл: адрес, имя, размер (-1 — неизвестен) и тип. */
data class PickedFile(val uri: Uri, val name: String, val size: Long, val mime: String) {

    companion object {
        /** Сведения о файле от системы. Читает провайдера файлов — не из главного потока. */
        fun describe(context: Context, uri: Uri): PickedFile {
            var name = ""
            var size = -1L
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0).orEmpty()
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            } catch (e: Exception) {
                // Провайдер недоступен: имя возьмём из адреса, размер выяснится при чтении.
            }
            if (name.isEmpty()) name = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifEmpty { "file" }
            val mime = context.contentResolver.getType(uri)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
            return PickedFile(uri, name.take(120), size, mime)
        }
    }
}
