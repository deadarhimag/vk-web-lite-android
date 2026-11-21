package com.example.vkweb.people

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeoplePayloadTest {

    private fun payload(listSeen: Boolean, hasMore: Boolean) = PeoplePayload(
        kind = PeopleKind.FRIENDS, key = "/friends", own = true, hasMore = hasMore, total = 0,
        items = emptyList(), listSeen = listSeen,
    )

    @Test fun `одни заявки — список ещё не полный`() {
        // Заявки приходят раньше списка: пустой список без listSeen не должен стирать сохранённое.
        assertFalse(payload(listSeen = false, hasMore = false).isComplete)
    }

    @Test fun `весь список пришёл`() {
        assertTrue(payload(listSeen = true, hasMore = false).isComplete)
        assertFalse(payload(listSeen = true, hasMore = true).isComplete)
    }

    @Test fun `подсказки «Возможно, вы знакомы» разбираются и сохраняются`() {
        val json = JSONObject("""{"type":"people","kind":"friends","key":"/friends","items":[],
            "suggestions":[{"id":10,"name":"Анна","mutual":2},{"id":0,"name":"нет"}]}""")
        val p = PeoplePayload.fromJson(json)!!
        assertEquals(listOf(10L), p.suggestions.map { it.id })
        assertTrue(p.suggestions.all { it.suggested && !it.request })
        assertEquals(listOf(10L), PeoplePayload.fromJson(p.toJson())!!.suggestions.map { it.id })
    }

    @Test fun `у сообществ подсказок нет`() {
        val json = JSONObject("""{"type":"people","kind":"groups","items":[],"suggestions":[{"id":10,"name":"А"}]}""")
        assertTrue(PeoplePayload.fromJson(json)!!.suggestions.isEmpty())
    }
}
