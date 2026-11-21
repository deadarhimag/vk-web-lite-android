package com.example.vkweb.outbox

import com.example.vkweb.messages.ChatMessage
import com.example.vkweb.messages.MessageStatus

/**
 * Правила очереди неотправленных сообщений. Без зависимостей от Android: проверяются обычными тестами.
 *
 * Главная трудность: «Отправить» на странице VK — это нажатие кнопки; ответ «ok» значит только «нажали», а не
 * «сервер принял». Поэтому доставку подтверждает только появление в переписке настоящего сообщения
 * ([ChatMessage.isConfirmed]), которого не было до нажатия. Повторять отправку сами можно лишь то, что
 * точно не уходило ([OutboxState.QUEUED]); всё сомнительное ([OutboxState.UNCONFIRMED]) ждёт решения
 * человека — иначе при обрыве связи получился бы дубль.
 */
object OutboxLogic {

    /** Сколько ждать появления сообщения в переписке после нажатия, прежде чем счесть его неподтверждённым. */
    const val CONFIRM_TIMEOUT_MS = 45_000L

    /**
     * Сколько неудач подряд (страница не приняла ввод) допускается, прежде чем сообщение станет [OutboxState.FAILED].
     */
    const val MAX_ATTEMPTS = 5

    /** Предел очереди: чтобы она не росла без границ, если связи нет неделями. */
    const val MAX_ITEMS = 200

    private val BACKOFF_MS = longArrayOf(3_000, 10_000, 30_000, 60_000)
    private val WHITESPACE = Regex("\\s+")

    /** Текст для сравнения: VK может поменять переводы строк и пробелы по краям. */
    fun normalize(text: String): String = WHITESPACE.replace(text.trim(), " ")

    /** Очередь заполнена: новое сообщение принять нельзя. */
    fun isFull(items: List<OutboxItem>): Boolean = items.size >= MAX_ITEMS

    /** Добавить сообщение в конец очереди (в заполненную не добавляется). */
    fun enqueue(items: List<OutboxItem>, item: OutboxItem): List<OutboxItem> =
        if (isFull(items)) items else items + item

    /**
     * Что отправлять следующим в переписку [peerId] — или null. Порядок сохраняется: одно сообщение «в полёте»
     * за раз, за ним по очереди остальные; пока у первого не подошло время повтора, второе не обгоняет его.
     * Сомнительные и неудавшиеся ([OutboxState.UNCONFIRMED], [OutboxState.FAILED]) очередь не блокируют.
     */
    fun nextToSend(items: List<OutboxItem>, peerId: String, now: Long): OutboxItem? {
        val mine = items.filter { it.peerId == peerId }.sortedBy { it.createdAt }
        if (mine.any { it.state == OutboxState.SENDING }) return null
        // Отложенное (4.17), время которого не пришло, очередь не держит: остальные уходят как обычно.
        val first = mine.firstOrNull { it.state == OutboxState.QUEUED && !it.isScheduled(now) } ?: return null
        return first.takeIf { it.retryAt <= now }
    }

    /**
     * Кнопка нажата: ждём подтверждения. [baseline] — см. [baselineFor]. Счётчик попыток не меняется:
     * он считает только неудачи ([markNotSent]), иначе одна неудача засчитывалась бы дважды.
     */
    fun markClicked(items: List<OutboxItem>, id: String, now: Long, baseline: List<String>, receiptFloor: Long = 0): List<OutboxItem> =
        update(items, id) { it.copy(state = OutboxState.SENDING, sentAt = now, baseline = baseline,
            confirmedThrough = maxOf(receiptFloor, it.confirmedThrough, baseline.mapNotNull { key -> key.removePrefix("c").toLongOrNull() }.maxOrNull() ?: 0)) }

    /**
     * Кнопка не нажалась (страница не приняла ввод): точно не ушло, повторим позже; после [MAX_ATTEMPTS] —
     * [OutboxState.FAILED].
     */
    fun markNotSent(items: List<OutboxItem>, id: String, now: Long): List<OutboxItem> = update(items, id) { item ->
        val attempts = item.attempts + 1
        if (attempts >= MAX_ATTEMPTS) item.copy(state = OutboxState.FAILED, attempts = attempts)
        else item.copy(state = OutboxState.QUEUED, attempts = attempts, retryAt = now + backoff(attempts))
    }

