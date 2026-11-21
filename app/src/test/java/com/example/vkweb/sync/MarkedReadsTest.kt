package com.example.vkweb.sync

import com.example.vkweb.messages.DialogEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/** «Прочитано» из уведомления и следующая проверка (4.35). */
class MarkedReadsTest {

    private fun d(peer: String) = DialogEntry(peerId = peer, title = "T$peer", snippet = "", time = "", unread = true, online = false)

    @Test fun `отмеченное прочитанным не возвращается в уведомление и отправляется`() {
        val r = MarkedReads.apply(unread = listOf(d("1"), d("2")), fresh = listOf(d("2")), marked = setOf("1"))
        assertEquals(setOf("1"), r.keep)
        assertEquals(emptySet<String>(), r.drop)
        assertEquals(listOf("2"), r.unread.map { it.peerId })
    }

    @Test fun `после нажатия пришло новое — отметка снимается, переписка снова в уведомлении`() {
        val r = MarkedReads.apply(unread = listOf(d("1")), fresh = listOf(d("1")), marked = setOf("1"))
        assertEquals(setOf("1"), r.drop)
        assertEquals(emptySet<String>(), r.keep)
        assertEquals(listOf("1"), r.unread.map { it.peerId })
    }

    @Test fun `без отметок — ничего не меняется`() {
        val r = MarkedReads.apply(unread = listOf(d("1")), fresh = emptyList(), marked = emptySet())
        assertEquals(listOf("1"), r.unread.map { it.peerId })
        assertEquals(emptySet<String>(), r.keep)
    }
}
