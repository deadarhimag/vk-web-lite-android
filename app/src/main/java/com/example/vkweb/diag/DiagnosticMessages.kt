package com.example.vkweb.diag

import org.json.JSONException
import org.json.JSONObject

/**
 * Служебные сообщения страницы, которые идут в журнал и ни на какой экран не попадают: записи журнала скрипта
 * ([PageLog]) и сводка трафика ([TrafficSummary]). Один разбор на оба окна — главное и окно чтения сообщений
 * (раньше окно чтения свой журнал вовсе не получало).
 */
object DiagnosticMessages {

    /** @return true — сообщение служебное и обработано (дальше его передавать не нужно). */
    fun handle(type: String, raw: String): Boolean {
        if (type != PageLog.TYPE && type != TrafficSummary.TYPE) return false
        try {
            val json = JSONObject(raw)
            when (type) {
                PageLog.TYPE -> PageLog.parse(json)?.let(PageLog::write)
                else -> TrafficSummary.parse(json)?.let(TrafficSummary::write)
            }
        } catch (e: JSONException) {
            AppLog.w(LogTag.BRIDGE, "служебное сообщение страницы не разобрано", "тип" to type)
        }
        return true
    }
}