    /** Человек решил отправить ещё раз: счётчики и подтверждённое прошлое сбрасываются. */
    fun retry(items: List<OutboxItem>, id: String): List<OutboxItem> = update(items, id) { item ->
        if (item.state == OutboxState.UNCONFIRMED || item.state == OutboxState.FAILED) {
            item.copy(state = OutboxState.QUEUED, attempts = 0, sentAt = 0, retryAt = 0, baseline = emptyList(), confirmationBlocked = false)
        } else item
    }

    /** Человек решил не отправлять (или удалил сомнительное): убрать из очереди. */
    fun discard(items: List<OutboxItem>, id: String): List<OutboxItem> = items.filterNot { it.id == id }

    /**
     * «Вернуть» после удаления (3.94): пункт снова в очереди на своём месте (по времени создания). Отправлявшееся
     * возвращается как [OutboxState.UNCONFIRMED] — пока его не было в очереди, ответ страницы мог прийти без него.
     * Уже вернувшийся (повторное нажатие) не дублируется.
     */
    fun undoDiscard(items: List<OutboxItem>, item: OutboxItem): List<OutboxItem> {
        if (items.any { it.id == item.id }) return items
        val restored = if (item.state == OutboxState.SENDING) item.copy(state = OutboxState.UNCONFIRMED) else item
        return (items + restored).sortedBy { it.createdAt }
    }

    /**
     * Очередь после запуска приложения. Приложение могло закрыться между нажатием и подтверждением — про такое
     * сообщение ничего не известно, поэтому оно становится [OutboxState.UNCONFIRMED], а не уходит повторно.
     */
    fun restore(items: List<OutboxItem>): List<OutboxItem> =
        items.map { if (it.state == OutboxState.SENDING) it.copy(state = OutboxState.UNCONFIRMED) else it }

    /**
     * Ответ с номером сообщения подтверждает единственное подходящее ожидание. Неоднозначные тексты, ответы без
     * peer/cmid и ранее использованные номера не удаляют очередь. Они могут разрешиться по истории или вручную.
     */
    fun deliveredByServer(items: List<OutboxItem>, peerId: String, text: String, cmid: Long = 0): Reconciled {
        // A text-only response can belong to another operation or be a duplicate of an earlier response.
        if (peerId.isEmpty() || cmid <= 0) return Reconciled(items, emptyList())
        val target = normalize(text)
        val candidates = items.filter {
            it.peerId == peerId &&
                (it.state == OutboxState.SENDING || it.state == OutboxState.UNCONFIRMED) && normalize(it.text) == target
        }
        if (candidates.size > 1) return Reconciled(items.map {
            if (it in candidates) it.copy(confirmationBlocked = true) else it
        }, emptyList())
        val match = candidates.singleOrNull()?.takeIf { !it.confirmationBlocked && cmid > it.confirmedThrough && "c$cmid" !in it.baseline }
            ?: return Reconciled(items, emptyList())
        return Reconciled(consume(items.filterNot { it.id == match.id }, peerId, target, cmid), listOf(match), cmid)
    }

    private fun consume(items: List<OutboxItem>, peer: String, text: String, cmid: Long) = items.map {
        if (it.peerId == peer && normalize(it.text) == text) it.copy(confirmedThrough = maxOf(it.confirmedThrough, cmid)) else it
    }

    /** Номера уже существующих своих сообщений с таким же текстом — на момент нажатия «Отправить». */
    fun baselineFor(item: OutboxItem, history: List<ChatMessage>): List<String> {
        val target = normalize(item.text)
        return history.filter { it.out && it.isConfirmed && normalize(it.text) == target }.map { it.id }
    }

    /** Результат сверки очереди с перепиской: что осталось и что оказалось доставленным. */
    data class Reconciled(val items: List<OutboxItem>, val delivered: List<OutboxItem>, val confirmedThrough: Long = 0)

