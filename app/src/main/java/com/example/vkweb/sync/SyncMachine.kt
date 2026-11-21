package com.example.vkweb.sync

import com.example.vkweb.messages.ChatMessage
import com.example.vkweb.messages.DialogEntry
import com.example.vkweb.outbox.OutboxItem
import com.example.vkweb.outbox.OutboxLogic
import com.example.vkweb.outbox.OutboxState

/** Доступ к очереди неотправленных: на устройстве это [com.example.vkweb.outbox.Outbox], в тестах — список в памяти. */
interface OutboxAccess {
    fun items(): List<OutboxItem>
    fun update(transform: (List<OutboxItem>) -> List<OutboxItem>)
    /** Callback only after pending writes are durable; test stores are synchronous by default. */
    fun whenPersisted(done: (Boolean) -> Unit) { done(true) }
    /** Эти сообщения доставлены (4.17: об отложенных — уведомление). */
    fun delivered(items: List<OutboxItem>) {}
    /** Shared across foreground/background executors, including when the queue becomes empty. */
    fun receiptFloor(peerId: String): Long = 0
    fun recordReceipt(peerId: String, cmid: Long) {}
}

/**
 * Порядок работы фоновой проверки, без Android: что открыть, что нажать, когда закончить.
 * Исполнитель ([HeadlessSync]) выполняет команды в невидимом окне и сообщает о результатах, а решения принимает
 * эта машина — поэтому её можно проверить обычными тестами.
 *
 * Шаги по очереди: сначала список диалогов (по нему приходят новые сообщения), затем каждая переписка, где есть что
 * отправить или проверить. В переписке: сверить очередь с историей → отправить следующее → подождать, пока оно
 * появится в истории ([CONFIRM_WINDOW_MS]) → так же со следующим. Не дождались — оставляем как есть: сомнительное
 * разрешится при следующей сверке, повторно само не уйдёт.
 */
