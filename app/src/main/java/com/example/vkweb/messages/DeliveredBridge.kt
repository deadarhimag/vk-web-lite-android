package com.example.vkweb.messages

import com.example.vkweb.outbox.OutboxLogic

/**
 * Сообщения, которые сервер уже принял (ответ messages.send с номером), а в истории страницы их ещё нет (5.00.12).
 *
 * Отчёт 11.11 22:25: после ответа сервера карточка из очереди убиралась сразу, а история с этим сообщением приходила
 * через 0,2-0,4 с. Всё это время сообщения не было нигде: переписка сжималась на строку и тут же вырастала обратно,
 * второй раз - уже без прокрутки к последнему. Здесь принятое сообщение показывается под настоящим номером ("c" + номер
 * в беседе), пока его не пришлёт страница: тогда то же id просто обновляется на месте, и число строк не меняется.
 *
 * Без зависимостей от Android: проверяется обычными тестами.
 */
class DeliveredBridge(private val clock: () -> Long = System::currentTimeMillis) {

    private class Entry(val peerId: String, val message: ChatMessage, val addedAt: Long)

    private val entries = ArrayList<Entry>()

    /** Сервер принял [text] в переписке [peerId] под номером [cmid]. Ответ без номера не принимается. */
    fun add(peerId: String, text: String, cmid: Long) {
        if (peerId.isEmpty() || cmid <= 0) return
        val id = "c$cmid"
        if (entries.any { it.peerId == peerId && it.message.id == id }) return
        val now = clock()
        entries += Entry(peerId, ChatMessage(id = id, out = true, text = text, time = "", dateSec = now / 1000), now)
    }

    /** Открыта другая переписка или очередь удалена: принятые прежде не относятся к новому экрану. */
    fun clear() = entries.clear()

    /**
     * История [server] переписки [peerId] вместе с принятыми, которых в ней ещё нет. Пришедшие и устаревшие (дольше
     * [TTL_MS]) забываются. Черновая копия страницы с тем же текстом прячется - иначе одно сообщение было бы показано
     * дважды (как в [OutboxLogic.visibleMessages]).
     */
    fun visible(server: List<ChatMessage>, peerId: String): List<ChatMessage> {
        // Забыть пришедшие в историю и устаревшие
        val now = clock()
        val known = server.mapTo(HashSet()) { it.id }
        entries.removeAll { it.message.id in known || now - it.addedAt > TTL_MS }
        val mine = entries.filter { it.peerId == peerId }.map { it.message }
        if (mine.isEmpty()) return server

        // Спрятать черновые копии принятых
        val hidden = HashSet<Int>()
        for (message in mine) {
            val target = OutboxLogic.normalize(message.text)
            val index = server.indices.firstOrNull { i ->
                i !in hidden && server[i].out && !server[i].isConfirmed && OutboxLogic.normalize(server[i].text) == target
            }
            if (index != null) hidden += index
        }

        // Принятые - на своё место по номеру: перед первым настоящим с большим номером, иначе в конец
        val result = ArrayList(server.filterIndexed { i, _ -> i !in hidden })
        for (message in mine.sortedBy { cmid(it) }) {
            val number = cmid(message)
            val before = result.indexOfFirst { it.isConfirmed && cmid(it) > number }
            result.add(if (before >= 0) before else result.size, message)
        }
        return result
    }

    private fun cmid(message: ChatMessage): Long = message.id.drop(1).toLongOrNull() ?: 0

    private companion object {
        /** Дольше страница не тянет: если сообщения так и нет, его покажет следующая история (или его удалили). */
        const val TTL_MS = 30_000L
    }
}
