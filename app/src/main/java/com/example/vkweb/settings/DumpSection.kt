package com.example.vkweb.settings

import android.content.Context
import com.example.vkweb.ui.Notice
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.result.ActivityResultLauncher
import androidx.preference.Preference
import com.example.vkweb.R
import com.example.vkweb.dev.DumpRecorder
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.DiagnosticReport
import com.example.vkweb.diag.FileShare
import com.example.vkweb.diag.LogTag
import java.io.File
import java.io.IOException

/**
 * Раздел настроек «Для разработки»: сколько собрано данных страниц и сохранение их в файл,
 * который пользователь выбирает сам (без разрешений на память). Удаление — в «Сохранённых данных».
 */
class DumpSection(
    private val context: Context,
    private val exportItem: Preference,
    private val shareItem: Preference,
    /** Запуск выбора файла: в ответ приходит адрес, куда писать (или null — отменили). */
    private val createDocument: ActivityResultLauncher<String>,
) {
    private val recorder = DumpRecorder.get(context)
    private var active = true

    init {
        exportItem.setOnPreferenceClickListener {
            createDocument.launch(FILE_NAME)
            true
        }
        shareItem.setOnPreferenceClickListener { share(); true }
    }

    /** Пересчитать, сколько собрано. */
    fun refresh() {
        recorder.stats { stats ->
            if (!active) return@stats
            val size = Formatter.formatShortFileSize(context, stats.bytes)
            exportItem.isEnabled = stats.records > 0
            shareItem.isEnabled = stats.records > 0
            exportItem.summary = when {
                stats.records == 0 -> context.getString(R.string.pref_dump_export_empty)
                stats.full -> context.getString(R.string.pref_dump_export_full, stats.records, size)
                else -> context.getString(R.string.pref_dump_export_summary, stats.records, size)
            }
        }
    }

    /** Пользователь выбрал файл (или отменил — [uri] null). */
    fun onDocumentCreated(uri: Uri?) {
        if (uri == null) return
        val output = try {
            context.contentResolver.openOutputStream(uri)
        } catch (e: SecurityException) {
            null
        } catch (e: java.io.FileNotFoundException) {
            null
        }
        if (output == null) {
            Notice.show(context, R.string.pref_dump_export_failed)
            return
        }
        recorder.export(output) { count ->
            if (!active) return@export
            val text = if (count == null) context.getString(R.string.pref_dump_export_failed)
            else context.getString(R.string.pref_dump_exported, count)
            Notice.show(context, text)
        }
    }

    /**
     * Выгружает собранное во временный файл и отдаёт системному окну «Поделиться» — с 4.11 вместе с отчётом
     * (журнал событий за то же время): по одним ответам страниц не видно, что в это время делало приложение —
     * какие экраны открывались, что не загрузилось, чего ждали. Отчёт — тот же, что «Поделиться отчётом».
     */
    private fun share() {
        Notice.show(context, R.string.pref_log_preparing)
        val dir = FileShare.newDir(context)
        val file = File(dir, FILE_NAME)
        val output = try {
            file.outputStream()
        } catch (e: IOException) {
            null
        }
        if (output == null) {
            Notice.show(context, R.string.pref_dump_export_failed)
            return
        }
        recorder.export(output) { count ->
            AppLog.i(LogTag.DUMP, "собранное подготовлено для отправки", "записей" to count)
            if (!active) return@export
            if (count == null) {
                Notice.show(context, R.string.pref_dump_export_failed)
                return@export
            }
            DiagnosticReport.build(
                context,
                consume = { report ->
                    try {
                        File(dir, REPORT_NAME).apply { writeText(report) }
                    } catch (e: IOException) {
                        // Без отчёта — только собранное: главное отправить его.
                        AppLog.w(LogTag.DUMP, "отчёт к собранному не записан", "ошибка" to e.javaClass.simpleName)
                        null
                    }
                },
                done = { report ->
                    if (active) FileShare.shareMany(context, listOfNotNull(file, report), BOTH_MIME, "")
                },
            )
        }
    }

    /** Экран настроек закрыт: результаты больше некуда показывать. */
    fun detach() {
        active = false
    }

    private companion object {
        const val FILE_NAME = "vk_dump.json"
        const val REPORT_NAME = "vkweb_report.txt"
        /** Разные типы в одной передаче (JSON и текст). */
        const val BOTH_MIME = "*/*"
    }
}
