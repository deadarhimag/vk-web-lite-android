package com.example.vkweb.outbox

import org.junit.Assert.assertEquals
import org.junit.Test

class OutboxUndoTest {

    private fun item(id: String, at: Long, state: OutboxState = OutboxState.QUEUED) =
        OutboxItem(id = id, peerId = "1", text = "т$id", createdAt = at, state = state)

    @Test fun `удалённое возвращается на своё место`() {
        val a = item("a", 1); val b = item("b", 2); val c = item("c", 3)
        val after = OutboxLogic.discard(listOf(a, b, c), "b")
        assertEquals(listOf("a", "b", "c"), OutboxLogic.undoDiscard(after, b).map { it.id })
    }

    @Test fun `повторное «Вернуть» не дублирует`() {
        val a = item("a", 1)
        assertEquals(listOf(a), OutboxLogic.undoDiscard(listOf(a), a))
    }

    @Test fun `отправлявшееся возвращается сомнительным, а не уходит второй раз само`() {
        val s = item("s", 1, OutboxState.SENDING)
        assertEquals(OutboxState.UNCONFIRMED, OutboxLogic.undoDiscard(emptyList(), s).single().state)
        val f = item("f", 1, OutboxState.FAILED)
        assertEquals(OutboxState.FAILED, OutboxLogic.undoDiscard(emptyList(), f).single().state)
    }
}
