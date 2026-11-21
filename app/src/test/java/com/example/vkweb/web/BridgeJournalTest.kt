package com.example.vkweb.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeJournalTest {

    @Test fun `первое сообщение типа — без «после прошлого», дальше считается разница`() {
        val t = BridgeTrace()
        val a = t.received("music", 1000)
        assertNull(a.sinceMs)
        assertEquals(1, a.number)
        val b = t.received("music", 1450)
        assertEquals(450L, b.sinceMs)
        assertEquals(2, b.number)
    }

    @Test fun `типы считаются отдельно`() {
        val t = BridgeTrace()
        t.received("music", 1000)
        assertNull(t.received("history", 1100).sinceMs)
    }

    @Test fun `отброшенные повторы называются в следующем принятом и обнуляются`() {
        val t = BridgeTrace()
        t.received("music", 0)
        repeat(3) { t.duplicate("music") }
        assertEquals(3, t.received("music", 10).dupsBefore)
        assertEquals(0, t.received("music", 20).dupsBefore)
    }

    @Test fun `счётчик серверов — потокобезопасный подсчёт и самые частые`() {
        val c = HostCounter()
        val threads = (1..8).map { Thread { repeat(500) { c.add("a.vk.ru") }; repeat(100) { c.add("b.vk.ru") } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(4000, c["a.vk.ru"])
        assertEquals(800, c["b.vk.ru"])
        assertEquals(listOf("a.vk.ru" to 4000, "b.vk.ru" to 800), c.top(5))
        assertEquals(4800, c.total)
        c.add(null); c.add("")
        assertEquals(4800, c.total)
        c.clear()
        assertEquals(0, c.total)
    }

    @Test fun `статистика серверов — запросы, блокировки, ошибки, сначала самые заметные`() {
        HostStats.requests.clear(); HostStats.blocked.clear(); HostStats.errors.clear()
        repeat(40) { HostStats.requests.add("web.api.vk.ru") }
        repeat(5) { HostStats.requests.add("sun9-62.vkuserphoto.ru") }
        repeat(5) { HostStats.errors.add("sun9-62.vkuserphoto.ru") }
        repeat(2) { HostStats.requests.add("top-fwz1.mail.ru") }
        repeat(2) { HostStats.blocked.add("top-fwz1.mail.ru") }
        val lines = HostStats.lines()
        assertEquals("web.api.vk.ru: запросов 40", lines[0])
        assertTrue(lines.any { it == "sun9-62.vkuserphoto.ru: запросов 5, ошибок 5" })
        assertTrue(lines.any { it == "top-fwz1.mail.ru: запросов 2, заблокировано 2" })
        HostStats.requests.clear(); HostStats.blocked.clear(); HostStats.errors.clear()
    }
}