class SyncMachine(
    private val outbox: OutboxAccess,
    /** Читать ли список диалогов (новые сообщения). Выключено — только отправка очереди. */
    private val includeDialogs: Boolean = true,
) {

    sealed interface Command {
        /** Открыть адрес и дальше ждать данные страницы. */
        data class Load(val url: String) : Command

        /** Нажать «Отправить» с этим текстом на открытой переписке. */
        data class Send(val text: String) : Command {
            /** Куда и с каким номером отправки (4.22: в фоне — запросом API, номер не даёт задвоить при повторе). */
            var peerId: String = ""
            var randomId: Int = 0
            var itemId: String = ""
        }

        /** Через [delayMs] снова прочитать историю открытой страницы. */
        data class Recheck(val delayMs: Long) : Command

        /** Ничего не делать, ждать следующего сообщения от страницы. */
        object Wait : Command

        /** Закончить работу. */
        object Finish : Command
    }

    private sealed interface Step {
        object Dialogs : Step
        data class Peer(val peerId: String) : Step
    }

    private enum class Phase { WAIT_DIALOGS, WAIT_HISTORY, WAIT_SENT, WAIT_CONFIRM }

    private var steps: List<Step> = emptyList()
    private var index = -1
    private var phase = Phase.WAIT_DIALOGS
    private var confirmDeadline = 0L
    private var sendingId: String? = null
    private var pendingDispatch: String? = null

    /** Called immediately before the executor hands the command to the page. */
    fun onDispatched(itemId: String) {
        if (pendingDispatch == itemId) pendingDispatch = null
    }

    /** No external send was attempted: this item can safely remain eligible for automatic retry. */
    fun cancelPendingDispatch() {
        val id = pendingDispatch ?: return
        pendingDispatch = null
        outbox.update { items -> items.map {
            if (it.id == id && it.state == OutboxState.SENDING) {
                it.copy(state = OutboxState.QUEUED, sentAt = 0, baseline = emptyList())
            } else it
        } }
    }

    /** Начало работы. */
    fun start(now: Long): Command {
        steps = (if (includeDialogs) listOf<Step>(Step.Dialogs) else emptyList()) + peersWithWork(now).map { Step.Peer(it) }
        index = -1
        return advance(now)
    }

    /** Пришёл список диалогов. */
    fun onDialogs(@Suppress("UNUSED_PARAMETER") items: List<DialogEntry>, now: Long): Command =
        if (phase == Phase.WAIT_DIALOGS) advance(now) else Command.Wait

    /** Пришла история открытой переписки. */
    fun onHistory(peerId: String, history: List<ChatMessage>, now: Long): Command {
        val step = steps.getOrNull(index) as? Step.Peer ?: return Command.Wait
        // История другой переписки (страница ещё не сменилась) — не наша.
        if (peerId.isNotEmpty() && peerId != step.peerId) return Command.Wait
        if (phase != Phase.WAIT_HISTORY && phase != Phase.WAIT_CONFIRM) return Command.Wait

        outbox.update { items ->
            val result = OutboxLogic.reconcile(items, step.peerId, history, now)
            outbox.recordReceipt(step.peerId, result.confirmedThrough)
            if (result.delivered.isNotEmpty()) outbox.delivered(result.delivered)
            result.items
        }
        val next = OutboxLogic.nextToSend(outbox.items(), step.peerId, now)
        if (next != null) {
            val baseline = OutboxLogic.baselineFor(next, history)
            outbox.update { OutboxLogic.markClicked(it, next.id, now, baseline, outbox.receiptFloor(step.peerId)) }
            sendingId = next.id
            pendingDispatch = next.id
            phase = Phase.WAIT_SENT
            val send = Command.Send(next.text)
            send.peerId = step.peerId
            send.randomId = OutboxLogic.randomIdOf(next.id)
            send.itemId = next.id
            return send
        }
        val stillSending = outbox.items().any { it.peerId == step.peerId && it.state == OutboxState.SENDING }
        if (stillSending && now < confirmDeadline) return Command.Recheck(RECHECK_MS)
        return advance(now)
    }

    /** Результат нажатия «Отправить». */
    fun onSent(ok: Boolean, now: Long): Command {
        if (phase != Phase.WAIT_SENT) return Command.Wait
        val id = sendingId
        pendingDispatch = null
        sendingId = null
        if (!ok) {
            // Страница не приняла ввод — точно не ушло: вернуть в очередь, повторить при следующей проверке.
            if (id != null) outbox.update { OutboxLogic.markNotSent(it, id, now) }
            return advance(now)
        }
        phase = Phase.WAIT_CONFIRM
        confirmDeadline = now + CONFIRM_WINDOW_MS
        return Command.Recheck(RECHECK_MS)
    }

    /**
     * Страница перехватила успешный ответ отправки: сервер принял сообщение. Оно доставлено сразу — ждать, пока оно
     * появится в истории (до [CONFIRM_WINDOW_MS]), не нужно. Остались ещё сообщения этой переписки — перечитываем историю
     * (по ней берётся основа для следующего), иначе идём дальше. Ответ на чужое или давно доставленное — не наш.
     */
    fun onDelivered(peerId: String, text: String, now: Long, cmid: Long = 0): Command {
        val step = steps.getOrNull(index) as? Step.Peer ?: return Command.Wait
        if (phase != Phase.WAIT_SENT && phase != Phase.WAIT_CONFIRM) return Command.Wait
        if (peerId.isNotEmpty() && peerId != step.peerId) return Command.Wait
        var delivered = 0
        outbox.update { items ->
            val result = OutboxLogic.deliveredByServer(items, step.peerId, text, cmid)
            outbox.recordReceipt(step.peerId, result.confirmedThrough)
            delivered = result.delivered.size
            if (result.delivered.isNotEmpty()) outbox.delivered(result.delivered)
            result.items
        }
        if (delivered == 0) return Command.Wait
        pendingDispatch = null
        // Ответ мог прийти раньше подтверждения нажатия: оно уже ни на что не влияет.
        sendingId = null
        phase = Phase.WAIT_CONFIRM
        val more = outbox.items().any {
            it.peerId == step.peerId && (it.state == OutboxState.QUEUED || it.state == OutboxState.SENDING || it.state == OutboxState.UNCONFIRMED)
        }
        return if (more) Command.Recheck(SOON_MS) else advance(now)
    }

    /** Шаг не дождался данных от страницы. */
    fun onStepTimeout(now: Long): Command = advance(now)

    /** Страница недоступна: [loginRequired] — вход потерян, дальше идти бессмысленно. */
    fun onUnavailable(loginRequired: Boolean, now: Long): Command =
        if (loginRequired) { cancelPendingDispatch(); Command.Finish } else advance(now)

    private fun advance(now: Long): Command {
        cancelPendingDispatch()
        sendingId = null
        index++
        val step = steps.getOrNull(index) ?: return Command.Finish
        return when (step) {
            Step.Dialogs -> {
                phase = Phase.WAIT_DIALOGS
                Command.Load(DIALOGS_URL)
            }
            is Step.Peer -> {
                phase = Phase.WAIT_HISTORY
                confirmDeadline = 0
                Command.Load(PEER_URL + step.peerId)
            }
        }
    }

    /** Переписки, где есть что сделать: отправить (время повтора пришло) или проверить сомнительное. */
    private fun peersWithWork(now: Long): List<String> = outbox.items()
        .sortedBy { it.createdAt }
        .filter { item ->
            when (item.state) {
                OutboxState.SENDING, OutboxState.UNCONFIRMED -> true
                OutboxState.QUEUED -> item.retryAt <= now && !item.isScheduled(now)
                OutboxState.FAILED -> false
            }
        }
        .map { it.peerId }
        .distinct()

    companion object {
        /** Адреса те же, что у экрана сообщений ([com.example.vkweb.messages.ReaderChannel]). */
        const val DIALOGS_URL = "https://m.vk.com/mail"
        const val PEER_URL = "https://m.vk.com/mail/convo/"

        /** Сколько ждать появления отправленного в истории, прежде чем оставить как есть. */
        const val CONFIRM_WINDOW_MS = 20_000L
        const val RECHECK_MS = 2_000L
        /** Подтверждение пришло по ответу отправки: историю для следующего сообщения перечитываем почти сразу. */
        const val SOON_MS = 300L
    }
}
