package com.example.vkweb.web

/**
 * Что и когда приходит от страницы: по каждому типу сообщений — когда пришло прошлое и сколько повторов отброшено
 * после него. Без зависимостей от Android: проверяется обычными тестами. Только поток разбора.
 */
class BridgeTrace {
    private class Entry {
        var lastAt = 0L
        var count = 0
        var dups = 0
    }

    private val entries = HashMap<String, Entry>()

    /** Пришёл повтор прошлого сообщения того же типа: он отброшен без разбора. */
    fun duplicate(type: String) {
        entries.getOrPut(type) { Entry() }.dups++
    }

    /** [sinceMs] — сколько прошло с прошлого сообщения этого типа (null — первое); [dupsBefore] — повторов отброшено между ними. */
    class Receipt(val sinceMs: Long?, val dupsBefore: Int, val number: Int)

    fun received(type: String, nowMs: Long): Receipt {
        val e = entries.getOrPut(type) { Entry() }
        val receipt = Receipt(if (e.count == 0) null else nowMs - e.lastAt, e.dups, e.count + 1)
        e.lastAt = nowMs
        e.count++
        e.dups = 0
        return receipt
    }
}
