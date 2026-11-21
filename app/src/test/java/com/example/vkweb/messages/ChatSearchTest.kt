package com.example.vkweb.messages

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSearchTest {

    private fun m(id: String, text: String) = ChatMessage(id = id, out = false, text = text, time = "")

    @Test fun `от новых к старым, регистр и ё не важны`() {
        val items = listOf(m("c1", "Встретимся у Ёлки"), m("c2", "ок"), m("c3", "какая елка?"))
        assertEquals(listOf(2, 0), ChatSearch.find(items, "ЕЛК"))
    }

    @Test fun `слишком короткий запрос не ищется`() {
        assertEquals(emptyList<Int>(), ChatSearch.find(listOf(m("c1", "а")), "а"))
    }
}
