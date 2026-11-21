package com.example.vkweb.diag

import com.example.vkweb.web.PageMedia
import org.json.JSONObject

/**
 * Записи журнала от скрипта страницы (assets/vk_lite/40_screens.js, модуль Diag): какие ответы приняты и
 * пропущены, где не совпала структура. Приходят сообщением type = [TYPE] и попадают в общий журнал с меткой SCRIPT.
 */
object PageLog {
    /** Тип сообщения страницы (поле type). */
    const val TYPE = "log"
    private const val MAX_PAGE = 100
    private const val MAX_MESSAGE = 700

    class Record(val level: LogLevel, val message: String, val page: String)

    /** null — пустая запись: пропускается. */
    fun parse(json: JSONObject): Record? {
        val message = json.optString("message").take(MAX_MESSAGE)
        if (message.isBlank()) return null
        return Record(
            level = LogFormat.levelOf(json.optString("level")),
            message = message,
            page = json.optString("page").take(MAX_PAGE),
        )
    }

    fun write(record: Record) {
        val r = adjusted(record, PageMedia.blocked)
        AppLog.log(r.level, LogTag.SCRIPT, r.message, arrayOf("страница" to r.page.ifEmpty { null }))
    }

    /**
     * Событие плеера скрытой страницы, вызванное тем, что её файлы видео не грузятся ([PageMedia]), — подробность,
     * а не предупреждение (4.30): уровень D и пометка. Остальное без изменений.
     */
    fun adjusted(record: Record, pageMediaBlocked: Boolean): Record =
        if (record.level == LogLevel.DEBUG || !PageMedia.isSelfInflicted(record.message, pageMediaBlocked)) record
        else Record(LogLevel.DEBUG, record.message + SELF_INFLICTED, record.page)

    private const val SELF_INFLICTED = " (ожидаемо: страница скрыта, файлы видео ей не грузятся)"
}
