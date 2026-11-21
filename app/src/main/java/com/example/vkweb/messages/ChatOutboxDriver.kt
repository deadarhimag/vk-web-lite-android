package com.example.vkweb.messages

import com.example.vkweb.outbox.OutboxItem
import com.example.vkweb.outbox.OutboxLogic
import com.example.vkweb.outbox.OutboxState
import com.example.vkweb.sync.OutboxAccess
import java.util.UUID

/**
 * Очередь неотправленных в открытой переписке: когда нажимать «Отправить» на странице, как дождаться подтверждения,
 * что делать, если страница не отвечает. Решения о самих сообщениях принимает [OutboxLogic]; здесь — порядок действий во
 * времени. Без зависимостей от Android: экран ([Host]) даёт страницу и таймер, поэтому всё проверяется обычными тестами.
 *
 * Порядок: сообщение уходит, только когда страница готова (пришла свежая история — значит, поле ввода на месте), есть
 * сеть и предыдущее не «в полёте». После нажатия ждём, что сообщение появится в истории: перечитываем её каждые
 * [CONFIRM_POLL_MS], а если страница сама не обновилась, перезагружаем её (не чаще раза в [CONFIRM_RELOAD_MS]).
 */
class ChatOutboxDriver(
    private val outbox: OutboxAccess,
    private val host: Host,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Что нужно от экрана переписки. */
    interface Host {
        /** Открытая переписка или null (список диалогов). */
        val peerId: String?

        /** Экран — переписка, и страница мессенджера не заменена сайтом. */
        val isChatOpen: Boolean

        val isOnline: Boolean

        /** Сообщения открытой переписки, как их дала страница (без неотправленных из очереди). */
        val serverHistory: List<ChatMessage>

        /** Нажать «Отправить» на странице с этим текстом. */
        fun sendToPage(text: String)

        /** Перечитать историю у открытой страницы. */
        fun requestHistory()

        /** Перезагрузить страницу; экран обязан сообщить об этом [onPageReloading]. */
        fun reloadPage()

        /** Через [delayMs] вызвать [tick]; прежнее ожидание отменяется. */
        fun scheduleTick(delayMs: Long)

        fun cancelTick()

        fun log(message: String, vararg fields: Pair<String, Any?>)
    }

    private var ready = false

    /** Принятые сервером, которых ещё нет в истории страницы: показываются, пока она их не пришлёт (5.00.12). */
    private val acceptedBridge = DeliveredBridge(clock)

    /** История открытой переписки [peerId] вместе с принятыми сервером, но ещё не пришедшими от страницы. */
    fun withAccepted(server: List<ChatMessage>, peerId: String): List<ChatMessage> = acceptedBridge.visible(server, peerId)

    /** Сообщение, на которое нажата кнопка и ещё не пришёл ответ страницы. */
    private var sendingId: String? = null
    private var lastConfirmReload = 0L

    /** От страницы пришла свежая история: можно отправлять и проверять доставку. */
    val isReady: Boolean get() = ready
    val currentPeerId: String? get() = host.peerId

    /** Открыта другая переписка: прежняя готовность и ожидание ответа не относятся к ней. */
    fun onChatOpened() {
        ready = false
        sendingId = null
        acceptedBridge.clear()
    }

    /** Страница перезагружается: пока она грузится, поле ввода недоступно — отправлять нельзя. */
    fun onPageReloading() {
        ready = false
    }

    /** Пришла свежая история открытой переписки: сверить очередь, отправить следующее. */
    fun onFreshHistory() {
        ready = true
        host.peerId?.let { peer ->
            outbox.update { items ->
                val result = OutboxLogic.reconcile(items, peer, host.serverHistory, clock())
                outbox.recordReceipt(peer, result.confirmedThrough)
                if (result.delivered.isNotEmpty()) outbox.delivered(result.delivered)
                result.items
            }
        }
        drain()
        schedule()
    }

    /** Очередь изменилась или вернулась сеть: отправить, если можно. */
    fun onOutboxChanged() {
        drain()
        schedule()
    }

    /**
     * Поставить сообщение в очередь; [sendAt] — отложить до этого момента (4.17), [title] — название переписки для
     * уведомления. @return false — очередь заполнена или переписка не открыта.
     */
    fun submit(text: String, sendAt: Long = 0, title: String = "", onCommitted: (Boolean) -> Unit = {}): Boolean {
        val peer = host.peerId ?: return false
        if (OutboxLogic.isFull(outbox.items())) return false
        val item = OutboxItem(id = newId(), peerId = peer, text = text, createdAt = clock(), sendAt = sendAt, title = title)
        host.log(
            if (sendAt > 0) "сообщение отложено" else "сообщение поставлено в очередь",
            "сеть" to host.isOnline, "страница готова" to ready, "через, мин" to if (sendAt > 0) (sendAt - clock()) / 60_000 else null,
        )
        outbox.update { OutboxLogic.enqueue(it, item) }
        outbox.whenPersisted { ok ->
            val accepted = ok && outbox.items().any { it.id == item.id }
            if (!ok) outbox.update { OutboxLogic.discard(it, item.id) }
            onCommitted(accepted)
        }
        return true
    }

    /**
     * Ответ страницы на нажатие. «Нажата» ещё не значит «доставлена»: доставку подтверждает только появление
     * сообщения в истории ([OutboxLogic.reconcile]). Не нажалась — точно не ушло, повторим позже.
     */
    fun onSent(ok: Boolean) {
        val id = sendingId
        sendingId = null
        if (id != null && !ok) outbox.update { OutboxLogic.markNotSent(it, id, clock()) }
        schedule()
    }

    /**
     * Страница перехватила успешный ответ отправки: сервер принял сообщение. Такое сообщение доставлено сразу — не
     * через 12 секунд и перезагрузку страницы; освободившееся место занимает следующее из очереди.
     */
    fun onDelivered(peerId: String, text: String, cmid: Long = 0) {
        val peer = peerId
        var delivered = 0
        outbox.update { items ->
            val result = OutboxLogic.deliveredByServer(items, peer, text, cmid)
            outbox.recordReceipt(peer, result.confirmedThrough)
            delivered = result.delivered.size
            // Карточка очереди уходит сейчас, а история страницы - позже: до неё сообщение показывает мост
            result.delivered.forEach { acceptedBridge.add(peer, it.text, cmid) }
            if (result.delivered.isNotEmpty()) outbox.delivered(result.delivered)
            result.items
        }
        host.log("ответ отправки: сервер принял сообщение", "из очереди подтверждено" to delivered)
        drain()
        schedule()
    }

    /** Сработал таймер: истёк срок ожидания, пора повторить или перечитать историю. */
    fun tick() {
        releaseLostReply()
        // Слишком долго без подтверждения — сомнительное (в любой переписке).
        outbox.update { OutboxLogic.expire(it, clock()) }
        val peer = host.peerId
        if (peer != null && host.isChatOpen && host.isOnline) {
            val waiting = outbox.items().filter { it.peerId == peer && it.state == OutboxState.SENDING }
            if (waiting.isNotEmpty()) confirmDelivery(waiting)
        }
        drain()
        schedule()
    }

    /** Идёт отправка в открытой переписке: страницу в этот момент трогать нельзя (автообновление пропускает). */
    fun hasInFlight(): Boolean =
        sendingId != null || outbox.items().any { it.state == OutboxState.SENDING && it.peerId == host.peerId }

    /** Человек решил отправить сомнительное ещё раз. */
    fun retry(item: OutboxItem) {
        host.log("повтор по решению человека", "было" to item.state.name)
        outbox.update { OutboxLogic.retry(it, item.id) }
    }

    /** Человек решил не отправлять (или удалил сомнительное). */
    fun discard(item: OutboxItem) {
        host.log("удалено человеком", "было" to item.state.name)
        outbox.update { OutboxLogic.discard(it, item.id) }
    }

    /** «Вернуть» на полосе после удаления. */
    fun undoDiscard(item: OutboxItem) {
        host.log("удаление отменено", "было" to item.state.name)
        outbox.update { OutboxLogic.undoDiscard(it, item) }
    }

    fun stop() {
        sendingId = null
        host.cancelTick()
    }

    private fun drain() {
        val peer = host.peerId ?: return
        // Сообщение уже доставлено (или удалено), а ответ страницы на нажатие ещё не пришёл: ждать его незачем.
        sendingId?.let { id -> if (outbox.items().none { it.id == id }) sendingId = null }
        if (!host.isChatOpen || !ready || !host.isOnline || sendingId != null) return
        val item = OutboxLogic.nextToSend(outbox.items(), peer, clock()) ?: return
        sendingId = item.id
        val baseline = OutboxLogic.baselineFor(item, host.serverHistory)
        outbox.update { OutboxLogic.markClicked(it, item.id, clock(), baseline, outbox.receiptFloor(peer)) }
        host.log("отправка из очереди", "попыток до этого" to item.attempts)
        outbox.whenPersisted { ok ->
            if (ok && sendingId == item.id && host.peerId == peer && host.isChatOpen && ready && host.isOnline &&
                outbox.items().any { it.id == item.id && it.state == OutboxState.SENDING }) {
                host.sendToPage(item.text)
            } else {
                if (sendingId == item.id) sendingId = null
                // No side effect was dispatched: safe to return this item to the queue.
                outbox.update { items -> items.map {
                    if (it.id == item.id && it.state == OutboxState.SENDING) {
                        it.copy(state = OutboxState.QUEUED, sentAt = 0, baseline = emptyList(), retryAt = clock() + CONFIRM_POLL_MS)
                    } else it
                } }
            }
        }
    }

    /** Следующая проверка — когда придёт время повтора или истечёт ожидание подтверждения. */
    private fun schedule() {
        val items = outbox.items()
        var delay = OutboxLogic.nextWakeupDelay(items, clock())
        // Пока ждём подтверждения в открытой переписке, регулярно перечитываем историю.
        val peer = host.peerId
        if (peer != null && items.any { it.peerId == peer && it.state == OutboxState.SENDING }) {
            delay = minOf(delay ?: CONFIRM_POLL_MS, CONFIRM_POLL_MS)
        }
        if (delay == null) host.cancelTick() else host.scheduleTick(delay.coerceAtLeast(MIN_TICK_MS))
    }

    /**
     * Страница обычно отвечает на нажатие за доли секунды. Если ответа нет (сбой скрипта), без этой защиты флаг
     * «в полёте» остался бы навсегда и остальные сообщения не отправлялись бы. Само сообщение остаётся
     * нажатым — его дальше разберёт проверка доставки.
     */
    private fun releaseLostReply() {
        val id = sendingId ?: return
        val item = outbox.items().firstOrNull { it.id == id }
        if (item == null || item.state != OutboxState.SENDING || clock() - item.sentAt >= SENT_REPLY_TIMEOUT_MS) {
            host.log("страница не ответила на нажатие «Отправить»", "сообщение ещё в очереди" to (item != null))
            sendingId = null
        }
    }

    /**
     * Ждём, что отправленное появится в истории: перечитываем её у страницы, а если страница сама не обновилась,
     * один раз за [CONFIRM_RELOAD_MS] перезагружаем её — сервер отдаст настоящую историю.
     */
    private fun confirmDelivery(waiting: List<OutboxItem>) {
        host.requestHistory()
        val sinceSent = clock() - waiting.minOf { it.sentAt }
        if (sinceSent >= CONFIRM_RELOAD_MS && clock() - lastConfirmReload >= CONFIRM_RELOAD_MS) {
            lastConfirmReload = clock()
            host.log("страница сама не обновилась, перезагружаем ради подтверждения", "мс после отправки" to sinceSent)
            host.reloadPage()
        }
    }

    companion object {
        const val CONFIRM_POLL_MS = 3_000L
        const val CONFIRM_RELOAD_MS = 12_000L
        const val MIN_TICK_MS = 500L
        const val SENT_REPLY_TIMEOUT_MS = 10_000L
    }
}
