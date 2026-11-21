package com.example.vkweb.dev

import org.json.JSONObject

/**
 * Сводка собранных записей для выгрузки (4.11): сколько, за какое время, из каких окон и страниц. Раньше файл содержал
 * только записи, и по нему нельзя было понять, какой сборкой и откуда он собран, не перебирая все записи. Считается на
 * ходу при выгрузке, по строкам как есть (без разбора JSON): записи бывают по сотне килобайт.
 * Без Android: проверяется [DumpSummaryTest].
 */
class DumpSummary {

    var records = 0
        private set

    /** Время первой и последней записи (ISO, как в поле at). */
    var first: String? = null
        private set
    var last: String? = null
        private set

    private val windows = LinkedHashMap<String, Int>()
    private val pages = HashMap<String, Int>()

    fun add(line: String) {
        records++
        val window = WINDOW.find(line)?.groupValues?.get(1) ?: NO_WINDOW
        windows[window] = (windows[window] ?: 0) + 1
        PAGE.find(line)?.groupValues?.get(1)?.let { page ->
            if (pages.size < MAX_PAGES_TRACKED || page in pages) pages[page] = (pages[page] ?: 0) + 1
        }
        AT.find(line)?.groupValues?.get(1)?.let { at ->
            if (first == null) first = at
            last = at
        }
    }

    /** {records, from, to, windows: {окно: число}, pages: {страница: число} — самые частые}. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("records", records)
        first?.let { put("from", it) }
        last?.let { put("to", it) }
        put("windows", JSONObject().apply { windows.forEach { (k, v) -> put(k, v) } })
        put("pages", JSONObject().apply {
            pages.entries.sortedByDescending { it.value }.take(TOP_PAGES).forEach { (k, v) -> put(k, v) }
        })
    }

    private companion object {
        /** Окно записи — первое поле, его дописывает DumpRecorder.appendRaw. */
        val WINDOW = Regex("^\\{\"window\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        /** Первые поля записи (shared/dump_core.js): page, at — до тела ответа, поэтому первое совпадение — их. */
        val PAGE = Regex("\"page\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        val AT = Regex("\"at\":\"([^\"]+)\"")
        const val NO_WINDOW = "?"
        const val TOP_PAGES = 20
        const val MAX_PAGES_TRACKED = 500
    }
}
