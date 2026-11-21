package com.example.vkweb.messages

import com.example.vkweb.outbox.OutboxItem
import com.example.vkweb.outbox.OutboxLogic
import com.example.vkweb.sync.OutboxAccess
import org.junit.Assert.assertEquals
import org.junit.Test

/** Отчёт 11.11 22:25: при отправке переписка не сжимается на строку между ответом сервера и историей страницы. */
class DeliveredBridgeTest {

    private class MemoryOutbox : OutboxAccess {
        var list: List<OutboxItem> = emptyList()
        val receipts = HashMap<String, Long>()
        override fun receiptFloor(peerId: String) = receipts[peerId] ?: 0L
        override fun recordReceipt(peerId: String, cmid: Long) { receipts[peerId] = maxOf(receiptFloor(peerId), cmid) }
        override fun items() = list
        override fun update(transform: (List<OutboxItem>) -> List<OutboxItem>) { list = transform(list) }
    }

    private class FakeHost : ChatOutboxDriver.Host {
        override var peerId: String? = "100"
        override var isChatOpen = true
        override var isOnline = true
        override var serverHistory: List<ChatMessage> = emptyList()
        override fun sendToPage(text: String) = Unit
        override fun requestHistory() = Unit
        override fun reloadPage() = Unit
        override fun scheduleTick(delayMs: Long) = Unit
        override fun cancelTick() = Unit
        override fun log(message: String, vararg fields: Pair<String, Any?>) = Unit
    }

    private var now = 1_000_000L
    private val box = MemoryOutbox()
    private val host = FakeHost()
    private var ids = 0
    private val driver = ChatOutboxDriver(box, host, clock = { now }, newId = { "id${ids++}" })

    private fun msg(id: String, text: String, out: Boolean = true) = ChatMessage(id = id, out = out, text = text, time = "")

    /** Что на экране: как в MessagesActivity.renderChat. */
    private fun shown(): List<ChatMessage> =
        OutboxLogic.visibleMessages(driver.withAccepted(host.serverHistory, "100"), box.list, "100", now)

    @Test fun `ответ сервера раньше истории - строк столько же`() {
        host.serverHistory = (1L..13L).map { msg("c$it", "старое $it", out = it % 2 == 0L) }
        driver.onFreshHistory()
        driver.submit("привет")
        driver.onOutboxChanged()
        assertEquals(14, shown().size)

        // Сервер принял: карточка очереди уходит, история ещё прежняя
        driver.onDelivered("100", "привет", cmid = 14)
        assertEquals(emptyList<OutboxItem>(), box.list)
        assertEquals(14, shown().size)
        assertEquals("c14", shown().last().id)

        // Пришла история с этим сообщением: то же id, та же длина
        host.serverHistory = host.serverHistory + msg("c14", "привет")
        assertEquals(14, shown().size)
        assertEquals(1, shown().count { it.id == "c14" })
    }

    @Test fun `черновая копия страницы не задваивает принятое`() {
        host.serverHistory = listOf(msg("c1", "раз"))
        driver.onFreshHistory()
        driver.submit("ок")
        driver.onOutboxChanged()
        driver.onDelivered("100", "ок", cmid = 2)
        host.serverHistory = listOf(msg("c1", "раз"), msg("d:ок", "ок"))
        assertEquals(listOf("c1", "c2"), shown().map { it.id })
    }

    @Test fun `принятое встаёт по номеру и забывается со временем`() {
        val bridge = DeliveredBridge { now }
        bridge.add("100", "моё", 5)
        val server = listOf(msg("c4", "a"), msg("c6", "b", out = false))
        assertEquals(listOf("c4", "c5", "c6"), bridge.visible(server, "100").map { it.id })
        assertEquals(listOf("c4", "c6"), bridge.visible(server, "200").map { it.id })
        now += 31_000
        assertEquals(listOf("c4", "c6"), bridge.visible(server, "100").map { it.id })
    }

    @Test fun `ответ без номера ничего не показывает`() {
        val bridge = DeliveredBridge { now }
        bridge.add("100", "моё", 0)
        assertEquals(listOf("c1"), bridge.visible(listOf(msg("c1", "a")), "100").map { it.id })
    }
}
