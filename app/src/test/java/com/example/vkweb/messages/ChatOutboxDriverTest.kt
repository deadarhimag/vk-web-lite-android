package com.example.vkweb.messages

import com.example.vkweb.outbox.OutboxItem
import com.example.vkweb.outbox.OutboxLogic
import com.example.vkweb.outbox.OutboxState
import com.example.vkweb.sync.OutboxAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatOutboxDriverTest {

    private class MemoryOutbox : OutboxAccess {
        var list: List<OutboxItem> = emptyList()
        val receipts = HashMap<String, Long>()
        override fun receiptFloor(peerId: String) = receipts[peerId] ?: 0L
        override fun recordReceipt(peerId: String, cmid: Long) { receipts[peerId] = maxOf(receiptFloor(peerId), cmid) }
        override fun items() = list
        override fun update(transform: (List<OutboxItem>) -> List<OutboxItem>) { list = transform(list) }
    }

    /** Экран и страница, как их видит очередь: всё записывается, время — вручную. */
    private class FakeHost : ChatOutboxDriver.Host {
        override var peerId: String? = "100"
        override var isChatOpen = true
        override var isOnline = true
        override var serverHistory: List<ChatMessage> = emptyList()
        val sent = ArrayList<String>()
        var historyRequests = 0
        var reloads = 0
        var scheduled: Long? = null
        override fun sendToPage(text: String) { sent += text }
        override fun requestHistory() { historyRequests++ }
        override fun reloadPage() { reloads++ }
        override fun scheduleTick(delayMs: Long) { scheduled = delayMs }
        override fun cancelTick() { scheduled = null }
        override fun log(message: String, vararg fields: Pair<String, Any?>) = Unit
    }

    private var now = 1_000_000L
    private val box = MemoryOutbox()
    private val host = FakeHost()
    private var ids = 0
    private val driver = ChatOutboxDriver(box, host, clock = { now }, newId = { "id${ids++}" })

    private fun mine(id: String, text: String) = ChatMessage(id = id, out = true, text = text, time = "")
    private fun state(id: String) = box.list.first { it.id == id }.state

    /** Страница готова: пришла свежая история. */
    private fun pageReady(history: List<ChatMessage> = emptyList()) {
        host.serverHistory = history
        driver.onFreshHistory()
    }

    @Test fun `пока страница не готова, сообщение ждёт`() {
        assertTrue(driver.submit("привет"))
        driver.onOutboxChanged()
        assertEquals(emptyList<String>(), host.sent)
        pageReady()
        assertEquals(listOf("привет"), host.sent)
        assertEquals(OutboxState.SENDING, state("id0"))
    }

    @Test fun `без сети и без открытой переписки не отправляется`() {
        driver.submit("привет")
        pageReady()
        host.sent.clear()
        box.list = OutboxLogic.discard(box.list, "id0")
        host.isOnline = false
        driver.submit("второе"); driver.onOutboxChanged()
        assertEquals(emptyList<String>(), host.sent)
        host.isOnline = true; host.isChatOpen = false
        driver.onOutboxChanged()
        assertEquals(emptyList<String>(), host.sent)
        host.isChatOpen = true
        driver.onOutboxChanged()
        assertEquals(listOf("второе"), host.sent)
    }

    @Test fun `одно в полёте, следующее уходит после подтверждения доставки`() {
        driver.submit("раз"); driver.submit("два")
        pageReady()
        assertEquals(listOf("раз"), host.sent)
        driver.onSent(true)
        driver.onOutboxChanged()
        assertEquals("пока первое не подтверждено, второе ждёт", listOf("раз"), host.sent)
        // В истории появилось настоящее «раз» — оно доставлено, уходит «два».
        pageReady(listOf(mine("c1", "раз")))
        assertEquals(listOf("раз", "два"), host.sent)
        assertEquals(listOf("id1"), box.list.map { it.id })
    }

    @Test fun `страница не приняла ввод — повтор после паузы, не раньше`() {
        driver.submit("привет")
        pageReady()
        driver.onSent(false)
        assertEquals(OutboxState.QUEUED, state("id0"))
        host.sent.clear()
        driver.tick()
        assertEquals("пауза ещё не прошла", emptyList<String>(), host.sent)
        now += 3_000
        driver.tick()
        assertEquals(listOf("привет"), host.sent)
    }

    @Test fun `ответ страницы потерян — флаг в полёте снимается, сообщение становится сомнительным`() {
        driver.submit("раз"); driver.submit("два")
        pageReady()
        assertEquals(listOf("раз"), host.sent)
        // Страница так и не ответила на нажатие.
        now += ChatOutboxDriver.SENT_REPLY_TIMEOUT_MS
        driver.tick()
        assertEquals("«раз» всё ещё ждёт подтверждения: второе не обгоняет", listOf("раз"), host.sent)
        now += OutboxLogic.CONFIRM_TIMEOUT_MS
        driver.tick()
        assertEquals(OutboxState.UNCONFIRMED, state("id0"))
        assertEquals("сомнительное очередь не блокирует", listOf("раз", "два"), host.sent)
    }

    @Test fun `пока ждём подтверждения, историю перечитываем, а страницу перезагружаем не чаще раза в 12 секунд`() {
        driver.submit("привет")
        pageReady()
        driver.onSent(true)
        now += 3_000; driver.tick()
        assertEquals(1, host.historyRequests)
        assertEquals(0, host.reloads)
        now += 9_000; driver.tick()
        assertEquals(1, host.reloads)
        now += 3_000; driver.tick()
        assertEquals("второй раз подряд не перезагружаем", 1, host.reloads)
        now += 12_000; driver.tick()
        assertEquals(2, host.reloads)
    }

    @Test fun `следующая проверка назначается, пока есть чего ждать, и отменяется, когда нечего`() {
        assertNull(host.scheduled)
        driver.submit("привет")
        pageReady()
        assertEquals("ждём подтверждения: опрос раз в 3 секунды", ChatOutboxDriver.CONFIRM_POLL_MS, host.scheduled)
        pageReady(listOf(mine("c1", "привет")))
        assertNull(host.scheduled)
    }

    @Test fun `в полёте — пока ждём ответа или подтверждения`() {
        assertFalse(driver.hasInFlight())
        driver.submit("привет")
        pageReady()
        assertTrue(driver.hasInFlight())
        driver.onSent(true)
        assertTrue(driver.hasInFlight())
        pageReady(listOf(mine("c1", "привет")))
        assertFalse(driver.hasInFlight())
    }

    @Test fun `новая переписка сбрасывает готовность`() {
        driver.submit("привет")
        pageReady()
        driver.onChatOpened()
        assertFalse(driver.isReady)
        host.sent.clear()
        driver.onPageReloading()
        driver.submit("ещё")
        driver.onOutboxChanged()
        assertEquals(emptyList<String>(), host.sent)
    }

    @Test fun `очередь заполнена или переписка не открыта — не принимаем`() {
        host.peerId = null
        assertFalse(driver.submit("привет"))
        host.peerId = "100"
        repeat(OutboxLogic.MAX_ITEMS) { assertTrue(driver.submit("т$it")) }
        assertFalse(driver.submit("лишнее"))
    }

    @Test fun `повтор и удаление по решению человека`() {
        driver.submit("привет")
        pageReady()
        box.list = OutboxLogic.restore(box.list)
        assertEquals(OutboxState.UNCONFIRMED, state("id0"))
        driver.retry(box.list.first())
        assertEquals(OutboxState.QUEUED, state("id0"))
        driver.discard(box.list.first())
        assertEquals(emptyList<OutboxItem>(), box.list)
    }

    @Test fun `ответ отправки подтверждает сразу, без перезагрузки страницы`() {
        driver.submit("привет")
        pageReady()
        driver.onSent(true)
        assertEquals(OutboxState.SENDING, state("id0"))
        driver.onDelivered("100", "привет", cmid = 1)
        assertTrue("очередь освободилась", box.list.isEmpty())
        now += 30_000; driver.tick()
        assertEquals("страницу перезагружать незачем", 0, host.reloads)
    }

    @Test fun `после подтверждения по ответу уходит следующее сообщение`() {
        driver.submit("первое"); driver.submit("второе")
        pageReady()
        driver.onSent(true)
        assertEquals(listOf("первое"), host.sent)
        driver.onDelivered("100", "первое", cmid = 1)
        assertEquals(listOf("первое", "второе"), host.sent)
        assertEquals(OutboxState.SENDING, state("id1"))
    }

    @Test fun `ответ отправки чужой переписки или другого текста очередь не меняет`() {
        driver.submit("привет")
        pageReady()
        driver.onSent(true)
        driver.onDelivered("200", "привет")
        driver.onDelivered("100", "что-то другое")
        assertEquals(OutboxState.SENDING, state("id0"))
    }
    @Test fun `старое подтверждение не удаляет новое одинаковое сообщение после опустошения очереди`() {
        driver.submit("ок"); pageReady(); driver.onSent(true)
        driver.onDelivered("100", "ок", cmid = 10)
        assertTrue(box.list.isEmpty())
        // A new submission arrives before the history catches up with the previous response.
        driver.submit("ок"); driver.onOutboxChanged(); driver.onSent(true)
        driver.onDelivered("100", "ок", cmid = 10)
        pageReady(listOf(mine("c10", "ок")))
        assertEquals(1, box.list.size)
        driver.onDelivered("100", "ок", cmid = 11)
        assertTrue(box.list.isEmpty())
    }
}