    /**
     * Сверяет очередь переписки [peerId] с её свежей историей. Нажатое сообщение доставлено, если в истории есть
     * своё подтверждённое сообщение с тем же текстом, которого не было при нажатии. Каждое сообщение истории
     * засчитывается не больше чем одному из ожидающих. Не дождавшееся подтверждения за [CONFIRM_TIMEOUT_MS]
     * становится [OutboxState.UNCONFIRMED] (но продолжает сверяться: оно может появиться позже).
     */
    fun reconcile(items: List<OutboxItem>, peerId: String, history: List<ChatMessage>, now: Long): Reconciled {
        val confirmedOut = history.filter { it.out && it.isConfirmed }
        val used = HashSet<String>()
        val delivered = ArrayList<OutboxItem>()
        val remaining = ArrayList<OutboxItem>(items.size)
        val waitingCounts = items.filter { it.peerId == peerId && (it.state == OutboxState.SENDING || it.state == OutboxState.UNCONFIRMED) }
            .groupingBy { normalize(it.text) }.eachCount()
        val receipts = HashMap<String, Long>()
        for (item in items) {
            val waiting = item.peerId == peerId && (item.state == OutboxState.SENDING || item.state == OutboxState.UNCONFIRMED)
            if (!waiting) {
                remaining += item
                continue
            }
            val target = normalize(item.text)
            val ambiguous = waitingCounts.getValue(target) > 1
            val match = if (ambiguous || item.confirmationBlocked) null else confirmedOut.firstOrNull {
                (it.id.drop(1).toLongOrNull() ?: 0) > item.confirmedThrough &&
                    it.id !in item.baseline && it.id !in used && normalize(it.text) == target
            }
            when {
                match != null -> {
                    used += match.id
                    delivered += item
                    receipts[target] = maxOf(receipts[target] ?: 0, match.id.drop(1).toLongOrNull() ?: 0)
                }
                item.state == OutboxState.SENDING && now - item.sentAt >= CONFIRM_TIMEOUT_MS ->
                    remaining += item.copy(state = OutboxState.UNCONFIRMED, confirmationBlocked = item.confirmationBlocked || ambiguous)
                else -> remaining += if (ambiguous) item.copy(confirmationBlocked = true) else item
            }
        }
        var unclaimed: List<OutboxItem> = remaining
        receipts.forEach { (text, cmid) -> unclaimed = consume(unclaimed, peerId, text, cmid) }
        return Reconciled(unclaimed, delivered, receipts.values.maxOrNull() ?: 0)
    }

    /**
     * Нажатые сообщения, которые слишком долго не подтверждаются, становятся [OutboxState.UNCONFIRMED] —
     * в любой переписке, не только открытой (историю закрытой никто не читает, а ожидание не бесконечно).
     */
    fun expire(items: List<OutboxItem>, now: Long): List<OutboxItem> = items.map { item ->
        if (item.state == OutboxState.SENDING && now - item.sentAt >= CONFIRM_TIMEOUT_MS) item.copy(state = OutboxState.UNCONFIRMED) else item
    }

    /**
     * Что показать в переписке: сообщения сервера и ниже — неотправленные с пометкой. Черновые копии, которые
     * страница сама дорисовала после нажатия, прячутся (иначе одно сообщение было бы показано дважды).
     */
    fun visibleMessages(
        server: List<ChatMessage>, items: List<OutboxItem>, peerId: String, now: Long = System.currentTimeMillis(),
    ): List<ChatMessage> {
        val mine = items.filter { it.peerId == peerId }.sortedBy { it.createdAt }
        if (mine.isEmpty()) return server
        val hidden = HashSet<Int>()
        for (item in mine) {
            if (item.state != OutboxState.SENDING && item.state != OutboxState.UNCONFIRMED) continue
            val target = normalize(item.text)
            val index = server.indices.firstOrNull { i ->
                i !in hidden && server[i].out && !server[i].isConfirmed && normalize(server[i].text) == target
            }
            if (index != null) hidden += index
        }
        val shown = server.filterIndexed { i, _ -> i !in hidden }
        return shown + mine.map { toMessage(it, now) }
    }

    /**
     * Номер отправки для messages.send (4.22): постоянный для сообщения очереди — сервер VK не принимает второе
     * сообщение с тем же номером, и повтор после оборвавшегося ответа не задваивает. 1…2^31-1.
     */
    fun randomIdOf(itemId: String): Int = (itemId.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)

