package com.example.vkweb.messages

import com.example.vkweb.messages.ReadMarks.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadMarksTest {

    private class Store(vararg peers: String) : ReadMarks.Pending {
        val set = linkedSetOf(*peers)
        override fun all(): Set<String> = set.toSet()
        override fun remove(peer: String) { set.remove(peer) }
    }

    @Test fun `отложенные «прочитано» отправляются один раз, принятое снимается`() {
        val store = Store("1", "2")
        val marks = ReadMarks(store)
        assertEquals(listOf("1", "2"), marks.takeReads())
        // Уже ждут ответа — повторно не отправляются.
        assertEquals(emptyList<String>(), marks.takeReads())
        assertEquals(Result.READ_OK, marks.onResult(ReadMarks.READ, "1", ok = true))
        assertEquals(setOf("2"), store.all())
        // Не принято — остаётся в хранилище и уйдёт снова в следующий раз.
        assertEquals(Result.READ_FAILED, marks.onResult(ReadMarks.READ, "2", ok = false))
        assertEquals(setOf("2"), store.all())
        assertEquals(listOf("2"), marks.takeReads())
    }

    @Test fun `«непрочитана» — по одной на переписку, пока ждём ответа`() {
        val marks = ReadMarks(Store())
        assertEquals(listOf("5", "6"), marks.takeUnreads(listOf("5", "5", "6")))
        assertEquals(emptyList<String>(), marks.takeUnreads(listOf("5")))
        assertEquals(listOf("5", "6"), marks.unreadsInFlight())
        assertEquals(Result.UNREAD_OK, marks.onResult(ReadMarks.UNREAD, "5", ok = true))
        assertEquals(Result.UNREAD_FAILED, marks.onResult(ReadMarks.UNREAD, "6", ok = false))
        assertFalse(marks.isWaiting)
    }

    @Test fun `ответ не на наши отметки — чужой`() {
        val marks = ReadMarks(Store("1"))
        // Не отправляли — значит, это действие человека с беседой.
        assertEquals(Result.OTHER, marks.onResult(ReadMarks.READ, "1", ok = true))
        marks.takeReads()
        assertEquals(Result.OTHER, marks.onResult("mute", "1", ok = true))
        assertTrue(marks.isWaiting)
        marks.forget()
        assertFalse(marks.isWaiting)
        assertEquals(0, marks.readsWaiting)
    }
}
