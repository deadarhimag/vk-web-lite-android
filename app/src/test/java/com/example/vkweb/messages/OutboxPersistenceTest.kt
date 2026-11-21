package com.example.vkweb.messages

import com.example.vkweb.outbox.OutboxItem
import com.example.vkweb.outbox.OutboxState
import com.example.vkweb.sync.OutboxAccess
import org.junit.Assert.*
import org.junit.Test

class OutboxPersistenceTest {
    private class Store : OutboxAccess {
        var data = listOf<OutboxItem>()
        val waiting = ArrayList<(Boolean) -> Unit>()
        override fun items() = data
        override fun update(transform: (List<OutboxItem>) -> List<OutboxItem>) { data = transform(data) }
        override fun whenPersisted(done: (Boolean) -> Unit) { waiting += done }
        fun complete(ok: Boolean) { val callbacks = waiting.toList(); waiting.clear(); callbacks.forEach { it(ok) } }
    }
    private class Host : ChatOutboxDriver.Host {
        override var peerId: String? = "5"
        override var isChatOpen = true
        override val isOnline = true
        override val serverHistory = emptyList<ChatMessage>()
        val sent = ArrayList<String>()
        override fun sendToPage(text: String) { sent += text }
        override fun requestHistory() {}
        override fun reloadPage() {}
        override fun scheduleTick(delayMs: Long) {}
        override fun cancelTick() {}
        override fun log(message: String, vararg fields: Pair<String, Any?>) {}
    }

    @Test fun `submission is acknowledged only after persistence`() {
        val store = Store()
        val driver = ChatOutboxDriver(store, Host())
        var acknowledged = false
        assertTrue(driver.submit("hello", onCommitted = { acknowledged = it }))
        assertFalse(acknowledged)
        store.complete(true)
        assertTrue(acknowledged)
    }

    @Test fun `page dispatch waits for durable sending state`() {
        val store = Store()
        val host = Host()
        val driver = ChatOutboxDriver(store, host)
        driver.submit("hello")
        driver.onFreshHistory()
        assertTrue(host.sent.isEmpty())
        assertEquals(OutboxState.SENDING, store.data.single().state)
        store.complete(true)
        assertEquals(listOf("hello"), host.sent)
    }

    @Test fun `chat switch while saving cannot send through new composer`() {
        val store = Store()
        val host = Host()
        val driver = ChatOutboxDriver(store, host)
        driver.submit("for 5")
        driver.onFreshHistory()
        host.peerId = "6"
        driver.onChatOpened()
        store.complete(true)
        assertTrue(host.sent.isEmpty())
        assertEquals(OutboxState.QUEUED, store.data.single().state)
        assertEquals("5", store.data.single().peerId)
    }

    @Test fun `failed persistence never dispatches and keeps input unacknowledged`() {
        val store = Store()
        val host = Host()
        val driver = ChatOutboxDriver(store, host)
        var accepted: Boolean? = null
        driver.submit("hello", onCommitted = { accepted = it })
        driver.onFreshHistory()
        store.complete(false)
        assertEquals(false, accepted)
        assertTrue(host.sent.isEmpty())
        assertTrue(store.data.isEmpty())
    }

    @Test fun `stopping screen during commit prevents late dispatch`() {
        val store = Store()
        val host = Host()
        val driver = ChatOutboxDriver(store, host)
        driver.submit("hello")
        driver.onFreshHistory()
        driver.stop()
        store.complete(true)
        assertTrue(host.sent.isEmpty())
        assertEquals(OutboxState.QUEUED, store.data.single().state)
    }

    @Test fun `resuming can reuse an already ready page without mandatory reload`() {
        val store = Store()
        val host = Host()
        val driver = ChatOutboxDriver(store, host)
        driver.onFreshHistory()
        driver.stop()
        driver.submit("after resume")
        driver.onOutboxChanged()
        store.complete(true)
        assertEquals(listOf("after resume"), host.sent)
    }
}