    // ------------------------------------------------------------ отложенная отправка (4.17)

    /** Есть ли что отправить сейчас (время пришло) или что ждёт подтверждения: для фоновой работы. */
    fun hasDueWork(items: List<OutboxItem>, now: Long): Boolean = items.any {
        it.state == OutboxState.SENDING || (it.state == OutboxState.QUEUED && !it.isScheduled(now))
    }

    /** Earliest retry among the heads of ready conversations; future schedules use their own alarm. */
    fun nextRetryDelay(items: List<OutboxItem>, now: Long): Long = items
        .filter { it.state == OutboxState.QUEUED && !it.isScheduled(now) }
        .groupBy { it.peerId }.values.mapNotNull { peer -> peer.minByOrNull { it.createdAt } }
        .minOfOrNull { (it.retryAt - now).coerceAtLeast(0) } ?: 0L

    /** Ближайшее время отложенной отправки (мс) или null. */
    fun nextScheduledAt(items: List<OutboxItem>, now: Long): Long? =
        items.filter { it.state == OutboxState.QUEUED && it.isScheduled(now) }.minOfOrNull { it.sendAt }

    /** Переписки, где отложенное уже пора отправить. */
    fun duePeers(items: List<OutboxItem>, now: Long): Set<String> =
        items.filter { it.state == OutboxState.QUEUED && it.sendAt in 1..now }.mapTo(HashSet()) { it.peerId }

    /**
     * Когда уйдёт отложенное — словами: «в 18:30», «завтра в 09:00», «16 октября в 09:00» (другой год — с годом).
     * [zone] — часовой пояс телефона.
     */
    fun scheduleLabel(sendAt: Long, now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val at = java.time.Instant.ofEpochMilli(sendAt).atZone(zone)
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time = at.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val day = at.toLocalDate()
        return when {
            day == today -> "в $time"
            day == today.plusDays(1) -> "завтра в $time"
            day.year == today.year -> at.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM", RU)) + " в $time"
            else -> at.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", RU)) + " в $time"
        }
    }

    private val RU = java.util.Locale.forLanguageTag("ru")

    /** Собеседники, которым есть неотправленное: пометка в списке диалогов. */
    fun pendingPeers(items: List<OutboxItem>): Set<String> = items.mapTo(HashSet()) { it.peerId }

    /** Через сколько миллисекунд проверить очередь снова (повтор или истечение ожидания); null — не нужно. */
    fun nextWakeupDelay(items: List<OutboxItem>, now: Long): Long? = items.mapNotNull { item ->
        when (item.state) {
            OutboxState.QUEUED -> maxOf(item.retryAt, item.sendAt).takeIf { it > now }
            OutboxState.SENDING -> item.sentAt + CONFIRM_TIMEOUT_MS
            else -> null
        }
    }.minOrNull()?.let { (it - now).coerceAtLeast(0) }

    /** Идентификатор «сообщения» в списке для пункта очереди. */
    fun messageId(item: OutboxItem) = "o:${item.id}"

    private fun toMessage(item: OutboxItem, now: Long) = ChatMessage(
        id = messageId(item),
        out = true,
        text = item.text,
        // У отложенного — когда уйдёт (подпись «Запланировано · завтра в 09:00»).
        time = if (item.state == OutboxState.QUEUED && item.isScheduled(now)) scheduleLabel(item.sendAt, now) else "",
        dateSec = item.createdAt / 1000,
        deliveryNeedsReview = item.confirmationBlocked,
        status = when (item.state) {
            OutboxState.QUEUED -> if (item.isScheduled(now)) MessageStatus.SCHEDULED else MessageStatus.QUEUED
            OutboxState.SENDING -> MessageStatus.SENDING
            OutboxState.UNCONFIRMED -> MessageStatus.UNCONFIRMED
            OutboxState.FAILED -> MessageStatus.FAILED
        },
    )

    private fun backoff(attempts: Int): Long = BACKOFF_MS[(attempts - 1).coerceIn(0, BACKOFF_MS.lastIndex)]

    private fun update(items: List<OutboxItem>, id: String, change: (OutboxItem) -> OutboxItem): List<OutboxItem> =
        items.map { if (it.id == id) change(it) else it }
}
