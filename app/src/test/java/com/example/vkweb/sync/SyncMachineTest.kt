package com.example.vkweb.sync

import com.example.vkweb.messages.ChatMessage
import com.example.vkweb.outbox.OutboxItem
import com.example.vkweb.outbox.OutboxLogic
import com.example.vkweb.outbox.OutboxState
import com.example.vkweb.sync.SyncMachine.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMachineTest {

    /** Очередь в памяти. */
    private class MemoryOutbox(var list: List<OutboxItem>) : OutboxAccess {
        override fun items() = list
        override fun update(transform: (List<OutboxItem>) -> List<OutboxItem>) { list = transform(list) }
    }

    private fun item(id: String, peer: String, text: String = "привет", at: Long = 1) =
        OutboxItem(id = id, peerId = peer, text = text, createdAt = at)

    private fun mine(id: String, text: String) = ChatMessage(id = id, out = true, text = text, time = "")

    private val dialogsUrl = Command.Load(SyncMachine.DIALOGS_URL)
    private fun peerUrl(peer: String) = Command.Load(SyncMachine.PEER_URL + peer)

    @Test fun `очередь пуста — только список диалогов`() {
        val machine = SyncMachine(MemoryOutbox(emptyList()))
        assertEquals(dialogsUrl, machine.start(now = 0))
        assertEquals(Command.Finish, machine.onDialogs(emptyList(), now = 1))
    }

    @Test fun `отправка и подтверждение доставки`() {
        val box = MemoryOutbox(listOf(item("a", "100", "привет")))
        val machine = SyncMachine(box)
        assertEquals(dialogsUrl, machine.start(now = 0))
        assertEquals(peerUrl("100"), machine.onDialogs(emptyList(), now = 1))

        assertEquals(Command.Send("привет"), machine.onHistory("100", listOf(mine("c1", "раньше")), now = 2))
        assertEquals(OutboxState.SENDING, box.list.single().state)

        assertEquals(Command.Recheck(SyncMachine.RECHECK_MS), machine.onSent(ok = true, now = 3))
        // Первая проверка: сообщения в истории ещё нет — ждём.
        assertEquals(
            Command.Recheck(SyncMachine.RECHECK_MS), machine.onHistory("100", listOf(mine("c1", "раньше")), now = 4),
        )
        // Появилось настоящее — доставлено, очередь пуста, работа закончена.
        assertEquals(
            Command.Finish, machine.onHistory("100", listOf(mine("c1", "раньше"), mine("c2", "привет")), now = 5),
        )
        assertTrue(box.list.isEmpty())
    }

    @Test fun `не дождались подтверждения — оставляем нажатое как есть и заканчиваем`() {
        val box = MemoryOutbox(listOf(item("a", "100")))
        val machine = SyncMachine(box)
        machine.start(0); machine.onDialogs(emptyList(), 1)
        machine.onHistory("100", emptyList(), 2)
        machine.onSent(true, 3)
        val afterWindow = 3 + SyncMachine.CONFIRM_WINDOW_MS
        assertEquals(Command.Finish, machine.onHistory("100", emptyList(), afterWindow))
        // Не удалили и не отправили второй раз: решит следующая сверка.
        assertEquals(OutboxState.SENDING, box.list.single().state)
    }

    @Test fun `страница не приняла ввод — вернули в очередь с паузой`() {
        val box = MemoryOutbox(listOf(item("a", "100")))
        val machine = SyncMachine(box)
        machine.start(0); machine.onDialogs(emptyList(), 1)
        machine.onHistory("100", emptyList(), 2)
        assertEquals(Command.Finish, machine.onSent(ok = false, now = 3))
        val a = box.list.single()
        assertEquals(OutboxState.QUEUED, a.state)
        assertEquals(1, a.attempts)
        assertTrue(a.retryAt > 3)
    }

    @Test fun `несколько сообщений одному — по очереди, каждое после подтверждения`() {
        val box = MemoryOutbox(listOf(item("a", "100", "раз", at = 1), item("b", "100", "два", at = 2)))
        val machine = SyncMachine(box)
        machine.start(0); machine.onDialogs(emptyList(), 1)
        assertEquals(Command.Send("раз"), machine.onHistory("100", emptyList(), 2))
        machine.onSent(true, 3)
        // «раз» доставлено — сразу идёт «два», без ожидания окна.
        assertEquals(Command.Send("два"), machine.onHistory("100", listOf(mine("c1", "раз")), 4))
        machine.onSent(true, 5)
        assertEquals(Command.Finish, machine.onHistory("100", listOf(mine("c1", "раз"), mine("c2", "два")), 6))
        assertTrue(box.list.isEmpty())
    }

    @Test fun `две переписки — одна за другой`() {
        val box = MemoryOutbox(listOf(item("a", "100", at = 1), item("b", "200", at = 2)))
        val machine = SyncMachine(box)
        machine.start(0)
        assertEquals(peerUrl("100"), machine.onDialogs(emptyList(), 1))
        machine.onHistory("100", emptyList(), 2); machine.onSent(true, 3)
        assertEquals(peerUrl("200"), machine.onHistory("100", listOf(mine("c1", "привет")), 4))
        assertEquals(Command.Send("привет"), machine.onHistory("200", emptyList(), 5))
    }

    @Test fun `история чужой переписки игнорируется`() {
        val box = MemoryOutbox(listOf(item("a", "100")))
        val machine = SyncMachine(box)
        machine.start(0); machine.onDialogs(emptyList(), 1)
        assertEquals(Command.Wait, machine.onHistory("999", emptyList(), 2))
        assertEquals(OutboxState.QUEUED, box.list.single().state)
    }

    @Test fun `сомнительное только сверяется, повторно не отправляется`() {
        val doubtful = OutboxLogic.restore(OutboxLogic.markClicked(listOf(item("a", "100", "привет")), "a", 1, emptyList()))
        val box = MemoryOutbox(doubtful)
        val machine = SyncMachine(box)
        machine.start(100); machine.onDialogs(emptyList(), 101)
        // В истории оно есть — снимается без отправки.
        assertEquals(Command.Finish, machine.onHistory("100", listOf(mine("c7", "привет")), 102))
        assertTrue(box.list.isEmpty())
    }

    @Test fun `сомнительное, которого в истории нет, остаётся и не отправляется`() {
        val doubtful = OutboxLogic.restore(OutboxLogic.markClicked(listOf(item("a", "100")), "a", 1, emptyList()))
        val box = MemoryOutbox(doubtful)
        val machine = SyncMachine(box)
        machine.start(100); machine.onDialogs(emptyList(), 101)
        assertEquals(Command.Finish, machine.onHistory("100", emptyList(), 102))
        assertEquals(OutboxState.UNCONFIRMED, box.list.single().state)
    }

    @Test fun `ещё не пора повторять — переписку не открываем`() {
        val waiting = OutboxLogic.markNotSent(listOf(item("a", "100")), "a", now = 1_000)
        val machine = SyncMachine(MemoryOutbox(waiting))
        assertEquals(dialogsUrl, machine.start(now = 1_500))
        assertEquals(Command.Finish, machine.onDialogs(emptyList(), 1_501))
    }

    @Test fun `неудавшиеся не трогаем`() {
        var items = listOf(item("a", "100"))
        repeat(OutboxLogic.MAX_ATTEMPTS) { items = OutboxLogic.markNotSent(items, "a", 1) }
        val machine = SyncMachine(MemoryOutbox(items))
        machine.start(Long.MAX_VALUE / 2)
        assertEquals(Command.Finish, machine.onDialogs(emptyList(), 1))
    }

    @Test fun `вход потерян — дальше не идём и ничего не отправляем`() {
        val box = MemoryOutbox(listOf(item("a", "100")))
        val machine = SyncMachine(box)
        machine.start(0)
        assertEquals(Command.Finish, machine.onUnavailable(loginRequired = true, now = 1))
        assertEquals(OutboxState.QUEUED, box.list.single().state)
    }

    @Test fun `переписка недоступна — переходим к следующей`() {
        val box = MemoryOutbox(listOf(item("a", "100", at = 1), item("b", "200", at = 2)))
        val machine = SyncMachine(box)
        machine.start(0); machine.onDialogs(emptyList(), 1)
        assertEquals(peerUrl("200"), machine.onUnavailable(loginRequired = false, now = 2))
    }

    @Test fun `шаг не дождался страницы — переходим дальше`() {
        val box = MemoryOutbox(listOf(item("a", "100")))
        val machine = SyncMachine(box)
        machine.start(0)
        assertEquals(peerUrl("100"), machine.onStepTimeout(30_000))
        assertEquals(Command.Finish, machine.onStepTimeout(60_000))
        assertEquals(OutboxState.QUEUED, box.list.single().state)
    }

    @Test fun `посторонние события вне своей фазы игнорируются`() {
        val machine = SyncMachine(MemoryOutbox(emptyList()))
        machine.start(0)
        assertEquals(Command.Wait, machine.onSent(true, 1))
        assertEquals(Command.Wait, machine.onHistory("100", emptyList(), 1))
    }

    @Test fun `без чтения диалогов — сразу к отправке`() {
        val machine = SyncMachine(MemoryOutbox(listOf(item("a", "100"))), includeDialogs = false)
        assertEquals(peerUrl("100"), machine.start(now = 0))
    }

    @Test fun `без чтения диалогов и без очереди — делать нечего`() {
        assertEquals(Command.Finish, SyncMachine(MemoryOutbox(emptyList()), includeDialogs = false).start(now = 0))
    }

    @Test fun `ответ отправки подтверждает сразу — без ожидания истории, работа заканчивается`() {
        val box = MemoryOutbox(listOf(item("a", "100", "привет")))
        val machine = SyncMachine(box)
        machine.start(0); machine.onDialogs(emptyList(), 1)
        machine.onHistory("100", emptyList(), 2)
        machine.onSent(true, 3)
        assertEquals(Command.Finish, machine.onDelivered("100", "привет", now = 4, cmid = 1))
        assertTrue(box.list.isEmpty())
    }

    @Test fun `после подтверждения по ответу следующее сообщение той же переписки отправляется по свежей истории`() {
        val box = MemoryOutbox(listOf(item("a", "100", "первое", at = 1), item("b", "100", "второе", at = 2)))
        val machine = SyncMachine(box)
        machine.start(0); machine.onDialogs(emptyList(), 1)
        assertEquals(Command.Send("первое"), machine.onHistory("100", emptyList(), 2))
        machine.onSent(true, 3)
        assertEquals(Command.Recheck(SyncMachine.SOON_MS), machine.onDelivered("100", "первое", now = 4, cmid = 1))
        assertEquals(listOf("b"), box.list.map { it.id })
        assertEquals(Command.Send("второе"), machine.onHistory("100", listOf(mine("c1", "первое")), now = 5))
    }

    @Test fun `ответ отправки чужого текста, чужой переписки или не вовремя ничего не меняет`() {
        val box = MemoryOutbox(listOf(item("a", "100", "привет")))
        val machine = SyncMachine(box)
        machine.start(0); machine.onDialogs(emptyList(), 1)
        assertEquals("до нажатия", Command.Wait, machine.onDelivered("100", "привет", now = 1))
        machine.onHistory("100", emptyList(), 2)
        machine.onSent(true, 3)
        assertEquals("чужой текст", Command.Wait, machine.onDelivered("100", "другое", now = 4))
        assertEquals("чужая переписка", Command.Wait, machine.onDelivered("200", "привет", now = 4))
        assertEquals(1, box.list.size)
    }

    @Test fun `ответ отправки раньше подтверждения нажатия тоже подтверждает`() {
        val box = MemoryOutbox(listOf(item("a", "100", "привет")))
        val machine = SyncMachine(box)
        machine.start(0); machine.onDialogs(emptyList(), 1)
        machine.onHistory("100", emptyList(), 2)
        assertEquals(Command.Finish, machine.onDelivered("100", "привет", now = 3, cmid = 1))
        assertEquals("позднее подтверждение нажатия уже ничего не значит", Command.Wait, machine.onSent(true, 4))
    }
    @Test fun `таймаут до dispatch оставляет сообщение пригодным для автоматического повтора`() {
        val box = MemoryOutbox(listOf(item("a", "100")))
        val machine = SyncMachine(box, includeDialogs = false)
        machine.start(0)
        machine.onHistory("100", emptyList(), 1)
        assertEquals(Command.Finish, machine.onStepTimeout(30_001))
        assertEquals(OutboxState.QUEUED, OutboxLogic.restore(box.list).single().state)
    }

    @Test fun `отмена до dispatch не превращает неотправленное в сомнительное`() {
        val box = MemoryOutbox(listOf(item("a", "100")))
        val machine = SyncMachine(box, includeDialogs = false)
        machine.start(0)
        machine.onHistory("100", emptyList(), 1)
        machine.cancelPendingDispatch()
        assertEquals(OutboxState.QUEUED, box.list.single().state)
    }

    @Test fun `после dispatch таймаут и отмена не разрешают автоматический дубль`() {
        val box = MemoryOutbox(listOf(item("a", "100")))
        val machine = SyncMachine(box, includeDialogs = false)
        machine.start(0)
        machine.onHistory("100", emptyList(), 1)
        machine.onDispatched("a")
        machine.onStepTimeout(30_001)
        machine.cancelPendingDispatch()
        assertEquals(OutboxState.SENDING, box.list.single().state)
        assertEquals(OutboxState.UNCONFIRMED, OutboxLogic.restore(box.list).single().state)
    }
}
