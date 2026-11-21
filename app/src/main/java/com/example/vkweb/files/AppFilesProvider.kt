package com.example.vkweb.files

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.example.vkweb.R
import java.io.File
import java.io.FileNotFoundException

/**
 * Папка данных приложения ([AppFolder]) в системном приложении «Файлы» (4.99): просмотр, открытие, копирование и удаление.
 * Документ — путь относительно корня папки («root» — сам корень, «root/Images/x» — файл); выйти за корень нельзя.
 * Кэш музыки на Android 10+ находится в общей папке Download и этим провайдером не обслуживается.
 */
class AppFilesProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    private val rootDir: File get() = AppFolder.root(context!!)

    /** Корень с раскрытыми ссылками — с ним сравниваются файлы из [fileOf]. */
    private val realRoot: File get() = rootDir.toPath().toRealPath().toFile()

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        val ctx = context!!
        cursor.newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(Root.COLUMN_DOCUMENT_ID, documentId(""))
            .add(Root.COLUMN_TITLE, ctx.getString(R.string.app_name))
            .add(Root.COLUMN_SUMMARY, ctx.getString(R.string.app_folder_summary))
            .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            .add(Root.COLUMN_AVAILABLE_BYTES, rootDir.freeSpace)
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).also { addRow(it, documentId, fileOf(documentId)) }

    override fun queryChildDocuments(parentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val parent = fileOf(parentId)
        parent.listFiles()
            // Недокачанное и служебные скрытые файлы (отметка альбома фото) не показываются
            ?.filterNot { it.name.endsWith(".part") || it.name.startsWith(".") }
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            ?.forEach { addRow(cursor, idOf(it), it) }
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = fileOf(documentId)
        if (file.isDirectory) throw FileNotFoundException("Directory")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun deleteDocument(documentId: String) {
        val file = fileOf(documentId)
        if (file == realRoot) throw SecurityException("Root cannot be deleted")
        if (!file.deleteRecursively()) throw FileNotFoundException("Not deleted")
        com.example.vkweb.media.MusicCache.onFileDeleted(file)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = runCatching {
        val parent = fileOf(parentDocumentId).canonicalPath
        fileOf(documentId).canonicalPath.startsWith("$parent/")
    }.getOrDefault(false)

    override fun getDocumentType(documentId: String): String = mimeOf(fileOf(documentId))

    private fun addRow(cursor: MatrixCursor, id: String, file: File) {
        var flags = 0
        if (file != realRoot) flags = flags or Document.FLAG_SUPPORTS_DELETE
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, if (file == realRoot) context!!.getString(R.string.app_name) else file.name)
            .add(Document.COLUMN_MIME_TYPE, mimeOf(file))
            .add(Document.COLUMN_SIZE, if (file.isFile) file.length() else null)
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, flags)
    }

    /** Файл документа; путь за пределами корня (`..`, ссылки) — нет такого документа. */
    private fun fileOf(documentId: String): File {
        val file = resolve(rootDir, documentId) ?: throw FileNotFoundException(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        return file
    }

    private fun idOf(file: File): String =
        documentId(rootDir.toPath().toRealPath().relativize(file.toPath().toRealPath()).joinToString("/"))

    private fun mimeOf(file: File): String = when {
        file.isDirectory -> Document.MIME_TYPE_DIR
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
    }

    companion object {
        const val ROOT_ID = "root"

        /** Документ подпапки или файла по пути относительно корня ("" — корень). */
        fun documentId(relative: String): String = if (relative.isEmpty()) ROOT_ID else "$ROOT_ID/$relative"

        /** Адрес поставщика — как в манифесте (`${'$'}{applicationId}.documents`; «.files» — у FileProvider). */
        fun authority(context: Context) = context.packageName + AUTHORITY_SUFFIX
        const val AUTHORITY_SUFFIX = ".documents"

        /**
         * Файл документа [documentId] внутри [root]; null — чужой идентификатор или путь за пределами корня. Символьные
         * ссылки раскрываются через `toRealPath` (`canonicalFile` на Windows их не раскрывает), сравнение — по частям пути.
         */
        fun resolve(root: File, documentId: String): File? {
            if (documentId != ROOT_ID && !documentId.startsWith("$ROOT_ID/")) return null
            val relative = documentId.removePrefix(ROOT_ID).removePrefix("/")
            return runCatching {
                val base = root.toPath().toRealPath()
                val candidate = base.resolve(relative).normalize()
                if (!candidate.startsWith(base)) return null
                val real = if (java.nio.file.Files.exists(candidate)) candidate.toRealPath() else candidate
                real.takeIf { it.startsWith(base) }?.toFile()
            }.getOrNull()
        }

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS,
            Root.COLUMN_ICON, Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
