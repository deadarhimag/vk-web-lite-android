package com.example.vkweb.web

import android.os.SystemClock
import com.example.vkweb.Background
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag

/**
 * Входящие сообщения страницы: общее для главного окна ([PageBridge]) и окон чтения сообщений
 * ([com.example.vkweb.messages.ReaderChannel]) — раньше у каждого была своя копия.
 *
 *  - разбор идёт в отдельном потоке (данные бывают в сотни килобайт), поток один — порядок сохраняется;
 *  - повтор прошлого сообщения того же типа отбрасывается без разбора (страница шлёт одно и то же по нескольку раз);
 *  - каждое принятое сообщение записывается в журнал: тип, размер, число элементов, сколько прошло с прошлого такого
 *    и сколько повторов отброшено — по журналу видно, что и когда приходило.
 */
class MessageInbox(name: String) {
    private val executor = Background.serial(name)
    private val lastSeen = HashMap<String, String>()
    private val trace = BridgeTrace()

    /** Выполнить в потоке разбора. */
    fun post(task: () -> Unit) = executor.execute(task)

    /**
     * Поток разбора. @return false — это повтор прошлого сообщения такого типа: разбирать не нужно.
     * Сообщения без типа не сравниваются.
     */
    fun isNew(type: String, raw: String): Boolean {
        if (type.isEmpty()) return true
        if (lastSeen[type] == raw) {
            trace.duplicate(type)
            return false
        }
        lastSeen[type] = raw
        return true
    }

    /** Поток разбора. Сообщение принято и разобрано: запись в журнал. */
    fun received(type: String, bytes: Int, items: Int? = null) {
        val receipt = trace.received(type, SystemClock.elapsedRealtime())
        AppLog.d(
            LogTag.BRIDGE, "от страницы",
            "тип" to type, "байт" to bytes, "элементов" to items, "№" to receipt.number,
            "после прошлого, мс" to receipt.sinceMs, "повторов отброшено" to receipt.dupsBefore.takeIf { it > 0 },
        )
    }

    /**
     * Страницу о чём-то просили: ответ может совпасть с прежним сообщением, но дойти должен — память о прошлых
     * сообщениях сбрасывается.
     */
    fun forgetLast() = executor.execute { lastSeen.clear() }
}
