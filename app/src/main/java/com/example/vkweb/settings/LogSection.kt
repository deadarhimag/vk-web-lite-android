package com.example.vkweb.settings

import android.content.Context
import com.example.vkweb.ui.Notice
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.DiagnosticReport
import com.example.vkweb.diag.FileShare
import com.example.vkweb.diag.LogSetting
import com.example.vkweb.diag.LogTag
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Раздел настроек «Журнал и диагностика»: уровень журнала событий, отправка отчёта и сохранение его в файл.
 * Отчёт ([DiagnosticReport]) собирается в момент нажатия, поэтому в нём всегда свежее состояние.
 * Удаление журнала — в «Сохранённых данных».
 */
class LogSection(
    private val context: Context,
    private val levelItem: ListPreference,
    private val shareItem: Preference,
    private val saveItem: Preference,
    /** Запуск выбора файла: в ответ приходит адрес, куда писать (или null — отменили). */
    private val createDocument: ActivityResultLauncher<String>,
) {
    private var active = true

    init {
        levelItem.entries = LogSetting.VALUES.map { context.getString(labelOf(it)) }.toTypedArray()
        levelItem.entryValues = LogSetting.VALUES.toTypedArray()
        if (levelItem.value == null) levelItem.value = LogSetting.DEFAULT
        shareItem.setOnPreferenceClickListener { share(); true }
        saveItem.setOnPreferenceClickListener { createDocument.launch(fileName()); true }
    }

    /** Применить выбранный уровень сразу (до возврата на главный экран). */
    fun onLevelChanged(value: String?) {
        AppLog.configure(LogSetting.level(value))
        AppLog.i(LogTag.APP, "уровень журнала изменён", "значение" to value)
    }

    /** Пользователь выбрал файл (или отменил — [uri] null). */
    fun onDocumentCreated(uri: Uri?) {
        if (uri == null) return
        toast(R.string.pref_log_preparing)
        DiagnosticReport.build(
            context,
            consume = { report -> FileShare.writeTo(context, uri, report).also { saved -> logSaved(saved, report) } },
            done = { saved -> if (active) toast(if (saved) R.string.pref_log_saved else R.string.pref_log_failed) },
        )
    }

    /** Экран настроек закрыт: результаты больше некуда показывать. */
    fun detach() {
        active = false
    }

    private fun share() {
        toast(R.string.pref_log_preparing)
        DiagnosticReport.build(
            context,
            consume = { report -> writeTemporary(report) },
            done = { file ->
                if (!active) return@build
                if (file == null) toast(R.string.pref_log_failed) else FileShare.share(context, file, MIME)
            },
        )
    }

    /** Пишет отчёт во временный файл (вызывается в фоновом потоке). @return null — не удалось. */
    private fun writeTemporary(report: String): File? = try {
        FileShare.newFile(context, fileName()).also { it.writeText(report) }.also {
            AppLog.i(LogTag.APP, "отчёт подготовлен для отправки", "байт" to report.length)
        }
    } catch (e: IOException) {
        AppLog.e(LogTag.APP, "не удалось подготовить отчёт для отправки", e)
        null
    }

    private fun logSaved(saved: Boolean, report: String) {
        AppLog.i(LogTag.APP, "отчёт сохранён в файл", "успешно" to saved, "байт" to report.length)
    }

    private fun labelOf(value: String) = when (value) {
        LogSetting.OFF -> R.string.pref_log_level_off
        LogSetting.DEBUG -> R.string.pref_log_level_debug
        else -> R.string.pref_log_level_info
    }

    private fun toast(text: Int) = Notice.show(context, text)

    private fun fileName(): String =
        "vkweb_report_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm", Locale.ROOT)) + ".txt"

    companion object {
        /** Тип файла при сохранении и отправке. */
        const val MIME = "text/plain"
    }
}
